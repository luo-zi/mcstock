package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开仓 / 平仓 / 逐仓爆仓结算单测（**独立仓位列表**语义）。
 *
 * <p>算例沿用策划确认过的三组：稳妥（×5 押 2000）、赌徒（×400 押 5）、做空（×2 押 2500），
 * 但入口换成了新语义的两个：
 * <ul>
 *   <li>{@link TradeService#open} —— 买入 = 新开多仓、卖出 = 新开空仓；</li>
 *   <li>{@link TradeService#close} —— 按仓位 id 平掉**那一个**仓。</li>
 * </ul>
 *
 * <p>重点守住本次重构的核心断言：**反向下单不再平掉已有仓**、**平/爆一个仓不影响别的仓**、
 * 以及「手续费与保证金口径不变」（按仓位价值算）。
 */
class TradeServiceTest {

    private static final int STOCK = 0;
    private static final int OTHER_STOCK = 1;
    private static final long PRICE = 10_000L;   // 100.00 金币

    // ==================== 开仓 ====================

    @Test
    void openLocksMarginAndChargesFeeOnNotional() {
        Portfolio p = new Portfolio();
        long cash = 500_000L;                     // 5000.00 金币
        TradeService.Outcome out = TradeService.open(cash, p, STOCK, PRICE, 100, 5, true);

        assertEquals(TradeService.Status.OPENED, out.status());
        assertEquals(1_000_000L, PositionMath.notional(PRICE, 100));
        assertEquals(1, p.size(), "开仓只挂一个新仓上去");
        Position position = p.positions().get(0);
        assertEquals(out.positionId(), position.id());
        assertEquals(STOCK, position.stock());
        assertEquals(100, position.shares());
        assertEquals(PRICE, position.entryCents());
        assertEquals(200_000L, position.marginCents());          // 押金 2000.00
        assertEquals(5, position.leverage());
        assertEquals(2_000L, out.feeCents());                    // 手续费 = 10000.00 × 0.20% = 20.00 金币
        assertEquals(298_000L, out.cashCents());                 // 5000 − 2000 − 20
        assertEquals(9_100L, position.liquidationPrice());       // 爆仓价 = 100.00 − 2000.00×45%/100 股 = 91.00
    }

    @Test
    void highLeverageCostsFarMoreInFees() {
        Portfolio p = new Portfolio();
        long cash = 100_000L;                     // 1000.00 金币
        TradeService.Outcome out = TradeService.open(cash, p, STOCK, 2_000L, 100, 400, true);
        assertEquals(TradeService.Status.OPENED, out.status());
        assertEquals(500L, p.positions().get(0).marginCents());  // 仓位 2000.00 / 400 = 5.00
        assertEquals(400L, out.feeCents());                      // 手续费 = 2000.00 × 0.20% = 4.00 金币
        // 爆仓距离 = 45%/400 = 0.11%，在分位上取整后是 2 分 → 19.98
        assertEquals(1_998L, PositionMath.liquidationPrice(100, 2_000L, 500L), "×400 的爆仓距离只有约 0.1%");
    }

    @Test
    void insufficientCashIsRejected() {
        Portfolio p = new Portfolio();
        long cash = 1_000L;                       // 只有 10.00 金币
        TradeService.Outcome out = TradeService.open(cash, p, STOCK, PRICE, 100, 5, true);
        assertEquals(TradeService.Status.NO_CASH, out.status());
        assertEquals(1_000L, out.cashCents(), "拒绝下单时现金不能变");
        assertEquals(0, p.size());
        // 顺带把「差多少」带回去（提示行要写「需要 2035.00，现在只有 10.00」）
        // 需要 = 押金 2000.00 + 手续费（100 股 × 100.00 = 10000.00 仓位 × 0.20%）= 20.00
        assertEquals(200_000L + 2_000L, out.marginCents() + out.feeCents());
    }

    @Test
    void sellWithNoPositionOpensShort() {
        Portfolio p = new Portfolio();
        TradeService.Outcome out = TradeService.open(3_000_000L, p, STOCK, PRICE, 100, 2, false);
        assertEquals(TradeService.Status.OPENED, out.status());
        assertEquals(-100, p.positions().get(0).shares(), "无仓位时“卖出”就是开空");
        // 100 股 × 100.00、×2 → 押金 5000.00；爆仓价 = 100.00 + 250000/100 分 = 125.00
        assertEquals(12_250L, p.positions().get(0).liquidationPrice(), "空头爆仓价在均价上方（100.00 + 5000.00×45%/100 股 = 122.50）");
    }

    @Test
    void badQuantityIsRejectedWithoutTouchingAnything() {
        Portfolio p = new Portfolio();
        TradeService.Outcome out = TradeService.open(1_000_000L, p, STOCK, PRICE, 0, 5, true);
        assertEquals(TradeService.Status.BAD_QTY, out.status());
        assertEquals(0, p.size());
        assertEquals(1_000_000L, out.cashCents());
    }

    // ==================== 同股票多仓（互不影响） ====================

    @Test
    void repeatedBuysOnSameStockCreateIndependentPositions() {
        Portfolio p = new Portfolio();
        TradeService.Outcome first = TradeService.open(5_000_000L, p, STOCK, PRICE, 100, 5, true);
        TradeService.Outcome second = TradeService.open(first.cashCents(), p, STOCK, 11_000L, 50, 2, true);

        assertEquals(TradeService.Status.OPENED, second.status());
        assertEquals(2, p.size(), "同一只股票买两次 = 两个仓");
        assertNotEquals(first.positionId(), second.positionId());
        assertEquals(2, p.countFor(STOCK));

        Position a = p.byId(first.positionId());
        Position b = p.byId(second.positionId());
        assertNotNull(a);
        assertNotNull(b);
        // 第一仓一个数都没被第二仓改动（旧语义会在这里把均价加权合并）
        assertEquals(100, a.shares());
        assertEquals(PRICE, a.entryCents());
        assertEquals(200_000L, a.marginCents());
        assertEquals(5, a.leverage());
        // 第二仓按自己的价格与杠杆算
        assertEquals(50, b.shares());
        assertEquals(11_000L, b.entryCents());
        assertEquals(PositionMath.marginFor(11_000L, 50, 2), b.marginCents());
        assertEquals(2, b.leverage());
        assertEquals(a.marginCents() + b.marginCents(), p.totalMargin());
    }

    @Test
    void oppositeOrderDoesNotCloseExistingPosition() {
        // ★ 本次需求的核心：反向下单**不再**先平仓，而是再开一个反向仓
        Portfolio p = new Portfolio();
        TradeService.Outcome longOut = TradeService.open(5_000_000L, p, STOCK, PRICE, 100, 1, true);
        assertEquals(1, p.size());

        TradeService.Outcome shortOut = TradeService.open(longOut.cashCents(), p, STOCK, PRICE, 100, 1, false);
        assertEquals(TradeService.Status.OPENED, shortOut.status());
        assertEquals(2, p.size(), "反向下单必须表现为「多了一个仓」");
        assertEquals(100, p.byId(longOut.positionId()).shares(), "原来的多仓一股都没动");
        assertEquals(-100, p.byId(shortOut.positionId()).shares(), "新开的是空仓");
        assertEquals(0, p.sharesFor(STOCK), "净股数互相抵消（多空各 100）");
        assertEquals(1_000_000L, p.byId(longOut.positionId()).marginCents());
        assertEquals(1_000_000L, p.byId(shortOut.positionId()).marginCents(), "两个仓各押各的保证金");
        assertEquals(2_000_000L, p.totalMargin());
    }

    @Test
    void positionCapStopsOpeningMore() {
        Portfolio p = new Portfolio();
        long cash = 1_000_000_000L;
        for (int i = 0; i < Portfolio.MAX_POSITIONS; i++) {
            assertEquals(TradeService.Status.OPENED,
                    TradeService.open(cash, p, STOCK, PRICE, 1, 400, true).status(), "第 " + (i + 1) + " 个仓");
        }
        assertEquals(Portfolio.MAX_POSITIONS, p.size());
        TradeService.Outcome overflow = TradeService.open(cash, p, STOCK, PRICE, 1, 400, true);
        assertEquals(TradeService.Status.FULL, overflow.status(), "满了要明确拒绝，不能悄悄不记账");
        assertEquals(Portfolio.MAX_POSITIONS, p.size());
    }

    // ==================== 逐仓平仓 ====================

    @Test
    void closeOnlyTouchesTheTargetPosition() {
        Portfolio p = new Portfolio();
        TradeService.Outcome longOut = TradeService.open(5_000_000L, p, STOCK, PRICE, 100, 1, true);
        TradeService.Outcome shortOut = TradeService.open(longOut.cashCents(), p, STOCK, 9_000L, 100, 1, false);
        long cashBefore = shortOut.cashCents();

        // 价格涨到 110.00：平掉多仓（浮盈 +1000.00），空仓必须原封不动
        TradeService.Outcome closed = TradeService.close(cashBefore, p, longOut.positionId(), 11_000L);
        assertEquals(TradeService.Status.CLOSED, closed.status());
        assertEquals(100, closed.closedQty());
        assertEquals(100_000L, closed.realizedPnlCents(), "浮动盈亏按平仓价结算");
        assertEquals(2_200L, closed.feeCents(), "平仓费 = 11000.00 × 0.20% = 22.00");
        assertEquals(cashBefore + 1_000_000L + 100_000L - 2_200L, closed.cashCents());

        assertEquals(1, p.size(), "另一个仓还在");
        assertNull(p.byId(longOut.positionId()), "被平的仓已经从列表里摘掉");
        Position left = p.byId(shortOut.positionId());
        assertNotNull(left);
        assertEquals(-100, left.shares());
        assertEquals(9_000L, left.entryCents());
        assertEquals(900_000L, left.marginCents());
    }

    @Test
    void closingUnknownPositionChangesNothing() {
        Portfolio p = new Portfolio();
        TradeService.Outcome out = TradeService.open(5_000_000L, p, STOCK, PRICE, 10, 1, true);
        TradeService.Outcome closed = TradeService.close(out.cashCents(), p, 9999, PRICE);
        assertEquals(TradeService.Status.NO_POSITION, closed.status());
        assertEquals(out.cashCents(), closed.cashCents(), "找不到仓时现金不动");
        assertEquals(1, p.size());
    }

    // ==================== 逐仓爆仓 ====================

    @Test
    void liquidationOnlyClearsThePositionThatHitTheLine() {
        Portfolio p = new Portfolio();
        // 赌徒：×400 押 5.00，爆仓距离 = 45%/400 = 0.11%
        TradeService.Outcome risky = TradeService.open(10_000_000L, p, STOCK, 2_000L, 100, 400, true);
        // 另一只股票上的普通仓，价格没动，绝不该被连坐
        TradeService.Outcome safe = TradeService.open(risky.cashCents(), p, OTHER_STOCK, 5_000L, 200, 10, false);
        long cashBefore = safe.cashCents();

        assertFalse(TradeService.shouldLiquidate(p, risky.positionId(), 2_005L), "温和反弹：不该爆");
        assertFalse(TradeService.shouldLiquidate(p, safe.positionId(), 5_000L), "价格没动：不该爆");
        // 急跌 10%：一跳穿过爆仓线（约 0.1% 的距离），按真实价结算 → 亏 100×200 分 = 20000 分
        assertTrue(TradeService.shouldLiquidate(p, risky.positionId(), 1_800L));

        long delta = TradeService.liquidate(p, risky.positionId(), 1_800L);
        assertEquals(500L - 20_000L, delta, "退还保证金 + 真实浮亏");
        assertEquals(1, p.size(), "只清掉爆掉的那个仓");
        assertNull(p.byId(risky.positionId()));
        assertNotNull(p.byId(safe.positionId()), "别的仓必须保留");
        assertEquals(100_000L, p.totalMargin(), "剩下的保证金就是 safe 那一份");
        // 现金下限是调用方（StockService）兜的：这里只管「退了保证金 + 真实浮亏」这一个数。
        // 本来就只剩 300 分的话，这样爆一下就归零（不倒欠）
        assertEquals(0L, Math.max(0L, 300L + delta));
    }

    @Test
    void sameStockOtherPositionSurvivesALiquidation() {
        Portfolio p = new Portfolio();
        // 同一只股票上两个仓：一个 ×400（会爆），一个 ×1（不会）
        TradeService.Outcome risky = TradeService.open(10_000_000L, p, STOCK, 2_000L, 100, 400, true);
        TradeService.Outcome steady = TradeService.open(risky.cashCents(), p, STOCK, 2_000L, 100, 1, true);

        assertTrue(TradeService.shouldLiquidate(p, risky.positionId(), 1_800L));
        assertFalse(TradeService.shouldLiquidate(p, steady.positionId(), 1_800L),
                "×1 的仓在 −10% 时离爆仓（−45%）还远");
        TradeService.liquidate(p, risky.positionId(), 1_800L);

        assertEquals(1, p.size());
        Position left = p.byId(steady.positionId());
        assertNotNull(left);
        assertEquals(100, left.shares());
        assertEquals(200_000L, left.marginCents());
        assertEquals(1_100L, left.liquidationPrice(), "×1 的仓：均价 20.00 − 2000.00×45% ÷ 100 股 = 11.00");
    }

    // ==================== 净值口径 ====================

    @Test
    void portfolioEquityAddsUp() {
        Portfolio p = new Portfolio();
        TradeService.open(10_000_000L, p, 0, PRICE, 100, 5, true);
        TradeService.open(10_000_000L, p, 1, 5_000L, 200, 10, false);
        long[] prices = {PRICE, 5_000L, StockDefs.BASE_CENTS[2]};
        long equity = p.totalMargin() + p.totalPnl(prices);
        assertEquals(200_000L + 100_000L, p.totalMargin());
        assertEquals(0L, p.totalPnl(prices), "价格没动时浮盈为 0");
        assertEquals(300_000L, equity);

        long[] moved = {11_000L, 4_000L, StockDefs.BASE_CENTS[2]};
        // 多仓涨 10.00 × 100 股 = +1000.00；空仓跌 10.00 × 200 股 = 也赚 2000.00
        assertEquals(300_000L, p.totalPnl(moved));
        assertEquals(100_000L, p.pnlFor(0, 11_000L));
        assertEquals(200_000L, p.pnlFor(1, 4_000L));
    }

    @Test
    void leverageIsDerivedForDisplay() {
        // 协议里不传杠杆：客户端用「均价 × 股数 ÷ 保证金」反推
        assertEquals(5, Position.deriveLeverage(100, PRICE, 200_000L));
        assertEquals(400, Position.deriveLeverage(100, 2_000L, 500L));
        assertEquals(1, Position.deriveLeverage(10, PRICE, PositionMath.marginFor(PRICE, 10, 1)));
        assertEquals(0, Position.deriveLeverage(0, PRICE, 200_000L), "没有仓位就没有杠杆");
    }
}
