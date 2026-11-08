package com.qiushaonan.mcstock.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.qiushaonan.mcstock.Errors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **「绝不抛异常」的加固用例**（纯 Java，不需要游戏环境）。
 *
 * <p>背景：线上那份崩溃报告（整合包 + Sinytra Connector）里，某一家的 reload 监听器失败会让
 * {@code ServerStarted} 链断掉、把别人的模组一起拖下水。我们控制不了别人，但必须保证
 * **自己的代码在加载 / 重载 / 启动 / 读档的任何阶段都不抛异常**。
 *
 * <p>这一组用例就是那份承诺的锚：喂畸形 JSON、喂会抛异常的配方图、喂被改坏的存档 NBT，
 * 断言「不抛异常」+「降级行为正确」（保留旧表 / 跳过坏条目 / 退回新市场）。
 */
class HardeningTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.parse("minecraft:diamond");
    private static final ResourceLocation LOGS_TAG = ResourceLocation.parse("minecraft:logs");
    private static final ResourceLocation OAK_LOG = ResourceLocation.parse("minecraft:oak_log");

    /** 本类动的是 {@link ItemValuator} 的静态表：跑完清空，别影响别的用例（见类注释）。 */
    @AfterAll
    static void clearValuatorTable() {
        ItemValuator.rebuild(Map.of(), Map.of(), Map.of(), Set.of(), null);
    }

    // ==================== 1. 数据包种子文件：畸形 JSON ====================

    @Test
    void malformedSeedFilesNeverThrowAndKeepThePreviousTable() {
        ItemValueRules rules = new ItemValueRules();

        // ① 先喂一份好的：钻石 300
        Map<ResourceLocation, com.google.gson.JsonElement> good = new HashMap<>();
        good.put(ResourceLocation.parse("mcstock:test_good"), seedFile(Map.of("minecraft:diamond", 300)));
        assertDoesNotThrow(() -> rules.apply(good, null, null));
        assertEquals(300, ItemValueRules.itemSeeds().get(DIAMOND), "好文件必须正常加载");

        // ② 全坏：不是对象、缺 values、values 是数组、值不是数字、id 非法、负数 …
        Map<ResourceLocation, com.google.gson.JsonElement> allBad = new HashMap<>();
        allBad.put(ResourceLocation.parse("mcstock:bad_primitive"), new JsonPrimitive("这不是一个对象"));
        allBad.put(ResourceLocation.parse("mcstock:bad_missing_values"),
                JsonParser.parseString("{\"tiers\": {}}"));
        allBad.put(ResourceLocation.parse("mcstock:bad_values_array"),
                JsonParser.parseString("{\"values\": [1, 2, 3]}"));
        allBad.put(ResourceLocation.parse("mcstock:bad_values_types"),
                JsonParser.parseString("{\"values\": {\"minecraft:diamond\": \"很多\"}}"));
        allBad.put(ResourceLocation.parse("mcstock:bad_ids"),
                JsonParser.parseString("{\"values\": {\"这不是 id\": 5, \"minecraft:UPPER\": 5}}"));
        allBad.put(ResourceLocation.parse("mcstock:bad_null"), null);
        assertDoesNotThrow(() -> rules.apply(allBad, null, null), "畸形 JSON 绝不允许抛出去");
        assertEquals(300, ItemValueRules.itemSeeds().get(DIAMOND),
                "全部解析失败时**必须保留上一次的种子表**（清空会让所有物品一夜之间变成不值钱）");

        // ③ 一半好一半坏：好的那条照样生效
        Map<ResourceLocation, com.google.gson.JsonElement> mixed = new HashMap<>(allBad);
        mixed.put(ResourceLocation.parse("mcstock:test_good2"), seedFile(Map.of("minecraft:emerald", 20)));
        assertDoesNotThrow(() -> rules.apply(mixed, null, null));
        assertEquals(20, ItemValueRules.itemSeeds().get(ResourceLocation.parse("minecraft:emerald")),
                "坏文件不该影响好文件");
        // 语义说明：一次 reload 传进来的是**整个目录的完整集合**，所以只要有一个文件解析成功，
        // 就是「整体替换」（而不是和上一次合并）—— 上面那次「全坏」才会走「保留旧表」的分支。
        assertEquals(1, ItemValueRules.itemSeeds().size(),
                "成功的 reload 是整体替换：这次只有 emerald 一个条目，实际 " + ItemValueRules.itemSeeds());
    }

    /**
     * 进度档（{@code unlock_hours}）：段里的坏条目只丢那一条，绝不影响价格段，也不许抛。
     *
     * <p>两段在同一个文件里，最容易踩的坑是「一个值的类型写错 → 整个文件被丢掉 → 价格也没了」。
     * 实际实现是**逐条 try/catch**，所以钻石的价格和阶段能一起活下来。
     */
    @Test
    void unlockHoursSectionSurvivesMalformedEntries() {
        ItemValueRules rules = new ItemValueRules();

        Map<ResourceLocation, com.google.gson.JsonElement> files = new HashMap<>();
        files.put(ResourceLocation.parse("mcstock:stage_mixed"), JsonParser.parseString("""
                {
                  "values":       {"minecraft:diamond": 300},
                  "unlock_hours": {
                    "minecraft:diamond": 6,
                    "minecraft:emerald": "六小时",
                    "#这不是标签": 8,
                    "minecraft:wool": -2
                  }
                }"""));
        assertDoesNotThrow(() -> rules.apply(files, null, null), "进度档写坏了绝不允许抛出去");

        assertEquals(300, ItemValueRules.itemSeeds().get(DIAMOND), "同文件里的价格段照常生效");
        assertEquals(6, ItemValueRules.itemUnlockHours().get(DIAMOND), "好的条目要留下");
        assertFalse(ItemValueRules.itemUnlockHours().containsKey(ResourceLocation.parse("minecraft:emerald")),
                "值不是整数的条目要跳过，不能保留也不能抛");
        assertEquals(0, ItemValueRules.itemUnlockHours().getOrDefault(
                        ResourceLocation.parse("minecraft:wool"), -1),
                "负的阶段按 0 处理（= 没写，回落到按价格算）");

        // 整段类型写错（values 是个数组）：那个文件整体失败，但不能把上一条好数据冲掉
        Map<ResourceLocation, com.google.gson.JsonElement> broken = new HashMap<>();
        broken.put(ResourceLocation.parse("mcstock:broken"), JsonParser.parseString("{\"values\": [1, 2, 3]}"));
        assertDoesNotThrow(() -> rules.apply(broken, null, null));
        assertEquals(6, ItemValueRules.itemUnlockHours().get(DIAMOND), "全部失败时要保留上一份表");
    }

    @Test
    void tagSeedsSurviveUnparseableTags() {
        ItemValueRules rules = new ItemValueRules();
        Map<ResourceLocation, com.google.gson.JsonElement> files = new HashMap<>();
        files.put(ResourceLocation.parse("mcstock:test_tags"),
                seedFile(Map.of("#minecraft:logs", 4, "#这不是标签", 9, "#minecraft:wool", -3)));
        assertDoesNotThrow(() -> rules.apply(files, null, null));
        assertEquals(4, ItemValueRules.tagSeeds().get(LOGS_TAG), "合法标签条目要留下");
        assertEquals(0, ItemValueRules.tagSeeds().getOrDefault(ResourceLocation.parse("minecraft:wool"), -1),
                "负价格按 0 处理（标签地板只认正数，0/负 = 这条标签不生效），不是丢掉");
        assertTrue(ItemValueRules.tagSeeds().size() >= 2, "非法标签 id 要丢掉而不是抛异常（当前 " + ItemValueRules.tagSeeds() + "）");
    }

    // ==================== 2. 估值表：坏配方图 / 坏条目 ====================

    @Test
    void valuatorSurvivesAHostileRecipeGraph() {
        Map<ResourceLocation, Integer> seeds = Map.of(DIAMOND, 300);
        Map<ResourceLocation, Integer> tagSeeds = Map.of(LOGS_TAG, 4);
        Map<ResourceLocation, Set<ResourceLocation>> members = Map.of(LOGS_TAG, Set.of(OAK_LOG));
        Set<ResourceLocation> allItems = Set.of(DIAMOND, OAK_LOG,
                ResourceLocation.parse("minecraft:diamond_pickaxe"));

        // 每条查询都抛的配方图（模拟「某个模组的 RecipeGraph 实现在这一条上炸了」）
        RecipeGraph hostile = output -> {
            if (output.toString().contains("pickaxe")) {
                throw new IllegalStateException("模拟坏配方");
            }
            return List.of();
        };
        assertDoesNotThrow(() -> ItemValuator.rebuild(seeds, tagSeeds, members, allItems, hostile),
                "坏配方图绝不允许把整张估值表带崩");
        assertEquals(300, ItemValuator.value(DIAMOND), "种子照旧生效");
        assertEquals(4, ItemValuator.value(OAK_LOG), "标签种子照旧生效");
        assertEquals(0, ItemValuator.value(ResourceLocation.parse("minecraft:diamond_pickaxe")),
                "推不出来的物品按「无价」处理，不是崩掉");

        // 更极端：graph 本身是 null、所有入参都是 null
        assertDoesNotThrow(() -> ItemValuator.rebuild(null, null, null, null, null));
        assertDoesNotThrow(() -> ItemValuator.rebuild(Map.of(), Map.of(), Map.of(), Set.of(), null));
    }

    // ==================== 3. 存档：脏 NBT ====================

    @Test
    void corruptMarketTagFallsBackToAFreshMarket() {
        // prices 被写成了字符串（原版 getLongArray 遇到类型不符会抛 IllegalStateException）
        CompoundTag corrupt = new CompoundTag();
        corrupt.putString("prices", "被我改坏了");
        corrupt.putLong("advances", Long.MAX_VALUE);       // 想用巨大跳数把加载挂住
        StockMarketData data = assertDoesNotThrow(() -> StockMarketData.load(corrupt, null),
                "脏存档必须退回「新市场」，而不是让服务器起不来");
        assertNotNull(data, "读档失败也要给出一份可用的行情");
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            long price = data.engine().price(stock);
            assertTrue(price >= StockDefs.priceFloor(stock) && price <= StockDefs.priceCeil(stock),
                    "新市场的价格必须在合法区间：" + price);
        }

        // 长度不对 / 空 tag / null：都不许抛
        assertDoesNotThrow(() -> StockMarketData.load(new CompoundTag(), null));
        CompoundTag wrongLength = new CompoundTag();
        wrongLength.putLongArray("prices", new long[]{1, 2});
        wrongLength.putLongArray("history", new long[]{1, 2, 3});
        assertDoesNotThrow(() -> StockMarketData.load(wrongLength, null));
    }

    @Test
    void restoreClampsGarbagePricesIntoTheLegalBand() {
        long[] garbage = {0L, -5L, Long.MAX_VALUE};
        MarketEngine engine = assertDoesNotThrow(
                () -> MarketEngine.restore(42L, 100L, -7, garbage, new long[]{-1L, 0L, Long.MIN_VALUE}));
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            long price = engine.price(stock);
            assertTrue(price >= StockDefs.priceFloor(stock) && price <= StockDefs.priceCeil(stock),
                    "脏价格必须被夹回合法区间，实际 " + price);
        }
        assertTrue(engine.ticksToNext() > 0, "倒计时是坏值时退回默认节拍");
    }

    // ==================== 4. 「只记一次」的护栏 ====================

    @Test
    void guardLogsOncePerKeyAndStillRethrowsFatalVmErrors() {
        Errors.resetForTests();
        String key = "HardeningTest#guard";
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> Errors.guard(key, "模拟持续失败", () -> {
                throw new IllegalStateException("boom");
            }));
        }
        assertTrue(Errors.hasLogged(key), "第一次必须留下日志（否则线上无从查起）");
        assertEquals(4, Errors.suppressedCount(key), "之后的 4 次只计数、不再刷屏");
        assertEquals("兜底值", Errors.guard(key, "模拟失败", () -> {
            throw new IllegalStateException("boom");
        }, "兜底值"), "带返回值的护栏要给出兜底值");

        // 虚拟机的致命错误必须照抛（吞掉 OOM 只会让服务器带着坏状态继续跑）
        assertThrows(OutOfMemoryError.class, () -> Errors.guard("HardeningTest#oom", "OOM",
                () -> {
                    throw new OutOfMemoryError("模拟");
                }));
        // 缺前置模组那种 LinkageError 必须被接住（只 catch Exception 是接不住的）
        assertDoesNotThrow(() -> Errors.guard(key, "NoClassDefFoundError",
                () -> {
                    throw new NoClassDefFoundError("某个没装的模组的类");
                }));
    }

    // ==================== 工具 ====================

    /** 造一份合法的种子文件 JSON：{@code {"values": {...}}}。 */
    private static com.google.gson.JsonElement seedFile(Map<String, Integer> values) {
        JsonObject root = new JsonObject();
        JsonObject inner = new JsonObject();
        values.forEach(inner::addProperty);
        root.add("values", inner);
        return root;
    }

    /** 留一个「数组当 values」的形态给别处用（说明 JsonArray 也是合法 JsonElement）。 */
    @SuppressWarnings("unused")
    private static JsonArray arrayOf(int... numbers) {
        JsonArray array = new JsonArray();
        for (int number : numbers) {
            array.add(number);
        }
        return array;
    }
}
