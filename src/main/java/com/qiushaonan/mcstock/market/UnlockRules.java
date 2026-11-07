package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 「解锁」的纯规则：**投放区里凑满一组，才承认你确实有这个物品的产能**。
 *
 * <p>为什么门槛是「一组」而不是 1 个：一组是**游戏自己给出的批量定义**
 * （{@code ItemStack#getMaxStackSize()}：材料 64、部分模组 16、工具护甲 1）。
 * 玩家舍得把一整组交出去，说明这玩意儿要么便宜到可以自己量产，要么他真的建出了产线 ——
 * 两种情况都意味着「他不需要靠购买来跳级」。这个门槛**不需要任何配置**，
 * 而且天然适配任何整合包（模组自己定的堆叠上限就是它的难度刻度）。
 *
 * <h2>为什么这条不参与估值</h2>
 * 它只看「投放区里有什么、够不够一组」，不看价格。价格在 {@link ItemValuator} 里算，
 * 两件事刻意不互相依赖：估值表整个建不起来时，解锁页最多是「不知道解锁后多少钱」，
 * 而不是整页失效。
 *
 * <h2>⚠️ 消耗口径</h2>
 * <ul>
 *   <li>只消耗**恰好一组**（{@code need} 个），多出来的**原样留在投放区**；</li>
 *   <li>已经解锁 / 本来就不需要解锁的物品**一件都不动**（见 {@link Candidate#already()}）；</li>
 *   <li>不足一组的物品**一件都不动**（不让玩家「先交一半」）。</li>
 * </ul>
 * 这三条是「不静默吞玩家东西」的具体落地，单测在 {@link com.qiushaonan.mcstock.market.UnlockRulesTest}。
 */
public final class UnlockRules {

    /**
     * 需要解锁的最低买价（金币）：**只有贵的物品才上锁**。
     *
     * <p>取 500 的理由：低于它的东西（泥土、铁、木材、常见矿物）本来就该在购买页随手买，
     * 给它们上锁只会让界面变成一片灰。真正会把「开局捡点东西换后期物品」做实的是高价物品，
     * 而 500 金币 ≈ 8 分钟劳动（按 60 金币/分钟），这是「随便捡点」够不到、但正常玩一定过得去的线。
     */
    public static final int MIN_PRICE_TO_LOCK = 500;

    private UnlockRules() {
    }

    /**
     * 一个物品在投放区里的解锁进度。
     *
     * @param item    物品 id
     * @param have    投放区里的总件数（跨格子累加）
     * @param need    一组是多少（该物品的堆叠上限，至少 1）
     * @param already {@code true} = 不用做任何事（已经解锁，或者本来就不需要解锁）
     */
    public record Candidate(ResourceLocation item, int have, int need, boolean already) {

        /** 现在按下去就能解锁。 */
        public boolean complete() {
            return !already && need > 0 && have >= need;
        }

        /** 放了东西但不够一组（界面要显示「还差 N 个」）。 */
        public boolean partial() {
            return !already && have > 0 && have < need;
        }

        /** 还差多少个才够一组（已经够 / 已解锁时为 0）。 */
        public int missing() {
            return complete() || already ? 0 : Math.max(0, need - have);
        }
    }

    /**
     * 这件物品要不要上锁。
     *
     * <p>⚠️ <b>有种子的物品（数据包手写价的那些）一律不上锁</b>：它们是原版基础物资，
     * 也是整张兑换尺度的锚（{@code ExchangeRateTest} 守的就是它们），
     * 给它们上锁等于把购买页清空 —— 那不是这套机制想要的效果。
     * 上锁的只有「靠配方推导出价格的模组物品」，也就是整合包里真正会「以材料价买到后期货」的那一批。
     *
     * @param purchasePrice 采购价（金币）；{@code <= 0} 表示不可购买
     * @param seeded        这一项的价格是不是数据包直接写的（{@link ItemValuator.Origin}）
     */
    public static boolean requiresUnlock(int purchasePrice, boolean seeded) {
        return !seeded && purchasePrice >= MIN_PRICE_TO_LOCK;
    }

    /**
     * 把「投放区里有什么」摊成逐物品的解锁计划。
     *
     * <p>按物品 id 排序输出：同一份投放区在任何机器上算出来的顺序一致，
     * 提示文案与单测才好对。{@code counts} 里件数为 0 或负数的条目直接丢掉（不参与判定）。
     *
     * @param counts          物品 id → 投放区里的总件数
     * @param stackSize       该物品的一组是多少（生产端传 {@code ItemStack#getMaxStackSize()}）
     * @param alreadyUnlocked 这一项是不是「已经不用再解锁了」
     */
    public static List<Candidate> plan(Map<ResourceLocation, Integer> counts,
                                       ToIntFunction<ResourceLocation> stackSize,
                                       Predicate<ResourceLocation> alreadyUnlocked) {
        List<Candidate> out = new ArrayList<>();
        if (counts == null || counts.isEmpty()) {
            return out;
        }
        for (Map.Entry<ResourceLocation, Integer> entry : new TreeMap<>(counts).entrySet()) {
            ResourceLocation item = entry.getKey();
            Integer raw = entry.getValue();
            if (item == null || raw == null || raw <= 0) {
                continue;
            }
            int need = stackSize == null ? 1 : Math.max(1, stackSize.applyAsInt(item));
            boolean already = alreadyUnlocked != null && alreadyUnlocked.test(item);
            out.add(new Candidate(item, raw, need, already));
        }
        return out;
    }

    /** 这一份计划里有几项现在就能解锁（按钮是否可用的唯一判据）。 */
    public static int readyCount(List<Candidate> plan) {
        int n = 0;
        for (Candidate c : plan) {
            if (c.complete()) {
                n++;
            }
        }
        return n;
    }
}
