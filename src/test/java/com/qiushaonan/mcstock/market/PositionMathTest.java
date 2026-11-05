package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓位数学与下单结算的单测（纯 Java，不需要游戏环境）。
 *
 * <p>重点守住三条（数值是 2026-10-06 调紧之后的口径）：
 * <ol>
 *   <li>爆仓线 = 保证金的 **40%**（{@code StockDefs.MARGIN_CALL_PERCENT}；×2 时离现价 20%、
 *       ×400 时 0.1%）；</li>
 *   <li>手续费按 notional 收 **0.35%**（{@code StockDefs.FEE_RATE}），杠杆越高相对越贵 ——
 *       ×400 时手续费是押金的 1.4 倍，这就是「高杠杆不适合来回做」的数学来源；</li>
 *   <li>结算按真实价格，能亏穿保证金（这是「倾家荡产」的来源，不是 bug）。</li>
 * </ol>
 *
 * <p>⚠️ 本文件里所有数字都**跟着常量走**：改 {@code FEE_RATE} / {@code MARGIN_CALL_PERCENT}
 * 就要一起改这里（并且要顺带改 {@code TradeServiceTest} 与帮助面板的文案）。
 */
class PositionMathTest {

    @Test
    void marginIsNotionalDividedByLeverage() {
        // 100 股 × 100.00 金币 = 10000.00 金币仓位，×5 → 2000.00 金币押金
        long notional = PositionMath.notional(10_000L, 100);
        assertEquals(1_000_000L, notional);
        assertEquals(200_000L, PositionMath.marginFor(10_000L, 100, 5));
        assertEquals(1_000_000L, PositionMath.marginFor(10_000L, 100, 1));
    }

    @Test
    void feeIsOnNotionalNotMargin() {
        // 1.00 金币仓位 → 手续费 0.20 分，向上取整 = 1 分
        assertEquals(1L, PositionMath.feeFor(100L));
        // 10000.00 金币仓位 → 20.00 金币 = 2000 分（0.20%）
        assertEquals(2_000L, PositionMath.feeFor(1_000_000L));
        // ×400 时手续费 / 押金 = 0.20% × 400 = 80%：手续费吃掉大半押金，来回做仍然必亏
        long margin = PositionMath.marginFor(10_000L, 100, 400);
        long fee = PositionMath.feeFor(1_000_000L);
        assertEquals(margin * 80 / 100, fee);
        assertTrue(fee > margin / 2, "高杠杆下手续费已经超过押金的一半");
    }

    @Test
    void pnlIsSignedForShorts() {
        // 多头：涨 10.00 金币 × 100 股 = +1000.00 金币 = 100000 分
        assertEquals(100_000L, PositionMath.pnl(100, 10_000L, 11_000L));
        // 空头：股数为负，价格跌了应该赚（同一个公式，不用分支）
        assertEquals(100_000L, PositionMath.pnl(-100, 10_000L, 9_000L));
        // 空头遇涨 = 亏
        assertEquals(-100_000L, PositionMath.pnl(-100, 10_000L, 11_000L));
    }

    @Test
    void liquidationPriceSitsAtTheMarginCallLine() {
        // 100 股、均价 100.00、保证金 2000.00（×5）→ 爆仓价 = 100.00 − 200000×45%/100 分 = 91.00
        assertEquals(9_100L, PositionMath.liquidationPrice(100, 10_000L, 200_000L));
        // 空头镜像：涨到 109.00 爆
        assertEquals(10_900L, PositionMath.liquidationPrice(-100, 10_000L, 200_000L));
        // ×400：100 股、均价 100.00、保证金 25.00 → 爆仓距离 = 2500×45%/100 股 = 11 分 → 99.89（0.11%）
        assertEquals(9_989L, PositionMath.liquidationPrice(100, 10_000L, 2_500L));
    }

    /** 判定线就是 45%：差 1 分不爆、正好到线爆（边界两侧各取一个点）。 */
    @Test
    void liquidationTriggersAtTheMarginCallLine() {
        // 保证金 200000 分，45% = 90000 分；100 股 → 价格跌到 10000 − 900 = 9100 正好到线
        assertFalse(PositionMath.liquidated(100, 10_000L, 200_000L, 9_101L), "差 1 分不到线：不爆");
        assertTrue(PositionMath.liquidated(100, 10_000L, 200_000L, 9_100L), "正好到线：爆");
        assertTrue(PositionMath.liquidated(100, 10_000L, 200_000L, 8_900L));
        // 空头方向相反
        assertFalse(PositionMath.liquidated(-100, 10_000L, 200_000L, 10_899L));
        assertTrue(PositionMath.liquidated(-100, 10_000L, 200_000L, 10_900L));
    }

    @Test
    void maxAffordableRespectsLeverageAndFee() {
        // 1000.00 金币现金、价 100.00、×1：每股要 100.00 + 0.35 手续费 → 9 股（第 10 股不够）
        assertEquals(9, PositionMath.maxAffordable(100_000L, 10_000L, 1));
        // ×5：每股 20.00 + 0.35 → 49 股（0.35 的手续费还不够挤掉第 50 股）
        assertEquals(49, PositionMath.maxAffordable(100_000L, 10_000L, 5));
        // 没钱 → 0
        assertEquals(0, PositionMath.maxAffordable(0L, 10_000L, 5));
    }
}
