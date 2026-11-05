package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行情引擎单测：可复现、有界、会回归、能聚合出阴阳柱。
 */
class MarketEngineTest {

    private static final long SEED = 20260101L;

    @Test
    void deterministicForSameSeed() {
        MarketEngine a = new MarketEngine(SEED);
        MarketEngine b = new MarketEngine(SEED);
        for (int i = 0; i < 200; i++) {
            a.advanceAll();
            b.advanceAll();
        }
        for (int s = 0; s < StockDefs.COUNT; s++) {
            assertEquals(a.price(s), b.price(s), "同一颗种子必须给出同一串价格");
        }
    }

    @Test
    void differentSeedsDiverge() {
        MarketEngine a = new MarketEngine(1L);
        MarketEngine b = new MarketEngine(2L);
        for (int i = 0; i < 50; i++) {
            a.advanceAll();
            b.advanceAll();
        }
        assertNotEquals(a.price(0), b.price(0));
    }

    @Test
    void pricesStayInsideTheirBand() {
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 5_000; i++) {
            engine.advanceAll();
            for (int s = 0; s < StockDefs.COUNT; s++) {
                long p = engine.price(s);
                assertTrue(p >= StockDefs.priceFloor(s), "价格不能跌破下限");
                assertTrue(p <= StockDefs.priceCeil(s), "价格不能涨破上限");
            }
        }
    }

    @Test
    void singleStepIsCapped() {
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 2_000; i++) {
            long[] before = engine.prices();
            engine.advanceAll();
            for (int s = 0; s < StockDefs.COUNT; s++) {
                double ratio = (double) engine.price(s) / before[s];
                assertTrue(ratio <= 1.0 + StockDefs.MAX_STEP + 1e-9 && ratio >= 1.0 - StockDefs.MAX_STEP - 1e-9,
                        "单跳变动必须夹在 ±25% 内，实际 " + ratio);
            }
        }
    }

    @Test
    void extremePricePullsBackTowardBase() {
        MarketEngine engine = new MarketEngine(SEED);
        // 把价格推到上限附近再跑一段时间：均值回归应该把它拉回来
        for (int i = 0; i < 3_000; i++) {
            engine.advanceAll();
        }
        long price = engine.price(0);
        long base = StockDefs.BASE_CENTS[0];
        assertTrue(Math.abs(price - base) < base, "长期看价格应该在基准价附近，而不是贴在天花板上");
    }

    @Test
    void candlesAggregateHistory() {
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 40; i++) {
            engine.advanceAll();
        }
        int[][] candles = engine.candles(4);
        assertEquals(StockDefs.COUNT, candles.length);
        assertEquals(4 * 4, candles[0].length);
        // 最后一根 = 最近 5 跳：高 ≥ 开/收 ≥ 低
        int off = 3 * 4;
        int open = candles[0][off];
        int high = candles[0][off + 1];
        int low = candles[0][off + 2];
        int close = candles[0][off + 3];
        assertTrue(open > 0 && close > 0, "最近这根必须有数据");
        assertTrue(high >= open && high >= close, "最高价必须不低于开/收");
        assertTrue(low <= open && low <= close, "最低价必须不高于开/收");
    }

    @Test
    void boundaryCandleNeverMixesInUnfilledSlots() {
        // 跳 7 次（不是 CANDLE_TICKS=5 的整数倍）→ 必有一根柱子横跨「0 区 / 真实区」。
        // 回归：这根柱子一旦取到 open=0/low=0，客户端会把自适应量程一路拉到 0，
        // 实机上就是一根顶天立地的巨柱（脚手架 live2_trade_b.png 抓到过）。
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 7; i++) {
            engine.advanceAll();
        }
        int[][] candles = engine.candles(3);
        for (int c = 0; c < 3; c++) {
            int open = candles[0][c * 4];
            int high = candles[0][c * 4 + 1];
            int low = candles[0][c * 4 + 2];
            int close = candles[0][c * 4 + 3];
            boolean empty = open == 0 && high == 0 && low == 0 && close == 0;
            assertTrue(empty || (open > 0 && high > 0 && low > 0 && close > 0),
                    "有数据的柱子不能含 0 价（第 " + c + " 根 = " + open + "/" + high + "/" + low + "/" + close + "）");
        }
    }

    @Test
    void completedCandlesNeverChangeWhenANewTickArrives() {
        // 回归：以前 candles() 按「历史末尾」相对分箱，每跳一次价所有柱子的窗口整体后移，
        // 已完成的柱子 open/close 全变 → 客户端每次收到快照整张图乱跳（实机反馈）。
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 23; i++) {
            engine.advanceAll();
        }
        int[][] before = engine.candles(4);
        engine.advanceAll();
        int[][] after = engine.candles(4);
        for (int c = 0; c < 3; c++) {                 // 最后一根是「进行中」，允许变
            for (int f = 0; f < 4; f++) {
                assertEquals(before[0][c * 4 + f], after[0][c * 4 + f],
                        "已完成的柱子不允许随新跳价改变（第 " + c + " 根 字段 " + f + "）");
            }
        }
    }

    @Test
    void tickCounterCountsDown() {
        MarketEngine engine = new MarketEngine(SEED);
        int before = engine.ticksToNext();
        engine.tick();
        assertEquals(before - 1, engine.ticksToNext());
        // 再走满剩下的 tick：第 PRICE_TICK_TICKS 次 tick 会跳价并把倒计时重置
        for (int i = 0; i < StockDefs.PRICE_TICK_TICKS - 1; i++) {
            engine.tick();
        }
        assertEquals(StockDefs.PRICE_TICK_TICKS, engine.ticksToNext(), "跳完价后倒计时要重置");
    }

    @Test
    void restoreKeepsPrices() {
        MarketEngine engine = new MarketEngine(SEED);
        for (int i = 0; i < 120; i++) {
            engine.advanceAll();
        }
        MarketEngine restored = MarketEngine.restore(SEED, engine.advances(), engine.ticksToNextRaw(),
                engine.snapshotPrices(), engine.snapshotHistory());
        for (int s = 0; s < StockDefs.COUNT; s++) {
            assertEquals(engine.price(s), restored.price(s), "读档后价格必须一致");
        }
        assertFalse(restored.candles(4)[0].length == 0);
    }
}
