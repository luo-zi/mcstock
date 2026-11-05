package com.qiushaonan.mcstock.market;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **估值表的稀有度锚点检查**（纯 Java、读真数据包、可进 CI）。
 *
 * <p>守的是一个具体的历史漏洞：玩家发现「**50 个木头 = 4 颗钻石**」——
 * 木头种子 4、钻石种子 40，采购价 ×1.25 之后 200 金币就能买 4 颗钻石。
 * 根因是种子表没有锚定「获取难度 / 稀有度」，常见资源定价偏高、稀有资源定价偏低。
 *
 * <h2>核心指标：兑换比下限</h2>
 * <p>「用最廉价的可回收资源（泥土，1 金币/个）换 1 个 X，至少需要多少个？」
 * = {@code ceil(估值 × 1.25)}（泥土在表里是 1 金币，回收价 = 估值）。
 * 这条把「稀有度」翻译成一个玩家能直接感受到的数字（要挖多少块泥巴才能换到）。
 *
 * <p>下面的下限是**设计口径**（不是从表里算出来的），所以改表改坏了会被当场抓住：
 * 钻石 ≥ 300、下界合金碎片 ≥ 500、鞘翅 ≥ 3000、下界之星 ≥ 3000。
 */
class ExchangeRateTest {

    /** 最廉价的可回收资源：泥土。它决定了「多少个」这个单位。 */
    private static final String BASELINE = "minecraft:dirt";

    /** 需要打印/断言的代表物品（顺序 = 大致从便宜到贵）。 */
    private static final String[][] REPRESENTATIVE = {
            {"minecraft:dirt", "泥土"},
            {"minecraft:cobblestone", "圆石"},
            {"minecraft:oak_log", "木头"},
            {"minecraft:coal", "煤炭"},
            {"minecraft:iron_ingot", "铁锭"},
            {"minecraft:gold_ingot", "金锭"},
            {"minecraft:emerald", "绿宝石（村民交易，可再生）"},
            {"minecraft:obsidian", "黑曜石（水+岩浆，可再生）"},
            {"minecraft:crying_obsidian", "哭泣的黑曜石（可再生）"},
            {"minecraft:sponge", "海绵（可再生）"},
            {"minecraft:nautilus_shell", "鹦鹉螺壳（可再生）"},
            {"minecraft:scute", "鳞甲（可再生）"},
            {"minecraft:blaze_rod", "烈焰棒"},
            {"minecraft:ender_pearl", "末影珍珠"},
            {"minecraft:diamond", "钻石"},
            {"minecraft:netherite_scrap", "下界合金碎片"},
            {"minecraft:shulker_shell", "潜影壳"},
            {"minecraft:heart_of_the_sea", "海洋之心"},
            {"minecraft:totem_of_undying", "不死图腾"},
            {"minecraft:elytra", "鞘翅"},
            {"minecraft:nether_star", "下界之星"},
            {"minecraft:dragon_egg", "龙蛋"},
    };

    /** 兑换比下限：设计口径，不许低于（可以更高）。 */
    private static final Map<String, Integer> FLOORS = Map.of(
            "minecraft:diamond", 300,
            "minecraft:netherite_scrap", 500,
            "minecraft:elytra", 3000,
            "minecraft:nether_star", 3000);

    /** 常见资源：彼此之间不该出现「10 倍价差」（这里按 4 倍卡，比口径更严一点）。 */
    private static final String[] COMMON = {
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_log",
            "minecraft:sand", "minecraft:gravel", "minecraft:clay_ball",
    };

    /** 分档区间（与 core.json 的 tiers 文档一致）：{档名, 下限, 上限, 代表物品}。 */
    private static final Object[][] TIERS = {
            {"随手可得", 1, 1, new String[]{"minecraft:dirt", "minecraft:cobblestone"}},
            {"常见劳动产出", 1, 4, new String[]{"minecraft:oak_log", "minecraft:coal",
                    "minecraft:white_wool", "minecraft:leather"}},
            {"常见矿物", 5, 30, new String[]{"minecraft:copper_ingot", "minecraft:iron_ingot",
                    "minecraft:gold_ingot", "minecraft:redstone", "minecraft:amethyst_shard",
                    // 「可再生」的那一批也住在这一档（上限 30，见 renewableItemsStayCheap）
                    "minecraft:obsidian", "minecraft:emerald", "minecraft:sponge",
                    "minecraft:nautilus_shell", "minecraft:scute"}},
            {"稀有", 150, 800, new String[]{"minecraft:diamond", "minecraft:netherite_scrap",
                    "minecraft:blaze_rod", "minecraft:ender_pearl", "minecraft:shulker_shell",
                    "minecraft:ghast_tear"}},
            {"极稀有", 800, 3000, new String[]{"minecraft:echo_shard", "minecraft:heart_of_the_sea",
                    "minecraft:wither_skeleton_skull", "minecraft:trident",
                    "minecraft:totem_of_undying"}},
            {"传奇", 3000, 10000, new String[]{"minecraft:elytra", "minecraft:nether_star",
                    "minecraft:enchanted_golden_apple", "minecraft:dragon_egg"}},
    };

