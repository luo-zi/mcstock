package com.qiushaonan.mcstock.client;

/**
 * 回收明细的**一行**：单价与小计的**纯计算**（不碰任何 Minecraft 类，可直接单测）。
 *
 * <p>⚠️⚠️ 存在的理由是一次真机 bug：三种不同物品只要**件数相同**，
 * 明细里就显示出**完全一样**的单价与小计（截图：32 下界合金锭 / 32 金锭 /
 * 32 下界合金碎片 三行都是 {@code 776.66 / 24053.12}）。
 * <p>根因：客户端拿不到估值表，早期只收到「合计（分）」与「总件数」，
 * 于是用 {@code 合计 ÷ 总件数} 反推了一个**均价**，循环里**每一行都用这同一个值** ——
 * 而<b>均价不是单价</b>。现在服务端把**逐格**的单件价一起发过来（{@code S2CSlotValue#unitValues}），
 * 每一行取**它自己那种物品**的单价、乘**它自己的件数**。
 *
 * <p>把这段算法单独放成纯函数，就是为了让它能被 {@code RecycleRowTest} 直接锁死 ——
 * 「取数取错」这一类 bug，单测一次就能拦住。
 */
public record RecycleRow(int count, long unitCents, long amountCents) {

    /**
     * 算一行明细。
     *
     * @param count     这一种物品的总件数
     * @param unitCents 这一种物品的**单件**回收价（分）—— 来自服务端逐格单价，不是均价
     * @return 单价 = 单件价；小计 = 单价 × 件数
     */
    public static RecycleRow of(int count, long unitCents) {
        long unit = Math.max(0, unitCents);
        int n = Math.max(0, count);
        return new RecycleRow(n, unit, unit * n);
    }

    /**
     * 从**逐格单价**里取某一种物品占用的那些槽位中最大的那个单价（分）。
     *
     * <p>同一种物品可能占好几格；取最大是保守做法（估值表按注册名给价，
     * 同一种物品的单价本来就应该一样，取最大只是防手滑）。
     */
    public static long unitFromSlots(int[] slotUnits, int[] slots) {
        long unit = 0;
        if (slotUnits == null || slots == null) {
            return 0;
        }
        for (int slot : slots) {
            if (slot >= 0 && slot < slotUnits.length) {
                unit = Math.max(unit, slotUnits[slot]);
            }
        }
        return unit;
    }

    /** 这一行是否要计入总价（件数为 0 或估值 0 的物品不计）。 */
    public boolean counted() {
        return this.count > 0 && this.unitCents > 0;
    }
}
