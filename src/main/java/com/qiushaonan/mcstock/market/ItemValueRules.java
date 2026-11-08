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
 * 物品估值的**地板表**：数据包 JSON 里作者手写的价格。
 *
 * <p>⚠️ 语义是<b>地板</b>，不是锚：配方推导照跑，地板只在推导值更低时把它托起来；
 * 推出来更高就听推导的。这是照 Upgrader + ATM10 Balance 的口径做的 ——
 * 整合包里那些「没有配方」的关键资源（Allthemodium 之类）只能靠这里给个最低价。
 * 详见 {@link ItemValuator} 类注释「地板 ≠ 锚」。
 *
 * <h2>数据包格式</h2>
 * 文件放在 {@code data/<命名空间>/mcstock/item_values/*.json}，本模组自己那份在
 * {@code data/mcstock/mcstock/item_values/core.json}：
 * <pre>{@code
 * {
 *   "values":       { "minecraft:diamond": 40, "#minecraft:logs": 4 },
 *   "unlock_hours": { "minecraft:diamond": 6,  "#minecraft:logs": 1 }
 * }
 * }</pre>
 * 键以 {@code #} 开头的按**物品标签**处理，命中标签的物品都拿到这个地板。
 * 同一个键在多个文件里重复时取最大值（数据包之间的加载顺序不该影响价格）。
 * 值为 {@code 0}（或负数）表示**拉黑**：这件物品既不算也不收，连配方推导一起废掉。
 *
 * <h2>两段是两件事：{@code values} 管「值多少」，{@code unlock_hours} 管「第几小时开放」</h2>
 * {@code unlock_hours} 是**进度档**（见 {@link UnlockStages}）：这件物品在玩家累计玩满
 * 多少小时**游戏时间**之后才允许购买。它刻意和价格分成两段，因为「值多少东西」和
 * 「什么时候该出现」在整合包里几乎不相关 —— ATM10 的 Allthemodium 锭价值不算夸张，
 * 但它该出现的时刻是「整个原版流程打完之后」。想改阶段就改这一段，**别去抬地板价**：
 * 抬价会连带改坏回收价、套利判定和采购冷却。
 *
 * <p>两段都可以不写（只写 {@code values} 的老数据包行为完全不变）；写了 {@code unlock_hours}
 * 就以它为准，没写的物品回落到「买价 × 0.95 秒」（{@link UnlockRules#requiredPlaySeconds(int)}）。
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

    /** 物品进度档（物品 id → 第几小时开放）。语义见 {@link UnlockStages}，与价格无关。 */
    private static volatile Map<ResourceLocation, Integer> itemUnlockHours = Map.of();

    /** 标签进度档（标签 id → 第几小时开放）。 */
    private static volatile Map<ResourceLocation, Integer> tagUnlockHours = Map.of();

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

    /** 物品进度档（不可变快照）：物品 id → 第几小时游戏时间开放。没写 = 查不到。 */
    public static Map<ResourceLocation, Integer> itemUnlockHours() {
        return itemUnlockHours;
    }

    /** 标签进度档（不可变快照）。 */
    public static Map<ResourceLocation, Integer> tagUnlockHours() {
        return tagUnlockHours;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        // ⚠️ 这是**数据包 reload 链**上的一环：这里抛异常会打断整个 reload
        //    （别人的监听器可能因此拿不到数据）。所以整段包一层「绝不抛出」，
        //    并且失败时**保留上一次的种子表**，而不是把价格清空。
        try {
            Map<ResourceLocation, Integer> items = new HashMap<>();
            Map<ResourceLocation, Integer> tags = new HashMap<>();
            Map<ResourceLocation, Integer> itemHours = new HashMap<>();
            Map<ResourceLocation, Integer> tagHours = new HashMap<>();
            int failed = 0;
            for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
                try {
                    readFile(file.getKey(), file.getValue(), items, tags, itemHours, tagHours);
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
            // 整体替换（volatile 引用）：估值线程要么看到旧的一整份、要么看到新的一整份，不会读到半张表。
            // ⚠️ 四张表必须一起换：价格和进度档是同一份 JSON 的两段，只换一半会让「价是新的、阶段是旧的」
            itemSeeds = Map.copyOf(items);
            tagSeeds = Map.copyOf(tags);
            itemUnlockHours = Map.copyOf(itemHours);
            tagUnlockHours = Map.copyOf(tagHours);
            LOGGER.info("[mcstock] 估值种子已加载：{} 项物品 + {} 个标签（来自 {} 个文件，失败 {} 个）；"
                            + "进度档：{} 项物品 + {} 个标签",
                    itemSeeds.size(), tagSeeds.size(), files.size(), failed,
                    itemUnlockHours.size(), tagUnlockHours.size());
        } catch (Throwable e) {
            LOGGER.error("[mcstock] 估值种子表加载整体失败，保留上一次的种子表（{} 项物品 + {} 个标签）",
                    itemSeeds.size(), tagSeeds.size(), e);
        }
    }

    /**
     * 读一个种子文件：{@code values} 段（价格）与 {@code unlock_hours} 段（进度档）各读一遍，
     * 键按 {@code #} 前缀分流进四张表。
     *
     * <p>两段都是可选的，但**至少要有一段**（都没有就是写错了，报一条 WARN）。
     * 分开读而不是合成一个对象，是为了让老数据包（只有 {@code values}）一个字都不用改。
     */
    private static void readFile(ResourceLocation fileId, JsonElement json,
                                 Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags,
                                 Map<ResourceLocation, Integer> itemHours, Map<ResourceLocation, Integer> tagHours) {
        JsonObject root = GsonHelper.convertToJsonObject(json, fileId.toString());
        boolean hasValues = root.has("values");
        boolean hasHours = root.has("unlock_hours");
        if (!hasValues && !hasHours) {
            LOGGER.warn("[mcstock] 估值种子文件 {} 里既没有 values 也没有 unlock_hours，已跳过", fileId);
            return;
        }
        if (hasValues) {
            readSection(fileId, GsonHelper.getAsJsonObject(root, "values"), "values", items, tags);
        }
        if (hasHours) {
            readSection(fileId, GsonHelper.getAsJsonObject(root, "unlock_hours"), "unlock_hours", itemHours, tagHours);
        }
    }

    /** 读一段「键 → 整数」的表，按 {@code #} 前缀分流成「物品」与「标签」两张。 */
    private static void readSection(ResourceLocation fileId, JsonObject section, String name,
                                    Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags) {
        // 进度档的合法范围是 1..UnlockStages.MAX_HOURS；超出就在这里报出来，
        // 免得作者以为「我写了 9999 小时」真的生效了（真正生效的值由 UnlockStages 夹取）
        boolean hours = "unlock_hours".equals(name);
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            String key = entry.getKey();
            int value;
            try {
                value = GsonHelper.convertToInt(entry.getValue(), key);
            } catch (RuntimeException e) {
                LOGGER.warn("[mcstock] {} 段里的 {} 的值不是一个整数，已跳过", name, key);
                continue;
            }
            if (value < 0) {
                // 负价格没有意义；当成 0（= 显式声明「不可回收」）比直接丢掉更好排查。
                // 负的阶段同理当成 0（= 没写，回落到按价格算）
                LOGGER.warn("[mcstock] {} 段里的 {} 是负数（{}），按 0 处理", name, key, value);
                value = 0;
            }
            if (hours && value > UnlockStages.MAX_HOURS) {
                LOGGER.warn("[mcstock] {} 段里的 {} 写了 {} 小时，超过上限 {} 小时，按上限处理",
                        name, key, value, UnlockStages.MAX_HOURS);
            }
            if (key.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                if (tag == null) {
                    LOGGER.warn("[mcstock] {} 段里的 {} 不是合法的标签 id，已跳过", name, key);
                    continue;
                }
                tags.merge(tag, value, Math::max);
            } else {
                ResourceLocation item = ResourceLocation.tryParse(key);
                if (item == null) {
                    LOGGER.warn("[mcstock] {} 段里的 {} 不是合法的物品 id，已跳过", name, key);
                    continue;
                }
                items.merge(item, value, Math::max);
            }
        }
    }
}
