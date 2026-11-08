package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「进度档」（第几小时游戏时间开放）的规则测试（纯 JVM，不启动游戏）。
 *
 * <p>守的是四条容易改坏的语义：
 * <ol>
 *   <li>写了阶段就**盖过**按价格算的时长（长短都盖）—— 数据包没写的物品行为不变；</li>
 *   <li>标签键会展开成成员物品，命中多条**取最大**（只能往后推）；</li>
 *   <li>阶段**顺着配方图往下游传** —— 没有这一条，9 锭做的块会走回落公式变成
 *       「7 小时就开」，整道闸门被一个块绕过去；</li>
 *   <li>上限 30 天（{@link UnlockStages#MAX_HOURS}），超过就截断而不是报错。</li>
 * </ol>
 */
class UnlockStagesTest {

    private static final int MAX = UnlockStages.MAX_HOURS;

    @BeforeEach
    void clear() {
        // 静态表：每条用例从干净状态开始，否则上一条的结果会串进来
        UnlockStages.reset();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static Map<ResourceLocation, Integer> table(Object... pairs) {
        Map<ResourceLocation, Integer> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((ResourceLocation) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    /** 一条「输入 → 产出 1 个」的最小配方。 */
    private static RecipeGraph graphOf(Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes) {
        return output -> recipes.getOrDefault(output, List.of());
    }

    private static RecipeGraph.RecipeEntry recipe(ResourceLocation input, int count) {
        return new RecipeGraph.RecipeEntry(List.of(new RecipeGraph.Ingredient(List.of(input), count)), 1);
    }

    // ==================== 显式阶段 ====================

    @Test
    void explicitItemStageIsReadAsHours() {
        UnlockStages.rebuild(table(id("allthemodium_ingot"), 48), Map.of(), Map.of(), null, null);
        assertEquals(48, UnlockStages.hours(id("allthemodium_ingot")));
    }

    @Test
    void itemsWithoutAStageReportZeroSoThePriceFormulaStillApplies() {
        UnlockStages.rebuild(table(id("diamond"), 6), Map.of(), Map.of(), null, null);
        assertEquals(0, UnlockStages.hours(id("dirt")), "没写 = 0 = 回落到按买价算");
        assertEquals(0, UnlockStages.hours(null), "null 不许抛");
    }

    @Test
    void zeroAndNegativeMeanNotWritten() {
        UnlockStages.rebuild(table(id("a"), 0, id("b"), -5), Map.of(), Map.of(), null, null);
        assertTrue(UnlockStages.snapshot().isEmpty(),
                "0 / 负数不是「立刻开放」而是「没写」—— 想让物品立刻开放就别写它");
    }

    @Test
    void stagesAreClampedToTheThirtyDayCeiling() {
        UnlockStages.rebuild(table(id("absurd"), 9999), Map.of(), Map.of(), null, null);
        assertEquals(MAX, UnlockStages.hours(id("absurd")), "超上限就截断：30 天 = 720 小时");
        assertEquals(720, MAX, "上限本身也要钉住，它是 UnlockRules.MAX_REQUIRED_PLAY_SECONDS 换算的");
    }

    @Test
    void itemsMissingFromTheRegistryAreDropped() {
        // allItems 非 null 时按注册表筛：给没装的模组写的阶段不该一路推到客户端
        UnlockStages.rebuild(table(id("present"), 12, id("absent"), 24), Map.of(), Map.of(),
                Set.of(id("present")), null);
        assertEquals(12, UnlockStages.hours(id("present")));
        assertEquals(0, UnlockStages.hours(id("absent")));
    }

    // ==================== 标签键 ====================

    @Test
    void tagStageExpandsToItsMembers() {
        Map<ResourceLocation, Set<ResourceLocation>> members = Map.of(
                id("ingots/allthemodium"), Set.of(id("ingot"), id("nugget")));
        UnlockStages.rebuild(Map.of(), table(id("ingots/allthemodium"), 48), members, null, null);
        assertEquals(48, UnlockStages.hours(id("ingot")));
        assertEquals(48, UnlockStages.hours(id("nugget")));
    }

    @Test
    void theLatestStageWinsWhenAnItemIsHitTwice() {
        // 物品键 48 + 标签键 96 → 取 96。只能往后推，不能往前拉（这是刻意的：
        // 提前会让关键物品过早出现，而那正是这套机制唯一想拦的事）
        Map<ResourceLocation, Set<ResourceLocation>> members = Map.of(
                id("ingots/x"), Set.of(id("ingot")));
        UnlockStages.rebuild(table(id("ingot"), 48), table(id("ingots/x"), 96), members, null, null);
        assertEquals(96, UnlockStages.hours(id("ingot")));
    }

    @Test
    void tagsThatDontExistInThisPackAreIgnored() {
        // 标签名写错 / 模组没装：条目静默无效，但**不许**影响别的条目
        UnlockStages.rebuild(table(id("real"), 24), table(id("ingots/nonexistent"), 96), Map.of(), null, null);
        assertEquals(24, UnlockStages.hours(id("real")));
        assertEquals(0, UnlockStages.hours(id("fake")));
    }

    // ==================== 下游传播 ====================

    /**
     * 核心用例：锭 48 小时 → 块（9 锭）48 小时 → 装备（块 + 木棍）48 小时。
     *
     * <p>没有这条传播的话，块会走回落公式：它在数据包里没写阶段，价格却高达 2.7 万，
     * 算出来是 7.1 小时 —— 玩家 7 小时买一块，等于一次买到 9 个本该等 48 小时的锭。
     */
    @Test
    void stagePropagatesDownstreamLikeTheFloor() {
        ResourceLocation ingot = id("allthemodium_ingot");
        ResourceLocation block = id("allthemodium_block");
        ResourceLocation gear = id("allthemodium_pickaxe");
        ResourceLocation stick = id("stick");

        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = new HashMap<>();
        recipes.put(block, List.of(recipe(ingot, 9)));
        recipes.put(gear, List.of(new RecipeGraph.RecipeEntry(List.of(
                new RecipeGraph.Ingredient(List.of(block), 3),
                new RecipeGraph.Ingredient(List.of(stick), 2)), 1)));
        recipes.put(stick, List.of(recipe(id("planks"), 1)));

        Set<ResourceLocation> all = Set.of(ingot, block, gear, stick, id("planks"));
        UnlockStages.rebuild(table(ingot, 48), Map.of(), Map.of(), all, graphOf(recipes));

        assertEquals(48, UnlockStages.hours(ingot), "自己写的");
        assertEquals(48, UnlockStages.hours(block), "块继承锭（9 锭做 1 块）");
        assertEquals(48, UnlockStages.hours(gear), "装备继承块，块又继承锭（两跳）");
        assertEquals(0, UnlockStages.hours(stick), "和 Allthemodium 无关的东西不该被带上来");
        assertEquals(0, UnlockStages.hours(id("planks")));
    }

    @Test
    void propagationTakesTheEasiestRecipeNotTheHardest() {
        // 同一产物有两条配方：一条吃 Allthemodium（48h），一条吃泥土（开局就有）。
        // 玩家当然走泥土那条 —— 所以它必须**不受**阶段限制（和估值取「最便宜那条配方」同理）
        ResourceLocation shortcut = id("shortcut");
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = Map.of(
                shortcut, List.of(recipe(id("allthemodium_ingot"), 1), recipe(id("dirt"), 1)));
        UnlockStages.rebuild(table(id("allthemodium_ingot"), 48), Map.of(), Map.of(),
                Set.of(shortcut, id("allthemodium_ingot"), id("dirt")), graphOf(recipes));
        assertEquals(0, UnlockStages.hours(shortcut), "有便宜的替代配方就不该被锁到 48 小时");
    }

    @Test
    void propagationNeverPullsAnExplicitStageEarlier() {
        ResourceLocation cheapButLate = id("late");
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = Map.of(
                cheapButLate, List.of(recipe(id("dirt"), 1)));
        UnlockStages.rebuild(table(cheapButLate, 96), Map.of(), Map.of(),
                Set.of(cheapButLate, id("dirt")), graphOf(recipes));
        assertEquals(96, UnlockStages.hours(cheapButLate),
                "显式阶段只能往后推：材料是泥土也不能把它拉早");
    }

    @Test
    void propagationRunsEvenWithoutAGraphButJustKeepsTheExplicitOnes() {
        UnlockStages.rebuild(table(id("a"), 24), Map.of(), Map.of(), Set.of(id("a")), null);
        assertEquals(24, UnlockStages.hours(id("a")), "没有配方图（单测 / 配方没加载）也不许把表清空");
    }

    /**
     * 配方图在个别物品上抛异常（某个模组的实现炸了）：那一条跳过，其余照传，绝不让整张表建不起来。
     *
     * <p>这是估值那边 {@code valuatorSurvivesAHostileRecipeGraph} 的镜像 ——
     * 两条链都在服务端初始化路径上，抛出去会打断整个数据包同步。
     */
    @Test
    void hostileRecipeGraphNeverBreaksTheTable() {
        ResourceLocation ingot = id("allthemodium_ingot");
        ResourceLocation block = id("block");
        ResourceLocation bomb = id("bomb");
        RecipeGraph hostile = output -> {
            if (output.equals(bomb)) {
                throw new IllegalStateException("模拟坏配方图");
            }
            return output.equals(block)
                    ? List.of(recipe(ingot, 9))
                    : List.of();
        };
        UnlockStages.rebuild(table(ingot, 48), Map.of(), Map.of(),
                Set.of(ingot, block, bomb), hostile);
        assertEquals(48, UnlockStages.hours(block), "炸的是别人，这条照样传播");
        assertEquals(0, UnlockStages.hours(bomb), "抛异常的那条按「没有配方」处理");
    }

    @Test
    void rebuildIsIdempotentAndReplacesWholesale() {
        UnlockStages.rebuild(table(id("a"), 24), Map.of(), Map.of(), null, null);
        UnlockStages.rebuild(table(id("b"), 6), Map.of(), Map.of(), null, null);
        assertEquals(0, UnlockStages.hours(id("a")), "整份替换：上一份里没了的条目就该消失");
        assertEquals(6, UnlockStages.hours(id("b")));
    }
}
