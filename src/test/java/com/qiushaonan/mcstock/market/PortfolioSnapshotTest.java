package com.qiushaonan.mcstock.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持仓存盘：新格式往返 + **旧存档迁移**（纯 Java，用 JsonOps 跑真实 Codec）。
 *
 * <p>为什么这几条必须有：迁移写错的代价是「老玩家一进游戏仓位全没了」，
 * 而且只在真实存档上才看得见 —— 用同一个 Codec 走一遍 JSON 是最接近实机的验证方式。
 */
class PortfolioSnapshotTest {

    @Test
    void newFormatRoundTripsPositions() {
        Portfolio p = new Portfolio();
        p.addPosition(0, 100, 10_000L, 200_000L, 5);
        p.addPosition(0, -50, 12_000L, 300_000L, 2);      // 同一只股票的空仓
        p.addPosition(2, 7, 2_000L, 14_000L, 1);

        PortfolioSnapshot snapshot = PortfolioSnapshot.of(p);
        JsonElement json = PortfolioSnapshot.CODEC.encodeStart(JsonOps.INSTANCE, snapshot).getOrThrow();
        // 写盘必须是新格式：旧的三元组字段一个都不该再出现（否则以后分不清哪份是事实）
        JsonObject object = json.getAsJsonObject();
        assertTrue(object.has("positions"), "新格式的主字段是 positions");
        assertFalse(object.has("shares"), "旧字段只读不写");
        assertFalse(object.has("avg"));
        assertFalse(object.has("margin"));

        Portfolio back = PortfolioSnapshot.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().toPortfolio();
        assertEquals(p.size(), back.size());
        assertEquals(p.nextId(), back.nextId(), "发号器要跟着存，否则重进游戏会撞 id");
        for (int i = 0; i < p.size(); i++) {
            Position expected = p.positions().get(i);
            Position actual = back.positions().get(i);
            assertEquals(expected.id(), actual.id());
            assertEquals(expected.stock(), actual.stock());
            assertEquals(expected.shares(), actual.shares());
            assertEquals(expected.entryCents(), actual.entryCents());
            assertEquals(expected.marginCents(), actual.marginCents());
            assertEquals(expected.leverage(), actual.leverage());
        }
    }

    @Test
    void legacyTriplesMigrateToOnePositionPerNonEmptyStock() {
        // 旧存档的真实形状（每只股票一个净仓的三元组）
        JsonObject legacy = JsonParser.parseString(
                "{\"shares\":[100,0,-7],\"avg\":[10000,0,2000],\"margin\":[200000,0,14000]}")
                .getAsJsonObject();

        Portfolio portfolio = PortfolioSnapshot.CODEC.parse(JsonOps.INSTANCE, legacy)
                .getOrThrow().toPortfolio();

        assertEquals(2, portfolio.size(), "两个非空旧仓 → 两个新仓（0 股那个不建）");
        Position first = portfolio.positions().get(0);
        assertEquals(1, first.id(), "id 从 1 递增");
        assertEquals(0, first.stock());
        assertEquals(100, first.shares());
        assertEquals(10_000L, first.entryCents());
        assertEquals(200_000L, first.marginCents());
        assertEquals(0, first.leverage(), "旧存档没有杠杆 → 记 0（未知），界面上再反推显示");

        Position second = portfolio.positions().get(1);
        assertEquals(2, second.id());
        assertEquals(2, second.stock());
        assertEquals(-7, second.shares(), "空头是负数，迁移不该把它翻正");
        assertEquals(2_000L, second.entryCents());
        assertEquals(14_000L, second.marginCents());
        assertEquals(3, portfolio.nextId(), "发号器接在迁移出来的 id 后面");
    }

    @Test
    void emptyLegacySaveMigratesToEmptyPortfolio() {
        // 一分钱没花过的老玩家：三张全 0 的表（这就是旧版的默认值）
        JsonObject legacy = JsonParser.parseString(
                "{\"shares\":[0,0,0],\"avg\":[0,0,0],\"margin\":[0,0,0]}").getAsJsonObject();
        Portfolio portfolio = PortfolioSnapshot.CODEC.parse(JsonOps.INSTANCE, legacy)
                .getOrThrow().toPortfolio();
        assertTrue(portfolio.isEmpty());
        assertEquals(1, portfolio.nextId());
    }

    @Test
    void fromLegacySkipsEmptySlotsAndKeepsShortListsSafe() {
        Portfolio portfolio = Portfolio.fromLegacy(new int[]{0, 5}, new long[]{1L}, new long[]{});
        assertEquals(1, portfolio.size(), "短列表按 0 补齐，空仓跳过");
        Position position = portfolio.positions().get(0);
        assertEquals(1, position.stock());
        assertEquals(5, position.shares());
        assertEquals(0L, position.entryCents(), "缺的均价按 0 读，不能越界");
    }

    @Test
    void restoreSanitizesCorruptData() {
        Portfolio portfolio = new Portfolio();
        portfolio.restore(List.of(
                new Position(3, 0, 100, 10_000L, 200_000L, 5),
                new Position(3, 1, 50, 5_000L, 100_000L, 2),      // id 重复 → 丢弃
                new Position(4, 0, 0, 10_000L, 0L, 1),            // 0 股仓不存在 → 丢弃
                new Position(5, 99, 10, 10_000L, 1_000L, 1),      // 股票下标越界 → 丢弃
                new Position(9, 2, -20, 2_000L, 40_000L, 1)), 1);
        assertEquals(2, portfolio.size());
        assertEquals(3, portfolio.positions().get(0).id());
        assertEquals(9, portfolio.positions().get(1).id());
        assertEquals(10, portfolio.nextId(), "发号器至少是「最大 id + 1」，否则新仓会撞 id");
    }

    @Test
    void restoreDropsPositionsBeyondTheCap() {
        java.util.List<Position> many = new java.util.ArrayList<>();
        for (int i = 1; i <= Portfolio.MAX_POSITIONS + 5; i++) {
            many.add(new Position(i, 0, 1, 10_000L, 1_000L, 1));
        }
        Portfolio portfolio = new Portfolio();
        portfolio.restore(many, 1);
        assertEquals(Portfolio.MAX_POSITIONS, portfolio.size(),
                "超过上限的仓读不进来（否则客户端列表里既看不见也平不掉）");
        assertEquals(Portfolio.MAX_POSITIONS + 1, portfolio.nextId());
    }
}
