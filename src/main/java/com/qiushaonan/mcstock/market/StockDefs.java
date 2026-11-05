package com.qiushaonan.mcstock.market;

/**
 * 市场与账户的**全部可调数值**，纯 Java、不依赖 Minecraft —— 单测直接拿这些常量算。
 *
 * <p>金额一律用**分**（{@code long}）做内部单位，显示时再换算成金币（1 金币 = 100 分）；
 * 价格显示两位小数就是分位。用整数分是为了避免浮点累计误差影响爆仓判定
 * （×400 杠杆时爆仓线离现价只有 0.11%，一点误差就会算错）。
 */
public final class StockDefs {

    private StockDefs() {
    }

    /** 股票数量。 */
    public static final int COUNT = 3;

    /** 股票 id（资源路径用，顺序 = 索引）。 */
    public static final String[] IDS = {"diamond_mine", "redstone_tech", "nether_trade"};

    /** 基准价（分）：钻石矿业 100.00 / 红石科技 50.00 / 下界贸易 20.00。 */
    public static final long[] BASE_CENTS = {10_000L, 5_000L, 2_000L};

    /** 每跳波动率 σ（高斯增量）：稳健 0.6% / 波动 1.2% / 激进 2.5%。 */
    public static final double[] VOLATILITY = {0.006, 0.012, 0.025};

    /** 风险档：0 稳健 / 1 波动 / 2 激进（用于界面文案）。 */
    public static final int[] RISK = {0, 1, 2};

    // ==================== 行情引擎 ====================

    /**
     * 多少 tick 跳一次价：**40 tick = 2 秒**（20 tps）。
     * <p>⚠️ 这里曾经写成 100（= 5 秒）却在注释里写着「2 秒」，实机对拍才发现 ——
     * 需求是「跳价从 15 秒加快到 2 秒」，所以以 40 为准。
     * 一根阴阳柱 = {@link #CANDLE_TICKS} 跳 = 10 秒。
     */
    public static final int PRICE_TICK_TICKS = 40;
    /**
     * 每跳往基准价回归的比例：**2%/跳**。
     *
     * <p>历史：1.5%（原版）→ 3%（2026-10-06 第一次收紧，想压住趋势交易）→
     * **2%（同日回调：玩家反馈「赚钱太难了」）**。
     *
     * <p>口径仍然不变：回归越强，价格越黏在基准价附近，**追涨杀跌更容易失败**
     * （趋势还没走远就被拉回去）。2% 是「趋势能走一小段、运气好能赚」
     * 与「追消息长期不划算」之间的折中 —— 别调回 3%，也别退回 1.5%
     * （1.5% 时趋势太容易吃满，A 策略会转正）。
     */
    public static final double MEAN_REVERSION = 0.020;
    /** 单跳变动上限（±25%），防极端值。 */
    public static final double MAX_STEP = 0.25;
    /**
     * 每只股票**每跳**抽中一次「行情事件」的概率：0.25%。
     *
     * <p>⚠️ 2026-10-06 由 0.5% 调低到 0.25%，同时把幅度从「2~4σ 的通用高斯跳变」升级成
     * **有名字、有生命周期的事件表**（{@link StockEventTable}，3~8σ，半衰期 20~60 跳）——
     * 概率×跳价周期（{@link #PRICE_TICK_TICKS} tick = 2 秒）算下来约 **13 分钟一次/只**，
     * 玩家一局里能撞见好几次，而不是「一天都看不出来发生过什么」。
     * 之前的 0.5% + 2~4σ 在界面上几乎察觉不到（这正是本次升级的原因）。
     *
     * <p>高波动（{@link MarketRegime#VOLATILE}）状态下乘 {@link #VOLATILE_EVENT_MULTIPLIER}：
     * 「市场一乱，消息也变多」。
     */
    public static final double EVENT_CHANCE = 0.0025;
    /** 高波动状态下的事件概率倍率（市场一乱，消息也变多）。 */
    public static final double VOLATILE_EVENT_MULTIPLIER = 2.0;

