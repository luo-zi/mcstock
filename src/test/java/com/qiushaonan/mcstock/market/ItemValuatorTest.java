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
 * 物品估值表：地板的语义、配方推导、防套利与采购价。
 *
 * <p>除了 {@link ResourceLocation}（本质上就是一对字符串）之外全是本模组自己的代码 ——
 * 不用启动游戏，也不用把真实数据包塞进测试里，配方图用一张写死的 stub 代替。
 *
 * <p>⚠️ 估值表是 {@link ItemValuator} 里的**静态缓存**：整类用 {@link Order} 固定执行顺序，
 * 因为「rebuild 之前返回 0」这条断言只在没人动过缓存时成立 ——
 * 顺序一变，它就会被别的用例悄悄喂饱，然后变成一条永远通过的假测试。
 * 同理，凡是自带配方图的用例（Order 10、11）都排在最后。
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
     * 有地板值的物品。它们的地板是**外生**的（数据包作者拍板），可以高于材料成本 ——
     * 是否构成套利由 {@link ItemValuator#conflictCount()} 单独判定，
     * 见 {@link #floorAboveMaterialCostIsReportedAsAConflict()}。
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
        assertEquals(0, ItemValuator.derived(DIAMOND));
        assertEquals(0, ItemValuator.purchasePrice(DIAMOND));
        assertEquals(0, ItemValuator.conflictCount());
    }

    @Test
    @Order(2)
    void seedsAreFloorsAndTagSeedsTakeTheMaximum() {
        rebuild();
        assertEquals(INGOT_SEED, ItemValuator.value(IRON_INGOT), "铁锭的地板是 6（它的配方输入全 0，推不出价）");
        assertEquals(DIAMOND_SEED, ItemValuator.value(DIAMOND), "钻石没配方 → 只有地板 40");
        assertEquals(1, ItemValuator.value(BIRCH_PLANKS), "标签地板 #minecraft:planks = 1");
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
                // 地板价是外生的，允许高于合成成本。反例就在本测试的数据里：
                // 铁锭地板 6，而「9 铁粒 → 1 铁锭」的原料成本是 0（铁粒估值 0，买不到）。
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
        // 基础表里一条冲突都不该有：上面那套地板全是「比原料便宜」的
        assertEquals(0, ItemValuator.conflictCount(), "基础数据不该产生地板冲突");
    }

    // ==================== 采购价 ====================

    @Test
    @Order(5)
    void purchasePriceIsTheMarkupOverValue() {
        rebuild();
        for (ResourceLocation item : ALL_ITEMS) {
            int value = ItemValuator.value(item);
            if (value <= 0) {
                assertEquals(0, ItemValuator.purchasePrice(item), item + " 不可购买");
                continue;
            }
            assertEquals((int) Math.ceil(value * ItemValuator.PURCHASE_MARKUP),
                    ItemValuator.purchasePrice(item), item + " 的采购价 = ceil(估价 × 1.18)");
        }
        // 钉几个具体数字：向上取整，不是四舍五入（40 × 1.18 = 47.2 → 48，不能因为浮点误差变成 47 或 49）
        // ⚠️ 倍率历史：1.15（原版）→ 1.25（2026-10-06 收紧）→ 1.18（同日回调），这三个数跟着变
        assertEquals(48, ItemValuator.purchasePrice(DIAMOND));
        assertEquals(8, ItemValuator.purchasePrice(IRON_INGOT));   // 估价 6 → 7.08 → 8
        assertEquals(142, ItemValuator.purchasePrice(DIAMOND_PICKAXE));   // 估价 120 → 141.6 → 142
    }

    /**
     * 地板<b>只托不封</b>：推导值高于地板时，听推导的。
     *
     * <p>这条钉住的正是这次改动的分水岭。旧语义是「锚」—— 种子写多少就是多少，
     * 配方推导被屏蔽；于是整合包里一个手写价的锭做成的块、合金、装备，
     * 全都还停在原料那一档。地板语义下它们会跟着涨。
     */
    @Test
    @Order(6)
    void floorLiftsButDoesNotSuppressDerivation() {
        ResourceLocation ore = id("mcstock_test_floor_ore");
        ResourceLocation ingot = id("mcstock_test_floor_ingot");
        // 矿（无配方）地板 500；锭的地板只写 100，但「1 矿 → 1 锭」推出来是 500
        ItemValuator.rebuild(Map.of(ore, 500, ingot, 100), Map.of(), Map.of(),
                Set.of(ore, ingot),
                new StubGraph(Map.of(ingot, List.of(entry(1, ingredient(1, ore))))));

        assertEquals(500, ItemValuator.value(ore), "无配方物品只有地板");
        assertEquals(500, ItemValuator.value(ingot),
                "推导 500 > 地板 100：必须听推导的（旧「锚」语义会把它按死在 100）");
        assertEquals(500, ItemValuator.derived(ingot), "推导值自己也是 500");
        assertEquals((int) Math.ceil(500 * ItemValuator.PURCHASE_MARKUP),
                ItemValuator.purchasePrice(ingot));
    }

    // ==================== 边界 ====================

    @Test
    @Order(7)
    void unknownItemIsWorthNothing() {
        rebuild();
        assertEquals(0, ItemValuator.value(UNKNOWN), "既没地板也没配方的物品估值 0");
        assertEquals(0, ItemValuator.derived(UNKNOWN), "也推不出价 —— 不按稀有度兜底");
        assertEquals(0, ItemValuator.purchasePrice(UNKNOWN), "不可购买");
        assertEquals(0, ItemValuator.cooldownSeconds(ItemValuator.purchasePrice(UNKNOWN)), "没有冷却可言");
    }

    @Test
    @Order(8)
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
    @Order(9)
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

    // ==================== 整合包：地板传播（整套改动的验收标准） ====================

    /** 「开局手里那点钱」：一个刚进游戏、随手攒了点破烂的玩家量级。 */
    private static final int BOOTSTRAP_CASH = 600;

    /**
     * ATM10 的真实形状：<b>关键资源没有配方</b>，只能靠挖矿 / 任务拿到。
     *
     * <p>为什么这条是整套改动的验收标准：Allthemodium 锭在配方图里查不到任何配方，
     * 纯推导给它 0，于是「用 9 个锭做的块」也推不出来 —— 整条进度线全是 0 或原料价。
     * 这正是玩家反馈的「后期物品被廉价出售」。
     *
     * <p>现在的解法（照抄 Upgrader + ATM10 Balance）：给锭写一个<b>地板</b>，
     * 让推导<b>从它往下游传</b>：
     * 锭 5000 → 块（9 锭）45000 → 齿轮（4 锭）20000 → 线缆（1 齿轮 → 4 根）5000。<br>
     * 这条链证明了「地板 ≠ 锚」：锚语义下块只会算出 0（因为锭是种子，不参与推导），
     * 或者按种子的原值卖；地板语义下它会一路涨上去。
     *
     * <p>⛔ 本用例用自己的一张表覆盖静态缓存，所以排在所有依赖 {@link #rebuild()} 的
     * 用例之后（同一个类里用 {@link Order} 锁住顺序）。
     */
    @Test
    @Order(10)
    void floorOnARecipeLessItemPropagatesDownstream() {
        ResourceLocation ingot = id("mcstock_test_atm_ingot");
        ResourceLocation block = id("mcstock_test_atm_block");
        ResourceLocation gear = id("mcstock_test_atm_gear");
        ResourceLocation cable = id("mcstock_test_atm_cable");
        int ingotFloor = 5000;

        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput = new LinkedHashMap<>();
        byOutput.put(block, List.of(entry(1, ingredient(9, ingot))));
        byOutput.put(gear, List.of(entry(1, ingredient(4, ingot))));
        // 第二层：1 个齿轮切 4 根线缆，验证传播不止一层
        byOutput.put(cable, List.of(entry(4, ingredient(1, gear))));
        Set<ResourceLocation> items = Set.of(ingot, block, gear, cable);

        ItemValuator.rebuild(Map.of(ingot, ingotFloor), Map.of(), Map.of(), items,
                new StubGraph(Map.copyOf(byOutput)));

        // ① 无配方物品：只有地板，推导是 0 —— 这正是地板存在的理由
        assertEquals(0, ItemValuator.derived(ingot), "锭没有配方，推不出来");
        assertEquals(ingotFloor, ItemValuator.value(ingot), "价全靠地板");
        // ② 地板顺着配方往下游传
        assertEquals(ingotFloor * 9, ItemValuator.value(block), "9 锭 → 块，地板传下去了");
        assertEquals(ingotFloor * 4, ItemValuator.value(gear), "4 锭 → 齿轮");
        assertEquals(ingotFloor * 4 / 4, ItemValuator.value(cable), "第二层也要传：1 齿轮 → 4 根线缆");
        assertEquals(ItemValuator.value(cable), ItemValuator.derived(cable),
                "这几件是**推**出来的，地板没参与（地板只有锭有）");
        // ③ 买价跟着回收价走，不是另一套算法
        assertEquals((int) Math.ceil(ingotFloor * 9 * ItemValuator.PURCHASE_MARKUP),
                ItemValuator.purchasePrice(block));
        // ④ 原始诉求：「开局随便捡点东西就能换到后期物品」必须不成立
        assertTrue(ItemValuator.purchasePrice(block) > BOOTSTRAP_CASH,
                "开局那点钱买不到后期块，实际 " + ItemValuator.purchasePrice(block)
                        + " 金币 vs 开局 " + BOOTSTRAP_CASH);
        // ⑤ 这条链上没有地板冲突（地板全都低于下游推导值）
        assertEquals(0, ItemValuator.conflictCount());
    }

    /**
     * 地板高于材料成本 = 印钞机，必须被扫描报出来。
     *
     * <p>这是地板模型**唯一**的风险面：地板托高回收价，一旦托过了「材料成本」这条线，
     * 「买原料 → 合成 → 回收」就变成稳赚。改了地板却不看这个数，等于闭着眼改经济。
     */
    @Test
    @Order(11)
    void floorAboveMaterialCostIsReportedAsAConflict() {
        ResourceLocation cheap = id("mcstock_test_conflict_input");
        ResourceLocation pricey = id("mcstock_test_conflict_output");
        ItemValuator.rebuild(
                Map.of(cheap, 50, pricey, 100), Map.of(), Map.of(),
                Set.of(cheap, pricey),
                new StubGraph(Map.of(pricey, List.of(entry(1, ingredient(1, cheap))))));

        assertEquals(100, ItemValuator.value(pricey), "地板 100 赢过推导 50");
        assertEquals(50, ItemValuator.derived(pricey), "推导值确实是 50（这条冲突是真的，不是没扫到）");
        assertEquals(1, ItemValuator.conflictCount(),
                "地板 100 > 材料 50：买 50 的原料合成卖 100，必须被报成 1 条冲突");
    }

    /**
     * 保险丝：地板当迭代起点之后，「互相引用」的环路会把价格一轮轮抬上去。
     *
     * <p>不用 {@link Integer#MAX_VALUE} 兜底是有意的：真撞到 int 溢出会变成负数，
     * 玩家看到的是「这件东西值 −21 亿」，比贵还难查。所以有一个**明确的上限**，
     * 撞到就截断并在日志里报出来（说明配方图里有环路）。
     *
     * <p>这条环路是编出来的（`1 A ← 10 B`、`1 B ← 1 A`），真实整合包里基本不存在 ——
     * 它的意义是钉住「撞上限时不会溢出、不会崩」这个行为。
     */
    @Test
    @Order(12)
    void runawayRelaxationIsClampedInsteadOfOverflowing() {
        ResourceLocation a = id("mcstock_test_cycle_a");
        ResourceLocation b = id("mcstock_test_cycle_b");
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput = new LinkedHashMap<>();
        byOutput.put(a, List.of(entry(1, ingredient(10, b))));
        byOutput.put(b, List.of(entry(1, ingredient(1, a))));

        ItemValuator.rebuild(Map.of(a, 100, b, 100), Map.of(), Map.of(), Set.of(a, b),
                new StubGraph(Map.copyOf(byOutput)));

        assertEquals(ItemValuator.MAX_VALUE_COINS, ItemValuator.value(a),
                "环路会一轮轮翻十倍，必须在上限处截断（而不是溢出成负数）");
        assertEquals(ItemValuator.MAX_VALUE_COINS, ItemValuator.value(b));
    }

    /**
     * 冷却 = 买价秒数，带下限（60 金币以下不限购）与上限（24 小时）。
     *
     * <p>这几个数是**同一条换算**的边界：1 金币 ≈ 1 秒游戏时间，所以「买一件要等多久」
     * 就是「自己造一件要多久」。下限是为了不让一块泥土把玩家锁在按钮前，
     * 上限是为了不让整合包里的天价物品锁到几个月后（那等于买不到）。
     */
    @Test
    @Order(13)
    void cooldownFollowsPriceWithAFloorAndACap() {
        assertEquals(0, ItemValuator.cooldownSeconds(0), "不可购买 = 没有冷却");
        assertEquals(0, ItemValuator.cooldownSeconds(ItemValuator.MIN_COOLDOWN_SECONDS - 1),
                "便宜物品不限购（否则买一组土要连点两分钟）");
        assertEquals(ItemValuator.MIN_COOLDOWN_SECONDS,
                ItemValuator.cooldownSeconds(ItemValuator.MIN_COOLDOWN_SECONDS));
        assertEquals(3000, ItemValuator.cooldownSeconds(3000), "1 金币 = 1 秒");
        assertEquals(ItemValuator.MAX_COOLDOWN_SECONDS,
                ItemValuator.cooldownSeconds(Integer.MAX_VALUE), "上限 24 小时");
    }

    // ==================== stub 配方图 ====================

    /** 写死的一张配方图，覆盖「多选一候选 + 数量 + 多配方」三种形状。 */
    private static Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes() {
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> byOutput = new LinkedHashMap<>();
        // 1 铁锭 → 9 铁粒：floor(6 / 9) = 0 → 铁粒不可回收
        byOutput.put(IRON_NUGGET, List.of(entry(9, ingredient(1, IRON_INGOT))));
        // 9 铁粒 → 1 铁锭：输入全是 0 金币 → 这条配方直接跳过，铁锭保持地板价
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
