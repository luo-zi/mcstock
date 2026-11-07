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
 * 另外守「是否上锁」那条线：有种子价的原版物品永远不上锁（否则购买页会被清空）。
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
    void seededItemsNeverNeedUnlocking() {
        // 原版种子物品：价格再高也不上锁（它们是购买页的基础物资，也是兑换尺度的锚）
        assertFalse(UnlockRules.requiresUnlock(5000, true));
        assertFalse(UnlockRules.requiresUnlock(10, true));
    }

    @Test
    void cheapDerivedItemsStayBuyable() {
        assertFalse(UnlockRules.requiresUnlock(UnlockRules.MIN_PRICE_TO_LOCK - 1, false),
                "低于门槛的推导价物品不该上锁 —— 否则购买页会变成一片灰");
        assertFalse(UnlockRules.requiresUnlock(0, false), "没有价格 = 不可购买，谈不上解锁");
        assertFalse(UnlockRules.requiresUnlock(-5, false));
    }

    @Test
    void expensiveDerivedItemsNeedUnlocking() {
        assertTrue(UnlockRules.requiresUnlock(UnlockRules.MIN_PRICE_TO_LOCK, false));
        assertTrue(UnlockRules.requiresUnlock(99999, false));
    }

    @Test
    void planDoesNotMutateItsInput() {
        Map<ResourceLocation, Integer> c = counts(id("a"), 64);
        Set<ResourceLocation> keys = Set.copyOf(c.keySet());
        plan(c, 64, x -> false);
        assertEquals(keys, c.keySet(), "计划只是只读统计，绝不该改动投放区的账");
        assertEquals(64, c.get(id("a")));
    }
}
