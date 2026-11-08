package com.qiushaonan.mcstock.market;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「进度档」：一件物品**该在累计玩了多少小时游戏时间之后**才允许买 —— 和它值多少钱是**两件事**。
 *
 * <h2>为什么必须把「价格」和「阶段」拆成两个轴（2026-11-10，别再合回去）</h2>
 * 上一版只有一条线性公式：{@code 等够 买价 × 0.95 秒} 就自动开放。它的错误不在「系数」，
 * 而在**用错了自变量**：
 * <ul>
 *   <li><b>价格回答「值多少东西」，阶段回答「什么时候该出现」</b>。
 *       这两件事在好的整合包里**几乎不相关**：Allthemodium 锭在 ATM10 里价值并不夸张
 *       （本表给它 3000，约等于 10 颗钻石），但它**该出现的时刻**是「整个原版流程打完之后」；
 *       反过来，一条十二层的机器链末端产物会因为材料累加而极贵，
 *       可它出现的阶段其实比 Allthemodium 早得多。</li>
 *   <li><b>线性公式给出的答案是荒谬的</b>：3000 金币 × 0.95 秒 ≈ <b>47 分钟</b> ——
 *       一个刚开始玩的人挖两小时就能买 Allthemodium 锭，这正是玩家反馈的那个漏洞。</li>
 *   <li><b>改地板也修不好</b>：想把阶段往后推就得把地板价抬十倍，
 *       那会把价格、回收价、套利判定、采购冷却全部一起带偏（地板越高越容易造印钞机）。
 *       玩家原话：「你用的线性增长并不好，虽然我拉到 30 天，但是 ATM10 锭仍然是应该在整个
 *       MC 原版流程的耗时 2 倍时长后开放」。<b>一个数调不出两件事</b>，所以拆轴。</li>
 * </ul>
 *
 * <h2>现在的口径</h2>
 * <ol>
 *   <li>数据包在 {@code unlock_hours} 段里**显式写**「这件物品第几小时开放」（键可以是物品 id
 *       或 {@code #标签}），见 {@link ItemValueRules}；</li>
 *   <li>写了就以它为准，<b>盖过</b>按价格算出来的时长（长短都盖：它可以比价格更晚，也可以更早）；</li>
 *   <li><b>阶段会顺着配方图往下游传播</b>（{@link #propagate}）：9 个 Allthemodium 锭做的块、
 *       用锭做的齿轮 / 装备，全都自动继承锭的阶段。这一条不是锦上添花 ——
 *       没有它，48 小时才开的锭旁边会摆着「7.1 小时就开」的块（块更贵，按价格公式算更短），
 *       玩家买一块就等于买九个锭，闸门形同虚设。**和地板往下游传播是同一个道理的镜像**；</li>
 *   <li>没写才回落到旧规则（{@code 买价 × 0.95 秒}，见 {@link UnlockRules#requiredPlaySeconds(int)}）——
 *       所以**不写这张表的数据包行为完全不变**，老存档也不需要任何迁移。</li>
 * </ol>
 *
 * <p>⚠️ 本类**只管「第几小时」**，不参与定价、不参与回收、不碰「要不要上锁」的判断
 * （那条仍然看买价或「有没有写阶段」，见 {@link UnlockRules#isLocked}）。
 * 它和 {@link ItemValuator} 只有两处关系：键语法相同（{@code #标签} / 物品 id），
 * 以及传播时用同一张 {@link RecipeGraph} —— 作者写在一张表里，一眼能看出「价」和「阶段」是两列。
 *
 * <p>合并口径与地板一致：**取最大**。同一件物品命中多条（物品键 + 多个标签键 + 配方上游）时，
 * 最晚的那个阶段生效 —— 阶段是「至少等到这时候」，取小的等于凭空放宽，
 * 而放宽会让关键物品提前出现，那是这套机制唯一想拦的事。
 * 代价是**只能往后推、不能往前拉**：想在标签内给某件物品提前，就把该物品从标签里排除
 * （那属于整合包自己的数据）。
 */
public final class UnlockStages {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /**
     * 阶段上限（小时）= {@link UnlockRules#MAX_REQUIRED_PLAY_SECONDS} 换算过来的 720 小时（30 天）。
     *
     * <p>为什么阶段也要受同一个上限约束：两条路（显式阶段 / 按价格算）最终都进同一个
     * 「还差多少游戏时间」的提示，超过 30 天的数字既没法显示也没有意义 ——
     * 那是「等不来，只能交一组」的语义，兜底逻辑只该有一份。
     */
    public static final int MAX_HOURS = (int) (UnlockRules.MAX_REQUIRED_PLAY_SECONDS / 3600L);

    /** 传播的轮数上限。和估值那边一样：正常配方链五六层，20 轮足够，同时是防死循环的保险丝。 */
    private static final int MAX_PASSES = 20;

    /** 物品 id → 开放所需的小时数（只放 &gt; 0 的项）。整份表在 {@link #rebuild} 里一次性换掉。 */
    private static volatile Map<ResourceLocation, Integer> stages = Map.of();

    private UnlockStages() {
    }

    /**
     * 重建整张进度档表。在估值表重建的同一个入口里调（{@code StockEvents#rebuildValues}）。
     *
     * <p>⚠️ 任何一条坏条目都**不许**让整张表建不起来：数据包是作者/别的模组能塞进来的东西，
     * 而调用方在服务端初始化路径上。口径与 {@link ItemValuator#rebuild} 一致：
     * 坏条目跳过 + 计数 + 汇总一条日志，能用的照用。
     *
     * @param itemHours  物品 id → 小时（{@code <= 0} 视为「没写」，回落到按价格算）
     * @param tagHours   标签 id → 小时（命中多个标签取最大）
     * @param tagMembers 标签展开表（与估值表共用同一份，见 {@code StockEvents#tagMembers}）
     * @param allItems   物品全集：只保留真实存在的物品，免得把「没装的模组」的条目
     *                   一路推到客户端（{@code S2CUnlocked} 每次登录都要发一份）。
     *                   传 {@code null} 表示不筛、也不做下游传播（纯 JVM 单测）
     * @param graph      配方图：用来把阶段往下游传（{@link #propagate}）。可以为 {@code null}
     */
    public static void rebuild(Map<ResourceLocation, Integer> itemHours,
                               Map<ResourceLocation, Integer> tagHours,
                               Map<ResourceLocation, Set<ResourceLocation>> tagMembers,
                               Set<ResourceLocation> allItems,
                               RecipeGraph graph) {
        try {
            Map<ResourceLocation, Integer> table = new HashMap<>();
            int skipped = 0;
            int clamped = 0;

            if (itemHours != null) {
                for (Map.Entry<ResourceLocation, Integer> entry : itemHours.entrySet()) {
                    ResourceLocation item = entry.getKey();
                    Integer raw = entry.getValue();
                    if (item == null || raw == null) {
                        skipped++;
                        continue;
                    }
                    if (allItems != null && !allItems.contains(item)) {
                        // 给没装的模组写的阶段：静默丢掉（和地板的 modPresentSeeds 同一个理由，
                        // 否则普通存档每次进世界都要给客户端推几百条用不上的条目）
                        skipped++;
                        continue;
                    }
                    int hours = clamp(raw.intValue());
                    if (hours <= 0) {
                        skipped++;
                        continue;
                    }
                    if (hours != raw.intValue()) {
                        clamped++;
                    }
                    merge(table, item, hours);
                }
            }

            List<String> unresolved = new ArrayList<>();
            int fromTags = 0;
            if (tagHours != null) {
                for (Map.Entry<ResourceLocation, Integer> entry : tagHours.entrySet()) {
                    ResourceLocation tag = entry.getKey();
                    Integer raw = entry.getValue();
                    if (tag == null || raw == null) {
                        continue;
                    }
                    int hours = clamp(raw.intValue());
                    if (hours <= 0) {
                        continue;   // 0 / 负数 = 这条标签不写阶段，不是错误
                    }
                    Set<ResourceLocation> members = tagMembers == null ? null : tagMembers.get(tag);
                    if (members == null || members.isEmpty()) {
                        // 标签在这套环境里不存在（模组没装 / 标签名写错）：静默无效，最后按
                        // 「全都落空」还是「个别落空」决定报 WARN 还是 DEBUG（与地板表同一套降噪规则）
                        unresolved.add("#" + tag);
                        continue;
                    }
                    for (ResourceLocation member : members) {
                        if (member == null) {
                            continue;
                        }
                        merge(table, member, hours);
                        fromTags++;
                    }
                }
            }

            // 显式阶段写完了 —— 现在把它**顺着配方图往下游传**（块 / 齿轮 / 装备自动继承锭的阶段）
            int propagated = propagate(table, allItems, graph);

            stages = Map.copyOf(table);

            if (!unresolved.isEmpty()) {
                if (!table.isEmpty()) {
                    // 有别的条目落上了 → 是标签名写错了，要让作者看见
                    LOGGER.warn("[mcstock] 有 {} 条标签进度档展开不出任何物品（标签名写错或模组没装）：{}{}",
                            unresolved.size(),
                            String.join(", ", unresolved.subList(0, Math.min(5, unresolved.size()))),
                            unresolved.size() > 5 ? " …" : "");
                } else {
                    LOGGER.debug("[mcstock] 本次 {} 条标签进度档全部落空（对应模组未安装），已忽略", unresolved.size());
                }
            }
            if (skipped > 0 || clamped > 0) {
                LOGGER.warn("[mcstock] 进度档重建完成：{} 件物品（标签展开 {} 条；跳过 {} 条（不存在 / 非正数）；"
                        + "{} 条超过上限 {} 小时已截断；下游传播新增 {} 件）",
                        stages.size(), fromTags, skipped, clamped, MAX_HOURS, propagated);
            } else {
                LOGGER.info("[mcstock] 进度档重建完成：{} 件物品（标签展开 {} 条；下游传播新增 {} 件）",
                        stages.size(), fromTags, propagated);
            }
        } catch (Throwable e) {
            // 建不起来时保留上一次的表，并在日志里说清「现在走的是按价格算的回落」
            LOGGER.error("[mcstock] 进度档重建失败，保留上一次的表（{} 件；没有阶段数据的物品按买价算时长）",
                    stages.size(), e);
        }
    }

    /**
     * 这件物品的进度档（**小时**）。
     *
     * @return &gt; 0 = 作者显式写了阶段，用它；{@code 0} = 没写，调用方回落到按价格算
     */
    public static int hours(ResourceLocation item) {
        if (item == null) {
            return 0;
        }
        Integer found = stages.get(item);   // volatile 只读一次，拿到的是一整版快照
        return found == null ? 0 : found;
    }

    /** 整张进度档表的只读快照（{@code S2CUnlocked} 要把它推给客户端）。 */
    public static Map<ResourceLocation, Integer> snapshot() {
        return stages;
    }

    /** 清空（数据包被卸载 / 单测之间互不影响时用）。 */
    public static void reset() {
        stages = Map.of();
    }

    // ==================== 下游传播 ====================

    /**
     * 把阶段顺着配方图**往下游传**：一件物品不可能比它的材料出现得更早。
     *
     * <p>为什么必须有这一步（少了它，整道闸门会被一个块绕过去）：
     * 作者只给「锭」写了 48 小时，可「9 个锭做的块」在数据包里**没有**阶段，
     * 于是它走回落公式 —— 块更贵，{@code 27000 金币 × 0.95 秒 ≈ 7.1 小时}。
     * 玩家 7 小时就能买到一块，等于一次买到 9 个本该 48 小时才能碰的锭。
     * 这和「地板不往下游传」是同一类错误：**局部写对了，整体还是漏的**。
     *
     * <h2>口径（和价格公式刻意相反的两处取 min）</h2>
     * <pre>
     * 物品阶段 = max( 自己写的, min over 配方( max over 输入( 材料阶段 ) ) )
     * </pre>
     * <ul>
     *   <li>**输入取 max**：一条配方要等所有材料都能买到才能做，等最晚的那个；</li>
     *   <li>**配方取 min**：同一产物有多条配方时，玩家当然走**最早能走通**的那条 ——
     *       和估值里「同一产物取最便宜那条配方」是同一个思路（玩家走最省的那条路）；</li>
     *   <li>**候选取 min**：一条输入可以用多个物品满足时（标签展开），能买到哪个都行；</li>
     *   <li>**和自己的阶段取 max**：显式永远只能往后推，不会被上游拉早。</li>
     * </ul>
     * <p>因此阶段只会从「作者写的那几档」往外长，**不会自己冒出来**：没有写在表里、
     * 上游也没有阶段的物品，最后仍然是 0（= 走回落公式）。表是空的就整段跳过。
     *
     * <p>单调 + 有上界（最大不超过作者写的最大值），所以不动点一定在有限轮内到达。
     *
     * @return 有多少件物品是**新增**进表的（只被抬高、本来就在表里的不算）—— 只给日志看
     */
    private static int propagate(Map<ResourceLocation, Integer> table,
                                 Set<ResourceLocation> allItems, RecipeGraph graph) {
        if (graph == null || allItems == null || table.isEmpty()) {
            return 0;
        }
        // 只对「真的有配方」的物品做松弛，省掉大部分空转（和 ItemValuator.rebuild 同一个做法）
        List<ResourceLocation> targets = new ArrayList<>();
        for (ResourceLocation item : new TreeSet<>(allItems)) {
            if (item == null) {
                continue;
            }
            try {
                if (!graph.recipesFor(item).isEmpty()) {
                    targets.add(item);
                }
            } catch (Throwable e) {
                // 某个模组的配方图实现对这一条抛了异常：跳过它（按显式阶段 / 0 处理），别的照传
            }
        }
        int before = table.size();
        for (int pass = 0; pass < MAX_PASSES && !targets.isEmpty(); pass++) {
            boolean changed = false;
            for (ResourceLocation item : targets) {
                int need;
                try {
                    need = requirement(graph.recipesFor(item), table);
                } catch (Throwable e) {
                    continue;
                }
                if (need <= 0) {
                    continue;
                }
                Integer old = table.get(item);
                if (need > (old == null ? 0 : old)) {
                    table.put(item, need);
                    changed = true;
                }
            }
            if (!changed) {
                break;   // 到不动点了
            }
        }
        return Math.max(0, table.size() - before);
    }

    /** 一件物品「最早能什么时候做出来」= 各条配方里最宽松的那一条的「最难的那个材料」。 */
    private static int requirement(List<RecipeGraph.RecipeEntry> recipes, Map<ResourceLocation, Integer> table) {
        int best = Integer.MAX_VALUE;
        for (RecipeGraph.RecipeEntry recipe : recipes) {
            if (recipe == null) {
                continue;
            }
            int need = 0;
            for (RecipeGraph.Ingredient ingredient : recipe.inputs()) {
                if (ingredient == null || ingredient.count() <= 0) {
                    continue;
                }
                need = Math.max(need, earliestOf(ingredient.candidates(), table));
            }
            best = Math.min(best, need);
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** 一条输入里**最早能拿到**的候选的阶段（拿不到阶段 = 0 = 开局就能拿）。 */
    private static int earliestOf(List<ResourceLocation> candidates, Map<ResourceLocation, Integer> table) {
        int min = Integer.MAX_VALUE;
        for (ResourceLocation candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Integer stage = table.get(candidate);
            int value = stage == null ? 0 : stage;
            if (value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** 把作者写的数字夹到 {@code [0, MAX_HOURS]}：{@code <= 0} 归 0（= 没写）。 */
    private static int clamp(int raw) {
        if (raw <= 0) {
            return 0;
        }
        return Math.min(raw, MAX_HOURS);
    }

    /** 合并一条：**取最大**（理由见类注释「只能往后推、不能往前拉」）。 */
    private static void merge(Map<ResourceLocation, Integer> table, ResourceLocation item, int hours) {
        Integer old = table.get(item);
        if (old == null || old < hours) {
            table.put(item, hours);
        }
    }

    /** 表里有没有任何条目（日志与诊断用）。 */
    public static boolean isEmpty() {
        return stages.isEmpty();
    }

    /** 已解析出的物品 id 集合（排序过的，诊断命令打印用）。 */
    public static Set<ResourceLocation> keys() {
        return new TreeSet<>(stages.keySet());
    }
}
