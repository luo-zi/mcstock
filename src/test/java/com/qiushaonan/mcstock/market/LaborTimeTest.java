package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 劳动时间表（采购价的第二条轨道）。
 *
 * <p>全部是纯 Java：种子、配方图、步长都是本地构造的，不启动游戏。
 *
 * <p>⚠️ 和 {@code ItemValuatorTest} 不同，本类**不碰** {@link ItemValuator} 的静态缓存
 * （只调 {@link LaborTime#compute}），所以两个类谁先跑都不影响 ——
 * 这也是把这条算法抽成独立类的直接原因之一。
 */
class LaborTimeTest {

    private static final ResourceLocation BASE = id("base");

    // ==================== 锚点 ====================

    @Test
    void seededValueIsTheAnchor() {
        // 60 金币 = 1 分钟（COINS_PER_MINUTE = 60）
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE), Map.of(), seconds -> 0);
        assertEquals(1.0, time.get(BASE), 1e-9);
    }

    @Test
    void emptyValueTableGivesEmptyTimeTable() {
        assertTrue(LaborTime.compute(Map.of(), Set.of(BASE), Map.of(), seconds -> 0).isEmpty());
        assertTrue(LaborTime.compute(null, Set.of(BASE), Map.of(), seconds -> 0).isEmpty());
    }

    // ==================== 一步 ====================

    @Test
    void oneStepDividesMaterialTimeByOutputCountThenAddsTheStep() {
        // 2 个底料（各 1 分钟）→ 4 个产物：1.0 × 2 ÷ 4 = 0.5，再加 0.1 的工序
        ResourceLocation out = id("out");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(out, List.of(recipe(4, "minecraft:crafting_shaped", ing(2, BASE)))),
                type -> 0.1);
        assertEquals(0.6, time.get(out), 1e-9);
    }

    @Test
    void stepIsNotDividedByOutputCount() {
        // 工序时间按「一次操作」算：产出 4 个也不会让这一步变成 1/4 次
        ResourceLocation one = id("one");
        ResourceLocation four = id("four");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(one, List.of(recipe(1, "minecraft:crafting_shaped", ing(1, BASE))),
                        four, List.of(recipe(4, "minecraft:crafting_shaped", ing(1, BASE)))),
                type -> 0.5);
        assertEquals(1.0 + 0.5, time.get(one), 1e-9, "1 分钟材料 + 0.5 分钟工序");
        assertEquals(0.25 + 0.5, time.get(four), 1e-9, "材料被摊薄，工序不被摊薄");
    }

    // ==================== 链 ====================

    @Test
    void eachLayerAddsOneStep() {
        // 三层 1:1：1 + 4 + 4 + 4 = 13 分钟
        ResourceLocation a = id("a");
        ResourceLocation b = id("b");
        ResourceLocation c = id("c");
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = new LinkedHashMap<>();
        recipes.put(a, List.of(recipe(1, "mcstock_test:machine", ing(1, BASE))));
        recipes.put(b, List.of(recipe(1, "mcstock_test:machine", ing(1, a))));
        recipes.put(c, List.of(recipe(1, "mcstock_test:machine", ing(1, b))));

        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE), recipes,
                type -> type.getNamespace().equals("mcstock_test") ? 4.0 : 0.25);
        assertEquals(5.0, time.get(a), 1e-6);
        assertEquals(9.0, time.get(b), 1e-6);
        assertEquals(13.0, time.get(c), 1e-6);
    }

    @Test
    void missingIngredientPriceBlocksTheRecipe() {
        // 原料里有一个「还算不出来」→ 这条配方整体不成立，产物留在 0（= 不加价）
        ResourceLocation unknown = id("unknown");
        ResourceLocation out = id("out");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(out, List.of(recipe(1, "minecraft:crafting_shaped",
                        ing(1, BASE), ing(1, unknown)))),
                seconds -> 0);
        assertEquals(0.0, time.getOrDefault(out, 0.0), 1e-9);
    }

    @Test
    void cheapestRecipeWins() {
        ResourceLocation fast = id("fast");
        ResourceLocation out = id("out");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60, fast, 120), Set.of(BASE, fast),
                Map.of(out, List.of(
                        recipe(1, "mcstock_test:machine", ing(1, BASE)),   // 1 + 4 = 5
                        recipe(1, "minecraft:crafting_shaped", ing(1, fast)))),  // 2 + 0.1 = 2.1
                type -> type.getNamespace().equals("mcstock_test") ? 4.0 : 0.1);
        assertEquals(2.1, time.get(out), 1e-9, "玩家会挑最省的那条路，价格按最省的算");
    }

    /**
     * 9 锭 ↔ 1 块的环路**不能发散**。
     *
     * <p>这是加法式工序成本最危险的形状：绕一圈总要多付一次工序，所以 {@code min}
     * 永远选直路，迭代到不动点就停 —— 不会像「取 max」那样一圈一圈涨上去。
     */
    @Test
    void storageCycleDoesNotDiverge() {
        ResourceLocation ingot = id("ingot");
        ResourceLocation block = id("block");
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = new LinkedHashMap<>();
        recipes.put(block, List.of(recipe(1, "minecraft:crafting_shaped", ing(9, ingot))));
        recipes.put(ingot, List.of(recipe(9, "minecraft:crafting_shapeless", ing(1, block))));

        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(ingot, 60), Set.of(ingot), recipes, type -> 0.5);
        // 锭是种子（锚在 1 分钟），块 = 9 × 1 + 0.5
        assertEquals(1.0, time.get(ingot), 1e-9);
        assertEquals(9.5, time.get(block), 1e-6);
    }

    @Test
    void recipeWithNoIngredientsIsIgnored() {
        // 没有输入的配方（配方图里本来就被剔掉了）不能凭空给一个「只有工序」的价
        ResourceLocation out = id("out");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(out, List.of(new RecipeGraph.RecipeEntry(List.of(), 1, null))),
                seconds -> 1.0);
        assertEquals(0.0, time.getOrDefault(out, 0.0), 1e-9);
    }

    @Test
    void zeroOutputCountIsIgnored() {
        ResourceLocation out = id("out");
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(out, List.of(recipe(0, "minecraft:crafting_shaped", ing(1, BASE)))),
                seconds -> 0);
        assertEquals(0.0, time.getOrDefault(out, 0.0), 1e-9);
    }

    @Test
    void anchoredItemsNeverComeFromRecipes() {
        // 种子物品的 T 由数据包定死，配方不该覆盖它（和估值那边「种子优先」同一条规矩）
        Map<ResourceLocation, Double> time = LaborTime.compute(
                Map.of(BASE, 60), Set.of(BASE),
                Map.of(BASE, List.of(recipe(1, "mcstock_test:machine", ing(1, BASE)))),
                type -> 4.0);
        assertEquals(1.0, time.get(BASE), 1e-9);
    }

    // ==================== 小工具 ====================

    private static RecipeGraph.RecipeEntry recipe(int outputCount, String type,
                                                  RecipeGraph.Ingredient... inputs) {
        return new RecipeGraph.RecipeEntry(List.of(inputs), outputCount, ResourceLocation.tryParse(type));
    }

    private static RecipeGraph.Ingredient ing(int count, ResourceLocation... candidates) {
        return new RecipeGraph.Ingredient(List.of(candidates), count);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }
}
