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
 * <h2>估值的三层优先级</h2>
 * <ol>
 *   <li><b>物品种子</b>（数据包里直接写 {@code "minecraft:diamond": 40}）；</li>
 *   <li><b>标签种子</b>（{@code "#minecraft:logs": 4}，命中多个标签时取最大）；</li>
 *   <li><b>配方推导</b>：没种子值的物品，按
 *       {@code floor(Σ(输入候选中最低估值 × 数量) / 产出数量)} 算，同一产物的所有配方取 min，
 *       反复松弛到不动点（最多 {@link #MAX_PASSES} 轮）。</li>
 * </ol>
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
 * <h2>两条轨道（2026-11-09 起）</h2>
 * <ul>
 *   <li><b>回收价</b> = {@link #value} = 材料成本（本节开头那套三层优先级 + 两条防印钞规矩）。
 *       <b>一分钱都没动</b>，它是整个经济的地板；</li>
 *   <li><b>采购价</b> = {@link #purchasePrice} = {@code max(材料价 × 1.18, 劳动时间 × 60)}。
 *       第二条支由 {@link LaborTime} 提供，专门解决「模组机器链里 1:1 配方让后期物品
 *       按原料价出售」这个病 —— 详见 {@link ItemValuator#purchasePrice}。</li>
 * </ul>
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
     * 而**回收价一个字没动**（估价 × 1.0）—— 劳动那条路必须保持公平。
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
     * **劳动时间表**（物品 → 分钟），采购价的第二条轨道，见 {@link LaborTime}。
     *
     * <p>⚠️ 与 {@link #values} 是两件事：{@code values} 管**回收价**（材料成本，一分钱没动），
     * 这张表只管**买价**能不能更高。整张表在同一轮 {@link #rebuild} 里换掉；
     * 建不起来就是空表，买价自动退回「材料价 × 1.18」那一档 —— 降级，不失效。
     */
    private static volatile Map<ResourceLocation, Double> labor = Map.of();

    private ItemValuator() {
    }

    /**
     * 重算整张估值表。服务端启动完、数据包 reload 之后各调一次
     * （本模组在 {@code StockEvents} 里接的 {@code ServerStartedEvent} / 登录事件）。
     *
     * <p>本方法是幂等的：同样的输入重算多少遍都是同一张表，不依赖上一次的结果。
     *
     * @param itemSeeds  物品种子（物品 id → 金币）。值 ≤ 0 表示「显式钉死成不可回收」，
     *                   它会挡住标签种子与配方推导（种子优先级是「物品种子说了算」，不是「取最大」）
     * @param tagSeeds   标签种子（标签 id → 金币）
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
        Map<ResourceLocation, Integer> table = new HashMap<>();
        // 「有种子」的物品不再参与推导 —— 哪怕种子值是 0（0 是有效的「不回收」声明）
        Set<ResourceLocation> seeded = new HashSet<>();
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
                    seeded.add(item);
                    Integer value = seed.getValue();
                    if (value != null && value > 0) {
                        table.put(item, value);
                    }
                    originTable.put(item, new Origin(Source.ITEM_SEED, null, value == null ? 0 : value));
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
                    if (members == null) {
                        continue;   // 标签没展开成功（比如是方块标签）：当作没写
                    }
                    for (ResourceLocation member : members) {
                        if (member == null || seeded.contains(member)) {
                            continue; // 物品种子优先
                        }
                        Integer old = table.get(member);
                        if (old == null || old < value) {
                            table.put(member, value); // 命中多个标签取最大
                            originTable.put(member, new Origin(Source.TAG_SEED, seed.getKey(), value));
                        }
                    }
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        }

        // 劳动时间表的**锚**：数据包直接写了价的所有物品（物品种子 + 标签种子）。
        // ⚠️ 不能直接用上面的 `seeded` —— 那只是**物品**种子。原木 / 羊毛 / 木板这些是
        //    **标签**种子，价格同样由数据包拍板，可是它们不在 seeded 里。不锚住它们的后果：
        //    它们会走配方推导、白白多付一份工序时间，于是「基础物资」的买价也被抬高 ——
        //    而涨价只应该发生在深加工链上（见 LaborTime 类注释里「两条轨道共用同一个锚」）。
        Set<ResourceLocation> anchored = new HashSet<>();
        for (Map.Entry<ResourceLocation, Integer> priced : table.entrySet()) {
            Origin origin = originTable.get(priced.getKey());
            if (origin != null && (origin.source() == Source.ITEM_SEED
                    || origin.source() == Source.TAG_SEED)) {
                anchored.add(priced.getKey());
            }
        }

        // 排序：让每轮的遍历顺序固定，同一份数据包在任何机器上算出来的表都一样
        List<ResourceLocation> derived = new ArrayList<>();
        if (allItems != null) {
            for (ResourceLocation item : new TreeSet<>(allItems)) {
                if (item != null && !seeded.contains(item) && !table.containsKey(item)) {
                    derived.add(item);
                }
            }
        }

        // 只对「真的有配方」的物品做松弛，省掉大部分空转
        Map<ResourceLocation, List<RecipeGraph.RecipeEntry>> recipes = new HashMap<>();
        if (graph != null) {
            for (ResourceLocation item : derived) {
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
                Integer old = table.get(item);
                int current = old == null ? 0 : old;
                if (candidate == current) {
                    continue;
                }
                if (candidate > 0) {
                    table.put(item, candidate);
                } else {
                    table.remove(item);
                }
                changed = true;
            }
            if (!changed) {
                break; // 到不动点了
            }
        }

        values = Map.copyOf(table);
        origins = Map.copyOf(originTable);

        // 劳动时间表（采购价的第二条轨道）：锚在**种子价**上，按配方图一层层累加工序时间。
        // ⚠️ 它在估值表**之后**算，因为种子价就是它的锚点；失败了也只是买价不加成，
        //    绝不影响上面那张回收价表（那条是防印钞机的地板，不能跟着一起塌）。
        try {
            labor = LaborTime.compute(table, anchored, recipes, RecipeSteps::minutes);
        } catch (Throwable e) {
            skipped++;
            labor = Map.of();
            LOGGER.warn("[mcstock] 劳动时间表构建失败，采购价退回「材料价 × 1.18」那一档", e);
        }

        if (skipped > 0) {
            LOGGER.warn("[mcstock] 估值表重建完成：{} 项有价（跳过 {} 条坏条目 —— "
                    + "坏种子 / 坏标签 / 抛异常的配方查询，它们按「无价」处理）", values.size(), skipped);
        } else {
            // INFO：这是「购买页目录条数」与「推导价是否正常」的现场证据（真机排障第一条就看它）
            LOGGER.info("[mcstock] 估值表重建完成：{} 项有价（无跳过）", values.size());
        }
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
     * 采购价（金币）= {@code max(ceil(估价 × 1.18), ceil(劳动时间 × 60))}；
     * 估价为 0 时返回 0（买不到）。
     *
     * <h2>为什么是两条轨道取 max，而不是替换掉旧公式</h2>
     * <ul>
     *   <li><b>旧公式那一支是「防印钞机地板」</b>：买价 ≥ 回收价 × 1.18 保证了
     *       「买原料 → 合成 → 回收」永远亏。它绝不能失效，否则整个经济被套利吃掉；</li>
     *   <li><b>劳动时间那一支是「后期物品该贵」</b>：材料成本算不出来的东西
     *       （模组机器链里那些 1:1 的配方）由它来标价，链越深越贵；</li>
     *   <li>两者取 {@code max}：**只会更贵，永远不会更便宜** —— 于是旧存档里已经是
     *       「按材料价买」的东西不会突然变便宜，回收价更是一个字都没动。</li>
     * </ul>
     *
     * <p>用 {@code ceil} 而不是四舍五入：1 金币的东西卖出 1.18 → 收 2 金币，
     * 否则最低价那一档会变成「买进来和卖出去一个价」的免费搬运。
     */
    public static int purchasePrice(ResourceLocation item) {
        int value = value(item);
        if (value <= 0) {
            return 0;
        }
        int byMaterial = (int) Math.ceil(value * PURCHASE_MARKUP);
        double minutes = laborMinutes(item);
        if (minutes <= 0) {
            return byMaterial;
        }
        double coins = minutes * LaborTime.COINS_PER_MINUTE;
        int byLabor = coins >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.ceil(coins);
        return Math.max(byMaterial, byLabor);
    }

    /**
     * 做出这个物品要多少**分钟**劳动（0 = 推不出来，买价不加成）。
     *
     * <p>口径见 {@link LaborTime}：锚在种子价上（1 金币 = 1 秒劳动），按配方图逐层累加
     * 工序时间。**只影响买价**，回收价与它无关。
     */
    public static double laborMinutes(ResourceLocation item) {
        if (item == null) {
            return 0;
        }
        Double minutes = labor.get(item);   // volatile 只读一次，拿到一整版快照
        return minutes == null ? 0 : minutes;
    }

    /**
     * 买一件要多长时间冷却（**秒**）= 采购价 ÷ 60 分钟 = 采购价秒。
     *
     * <p>为什么就是价格本身：1 金币 = 1 秒劳动（见 {@link LaborTime#COINS_PER_MINUTE}），
     * 所以「冷却时长 = 这件东西对应的劳动时间」—— 买入的吞吐量正好等于自己生产的吞吐量。
     * <b>它们是同一个数，不需要第二个配置项</b>，价格一改冷却自动跟上。
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
        /** 物品种子：数据包在 {@code values} 里直接写了这个物品，它挡住标签种子与配方推导。 */
        ITEM_SEED,
        /** 标签种子：命中了 {@code #命名空间:标签}，取所有命中标签里的**最大值**。 */
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
