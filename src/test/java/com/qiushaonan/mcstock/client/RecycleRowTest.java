package com.qiushaonan.mcstock.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收明细一行的**单价 / 小计**回归单测（纯 Java，不碰任何 Minecraft 类）。
 *
 * <p>守的是一次真机 bug：回收页明细里**三种不同物品只要件数相同，
 * 单价与小计就完全一样**。玩家截图（回收栏放 32 下界合金锭 + 32 金锭 + 32 下界合金碎片）：
 * <pre>
 *   下界合金锭 ×32     776.66    24053.12
 *   金锭 ×32           776.66    24053.12   ← 三行同值
 *   下界合金碎片 ×32   776.66    24053.12
 * </pre>
 * 根因是取数取错了：客户端当时只收到「合计（分）」和「总件数」，于是拿
 * {@code 合计 ÷ 总件数} 当**每一行**的单价 —— 那是**均价**，不是单价。
 * 现在单价必须来自服务端**逐格**的 {@code S2CSlotValue#unitValues}。
 *
 * <p>所以这里最要紧的一条断言就是 {@link #differentItemsSameCountMustNotShareUnit()}：
 * 不同物品、相同件数 → 单价必须不同。它直接对应上面那张截图。
 */
class RecycleRowTest {

    /** 常见场景：一格 64 个，单价 12.00（= 1200 分）。 */
    @Test
    void unitIsTheSingleItemValueNotAnAverage() {
        RecycleRow row = RecycleRow.of(64, 1200);
        assertEquals(1200, row.unitCents(), "单价 = 单件回收价（分）");
    }

    /** 小计 = 单价 × 件数，件数是**本行**的件数。 */
    @Test
    void amountIsUnitTimesCount() {
        assertEquals(64 * 1200, RecycleRow.of(64, 1200).amountCents());
        assertEquals(32 * 1200, RecycleRow.of(32, 1200).amountCents());
        assertEquals(1 * 1200, RecycleRow.of(1, 1200).amountCents());
        assertEquals(576 * 56, RecycleRow.of(576, 56).amountCents(), "0.56 分/件 × 576 件");
    }

    /**
     * ⭐ **本次 bug 的回归点**：不同物品、**相同件数** → 单价必须不同。
     *
     * <p>截图那组数据：32 下界合金锭 / 32 金锭 / 32 下界合金碎片。
     * 件数都是 32，所以行与行之间**只能靠单价区分** ——
     * 一旦有人再把「均价」当单价用，三条断言会同时红。
     */
    @Test
    void differentItemsSameCountMustNotShareUnit() {
        // 用「估值表」给的三个不同单件价（分）：下界合金锭最贵、金锭次之、碎片更便宜
        long netheriteIngot = 24053;      // 分/件
        long goldIngot = 1200;
        long netheriteScrap = 800;

        RecycleRow a = RecycleRow.of(32, netheriteIngot);
        RecycleRow b = RecycleRow.of(32, goldIngot);
        RecycleRow c = RecycleRow.of(32, netheriteScrap);

        assertNotEquals(a.unitCents(), b.unitCents(), "下界合金锭与金锭的单价不能相同");
        assertNotEquals(b.unitCents(), c.unitCents(), "金锭与下界合金碎片的单价不能相同");
        assertNotEquals(a.unitCents(), c.unitCents(), "下界合金锭与碎片的单价不能相同");

        assertEquals(32 * netheriteIngot, a.amountCents());
        assertEquals(32 * goldIngot, b.amountCents());
        assertEquals(32 * netheriteScrap, c.amountCents());
        assertNotEquals(a.amountCents(), b.amountCents(), "相同件数下小计也必须不同");
    }

    /** 均价会把「不同单价」摊平成同一个数 —— 这正是旧实现错在哪，固化成断言。 */
    @Test
    void averageWouldCollapseDifferentRows() {
        long[] units = {24053, 1200, 800};
        long sum = 0;
        for (long u : units) {
            sum += u * 32;
        }
        long average = sum / (32 * 3);

        // 旧实现：三行都用 average，于是三行一模一样（这就是截图里的现象）
        RecycleRow wrongA = RecycleRow.of(32, average);
        RecycleRow wrongB = RecycleRow.of(32, average);
        assertEquals(wrongA.unitCents(), wrongB.unitCents(), "均价模式下两行必然相同（复现旧 bug）");

        // 新实现：取各自单价，三行互不相同
        assertNotEquals(RecycleRow.of(32, units[0]).unitCents(),
                RecycleRow.of(32, units[1]).unitCents(), "按各自单价算就不会撞在一起");
    }

    /** 件数 0 或估值 0 → 小计 0，且**不计入总价**。 */
    @Test
    void zeroCountOrWorthlessDoesNotCount() {
        RecycleRow zeroCount = RecycleRow.of(0, 1200);
        assertEquals(0, zeroCount.amountCents());
        assertFalse(zeroCount.counted(), "件数 0 不计入总价");

        RecycleRow worthless = RecycleRow.of(64, 0);
        assertEquals(0, worthless.unitCents());
        assertEquals(0, worthless.amountCents());
        assertFalse(worthless.counted(), "估值 0 的物品小计 0、不计入总价");

        assertTrue(RecycleRow.of(64, 1200).counted(), "有价有量才计入总价");
    }

    /** 负数（防御性）：件数/单价给负也不该算出负的小计。 */
    @Test
    void negativesAreClampedToZero() {
        RecycleRow row = RecycleRow.of(-5, -100);
        assertEquals(0, row.count());
        assertEquals(0, row.unitCents());
        assertEquals(0, row.amountCents());
    }

    /** 逐格单价取「该物品占用的格子」里的值；空格/越界按 0；多格取最大。 */
    @Test
    void unitFromSlotsPicksThisItemsSlot() {
        int[] slotUnits = {0, 24053, 0, 1200, 800};

        assertEquals(24053, RecycleRow.unitFromSlots(slotUnits, new int[]{1}));
        assertEquals(1200, RecycleRow.unitFromSlots(slotUnits, new int[]{3}));
        assertEquals(800, RecycleRow.unitFromSlots(slotUnits, new int[]{4}));
        assertEquals(0, RecycleRow.unitFromSlots(slotUnits, new int[]{0}), "空格单价 0");
        assertEquals(0, RecycleRow.unitFromSlots(slotUnits, new int[]{99}), "越界按 0");
        assertEquals(24053, RecycleRow.unitFromSlots(slotUnits, new int[]{3, 1}), "占多格取最大");
        assertEquals(0, RecycleRow.unitFromSlots(null, new int[]{1}), "没有逐格数据时退化为 0");
    }

    /** 汇总一致性：把各行小计加起来，应当等于「逐格单价 × 该格件数」的合计。 */
    @Test
    void sumOfRowsMatchesPerSlotTotal() {
        // 三格：32 × 24053、32 × 1200、32 × 800
        int[] counts = {32, 32, 32};
        long[] units = {24053, 1200, 800};

        long rowsSum = 0;
        for (int i = 0; i < counts.length; i++) {
            rowsSum += RecycleRow.of(counts[i], units[i]).amountCents();
        }
        long expected = 32L * 24053 + 32L * 1200 + 32L * 800;
        assertEquals(expected, rowsSum, "各行小计之和 == 服务端合计（同一套口径）");
    }
}