    /**
     * **已知可再生物品**（能用水+岩浆 / 村民交易 / 刷怪塔 / 农场 / 骨粉无限复制的）。
     *
     * <p>通用规则：这一档一律按材料价定价，**估价 ≤ 30**，不许进 T4「稀有」以上。
     * 每条后面都跟着「凭什么可再生」，加新条目时照着写清楚。
     */
    private static final String[][] RENEWABLE = {
            {"minecraft:obsidian", "水+岩浆，零门槛，随便搭个模子就能刷"},
            {"minecraft:crying_obsidian", "同上；猪灵以物易物也能换"},
            {"minecraft:emerald", "村民交易（24 张纸 ↔ 1 颗），配个甘蔗农场就是无限"},
            {"minecraft:sponge", "远古守卫者掉落（海洋无限、守卫者农场可刷）；湿海绵烧干即得"},
            {"minecraft:scute", "溺尸掉落 + 海龟农场（喂海草催熟）"},
            {"minecraft:nautilus_shell", "溺尸掉落 + 钓鱼，溺尸塔可刷"},
            {"minecraft:rabbit_foot", "兔子农场（10% 掉落）"},
            {"minecraft:phantom_membrane", "幻翼农场（不睡觉就能持续刷）"},
            {"minecraft:gunpowder", "苦力怕刷怪塔"},
            {"minecraft:slime_ball", "史莱姆区块 / 沼泽农场"},
            {"minecraft:bone", "骷髅刷怪塔"},
            {"minecraft:string", "蜘蛛刷怪塔"},
            {"minecraft:feather", "鸡场"},
            {"minecraft:rotten_flesh", "僵尸刷怪塔"},
            {"minecraft:leather", "牛场"},
            {"minecraft:white_wool", "羊场（剪羊毛）"},
            {"minecraft:honeycomb", "蜂箱农场"},
            {"minecraft:ink_sac", "鱿鱼农场"},
            {"minecraft:glow_ink_sac", "发光鱿鱼农场"},
            {"minecraft:nether_wart", "下界疣农场（灵魂沙 + 任意维度）"},
            {"minecraft:magma_cream", "岩浆怪农场（或史莱姆球 + 烈焰粉合成）"},
            {"minecraft:coal", "木炭（烧原木）+ 凋灵骷髅农场，砍树就能无限"},
            {"minecraft:iron_ingot", "铁傀儡农场 / 村民交易"},
            {"minecraft:gold_ingot", "僵尸猪灵农场（下界）"},
            {"minecraft:copper_ingot", "溺尸掉落 + 铜矿（大型矿脉）"},
            {"minecraft:redstone", "女巫掉落 + 村民交易"},
            {"minecraft:lapis_lazuli", "村民交易 + 女巫掉落"},
            {"minecraft:quartz", "猪灵以物易物"},
            {"minecraft:amethyst_shard", "紫水晶洞（会自己长回来）"},
            {"minecraft:glowstone_dust", "女巫掉落 + 下界萤石（可再生农场）"},
            {"minecraft:prismarine_shard", "守卫者农场"},
            {"minecraft:prismarine_crystals", "守卫者农场"},
            {"minecraft:oak_log", "树场（骨粉催熟）"},
            {"minecraft:flint", "沙砾（猪灵以物易物换沙砾）"},
            {"minecraft:sugar_cane", "甘蔗农场"},
            {"minecraft:bamboo", "竹林 / 骨粉农场"},
            {"minecraft:kelp", "海带农场"},
            {"minecraft:ice", "冰场（霜行者 / 雪层）"},
            {"minecraft:snowball", "雪傀儡 / 下雪"},
            {"minecraft:clay_ball", "黏土（石匠交易）+ 繁茂洞穴"},
            // ---------- 农作物与相关产物（2026-10-21 补价）----------
            {"minecraft:wheat", "小麦农场（骨粉催熟）"},
            {"minecraft:carrot", "胡萝卜农场（可无限种）"},
            {"minecraft:potato", "马铃薯农场"},
            {"minecraft:beetroot", "甜菜根农场"},
            {"minecraft:melon_slice", "西瓜农场（1 西瓜 → 9 片，循环种植）"},
            {"minecraft:pumpkin", "南瓜农场（1 南瓜 → 4 种子，种子还能再种）"},
            {"minecraft:cocoa_beans", "可可豆农场（丛林原木，骨粉催熟）"},
            {"minecraft:sweet_berries", "甜浆果丛（骨粉催熟）"},
            {"minecraft:glow_berries", "发光浆果藤（繁茂洞穴，可无限采）"},
            {"minecraft:sea_pickle", "海泡菜（海底，骨粉催熟分株）"},
            {"minecraft:red_mushroom", "蘑菇农场（暗室 + 骨粉）"},
            {"minecraft:brown_mushroom", "蘑菇农场（暗室 + 骨粉）"},
            {"minecraft:chorus_fruit", "紫颂果（末地作物，可循环种植）"},
            {"minecraft:honey_bottle", "蜂箱采集（蜜蜂可无限产蜜）"},
            {"minecraft:milk_bucket", "挤牛奶（牛可无限产奶）"},
    };

