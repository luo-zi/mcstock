package com.qiushaonan.mcstock.market;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件单的**纯逻辑**单测：触发判定、夹取、簿子增删/上限/读档收口、以及盘上 Codec 往返。
 *
 * <p>为什么这些必须有：条件单是「价格到线就替玩家动钱」的功能，判定写反或读档丢单都不会在
 * 编译期报错，只会在实机里变成「该成交没成交 / 重进游戏单没了」。纯 Java 类不依赖 Minecraft，
 * 所以能在没有 Bootstrap 的 JUnit 里直接跑真实实现。
 */
class ConditionalOrderTest {

    private static final long[] PRICES = {10_000L, 5_000L, 2_000L};

    private static ConditionalOrder order(int id, int stock, ConditionalOrder.Direction direction,
                                          long trigger, ConditionalOrder.Action action) {
        return new ConditionalOrder(id, stock, direction, trigger, action, 10, 5);
    }

    // ==================== 触发判定 ====================

    @Test
    void aboveTriggersAtOrAboveThePrice() {
        ConditionalOrder o = order(1, 0, ConditionalOrder.Direction.ABOVE, 10_500L,
                ConditionalOrder.Action.OPEN_LONG);
        assertFalse(o.triggeredBy(10_499L));
        assertTrue(o.triggeredBy(10_500L), "边界值算触发（含等号）");
        assertTrue(o.triggeredBy(12_000L), "跳空穿过触发价也算");
    }

    @Test
    void belowTriggersAtOrBelowThePrice() {
        ConditionalOrder o = order(2, 1, ConditionalOrder.Direction.BELOW, 4_800L,
                ConditionalOrder.Action.CLOSE_ALL);
        assertFalse(o.triggeredBy(4_801L));
        assertTrue(o.triggeredBy(4_800L));
        assertTrue(o.triggeredBy(3_000L));
    }

    @Test
    void invalidPricesNeverTrigger() {
        ConditionalOrder below = order(1, 0, ConditionalOrder.Direction.BELOW, 5_000L,
                ConditionalOrder.Action.OPEN_SHORT);
        // 0 价 = 引擎还没起来 / 畸形存档：这时不该把一堆 BELOW 单全打掉
        assertFalse(below.triggeredBy(0L));
        assertFalse(below.triggeredBy(-1L));
        ConditionalOrder broken = order(2, 0, ConditionalOrder.Direction.ABOVE, 0L,
                ConditionalOrder.Action.OPEN_LONG);
        assertFalse(broken.triggeredBy(100L));
        assertFalse(broken.valid());
    }

    @Test
    void directionAndActionOrdinalsFallBackSafely() {
        assertEquals(ConditionalOrder.Direction.ABOVE, ConditionalOrder.direction(-1));
        assertEquals(ConditionalOrder.Direction.BELOW, ConditionalOrder.direction(1));
        assertEquals(ConditionalOrder.Direction.ABOVE, ConditionalOrder.direction(99));
        assertEquals(ConditionalOrder.Action.OPEN_LONG, ConditionalOrder.action(-1));
        assertEquals(ConditionalOrder.Action.CLOSE_ALL, ConditionalOrder.action(2));
        assertEquals(ConditionalOrder.Action.OPEN_LONG, ConditionalOrder.action(99));
    }

    // ==================== 服务端夹取 ====================

    @Test
    void triggerIsClampedIntoTheStockPriceBand() {
        assertEquals(StockDefs.priceFloor(0), ConditionalOrder.clampTrigger(0, 1L));
        assertEquals(StockDefs.priceCeil(0), ConditionalOrder.clampTrigger(0, Long.MAX_VALUE));
        assertEquals(10_500L, ConditionalOrder.clampTrigger(0, 10_500L));
        assertEquals(0L, ConditionalOrder.clampTrigger(-1, 10_000L), "非法股票不建单");
        assertEquals(StockDefs.QTY_MAX, ConditionalOrder.clampQty(Integer.MAX_VALUE));
        assertEquals(StockDefs.QTY_MIN, ConditionalOrder.clampQty(0));
        assertEquals(StockDefs.LEVERAGE_MAX, ConditionalOrder.clampLeverage(999_999));
        assertEquals(StockDefs.LEVERAGE_MIN, ConditionalOrder.clampLeverage(-3));
    }

    // ==================== 簿子 ====================

    @Test
    void addClampsClientValuesAndAssignsUniqueIds() {
        ConditionalBook book = new ConditionalBook();
        ConditionalOrder first = book.add(0, 1, Long.MAX_VALUE, 99, 0, -7);
        assertNotNull(first);
        assertEquals(1, first.id());
        assertEquals(ConditionalOrder.Direction.BELOW, first.direction());
        assertEquals(StockDefs.priceCeil(0), first.triggerCents());
        assertEquals(ConditionalOrder.Action.OPEN_LONG, first.action());
        assertEquals(StockDefs.QTY_MIN, first.qty());
        assertEquals(StockDefs.LEVERAGE_MIN, first.leverage());
        ConditionalOrder second = book.add(2, 0, 2_000L, 2, 100, 10);
        assertNotNull(second);
        assertEquals(2, second.id(), "id 单调递增，撤单才不会撤错人");
        assertEquals(ConditionalOrder.Action.CLOSE_ALL, second.action());
        assertNull(book.add(-1, 0, 1_000L, 0, 1, 1), "非法股票不建单");
    }

