package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **事件系统在引擎里的行为**单测：方向、波动放大、传闻回吐、成簇上限、可复现、统计与大盘指数。
 *
 * <p>手法上有两条是刻意的：
 * <ol>
 *   <li><b>强制触发</b>（{@link MarketEngine#debugTriggerEvent}）不消耗随机数、也不参与成簇抽签，
 *       所以可以拿「强制触发过的引擎」和「同种子的无事件基线」**逐跳对拍** —— 两者的噪声序列
 *       完全一样，差出来的那一截只可能来自事件本身；</li>
 *   <li>方向类断言取**多个试验的和**（同一颗种子、不同起点），而不是只看一次实现：
 *       「期望价格高于基线」本来就是统计口径，单次实现可能被噪声盖住（事件冲击同时会把
 *       那一跳的 σ 放大 1.5~3 倍，噪声也跟着变大）。</li>
 * </ol>
 */
class MarketEventsTest {

    private static final long SEED = 20260101L;

    /** 红石科技（波动股，σ=1.2%）的「红石专利获批」：利好、5σ、半衰期 40。 */
    private static final int UP_STOCK = 1;
    private static final String UP_ID = "patent_granted";
    /** 同股票的「电路短路事故」：利空、5σ、半衰期 25。 */
    private static final String DOWN_ID = "circuit_short";

    private static int indexOf(int stock, String id) {
        for (int i = 0; i < StockEventTable.size(stock); i++) {
            if (StockEventTable.id(stock, i).equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("事件表里没有 " + id);
    }

    // ==================== 可复现 ====================

    @Test
    void sameSeedGivesTheSameEventSequence() {
        MarketEngine a = new MarketEngine(SEED);
        MarketEngine b = new MarketEngine(SEED);
        for (int i = 0; i < 3_000; i++) {
            a.advanceAll();
            b.advanceAll();
        }
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            assertEquals(a.price(stock), b.price(stock), "同一颗种子必须给出同一串价格");
        }
        assertEquals(a.regime(), b.regime(), "大盘状态也要一致");
        assertEquals(newsIds(a), newsIds(b), "新闻流也要一致");
    }

    private static List<String> newsIds(MarketEngine engine) {
        List<String> out = new ArrayList<>();
        for (MarketNews item : engine.newsFeed()) {
            out.add(item.stock() + ":" + item.id() + "@" + item.jump());
        }
        return out;
    }

    /**
     * 读档：价格照抄存档，**派生状态（大盘 / 冲击 / 新闻）由「从种子重放」重建** ——
     * 所以读档之后继续跑，两边的价格与新闻必须逐跳一致。
     */
    @Test
    void restoreReplaysTheWholeDerivedState() {
        MarketEngine original = new MarketEngine(SEED);
        for (int i = 0; i < 1_500; i++) {
            original.advanceAll();
        }
        MarketEngine restored = MarketEngine.restore(SEED, original.advances(), original.ticksToNextRaw(),
                original.snapshotPrices(), original.snapshotHistory());

        assertEquals(original.regime(), restored.regime(), "大盘状态必须被重放出来");
        assertEquals(newsIds(original), newsIds(restored), "新闻流必须被重放出来");
        for (int i = 0; i < 500; i++) {
            original.advanceAll();
            restored.advanceAll();
            for (int stock = 0; stock < StockDefs.COUNT; stock++) {
                assertEquals(original.price(stock), restored.price(stock),
                        "读档后第 " + i + " 跳的价格必须一致（随机序列对齐了）");
            }
        }
    }

    // ==================== 方向 ====================

    @Test
    void goodNewsPushesPriceAboveBaselineAndBadNewsBelow() {
        assertDirection(UP_ID, +1);
        assertDirection(DOWN_ID, -1);
    }

    private void assertDirection(String eventId, int expectedSign) {
        // 窗口取 10 跳、样本 100 组：**冲击的「急动」部分在头几跳最干净**，
        // 拖得越久噪声（被放大 1.5~3 倍的 σ）和均值回归（现在 3%/跳）越容易把方向糊掉。
        // 实测（2026-10-06 的经济参数下、200 组）：+5σ 的专利事件 10 跳内 96% 站对方向、
        // −5.75σ 的短路事故 95%；窗口放到 30 跳就降到 77% / 86%。所以按 10 跳窗口断言。
        int trials = 100;
        int window = 10;
        int wins = 0;
        double sum = 0.0;
        for (int trial = 0; trial < trials; trial++) {
            long seed = SEED + trial * 7919L;
            MarketEngine shocked = new MarketEngine(seed);
            MarketEngine baseline = new MarketEngine(seed);
            int warmup = 5 + trial % 30;
            for (int i = 0; i < warmup; i++) {
                shocked.advanceAll();
                baseline.advanceAll();
            }
            shocked.debugTriggerEvent(UP_STOCK, indexOf(UP_STOCK, eventId));
            for (int i = 0; i < window; i++) {
                shocked.advanceAll();
                baseline.advanceAll();
            }
            double diff = (shocked.price(UP_STOCK) - baseline.price(UP_STOCK)) * expectedSign;
            if (diff > 0) {
                wins++;
            }
            sum += diff;
        }
        assertTrue(sum > 0, eventId + "：" + window + " 跳之后的累计差值必须站在事件方向上，实际 " + sum);
        assertTrue(wins >= trials * 0.8, eventId + "：" + trials
                + " 组里至少八成要站在事件方向上（该量级的事件实测 95%+），实际 " + wins);
    }

    /**
     * 外溢：负权重 = **反方向**外溢。
     *
     * <p>先做**结构性**断言（不看价格、不受噪声影响）：{@code portal_congestion} 是
     * 「下界贸易利空、钻石矿业权重 −0.25」，注册出来的两条冲击里，下界贸易那条是利空、
     * 钻石矿业那条必须是**利好**（资金避险）；再做一次多试验的统计断言，
     * 确认这股避险资金真的把钻石矿业托起来了。
     *
     * <p>⚠️ 幅度按 {@code StockDefs.BEARISH_EVENT_SCALE}（1.15）放大过：表里写 4σ，
     * 生效值是 4.6σ，外溢 = 0.25 × 4.6 = 1.15σ。
     */
    @Test
    void spilloverCanFlipDirectionForTheOtherStock() {
        int home = 2;
        int index = indexOf(home, "portal_congestion");

        MarketEngine engine = new MarketEngine(SEED);
        engine.debugTriggerEvent(home, index);
        engine.advanceAll();
        MarketShock homeShock = engine.liveShocks().stream()
                .filter(shock -> shock.stock() == home).findFirst().orElse(null);
        MarketShock spill = engine.liveShocks().stream()
                .filter(shock -> shock.stock() == 0).findFirst().orElse(null);
        assertNotNull(homeShock, "下界贸易那条冲击必须注册出来");
        assertNotNull(spill, "外溢到钻石矿业的那条冲击必须注册出来");
        assertEquals(-1, homeShock.direction(), "拥堵对下界贸易本身是利空");
        assertEquals(+1, spill.direction(), "资金避险：同一条新闻对钻石矿业是利好（权重为负 → 方向翻转）");
        assertEquals(4.0 * StockDefs.BEARISH_EVENT_SCALE, homeShock.magnitude(), 1e-9);
        assertEquals(1.0 * StockDefs.BEARISH_EVENT_SCALE, spill.magnitude(), 1e-9,
                "外溢幅度 = 0.25 × 4σ × 利空放大 1.15");

        // 统计：**200 组**不同种子、只看触发后的两跳。
        // 为什么这么安排：外溢只有 +1σ（钻石矿业 σ=0.6% ≈ 10 分），而冲击同时把它的 σ 放大到
        // 2.0 倍 —— 单跳的噪声差（±10 分）和这点位移一样大。样本一多，
        // 信号（200 × 10 分 = 2000 分）就把噪声（标准差 ≈ 140 分）远远甩开，
        // 这才是「期望价格更高」的正确测法；窗口取两跳是为了少累一点放大的噪声。
        int trials = 200;
        int wins = 0;
        double sum = 0.0;
        for (int trial = 0; trial < trials; trial++) {
            MarketEngine shocked = new MarketEngine(SEED + trial * 104_729L);
            MarketEngine baseline = new MarketEngine(SEED + trial * 104_729L);
            shocked.debugTriggerEvent(home, index);
            for (int i = 0; i < 2; i++) {
                shocked.advanceAll();
                baseline.advanceAll();
            }
            double diff = shocked.price(0) - baseline.price(0);
            if (diff > 0) {
                wins++;
            }
            sum += diff;
        }
        assertTrue(sum > 0, "外溢总体上必须托起钻石矿业，实际累计差 " + sum);
        assertTrue(wins >= trials * 0.55, "200 组里至少 55% 该是正的，实际 " + wins);
    }

    // ==================== 波动放大 ====================

    @Test
    void shocksAmplifyVolatility() {
        // 两条链同种子：一条持续被冲击覆盖（每次触发都强制挂上「矿洞塌方」，σ 倍率 2.0），
        // 另一条什么都不挂。噪声序列一样，所以收益率的离散度之比就是 σ 放大倍数。
        MarketEngine shocked = new MarketEngine(SEED);
        MarketEngine calm = new MarketEngine(SEED);
        int index = indexOf(0, "mine_collapse");
        List<Double> shockedReturns = new ArrayList<>();
        List<Double> calmReturns = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            if (i % 20 == 0) {
                shocked.debugTriggerEvent(0, index);      // halfLife = 30 → 一直有活的冲击
            }
            long beforeA = shocked.price(0);
            long beforeB = calm.price(0);
            shocked.advanceAll();
            calm.advanceAll();
            if (i > 5) {
                shockedReturns.add(shocked.price(0) / (double) beforeA - 1.0);
                calmReturns.add(calm.price(0) / (double) beforeB - 1.0);
            }
        }
        double ratio = stdev(shockedReturns) / stdev(calmReturns);
        assertTrue(ratio > 1.5, "冲击存续期内波动必须显著放大，实测比值 " + ratio);
    }

    private static double stdev(List<Double> values) {
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.size();
        double sum = 0.0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    // ==================== 传闻：先涨后跌 ====================

    /**
     * 传闻：**先涨后跌**（买预期、卖事实）。
     *
     * <p>为什么用「多组试验求和」而不是一次实现：冲击会把该股票的 σ 放大到 2.8 倍，
     * 于是冲击引擎与基线的**噪声差**也是一条随机游走（几十跳之后能到 ±40%），
     * 单看一次实现完全可能被它盖住。取 20 组不同种子求和，看的才是「期望」。
     */
    @Test
    void rumoursRiseFirstAndGiveItBackOnClarification() {
        int home = 2;                                   // 下界贸易（激进股，幅度最明显）
        int index = indexOf(home, "route_rumor");        // +6.5σ，20 跳后澄清，回吐 70%
        StockEventTable.EventDef def = StockEventTable.get(home, index);
        int clarifyAfter = def.clarifyAfter();

        int trials = 20;
        double peakSum = 0.0;
        double afterSum = 0.0;
        int peaksUp = 0;
        int gaveBack = 0;
        for (int trial = 0; trial < trials; trial++) {
            long seed = SEED + trial * 104_729L;
            MarketEngine shocked = new MarketEngine(seed);
            MarketEngine baseline = new MarketEngine(seed);
            shocked.debugTriggerEvent(home, index);

            double peak = 0.0;
            for (int i = 0; i < clarifyAfter; i++) {
                shocked.advanceAll();
                baseline.advanceAll();
                peak = Math.max(peak, shocked.price(home) - baseline.price(home));
            }
            for (int i = 0; i < 3 * def.halfLife(); i++) {
                shocked.advanceAll();
                baseline.advanceAll();
            }
            double after = shocked.price(home) - baseline.price(home);
            peakSum += peak;
            afterSum += after;
            if (peak > 0) {
                peaksUp++;
            }
            if (after < peak) {
                gaveBack++;
            }
        }

        // ① 先涨：澄清之前的差值必须是正的（传闻本身是 +6.5σ 的利好）
        assertTrue(peakSum > 0, "传闻必须先把价格推上去，20 组累计峰值差 " + peakSum);
        assertTrue(peaksUp >= trials * 0.75, "至少四分之三的试验里峰值差为正，实际 " + peaksUp + "/" + trials);
        // ② 澄清后回吐：末了的差值必须明显小于峰值差
        assertTrue(afterSum < peakSum * 0.5,
                "澄清后必须回吐大半：峰值差合计 " + peakSum + "，末了差合计 " + afterSum);
        assertEquals(trials, gaveBack, "每一组都该出现「末了差 < 峰值差」");
        // ③ 但也不该被反手打到起点下方很深（回吐 70%，理论上还留 30% 的净影响）
        assertTrue(afterSum > -peakSum * 0.5,
                "回吐不该把价格打到起点下方很深：峰值差合计 " + peakSum + "，末了差合计 " + afterSum);

        // ④ 新闻条目上要留下「已澄清」的标记与计划澄清的跳数
        MarketEngine engine = new MarketEngine(SEED);
        engine.debugTriggerEvent(home, index);
        for (int i = 0; i < 3 * def.halfLife(); i++) {
            engine.advanceAll();
        }
        MarketNews rumour = engine.newsFeed().stream()
                .filter(item -> item.id().equals("route_rumor"))
                .findFirst().orElse(null);
        assertNotNull(rumour);
        assertTrue(rumour.clarified(), "澄清之后这条新闻必须被标记");
        assertTrue(rumour.clarifyAt() > rumour.jump(), "传闻要记下计划澄清的跳数");
        assertFalse(rumour.isMarket());
    }

    // ==================== 大盘状态对个股的影响（β） ====================

    /**
     * 牛市 vs 平静：同种子、同噪声，只有 {@code bias × β} 这一项不同 ——
     * 三只股票都必须被托起来，而且 **β 越大涨得越多**（激进股 > 波动股 > 稳健股）。
     */
    @Test
    void regimeBiasTiltsEveryStockByItsBeta() {
        MarketEngine bull = new MarketEngine(SEED);
        MarketEngine calm = new MarketEngine(SEED);
        bull.debugSetRegime(MarketRegime.BULL);
        for (int i = 0; i < 40; i++) {
            bull.advanceAll();
            calm.advanceAll();
        }
        double[] gain = new double[StockDefs.COUNT];
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            gain[stock] = bull.price(stock) / (double) calm.price(stock) - 1.0;
            assertTrue(gain[stock] > 0, StockDefs.IDS[stock] + " 在牛市里必须被托起来：" + gain[stock]);
        }
        assertTrue(gain[2] > gain[1] && gain[1] > gain[0],
                "β（0.7 / 1.0 / 1.5）必须体现成「激进股涨得更多」：" + gain[0] + " / " + gain[1] + " / " + gain[2]);
        assertEquals(1.5, StockDefs.BETA[2] / StockDefs.BETA[1], 1e-9, "β 的比例就是涨跌幅的比例");
    }

    // ==================== 新闻流与大盘指数 ====================

    @Test
    void newsFeedKeepsTheLastFewAndNewestFirst() {
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 20_000; i++) {
            engine.advanceAll();
            assertTrue(engine.newsFeed().size() <= StockDefs.NEWS_KEEP,
                    "新闻流最多保留 " + StockDefs.NEWS_KEEP + " 条");
        }
        List<MarketNews> feed = engine.newsFeed();
        assertFalse(feed.isEmpty(), "两万跳里不可能一条新闻都没有");
        for (int i = 1; i < feed.size(); i++) {
            assertTrue(feed.get(i - 1).jump() >= feed.get(i).jump(),
                    "新闻流必须**最新在最前**");
        }
    }

    @Test
    void marketIndexIsTheEqualWeightedAverageAndStartsAtOneThousand() {
        MarketEngine engine = new MarketEngine(SEED);
        assertEquals(10_000, engine.indexDeci(), "开盘时三只都在基准价 → 指数 1000.0");
        assertEquals(0, engine.indexChangePermille(), "还没跳过价，涨跌是 0");

        engine.advanceAll();
        long expected = 0;
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            expected += engine.price(stock) * 10_000L / StockDefs.BASE_CENTS[stock];
        }
        assertEquals((int) (expected / StockDefs.COUNT), engine.indexDeci(),
                "指数 = 1000 × mean(price / base)，实现里每只先取整再平均");
        int change = engine.indexChangePermille();
        assertTrue(change >= -250 && change <= 250, "指数单跳涨跌也该在 ±25% 以内：" + change);
    }

    // ==================== 成簇 ====================

    /**
     * 新闻扎堆：连着出现的同类型新闻最多 {@link StockDefs#CLUSTER_MAX_CHAIN} 条。
     *
     * <p>判据用 {@link MarketNews#chainDepth()}（引擎自己记的「这是链上第几条」），
     * 而不是「两条同类型新闻挨得近」—— 后者会把两条互不相干的链误判成一条
     * （十万跳里这种巧合多得是，实测会把最长链读成 6）。
     */
    @Test
    void newsClustersAreCapped() {
        MarketEngine engine = new MarketEngine(SEED);
        Set<MarketNews> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            engine.advanceAll();
            seen.addAll(engine.newsFeed());
        }
        assertTrue(seen.size() > 20, "十万跳里应该攒下不少新闻，实际 " + seen.size());

        int maxDepth = 0;
        int chained = 0;
        for (MarketNews item : seen) {
            maxDepth = Math.max(maxDepth, item.chainDepth());
            if (item.chainDepth() >= 2) {
                chained++;
                // 第 k 条必须有一条「同类型、深度 k-1、在它之前 1~3 跳内」的父新闻
                int depth = item.chainDepth();
                boolean parentFound = seen.stream().anyMatch(other ->
                        other.type() == item.type() && other.chainDepth() == depth - 1
                                && item.jump() - other.jump() >= StockDefs.CLUSTER_MIN_DELAY
                                && item.jump() - other.jump() <= StockDefs.CLUSTER_MAX_DELAY);
                assertTrue(parentFound, "深度 " + depth + " 的新闻找不到 1~3 跳内的同类型父新闻：" + item);
            }
        }
        assertTrue(maxDepth <= StockDefs.CLUSTER_MAX_CHAIN,
                "连簇深度不该超过 " + StockDefs.CLUSTER_MAX_CHAIN + "，实测 " + maxDepth);
        assertEquals(StockDefs.CLUSTER_MAX_CHAIN, maxDepth,
                "十万跳里应该出现过满链（" + StockDefs.CLUSTER_MAX_CHAIN + " 条）");
        assertTrue(chained > 10, "成簇的跟风新闻应该有一定数量，实际 " + chained);
    }

    // ==================== 统计（十万跳） ====================

    @Test
    void eventFrequencyAndNewsMixAreReasonable() {
        MarketEngine engine = new MarketEngine(SEED);
        Set<MarketNews> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            engine.advanceAll();
            seen.addAll(engine.newsFeed());
        }
        Map<EventType, Integer> byType = new EnumMap<>(EventType.class);
        Map<String, Integer> byId = new HashMap<>();
        int marketNews = 0;
        int events = 0;
        for (MarketNews item : seen) {
            byType.merge(item.type(), 1, Integer::sum);
            byId.merge(item.id(), 1, Integer::sum);
            if (item.isMarket()) {
                marketNews++;
            } else {
                events++;
            }
        }

        // 事件频率：每只股票每跳 0.25% × 10 万跳 ≈ 250 次；高波动期翻倍、成簇再加一点。
        // 区间写得很宽松（±60%），只用来抓「一个数量级」的错误（比如忘了乘概率、
        // 或者成簇失控刷屏），不追求精确值。
        int perStockLow = 90;
        int perStockHigh = 640;
        int homeEvents = 0;
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            int count = 0;
            for (int index = 0; index < StockEventTable.size(stock); index++) {
                count += byId.getOrDefault(StockEventTable.id(stock, index), 0);
            }
            assertTrue(count >= perStockLow && count <= perStockHigh,
                    StockDefs.IDS[stock] + " 十万跳里触发了 " + count + " 次，超出宽松区间 ["
                            + perStockLow + ", " + perStockHigh + "]");
            homeEvents += count;
        }
        assertTrue(events >= homeEvents, "新闻条数（含澄清/成簇）不该少于事件次数");

        // 大盘状态播报：十万跳 × 2 秒 ≈ 55 小时，状态平均持续 1~10 分钟 → 几百次；
        // 区间同样放宽。
        assertTrue(marketNews >= 50 && marketNews <= 5_000,
                "大盘状态播报次数 " + marketNews + " 应该落在一个合理的量级");

        // 类型分布：四类事件 + 大盘都得出现；也没有哪一类压倒性地多（成簇失控会让某一类爆掉）
        for (EventType type : EventType.values()) {
            assertTrue(byType.getOrDefault(type, 0) > 0, type + " 一次都没出现过");
            assertTrue(byType.getOrDefault(type, 0) < seen.size() * 0.8,
                    type + " 占了新闻的八成以上，成簇或权重八成写歪了");
        }
    }
}