    /**
     * **结构 / BOSS 限定**（要先把稀有结构拿下来、或者要打 BOSS 才谈得上刷）。
     *
     * <p>口径：**门槛比「理论可再生」重要** —— 烈焰人刷怪笼只在要塞里、末影人要跑到末地、
     * 潜影贝只在末地城、凋灵骷髅只在要塞、潮涌核心要打远古守卫者……这些按门槛定价，
     * 所以它们（以及完全不可再生的钻石 / 合金 / 鞘翅 / 下界之星）**必须留在高档**：断言估价 ≥ 150。
     */
    private static final String[][] GATED = {
            {"minecraft:diamond", "矿石，不可再生"},
            {"minecraft:ancient_debris", "矿石，不可再生"},
            {"minecraft:netherite_scrap", "由远古残骸熔炼，不可再生"},
            {"minecraft:elytra", "末地船限定，一个存档就那几件"},
            {"minecraft:nether_star", "要打凋灵（还得先刷三个头）"},
            {"minecraft:blaze_rod", "要塞烈焰人刷怪笼限定"},
            {"minecraft:ender_pearl", "末地限定（末影人农场要先通关）"},
            {"minecraft:shulker_shell", "末地城潜影贝，终局内容"},
            {"minecraft:ghast_tear", "下界恶魂（掉落率低、刷起来很费劲）"},
            {"minecraft:wither_skeleton_skull", "要塞凋灵骷髅，掉落率 2.5%"},
            {"minecraft:trident", "溺尸 8.5% 掉落，且要刷海量溺尸"},
            {"minecraft:totem_of_undying", "唤魔者（袭击 / 林地府邸）"},
            {"minecraft:dragon_breath", "要打完末影龙"},
            {"minecraft:heart_of_the_sea", "埋藏的宝藏（探索限定）"},
            {"minecraft:echo_shard", "远古城市战利品（探索限定）"},
            {"minecraft:enchanted_golden_apple", "战利品限定，1.21 没有配方"},
            {"minecraft:dragon_egg", "打完末影龙才给一个"},
            {"minecraft:dragon_head", "末地船战利品"},
            {"minecraft:netherite_upgrade_smithing_template", "堡垒遗迹战利品"},
    };

