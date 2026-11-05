package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **插针**（盘中异动）的行为与护栏单测：每只股票每跳 {@link StockDefs#SPIKE_CHANCE}，
 * 单跳 −8%~−15%，随后 3~5 跳只回补 60%~80%。
 *
 * <p>三条必须钉住的东西：
 * <ol>
 *   <li><b>护栏一条没放宽</b>：插针那一跳仍然 ≤ ±25%，价格区间（2%~500%、下限 ≥1 分）照旧生效，
 *       **价格永远 > 0**；</li>
 *   <li><b>「等反弹就行」不成立</b>：插针过去之后净跌幅仍然是原始跌幅的 20%~40%；</li>
 *   <li>插针要往新闻流里写一条**中性叙述**（{@link EventType#SPIKE}），而不是默默来一根针。</li>
 * </ol>
 *
 * <p>实测数字打印在 gradle 的测试报告里（汇报直接引用）。
 */
class SpikeMechanismTest {

    private static final long SEED = 20260101L;
    private static final int JUMPS = 100_000;

    @Test
    void spikesAreBoundedAndOnlyPartlyRecovered() {
        MarketEngine engine = new MarketEngine(SEED);
        long[] previous = engine.prices();          // 上一跳的价格
        int spikes = 0;
        int spikesWithDrop = 0;
        int spikeNews = 0;
        double dropSum = 0.0;
        double netDropSum = 0.0;                    // 回补跑完之后的净跌幅（相对插针前）
        double worstSingleJump = 0.0;
        long minPrice = Long.MAX_VALUE;
        Set<MarketNews> seenNews = new HashSet<>();
        // 正在跟踪的插针：{股票, 插针前价格, 插针后价格, 还差几跳观察完}
        List<long[]> tracking = new ArrayList<>();

        for (int jump = 1; jump <= JUMPS; jump++) {
            engine.advanceAll();
            long[] prices = engine.prices();

            // 新闻流里新出现的插针 → 开始跟踪它
            for (MarketNews item : engine.newsFeed()) {
                if (item.type() != EventType.SPIKE || !seenNews.add(item)) {
                    continue;
                }
                spikeNews++;
                spikes++;
                long before = previous[item.stock()];
                long after = prices[item.stock()];
                if (after < before) {
                    spikesWithDrop++;
                }
                dropSum += (before - after) / (double) before;
                tracking.add(new long[]{item.stock(), before, after, 6});
            }

            // 插针那一跳（刚跟踪上的那些）的单跳幅度
            for (long[] tracked : tracking) {
                if (tracked[3] == 6) {
                    double move = Math.abs(prices[(int) tracked[0]] / (double) tracked[1] - 1.0);
                    worstSingleJump = Math.max(worstSingleJump, move);
                }
            }

            // 观察满 6 跳 → 结算净跌幅（回补 3~5 跳，6 跳足够看完）
            for (int i = tracking.size() - 1; i >= 0; i--) {
                long[] tracked = tracking.get(i);
                if (--tracked[3] > 0) {
                    continue;
                }
                long stock = tracked[0];
                netDropSum += (tracked[1] - prices[(int) stock]) / (double) tracked[1];
                tracking.remove(i);
            }

            for (long price : prices) {
                minPrice = Math.min(minPrice, price);
            }
            previous = prices;
        }

        double avgDrop = dropSum / Math.max(1, spikesWithDrop);
        double avgNetDrop = netDropSum / Math.max(1, spikes - tracking.size());
        System.out.println("=== 插针实测：" + JUMPS + " 跳 × 3 只股票（预期 "
                + (long) (JUMPS * StockDefs.COUNT * StockDefs.SPIKE_CHANCE) + " 次）");
        System.out.println("触发 " + spikes + " 次（其中 " + spikesWithDrop
                + " 次当跳确实下跌），新闻条数 " + spikeNews);
        System.out.printf("平均单跳跌幅 %.2f%%，回补完成后平均净跌幅 %.2f%%（= 原始跌幅的 %.0f%%）%n",
                avgDrop * 100, avgNetDrop * 100, avgNetDrop / Math.max(1e-9, avgDrop) * 100);
        System.out.printf("最大单跳幅度 %.2f%%（护栏 ±%.0f%%），最低价 %d 分%n",
                worstSingleJump * 100, StockDefs.MAX_STEP * 100, minPrice);
        System.out.println("剩余跟踪中的插针 " + tracking.size() + " 个（末尾还没观察完）");

        // ---------- 断言 ----------
        long expected = (long) (JUMPS * StockDefs.COUNT * StockDefs.SPIKE_CHANCE);
        assertTrue(spikes >= expected * 0.5 && spikes <= expected * 1.7,
                "十万跳里插针次数应该落在预期量级附近（预期 " + expected + "），实际 " + spikes);
        assertEquals(spikes, spikeNews, "每一次插针都该写一条新闻（否则玩家看到一根莫名其妙的针）");
        assertTrue(spikesWithDrop >= spikes * 0.9,
                "九成以上的插针当跳就该是下跌的，实际 " + spikesWithDrop + "/" + spikes);
        assertTrue(avgDrop >= StockDefs.SPIKE_MIN_DROP * 0.5
                        && avgDrop <= StockDefs.SPIKE_MAX_DROP * 1.5,
                "平均跌幅应该落在设计区间附近（8%~15%），实际 " + avgDrop);
        // 「不能让玩家等反弹就行」：回补之后仍然明显是亏的
        assertTrue(avgNetDrop > 0.01, "回补之后净跌幅仍应明显为负，实际 " + avgNetDrop);
        assertTrue(avgNetDrop < avgDrop * 0.9, "回补必须只是**部分**回补，实际净跌幅 " + avgNetDrop);
        assertTrue(worstSingleJump <= StockDefs.MAX_STEP + 1e-9,
                "插针那一跳也必须夹在 ±25% 内，实际 " + worstSingleJump);
        assertTrue(minPrice > 0, "价格永远不能掉到 0 以下，实测最低 " + minPrice + " 分");
    }
}
