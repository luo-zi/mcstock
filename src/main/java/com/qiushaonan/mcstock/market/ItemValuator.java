package com.qiushaonan.mcstock.market;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 物品估值表：把「一件物品值多少金币」算出来，供回收页定价与采购页标价使用。
 *
 * <p><b>纯 Java</b>（只借了 {@link ResourceLocation} 当物品 id），单测直接调，
 * 真正的配方解析在 {@link MinecraftRecipeGraph}、数据包读取在 {@link ItemValueRules}。
 *
 * <h2>估值 = max(配方推导, 地板)</h2>
 *
 * <p>这套口径照抄 Upgrader + <i>Upgrader: ATM10 Balance</i>（见 AGENTS.md 记载的取舍）：
 * 价格**主要靠真实配方推**，数据包只负责给「推不准的关键物品」补一个**地板**。
 *
 * <ol>
 *   <li><b>配方推导</b>（主力）：按
 *       {@code floor(Σ(输入候选中最低估值 × 数量) / 产出数量)} 算，同一产物的所有配方取 min，
 *       反复松弛到不动点（最多 {@link #MAX_PASSES} 轮）；</li>
 *   <li><b>地板</b>（补丁）：数据包里写的
 *       <b>物品种子</b>（{@code "minecraft:diamond": 40}）与
 *       <b>标签种子</b>（{@code "#minecraft:logs": 4}，命中多个标签取最大）
 *       就是地板 —— 推导值低于它才抬上去，高于它就听推导的；</li>
 *   <li>推导不出、又没地板 = <b>0</b>：既不可回收也不可购买。
 *       <b>没有稀有度兜底</b>（这是刻意的：猜出来的价一定错，错的价格比没有价格更糟）。</li>
 * </ol>
 *
 * <h2>地板 ≠ 锚（这条是整套改动的关键，别改回去）</h2>
 * <ul>
 *   <li><b>锚</b>（旧语义）：种子说多少就是多少，<b>屏蔽</b>配方推导。
 *       后果是种子不往下游传播，一个「手写价的锭」做成的块、合金、装备全都还是按原料价卖；</li>
 *   <li><b>地板</b>（现在的语义）：推导照跑，地板只在下面托一下，
 *       并且因为<b>所有物品都参与推导</b>，地板会顺着配方图往下游<b>传播</b> ——
 *       锭有地板 → 块 / 合金 / 装备全都跟着起来。ATM10 那种「Allthemodium 锭没有配方」
 *       的情况正是靠这条救回来的。</li>
 * </ul>
 *
 * <h2>两条防印钞机的规矩（别删）</h2>
 * <ul>
 *   <li><b>输入全为 0 的配方本轮跳过</b>：那只是「上游还没算出来」，不是「原料不要钱」。
 *       当成 0 成本的话，第一次遍历就会把所有东西估成 0。</li>
 *   <li><b>推导值 &lt; 1 一律记 0</b>：{@code 9 铁粒 ↔ 1 铁锭}、{@code 2 木板 → 4 木棍}
 *       这类配方算出来是 0.x，四舍五入成 1 的话「买原料 → 合成 → 回收」就成了取之不尽的印钞机。
 *       注意 min 是**连 0 一起取**的：某条配方算出 0 就说明「这条路能把东西做得比 1 金币还便宜」，
 *       产物的估值必须跟着塌到 0，否则同样能套利。</li>
 * </ul>
 *
 * <h2>回收价与采购价</h2>
 * <ul>
 *   <li><b>回收价</b> = {@link #value} = 上面那套「max(推导, 地板)」。
 *       它是整个经济的基准，<b>地板只会把它抬高</b>，绝不压低；</li>
 *   <li><b>采购价</b> = {@link #purchasePrice} = {@code ceil(回收价 × 1.18)}。
 *       1.18 这一档买卖价差是「买原料 → 合成 → 回收」永远亏的唯一保证，别在别处再加摩擦。</li>
 * </ul>
 *
 * <p>⚠️ <b>地板是这套模型唯一的风险面</b>：地板高于「这条配方的材料成本」时，
 * 「买原料 → 合成 → 回收」就变成印钞机。所以每次 {@link #rebuild} 都会扫一遍
 * （{@link #countFloorConflicts}），发现就报 WARN，账目见 {@link #conflictCount()}。
 *
 * <h2>缓存</h2>
 * 整张表放在一个 {@code volatile} 的不可变 map 里，{@link #rebuild} 一次性换掉引用 ——
 * 估值线程（服务端 tick、GUI 查询、别的模组的兼容代码）读到的永远是完整的一版，
 * 不会看到 rebuilt 到一半的表；没调过 {@link #rebuild} 时 {@link #value} 安全返回 0。
 *
 * <p>同一个 {@link #rebuild} 还会顺手记一份**来源表**（{@link Origin}），
 * 只给调试命令 {@code /mcstock value} 回答「这个价是种子写的、标签给的，还是配方推出来的」，
 * **不参与任何定价计算**（{@link #value} / {@link #purchasePrice} 的算法一个字都没动）。
 */
public final class ItemValuator {

    /**
     * 采购价倍率：回收价 = 估价，购买价 = {@code ceil(估价 × 1.18)}。
     *
     * <p>⚠️ 历史：1.15（原版）→ 1.25（2026-10-06 第一次收紧）→ **1.18（同日回调：
 * 玩家反馈「赚钱太难」，买卖价差从 25% 降到 18%）**：
     * 这是「把投机赚到的钱换回物资」唯一的出口，收窄之后「买 → 卖」往返要亏 20%；
     * 而**回收价一个字没动**（估价 × 1.0）—— 回收那条路必须保持公平。
     * 25% 的差价是回收 / 购买的**唯一**摩擦，别在别处再加一遍，否则「采购 → 回收」两头堵。
     */
    public static final double PURCHASE_MARKUP = 1.18;

    /** 松弛轮数上限。正常世界里配方链最多五六层，20 轮足够，同时也是防死循环的保险丝。 */
    private static final int MAX_PASSES = 20;

    private static final Map<ResourceLocation, Integer> EMPTY_TABLE = Map.of();

    /** 只用来汇报「跳过了几条坏条目」——本类不碰游戏状态，日志也只写诊断信息。 */
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** 当前估值表（只放 &gt; 0 的项：查不到 = 0 = 既不可回收也不可购买）。 */
    private static volatile Map<ResourceLocation, Integer> values = EMPTY_TABLE;

    /**
     * 当前估值表的**来源表**（{@link Origin}）：与 {@link #values} 在同一次 {@link #rebuild}
     * 里一起换掉。同样是不可变快照，同样只读。
     */
    private static volatile Map<ResourceLocation, Origin> origins = Map.of();

    /**
     * **纯推导值**（不含地板），与 {@link #values} 同一次 {@link #rebuild} 里换掉。
     *
     * <p>只给诊断用（{@code /mcstock audit} 与 {@code /mcstock value}）：整合包作者要能看出
     * 一个价是「配方推出来的」还是「地板托起来的」，否则改地板就是盲调。
     * **不参与任何结算**。
     */
    private static volatile Map<ResourceLocation, Integer> derivedTable = Map.of();

    /** 上一次重建扫出多少条「地板冲突」（地板高于材料成本 = 潜在印钞机）。只给诊断用。 */
    private static volatile int conflictsSeen = 0;

    private ItemValuator() {
    }

    /**
     * 重算整张估值表。服务端启动完、数据包 reload 之后各调一次
     * （本模组在 {@code StockEvents} 里接的 {@code ServerStartedEvent} / 登录事件）。
     *
     * <p>本方法是幂等的：同样的输入重算多少遍都是同一张表，不依赖上一次的结果。
     *
     * @param itemSeeds  物品种子（物品 id → 金币），**语义是地板**：只在推导值更低时托起来。
     *                   值 ≤ 0 表示另一件事 —— **拉黑**：这件物品既不算也不收，
     *                   <b>连同它的配方推导一起废掉</b>（地板为 0 只是「没写地板」，
     *                   拉黑才是「这东西不许有价」，两者别混）
     * @param tagSeeds   标签种子（标签 id → 金币），同样是地板；≤ 0 表示这条标签不生效
     * @param tagMembers 标签展开表（标签 id → 成员物品），键要和 {@code tagSeeds} 对得上
     * @param allItems   物品全集（一般是 {@code BuiltInRegistries.ITEM.keySet()}）：
     *                   只有在这个集合里的物品才会被推导，集合外的物品一律 0
     * @param graph      配方图
     */
    public static void rebuild(Map<ResourceLocation, Integer> itemSeeds,
                               Map<ResourceLocation, Integer> tagSeeds,
                               Map<ResourceLocation, Set<ResourceLocation>> tagMembers,
                               Set<ResourceLocation> allItems,
                               RecipeGraph graph) {
        // ⚠️ 加固（2026-10-06）：**任何一条坏条目都不许让整张表建不起来** ——
        //    这是数据包作者/整合包模组能塞进来的东西（坏 id、坏标签、抛异常的配方图），
        //    而调用方在 ServerStarted 上：抛出去会把整个服务端初始化打断。
        //    口径：坏条目跳过 + 计数，最后汇总一条日志；能算的照算。
        int skipped = 0;
        // 撞到单件上限的物品数（见 MAX_VALUE_COINS）：> 0 说明数据包里有环路
        int clamped = 0;
        // 写了「但这套环境里不存在」的标签地板（见下面 tagSeeds 循环）
        List<String> unresolved = new ArrayList<>();
        // 成功落到物品上的标签地板条数 —— 用来区分「模组没装（全都落空）」和「标签名写错（个别落空）」
        int resolvedTags = 0;
        Map<ResourceLocation, Integer> table = new HashMap<>();
        // 数据包写的地板（物品 id → 金币）。**地板不是锚**：它不屏蔽配方推导，
        // 只在推导值更低的时候把它托起来。见类注释「地板 ≠ 锚」一节。
        Map<ResourceLocation, Integer> floors = new HashMap<>();
        // 显式拉黑（{@code "x": 0}）：不算、不收、不推导。给整合包作者留的「关掉某件物品」的出口
        Set<ResourceLocation> blocked = new HashSet<>();
        // 来源表：只记录「怎么来的」，与 table 的取值完全无关（table 只留 > 0 的项）
        Map<ResourceLocation, Origin> originTable = new HashMap<>();

        if (itemSeeds != null) {
            for (Map.Entry<ResourceLocation, Integer> seed : itemSeeds.entrySet()) {
                try {
                    ResourceLocation item = seed.getKey();
                    if (item == null) {
                        skipped++;
                        continue;
                    }
                    Integer value = seed.getValue();
                    int declared = value == null ? 0 : value;
                    if (declared > 0) {
                        floors.put(item, declared);
                    } else {
                        blocked.add(item);   // ≤ 0 = 拉黑，见方法注释
                    }
                    originTable.put(item, new Origin(Source.ITEM_SEED, null, declared));
                } catch (RuntimeException e) {
                    skipped++;   // 坏 key（比如自定义 Map 实现里取 key 就抛）：跳过这一条
                }
            }
        }

        if (tagSeeds != null) {
            for (Map.Entry<ResourceLocation, Integer> seed : tagSeeds.entrySet()) {
                try {
                    Integer value = seed.getValue();
                    if (value == null || value <= 0 || tagMembers == null) {
                        continue;
                    }
                    Set<ResourceLocation> members = tagMembers.get(seed.getKey());
                    if (members == null || members.isEmpty()) {
                        // 标签在这套环境里不存在（模组没装 / 标签名写错）：这条地板**静默无效**。
                        // 记下来，最后按「全都落空」还是「个别落空」决定报 WARN 还是 DEBUG
                        unresolved.add("#" + seed.getKey());
                        continue;
                    }
                    resolvedTags++;
                    for (ResourceLocation member : members) {
                        if (member == null || blocked.contains(member)) {
                            continue;   // 被物品种子拉黑的，标签地板也别想复活它
                        }
                        Integer old = floors.get(member);
                        if (old == null || old < value) {
                            floors.put(member, value); // 命中多个标签取最大
                            originTable.put(member, new Origin(Source.TAG_SEED, seed.getKey(), value));
                        }
                    }
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        }

        // 地板是**起点**：推导只会把值往上顶（见下面的不动点），不会压下来
        table.putAll(floors);

        // ⚠️ 地板写了一个**这个整合包里不存在**的物品 id（拼错 / 模组没装 / 换了分支）时，
        //    它会静默不生效 —— 而作者很可能以为「我明明写了地板，为什么还是 0」。
        //    这里对一次账并报出来。只在 allItems 可用时做（纯 JVM 单测里传的是小集合）。
        if (allItems != null && !floors.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (ResourceLocation item : new TreeSet<>(floors.keySet())) {
                if (!allItems.contains(item)) {
                    unknown.add(item.toString());
                }
            }
            if (!unknown.isEmpty()) {
                LOGGER.warn("[mcstock] 有 {} 条地板指向整合包里不存在的物品（拼错或模组没装，这些条目不会生效）：{}{}",
                        unknown.size(), String.join(", ", unknown.subList(0, Math.min(5, unknown.size()))),
                        unknown.size() > 5 ? " …" : "");
            }
        }
        if (!unresolved.isEmpty()) {
            // 「一条都没落上」= 这些标签所属的模组根本没装（本模组自带的 ATM10 地板表在普通存档里
            // 就是这种情况）→ 正常现象，别刷 WARN；「有的落上有的没落」= 标签名写错了 → WARN
            if (resolvedTags > 0) {
                LOGGER.warn("[mcstock] 有 {} 条标签地板展开不出任何物品（标签名写错或模组没装）：{}{}",
                        unresolved.size(), String.join(", ", unresolved.subList(0, Math.min(5, unresolved.size()))),
                        unresolved.size() > 5 ? " …" : "");
            } else {
                LOGGER.debug("[mcstock] 本次 {} 条标签地板全部落空（对应模组未安装），已忽略", unresolved.size());
            }
        }

        // 所有物品都参与推导 —— 有地板的也参与。这是「地板 vs 锚」的分水岭：
        //    锚是「我说了算，别推」，地板是「推出来更高就用推出来的」。
        //    正是这条让地板能**向下游传播**：锭有地板 → 块/合金/装备全都跟着起来。
        //    （被拉黑的除外：它们连推导都不进，见 blocked。）
        List<ResourceLocation> sweep = new ArrayList<>();
        if (allItems != null) {
            for (ResourceLocation item : new TreeSet<>(allItems)) {
                if (item != null && !blocked.contains(item)) {
                    sweep.add(item);
                }
            }
        }

        // 只对「真的有配方」的物品做松弛，省掉大部分空转
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = new HashMap<>();
        if (graph != null) {
            for (ResourceLocation item : sweep) {
                try {
                    List<RecipeGraph.RecipeEntry> found = graph.recipesFor(item);
                    if (found != null && !found.isEmpty()) {
                        recipes.put(item, found);
                    }
                } catch (Throwable e) {
                    // 某个模组的 RecipeGraph 实现对这一条物品抛了异常：跳过它，别的物品照推
                    skipped++;
                    if (skipped <= 5) {
                        LOGGER.warn("[mcstock] 配方图查询 {} 失败，已跳过（该物品按无配方处理）", item, e);
                    }
                }
            }
        }
        List<ResourceLocation> targets = new ArrayList<>(recipes.keySet());
        targets.sort(null);
        // 只要「参与过配方推导」就算 DERIVED —— 哪怕这一轮推出来是 0
        //（输入还全是 0，或者算出来不足 1 金币），调试时正是这两种情况最需要被认出来
        for (ResourceLocation item : targets) {
            originTable.putIfAbsent(item, new Origin(Source.DERIVED, null, 0));
        }

        for (int pass = 0; pass < MAX_PASSES && !targets.isEmpty(); pass++) {
            boolean changed = false;
            for (ResourceLocation item : targets) {
                int candidate;
                try {
                    candidate = derive(recipes.get(item), table);
                } catch (Throwable e) {
                    skipped++;      // 这一条推不出来：本轮按 0（不可回收）处理，别把整张表带崩
                    candidate = 0;
                }
                // 地板在这里生效：推导值和地板取大的那个。地板为 0（没写）时就是纯推导值。
                int next = Math.max(candidate, floors.getOrDefault(item, 0));
                if (next > MAX_VALUE_COINS) {
                    // 撞上限 = 数据包里有环路（见 MAX_VALUE_COINS）。截断并计数，最后汇总报出来
                    next = MAX_VALUE_COINS;
                    clamped++;
                }
                Integer old = table.get(item);
                int current = old == null ? 0 : old;
                if (next == current) {
                    continue;
                }
                if (next > 0) {
                    table.put(item, next);
                } else {
                    table.remove(item);
                }
                changed = true;
            }
            if (!changed) {
                break; // 到不动点了
            }
        }

        // 纯推导值（不含地板）单独留一份：`/mcstock audit` 要能回答
        // 「这个价是推导出来的，还是地板托起来的」—— 这是整合包作者写地板的唯一依据
        Map<ResourceLocation, Integer> derivedOnly = new HashMap<>();
        for (ResourceLocation item : targets) {
            try {
                int derivedValue = derive(recipes.get(item), table);
                if (derivedValue > 0) {
                    derivedOnly.put(item, derivedValue);
                }
            } catch (Throwable e) {
                // 推不出来就是 0，不影响主表
            }
        }

        values = Map.copyOf(table);
        origins = Map.copyOf(originTable);
        derivedTable = Map.copyOf(derivedOnly);

        // ⚠️ 地板可以造出印钞机：地板高于「这条配方的材料成本」时，「买原料 → 合成 → 回收」
        //    就变成稳赚。这是地板模型**唯一**的风险，不能静默放过 —— 每次重建都扫一遍并报出来。
        int conflicts = countFloorConflicts(targets, recipes, table);
        conflictsSeen = conflicts;

        if (skipped > 0 || conflicts > 0 || clamped > 0) {
            LOGGER.warn("[mcstock] 估值表重建完成：{} 项有价（跳过 {} 条坏条目；"
                    + "**{} 条地板冲突** —— 地板高于材料成本，那几条是潜在印钞机，"
                    + "用 /mcstock audit 查，把地板调低或给上游材料补地板；"
                    + "{} 项撞到单件上限 {} —— 撞了就说明配方图里有环路）",
                    values.size(), skipped, conflicts, clamped, MAX_VALUE_COINS);
        } else {
            // INFO：这是「购买页目录条数」与「推导价是否正常」的现场证据（真机排障第一条就看它）
            LOGGER.info("[mcstock] 估值表重建完成：{} 项有价（无跳过、无地板冲突）", values.size());
        }
    }

    /**
     * 扫一遍「地板冲突」：买原料 → 合成 → 卖出去**能净赚**的物品。
     *
     * <p>真正的套利条件是 {@code 卖出收入 > 买原料的钱}，而买价是
     * {@code ceil(回收价 × 1.18)} —— 所以比较的是 {@code out × 数量 × 100 > 材料价 × 118}
     * （用整数放大 100 倍，避免浮点误差把 1.18 算成 1.1799999）。
     *
     * <p>⚠️ 早期版本只比 {@code out × 数量 > 材料价}，那是**加价前**的口径，
     * 会把「9 铁粒 → 1 铁锭」这种差 7 个金币的整数除法残差全报成冲突 ——
     * 一开 ATM10 地板表就是几百条假警报，真正的印钞机反而被淹掉。
     *
     * @return 冲突的物品条数（不是配方条数：一个物品只要有一条配方能赚就算一条）
     */
    private static int countFloorConflicts(List<ResourceLocation> targets,
                                           Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes,
                                           Map<ResourceLocation, Integer> table) {
        int conflicts = 0;
        for (ResourceLocation item : targets) {
            Integer out = table.get(item);
            if (out == null || out <= 0) {
                continue;
            }
            List<RecipeGraph.RecipeEntry> list = recipes.get(item);
            if (list == null) {
                continue;
            }
            for (RecipeGraph.RecipeEntry recipe : list) {
                if (recipe == null || recipe.outputCount() <= 0) {
                    continue;
                }
                long cost = 0;
                boolean priced = true;
                for (RecipeGraph.Ingredient ingredient : recipe.inputs()) {
                    if (ingredient == null || ingredient.count() <= 0) {
                        continue;
                    }
                    int unit = cheapest(ingredient.candidates(), table);
                    if (unit <= 0) {
                        priced = false;   // 有买不到的材料 → 这条路线根本不成立，算不上套利
                        break;
                    }
                    cost += (long) unit * ingredient.count();
                }
                if (!priced || cost <= 0) {
                    continue;
                }
                if ((long) out * recipe.outputCount() * 100L > cost * 118L) {
                    conflicts++;
                    break;
                }
            }
        }
        return conflicts;
    }

    /**
     * 物品估值（金币，整数）。
     *
     * @return &gt; 0 表示可回收；0 表示不可回收、也不可购买
     */
    public static int value(ResourceLocation item) {
        if (item == null) {
            return 0;
        }
        // volatile 只读一次：万一这中间有人 rebuild，手里这份快照仍然是完整的一版
        Map<ResourceLocation, Integer> snapshot = values;
        Integer value = snapshot.get(item);
        return value == null ? 0 : value;
    }

    /**
     * 采购价（金币）= {@code ceil(估价 × 1.18)}；估价为 0 时返回 0（买不到）。
     *
     * <h2>为什么必须比回收价贵</h2>
     * 买价 ≥ 回收价 × 1.18 保证了「买原料 → 合成 → 回收」永远亏 15% 以上 ——
     * 这是整个经济<b>唯一</b>的防套利闸门。地板模型不改变这条：
     * 地板托高的是回收价，买价跟着一起抬，价差不变。
     *
     * <h2>为什么后期物品现在会贵</h2>
     * 靠的是地板顺着配方图往下游传播（见类注释「地板 ≠ 锚」），
     * 而不是另开一条「劳动时间」轨道 —— 后者试过，在 ATM10 里对
     * 「没有配方」的稀有资源完全失效。
     *
     * <p>用 {@code ceil} 而不是四舍五入：1 金币的东西卖出 1.18 → 收 2 金币，
     * 否则最低价那一档会变成「买进来和卖出去一个价」的免费搬运。
     */
    public static int purchasePrice(ResourceLocation item) {
        int value = value(item);
        if (value <= 0) {
            return 0;
        }
        return (int) Math.ceil(value * PURCHASE_MARKUP);
    }

    /**
     * **纯配方推导值**（不含地板），只给诊断命令看。
     *
     * <p>和 {@link #value} 对照就能回答整合包作者唯一关心的问题：
     * 「这个价是我写的地板托起来的，还是配方本来就推得这么高？」
     * —— 前者说明地板有用，后者说明地板写多了（白写，还可能造套利）。
     *
     * <p><b>不参与任何结算</b>：定价、回收、购买一律走 {@link #value} / {@link #purchasePrice}。
     *
     * @return 推导值（金币）；0 = 推不出来（无配方 / 输入无价 / 算出来不足 1 金币）
     */
    public static int derived(ResourceLocation item) {
        if (item == null) {
            return 0;
        }
        Integer found = derivedTable.get(item);   // volatile 只读一次，拿到的是一整版快照
        return found == null ? 0 : found;
    }

    /**
     * 上一次重建扫出多少条<b>地板冲突</b>（地板高于材料成本 = 潜在印钞机）。
     *
     * <p>只给 {@code /mcstock audit} 与日志用。它 &gt; 0 不代表世界立刻坏掉
     * （可能只是那条配方有别的约束，比如需要机器），但**必须有人看一眼**。
     */
    public static int conflictCount() {
        return conflictsSeen;
    }

    /**
     * 买一件要多长时间冷却（**秒**）= 采购价秒数（1 金币 ≈ 1 秒劳动量）。
     *
     * <p>为什么拿价格当冷却：价格本身就是「这东西值多少劳动」的度量，
     * 于是买入的吞吐量天然被限制在「自己生产的吞吐量」附近 ——
     * 不需要第二个配置项，价格一改冷却自动跟上。
     *
     * <p>上限 {@link #MAX_COOLDOWN_SECONDS}（24 小时）：没有上限的话，
     * 一件几十万金币的整合包物品会锁到几个月后，那等同于买不到，玩家只会以为按钮坏了。
     *
     * <p>下限 {@link #MIN_COOLDOWN_SECONDS}（1 分钟，= 60 金币）：便宜物品**干脆没有冷却**。
     * 这不是偷懒 —— 一块泥土的「劳动时间」只有 2 秒，若真按 2 秒限购，
     * 玩家连买一组土都要连点两分钟，那是纯折腾；而限购真正要挡的是「成批吃进高价货」。
     *
     * @param purchasePrice 采购价（金币）；{@code <= 0} 返回 0（不可购买的东西没有冷却可言）
     */
    public static int cooldownSeconds(int purchasePrice) {
        if (purchasePrice < MIN_COOLDOWN_SECONDS) {
            return 0;
        }
        return Math.min(purchasePrice, MAX_COOLDOWN_SECONDS);
    }

    /** 冷却上限：24 小时（秒）。见 {@link #cooldownSeconds}。 */
    public static final int MAX_COOLDOWN_SECONDS = 24 * 60 * 60;

    /** 冷却下限：低于这个价格的物品**没有冷却**（见 {@link #cooldownSeconds}）。 */
    public static final int MIN_COOLDOWN_SECONDS = 60;

    /**
     * 单件估值上限（金币）：**地板作为迭代起点之后的保险丝**。
     *
     * <p>旧「锚」语义下不会有这个问题：没有种子的物品从 0 起步，输入全是 0 的配方直接跳过，
     * 环路原地不动。现在地板是起点，一个「A 用 2 个 B、B 用 1 个 A」这种互相引用的环路
     * 会把两边的价一轮一轮往上抬 —— 虽然整合包里基本不会出现（配方比例通常互为倒数，
     * 整数除法会把它压回去），但一旦出现就是把价格迭代到天文数字，
     * 那时玩家看到的是「这件东西要 3 亿金币」，比崩掉还难查。
     *
     * <p>取 1 亿：远高于任何正常玩法的价格（冷却上限 24 小时也只对应 86400 金币/件），
     * 又远低于 {@code int} 上限，撞到它就说明数据包里有环路，日志会报出来。
     */
    public static final int MAX_VALUE_COINS = 100_000_000;

    /** 整张估值表的只读快照（购买页要列举可采购物品时用；不要试图修改它）。 */
    public static Map<ResourceLocation, Integer> snapshot() {
        return values;
    }

    // ==================== 来源查询（只读，给调试命令用） ====================

    /**
     * 一个价**是怎么来的** —— 只用于回答「这是种子值 / 标签值 / 推导值」，
     * 不参与任何定价计算（{@link #value} 的算法与它无关）。
     */
    public enum Source {
        /** 没有来源：既没种子、也没有能推出 ≥1 金币的配方（查不到价的物品都是这一档）。 */
        NONE,
        /** 物品种子：数据包在 {@code values} 里直接写了这个物品 —— 它是**地板**，不是锚。 */
        ITEM_SEED,
        /** 标签种子：命中了 {@code #命名空间:标签}，取所有命中标签里的**最大值**；同样是地板。 */
        TAG_SEED,
        /** 配方推导：按「输入最便宜的候选 × 数量 ÷ 产出」松弛出来的。 */
        DERIVED
    }

    /**
     * 一条估值的来源明细（不可变）。
     *
     * @param source   来源分类
     * @param tag      命中的标签 id；只有 {@link Source#TAG_SEED} 会填，其余是 {@code null}
     * @param declared 种子在数据包里**声明的原值**（金币）：物品种子 / 标签种子会填，
     *                 可能是 0（= 显式钉死成「不可回收」）；其它来源固定 0
     */
    public record Origin(Source source, ResourceLocation tag, int declared) {

        /** 「查不到来源」的那一份（表还没建、物品不在表里、或者传了 null）。 */
        public static final Origin NONE = new Origin(Source.NONE, null, 0);
    }

    /**
     * 查一个物品的估值来源（只读纯查询，不碰缓存以外的任何东西）。
     *
     * <p>⚠️ 它和 {@link #value} 是**两件独立的事**：一个物品完全可能
     * {@code value() == 0} 而 {@code origin().source() == DERIVED}——
     * 那表示「它确实来自配方，但这一轮推出来不足 1 金币」（见 {@link #derive}）。
     * 反过来 {@code ITEM_SEED} + {@code declared() == 0} 表示「数据包显式声明不可回收」。
     *
     * @return 永远非 null；没调过 {@link #rebuild} 时一律 {@link Source#NONE}
     */
    public static Origin origin(ResourceLocation item) {
        if (item == null) {
            return Origin.NONE;
        }
        Origin found = origins.get(item);   // volatile 只读一次，拿到的是一整版快照
        return found == null ? Origin.NONE : found;
    }

    /**
     * 一条产物的估值：取所有配方里最便宜的那条。
     *
     * @return 推导值（金币）；0 = 这一轮推不出来（输入还全是 0，或者算出来不足 1 金币）
     */
    private static int derive(List<RecipeGraph.RecipeEntry> recipes, Map<ResourceLocation, Integer> table) {
        long best = Long.MAX_VALUE;
        for (RecipeGraph.RecipeEntry recipe : recipes) {
            if (recipe == null || recipe.outputCount() <= 0) {
                continue;
            }
            long cost = 0;
            for (RecipeGraph.Ingredient ingredient : recipe.inputs()) {
                if (ingredient == null || ingredient.count() <= 0) {
                    continue;
                }
                cost += (long) cheapest(ingredient.candidates(), table) * ingredient.count();
            }
            if (cost <= 0) {
                continue; // 输入全是 0（还没算出来）→ 这条配方本轮跳过
            }
            best = Math.min(best, cost / recipe.outputCount());
        }
        if (best == Long.MAX_VALUE || best < 1) {
            return 0; // 推导值 < 1 → 记 0
        }
        return (int) Math.min(Integer.MAX_VALUE, best);
    }

    /** 一条输入里最便宜的那个候选的估值；候选全没估值（或没有候选）时返回 0。 */
    private static int cheapest(List<ResourceLocation> candidates, Map<ResourceLocation, Integer> table) {
        if (candidates == null) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        for (ResourceLocation candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Integer value = table.get(candidate);
            if (value != null && value > 0 && value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }
}
