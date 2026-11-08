package com.qiushaonan.mcstock.market;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把服务端 {@link RecipeManager} 里的配方摊平成 {@link RecipeGraph}。
 *
 * <p>覆盖范围（1.21.1 的原版配方类型）：
 * <ul>
 *   <li>工作台：{@code ShapedRecipe} / {@code ShapelessRecipe}，以及盔甲染色一类
 *       {@code CustomRecipe}（它们的产物要么是动态的、要么压根不给静态产物，会被自动跳过）——
 *       ⚠️ 1.21.1 **没有** {@code TransmuteRecipe}，那是 1.21.2 才加的；</li>
 *   <li>烧炼：{@code AbstractCookingRecipe}（熔炉 / 高炉 / 烟熏 / 营火）；</li>
 *   <li>切石机：{@code SingleItemRecipe}（{@code StonecutterRecipe}）；</li>
 *   <li>锻造台：只收 {@code SmithingTransformRecipe}，理由见 {@link #smithingInputs}。</li>
 * </ul>
 *
 * <p>输入统一走 {@link Recipe#getIngredients()}：这些类型的配方都重写了它，
 * 标签型 {@code Ingredient} 在 {@code getItems()} 里就已经展开成具体物品了，
 * 不需要（也没法）在这里分辨「标签还是物品」。
 *
 * <p>惰性构建 + 缓存：{@link #of(ServerLevel)} 第一次调用时建索引，之后直接复用；
 * 数据包 reload 会换掉整个 {@code RecipeManager} 实例，所以缓存按实例身份自动失效，
 * 另外再提供一个显式的 {@link #invalidate()} 给调用方兜底。
 */
public final class MinecraftRecipeGraph implements RecipeGraph {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 「哪份 RecipeManager 建出来的图」和「图本身」绑成一个对象放进**同一个** volatile：
     * 分开存两个 volatile 的话，读线程可能看到「新的 manager + 旧的图」，那就用错配方了。
     */
    private record Cache(RecipeManager source, MinecraftRecipeGraph graph) {
    }

    private static volatile Cache cache;

    private final Map<ResourceLocation, List<RecipeEntry>> byOutput;

    private MinecraftRecipeGraph(Map<ResourceLocation, List<RecipeEntry>> byOutput) {
        this.byOutput = byOutput;
    }

    /**
     * 取（必要时构建）这个服务端的配方图。
     *
     * <p>返回具体类型而不是 {@link RecipeGraph}：调用方（估值重建那条链路）经常要把它当
     * {@code MinecraftRecipeGraph} 存着；接口该有的样子没变，赋值给 {@code RecipeGraph} 照样成立。
     *
     * <p>构建要遍历上千条配方，别在 tick 里反复调 —— 拿到引用以后自己存着；
     * 需要强制重建时调 {@link #invalidate()}。
     *
     * <p>数据包 reload 会整个换掉 {@code RecipeManager} 实例，所以缓存按实例身份自动失效；
     * 万一哪天真遇到「manager 没换但标签变了」，还有 {@link #invalidate()} 兜底。
     */
    public static MinecraftRecipeGraph of(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Cache local = cache;
        if (local != null && local.source() == manager) {
            return local.graph();
        }
        synchronized (MinecraftRecipeGraph.class) {
            if (cache == null || cache.source() != manager) {
                // ⚠️ 图建不起来也必须给出一张**空图**而不是抛异常：调用方（估值重建）
                //    拿到空图只会「所有物品都没价」，而抛异常会把整个 ServerStarted 打断
                //    —— 在整合包里那就是把别人的模组一起拖下水。
                Map<ResourceLocation, List<RecipeEntry>> built = new HashMap<>();
                try {
                    built = index(manager, level.registryAccess());
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    LOGGER.error("[mcstock] 配方图构建整体失败，本次按「所有物品都推导不出价格」处理"
                            + "（估值种子表仍然生效）", t);
                }
                cache = new Cache(manager, new MinecraftRecipeGraph(built));
            }
            return cache.graph();
        }
    }

    /** 丢掉缓存（数据包 reload 后调用；下次 {@link #of} 会重建）。 */
    public static void invalidate() {
        synchronized (MinecraftRecipeGraph.class) {
            cache = null;
        }
    }

    @Override
    public List<RecipeEntry> recipesFor(ResourceLocation output) {
        if (output == null) {
            return List.of();
        }
        List<RecipeEntry> found = byOutput.get(output);
        return found == null ? List.of() : found;
    }

    /** 遍历 {@code RecipeManager} 里全部配方，按产物建倒排索引。 */
    private static Map<ResourceLocation, List<RecipeEntry>> index(RecipeManager manager, HolderLookup.Provider registries) {
        Map<ResourceLocation, List<RecipeEntry>> byOutput = new HashMap<>();
        int usable = 0;
        int skipped = 0;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (add(byOutput, holder.value(), registries)) {
                    usable++;
                }
            } catch (Throwable e) {
                // 一条坏配方（自定义 Ingredient 抛异常、缺前置模组抛 NoClassDefFoundError 之类）
                // 不该让整张估值表崩掉：跳过它，剩下的照算。
                // ⚠️ 单个模组有系统性问题时可能是上百条：**前 5 条逐条打**，其余只计数（别刷屏）。
                skipped++;
                if (skipped <= 5) {
                    LOGGER.warn("[mcstock] 配方 {} 无法摊平成估值输入，已跳过", holder.id(), e);
                } else if (skipped == 6) {
                    LOGGER.warn("[mcstock] 还有更多同类配方解析失败，之后只计数不再逐条打印");
                }
            }
        }
        byOutput.replaceAll((output, entries) -> List.copyOf(entries));
        if (skipped > 0) {
            LOGGER.warn("[mcstock] 配方图构建完成：{} 条配方 → {} 种产物（**跳过 {} 条**）",
                    usable + skipped, byOutput.size(), skipped);
        } else {
            LOGGER.info("[mcstock] 配方图构建完成：{} 条配方 → {} 种产物", usable, byOutput.size());
        }
        return Map.copyOf(byOutput);
    }

    /**
     * 处理一条配方。
     *
     * @return 这条配方有没有被收进图里
     */
    private static boolean add(Map<ResourceLocation, List<RecipeEntry>> byOutput, Recipe<?> recipe, HolderLookup.Provider registries) {
        ItemStack result = recipe.getResultItem(registries);
        if (result == null || result.isEmpty()) {
            // 产物要看输入才知道是什么（盔甲染色、盔甲纹饰、烟花…）：没有固定产物，就不该有固定估价
            return false;
        }
        ResourceLocation output = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (output == null) {
            return false;
        }

        List<RecipeGraph.Ingredient> inputs = recipe instanceof SmithingTransformRecipe smithing
                ? smithingInputs(smithing)
                : plainInputs(recipe.getIngredients());
        if (inputs.isEmpty()) {
            // 一点可估值的输入都没有（动态配方、纯标签空配方…）。
            // 收进来只会让它以「零成本」参与推导，把产物估值压成 0，所以直接不算数。
            return false;
        }

        byOutput.computeIfAbsent(output, key -> new ArrayList<>())
                .add(new RecipeEntry(inputs, Math.max(1, result.getCount())));
        return true;
    }

    /**
     * 把配方给的 {@code Ingredient} 列表转成估值输入，顺手剔掉「不花材料」的格子。
     *
     * <p>⚠️ 原版的 {@code Ingredient} 与本接口的 {@link RecipeGraph.Ingredient} 同名，
     * 而本类实现了 {@code RecipeGraph}、成员类型进了作用域，所以原版那个一律写全名，
     * 免得编译器把两者当成一个东西。
     */
    private static List<RecipeGraph.Ingredient> plainInputs(List<net.minecraft.world.item.crafting.Ingredient> ingredients) {
        List<RecipeGraph.Ingredient> inputs = new ArrayList<>(ingredients.size());
        for (net.minecraft.world.item.crafting.Ingredient ingredient : ingredients) {
            RecipeGraph.Ingredient input = toInput(ingredient);
            if (input != null) {
                inputs.add(input);
            }
        }
        return inputs;
    }

    /** 单个 {@code Ingredient} → 估值输入；没有候选（空格子 / 空标签）时返回 {@code null}。 */
    private static RecipeGraph.Ingredient toInput(net.minecraft.world.item.crafting.Ingredient ingredient) {
        // 工作台配方用一个 Ingredient.EMPTY 占住空格子，空标签还会被塞一个 BARRIER 占位物品，
        // 两者都不是「零成本材料」—— 留着它们会被估值器当成 0 金币原料，把产物直接压成 0。
        if (ingredient == null || ingredient.isEmpty() || ingredient.hasNoItems()) {
            return null;
        }
        List<ResourceLocation> candidates = new ArrayList<>();
        int count = 1;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty() || stack.getItem() == Items.AIR) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            candidates.add(id);
            // 原版配方的输入恒为 1 个；NeoForge 允许在 Ingredient 里带数量，带了就按最大的那个算
            count = Math.max(count, stack.getCount());
        }
        return candidates.isEmpty() ? null : new RecipeGraph.Ingredient(candidates, count);
    }

    /**
     * 锻造台升级配方（下界合金升级那一类）。
     *
     * <p>⚠️ 1.21.1 的 {@code SmithingTransformRecipe} 把 template / base / addition 三个
     * {@code Ingredient} 全藏成了包私有字段，对外只给
     * {@code isTemplateIngredient(ItemStack)} / {@code isBaseIngredient} / {@code isAdditionIngredient}
     * 三个谓词 —— 拿不到 {@code Ingredient} 本身，只能反着筛：把注册表里每个物品都丢进去问一遍。
     * 物品总数一千三百上下、一次数据包 reload 只跑一次，这点代价可以接受。
     *
     * <p>⚠️ 只处理 {@code SmithingTransformRecipe}：{@code SmithingTrimRecipe}（盔甲纹饰）的
     * {@code getResultItem()} 会返回一件**带纹饰的铁胸甲**，可它并不吃那件胸甲（只是拿去展示纹饰），
     * 当成普通配方收进来会把铁胸甲的估值一路打到地板价。
     */
    private static List<RecipeGraph.Ingredient> smithingInputs(SmithingTransformRecipe recipe) {
        List<ResourceLocation> template = new ArrayList<>();
        List<ResourceLocation> base = new ArrayList<>();
        List<ResourceLocation> addition = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (recipe.isTemplateIngredient(stack)) {
                template.add(id);
            }
            if (recipe.isBaseIngredient(stack)) {
                base.add(id);
            }
            if (recipe.isAdditionIngredient(stack)) {
                addition.add(id);
            }
        }
        List<RecipeGraph.Ingredient> inputs = new ArrayList<>(3);
        addSlot(inputs, template);
        addSlot(inputs, base);
        addSlot(inputs, addition);
        return inputs;
    }

    private static void addSlot(List<RecipeGraph.Ingredient> inputs, List<ResourceLocation> candidates) {
        if (!candidates.isEmpty()) {
            inputs.add(new RecipeGraph.Ingredient(candidates, 1));
        }
    }
}
