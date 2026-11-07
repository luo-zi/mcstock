package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * 「做出一个 X 要花多少劳动时间」—— 采购价的第二条轨道。
 *
 * <h2>它和回收价是什么关系</h2>
 * <b>两件独立的事</b>，刻意不互相改写：
 * <ul>
 *   <li><b>回收价</b>（{@link ItemValuator#value}）＝材料成本，一分钱没动。
 *       它是「防印钞机」的地板：买价永远 ≥ 回收价 × 1.18，所以「买原料 → 合成 → 回收」
 *       永远亏，这条不变量由 {@code ExchangeRateTest} 守着；</li>
 *   <li><b>劳动时间</b>（本类）＝「从零做出它，一个玩家要干多久」，单位**分钟**。
 *       它只抬高**买价**：{@code 买价 = max(材料价 × 1.18, 劳动时间 × 60)}。</li>
 * </ul>
 * 抬买价永远不会造出印钞机（只会让买更难），所以两条轨道可以放心叠加成 {@code max}。
 *
 * <h2>单位：1 金币 = 1 秒劳动</h2>
 * {@link #COINS_PER_MINUTE} = 60。这个数是从「泥土 = 1 金币」倒推的：
 * 挖泥土大约 1 秒 1 个，所以 1 金币 ≈ 1 秒劳动力。
 * 于是「劳动时间（分钟）× 60 = 金币」和「价格（金币）= 冷却秒数」是同一条换算，
 * 不需要第二套标定 —— 这也是为什么冷却可以直接写成 {@code 价格 ÷ 60 分钟}。
 *
 * <h2>递推式</h2>
 * <pre>
 *   T(有种子价的物品) = 回收价 ÷ 60                     ← 锚点：数据包写的价就是劳动价
 *   T(其余物品)       = min over 配方[
 *                          (Σ 每种原料的 T × 数量) ÷ 产出数量 + 这一步的工序时间 ]
 * </pre>
 * 取 min 是**故意的**：玩家会挑最省的那条路做，价格要按他能做到的最便宜路径算。
 * 工序时间为 0 的环路（{@code 9 锭 ↔ 1 块}）因此也不会发散 —— 绕一圈总要多付一次工序，
 * min 永远选直路。
 *
 * <h2>两条硬规矩</h2>
 * <ul>
 *   <li><b>原料没价（T ≤ 0）就跳过这条配方</b>：那是「还算不出来」，不是「原料不要钱」。
 *       放松成「缺的当 0」会让任何沾了一点未知材料的物品直接塌到工序价；</li>
 *   <li><b>一条配方都不成立就留在 0</b>（查不到 = 不加价），买价退回材料价那一档。
 *       这条让「估值表在整合包里半残」变成**降级**而不是「整页买不了」。</li>
 * </ul>
 *
 * <p>纯 Java（只借 {@link ResourceLocation} 当 id），单测在 {@code LaborTimeTest}。
 */
public final class LaborTime {

    /** 1 分钟劳动值多少金币 —— 也是「1 金币 = 1 秒劳动」的另一种写法。 */
    public static final int COINS_PER_MINUTE = 60;

    /**
     * 松弛轮数上限。
     *
     * <p>比估值那边（{@link ItemValuator} 的 20 轮）宽一倍：这里的规则更严
     * （原料**全部**有价才算得出来），所以一条 N 层的链至少要 N 轮才推得到底。
     * 40 层足够覆盖任何整合包的加工深度，同时当防死循环的保险丝。
     */
    public static final int MAX_PASSES = 40;

    /** 浮点比较的容差：分钟级别的量，1e-6 分钟 = 0.06 毫秒，远小于任何有意义的差异。 */
    private static final double EPSILON = 1e-6;

    private LaborTime() {
    }

    /**
     * 算一整张劳动时间表。
     *
     * @param values    回收价表（{@link ItemValuator#value} 那一份）；只有 &gt; 0 的项才算锚点
     * @param anchored  有种子价的物品（物品种子 + 标签种子）。它们的 T 直接由回收价定死，
     *                  不参与配方推导 —— 和估值那边「种子优先」是同一条规矩
     * @param recipes   产物 → 配方列表（配方图里那一份）
     * @param stepMinutes 配方类型 → 这一步的分钟数（{@link RecipeSteps#minutes}）
     * @return 物品 → 分钟（**只含 &gt; 0 的项**；查不到 = 推不出来 = 不加价）
     */
    public static Map<ResourceLocation, Double> compute(
            Map<ResourceLocation, Integer> values,
            Set<ResourceLocation> anchored,
            Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes,
            ToDoubleFunction<ResourceLocation> stepMinutes) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<ResourceLocation, Double> time = new HashMap<>();

        // ① 锚点：种子价 ÷ 60。种子价是数据包作者拍的「这东西值多少劳动」，
        //    所以它同时就是时间锚 —— 不需要第二份手写的时间表。
        if (anchored != null) {
            for (ResourceLocation item : anchored) {
                if (item == null) {
                    continue;
                }
                Integer value = values.get(item);
                if (value != null && value > 0) {
                    time.put(item, value / (double) COINS_PER_MINUTE);
                }
            }
        }

        if (recipes == null || recipes.isEmpty()) {
            return Map.copyOf(time);
        }

        // ② 排序：遍历顺序固定，同一份数据包在任何机器上算出来的表都一样
        List<ResourceLocation> targets = new ArrayList<>(recipes.keySet());
        targets.removeIf(item -> item == null || time.containsKey(item));
        targets.sort(null);

        ToDoubleFunction<ResourceLocation> steps =
                stepMinutes == null ? RecipeSteps::minutes : stepMinutes;

        for (int pass = 0; pass < MAX_PASSES && !targets.isEmpty(); pass++) {
            boolean changed = false;
            for (ResourceLocation item : targets) {
                double next;
                try {
                    next = best(recipes.get(item), time, steps);
                } catch (Throwable e) {
                    // 某条配方在自定义 Ingredient 上抛了：跳过它，别的物品照推
                    continue;
                }
                if (next <= 0) {
                    continue;   // 推不出来（原料没价 / 没有配方）：保持「不加价」
                }
                Double current = time.get(item);
                if (current != null && Math.abs(current - next) < EPSILON) {
                    continue;
                }
                time.put(item, next);
                changed = true;
            }
            if (!changed) {
                break;  // 到不动点了
            }
        }
        return Map.copyOf(time);
    }

    /**
     * 一个物品的最省时间：所有配方里最短的那条。
     *
     * @return 分钟；{@code 0} 表示这一轮还推不出来
     */
    private static double best(List<RecipeGraph.RecipeEntry> recipes,
                               Map<ResourceLocation, Double> time,
                               ToDoubleFunction<ResourceLocation> stepMinutes) {
        if (recipes == null || recipes.isEmpty()) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (RecipeGraph.RecipeEntry recipe : recipes) {
            if (recipe == null || recipe.outputCount() <= 0) {
                continue;
            }
            double cost = 0;
            int counted = 0;
            boolean complete = true;
            for (RecipeGraph.Ingredient ingredient : recipe.inputs()) {
                if (ingredient == null || ingredient.count() <= 0) {
                    continue;
                }
                double unit = cheapest(ingredient.candidates(), time);
                if (unit <= 0) {
                    complete = false;   // 有一种原料还算不出来 → 这条配方整体不算
                    break;
                }
                cost += unit * ingredient.count();
                counted++;
            }
            if (!complete || counted == 0) {
                continue;
            }
            // 工序时间按「这一次操作」算，不随产出数量摊薄：
            // 一次合成 4 根木棍，多出来的那 3 根是加工带来的，不是加工时间变长了
            double total = cost / recipe.outputCount() + stepMinutes.applyAsDouble(recipe.type());
            if (total > 0 && total < best) {
                best = total;
            }
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /** 一种输入里最便宜的那个候选的时间；候选全没时间（或没有候选）时返回 0。 */
    private static double cheapest(List<ResourceLocation> candidates, Map<ResourceLocation, Double> time) {
        if (candidates == null) {
            return 0;
        }
        double min = Double.MAX_VALUE;
        for (ResourceLocation candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Double minutes = time.get(candidate);
            if (minutes != null && minutes > 0 && minutes < min) {
                min = minutes;
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }
}
