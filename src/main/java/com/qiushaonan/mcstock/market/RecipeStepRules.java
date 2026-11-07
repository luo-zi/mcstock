package com.qiushaonan.mcstock.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * 工序步长的**数据包覆盖表**：整合包作者按自己包的难度调 {@link RecipeSteps}，不用改代码。
 *
 * <h2>数据包格式</h2>
 * 文件放在 {@code data/<命名空间>/mcstock/recipe_steps/*.json}：
 * <pre>{@code
 * { "steps": { "minecraft:crafting_shaped": 0.1, "mekanism:enriching": 6.0 } }
 * }</pre>
 * 值是**分钟**（可以带小数）。同一个键在多个文件里重复时取**最大值**
 * （数据包之间的加载顺序不该影响价格；而步长越大越保守）。
 *
 * <p>目录名垫了一层 {@code mcstock}，理由与 {@link ItemValueRules} 完全一样：
 * 不和「覆盖原版数据」的那些文件混在一起。
 *
 * <p>⚠️ 失败口径也照抄 {@link ItemValueRules}：这是**数据包 reload 链**上的一环，
 * 这里抛异常会打断整个 reload（别人的监听器跟着拿不到数据）。所以整段「绝不抛出」，
 * 失败时保留上一次的表，而不是把步长清空 —— 清空会让所有模组物品瞬间变便宜，
 * 那是比崩溃更难查的一类问题。
 */
public final class RecipeStepRules extends SimpleJsonResourceReloadListener {

    /** 相对 {@code data/<命名空间>/} 的目录。 */
    private static final String DIRECTORY = "mcstock/recipe_steps";

    private static final Gson GSON = new GsonBuilder().create();

    private static final Logger LOGGER = LogUtils.getLogger();

    public RecipeStepRules() {
        super(GSON, DIRECTORY);
    }

    /** 造一个监听器实例交给 {@code AddReloadListenerEvent#addListener}（每次 reload 都要新建一份）。 */
    public static PreparableReloadListener listener() {
        return new RecipeStepRules();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        try {
            Map<ResourceLocation, Double> steps = new HashMap<>();
            // [0] = 模组默认，[1] = 原版默认；NaN = 文件里没写（那就用 {@link RecipeSteps} 的类常量）
            double[] defaults = {Double.NaN, Double.NaN};
            int failed = 0;
            for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
                try {
                    readFile(file.getKey(), file.getValue(), steps, defaults);
                } catch (Throwable e) {
                    failed++;
                    LOGGER.warn("[mcstock] 工序步长文件 {} 解析失败，已跳过", file.getKey(), e);
                }
            }
            boolean nothing = steps.isEmpty() && Double.isNaN(defaults[0]) && Double.isNaN(defaults[1]);
            if (nothing && !RecipeSteps.overrides().isEmpty()) {
                LOGGER.error("[mcstock] 工序步长全部解析失败（共 {} 个文件、失败 {} 个），"
                        + "保留上一次的覆盖表（{} 项）", files.size(), failed, RecipeSteps.overrides().size());
                return;
            }
            RecipeSteps.override(steps, defaults[0], defaults[1]);
            LOGGER.info("[mcstock] 工序步长覆盖已加载：{} 项 + 默认值（模组 {} / 原版 {})"
                            + "，来自 {} 个文件，失败 {} 个",
                    steps.size(), RecipeSteps.defaultModded(), RecipeSteps.defaultVanilla(),
                    files.size(), failed);
        } catch (Throwable e) {
            LOGGER.error("[mcstock] 工序步长表加载整体失败，保留上一次的覆盖表（{} 项）",
                    RecipeSteps.overrides().size(), e);
        }
    }

    /** 两个特殊键（不是配方类型 id，所以不走 {@code ResourceLocation} 解析）。 */
    private static final String KEY_MODDED_DEFAULT = "#default";
    private static final String KEY_VANILLA_DEFAULT = "#vanilla";

    /** 读一个文件，把 {@code steps} 里的条目并进结果表（重复取最大）。 */
    private static void readFile(ResourceLocation fileId, JsonElement json,
                                 Map<ResourceLocation, Double> steps, double[] defaults) {
        JsonObject root = GsonHelper.convertToJsonObject(json, fileId.toString());
        if (!root.has("steps")) {
            LOGGER.warn("[mcstock] 工序步长文件 {} 里没有 steps 字段，已跳过", fileId);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(root, "steps").entrySet()) {
            String key = entry.getKey();
            double minutes;
            try {
                // 不用 GsonHelper.convertToDouble：Gson 自己的 getAsDouble 对 "4.0" / 4 都认，
                // 出错也只抛 RuntimeException（下面接得住），少一层对 Tool 静态方法签名的依赖
                minutes = entry.getValue().getAsDouble();
            } catch (RuntimeException e) {
                LOGGER.warn("[mcstock] 工序步长 {} 的值不是一个数字，已跳过", key);
                continue;
            }
            if (!Double.isFinite(minutes) || minutes < 0) {
                // 负步长会让价格随链长**下降**（等于奖励长链），比写错更危险：按 0 处理并报警
                LOGGER.warn("[mcstock] 工序步长 {} 不是非负有限数（{}），按 0 处理", key, minutes);
                minutes = 0;
            }
            // 两个特殊键：给「没列出来的类型」一个整包通用的默认值
            //（模组类型成百上千，整合包作者不可能逐个列；这一档才是他们真正想调的那个数）
            if (KEY_MODDED_DEFAULT.equals(key) || KEY_VANILLA_DEFAULT.equals(key)) {
                int slot = KEY_MODDED_DEFAULT.equals(key) ? 0 : 1;
                defaults[slot] = Double.isNaN(defaults[slot]) ? minutes : Math.max(defaults[slot], minutes);
                continue;
            }
            ResourceLocation type = ResourceLocation.tryParse(key);
            if (type == null) {
                LOGGER.warn("[mcstock] 工序步长 {} 不是合法的配方类型 id，已跳过", key);
                continue;
            }
            steps.merge(type, minutes, Math::max);
        }
    }
}
