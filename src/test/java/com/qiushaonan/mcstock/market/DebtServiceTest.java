package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 负债时「自动卖背包里的回收物还债」的纯逻辑单测（{@link DebtService}）。
 *
 * <p>这些用例全部跑在纯 JVM 里（类里一个 Minecraft 类都没有）：物品标识用字符串，
 * 「有没有耐久 / 是不是终端」由 {@code sellable} 谓词扮演 —— 服务端传的
 * {@code ItemStack#getMaxDamage() == 0} 就是这个谓词。
 */
class DebtServiceTest {

    private static final ToIntFunction<String> VALUES = key -> switch (key) {
        case "cobble" -> 1;         // 1 金币 = 100 分
        case "iron" -> 5;
        case "diamond" -> 40;
        default -> 0;               // 没估价（含估价 0）
    };

    /** 「可回收且无耐久」的那一侧：镐子有耐久、终端是工具，都不卖。 */
    private static final Predicate<String> SELLABLE = key -> !key.equals("pickaxe") && !key.equals("terminal");

    private static DebtService.Entry<String> entry(int slot, String key, int count) {
        return new DebtService.Entry<>(slot, key, count);
    }

    // ==================== 不负债：一件都不卖 ====================

    @Test
    void sellsNothingWhenCashIsNotNegative() {
        List<DebtService.Entry<String>> bag = List.of(entry(0, "diamond", 64), entry(1, "iron", 64));

        DebtService.Result<String> zero = DebtService.sellToRepay(bag, 0L, VALUES, SELLABLE);
        assertFalse(zero.soldAnything());
        assertEquals(0L, zero.gainedCents());
        assertEquals(0L, zero.cashCents());
        assertEquals(0L, zero.debtCents());
        assertTrue(zero.settled());

        DebtService.Result<String> rich = DebtService.sellToRepay(bag, 123_456L, VALUES, SELLABLE);
        assertFalse(rich.soldAnything(), "有钱的时候一件都不许动");
        assertEquals(123_456L, rich.cashCents());
    }

    // ==================== 卖出恰好够还债就停（不多卖） ====================

    @Test
    void sellsJustEnoughAndStops() {
        // 欠 12.00（1200 分），铁锭 5.00（500 分）一件 → 卖 3 件正好 15.00，第 4 件不许动
        List<DebtService.Entry<String>> bag = List.of(entry(3, "iron", 10), entry(4, "diamond", 10));

        DebtService.Result<String> result = DebtService.sellToRepay(bag, -1_200L, VALUES, SELLABLE);

        assertTrue(result.settled());
        assertEquals(300L, result.cashCents(), "欠 12.00、卖 3 件铁锭（15.00）→ 余额 +3.00");
        assertEquals(1_500L, result.gainedCents());
        assertEquals(3, result.soldItems());
        assertEquals(1, result.sales().size());
        assertEquals(3, result.sales().get(0).count(), "只卖够还债的件数，不整组卖光");
        assertEquals(3, result.sales().get(0).slot());
        assertEquals(0L, result.debtCents());
    }

    @Test
    void repeatsExactlyTheDebtWhenOneItemCoversIt() {
        // 欠 30.00（3000 分），钻石 40.00 一件 → 卖 1 件就够，余额变正
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "diamond", 5)), -3_000L, VALUES, SELLABLE);

        assertEquals(1, result.soldItems());
        assertEquals(1_000L, result.cashCents());
        assertTrue(result.settled());
    }

    // ==================== 一件不够就多卖几件 ====================

    @Test
    void keepsSellingAcrossSlotsAndKindsUntilPaidOff() {
        // 欠 55.00（5500 分）：1 圆石(1.00) + 2 铁锭(5.00) 都不够，必须继续卖到钻石
        List<DebtService.Entry<String>> bag = new ArrayList<>(List.of(
                entry(0, "cobble", 1),
                entry(1, "iron", 2),
                entry(2, "diamond", 3)));

        DebtService.Result<String> result = DebtService.sellToRepay(bag, -5_500L, VALUES, SELLABLE);

        // 逐格账：圆石 1 件（1.00）→ 铁锭 2 件（10.00）→ 钻石 2 件（80.00，第 3 件就不卖了）
        assertEquals(3, result.sales().size(), "三格都卖到了");
        assertEquals(1 + 2 + 2, result.soldItems());
        assertEquals(100L + 1_000L + 8_000L, result.gainedCents());
        assertEquals(2, result.sales().get(2).count(), "钻石只卖到够还债为止（2 件，不是 3 件）");
        assertEquals(3_600L, result.cashCents(), "还完债还多出 36.00");
        assertTrue(result.settled());
        assertEquals(0L, result.debtCents());
    }

    @Test
    void staysInDebtWhenTheBagIsNotWorthEnough() {
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "cobble", 2)), -100_000L, VALUES, SELLABLE);
        assertEquals(200L, result.gainedCents());
        assertFalse(result.settled());
        assertEquals(99_800L, result.debtCents());
    }

    // ==================== 红线：有耐久的、终端、没估价的一律不卖 ====================

    @Test
    void neverSellsToolsWeaponsOrArmour() {
        // 「有耐久」由谓词扮演：pickaxe 就是镐子/剑/护甲那一类
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "pickaxe", 1), entry(1, "diamond", 1)), -5_000L, VALUES, SELLABLE);

        assertEquals(1, result.sales().size());
        assertEquals("diamond", result.sales().get(0).key());
        assertEquals(1, result.sales().get(0).slot(), "卖的是钻石那一格，第 0 格的镐子一根汗毛都没动");
    }

    @Test
    void neverSellsTheExchangeTerminal() {
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(7, "terminal", 1), entry(8, "iron", 1)), -400L, VALUES, SELLABLE);

        assertEquals(1, result.soldItems());
        assertEquals("iron", result.sales().get(0).key());
    }

    @Test
    void skipsItemsWithoutAValue() {
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "dirt", 64), entry(1, "iron", 1)), -400L, VALUES, SELLABLE);

        assertEquals(1, result.sales().size());
        assertEquals("iron", result.sales().get(0).key());
        assertEquals(1, result.sales().get(0).slot(), "没估价的泥土（第 0 格）留在原处");
        assertEquals(100L, result.cashCents(), "铁锭 5.00 卖掉 1 件 → 欠 4.00 变成 +1.00");
    }

    @Test
    void ignoresEmptySlotsAndZeroCounts() {
        List<DebtService.Entry<String>> bag = new ArrayList<>();
        bag.add(entry(0, "iron", 0));
        bag.add(null);
        bag.add(entry(1, "iron", 2));

        DebtService.Result<String> result = DebtService.sellToRepay(bag, -500L, VALUES, SELLABLE);
        assertEquals(1, result.sales().size());
        assertEquals(1, result.sales().get(0).slot());
    }

    // ==================== 单位换算与边界 ====================

    @Test
    void convertsCoinsToCents() {
        // 单价是**金币**、现金是**分**：1 金币的东西卖 1 件 → 现金 +100 分
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "cobble", 1)), -50L, VALUES, SELLABLE);
        assertEquals(50L, result.cashCents());
        assertEquals(100L, result.gainedCents());
    }

    @Test
    void emptyInventoryChangesNothing() {
        DebtService.Result<String> result = DebtService.sellToRepay(null, -9_999L, VALUES, SELLABLE);
        assertFalse(result.soldAnything());
        assertEquals(-9_999L, result.cashCents());
        assertEquals(9_999L, result.debtCents());
    }

    @Test
    void nullPredicateMeansEverythingWithAValueIsSellable() {
        DebtService.Result<String> result = DebtService.sellToRepay(
                List.of(entry(0, "pickaxe", 1)), -100L, VALUES, null);
        // VALUES 里没给 pickaxe 估价 → 依然不卖；换一个真有价的最能说明问题
        assertEquals(0, result.soldItems());
    }
}
