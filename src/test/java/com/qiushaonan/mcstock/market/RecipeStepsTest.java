package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工序步长表：内置值、两档默认值、数据包覆盖。
 *
 * <p>⚠️ {@link RecipeSteps} 的覆盖表是**静态状态**，`ItemValuatorTest` 里那条深链用例
 * 依赖「模组默认 = {@link RecipeSteps#DEFAULT_MODDED}」这个前提 ——
 * 所以每个用例跑完都必须把表恢复原样（{@link #restore()}），
 * 否则这个类的用例先跑就会把价格标定悄悄改掉。
 */
class RecipeStepsTest {

    @AfterEach
    void restore() {
        RecipeSteps.override(Map.of(), RecipeSteps.DEFAULT_MODDED, RecipeSteps.DEFAULT_MINECRAFT);
    }

    @Test
    void builtinTypesHaveTheirOwnStep() {
        assertEquals(0.1, RecipeSteps.minutes(id("minecraft:crafting_shaped")), 1e-9);
        assertEquals(0.05, RecipeSteps.minutes(id("minecraft:stonecutting")), 1e-9);
        assertEquals(0.5, RecipeSteps.minutes(id("minecraft:smithing_transform")), 1e-9);
    }

    @Test
    void unknownTypesFallBackByNamespace() {
        // 没列出来的原版类型 → 原版默认（合成很快，不能按模组算）
        assertEquals(RecipeSteps.DEFAULT_MINECRAFT, RecipeSteps.minutes(id("minecraft:crafting_special_armordye")), 1e-9);
        // 没列出来的模组类型 → 模组默认（产线成本的代理指标，这才是真正该贵的那一档）
        assertEquals(RecipeSteps.DEFAULT_MODDED, RecipeSteps.minutes(id("mekanism:enriching")), 1e-9);
    }

    @Test
    void nullTypeIsTheVanillaDefault() {
        // 拿不到类型的基本都是原版工作台那条路（见 MinecraftRecipeGraph#typeOf），按模组算会贵 16 倍
        assertEquals(RecipeSteps.DEFAULT_MINECRAFT, RecipeSteps.minutes(null), 1e-9);
    }

    @Test
    void datapackCanOverrideOneType() {
        RecipeSteps.override(Map.of(id("mekanism:enriching"), 9.0),
                RecipeSteps.DEFAULT_MODDED, RecipeSteps.DEFAULT_MINECRAFT);
        assertEquals(9.0, RecipeSteps.minutes(id("mekanism:enriching")), 1e-9);
        // 别的类型不受影响
        assertEquals(RecipeSteps.DEFAULT_MODDED, RecipeSteps.minutes(id("mekanism:crushing")), 1e-9);
    }

    @Test
    void datapackCanMoveBothDefaults() {
        RecipeSteps.override(Map.of(), 8.0, 0.5);
        assertEquals(8.0, RecipeSteps.minutes(id("thermal:smelter")), 1e-9);
        assertEquals(0.5, RecipeSteps.minutes(id("minecraft:crafting_special_bannerduplicate")), 1e-9);
    }

    @Test
    void badDefaultsFallBackToTheConstants() {
        // NaN / 负数都是写坏了：按类常量处理，而不是把价格标定搞成 0（那等于关掉整套机制）
        RecipeSteps.override(Map.of(), Double.NaN, -1);
        assertEquals(RecipeSteps.DEFAULT_MODDED, RecipeSteps.minutes(id("thermal:smelter")), 1e-9);
        assertEquals(RecipeSteps.DEFAULT_MINECRAFT,
                RecipeSteps.minutes(id("minecraft:crafting_special_bannerduplicate")), 1e-9);
    }

    @Test
    void nullTableMeansNoOverride() {
        RecipeSteps.override(Map.of(id("mekanism:enriching"), 9.0), 4.0, 0.25);
        RecipeSteps.override(null, 4.0, 0.25);
        assertEquals(RecipeSteps.DEFAULT_MODDED, RecipeSteps.minutes(id("mekanism:enriching")), 1e-9);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
