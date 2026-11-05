package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大盘状态机（{@link MarketRegime}）单测：转移概率合法、持续时间落在区间、可复现。
 */
class MarketRegimeTest {

    private static final double EPS = 1e-9;

    /** 转移表每一行都必须是一个概率分布（和 = 1，且每项 >= 0）。 */
    @Test
    void transitionsAreAValidProbabilityTable() {
        for (MarketRegime from : MarketRegime.values()) {
            double[] row = MarketRegime.transitionRow(from);
            assertEquals(MarketRegime.values().length, row.length, from + " 的转移行长度必须是状态数");
            double sum = 0.0;
            for (double p : row) {
                assertTrue(p >= 0.0 && p <= 1.0, from + " 的转移概率必须在 [0,1]：" + p);
                sum += p;
            }
            assertEquals(1.0, sum, EPS, from + " 的转移行和必须是 1");
        }
    }

    /** 同一个随机数永远给同一个后继状态（纯函数）。 */
    @Test
    void nextIsPure() {
        for (MarketRegime from : MarketRegime.values()) {
            for (double roll : new double[]{0.0, 0.05, 0.3, 0.5, 0.79, 0.999}) {
                assertEquals(from.next(roll), from.next(roll), "同一个 roll 必须给同一个结果");
            }
        }
        // 抽 2000 次：结果必须都是合法状态，且各个状态都出现过（转移表不能把某个状态孤立掉）
        Random random = new Random(7L);
        Map<MarketRegime, Integer> hits = new EnumMap<>(MarketRegime.class);
        for (int i = 0; i < 2_000; i++) {
            MarketRegime next = MarketRegime.CALM.next(random.nextDouble());
            assertNotNull(next);
            hits.merge(next, 1, Integer::sum);
        }
        for (MarketRegime regime : MarketRegime.values()) {
            assertTrue(hits.getOrDefault(regime, 0) > 0, regime + " 从来抽不到（转移表把它孤立了）");
        }
    }

    /** 持续跳数落在 [minJumps, maxJumps] 内，且两端都能抽到。 */
    @Test
    void durationStaysInsideItsRange() {
        Random random = new Random(11L);
        for (MarketRegime regime : MarketRegime.values()) {
            boolean sawMin = false;
            boolean sawMax = false;
            // 两万次：区间最宽的是牛市（60~300，241 个取值），
            // 每端被抽到的概率 1 − (1 − 1/241)^20000 ≈ 1，不会因为「运气不好」而漏掉端点
            for (int i = 0; i < 20_000; i++) {
                int jumps = regime.drawDuration(random);
                assertTrue(jumps >= regime.minJumps() && jumps <= regime.maxJumps(),
                        regime + " 的持续跳数越界：" + jumps);
                sawMin |= jumps == regime.minJumps();
                sawMax |= jumps == regime.maxJumps();
            }
            assertTrue(sawMin && sawMax, regime + " 的两端都该抽得到（区间是闭区间）");
        }
    }

    /** 口径换算：1 跳 = 40 tick = 2 秒，所以「2~10 分钟」= 60~300 跳。 */
    @Test
    void durationsMatchTheDocumentedMinutes() {
        int ticksPerMinute = 60 * 20;                       // 20 tps
        int jumpsPerMinute = ticksPerMinute / StockDefs.PRICE_TICK_TICKS;
        assertEquals(30, jumpsPerMinute, "1 分钟 = 30 跳（2 秒一跳）");
        assertTrue(MarketRegime.CALM.minJumps() >= 2 * jumpsPerMinute, "平静至少 2 分钟");
        assertTrue(MarketRegime.BULL.maxJumps() <= 10 * jumpsPerMinute, "牛市最多 10 分钟");
        assertTrue(MarketRegime.VOLATILE.maxJumps() <= MarketRegime.BULL.maxJumps(),
                "高波动比牛市短（恐慌不会持续一整天）");
    }

    /** 同一个种子 → 同一串状态（读档重放靠它）。 */
    @Test
    void reproducibleForTheSameSeed() {
        assertEquals(regimeSequence(99L, 400), regimeSequence(99L, 400));
    }

    private static String regimeSequence(long seed, int jumps) {
        MarketEngine engine = new MarketEngine(seed);
        StringBuilder sb = new StringBuilder();
        MarketRegime previous = engine.regime();
        sb.append(previous.id());
        for (int i = 0; i < jumps; i++) {
            engine.advanceAll();
            if (engine.regime() != previous) {
                previous = engine.regime();
                sb.append('>').append(previous.id());
            }
        }
        return sb.toString();
    }

    /** 引擎里的状态切换不违反「持续跳数 = 进入时抽的那个值」。 */
    @Test
    void engineKeepsRegimeForTheDrawnDuration() {
        MarketEngine engine = new MarketEngine(20260101L);
        int left = engine.regimeJumpsLeft();
        for (int i = 0; i < 5_000; i++) {
            engine.advanceAll();
            int now = engine.regimeJumpsLeft();
            if (now > left - 1) {
                // ⚠️ 判据是「剩余跳数被重新抽过」，**不是**「状态名变了」：
                //    转移表允许自转移（平静 → 平静 0.45），那种情况下状态名不变、计数却会重置
                MarketRegime regime = engine.regime();
                assertTrue(now >= regime.minJumps() && now <= regime.maxJumps(),
                        "刚切换时剩余跳数应该就是新抽的持续值，实际 " + now + "（" + regime + "）");
            } else {
                assertEquals(left - 1, now, "同一状态内剩余跳数每跳减 1");
            }
            left = now;
        }
    }
}
