package com.qiushaonan.mcstock.menu;

/**
 * 「这件东西能不能变成金币」的两条判据：纯计算，不碰任何 Minecraft 类，可直接单测。
 *
 * <p>⚠️⚠️ <b>两条判据故意不一样，因为「谁在卖」不一样</b>：
 * <table border="1">
 *   <caption>两种场景的规则</caption>
 *   <tr><th>场景</th><th>判据</th><th>规则</th></tr>
 *   <tr>
 *     <td><b>手动回收</b><br>（玩家自己 shift 进回收栏，再点「回收」）</td>
 *     <td>{@link #acceptsManual}</td>
 *     <td><b>未使用过</b>（{@code getDamageValue() == 0}）的装备 / 工具 / 武器 / 护甲
 *         <b>可以卖</b>（按满价）；<b>用过的</b>（{@code > 0}）不收；终端不收</td>
 *   </tr>
 *   <tr>
 *     <td><b>自动还债</b><br>（欠债时系统每秒自动变卖背包）</td>
 *     <td>{@link #acceptsAutoSell}</td>
 *     <td><b>任何有耐久的物品都不卖</b>（{@code getMaxDamage() > 0}，<b>含全新未使用</b>）；终端不收</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么手动可以卖全新的、自动却不行</b>：
 * <ul>
 *   <li>手动那条是<b>玩家自己放进去、自己点的按钮</b> —— 意图明确。而一把刚做出来的钻石剑
 *       <b>本身就等于一堆材料</b>（估价就是按配方推导出来的），不让卖等于把材料锁死在装备形态里。</li>
 *   <li>自动还债那条是<b>未经玩家同意</b>的变卖：玩家可能正穿着 / 正准备用那套装备，
 *       系统不该替他决定「这套全新的下界合金甲可以卖」。
 *       <b>宁可少还一点债，也不动玩家没用过的家当。</b></li>
 *   <li>两条的<b>共同底线</b>是「用过的不能按新价卖」（{@code damage > 0} 一律不收）——
 *       否则就是把工具白用一场再原价卖回去。</li>
 * </ul>
 *
 * <p>⚠️ 历史上的三次调整（别再往回改）：
 * <ol>
 *   <li>最早自动还债拒收有耐久、回收栏照收 → 玩家把掉耐久的下界合金剑按满价 2964.00 卖了
 *       （规则不一致 + 白用工具）；</li>
 *   <li>统一成「有耐久的都不收」→ 矫枉过正，<b>全新的</b>剑 / 镐也进不去
 *       （玩家：「未使用过的武器装备无法回收」）；</li>
 *   <li>现在按「谁在卖」分成两条（本文件）。</li>
 * </ol>
 *
 * <p>⚠️ 1.21.1 里耐久与损伤都是<b>数据组件</b>，{@code Item} 上没有这两个方法，只能问 {@code ItemStack}：
 * <pre>
 *   stack.getMaxDamage()   → Item.getMaxDamage(stack) → getOrDefault(MAX_DAMAGE, 0)
 *   stack.getDamageValue() → Item.getDamage(stack)     → clamp(getOrDefault(DAMAGE, 0), 0, getMaxDamage())
 * </pre>
 * （两条都用 {@code javap -c} 核实过实现：<b>缺组件时默认 0</b>，
 * 所以「全新的东西 {@code getDamageValue() == 0}」是可靠判据。）
 */
public final class RecycleRules {

    private RecycleRules() {
    }

    /**
     * <b>手动回收</b>收不收：未使用过（{@code damage == 0}）的都可以，用过的与终端不行。
     *
     * @param maxDamage  该物品的最大耐久（{@code ItemStack#getMaxDamage()}）；0 = 没有耐久
     * @param damage     该物品已损耗的耐久（{@code ItemStack#getDamageValue()}）；0 = 没用过
     * @param isTerminal 是不是交易所终端本身
     * @return true = 可以放进回收栏（会被按满价收）
     */
    public static boolean acceptsManual(int maxDamage, int damage, boolean isTerminal) {
        if (isTerminal) {
            return false;
        }
        // 没有耐久的普通材料：damage 恒为 0（根本没有 DAMAGE 组件），自然放行。
        // 有耐久的装备：只有「一点没用过」才收。
        // ⚠️ 判据是 damage <= 0 而**不是** maxDamage <= 0 —— 这正是本次改动的核心。
        return damage <= 0;
    }

    /**
     * <b>自动还债</b>卖不卖：任何有耐久的物品都不卖（含全新未使用），终端也不卖。
     *
     * <p>⚠️ 这里<b>故意</b>比 {@link #acceptsManual} 严：那是未经玩家同意的变卖，
     * 不能替他决定「这套全新的装备可以卖」。宁可少还一点债。
     *
     * @param maxDamage  该物品的最大耐久（{@code ItemStack#getMaxDamage()}）；0 = 没有耐久
     * @param isTerminal 是不是交易所终端本身
     * @return true = 负债时可以自动卖掉它
     */
    public static boolean acceptsAutoSell(int maxDamage, boolean isTerminal) {
        if (isTerminal) {
            return false;
        }
        return maxDamage <= 0;
    }
}
