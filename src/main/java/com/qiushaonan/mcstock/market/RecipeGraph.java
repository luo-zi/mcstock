package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 配方图：估值只关心两件事 —— 「某个产物能用哪些配方做出来」和「每条配方吃什么」。
 *
 * <p>刻意只留这么小的一个接口，而不是把 Minecraft 的 {@code RecipeManager} 直接递给估值器：
 * {@link ItemValuator} 和它的单测要保持纯 Java（不启动游戏也能跑），
 * 真正碰 Minecraft 的解析工作全部收在 {@link MinecraftRecipeGraph} 里。
 *
 * <p>{@link ResourceLocation} 虽然是 Minecraft 的类，但它本质上只是一对字符串（命名空间 + 路径），
 * 不涉及客户端 / 服务端环境、也不需要 bootstrap，拿来当「物品 id」不会把单测拖进游戏里。
 */
public interface RecipeGraph {

    /** 某个产物的全部配方（可能为空）。实现不要返回 {@code null}。 */
    List<RecipeEntry> recipesFor(ResourceLocation output);

    /**
     * 一条配方的一条输入。
     *
     * @param candidates 「多选一」的候选物品：标签在这里已经被展开成具体物品，
     *                   估值时取其中**最便宜**的那个（玩家当然会挑便宜的材料做）
     * @param count      这条输入要消耗几个
     */
    record Ingredient(List<ResourceLocation> candidates, int count) {

        /** 候选表做成不可变的：估值表会被服务端多个线程同时读，不能留可变引用出去。 */
        public Ingredient {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * 一条配方。
     *
     * @param inputs      输入。工作台配方里的**空格子**在这一层就已经被剔掉了
     *                    （原版用 {@code Ingredient.EMPTY} 占位，它不是「零成本材料」）
     * @param outputCount 产出数量，估值时当除数
     * @param type        配方类型 id（{@code minecraft:crafting_shaped} / {@code mekanism:enriching} …）。
     *                    <b>只给「劳动时间」那条轨道用</b>（见 {@link RecipeSteps}）：
     *                    回收价（{@link ItemValuator#value}）一个字都用不到它，
     *                    所以老调用方传 {@code null} 也不会改变任何回收价。
     *                    {@code null} = 未知类型，按 {@link RecipeSteps#DEFAULT_MINECRAFT} 处理
     */
    record RecipeEntry(List<Ingredient> inputs, int outputCount, ResourceLocation type) {

        public RecipeEntry {
            inputs = List.copyOf(inputs);
        }
    }

    /** 空图：配方还没加载好、或者只想看看种子价时先给个不炸的实现。 */
    RecipeGraph EMPTY = output -> List.of();
}
