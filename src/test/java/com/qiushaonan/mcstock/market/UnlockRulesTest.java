package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「交付一组才解锁」的规则测试（纯 JVM，不启动游戏）。
 *
 * <p>守的是三条**不能静默吞玩家东西**的口径（见 {@link UnlockRules} 类注释）：
 * 只消耗恰好一组、不足一组一件不动、已解锁/不需解锁的一件不动。
 * 另外守「是否上锁」那条线：只看价格，**不看有没有种子**（理由见
 * {@link UnlockRules#requiresUnlock}）。
 */
class UnlockRulesTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static Map<ResourceLocation, Integer> counts(Object... pairs) {
        Map<ResourceLocation, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((ResourceLocation) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private static List<UnlockRules.Candidate> plan(Map<ResourceLocation, Integer> counts,
                                                   int stackSize,
                                                   Predicate<ResourceLocation> already) {
        return UnlockRules.plan(counts, id -> stackSize, already);
    }

    @Test
    void fullStackIsReadyAndPartialIsNot() {
        List<UnlockRules.Candidate> p = plan(counts(id("a"), 64, id("b"), 63), 64, x -> false);
        UnlockRules.Candidate a = p.get(0);
        UnlockRules.Candidate b = p.get(1);
        assertTrue(a.complete(), "满一组就该能解锁");
        assertFalse(a.partial(), "满了就不算「不够」");
        assertEquals(0, a.missing());

        assertFalse(b.complete(), "差一个就是不够 —— 这条挡住「先交一半」");
        assertTrue(b.partial());
        assertEquals(1, b.missing());
    }

    @Test
    void needFollowsTheStackSizeSoToolsNeedOne() {
        // 工具护甲 stack = 1：做过第二把就说明你有产能
        List<UnlockRules.Candidate> p = plan(counts(id("pickaxe"), 1), 1, x -> false);
        assertEquals(1, p.get(0).need());
        assertTrue(p.get(0).complete(), "堆叠上限 1 的物品，交一个就够");
    }

    @Test
    void alreadyUnlockedNeverShowsAsReady() {
        // 哪怕交了一整组，已解锁的也不该再亮「可以解锁」—— 否则点一下就把东西吞了
        List<UnlockRules.Candidate> p = plan(counts(id("a"), 128), 64, x -> true);
        assertFalse(p.get(0).complete());
        assertTrue(p.get(0).already());
        assertEquals(0, UnlockRules.readyCount(p));
    }

    @Test
    void readyCountCountsOnlyCompleteOnes() {
        Map<ResourceLocation, Integer> c = counts(id("a"), 64, id("b"), 10, id("c"), 64);
        List<UnlockRules.Candidate> p = UnlockRules.plan(c,
                x -> x.getPath().equals("b") ? 64 : 32, x -> false);
        // a: 64/32 满；b: 10/64 不满；c: 64/32 满
        assertEquals(2, UnlockRules.readyCount(p));
    }

    @Test
    void planIsSortedAndDropsEmptyEntries() {
        Map<ResourceLocation, Integer> c = new LinkedHashMap<>();
        c.put(id("zeta"), 64);
        c.put(id("alpha"), 64);
        c.put(id("beta"), 0);      // 0 件不该出现在计划里
        c.put(id("gamma"), -3);    // 负数同理
        List<UnlockRules.Candidate> p = plan(c, 64, x -> false);
        assertEquals(2, p.size());
        assertEquals("alpha", p.get(0).item().getPath(), "输出必须按 id 排序，提示文案才稳定");
        assertEquals("zeta", p.get(1).item().getPath());
    }

    @Test
    void emptyInputGivesEmptyPlan() {
        assertTrue(UnlockRules.plan(Map.of(), id -> 64, x -> false).isEmpty());
        assertTrue(UnlockRules.plan(null, id -> 64, x -> false).isEmpty());
    }

    @Test
    void expensiveItemsNeedUnlockingNoMatterWhereThePriceCameFrom() {
        // ⚠️ 这条钉住的是「地板不再豁免解锁」：ATM10 里 Allthemodium 锭的价格正是
        //    数据包写的地板（它没有配方），按旧规矩它会被豁免 —— 而那恰好是
        //    这套机制唯一想拦的东西。现在只看价格。
        assertTrue(UnlockRules.requiresUnlock(5000), "贵就要解锁，不管价是地板写的还是推出来的");
        assertFalse(UnlockRules.requiresUnlock(10), "便宜货不上锁");
    }

    @Test
    void cheapItemsStayBuyable() {
        assertFalse(UnlockRules.requiresUnlock(UnlockRules.MIN_PRICE_TO_LOCK - 1),
                "低于门槛的物品不该上锁 —— 否则购买页会变成一片灰");
        assertFalse(UnlockRules.requiresUnlock(0), "没有价格 = 不可购买，谈不上解锁");
        assertFalse(UnlockRules.requiresUnlock(-5));
    }

    @Test
    void atTheThresholdItLocks() {
        assertTrue(UnlockRules.requiresUnlock(UnlockRules.MIN_PRICE_TO_LOCK), "门槛本身算「要解锁」");
        assertTrue(UnlockRules.requiresUnlock(99999));
    }

    @Test
    void planDoesNotMutateItsInput() {
        Map<ResourceLocation, Integer> c = counts(id("a"), 64);
        Set<ResourceLocation> keys = Set.copyOf(c.keySet());
        plan(c, 64, x -> false);
        assertEquals(keys, c.keySet(), "计划只是只读统计，绝不该改动投放区的账");
        assertEquals(64, c.get(id("a")));
    }

    // ==================== 时间解锁（第二条路） ====================

    /**
     * 时间解锁的门槛 = 买价的 95%。
     *
     * <p>为什么是 95%：留 5% 的缺口，让「主动交付一组」永远比「干等」早一点 ——
     * 否则谁都会选干等，解锁页就成了摆设。
     */
    @Test
    void timeLockIsNinetyFivePercentOfThePrice() {
        assertEquals(950L, UnlockRules.requiredPlaySeconds(1000));
        assertEquals(0L, UnlockRules.requiredPlaySeconds(0), "不可购买的东西没有时间门槛");
        assertEquals(0L, UnlockRules.requiredPlaySeconds(-100), "负价不能算出负数门槛");
        // 一件 3000 金币的后期物品 ≈ 47.5 分钟游戏时间
        assertEquals(2850L, UnlockRules.requiredPlaySeconds(3000));
    }

    /**
     * 时间上限：再贵的物品也最多等 24 小时。
     *
     * <p>为什么必须有这条：地板模型把整合包后期物品抬到几十万甚至上百万金币，
     * 而这条路是「价格 × 0.95 秒」—— 一个 ATM Star 会算出「再玩 110 天」，
     * 那不是难度，是坏掉的提示（玩家只会认为按钮坏了）。
     * 封在一天以内之后，「干等」对超后期物品依然是下策，交付仍然更快。
     */
    @Test
    void timeLockIsCappedAtOneDay() {
        long day = UnlockRules.MAX_REQUIRED_PLAY_SECONDS;
        assertEquals(24 * 60 * 60, day, "上限就是一天");
        assertEquals(950L, UnlockRules.requiredPlaySeconds(1000), "上限以下照旧：价格 × 0.95");
        assertEquals(day, UnlockRules.requiredPlaySeconds(100_000), "十万金币也只等一天");
        assertEquals(day, UnlockRules.requiredPlaySeconds(10_000_000), "一千万金币同样只等一天");
        // 恰好卡在边界上：让 0.95 × price 正好等于一天的那个价格点
        assertEquals(day, UnlockRules.requiredPlaySeconds((int) Math.ceil(day / UnlockRules.TIME_LOCK_FRACTION)));
        assertEquals(day - 1, UnlockRules.requiredPlaySeconds((int) Math.floor((day - 1) / UnlockRules.TIME_LOCK_FRACTION)));
    }

    @Test
    void lockedUntilEitherDeliveredOrOldEnough() {
        int price = 3000;
        long need = UnlockRules.requiredPlaySeconds(price);
        // 1) 刚开始玩、没交过 → 锁着
        assertTrue(UnlockRules.isLocked(price, false, 0));
        assertTrue(UnlockRules.isLocked(price, false, need - 1), "差一秒都还锁着");
        // 2) 玩够了 → 自动开，不需要任何存档
        assertFalse(UnlockRules.isLocked(price, false, need));
        assertFalse(UnlockRules.isLocked(price, false, need + 86400));
        // 3) 交过一组 → 立刻开，和新号老号无关
        assertFalse(UnlockRules.isLocked(price, true, 0));
        // 4) 便宜货 → 价格/时间都不参与
        assertFalse(UnlockRules.isLocked(UnlockRules.MIN_PRICE_TO_LOCK - 1, false, 0));
    }

    /**
     * 边界：买价不到门槛时，时间门槛也不该把新号挡住 ——
     * {@code isLocked} 先看 {@code requiresUnlock}，所以便宜物品的 playSeconds 完全不参与。
     */
    @Test
    void cheapItemsAreNeverTimeLockedEvenForBrandNewPlayers() {
        int cheap = UnlockRules.MIN_PRICE_TO_LOCK - 1;
        assertFalse(UnlockRules.isLocked(cheap, false, 0));
    }
}