    /**
     * **利空事件的幅度整体放大倍率**（利好保持 1.0）：轻微偏空。
     *
     * <p>2026-10-06 加：口径是「不要让玩家容易赚钱」—— 同样多的一堆消息砸下来，
     * 亏的那几发比赚的那几发更狠。⚠️ 它**不是**「必然亏」：利好的条数与强度仍然可观，
     * 只是不再对称。放大在 {@link StockEventTable} 的静态块里对 {@code direction < 0} 的行生效，
     * 表里写的仍是设计值（3~8σ），运行期用的是放大后的值（最大 6.9σ，仍在区间内）。
     */
    public static final double BEARISH_EVENT_SCALE = 1.08;

    /**
     * **插针**（2026-10-06 新增）：每只股票**每跳** {@code 0.03%} 概率来一根单跳急跌，
     * 专打高杠杆 —— 跌幅 {@link #SPIKE_MIN_DROP}~{@link #SPIKE_MAX_DROP}（价格百分比），
     * 随后 {@link #SPIKE_REBOUND_MIN_JUMPS}~{@link #SPIKE_REBOUND_MAX_JUMPS} 跳内
     * **只回补** {@link #SPIKE_REBOUND_MIN}~{@link #SPIKE_REBOUND_MAX}，
     * 所以「等反弹就行」是不成立的（净跌幅仍是原来的 20%~40%）。
     *
     * <p>⚠️ 跌幅取**价格百分比**而不是 σ 倍数：按 σ 缩放的话，稳健股（σ=0.6%）的
     * 8%~15% 相当于 13~25σ，那已经不是「插针」而是「归零」，而且一定撞上 ±25% 护栏；
     * 用百分比则三只股票一视同仁 —— 正好是「专打高杠杆」：×10 以上必爆。
     *
     * <p>护栏一条没放宽：插针的 drift 走同一次 {@code clamp(±MAX_STEP)}，
     * 之后还要过价格区间（基准价的 2%~500%，下限 ≥1 分），**不可能把价格打到 0 以下**。
     */
    public static final double SPIKE_CHANCE = 0.0003;
    public static final double SPIKE_MIN_DROP = 0.08;
    public static final double SPIKE_MAX_DROP = 0.15;
    public static final double SPIKE_REBOUND_MIN = 0.60;
    public static final double SPIKE_REBOUND_MAX = 0.80;
    public static final int SPIKE_REBOUND_MIN_JUMPS = 3;
    public static final int SPIKE_REBOUND_MAX_JUMPS = 5;

    /**
     * 各股票对大盘的 **β（敏感度）**：稳健 0.7 / 波动 1.0 / 激进 1.5。
     *
     * <p>大盘状态的基础漂移按它缩放：{@code drift += bias × β[stock]} ——
     * 牛市里三只股票都更容易涨，但激进股涨得更猛、稳健股更钝。
     * 数值取「低 β 抗跌也抗涨」的教科书口径，和 {@link #VOLATILITY} 的排序一致。
     */
    public static final double[] BETA = {0.7, 1.0, 1.5};

    /**
     * **新闻成簇**（新闻扎堆）：一次事件之后有 30% 概率，在 {@link #CLUSTER_MIN_DELAY}~{@link
     * #CLUSTER_MAX_DELAY} 跳内再抽一条**同类型**事件；连簇最多 {@link #CLUSTER_MAX_CHAIN} 条。
     *
     * <p>上限是必需的：没有它，「30% 再抽一条」会变成几何分布的长尾 ——
     * 玩家会看到某只股票连着十几条新闻、价格被推到护栏上。
     */
    public static final double CLUSTER_CHANCE = 0.30;
    public static final int CLUSTER_MAX_CHAIN = 3;
    public static final int CLUSTER_MIN_DELAY = 1;
    public static final int CLUSTER_MAX_DELAY = 3;

    /** 服务端保留最近几条新闻（快照整份推给客户端，界面显示主新闻 + 4 条历史）。 */
    public static final int NEWS_KEEP = 8;

