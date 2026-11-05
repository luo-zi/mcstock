package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物品估值表：种子优先级、配方推导、防套利与采购价。
 *
 * <p>除了 {@link ResourceLocation}（本质上就是一对字符串）之外全是本模组自己的代码 ——
 * 不用启动游戏，也不用把真实数据包塞进测试里，配方图用一张写死的 stub 代替。
 *
 * <p>⚠️ 估值表是 {@link ItemValuator} 里的**静态缓存**：整类用 {@link Order} 固定执行顺序，
 * 因为「rebuild 之前返回 0」这条断言只在没人动过缓存时成立 ——
 * 顺序一变，它就会被别的用例悄悄喂饱，然后变成一条永远通过的假测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ItemValuatorTest {

    private static final ResourceLocation IRON_INGOT = id("iron_ingot");
    private static final ResourceLocation IRON_NUGGET = id("iron_nugget");
    private static final ResourceLocation STICK = id("stick");
    private static final ResourceLocation DIAMOND = id("diamond");
    private static final ResourceLocation DIAMOND_PICKAXE = id("diamond_pickaxe");
    private static final ResourceLocation OAK_PLANKS = id("oak_planks");
    private static final ResourceLocation BIRCH_PLANKS = id("birch_planks");
    /** 从来没写进任何种子、也没有配方的物品。 */
    private static final ResourceLocation UNKNOWN = id("mcstock_test_never_valued");

    private static final ResourceLocation PLANKS_TAG = id("planks");
    private static final ResourceLocation WOODEN_TAG = id("mcstock_test_wooden");

    private static final int INGOT_SEED = 6;
    private static final int DIAMOND_SEED = 40;
    /** 钻石镐 = 3 钻石 + 2 木棍，木棍估值 0 → 3 × 40 = 120。 */
    private static final int PICKAXE_DERIVED = 120;

    private static final Map<ResourceLocation, Integer> ITEM_SEEDS = Map.of(
            IRON_INGOT, INGOT_SEED,
            DIAMOND, DIAMOND_SEED);
    /** 两个标签都盖住橡木木板，用来验证「命中多个标签取最大」。 */
    private static final Map<ResourceLocation, Integer> TAG_SEEDS = Map.of(
            PLANKS_TAG, 1,
            WOODEN_TAG, 3);
    private static final Map<ResourceLocation, Set<ResourceLocation>> TAG_MEMBERS = Map.of(
            PLANKS_TAG, Set.of(OAK_PLANKS, BIRCH_PLANKS),
            WOODEN_TAG, Set.of(OAK_PLANKS));
    private static final Set<ResourceLocation> ALL_ITEMS = Set.of(
            IRON_INGOT, IRON_NUGGET, STICK, DIAMOND, DIAMOND_PICKAXE, OAK_PLANKS, BIRCH_PLANKS);
    /**
     * 有种子值的物品。它们的价格是**外生**的（数据包作者拍板），不参与「不许套利」的约束 ——
     * 详见 {@link #derivedValueNeverExceedsIngredientCost()}。
     */
    private static final Set<ResourceLocation> SEEDED = Set.of(
            IRON_INGOT, DIAMOND, OAK_PLANKS, BIRCH_PLANKS);

    private static final Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> RECIPES = recipes();
    private static final RecipeGraph GRAPH = new StubGraph(RECIPES);

    // ==================== 静态缓存 ====================

    @Test
    @Order(1)
    void valueIsZeroBeforeAnyRebuild() {
        // 没调过 rebuild() 时缓存是空表：value() / purchasePrice() 必须安全地返回 0，不能 NPE
        assertEquals(0, ItemValuator.value(DIAMOND));
        assertEquals(0, ItemValuator.value(IRON_INGOT));
        assertEquals(0, ItemValuator.purchasePrice(DIAMOND));
    }

    @Test
    @Order(2)
    void seedsWinAndTagSeedsTakeTheMaximum() {
        rebuild();
        assertEquals(INGOT_SEED, ItemValuator.value(IRON_INGOT), "物品种子优先：铁锭就是 6");
        assertEquals(DIAMOND_SEED, ItemValuator.value(DIAMOND), "物品种子：钻石 40");
        assertEquals(1, ItemValuator.value(BIRCH_PLANKS), "标签种子 #minecraft:planks = 1");
        assertEquals(3, ItemValuator.value(OAK_PLANKS), "命中两个标签时取最大：planks=1、wooden=3");
        assertEquals(PICKAXE_DERIVED, ItemValuator.value(DIAMOND_PICKAXE), "配方推导：3 × 40 + 2 × 0");
    }

    @Test
    @Order(3)
    void derivedValueBelowOneBecomesZero() {
        rebuild();
        // 1 铁锭 → 9 铁粒：floor(6 / 9) = 0
        assertEquals(0, ItemValuator.value(IRON_NUGGET), "9 粒 ↔ 1 锭 的方向必须被截断成 0，否则就是印钞机");
        // 2 木板 → 4 木棍：floor(2 × 1 / 4) = 0
        assertEquals(0, ItemValuator.value(STICK), "2 板 → 4 棍 同理");
        // 0 的语义是「既不可回收、也不可购买」
        assertEquals(0, ItemValuator.purchasePrice(IRON_NUGGET));
        assertEquals(0, ItemValuator.purchasePrice(STICK));
    }

    // ==================== 无套利不变量 ====================

    @Test
    @Order(4)
    void derivedValueNeverExceedsIngredientCost() {
        rebuild();
        for (Map.Entry<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput : RECIPES.entrySet()) {
            ResourceLocation output = byOutput.getKey();
            int outputValue = ItemValuator.value(output);
            if (outputValue == 0) {
                continue; // 推导值被截断成 0（或压根没估出来）：不变量无从谈起
            }
            if (SEEDED.contains(output)) {
                // 种子价是外生的，允许高于合成成本。反例就在本测试的数据里：
                // 铁锭种子 6，而「9 铁粒 → 1 铁锭」的原料成本是 0（铁粒估值 0，买不到）。
                // 这不是套利 —— 0 金币的物品**不可购买**，玩家没法拿它去合成。
                // 真正要守的只有一条：**推导**出来的价格不能超过原料成本。
                continue;
            }
            for (RecipeGraph.RecipeEntry recipe : byOutput.getValue()) {
                if (recipe.outputCount() <= 0) {
                    continue;
                }
                long cost = 0;
                for (RecipeGraph.Ingredient ingredient : recipe.inputs()) {
                    cost += (long) cheapest(ingredient) * ingredient.count();
                }
                if (cost == 0) {
                    // 输入全是 0 金币：这条配方本轮不参与推导（规则 2），也就构不成套利路径
                    continue;
                }
                long produced = (long) outputValue * recipe.outputCount();
                assertTrue(produced <= cost,
                        output + " 估值 " + outputValue + " × " + recipe.outputCount()
                                + " = " + produced + " 超过了原料成本 " + cost + "：买原料合成再回收就能白赚");
            }
        }
    }

    // ==================== 采购价 ====================

    @Test
    @Order(5)
    void purchasePriceIsCeilOfValueTimesMarkup() {
        rebuild();
        for (ResourceLocation item : ALL_ITEMS) {
            int value = ItemValuator.value(item);
            int expected = value <= 0 ? 0 : (int) Math.ceil(value * ItemValuator.PURCHASE_MARKUP);
            assertEquals(expected, ItemValuator.purchasePrice(item), item + " 的采购价 = ceil(估价 × 1.18)");
        }
        // 钉几个具体数字：向上取整，不是四舍五入（40 × 1.18 = 47.2 → 48，不能因为浮点误差变成 47 或 49）
        // ⚠️ 倍率历史：1.15（原版）→ 1.25（2026-10-06 收紧）→ 1.18（同日回调），这三个数跟着变
        assertEquals(48, ItemValuator.purchasePrice(DIAMOND));
        assertEquals(8, ItemValuator.purchasePrice(IRON_INGOT));   // 估价 6 → 6 × 1.18 = 7.08 → 8
        assertEquals(142, ItemValuator.purchasePrice(DIAMOND_PICKAXE));   // 估价 120 → 141.6 → 142
    }

    // ==================== 边界 ====================

    @Test
    @Order(6)
    void unknownItemIsWorthNothing() {
        rebuild();
        assertEquals(0, ItemValuator.value(UNKNOWN), "既没种子也没配方的物品估值 0");
        assertEquals(0, ItemValuator.purchasePrice(UNKNOWN), "不可购买");
    }

    @Test
    @Order(7)
    void rebuildIsIdempotent() {
        rebuild();
        Map<ResourceLocation, Integer> first = Map.copyOf(ItemValuator.snapshot());
        assertTrue(first.containsKey(DIAMOND_PICKAXE), "重算前估值表里应该有推导出来的物品");
        rebuild();
        assertEquals(first, ItemValuator.snapshot(), "同样的输入重算一遍，表必须一模一样");
    }

    // ==================== 来源查询（/mcstock value 用） ====================

    /**
     * 来源表（{@link ItemValuator#origin}）：回答「这个价是怎么来的」。
     *
     * <p>⚠️ 和估值表是**两件独立的事**：{@code value() == 0} 不等于「没有来源」——
     * 铁粒推出来 0 金币，它的来源仍然是 {@code DERIVED}（这条路把东西做得比 1 金币还便宜，
     * 所以价格塌到 0）。调试时最需要被认出来的就是这种情况。
     */
    @Test
    @Order(8)
    void originReportsWhereThePriceCameFrom() {
        rebuild();

        ItemValuator.Origin ingot = ItemValuator.origin(IRON_INGOT);
        assertEquals(ItemValuator.Source.ITEM_SEED, ingot.source(), "铁锭是物品种子");
        assertEquals(INGOT_SEED, ingot.declared(), "来源里要带上数据包声明的原值");
        assertNull(ingot.tag(), "物品种子没有标签");

        // 命中两个标签取最大：橡木板拿到 wooden(3)，白桦板只命中 planks(1)
        ItemValuator.Origin oak = ItemValuator.origin(OAK_PLANKS);
        assertEquals(ItemValuator.Source.TAG_SEED, oak.source(), "橡木板是标签种子");
        assertEquals(WOODEN_TAG, oak.tag(), "要能看出是哪个标签给的价");
        assertEquals(3, oak.declared(), "命中多个标签时取最大那个");
        assertEquals(PLANKS_TAG, ItemValuator.origin(BIRCH_PLANKS).tag(), "白桦板只命中 planks");

        assertEquals(ItemValuator.Source.DERIVED, ItemValuator.origin(DIAMOND_PICKAXE).source(),
                "钻石镐是配方推出来的");
        assertEquals(ItemValuator.Source.DERIVED, ItemValuator.origin(IRON_NUGGET).source(),
                "推出来是 0 金币的物品，来源仍然是推导（不是「无来源」）");
        assertEquals(0, ItemValuator.value(IRON_NUGGET), "顺带钉住：它的价确实是 0");

        assertEquals(ItemValuator.Source.NONE, ItemValuator.origin(UNKNOWN).source(),
                "既没种子也没配方 → 无来源");
        assertNull(ItemValuator.origin(UNKNOWN).tag());
        assertEquals(ItemValuator.Source.NONE, ItemValuator.origin(null).source(), "null 不能 NPE");
    }

    // ==================== stub 配方图 ====================

    /** 写死的一张配方图，覆盖「多选一候选 + 数量 + 多配方」三种形状。 */
    private static Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes() {
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput = new LinkedHashMap<>();
        // 1 铁锭 → 9 铁粒：floor(6 / 9) = 0 → 铁粒不可回收
        byOutput.put(IRON_NUGGET, List.of(entry(9, ingredient(1, IRON_INGOT))));
        // 9 铁粒 → 1 铁锭：输入全是 0 金币 → 这条配方直接跳过，铁锭保持种子价
        byOutput.put(IRON_INGOT, List.of(entry(1, ingredient(9, IRON_NUGGET))));
        // 2 木板 → 4 木棍：候选取最便宜的（橡木 3、白桦 1）→ floor(2 × 1 / 4) = 0
        byOutput.put(STICK, List.of(entry(4, ingredient(2, OAK_PLANKS, BIRCH_PLANKS))));
        // 3 钻石 + 2 木棍 → 1 钻石镐：3 × 40 + 2 × 0 = 120
        byOutput.put(DIAMOND_PICKAXE, List.of(entry(1, ingredient(3, DIAMOND), ingredient(2, STICK))));
        return Map.copyOf(byOutput);
    }

    private static RecipeGraph.RecipeEntry entry(int outputCount, RecipeGraph.Ingredient... inputs) {
        return new RecipeGraph.RecipeEntry(List.of(inputs), outputCount);
    }

    private static RecipeGraph.Ingredient ingredient(int count, ResourceLocation... candidates) {
        return new RecipeGraph.Ingredient(List.of(candidates), count);
    }

    /** stub 里用的都是原版物品 id，命名空间照实写 {@code minecraft}。 */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static void rebuild() {
        ItemValuator.rebuild(ITEM_SEEDS, TAG_SEEDS, TAG_MEMBERS, ALL_ITEMS, GRAPH);
    }

    /** 与 {@link ItemValuator} 内部同一套「候选取最低」的算法：不变量要靠同一把尺子量。 */
    private static int cheapest(RecipeGraph.Ingredient ingredient) {
        int min = Integer.MAX_VALUE;
        for (ResourceLocation candidate : ingredient.candidates()) {
            int value = ItemValuator.value(candidate);
            if (value > 0 && value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private record StubGraph(Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput) implements RecipeGraph {

        @Override
        public List<RecipeGraph.RecipeEntry> recipesFor(ResourceLocation output) {
            return byOutput.getOrDefault(output, List.of());
        }
    }
}
