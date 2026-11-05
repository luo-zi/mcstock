package com.qiushaonan.mcstock.market;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * **负债时的自动还债**：从背包里挑出「可回收且不会伤到玩家」的物品，按**回收价**卖掉，
 * 直到债务还清为止。纯 Java（不 import 任何 Minecraft 类），单测直接在纯 JVM 里跑。
 *
 * <h2>玩家口径（原话）</h2>
 * <p>「负债情况下挖到的可回收直接卖掉还款」。所以：
 * <ul>
 *   <li><b>只在负债时工作</b>：{@code cash >= 0} 时一件都不卖（{@link Result#sales()} 为空）；</li>
 *   <li><b>还清就停</b>：现金回到 0 或正数的那一刻立刻收手，**不多卖一件**；</li>
 *   <li><b>按回收价</b>（{@code ItemValuator.value}，与回收页同一个价），
 *       估价是**金币**、现金是**分**，换算在这里做（{@code ×100}）；</li>
 *   <li><b>有耐久的东西一律不卖</b>：镐子 / 剑 / 护甲绝不能被动 —— 这条由调用方通过
 *       {@code sellable} 谓词传入（服务端那边就是 {@code ItemStack#getMaxDamage() == 0}），
 *       本类只保证「谓词说不卖的就一定不卖」。</li>
 * </ul>
 *
 * <h2>为什么要泛型 {@code <K>}</h2>
 * <p>物品的标识在服务端是 {@code Item}、在数据包/单测里可能是别的类型；而
 * {@code net.minecraft.resources.ResourceLocation} 本身就是 Minecraft 类，
 * 一旦出现在签名里这个类就没法在纯 JVM 里单测（AGENTS.md 第 6.11 条）。
 * 所以这里用 {@code K} 当「物品标识」，服务端传 {@code Item}、单测传 {@code String}。
 *
 * <p>⚠️ {@code unitValueCoins} 是**单价（金币）**，与 {@code ItemValuator.value} 同一个口径；
 * 返回 {@code <= 0} 表示「没估价」，一律不卖（别在这里做单位换算，换算只在本类里做一次）。
 *
 * <p>参数顺序 = 遍历顺序：先扫到的先卖（服务端传的是「背包 0..35 + 副手」），
 * 所以「先卖哪一件」是可预期的，不需要额外排序（排序反而会让玩家的背包顺序看起来乱跳）。
 */
public final class DebtService {

    private DebtService() {
    }

    /**
     * 背包里的一格。
     *
     * @param slot  格子下标（本类只负责原样回传；服务端用它把物品从正确的槽位扣掉）
     * @param key   物品标识（服务端传 {@code Item}，单测传字符串）
     * @param count 这一格的件数（{@code <= 0} 直接跳过）
     */
    public record Entry<K>(int slot, K key, int count) {
    }

    /**
     * 一次卖出。
     *
     * @param slot        从哪一格卖的
     * @param key         卖的什么
     * @param count       卖了几件
     * @param amountCents 这一笔卖了多少**分**
     */
    public record Sale<K>(int slot, K key, int count, long amountCents) {
    }

    /**
     * 一次自动还债的结果。
     *
     * @param cashCents   结算后的现金（分；负数 = 还欠着）
     * @param debtCents   剩余债务（分，{@code >= 0}；0 = 已还清）
     * @param gainedCents 这次卖东西一共卖了多少钱（分）
     * @param soldItems   一共卖了几件
     * @param sales       逐笔明细（调用方按它扣物品；空列表 = 什么都没卖）
     */
    public record Result<K>(long cashCents, long debtCents, long gainedCents, int soldItems,
                            List<Sale<K>> sales) {

        /** 这一轮有没有卖掉东西（用来决定要不要推提示）。 */
        public boolean soldAnything() {
            return soldItems > 0;
        }

        /** 债务是不是已经还清（现金回到了 0 或正数）。 */
        public boolean settled() {
            return debtCents <= 0;
        }
    }

    /**
     * 从一组「物品 → 数量」里挑出该卖的，返回总售价（分）与剩余债务。**纯函数，不改任何入参**。
     *
     * @param inventory      按顺序检查的格子（服务端：背包 36 格 + 副手；{@code null} 视为空背包）
     * @param cashCents      当前现金（分，负数 = 负债）
     * @param unitValueCoins 单价（**金币**）；{@code <= 0} = 没估价 → 不卖
     * @param sellable       这一格能不能卖（服务端：排除交易所终端、排除**任何有耐久**的物品、
     *                       排除回收栏里的东西）；传 {@code null} = 全部可卖
     * @return 结算结果；现金非负时返回「什么都没卖、现金原样」
     */
    public static <K> Result<K> sellToRepay(List<Entry<K>> inventory, long cashCents,
                                            ToIntFunction<K> unitValueCoins, Predicate<K> sellable) {
        List<Sale<K>> sales = new ArrayList<>();
        long cash = cashCents;
        long gained = 0L;
        int sold = 0;
        if (inventory != null && unitValueCoins != null && cash < 0) {
            for (Entry<K> entry : inventory) {
                if (cash >= 0L) {
                    break;              // ★ 债还清就立刻停：后面的格子一件都不动
                }
                if (entry == null || entry.key() == null || entry.count() <= 0) {
                    continue;
                }
                if (sellable != null && !sellable.test(entry.key())) {
                    continue;           // 不能卖的（终端 / 有耐久 / 回收栏）直接跳过
                }
                int unit = unitValueCoins.applyAsInt(entry.key());
                if (unit <= 0) {
                    continue;           // 没估价（含估价 0）：不卖，也不要静默吞掉玩家的东西
                }
                long unitCents = unit * 100L;               // 金币 → 分
                long need = -cash;                          // 还差多少分
                // 只卖「刚好够还债」的件数（向上取整）：欠 12.00、单价 5.00 时卖 3 件（+15.00），
                // 不整组卖光；单价高于欠款时也是 1 件。
                long want = (need + unitCents - 1L) / unitCents;
                int take = (int) Math.min(entry.count(), want);
                if (take <= 0) {
                    continue;
                }
                long amount = unitCents * take;
                cash += amount;
                gained += amount;
                sold += take;
                sales.add(new Sale<>(entry.slot(), entry.key(), take, amount));
            }
        }
        long debt = cash < 0L ? -cash : 0L;
        return new Result<>(cash, debt, gained, sold, List.copyOf(sales));
    }
}