    /**
     * 读档时最多重放多少跳（防脏存档把加载挂住）。
     *
     * <p>200 万跳 ≈ 46 天连续运行，远超任何真实存档；真撞上了只能说明存档被改过，
     * 那时放弃重放（随机序列从此对不上，但价钱不会读错）。
     */
    public static final long MAX_REPLAY_JUMPS = 2_000_000L;

    // ==================== 负债与自动还债 ====================

    /**
     * 负债时多久检查一次「背包里有没有能卖的东西」：{@code 20 tick = 1 秒}。
     *
     * <p>只在 {@code cash < 0} 时工作（还清就停），见 {@code StockEvents#onServerTick} +
     * {@code StockService#autoSellToRepay}。取 1 秒是因为跳价是 2 秒一跳：
     * 玩家挖到东西之后最多 1 秒就会被卖掉，但又不会每 tick 扫一遍 37 格背包。
     */
    public static final int DEBT_CHECK_TICKS = 20;
    /** 价格区间：基准价的 2% ~ 500%（能跌到几乎归零）。 */
    public static final double FLOOR_RATIO = 0.02;
    public static final double CEIL_RATIO = 5.0;
    /** 保留多少跳的原始价（供阴阳柱取用）。 */
    public static final int HISTORY_TICKS = 300;
    /** 一根阴阳柱 = 多少跳（5 跳 = 10 秒）。 */
    public static final int CANDLE_TICKS = 5;
    /** 界面最多显示多少根阴阳柱。 */
    public static final int CANDLE_MAX = 60;

    // ==================== 账户 / 交易 ====================

    /**
     * 手续费率：仓位价值（notional）的 **0.20%**，开仓平仓各一次（往返 **0.40%**）。
     *
     * <p>⚠️ 2026-10-06 由 0.1% 提高（口径：「不要让玩家容易赚」）。这条是**压制投机的主力**：
     * 盈亏平衡所需的价格变动从 0.2%（原版）变成 **0.4%**，而单跳 σ 只有 0.6%~2.5%，
     * 于是「频繁往返」在数学上仍然是负期望（模拟实测见 {@code EconomySimulationTest}）。
     *
     * <p>历史：0.1%（原版）→ 0.35%（2026-10-06 第一次收紧）→ **0.20%（同日回调，
     * 玩家反馈「赚钱太难」）**。往返 0.40% 仍是原版的两倍，但比 0.70% 好受得多。
     * 回收价（估价 × 1.0）**没动** —— 劳动那条路要保持公平。
     */
    public static final double FEE_RATE = 0.0020;
    /**
     * 爆仓线：浮动亏损达到保证金的这个百分比就强平。
     *
     * <p>⚠️ 历史：50（原版）→ 40（2026-10-06 第一次收紧）→ **45（同日回调：
     * 玩家反馈「赚钱太难」，容错多一点）**。
     * 爆仓距离 = 45% ÷ 杠杆（×2 是 22.5%、×400 是 0.11%）。
     * 公式口径在 {@link PositionMath#liquidationPrice} 与 {@link PositionMath#liquidated} 两处，
     * 界面上的「扛风险」也直接取这个常量（{@code StockScreen#riskText}），别写死 45。
     */
    public static final int MARGIN_CALL_PERCENT = 45;
    public static final int LEVERAGE_MIN = 1;
    public static final int LEVERAGE_MAX = 400;
    public static final int QTY_MIN = 1;
    public static final int QTY_MAX = 100_000;

    /** 价格下限（分）：基准价的 2%，但至少 1 分。 */
    public static long priceFloor(int stock) {
        return Math.max(1L, (long) (BASE_CENTS[stock] * FLOOR_RATIO));
    }

    /** 价格上限（分）。 */
    public static long priceCeil(int stock) {
        return (long) (BASE_CENTS[stock] * CEIL_RATIO);
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** 分 → 金币（向下取整，只用于显示）。 */
    public static long toCoins(long cents) {
        return cents / 100L;
    }
}
