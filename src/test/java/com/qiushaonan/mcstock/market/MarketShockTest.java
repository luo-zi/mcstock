package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲击的**衰减数学**（{@link MarketShock}）：这条曲线是整个事件系统的核心口径，
 * 所以它必须是一条能脱离引擎、脱离随机数直接断言的东西。
 */
class MarketShockTest {

    private static final int HALF_LIFE = 40;

    private static MarketShock shock(int direction, double magnitude) {
        return new MarketShock(0, "test", direction, 100L, HALF_LIFE, magnitude, 2.5, false);
    }

    /** 半衰期：拖尾贡献在 halfLife 跳后正好剩 50%（容差 5%）。 */
    @Test
    void tailHalvesEveryHalfLife() {
        MarketShock shock = shock(+1, 6.0);
        double initial = shock.tailUnits(100L);
        double atHalfLife = shock.tailUnits(100L + HALF_LIFE);
        assertEquals(0.5, atHalfLife / initial, 0.05, "半衰期后必须剩一半（半衰期的定义）");
        assertEquals(0.25, shock.tailUnits(100L + 2L * HALF_LIFE) / initial, 0.02, "两个半衰期后剩 1/4");
        assertEquals(1.0, shock.decay(100L), 1e-12, "触发那一跳衰减系数是 1");
    }

    /** 拖尾单调递减，且 3 个半衰期之后判为「活着但不值一提」（12.5%）。 */
    @Test
    void tailIsMonotonicAndEventuallyDies() {
        MarketShock shock = shock(+1, 6.0);
        double previous = Double.MAX_VALUE;
        for (int age = 0; age <= shock.lifeJumps(); age++) {
            double value = shock.tailUnits(100L + age);
            assertTrue(value < previous, "拖尾必须逐跳递减（age=" + age + "）");
            previous = value;
        }
        assertTrue(shock.alive(100L + shock.lifeJumps() - 1));
        assertFalse(shock.alive(100L + shock.lifeJumps()), "3 个半衰期之后不再算「活着」");
        assertEquals(0.125, shock.tailUnits(100L + 3L * HALF_LIFE) / shock.tailUnits(100L), 0.001);
    }

    /**
     * **归一化**：一条冲击的累计影响 = magnitude × (1 − STEP_SHARE)（拖尾部分）
     * ＋ magnitude × STEP_SHARE（触发那一跳的跳变）= **magnitude**（也就是事件表里的 3~8σ）。
     *
     * <p>这条断言是本次数值设计的核心：照字面把 3~8σ 当成「每跳 drift」的话，
     * 累计影响会是 halfLife/ln2 ≈ 58 倍，一条新闻就把价格顶到上限。
     */
    @Test
    void totalImpactEqualsTheDeclaredMagnitude() {
        MarketShock shock = shock(+1, 6.0);
        double total = 0.0;
        // 累加到 10 个半衰期：拖尾覆盖 1 − 2⁻¹⁰ = 99.9%，误差远小于容差
        for (int age = 0; age <= 10 * HALF_LIFE; age++) {
            total += shock.driftUnits(100L + age);
        }
        assertEquals(6.0, total, 0.01, "整条冲击的累计影响必须等于表里写的幅度（σ 倍数）");
    }

    /** 跳变只发生在触发那一跳，且方向正确、带符号。 */
    @Test
    void stepOnlyHappensOnTheTriggerJump() {
        MarketShock up = shock(+1, 5.0);
        assertEquals(5.0 * MarketShock.STEP_SHARE, up.stepUnits(100L), 1e-12);
        assertEquals(0.0, up.stepUnits(101L), 1e-12, "跳变只允许出现在 age = 0");

        // 「急动」的含义：触发那一跳的位移是拖尾第一跳的几十倍（否则根本看不见事件）
        assertEquals(5.0 * 0.3 * up.tailNorm(), up.tailUnits(100L), 1e-12, "拖尾起手值 = 剩下的 30% × 归一化系数");
        assertTrue(up.stepUnits(100L) > 20.0 * up.tailUnits(100L),
                "触发那一跳必须是「急动」：跳变远大于拖尾单跳");

        MarketShock down = shock(-1, 5.0);
        assertTrue(down.stepUnits(100L) < 0 && down.tailUnits(100L) < 0, "利空的贡献必须是负的");
        assertEquals(-up.stepUnits(100L), down.stepUnits(100L), 1e-12);
    }

    /** 幅度恒为正（方向由 direction 表达），外部按权重折符号时不会出现负幅度。 */
    @Test
    void magnitudeIsAlwaysNonNegativeByConstruction() {
        MarketShock shock = new MarketShock(2, "x", -1, 0L, 20, 4.0 * 0.25, 2.0, true);
        assertTrue(shock.magnitude() >= 0.0);
        assertTrue(shock.driftUnits(0L) < 0.0, "负方向 × 正幅度 = 利空");
        assertTrue(shock.clarification());
    }

    /** σ 放大：起手就是 sigmaMultiple，之后平滑回落到 1.0（不是突然掉回去）。 */
    @Test
    void sigmaScaleStartsAtTheMultiplierAndDecaysToParity() {
        MarketShock shock = shock(+1, 6.0);
        assertEquals(2.5, shock.sigmaScale(100L), 1e-12);
        assertEquals(1.75, shock.sigmaScale(100L + HALF_LIFE), 1e-12, "半衰期后放大倍数也减半");
        assertTrue(shock.sigmaScale(100L + shock.lifeJumps()) < 1.2, "3 个半衰期之后基本回到 1 倍");
        double previous = Double.MAX_VALUE;
        for (int age = 0; age <= shock.lifeJumps(); age++) {
            double scale = shock.sigmaScale(100L + age);
            assertTrue(scale <= previous && scale >= 1.0, "σ 倍率必须单调回落到 1.0，age=" + age);
            previous = scale;
        }
    }

    /** age 不会因为「读档时 jump 比 startJump 小」而变成负数。 */
    @Test
    void ageClampsAtZero() {
        MarketShock shock = shock(+1, 6.0);
        assertEquals(0, shock.ageJumps(0L));
        assertEquals(1.0, shock.decay(0L), 1e-12, "jump 早于 startJump 时按「刚刚触发」处理");
    }
}
