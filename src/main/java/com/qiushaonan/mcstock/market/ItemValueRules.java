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
 * 物品估值的**种子表**：数据包 JSON 里作者手写的价格，是整张估值表的锚点
 * （没有种子的物品才轮到配方推导，见 {@link ItemValuator}）。
 *
 * <h2>数据包格式</h2>
 * 文件放在 {@code data/<命名空间>/mcstock/item_values/*.json}，本模组自己那份在
 * {@code data/mcstock/mcstock/item_values/core.json}：
 * <pre>{@code
 * { "values": { "minecraft:diamond": 40, "#minecraft:logs": 4 } }
 * }</pre>
 * 键以 {@code #} 开头的按**物品标签**处理，命中标签的物品都拿到这个价。
 * 同一个键在多个文件里重复时取最大值（数据包之间的加载顺序不该影响价格）。
 *
 * <p>目录名是 {@code "mcstock/item_values"} 而不是 {@code "item_values"}：
 * 原版监听器的目录直接落在 {@code data/<命名空间>/} 下（{@code recipe}、{@code advancement}…），
 * 这里多垫一层 {@code mcstock}，才不会和「覆盖原版数据」的那些文件混在一起。
 *
 * <p>本类只管「把 JSON 读成两张表」：注册监听器与「拿种子 + 配方图重算估值表」分别在
 * {@code StockEvents} 的 {@code AddReloadListenerEvent} / {@code ServerStartedEvent} 里接，
 * 这样数据包 reload 换掉 {@code RecipeManager} 之后，估值表会在下次需要时自动跟上。
 */
public final class ItemValueRules extends SimpleJsonResourceReloadListener {

    /** 见类注释：相对 {@code data/<命名空间>/} 的目录。 */
    private static final String DIRECTORY = "mcstock/item_values";

    /** 原版监听器都是自带一个 Gson 实例的（{@link SimpleJsonResourceReloadListener} 只负责按目录扫文件）。 */
    private static final Gson GSON = new GsonBuilder().create();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 物品种子（物品 id → 金币）。整张表在 reload 时整体替换，读方拿到的是不可变快照。 */
    private static volatile Map<ResourceLocation, Integer> itemSeeds = Map.of();

    /** 标签种子（标签 id → 金币）。 */
    private static volatile Map<ResourceLocation, Integer> tagSeeds = Map.of();

    public ItemValueRules() {
        super(GSON, DIRECTORY);
    }

    /**
     * 造一个监听器实例，交给 {@code AddReloadListenerEvent#addListener}。
     *
     * <p>写成工厂而不是让调用方 {@code new}：数据包每次 reload 都要**重新注册一份**，
     * 「每次都新建」这件事由这里负责，调用点就不会不小心缓存住一个已经过期的实例。
     */
    public static PreparableReloadListener listener() {
        return new ItemValueRules();
    }

    /** 物品种子（不可变快照，可以安全地跨线程持有）。 */
    public static Map<ResourceLocation, Integer> itemSeeds() {
        return itemSeeds;
    }

    /** 标签种子（不可变快照）。 */
    public static Map<ResourceLocation, Integer> tagSeeds() {
        return tagSeeds;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        // ⚠️ 这是**数据包 reload 链**上的一环：这里抛异常会打断整个 reload
        //    （别人的监听器可能因此拿不到数据）。所以整段包一层「绝不抛出」，
        //    并且失败时**保留上一次的种子表**，而不是把价格清空。
        try {
            Map<ResourceLocation, Integer> items = new HashMap<>();
            Map<ResourceLocation, Integer> tags = new HashMap<>();
            int failed = 0;
            for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
                try {
                    readFile(file.getKey(), file.getValue(), items, tags);
                } catch (Throwable e) {
                    // 一个写坏的数据包文件不该让整个 reload 失败：跳过它，其他文件照常生效
                    failed++;
                    LOGGER.warn("[mcstock] 估值种子文件 {} 解析失败，已跳过", file.getKey(), e);
                }
            }
            if (items.isEmpty() && tags.isEmpty() && !itemSeeds.isEmpty()) {
                // 「所有文件都失败了」不是「作者把表清空了」：保留上一次的表比清空安全得多
                LOGGER.error("[mcstock] 估值种子全部解析失败（共 {} 个文件、失败 {} 个），"
                        + "保留上一次的种子表（{} 项物品 + {} 个标签）",
                        files.size(), failed, itemSeeds.size(), tagSeeds.size());
                return;
            }
            // 整体替换（volatile 引用）：估值线程要么看到旧的一整份、要么看到新的一整份，不会读到半张表
            itemSeeds = Map.copyOf(items);
            tagSeeds = Map.copyOf(tags);
            LOGGER.info("[mcstock] 估值种子已加载：{} 项物品 + {} 个标签（来自 {} 个文件，失败 {} 个）",
                    itemSeeds.size(), tagSeeds.size(), files.size(), failed);
        } catch (Throwable e) {
            LOGGER.error("[mcstock] 估值种子表加载整体失败，保留上一次的种子表（{} 项物品 + {} 个标签）",
                    itemSeeds.size(), tagSeeds.size(), e);
        }
    }

    /** 读一个种子文件，把里面的键按 {@code #} 前缀分流进两张表。 */
    private static void readFile(ResourceLocation fileId, JsonElement json,
                                 Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags) {
        JsonObject root = GsonHelper.convertToJsonObject(json, fileId.toString());
        if (!root.has("values")) {
            LOGGER.warn("[mcstock] 估值种子文件 {} 里没有 values 字段，已跳过", fileId);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(root, "values").entrySet()) {
            String key = entry.getKey();
            int value;
            try {
                value = GsonHelper.convertToInt(entry.getValue(), key);
            } catch (RuntimeException e) {
                LOGGER.warn("[mcstock] 估值种子 {} 的值不是一个整数，已跳过", key);
                continue;
            }
            if (value < 0) {
                // 负价格没有意义；当成 0（= 显式声明「不可回收」）比直接丢掉更好排查
                LOGGER.warn("[mcstock] 估值种子 {} 是负数（{}），按 0 处理", key, value);
                value = 0;
            }
            if (key.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                if (tag == null) {
                    LOGGER.warn("[mcstock] 估值种子 {} 不是合法的标签 id，已跳过", key);
                    continue;
                }
                tags.merge(tag, value, Math::max);
            } else {
                ResourceLocation item = ResourceLocation.tryParse(key);
                if (item == null) {
                    LOGGER.warn("[mcstock] 估值种子 {} 不是合法的物品 id，已跳过", key);
                    continue;
                }
                items.merge(item, value, Math::max);
            }
        }
    }
}