    /**
     * 「换 1 个 X 需要多少个泥土」——本次要求的核心指标。顺便把对照表打印出来
     * （gradle 测试报告里能看到，汇报直接引用）。
     */
    @Test
    void exchangeTableIsAnchoredToRarity() {
        ValueTableFixture table = ValueTableFixture.load();
        assertEquals(1, table.value(BASELINE), "泥土必须是 1 金币 —— 整个「多少个」的口径都由它定");

        System.out.println("=== 换 1 个 X 需要多少个泥土（泥土回收价 1 金币）===");
        Map<String, Integer> costs = new LinkedHashMap<>();
        for (String[] row : REPRESENTATIVE) {
            String id = row[0];
            int value = table.value(id);
            int cost = table.purchasePrice(id);
            costs.put(id, cost);
            System.out.printf("  %-10s %-14s 估值 %5d 金币 → 需要 %6d 个泥土%n",
                    row[1], id.replace("minecraft:", ""), value, cost);
        }

        // ① 四条设计下限（不许低于给定量级）
        for (Map.Entry<String, Integer> floor : FLOORS.entrySet()) {
            int cost = costs.get(floor.getKey());
            assertTrue(cost >= floor.getValue(),
                    floor.getKey() + " 只需要 " + cost + " 个泥土，低于下限 " + floor.getValue()
                            + "：稀有资源太便宜，常见资源就能换");
        }

        // ② 常见资源之间不许有 10 倍价差（这里按 4 倍卡）
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (String id : COMMON) {
            int value = table.value(id);
            assertTrue(value > 0, id + " 是「最廉价的一档」，必须有价");
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertTrue(max <= min * 4, "常见资源价差过大（" + min + " ~ " + max + "）："
                + "木头/圆石/泥土这一档应该都在 1~4 之间");

        // ③ 稀有度必须拉开数量级：钻石 / 铁 ≥ 20、鞘翅 / 钻石 ≥ 5、下界之星 / 钻石 ≥ 5
        assertTrue(table.value("minecraft:diamond") >= 20 * table.value("minecraft:iron_ingot"),
                "钻石相对铁的倍率太低（" + table.value("minecraft:diamond") + " vs "
                        + table.value("minecraft:iron_ingot") + "）");
        assertTrue(costs.get("minecraft:elytra") >= 5 * costs.get("minecraft:diamond"),
                "鞘翅相对钻石的倍率太低");
        assertTrue(costs.get("minecraft:nether_star") >= 5 * costs.get("minecraft:diamond"),
                "下界之星相对钻石的倍率太低");
        assertTrue(costs.get("minecraft:oak_log") >= 2 * costs.get(BASELINE),
                "原木应当明显贵于泥土（它受「1 原木 → 4 木板」约束，见 core.json 的说明）");
    }

    /** 每一档的代表物品都必须落在该档的区间里（防止以后再改表时档位串味）。 */
    @Test
    void everyTierKeepsItsRange() {
        ValueTableFixture table = ValueTableFixture.load();
        for (Object[] tier : TIERS) {
            String name = (String) tier[0];
            int low = (Integer) tier[1];
            int high = (Integer) tier[2];
            for (String id : (String[]) tier[3]) {
                int value = table.value(id);
                assertTrue(value >= low && value <= high,
                        name + " 档的 " + id + " 估值 " + value + " 落在区间 [" + low + ", " + high + "] 之外");
            }
        }
        // 档与档之间要有落差：上一档的上限 < 下一档的下限（相邻两档不许重叠）
        for (int i = 1; i < TIERS.length; i++) {
            int previousHigh = (Integer) TIERS[i - 1][2];
            int low = (Integer) TIERS[i][1];
            assertTrue(low >= previousHigh,
                    TIERS[i][0] + " 的下限 " + low + " 低于上一档的上限 " + previousHigh + "：档位重叠了");
        }
    }

    /**
     * **种子之间的套利检查**（推导出来的物品由 {@link ItemValuator} 保证，见
     * {@code ItemValuatorTest#derivedValueNeverExceedsIngredientCost}）。
     *
     * <p>这里专门盯「原料与产物**都有种子**」的那几条原版配方 —— 推导管不到它们，
     * 但玩家完全可以在采购页买原料、合成、再回收：
     * <pre>
     *   买齐原料（花 Σ 采购价）→ 合成 → 回收拿 N × value(产物)
     *   只要回收 &gt; Σ 采购价 就是印钞机
     * </pre>
     * 这条检查抓过两个真实漏洞：粗矿/锭熔炼（旧表 raw_iron 2 / iron_ingot 6，买粗矿熔炼再卖净赚）
     * 与羊毛/线（旧表 wool 2、1 羊毛 → 4 线，买羊毛拆线净赚）。
     *
     * <p>产物**没有**种子（纯靠推导）的配方直接跳过：那种情况由估值算法本身保证不套利。
     */
    @Test
    void seededRecipesCannotBeArbitraged() {
        ValueTableFixture table = ValueTableFixture.load();
        // {产物, 产出数量, 原料…, 每种原料的数量}（"熔炼"也算一条配方：1 粗矿 → 1 锭）
        Object[][] recipes = {
                {"minecraft:oak_planks", 4, new Object[]{"minecraft:oak_log", 1}},
                {"minecraft:string", 4, new Object[]{"minecraft:white_wool", 1}},
                {"minecraft:blaze_powder", 2, new Object[]{"minecraft:blaze_rod", 1}},
                {"minecraft:magma_cream", 1,
                        new Object[]{"minecraft:slime_ball", 1, "minecraft:blaze_powder", 1}},
                {"minecraft:iron_ingot", 1, new Object[]{"minecraft:raw_iron", 1}},
                {"minecraft:copper_ingot", 1, new Object[]{"minecraft:raw_copper", 1}},
                {"minecraft:gold_ingot", 1, new Object[]{"minecraft:raw_gold", 1}},
                {"minecraft:netherite_scrap", 1, new Object[]{"minecraft:ancient_debris", 1}},
                {"minecraft:coal_block", 1, new Object[]{"minecraft:coal", 9}},
                {"minecraft:iron_block", 1, new Object[]{"minecraft:iron_ingot", 9}},
                {"minecraft:diamond_block", 1, new Object[]{"minecraft:diamond", 9}},
                {"minecraft:netherite_ingot", 1,
                        new Object[]{"minecraft:netherite_scrap", 4, "minecraft:gold_ingot", 4}},
                // ---------- 农作物（2026-10-21 补价时逐条验算的那 8 条）----------
                // 南瓜 → 4 南瓜种子：南瓜 4 必须 ≥ 4 × 种子价 1，否则「买南瓜拆种子再卖」白赚
                {"minecraft:pumpkin_seeds", 4, new Object[]{"minecraft:pumpkin", 1}},
                // 1:1 的三条（小麦 / 甜菜根 / 西瓜片 → 对应的种子）
                {"minecraft:wheat_seeds", 1, new Object[]{"minecraft:wheat", 1}},
                {"minecraft:beetroot_seeds", 1, new Object[]{"minecraft:beetroot", 1}},
                {"minecraft:melon_seeds", 1, new Object[]{"minecraft:melon_slice", 1}},
                // 1 西瓜 → 9 片（反向：9 片能拼回 1 个西瓜，所以西瓜只能由 9 片推导）
                {"minecraft:melon", 1, new Object[]{"minecraft:melon_slice", 9}},
                // 熔炼 1:1（海带 → 干海带）
                {"minecraft:dried_kelp", 1, new Object[]{"minecraft:kelp", 1}},
                // 4 蘑菇 → 1 蘑菇煲（碗的推导值本来就是 0，不列原料）
                {"minecraft:mushroom_stew", 1,
                        new Object[]{"minecraft:red_mushroom", 1, "minecraft:brown_mushroom", 1}},
                // 4 蜂蜜瓶 → 1 蜂蜜块（新增蜂蜜瓶价格之后最容易被做坏的一对）
                {"minecraft:honey_block", 1, new Object[]{"minecraft:honey_bottle", 4}},
        };
        int checked = 0;
        for (Object[] recipe : recipes) {
            String product = (String) recipe[0];
            int outputCount = (Integer) recipe[1];
            Object[] flat = (Object[]) recipe[2];
            int productValue = table.value(product);
            if (productValue <= 0) {
                continue;   // 纯推导产物：由 ItemValuator 的不变量负责
            }
            long spend = 0;
            boolean allPriced = true;
            for (int i = 0; i < flat.length; i += 2) {
                String ingredient = (String) flat[i];
                int count = (Integer) flat[i + 1];
                int unit = table.purchasePrice(ingredient);
                if (unit <= 0) {
                    allPriced = false;      // 有买不到的原料 → 构不成套利路径
                    break;
                }
                spend += (long) unit * count;
            }
            if (!allPriced) {
                continue;
            }
            long earn = (long) outputCount * productValue;
            assertTrue(earn <= spend, "买原料做 " + product + " 再回收能白赚：成本 " + spend
                    + "、回收 " + earn + "（赚 " + (earn - spend) + "）");
            checked++;
        }
        assertTrue(checked >= 5, "至少该检查到 5 条「两头都有种子」的配方，实际 " + checked);
    }

    /** 表的文档块（分档 + 锚点理由）必须还在，而且是给人看的完整文字。 */
    @Test
    void tierDocumentationIsPresent() {
        ValueTableFixture table = ValueTableFixture.load();
        JsonObject tiers = table.root().getAsJsonObject("tiers");
        assertTrue(tiers != null && tiers.size() >= TIERS.length,
                "core.json 里必须保留 tiers 文档块（每档的锚点理由）");
        for (Object[] tier : TIERS) {
            String name = (String) tier[0];
            assertFalse(tiers.entrySet().stream().noneMatch(e -> e.getKey().contains(name)),
                    "tiers 文档里缺少「" + name + "」这一档的说明");
        }
        // 「为什么这么定」的说明（木板/原木、粗矿与锭、羊毛、绿宝石）也要留着
        assertTrue(tiers.size() > TIERS.length, "tiers 里应当还有几条「为什么这么定」的说明");
        // 通用规则本身必须写在文档块里（可再生 ⇒ ≤30、不许进 T4）
        assertTrue(tiers.entrySet().stream().anyMatch(e -> e.getKey().contains("可再生")
                        && e.getValue().toString().contains("30")),
                "tiers 文档块里必须有『可再生的一律围在 30 以内』这条通用规则");
    }

    /**
     * **通用规则：凡可再生（能无限刷）的物品，估价必须 ≤ 30，不许进「稀有」档。**
     *
     * <p>理由：价格一旦高过刷它的成本，基建就变成印钞机 —— 黑曜石（水+岩浆）曾经 150、
     * 绿宝石（村民交易）曾经 200（24 张纸的机会成本才 24 金币，一趟净赚近百）。
     * 清单在 {@link #RENEWABLE}，每条都写着「凭什么可再生」。
     */
    @Test
    void renewableItemsStayCheap() {
        ValueTableFixture table = ValueTableFixture.load();
        System.out.println("=== 可再生条目（通用规则：≤30 金币）===");
        for (String[] row : RENEWABLE) {
            String id = row[0];
            String why = row[1];
            int value = table.value(id);
            System.out.printf("  %-24s %3d 金币   ← %s%n", id.replace("minecraft:", ""), value, why);
            assertTrue(value > 0, id + " 是可再生的常见资源，不该是 0（卖不掉）");
            assertTrue(value <= 30, id + " 估价 " + value + " 超过 30：它是可再生的（" + why
                    + "），按通用规则必须压到材料价，否则刷它就成了印钞机");
        }
    }

    /**
     * **不可再生 / 高门槛的东西必须留在高档**：断言 ≥ 150。
     *
     * <p>口径见 {@link #GATED} 的注释 —— 「门槛比理论可再生重要」。这条和
     * {@link #exchangeTableIsAnchoredToRarity} 的四条兑换比下限一起，防止「一刀切压价」
     * 把宝石、合金、鞘翅也压成材料价。
     */
    @Test
    void gatedItemsStayValuable() {
        ValueTableFixture table = ValueTableFixture.load();
        System.out.println("=== 结构 / BOSS 限定（≥150 金币）===");
        for (String[] row : GATED) {
            String id = row[0];
            String why = row[1];
            int value = table.value(id);
            System.out.printf("  %-40s %5d 金币   ← %s%n", id.replace("minecraft:", ""), value, why);
            assertTrue(value >= 150, id + " 估价只有 " + value + "（" + why
                    + "）：这类东西按获取门槛定价，不该被压到材料价");
        }
        // 四条硬下限（与 exchangeTableIsAnchoredToRarity 同一口径，这里再钉一次「代表物」）
        assertTrue(table.value("minecraft:diamond") >= 150, "钻石必须留在高档");
        assertTrue(table.value("minecraft:netherite_scrap") >= 150, "下界合金碎片必须留在高档");
        assertTrue(table.value("minecraft:elytra") >= 150, "鞘翅必须留在高档");
        assertTrue(table.value("minecraft:nether_star") >= 150, "下界之星必须留在高档");
        // 两个清单不许重叠（同一个物品不可能既可无限刷又要门槛）
        for (String[] renewable : RENEWABLE) {
            for (String[] gated : GATED) {
                assertFalse(renewable[0].equals(gated[0]),
                        renewable[0] + " 同时出现在「可再生」与「结构/BOSS 限定」两个清单里，先想清楚它算哪边");
            }
        }
    }
}
