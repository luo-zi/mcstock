package com.qiushaonan.mcstock.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * **测试用的估值表读取器**：直接从 classpath 上那份真数据包
 * （{@code /data/mcstock/mcstock/item_values/core.json}）读**物品种子**，
 * 供「兑换比检查」与「经济模拟」共用。
 *
 * <p>为什么测试要自己读一遍：{@link ItemValuatorTest} 用的是写死的 stub 种子
 * （验的是算法），而**真表里的数字**（钻石值多少、木头值多少）没有任何单测守着 ——
 * 历史上正是这里出的漏洞（木头 4 / 钻石 40 → 50 根木头换一颗钻石）。
 *
 * <p>⚠️ 这里只读**物品种子**，不解析标签（{@code #minecraft:logs} 这种）：
 * 标签要展开成成员物品得有注册表，纯 JVM 里没有。代表物品在表里都是显式物品条目，
 * 所以兑换比检查不依赖标签；标签键的合法性由 {@link ExchangeRateTest} 单独校验。
 */
final class ValueTableFixture {

    private static final String PATH = "/data/mcstock/mcstock/item_values/core.json";

    /**
     * 代表物品 → **覆盖它的标签**。
     *
     * <p>纯 JVM 里没有物品注册表，展开不了标签，所以这里把检查用得到的几个代表物品
     * 显式登记到它的标签上（表里就是这么写的：{@code #minecraft:logs} 4）。
     * 只影响「读价」，不改游戏里的逻辑（游戏里由 {@code StockEvents#tagMembers} 展开）。
     */
    private static final Map<String, String> TAG_FALLBACK = Map.of(
            "minecraft:oak_log", "#minecraft:logs",
            "minecraft:coal", "#minecraft:coals",
            "minecraft:white_wool", "#minecraft:wool",
            "minecraft:white_carpet", "#minecraft:wool_carpets",
            "minecraft:beef", "#minecraft:meat",
            "minecraft:cod", "#minecraft:fishes");

    private final Map<String, Integer> itemSeeds = new HashMap<>();
    private final Map<String, Integer> tagSeeds = new HashMap<>();
    private final JsonObject root;

    private ValueTableFixture(JsonObject root) {
        this.root = root;
        JsonObject values = root.getAsJsonObject("values");
        for (Map.Entry<String, com.google.gson.JsonElement> entry : values.entrySet()) {
            String key = entry.getKey();
            int value = entry.getValue().getAsInt();
            if (key.startsWith("#")) {
                tagSeeds.put(key.substring(1), value);
            } else {
                itemSeeds.put(key, value);
            }
        }
    }

    static ValueTableFixture load() {
        try (InputStream in = ValueTableFixture.class.getResourceAsStream(PATH)) {
            if (in == null) {
                throw new AssertionError("估值表不在 classpath 上：" + PATH);
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return new ValueTableFixture(root);
        } catch (Exception e) {
            throw new AssertionError("读不到估值表 " + PATH, e);
        }
    }

    /**
     * 物品价（金币）：先查物品种子 → 再退回**覆盖它的标签**（见 {@link #TAG_FALLBACK}）
     * → 最后按 {@link #MINI_RECIPES} 做**一步**推导；都没有返回 0（= 既不可回收也不可购买）。
     *
     * <p>⚠️ {@link #MINI_RECIPES} 只是给「套利验算」补上那几个**没有种子**的产物（西瓜 / 干海带 /
     * 蜂蜜块 / 蘑菇煲），复刻的是引擎里那一行公式 {@code floor(Σ 原料价 × 数量 / 产出)}。
     * **它不是第二份估值实现**：真表由游戏里的 {@code ItemValuator} 从完整配方图推出来。
     */
    int value(String itemId) {
        Integer direct = itemSeeds.get(itemId);
        if (direct != null) {
            return direct;
        }
        String tag = TAG_FALLBACK.get(itemId);
        if (tag != null) {
            return tagSeeds.getOrDefault(tag.substring(1), 0);
        }
        return derivedValue(itemId);
    }

    /**
     * 「最小推导表」：{产物, 产出数量, 原料…, 每种原料数量}。
     *
     * <p>只收**单一配方、且原料都有价**的产物 —— 这样一步推导就等于真表的值
     * （真表在无环情况下就是这两者的最小值；这些产物都只有一条配方）。
     * 蘑菇煲里那半个「碗」按 0 算（3 木板 → 4 碗，推导值本来就是 0），所以不列进原料。
     */
    private static final Object[][] MINI_RECIPES = {
            {"minecraft:melon", 1, new Object[]{"minecraft:melon_slice", 9}},
            {"minecraft:dried_kelp", 1, new Object[]{"minecraft:kelp", 1}},
            {"minecraft:honey_block", 1, new Object[]{"minecraft:honey_bottle", 4}},
            {"minecraft:mushroom_stew", 1,
                    new Object[]{"minecraft:red_mushroom", 1, "minecraft:brown_mushroom", 1}},
            {"minecraft:popped_chorus_fruit", 1, new Object[]{"minecraft:chorus_fruit", 1}},
    };

    /** 一步推导（与引擎同一个公式与取整：{@code floor(成本 / 产出)}）。 */
    private int derivedValue(String itemId) {
        for (Object[] recipe : MINI_RECIPES) {
            if (!recipe[0].equals(itemId)) {
                continue;
            }
            int output = (Integer) recipe[1];
            Object[] flat = (Object[]) recipe[2];
            long cost = 0;
            for (int i = 0; i < flat.length; i += 2) {
                int unit = value((String) flat[i]);
                if (unit <= 0) {
                    return 0;       // 有原料没价 → 这条配方不参与推导
                }
                cost += (long) unit * (Integer) flat[i + 1];
            }
            return (int) (cost / output);
        }
        return 0;
    }

    /** 标签种子（金币）；没有条目返回 0。 */
    int tagValue(String tagId) {
        return tagSeeds.getOrDefault(tagId, 0);
    }

    boolean hasItem(String itemId) {
        return itemSeeds.containsKey(itemId) || TAG_FALLBACK.containsKey(itemId);
    }

    Map<String, Integer> itemSeeds() {
        return Map.copyOf(itemSeeds);
    }

    Map<String, Integer> tagSeeds() {
        return Map.copyOf(tagSeeds);
    }

    /** 整份 JSON（含 {@code tiers} 文档块），用来校验分档说明还在。 */
    JsonObject root() {
        return root;
    }

    /**
     * 采购价（金币）= {@code ceil(估值 × PURCHASE_MARKUP)}，与
     * {@link ItemValuator#purchasePrice} 逐字相同（买价就是这一个公式，没有第二条支）。
     *
     * <p>用在这两处正好够用：
     * <ul>
     *   <li>{@code exchangeTableIsAnchoredToRarity} 断言的是「换 1 个 X 至少需要多少个泥土」的下限；</li>
     *   <li>{@code buyingIngredientsCannotBeArbitraged} 断言「买原料 → 合成 → 回收」不赚钱 ——
     *       这里量的是**地板**给的价，配方推导只会更高，所以我们测的是最宽松的那一侧。</li>
     * </ul>
     */
    int purchasePrice(String itemId) {
        int value = value(itemId);
        return value <= 0 ? 0 : (int) Math.ceil(value * ItemValuator.PURCHASE_MARKUP);
    }
}
