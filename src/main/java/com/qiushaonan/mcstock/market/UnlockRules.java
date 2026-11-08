package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 「解锁」的纯规则：**投放区里凑满一组，才承认你确实有这个物品的产能**。
 *
 * <p>为什么门槛是「一组」而不是 1 个：一组是**游戏自己给出的批量定义**
 * （{@code ItemStack#getMaxStackSize()}：材料 64、部分模组 16、工具护甲 1）。
 * 玩家舍得把一整组交出去，说明这玩意儿要么便宜到可以自己量产，要么他真的建出了产线 ——
 * 两种情况都意味着「他不需要靠购买来跳级」。这个门槛**不需要任何配置**，
 * 而且天然适配任何整合包（模组自己定的堆叠上限就是它的难度刻度）。
 *
 * <h2>为什么这条不参与估值</h2>
 * 它只看「投放区里有什么、够不够一组」，不看价格。价格在 {@link ItemValuator} 里算，
 * 两件事刻意不互相依赖：估值表整个建不起来时，解锁页最多是「不知道解锁后多少钱」，
 * 而不是整页失效。
 *
 * <h2>⚠️ 消耗口径</h2>
 * <ul>
 *   <li>只消耗**恰好一组**（{@code need} 个），多出来的**原样留在投放区**；</li>
 *   <li>已经解锁 / 本来就不需要解锁的物品**一件都不动**（见 {@link Candidate#already()}）；</li>
 *   <li>不足一组的物品**一件都不动**（不让玩家「先交一半」）。</li>
 * </ul>
 * 这三条是「不静默吞玩家东西」的具体落地，单测在 {@link com.qiushaonan.mcstock.market.UnlockRulesTest}。
 */
public final class UnlockRules {

    /**
     * 需要解锁的最低买价（金币）：**只有贵的物品才上锁**。
     *
     * <p>取 500 的理由：低于它的东西（泥土、铁、木材、常见矿物）本来就该在购买页随手买，
     * 给它们上锁只会让界面变成一片灰。真正会把「开局捡点东西换后期物品」做实的是高价物品，
     * 而 500 金币 ≈ 8 分钟游戏时间，这是「随便捡点」够不到、但正常玩一定过得去的线。
     */
    public static final int MIN_PRICE_TO_LOCK = 500;

    /**
     * 「等时间」这条路的**回落系数**：没写进度档的物品，玩到它价格对应时长的 95% 就自动开放。
     *
     * <p>⚠️ 这条公式现在已经**不是主力**了（2026-11-10 换轨）：数据包 {@code unlock_hours}
     * 里写了阶段的物品**一律以阶段为准**（见 {@link UnlockStages}），这条只在作者没写时兜底。
     * 原因是「价格」和「该在哪个阶段出现」是两件事，一个数调不出两件事 ——
     * 详细理由（含 Allthemodium 47 分钟就能买的反例）写在 {@link UnlockStages} 类注释里。
     *
     * <p>为什么是 95% 而不是 100%：100% 会和「交一组解锁」这条路**恰好撞上**——
     * 一个玩家攒够劳动时间的那一刻，他通常也已经攒够了一组能交的货。
     * 留 5% 的缺口，是为了让「主动交付」永远比「干等」**早**一点点，
     * 这样交付这条路才有意义（否则谁都会选干等，解锁页就成了摆设）。
     */
    public static final double TIME_LOCK_FRACTION = 0.95;

    /**
     * 「等时间」这条路的上限：**30 天游戏时间**（秒）。
     *
     * <p>⚠️ 这条上限存在的意义**不是**「让玩家等得起」，而是「给数值崩坏兜底」。
     * 它必须明显**长于一个整合包的通关时间**，否则「干等」就成了绕过进度证明的后门：
     * ATM10 这类大型整合包，普通玩家从零到通关光是**游戏时间**就要十几天
     * （`Stats.PLAY_TIME` 是真实在玩的时间，不是挂机、不是现实天数）。所以：
     *
     * <ul>
     *   <li>上限取 30 天 ≈ 两倍通关时间 —— 撞到它的物品（ATM Star 那一档）
     *       在效果上就是「<b>等不来，只能交一组</b>」，这正是想要的语义；</li>
     *   <li>低于它的部分**完全按作者写的阶段 / 价格算**，不做任何提前；</li>
     *   <li>⚠️ 两条路共用这一个上限：显式阶段（{@link UnlockStages}，单位小时，最多 720 小时）
     *       与回落公式（价格 × 0.95 秒）走的是同一个「还差多少游戏时间」的提示，
     *       兜底逻辑只该有一份，所以 {@link UnlockStages#MAX_HOURS} 是从这里换算出去的；</li>
     *   <li>再往上加没有意义：{@code ItemValuator#MAX_VALUE_COINS} 会把估值截在 1 亿，
     *       1 亿 × 0.95 秒 ≈ 1100 天，那种数字是截断的产物，不该出现在界面上。</li>
     * </ul>
     *
     * <p>⛔ 历史教训（2026-11-08，别改回去）：这条上限一度是 **24 小时**，
     * 理由是「110 天是坏 UI」。结果是**所有**后期物品都变成「等一天就能买」——
     * 一把 8 万金币的镐和一颗 ATM Star 的等待时长完全一样，整套解锁机制当场失效。
     * 上限只能用来兜底，不能用来「让数字好看」。
     *
     * <p>锚：{@code UnlockRulesTest#timeLockStopsAtThirtyDays}。
     */
    public static final long MAX_REQUIRED_PLAY_SECONDS = 30L * 24 * 60 * 60;

    private UnlockRules() {
    }

    /**
     * 一个物品在投放区里的解锁进度。
     *
     * @param item    物品 id
     * @param have    投放区里的总件数（跨格子累加）
     * @param need    一组是多少（该物品的堆叠上限，至少 1）
     * @param already {@code true} = 不用做任何事（已经解锁，或者本来就不需要解锁）
     */
    public record Candidate(ResourceLocation item, int have, int need, boolean already) {

        /** 现在按下去就能解锁。 */
        public boolean complete() {
            return !already && need > 0 && have >= need;
        }

        /** 放了东西但不够一组（界面要显示「还差 N 个」）。 */
        public boolean partial() {
            return !already && have > 0 && have < need;
        }

        /** 还差多少个才够一组（已经够 / 已解锁时为 0）。 */
        public int missing() {
            return complete() || already ? 0 : Math.max(0, need - have);
        }
    }

    /**
     * 这件物品要不要上锁：<b>只看价格够不够高</b>。
     *
     * <p>⚠️ 这里**故意不看「有没有种子」**。旧版有一条「数据包写了种子的物品一律不上锁」，
     * 理由是「它们是购买页的基础物资」—— 那在<b>锚</b>语义下成立（种子只给基础物资写）。
     * 改成<b>地板</b>语义之后这条彻底反了：数据包写地板的恰恰是 Allthemodium 这类
     * <b>关键进度物品</b>（因为它们没有配方、推不出价），按旧规矩它们会<b>恰好绕过解锁</b>，
     * 而那正是这套机制唯一想拦的东西。
     *
     * <p>基础物资不需要豁免：木板、铁锭、钻石这些的地板价远低于
     * {@link #MIN_PRICE_TO_LOCK}，天然不会被锁。
     *
     * <p>⚠️ 这是**价格那一路**的门槛。数据包里写了进度档（{@link UnlockStages}）的物品
     * 一眼就要上锁，**不看这个门槛** —— 见 {@link #isLocked(int, boolean, long, int)}。
     *
     * @param purchasePrice 采购价（金币）；{@code <= 0} 表示不可购买
     */
    public static boolean requiresUnlock(int purchasePrice) {
        return purchasePrice >= MIN_PRICE_TO_LOCK;
    }

    /**
     * 这个物品现在是不是**还锁着**（服务端与客户端共用的唯一判据）。
     *
     * <p>三条路，任何一条成立就开：
     * <ol>
     *   <li><b>交过一组</b>（{@code delivered}，存在 {@code ModAttachments.UNLOCKED} 里）；</li>
     *   <li><b>玩够了它对应的时长</b>（见 {@link #requiredPlaySeconds(int, int)}）—— 这条路不需要存档，
     *       是「算出来的」，所以老存档进来也立刻正确，不需要迁移；</li>
     *   <li>便宜到不影响进度（见 {@link #requiresUnlock}）<b>且没写进度档</b>。</li>
     * </ol>
     *
     * <p>⚠️ 并列关系是有意的：<b>写了进度档就等于上锁</b>（哪怕价格不到
     * {@link #MIN_PRICE_TO_LOCK}）。否则作者没法给「便宜但关键」的物品单独设阶段
     * （比如某种矿粒），而那种东西恰恰是「开局捡一点就能换到后期货」的入口。
     *
     * <p>⚠️ 参数刻意全是从外部算好传进来的（价格、是否交付过、游戏时间、进度档），
     * 本方法**不碰玩家对象、不碰估值器、也不查静态表**：这样服务端与客户端可以调同一份判定，
     * 不会出现「界面说能买、服务端说不能」的错位。
     *
     * @param purchasePrice 采购价（金币）；{@code <= 0} 视为不用解锁
     * @param delivered     这个玩家是不是已经交过一组
     * @param playSeconds   这个玩家的**累计游戏时间**（秒）
     * @param explicitHours 数据包写的进度档（小时，{@link UnlockStages#hours}）；{@code <= 0} = 没写
     */
    public static boolean isLocked(int purchasePrice, boolean delivered, long playSeconds, int explicitHours) {
        if (delivered) {
            return false;
        }
        if (explicitHours <= 0 && !requiresUnlock(purchasePrice)) {
            return false;   // 既没写阶段、价格又不到门槛：永远随手可买
        }
        return playSeconds < requiredPlaySeconds(purchasePrice, explicitHours);
    }

    /**
     * 三参重载 = 「没写进度档」（回落公式）。保留它是因为大量既有调用与单测只关心价格那一路。
     *
     * @see #isLocked(int, boolean, long, int)
     */
    public static boolean isLocked(int purchasePrice, boolean delivered, long playSeconds) {
        return isLocked(purchasePrice, delivered, playSeconds, 0);
    }

    /**
     * 一个物品要玩多久（**秒**）才自动开放。
     *
     * <h2>显式进度档优先（2026-11-10）</h2>
     * {@code explicitHours > 0} 时**直接用它**（换算成秒，夹到 {@link #MAX_REQUIRED_PLAY_SECONDS}），
     * 不看价格 —— 而且**长短都盖**：它既可以把物品推到比价格算出来的更晚
     * （Allthemodium 锭：3000 金币按价格只要 47 分钟，按阶段要 48 小时），
     * 也可以比价格更早（一条长机器链的末端产物很贵，但它的阶段并不晚）。
     * 为什么必须这样，见 {@link UnlockStages} 类注释「为什么必须拆成两个轴」。
     *
     * <h2>没写才按价格算（回落）</h2>
     * 这套经济里 <b>1 金币 ≈ 1 秒劳动</b>（回收价就是「做出它要多少东西」的度量），
     * 所以「价格对应的时长」就是价格本身。一件 3000 金币的物品要 2850 秒 ≈ 48 分钟游戏时间；
     * 一件 60 万金币的块要 5.9 天。**回报越贵的东西，等得越久**。
     *
     * <p>⚠️ 上限 30 天只用来兜底数值崩坏，**不是**「让玩家等得起」的折扣 ——
     * 详见 {@link #MAX_REQUIRED_PLAY_SECONDS} 里那段历史教训。
     *
     * @return 秒；两者都没有（{@code purchasePrice <= 0} 且没写阶段）时返回 0
     */
    public static long requiredPlaySeconds(int purchasePrice, int explicitHours) {
        if (explicitHours > 0) {
            long wanted = (long) explicitHours * 3600L;
            return Math.min(wanted, MAX_REQUIRED_PLAY_SECONDS);
        }
        return requiredPlaySeconds(purchasePrice);
    }

    /**
     * 「没写进度档」时的回落公式：采购价的 95%，上限 {@link #MAX_REQUIRED_PLAY_SECONDS}。
     *
     * @return 秒；{@code purchasePrice <= 0} 返回 0
     */
    public static long requiredPlaySeconds(int purchasePrice) {
        if (purchasePrice <= 0) {
            return 0;
        }
        long wanted = (long) Math.ceil(purchasePrice * TIME_LOCK_FRACTION);
        return Math.min(wanted, MAX_REQUIRED_PLAY_SECONDS);
    }

    /**
     * 把「投放区里有什么」摊成逐物品的解锁计划。
     *
     * <p>按物品 id 排序输出：同一份投放区在任何机器上算出来的顺序一致，
     * 提示文案与单测才好对。{@code counts} 里件数为 0 或负数的条目直接丢掉（不参与判定）。
     *
     * @param counts          物品 id → 投放区里的总件数
     * @param stackSize       该物品的一组是多少（生产端传 {@code ItemStack#getMaxStackSize()}）
     * @param alreadyUnlocked 这一项是不是「已经不用再解锁了」
     */
    public static List<Candidate> plan(Map<ResourceLocation, Integer> counts,
                                       ToIntFunction<ResourceLocation> stackSize,
                                       Predicate<ResourceLocation> alreadyUnlocked) {
        List<Candidate> out = new ArrayList<>();
        if (counts == null || counts.isEmpty()) {
            return out;
        }
        for (Map.Entry<ResourceLocation, Integer> entry : new TreeMap<>(counts).entrySet()) {
            ResourceLocation item = entry.getKey();
            Integer raw = entry.getValue();
            if (item == null || raw == null || raw <= 0) {
                continue;
            }
            int need = stackSize == null ? 1 : Math.max(1, stackSize.applyAsInt(item));
            boolean already = alreadyUnlocked != null && alreadyUnlocked.test(item);
            out.add(new Candidate(item, raw, need, already));
        }
        return out;
    }

    /** 这一份计划里有几项现在就能解锁（按钮是否可用的唯一判据）。 */
    public static int readyCount(List<Candidate> plan) {
        int n = 0;
        for (Candidate c : plan) {
            if (c.complete()) {
                n++;
            }
        }
        return n;
    }
}
