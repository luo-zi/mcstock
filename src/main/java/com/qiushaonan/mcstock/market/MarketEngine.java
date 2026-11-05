package com.qiushaonan.mcstock.market;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 行情引擎：三只股票的价格、历史价、跳价计时、大盘状态、事件冲击与新闻流。
 * **纯 Java**，存盘由 {@code StockMarketData} 负责。
 *
 * <h2>一跳里发生了什么</h2>
 * <ol>
 *   <li><b>大盘状态</b>（{@link MarketRegime}）：到点就按转移表换一个状态，并播一条「大盘」新闻；</li>
 *   <li><b>到期的传闻澄清</b>：传闻到期就注册一条反向冲击（先涨后跌）；</li>
 *   <li><b>待成簇的同类新闻</b>：到期就再抽一条同类型事件（新闻扎堆，最多连 3 条）；</li>
 *   <li><b>三只股票各算一跳</b>：
 *       {@code drift = 均值回归 + 大盘 bias×β + Σ(活着的冲击) + 高斯噪声×σ×大盘倍率×冲击倍率}，
 *       再夹在 ±{@link StockDefs#MAX_STEP} 与价格区间内；</li>
 *   <li>清理衰减完的冲击。</li>
 * </ol>
 *
 * <h2>可复现：读档靠「完整重放」而不是「照着写一遍随机消耗」</h2>
 * <p>随机源用 {@link Random} + 固定种子，种子与已跳次数一起存盘。读档时
 * <b>从种子把 {@code advances} 跳整段重放一遍</b>（{@link #restore}），于是
 * **随机序列、大盘状态、还活着的冲击、新闻流**全都按同一条路径重建出来，
 * 再把存档里的价格 / 历史 / 倒计时盖回去。
 *
 * <p>⚠️ 早先的写法是 {@code burnOneRound()}：手写一份「这一跳消耗几个随机数」的镜像循环，
 * 只推进随机序列、不算价格。那套写法一旦和 {@link #nextPrice} 里真实的抽签顺序写歪一点
 * （比如加了个成簇抽签忘了同步），症状就是「同一个存档读两次跑出不同行情」——
 * 而且**不会报错**。现在整条重放走的就是 {@code advanceAll()} 本身，不存在两份实现。
 * 代价是读档时多跑一次纯内存计算（4 万跳约 10 毫秒量级）。
 */
public final class MarketEngine {

    private final long[] priceCents = new long[StockDefs.COUNT];
    /** 环形历史价：[stock][cursor]。 */
    private final long[][] history = new long[StockDefs.COUNT][StockDefs.HISTORY_TICKS];
    private int histCursor;
    private int ticksToNext = StockDefs.PRICE_TICK_TICKS;
    private long seed;
    private long advances;
    private Random random;

    // ---------- 第二版新增：大盘状态 / 冲击 / 新闻 ----------

    /** 当前大盘状态。 */
    private MarketRegime regime = MarketRegime.CALM;
    /** 当前状态还剩几跳（进入状态时抽好，到 0 就换）。 */
    private int regimeJumpsLeft;
    /** 还活着的冲击（每只受影响股票各一条）。 */
    private final List<MarketShock> shocks = new ArrayList<>();
    /** 新闻流：**最新的在最前**，最多 {@link StockDefs#NEWS_KEEP} 条。 */
    private final List<MarketNews> news = new ArrayList<>();
    /** 待成簇的同类型事件（单槽：两条链重叠时保留新的那条，见 {@code scheduleCluster}）。 */
    private PendingCluster pendingCluster;
    /** 待澄清的传闻。 */
    private final List<PendingClarify> pendingClarifies = new ArrayList<>();
    /** 待回补的**插针**（每只受影响股票一条；3~5 跳内线性回补，不消耗随机数）。 */
    private final List<PendingRebound> pendingRebounds = new ArrayList<>();

    /** 插针新闻的两条中性叙述（随机挑一条；**不写百分比、不写方向词**，见 EventType#SPIKE）。 */
    private static final String[] SPIKE_NEWS_IDS = {"terminal_glitch", "large_sell_order"};

    public MarketEngine(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
        for (int i = 0; i < StockDefs.COUNT; i++) {
            this.priceCents[i] = StockDefs.BASE_CENTS[i];
            // ⚠️ 历史价**故意留 0**（= 还没有数据），不要填 BASE_CENTS：
            // 填基准价的话，世界一开始就有 HISTORY_TICKS 跳「开=高=低=收=基准价」的假数据，
            // 客户端会把它们聚合成几十根「只有 1px 高」的柱子，连成一条横贯全图的直线
            // （实机反馈的那条红线）。0 = 这根没有数据，客户端本来就跳过四价全 0 的柱子。
        }
        // 初始也是「进入一个状态」：持续跳数当场抽好（唯一一处构造期消耗随机数，
        // restore 的重放从同一个起点开始，所以两边永远一致）
        this.regimeJumpsLeft = regime.drawDuration(random);
    }

    public long seed() {
        return seed;
    }

    long advances() {
        return advances;
    }

    public long price(int stock) {
        return priceCents[stock];
    }

    public long[] prices() {
        return Arrays.copyOf(priceCents, priceCents.length);
    }

    /** 距下一次跳价还有多少 tick（界面倒计时用）。 */
    public int ticksToNext() {
        return ticksToNext;
    }

    // ==================== 大盘 / 冲击 / 新闻（界面与快照的数据源） ====================

    /** 当前大盘状态。 */
    public MarketRegime regime() {
        return regime;
    }

    /**
     * **大盘指数**（三只股票等权）：{@code 1000 × mean(price / base)}，返回 **index × 10**
     * （一位小数的整数表示，例如 {@code 10000} = 1000.0，{@code 10324} = 1032.4）。
     *
     * <p>用整数传输：客户端只负责画，不需要浮点；每只股票的 {@code price × 10000 / base}
     * 先各自取整再平均，误差 ≤ 1/10000，肉眼不可见。
     */
    public int indexDeci() {
        long sum = 0L;
        for (int i = 0; i < StockDefs.COUNT; i++) {
            sum += priceCents[i] * 10_000L / StockDefs.BASE_CENTS[i];
        }
        return (int) (sum / StockDefs.COUNT);
    }

    /** 大盘涨跌（**千分比**，相对上一次跳价，与个股涨跌同一个口径）。 */
    public int indexChangePermille() {
        long now = indexDeci();
        long sum = 0L;
        for (int i = 0; i < StockDefs.COUNT; i++) {
            sum += previousPrice(i) * 10_000L / StockDefs.BASE_CENTS[i];
        }
        long prev = sum / StockDefs.COUNT;
        return prev > 0L ? (int) Math.round((now - prev) * 1000.0 / prev) : 0;
    }

    /** 新闻流（**最新在最前**，只读）。 */
    public List<MarketNews> newsFeed() {
        return Collections.unmodifiableList(news);
    }

    /** 还活着的冲击（只读；界面不用，调试与单测要看「衰减到哪一步了」）。 */
    public List<MarketShock> liveShocks() {
        return Collections.unmodifiableList(shocks);
    }

    /** 离下一次大盘状态切换还有几跳（调试命令 / 单测用）。 */
    public int regimeJumpsLeft() {
        return regimeJumpsLeft;
    }

    /** 距下一次跳价还有多少 tick（原样，存盘用）。 */
    public int ticksToNextRaw() {
        return ticksToNext;
    }

    // ==================== 跳价 ====================

    /** 服务端每 tick 调一次；到点就跳价。 */
    public void tick() {
        if (--ticksToNext <= 0) {
            ticksToNext = StockDefs.PRICE_TICK_TICKS;
            advanceAll();
        }
    }

    /** 立刻跳一次价（调试命令、单测用）。 */
    public void advanceAll() {
        long jump = advances + 1;
        advanceRegime(jump);
        fireDueClarifications(jump);
        firePendingCluster(jump);
        for (int i = 0; i < StockDefs.COUNT; i++) {
            priceCents[i] = nextPrice(i, priceCents[i], jump);
        }
        pushHistory();
        advances = jump;
        // 冲击衰减到 3 个半衰期之后就没有意义了（贡献 < 12.5%），清掉免得越攒越多
        shocks.removeIf(shock -> !shock.alive(jump));
        // 插针回补跑完的也清掉（回补区间是 [startJump, startJump+jumps)）
        pendingRebounds.removeIf(rebound -> jump >= rebound.startJump() + rebound.jumps());
    }

    /**
     * 一跳的价格。
     *
     * @param jump 这一跳的序号（写进冲击的 {@code startJump}，触发那一跳 age = 0、当场生效）
     */
    private long nextPrice(int stock, long current, long jump) {
        double base = StockDefs.BASE_CENTS[stock];
        double sigma = StockDefs.VOLATILITY[stock];

        // ⚠️⚠️ 随机消耗顺序就是协议：① 高斯噪声 → ② 事件抽签 → ③ 插针抽签 → ④（命中时）成簇抽签。
        //     读档重放（restore）走的是同一个 advanceAll，所以这里**不要**把抽签挪到别处去。
        double noise = random.nextGaussian();
        int eventIndex = drawEvent(stock);
        if (eventIndex >= 0) {
            registerEvent(stock, eventIndex, jump, 1, true);
        }
        double spike = drawSpike(stock, jump, sigma);

        // 均值回归 + 大盘基础漂移（按 β 缩放）
        double drift = StockDefs.MEAN_REVERSION * (base - current) / base
                + regime.bias() * StockDefs.BETA[stock];
        // 插针：命中那一跳当场砸下去（负 drift），回补由 pendingRebounds 在后续 3~5 跳摊平
        drift += spike;
        drift += reboundDrift(stock, jump);
        // 叠加所有活着的冲击（含本跳刚注册的那条）：贡献按 2^(-age/半衰期) 衰减
        double sigmaScale = 1.0;
        for (MarketShock shock : shocks) {
            if (shock.stock() != stock || !shock.alive(jump)) {
                continue;
            }
            drift += shock.driftUnits(jump) * sigma;
            sigmaScale = Math.max(sigmaScale, shock.sigmaScale(jump));
        }
        // 噪声：σ × 大盘波动倍率 × 冲击波动倍率（取最大的那条，不叠加 ——
        // 同时来三条消息时波动放大三倍会把价格打到护栏上）
        drift += noise * sigma * regime.volMultiplier() * sigmaScale;

        // ⚠️⚠️ 护栏顺序不能反：**先**把所有贡献（均值回归 / 大盘 bias / 插针与回补 / 全部冲击 / 噪声）
        //     并进 drift，**再**统一夹 ±MAX_STEP，最后才写回价格。事件与插针都可以很大
        //     （3~8σ 的跳变、-8%~-15% 的针），但这里一步都不放宽。
        drift = Math.max(-StockDefs.MAX_STEP, Math.min(StockDefs.MAX_STEP, drift));
        // ⚠️ 夹取必须落到**取整之后的价格**上，光夹 drift 不够：
        //    「±25%」约束的是成交价（整数分），而 round(price × 0.75) 可能比 0.75×price
        //    再低 0.5 分 —— 851 分 × 0.75 = 638.25 → round = 638，比值 0.74971 < 0.75。
        //    基线用例 singleStepIsCapped 抓的正是这个（事件冲击让护栏真的开始生效之后，
        //    这个半分误差就必然出现）。这里把取整后的价格夹回 [ceil(p×0.75), floor(p×1.25)]，
        //    「单跳 ±25%」才是一条**精确成立**的保证。
        long lo = (long) Math.ceil(current * (1.0 - StockDefs.MAX_STEP));
        long hi = (long) Math.floor(current * (1.0 + StockDefs.MAX_STEP));
        long next = Math.max(lo, Math.min(hi, Math.round(current * (1.0 + drift))));
        return Math.max(StockDefs.priceFloor(stock), Math.min(StockDefs.priceCeil(stock), next));
    }

    /** 这一跳的事件抽签（**唯一入口**）：高波动状态下概率翻倍。 */
    private int drawEvent(int stock) {
        double chance = StockDefs.EVENT_CHANCE
                * (regime == MarketRegime.VOLATILE ? StockDefs.VOLATILE_EVENT_MULTIPLIER : 1.0);
        if (random.nextDouble() >= chance) {
            return -1;
        }
        return StockEventTable.pick(stock, random.nextDouble());
    }

    /**
     * **插针抽签**（{@link StockDefs#SPIKE_CHANCE} 每跳 0.03%，逐股票独立）。
     *
     * <p>命中就：当场给一个 {@code -8%~-15%} 的 drift（**专门打高杠杆**：×10 以上必爆），
     * 排一条 3~5 跳内**只回补 60%~80%** 的线性回补（净跌幅仍剩 20%~40%，
     * 「等反弹就行」不成立），并往新闻流写一条**中性叙述**。
     *
     * <p>⚠️ 跌幅取**价格百分比**而不是 σ 倍数，理由见 {@link StockDefs#SPIKE_CHANCE}。
     * 护栏一条没放宽：返回值只是 drift 的一项，之后照样过 {@code clamp(±MAX_STEP)}
     * 与价格区间（下限 ≥1 分），**不可能把价格打到 0 以下**。
     *
     * <p>⚠️ 这里是**新的随机消耗点**，位置固定在「事件抽签之后、算 drift 之前」，
     * 不要在别处再抽一次；读档重放走同一个 {@code advanceAll}，自动对齐。
     */
    private double drawSpike(int stock, long jump, double sigma) {
        if (random.nextDouble() >= StockDefs.SPIKE_CHANCE) {
            return 0.0;
        }
        double drop = StockDefs.SPIKE_MIN_DROP
                + random.nextDouble() * (StockDefs.SPIKE_MAX_DROP - StockDefs.SPIKE_MIN_DROP);
        int span = StockDefs.SPIKE_REBOUND_MAX_JUMPS - StockDefs.SPIKE_REBOUND_MIN_JUMPS + 1;
        int reboundJumps = StockDefs.SPIKE_REBOUND_MIN_JUMPS + random.nextInt(span);
        double factor = StockDefs.SPIKE_REBOUND_MIN
                + random.nextDouble() * (StockDefs.SPIKE_REBOUND_MAX - StockDefs.SPIKE_REBOUND_MIN);
        String id = SPIKE_NEWS_IDS[random.nextInt(SPIKE_NEWS_IDS.length)];

        pendingRebounds.add(new PendingRebound(stock, jump + 1, reboundJumps, drop * factor / reboundJumps));
        pushNews(new MarketNews(stock, id, "gui.mcstock.spike." + id, EventType.SPIKE, -1,
                (int) Math.round(-drop * 1000.0), jump, 0L, false, 0));
        return -drop;
    }

    /** 这一跳该股票身上「插针回补」的 drift（正的，按 3~5 跳线性摊平）。 */
    private double reboundDrift(int stock, long jump) {
        double total = 0.0;
        for (PendingRebound rebound : pendingRebounds) {
            if (rebound.stock() != stock) {
                continue;
            }
            long age = jump - rebound.startJump();
            if (age >= 0 && age < rebound.jumps()) {
                total += rebound.perJumpDrift();
            }
        }
        return total;
    }

    // ==================== 事件 / 冲击 / 新闻的内部装配 ====================

    /**
     * 注册一条事件：给每个受影响的目标挂一条冲击、写一条新闻，并处理传闻排期与成簇抽签。
     *
     * @param homeStock   事件属于哪家公司（表里那一行的股票下标）
     * @param eventIndex  表内行号
     * @param jump        触发跳数
     * @param chainDepth  这是连簇里的第几条（1 = 自然触发）
     * @param rollCluster 是否参与成簇抽签。⚠️ 调试触发（{@link #debugTriggerEvent}）传 false：
     *                    不然它会额外消耗随机数，单测就没法拿它和「同种子的无事件基线」逐跳对拍
     */
    private void registerEvent(int homeStock, int eventIndex, long jump, int chainDepth, boolean rollCluster) {
        StockEventTable.EventDef def = StockEventTable.get(homeStock, eventIndex);
        if (def == null) {
            return;
        }
        for (StockEventTable.Target target : def.targets()) {
            // 权重可以是负数 = 反向外溢（资金避险这类）：符号在这里折进 direction，
            // 幅度永远取正，纯数学部分（MarketShock）不需要关心正负号从哪来
            double signed = def.magnitude() * target.weight();
            int direction = signed >= 0.0 ? def.direction() : -def.direction();
            shocks.add(new MarketShock(target.stock(), def.id(), direction, jump, def.halfLife(),
                    Math.abs(signed), def.sigmaMultiple(), false));
        }
        MarketNews item = new MarketNews(homeStock, def.id(), def.nameKey(), def.type(), def.direction(),
                def.permille(homeStock), jump,
                def.isRumor() ? jump + def.clarifyAfter() : 0L, false, chainDepth);
        pushNews(item);
        if (def.isRumor()) {
            pendingClarifies.add(new PendingClarify(item, def, homeStock, jump + def.clarifyAfter(),
                    def.clarifyFactor()));
        }
        if (rollCluster) {
            scheduleCluster(def.type(), jump, chainDepth);
        }
    }

    /**
     * 成簇抽签：{@link StockDefs#CLUSTER_CHANCE} 的概率在 1~3 跳内再来一条**同类型**事件。
     *
     * <p>连簇深度到 {@link StockDefs#CLUSTER_MAX_CHAIN} 就停 —— 没有这道闸，
     * 「30% 再抽一条」是几何分布，会长出十几条的尾巴，把价格一路推到护栏上。
     */
    private void scheduleCluster(EventType type, long jump, int chainDepth) {
        if (chainDepth >= StockDefs.CLUSTER_MAX_CHAIN || type == EventType.MARKET) {
            return;
        }
        if (random.nextDouble() >= StockDefs.CLUSTER_CHANCE) {
            return;
        }
        int span = StockDefs.CLUSTER_MAX_DELAY - StockDefs.CLUSTER_MIN_DELAY + 1;
        int delay = StockDefs.CLUSTER_MIN_DELAY + random.nextInt(span);
        // 单槽：两条簇撞在一起时保留新的那条（少一条跟风新闻，不会出错）
        pendingCluster = new PendingCluster(type, jump + delay, chainDepth + 1);
    }

    /** 到点的成簇事件：从**同类型**里均匀抽一条（不看股票，跨公司的跟风新闻更真实）。 */
    private void firePendingCluster(long jump) {
        PendingCluster pending = pendingCluster;
        if (pending == null || jump < pending.atJump()) {
            return;
        }
        pendingCluster = null;
        int count = StockEventTable.countOfType(pending.type());
        if (count <= 0) {
            return;
        }
        int[] flat = StockEventTable.ofType(pending.type(), random.nextInt(count));
        if (flat != null) {
            registerEvent(flat[0], flat[1], jump, pending.depth(), true);
        }
    }

    /**
     * 到点的传闻澄清：同一条传闻的目标映射照旧，但**方向反过来、幅度打 0.6~0.8 折**，
     * 并把原来那条新闻标记成「已澄清」（买预期、卖事实）。
     */
    private void fireDueClarifications(long jump) {
        for (Iterator<PendingClarify> it = pendingClarifies.iterator(); it.hasNext(); ) {
            PendingClarify pending = it.next();
            if (jump < pending.atJump()) {
                continue;
            }
            it.remove();
            StockEventTable.EventDef def = pending.def();
            for (StockEventTable.Target target : def.targets()) {
                double signed = def.magnitude() * target.weight() * pending.factor();
                int direction = signed >= 0.0 ? -def.direction() : def.direction();
                shocks.add(new MarketShock(target.stock(), def.id(), direction, jump, def.halfLife(),
                        Math.abs(signed), def.sigmaMultiple(), true));
            }
            int index = news.indexOf(pending.item());
            if (index >= 0) {
                news.set(index, news.get(index).asClarified());
            }
        }
    }

    /** 大盘状态推进：到点按转移表换状态、抽新的持续跳数，并播一条「大盘」新闻。 */
    private void advanceRegime(long jump) {
        if (--regimeJumpsLeft > 0) {
            return;
        }
        regime = regime.next(random.nextDouble());
        regimeJumpsLeft = regime.drawDuration(random);
        // 播报用**中性叙述句**（「市场交投转趋温和」），不是「牛市启动」——
        // 界面不许替玩家下买卖判断（口径见 MarketRegime#nameKey 的注释）
        pushNews(new MarketNews(MarketNews.MARKET_STOCK, regime.id(), regime.newsKey(), EventType.MARKET,
                regime.bias() >= 0.0 ? 1 : -1, (int) Math.round(regime.bias() * 1000.0),
                jump, 0L, false, 0));
    }

    private void pushNews(MarketNews item) {
        news.add(0, item);
        while (news.size() > StockDefs.NEWS_KEEP) {
            news.remove(news.size() - 1);
        }
    }

    /** 成簇的待办：什么类型、几跳后、连到第几条。 */
    private record PendingCluster(EventType type, long atJump, int depth) {
    }

    /** 传闻澄清的待办：澄清哪条新闻、按哪条定义、几跳后、回吐几成。 */
    private record PendingClarify(MarketNews item, StockEventTable.EventDef def, int homeStock,
                                  long atJump, double factor) {
    }

    /**
     * 插针的回补待办：从 {@code startJump} 起连续 {@code jumps} 跳，每跳补
     * {@code perJumpDrift}（正数）。**不消耗随机数** —— 回补的幅度与节奏在插针那一刻就定好了。
     */
    private record PendingRebound(int stock, long startJump, int jumps, double perJumpDrift) {
    }

    // ==================== 历史价与阴阳柱 ====================

    public long[] historyInOrder(int stock) {
        long[] out = new long[StockDefs.HISTORY_TICKS];
        for (int i = 0; i < StockDefs.HISTORY_TICKS; i++) {
            out[i] = history[stock][(histCursor + i) % StockDefs.HISTORY_TICKS];
        }
        return out;
    }

    /** 上一次跳价时的价格（算涨跌百分比用）。 */
    public long previousPrice(int stock) {
        int idx = (histCursor + StockDefs.HISTORY_TICKS - 2) % StockDefs.HISTORY_TICKS;
        long prev = history[stock][idx];
        return prev > 0 ? prev : priceCents[stock];
    }

    private void pushHistory() {
        for (int i = 0; i < StockDefs.COUNT; i++) {
            history[i][histCursor] = priceCents[i];
        }
        histCursor = (histCursor + 1) % StockDefs.HISTORY_TICKS;
    }

    /**
     * 把历史价聚合成阴阳柱，返回 {@code [stock][candle * 4 + (0=开,1=高,2=低,3=收)]}。
     * <p>不足 {@code candleCount} 根时前面补 0（0 = 这根没有数据，客户端跳过不画）。
     *
     * <p>⚠️ 世界刚开时历史里有一大段 0（= 还没跳过价）。分块要**跳过每块开头的 0**：
     * 否则跨在「0 区 / 真实区」分界上的那一根会取到 {@code open = 0, low = 0}，
     * 它不算「四价全 0」所以不会被客户端跳过，却会把自适应量程一路拉到 0 ——
     * 实机上就是一根顶天立地的巨柱把真实柱子压成一条缝。
     * 只要跳价次数不是 {@link StockDefs#CANDLE_TICKS} 的整数倍就会踩到（几乎总是）。
     */
    public int[][] candles(int candleCount) {
        int count = Math.min(StockDefs.CANDLE_MAX, Math.max(1, candleCount));
        int[][] out = new int[StockDefs.COUNT][count * 4];
        if (advances <= 0) {
            return out;
        }
        // 绝对分箱：第 b 根柱 = 第 [b*CANDLE_TICKS, (b+1)*CANDLE_TICKS) 次跳价。
        // ⚠️⚠️ 绝对不能用「相对历史末尾」的分箱（曾经的写法：end = hist.length - c * CANDLE_TICKS）——
        // 那样每跳一次价，所有柱子的 5 跳窗口都会整体后移 1 跳，于是**已完成的柱子 open/close 每 2 秒全变一次**，
        // 客户端把服务端推来的数据忠实地画出来，玩家看到的就是「每次更新所有柱子一起乱跳」
        // （实机反馈原话：「不是抖动，是每次更新的乱跳」；实测每份快照约 39 根柱子发生像素变化）。
        // 绝对分箱之后，一根柱子的 tick 区间一旦写满就再也不会被重算。
        long lastBin = (advances - 1) / StockDefs.CANDLE_TICKS;
        for (int s = 0; s < StockDefs.COUNT; s++) {
            long[] hist = historyInOrder(s);
            int base = StockDefs.HISTORY_TICKS - (int) advances;   // 有序历史里「第 0 次跳价」的下标
            for (int k = 0; k < count; k++) {
                long bin = lastBin - k;
                if (bin < 0) {
                    break;
                }
                long fromTick = Math.max(bin * StockDefs.CANDLE_TICKS, advances - StockDefs.HISTORY_TICKS);
                long toTick = Math.min((bin + 1) * StockDefs.CANDLE_TICKS, advances) - 1;
                if (toTick < fromTick) {
                    continue;   // 历史窗口还没覆盖到这一根：留全 0，客户端跳过
                }
                long open = hist[(int) (base + fromTick)];
                long close = hist[(int) (base + toTick)];
                long high = open;
                long low = open;
                for (long t = fromTick; t <= toTick; t++) {
                    long v = hist[(int) (base + t)];
                    if (v > 0) {
                        high = Math.max(high, v);
                        low = Math.min(low, v);
                    }
                }
                int slot = (count - 1 - k) * 4;
                out[s][slot] = (int) open;
                out[s][slot + 1] = (int) high;
                out[s][slot + 2] = (int) low;
                out[s][slot + 3] = (int) close;
            }
        }
        return out;
    }

    // ==================== 存盘 ====================

    /** 存盘快照（分）。历史价按环形顺序导出，读档时按同一顺序灌回去。 */
    public long[] snapshotPrices() {
        return Arrays.copyOf(priceCents, priceCents.length);
    }

    /** 导出整段历史（按时间顺序），长度 = COUNT × HISTORY_TICKS。 */
    public long[] snapshotHistory() {
        long[] out = new long[StockDefs.COUNT * StockDefs.HISTORY_TICKS];
        for (int s = 0; s < StockDefs.COUNT; s++) {
            long[] ordered = historyInOrder(s);
            System.arraycopy(ordered, 0, out, s * StockDefs.HISTORY_TICKS, StockDefs.HISTORY_TICKS);
        }
        return out;
    }

    /** 调试命令用：把某只股票的价格（和它最近的历史）直接设成给定值。 */
    public void debugSetPrice(int stock, long priceCents) {
        long clamped = Math.max(StockDefs.priceFloor(stock), Math.min(StockDefs.priceCeil(stock), priceCents));
        priceCents_force(stock, clamped);
    }

    private void priceCents_force(int stock, long value) {
        priceCents[stock] = value;
        // 历史里最近的一段也一起改，免得图上出现一个不可能的价格跳变
        history[stock][(histCursor + StockDefs.HISTORY_TICKS - 1) % StockDefs.HISTORY_TICKS] = value;
    }

    /**
     * 调试 / 单测：**强制**触发一条事件（走完整的注册流程：冲击 + 新闻 + 传闻排期）。
     *
     * <p>⚠️ 它**不消耗随机数、也不参与成簇抽签**。这是刻意的：单测要拿「强制触发过的引擎」和
     * 「同种子的无事件基线」逐跳对拍（两者的噪声序列必须一模一样），
     * 一旦这里偷偷抽一次签，对拍就失去意义了。
     *
     * @param stock      表里那一行的股票下标
     * @param eventIndex 行号
     */
    public void debugTriggerEvent(int stock, int eventIndex) {
        if (StockEventTable.get(stock, eventIndex) == null) {
            return;
        }
        registerEvent(stock, eventIndex, advances + 1, 1, false);
    }

    /** 调试 / 单测：直接切大盘状态（不抽签、不消耗随机数；持续跳数设成「很久以后」）。 */
    public void debugSetRegime(MarketRegime next) {
        if (next == null) {
            return;
        }
        this.regime = next;
        this.regimeJumpsLeft = Integer.MAX_VALUE / 2;
    }

    /**
     * 从存档恢复：**从种子完整重放 {@code advances} 跳**，再把存档里的价格 / 历史 / 倒计时盖回去。
     *
     * <p>重放重建的不只是随机序列，还有大盘状态、还活着的冲击与新闻流 ——
     * 也就是说「当前市场环境」在存盘里不需要单独存：它就是 {@code (seed, advances)} 的确定函数。
     * 这也是为什么**没有**把冲击/新闻写进 NBT：两份真相迟早会对不上（见类注释）。
     *
     * <p>⚠️ 重放跳数夹在 {@link StockDefs#MAX_REPLAY_JUMPS}：脏存档（被改过的 advances）
     * 不该把服务器加载挂住。
     */
    public static MarketEngine restore(long seed, long advances, int ticksToNext, long[] prices, long[] historyFlat) {
        MarketEngine engine = new MarketEngine(seed);
        long replay = Math.min(Math.max(0L, advances), StockDefs.MAX_REPLAY_JUMPS);
        for (long i = 0; i < replay; i++) {
            engine.advanceAll();
        }
        if (prices != null && prices.length == StockDefs.COUNT) {
            System.arraycopy(prices, 0, engine.priceCents, 0, StockDefs.COUNT);
        }
        if (historyFlat != null && historyFlat.length == StockDefs.COUNT * StockDefs.HISTORY_TICKS) {
            for (int s = 0; s < StockDefs.COUNT; s++) {
                System.arraycopy(historyFlat, s * StockDefs.HISTORY_TICKS, engine.history[s], 0, StockDefs.HISTORY_TICKS);
            }
        }
        engine.histCursor = 0;
        engine.ticksToNext = ticksToNext > 0 ? ticksToNext : StockDefs.PRICE_TICK_TICKS;
        // ⚠️ 加固（2026-10-06）：脏存档的价格字段可能被改成 0 / 负数 / 离谱的大数，
        //    这里统一夹回合法区间（≥ 下限、≤ 上限），别让「读一份被改过的存档」
        //    把之后每一跳都算在异常区间里。正常存档本来就落在区间内，对它是恒等变换。
        for (int s = 0; s < StockDefs.COUNT; s++) {
            long low = StockDefs.priceFloor(s);
            long high = StockDefs.priceCeil(s);
            engine.priceCents[s] = Math.min(high, Math.max(low, engine.priceCents[s]));
            long[] row = engine.history[s];
            for (int i = 0; i < row.length; i++) {
                // ⚠️ 历史里的 **0 是「空格子」的合法标记**（K 线未填满的那一段），
                //    不能当成脏数据补成价格 —— 补了会在图上画出一根掉到地板价的假柱子。
                //    所以只处理明显越界的：负数 → 空格子，超过上限 → 夹回上限。
                if (row[i] < 0) {
                    row[i] = 0;
                } else if (row[i] > high) {
                    row[i] = high;
                }
            }
        }
        // 跳数一律以存档为准：脏存档下重放被截断时也不能谎报进度
        engine.advances = Math.max(0L, advances);
        return engine;
    }
}
