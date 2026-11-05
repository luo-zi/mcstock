package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「空中飞人」判定（{@link AerialistJudge}）的单测 —— 纯 Java，不碰游戏。
 *
 * <p>玩家口径：**负债 + 一百格摔落致死** 才触发（授成就 + 清债）。这一组用例就是那条口径的锚，
 * 顺带把四个边界钉死：刚好 100.0 算达标、0 分不算负债、负数高度不算、没摔死不算。
 */
class AerialistJudgeTest {

    /** 负债一金币（-1 分就够，不用大数）。 */
    private static final long IN_DEBT = -1L;
    /** 刚好没钱。 */
    private static final long BROKE = 0L;
    /** 有钱。 */
    private static final long RICH = 100_000L;

    @Test
    void debtPlusHundredBlockFallKillsTriggers() {
        assertTrue(AerialistJudge.isAerialist(IN_DEBT, 101.0, true), "负债 + 101 格 + 摔死 → 触发");
        assertTrue(AerialistJudge.isAerialist(-123_456L, 250.5, true), "欠得越多越算（只看正负）");
    }

    @Test
    void justUnderAHundredBlocksDoesNotTrigger() {
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, 99.0, true), "99 格不够");
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, 99.99, true), "99.99 格也不够");
    }

    /** 边界：**刚好 100.0 格算达标**（口径是 ≥ 100）。 */
    @Test
    void exactlyOneHundredBlocksCounts() {
        assertTrue(AerialistJudge.isAerialist(IN_DEBT, 100.0, true), "100.0 格必须算达标");
        assertTrue(AerialistJudge.isAerialist(IN_DEBT, 100.0001, true));
    }

    @Test
    void withoutDebtItDoesNotTrigger() {
        assertFalse(AerialistJudge.isAerialist(BROKE, 100.0, true), "现金为 0 不算负债");
        assertFalse(AerialistJudge.isAerialist(BROKE, 500.0, true), "没钱但也不欠钱：不触发");
        assertFalse(AerialistJudge.isAerialist(RICH, 200.0, true), "有钱人摔死只是摔死");
    }

    @Test
    void withoutDyingItDoesNotTrigger() {
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, 200.0, false), "没摔死（比如被水接住/吃图腾）不算");
    }

    @Test
    void garbageDistanceNeverTriggers() {
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, -5.0, true), "负的摔落距离不算");
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, Double.NaN, true), "NaN 不算（比较恒为 false）");
        assertFalse(AerialistJudge.isAerialist(IN_DEBT, Double.NEGATIVE_INFINITY, true));
    }

    /** 三条条件缺一不可的交叉验证（8 种组合里只有 1 种成立）。 */
    @Test
    void onlyAllThreeConditionsTogetherTrigger() {
        int triggered = 0;
        for (long cash : new long[]{IN_DEBT, BROKE}) {
            for (double fall : new double[]{99.9, 100.0}) {
                for (boolean died : new boolean[]{false, true}) {
                    if (AerialistJudge.isAerialist(cash, fall, died)) {
                        triggered++;
                        assertTrue(cash < 0 && fall >= 100.0 && died);
                    }
                }
            }
        }
        // 只有「负债 + 100.0 + 摔死」这一种组合成立（BROKE/99.9 那些都不行）
        assertTrue(triggered == 1, "8 种组合里只该有 1 种触发，实际 " + triggered);
    }
}