    @Test
    void bookHasAHardLimitAndClearDoesNotReuseIds() {
        ConditionalBook book = new ConditionalBook();
        for (int i = 0; i < ConditionalBook.MAX_ORDERS; i++) {
            assertNotNull(book.add(0, 0, 10_000L, 0, 1, 1));
        }
        assertNull(book.add(0, 0, 10_000L, 0, 1, 1), "挂满要拒绝，不能留下客户端看不见的单");
        assertEquals(ConditionalBook.MAX_ORDERS, book.size());
        book.cancel(3);
        book.clear();
        assertTrue(book.isEmpty());
        assertNotNull(book.add(0, 0, 10_000L, 0, 1, 1));
        assertEquals(ConditionalBook.MAX_ORDERS + 1, book.byId(ConditionalBook.MAX_ORDERS + 1).id(),
                "clear 之后 id 仍然只增不减");
    }

    @Test
    void dueReturnsACopyAndOnlyMatchingOrders() {
        ConditionalBook book = new ConditionalBook();
        book.add(0, 0, 10_000L, 0, 1, 1);      // 0 号股 ≥ 100.00 → 触发
        book.add(1, 0, 5_000L, 0, 1, 1);       // 1 号股 ≥ 50.00 → 触发
        book.add(2, 0, 4_000L, 0, 1, 1);      // 2 号股 ≥ 40.00，现价只有 20.00 → 不触发
        book.add(1, 1, 6_000L, 1, 1, 1);      // 1 号股 ≤ 60.00 → 触发

        List<ConditionalOrder> due = book.due(PRICES);
        assertEquals(3, due.size());
        assertEquals(List.of(1, 2, 4), due.stream().map(ConditionalOrder::id).toList());
        // 返回值是拷贝：调用方摘单不会动到簿子（也不会 ConcurrentModificationException）
        due.clear();
        assertEquals(4, book.size());
    }

    @Test
    void restoreDropsMalformedDuplicatesAndKeepsTheIdGenerator() {
        ConditionalBook book = new ConditionalBook();
        ConditionalOrder good = order(7, 0, ConditionalOrder.Direction.ABOVE, 10_000L,
                ConditionalOrder.Action.OPEN_LONG);
        // ⚠️ 用 ArrayList 而不是 List.of(...)：存档里真的可能有 null 条目，List.of 会先抛 NPE
        List<ConditionalOrder> loaded = new java.util.ArrayList<>();
        loaded.add(good);
        loaded.add(order(7, 1, ConditionalOrder.Direction.BELOW, 5_000L,
                ConditionalOrder.Action.CLOSE_ALL));      // 重复 id：丢
        loaded.add(order(8, 99, ConditionalOrder.Direction.BELOW, 5_000L,
                ConditionalOrder.Action.CLOSE_ALL));     // 股票越界：丢
        loaded.add(order(9, 1, ConditionalOrder.Direction.BELOW, 0L,
                ConditionalOrder.Action.CLOSE_ALL));     // 触发价 0：丢
        loaded.add(null);                                     // null 条目：丢
        book.restore(loaded, 1);
        assertEquals(1, book.size());
        assertEquals(7, book.orders().get(0).id());
        assertEquals(8, book.nextId(), "发号器至少是最大 id + 1");
    }

    @Test
    void restoreTruncatesAtTheHardLimit() {
        ConditionalBook book = new ConditionalBook();
        List<ConditionalOrder> many = new java.util.ArrayList<>();
        for (int i = 1; i <= ConditionalBook.MAX_ORDERS + 5; i++) {
            many.add(order(i, 0, ConditionalOrder.Direction.ABOVE, 10_000L,
                    ConditionalOrder.Action.OPEN_LONG));
        }
        book.restore(many, 1);
        assertEquals(ConditionalBook.MAX_ORDERS, book.size());
    }

    // ==================== 存盘 Codec ====================

    @Test
    void snapshotRoundTripsThroughRealCodec() {
        ConditionalBook book = new ConditionalBook();
        book.add(0, 0, 10_500L, 0, 100, 5);
        book.add(1, 1, 4_500L, 2, 1, 1);

        ConditionalOrderSnapshot snapshot = ConditionalOrderSnapshot.of(book);
        var encoded = ConditionalOrderSnapshot.CODEC.encodeStart(JsonOps.INSTANCE, snapshot).getOrThrow();
        assertTrue(encoded.getAsJsonObject().has("orders"));
        assertTrue(encoded.getAsJsonObject().has("next_id"));

        ConditionalBook back = ConditionalOrderSnapshot.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow().toBook();
        assertEquals(book.size(), back.size());
        assertEquals(book.nextId(), back.nextId());
        for (int i = 0; i < book.size(); i++) {
            assertEquals(book.orders().get(i), back.orders().get(i));
        }
    }

    @Test
    void emptySnapshotIsUsable() {
        ConditionalBook book = ConditionalOrderSnapshot.EMPTY.toBook();
        assertTrue(book.isEmpty());
        assertEquals(1, book.nextId());
        assertNotNull(book.add(0, 0, 10_000L, 0, 1, 1));
    }
}
