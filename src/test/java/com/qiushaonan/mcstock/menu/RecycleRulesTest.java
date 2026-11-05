package com.qiushaonan.mcstock.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收 / 自动还债的**两条判据**单测（纯 Java，不碰任何 Minecraft 类）。
 *
 * <p>守的是三次规则调整里最后那次的边界（见 {@link RecycleRules} 类注释）：
 * <ul>
 *   <li><b>手动回收</b>：<b>未使用过</b>（{@code damage == 0}）的装备<b>可以卖</b> ——
 *       这是本次的回归点（玩家：「未使用过的武器装备无法回收」）；用过的、终端不收。</li>
 *   <li><b>自动还债</b>：任何有耐久的物品都不卖（<b>含全新</b>）——
 *       未经玩家同意的变卖，宁可少还债。</li>
 * </ul>
 *
 * <p>真实取值（已用 {@code javap -c} 核实）：
 * {@code getMaxDamage() → getOrDefault(MAX_DAMAGE, 0)}、
 * {@code getDamageValue() → clamp(getOrDefault(DAMAGE, 0), 0, getMaxDamage())}，
 * 所以「没有耐久的普通材料两项都是 0」「全新的装备 damage 是 0」。
 */
class RecycleRulesTest {

    /** 没有耐久条的普通材料（泥土 / 圆石 / 钻石…）：两项都是 0。 */
    private static final int NO_DURABILITY = 0;
    private static final int NO_DAMAGE = 0;
    /** 钻石剑 / 镐的最大耐久。 */
    private static final int SWORD_MAX = 1561;
    /** 鞘翅 432、盾 336 —— 换几个不同的数值，确认判据不看具体数字。 */
    private static final int ELYTRA_MAX = 432;

    // ==================== ① 全新装备：手动收、自动不收（本次回归点）====================

    /**
     * ⭐ **本次回归点**：全新的钻石剑 —— 手动回收**可以卖**，自动还债**不卖**。
     *
     * <p>之前被写成「有耐久的都不收」，于是刚做出来的剑也进不去回收栏；
     * 而一把新剑本身就等于一堆材料（估价按配方推导），不让卖等于把材料锁死在装备形态里。
     */
    @Test
    void brandNewSwordIsSellableManuallyButNotByAutoSell() {
        assertTrue(RecycleRules.acceptsManual(SWORD_MAX, NO_DAMAGE, false),
                "全新的剑**能**手动回收（本次回归点）");
        assertFalse(RecycleRules.acceptsAutoSell(SWORD_MAX, false),
                "全新的剑**不能**被自动还债卖掉（未经同意的变卖）");
    }

    /** 附魔 / 带自定义组件的装备：判据只看损伤，不看有没有别的组件。 */
    @Test
    void enchantedOrCustomGearCountsAsUnused() {
        assertTrue(RecycleRules.acceptsManual(2031, NO_DAMAGE, false), "附魔未使用的镐也能手动回收");
        assertTrue(RecycleRules.acceptsManual(ELYTRA_MAX, NO_DAMAGE, false), "全新鞘翅同理");
        assertFalse(RecycleRules.acceptsAutoSell(2031, false), "但自动还债不碰它");
    }

    // ==================== ② 用过的：两条都不收 ====================

    /** 用过的（掉了耐久）两条都拒收 —— 否则就是把工具白用一场再原价卖回去。 */
    @Test
    void usedGearIsRejectedByBoth() {
        assertFalse(RecycleRules.acceptsManual(SWORD_MAX, 100, false), "用过的剑手动也不收");
        assertFalse(RecycleRules.acceptsManual(SWORD_MAX, 1, false), "哪怕只掉 1 点耐久，也不收");
        assertFalse(RecycleRules.acceptsAutoSell(SWORD_MAX, false), "自动还债当然也不收");
    }

    /** 边界：damage 正好 0 收、正好 1 不收。 */
    @Test
    void damageZeroBoundary() {
        assertTrue(RecycleRules.acceptsManual(SWORD_MAX, 0, false), "damage=0 → 收");
        assertFalse(RecycleRules.acceptsManual(SWORD_MAX, 1, false), "damage=1 → 不收");
    }

    // ==================== ③ 普通材料：两条都收 ====================

    @Test
    void plainMaterialIsAcceptedByBoth() {
        assertTrue(RecycleRules.acceptsManual(NO_DURABILITY, NO_DAMAGE, false), "泥土手动收");
        assertTrue(RecycleRules.acceptsAutoSell(NO_DURABILITY, false), "泥土自动也收");
    }

    // ==================== ④ 终端：两条都不收 ====================

    @Test
    void terminalIsRejectedByBoth() {
        assertFalse(RecycleRules.acceptsManual(NO_DURABILITY, NO_DAMAGE, true), "终端手动不收");
        assertFalse(RecycleRules.acceptsAutoSell(NO_DURABILITY, true), "终端自动也不收");
    }

    /** 终端本身没有耐久；就算以后给它加了耐久，也照样两条都拒收。 */
    @Test
    void terminalIsRejectedEvenWithDurability() {
        assertFalse(RecycleRules.acceptsManual(SWORD_MAX, NO_DAMAGE, true), "有耐久 + 终端 → 手动不收");
        assertFalse(RecycleRules.acceptsAutoSell(SWORD_MAX, true), "有耐久 + 终端 → 自动不收");
    }

    // ==================== 全真值表 ====================

    /** 两条判据的完整真值表（含全新装备那一列的分歧），一次钉死。 */
    @Test
    void fullTruthTable() {
        assertPair(NO_DURABILITY, NO_DAMAGE, false, true, true);        // 普通材料
        assertPair(SWORD_MAX, NO_DAMAGE, false, true, false);           // ⭐ 全新装备：手动收、自动不收
        assertPair(SWORD_MAX, 100, false, false, false);                // 用过的装备
        assertPair(NO_DURABILITY, NO_DAMAGE, true, false, false);       // 终端
        assertPair(SWORD_MAX, NO_DAMAGE, true, false, false);           // 终端 + 耐久
    }

    private static void assertPair(int max, int damage, boolean terminal,
                                   boolean expectedManual, boolean expectedAuto) {
        String what = "max=" + max + " damage=" + damage + " terminal=" + terminal;
        if (expectedManual) {
            assertTrue(RecycleRules.acceptsManual(max, damage, terminal), "手动应收：" + what);
        } else {
            assertFalse(RecycleRules.acceptsManual(max, damage, terminal), "手动应不收：" + what);
        }
        if (expectedAuto) {
            assertTrue(RecycleRules.acceptsAutoSell(max, terminal), "自动应收：" + what);
        } else {
            assertFalse(RecycleRules.acceptsAutoSell(max, terminal), "自动应不收：" + what);
        }
    }

    /** 防御性：负的 damage / maxDamage 不该被当成「用过」或「有耐久」。 */
    @Test
    void negativesAreTreatedAsUnused() {
        assertTrue(RecycleRules.acceptsManual(-1, NO_DAMAGE, false), "负 maxDamage 按「没有耐久」");
        assertTrue(RecycleRules.acceptsManual(NO_DURABILITY, -1, false), "负 damage 按「没用过」");
        assertTrue(RecycleRules.acceptsAutoSell(-1, false), "负 maxDamage 按「没有耐久」");
    }
}
