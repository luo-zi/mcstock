package com.qiushaonan.mcstock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个玩家的**条件单簿**：一串互相独立、一次性触发的挂单（纯 Java，不依赖 Minecraft）。
 *
 * <p>与 {@link Portfolio} 同一个形状：{@code id} 在玩家内唯一、由单调递增的 {@link #nextId} 发号，
 * 存盘时把发号器一起带上，否则重进游戏后新单会和老单撞 id、撤单撤错人。
 *
 * <p>上限 {@link #MAX_ORDERS} 是**服务端硬约束**：它既是挂单被拒的阈值，也是快照包最多带几条、
 * 读档时最多留几条。客户端必须能看到并撤掉自己的每一张单，悄悄多留几张看不见的单比拒绝挂单更糟。
 */
public final class ConditionalBook {

    /** 一个玩家最多同时挂几张条件单（与 {@code S2CConditionalOrders} 的线路上限同源）。 */
    public static final int MAX_ORDERS = 8;

    private final List<ConditionalOrder> orders = new ArrayList<>();
    private int nextId = 1;

    /** 全部挂单（只读视图，顺序 = 挂单顺序）。 */
    public List<ConditionalOrder> orders() {
        return Collections.unmodifiableList(orders);
    }

    public int size() {
        return orders.size();
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public int nextId() {
        return nextId;
    }

    public ConditionalOrder byId(int id) {
        for (ConditionalOrder order : orders) {
            if (order.id() == id) {
                return order;
            }
        }
        return null;
    }

    /**
     * 挂一张单。**所有客户端给的数值都在这里夹一次**（服务端权威，见 {@link ConditionalOrder} 类注释）。
     *
     * @return 新建的订单；参数非法或已经挂满返回 {@code null}（调用方负责给提示）
     */
    public ConditionalOrder add(int stock, int directionOrdinal, long triggerCents,
                                 int actionOrdinal, int qty, int leverage) {
        if (orders.size() >= MAX_ORDERS
                || stock < 0 || stock >= StockDefs.COUNT) {
            return null;
        }
        long trigger = ConditionalOrder.clampTrigger(stock, triggerCents);
        if (trigger <= 0L) {
            return null;
        }
        ConditionalOrder order = new ConditionalOrder(
                nextId++,
                stock,
                ConditionalOrder.direction(directionOrdinal),
                trigger,
                ConditionalOrder.action(actionOrdinal),
                ConditionalOrder.clampQty(qty),
                ConditionalOrder.clampLeverage(leverage));
        orders.add(order);
        return order;
    }

    /** 撤掉一张单，返回被撤掉的那张；id 不存在返回 {@code null}。 */
    public ConditionalOrder cancel(int id) {
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id() == id) {
                return orders.remove(i);
            }
        }
        return null;
    }

    /**
     * 撤掉全部挂单。**发号器不复位**：id 只增不减，否则「刚撤掉 #3、下一张又是 #3」会让
     * 客户端的延迟撤单包打到一张完全不相干的新单上。
     */
    public void clear() {
        orders.clear();
    }

    /**
     * 这一跳有哪些单该触发（纯函数，不修改簿子）。
     *
     * <p>⚠️ 返回的是**拷贝**：调用方会边遍历边从簿子里摘单并改现金/持仓，拿活视图改会
     * {@code ConcurrentModificationException}（持仓强平那段踩过同一个坑）。
     *
     * @param prices 按股票下标取现价（分）
     */
    public List<ConditionalOrder> due(long[] prices) {
        List<ConditionalOrder> out = new ArrayList<>();
        for (ConditionalOrder order : orders) {
            long price = order.stock() >= 0 && order.stock() < prices.length
                    ? prices[order.stock()] : 0L;
            if (order.triggeredBy(price)) {
                out.add(order);
            }
        }
        return out;
    }

    /**
     * 从存档恢复：整份替换，并做与 {@link Portfolio#restore} 同款的防御性收口。
     * <ol>
     *   <li>丢掉 {@link ConditionalOrder#valid()} 判不过的条目；</li>
     *   <li>id 重复的只留第一个；</li>
     *   <li>最多留 {@link #MAX_ORDERS} 张；</li>
     *   <li>发号器至少是「最大 id + 1」。</li>
     * </ol>
     */
    public void restore(List<ConditionalOrder> loaded, int nextIdValue) {
        orders.clear();
        nextId = Math.max(1, nextIdValue);
        if (loaded == null) {
            return;
        }
        for (ConditionalOrder order : loaded) {
            if (order == null || !order.valid() || orders.size() >= MAX_ORDERS) {
                continue;
            }
            if (byId(order.id()) != null) {
                continue;
            }
            orders.add(order);
            nextId = Math.max(nextId, order.id() + 1);
        }
    }

    /** 存盘快照。 */
    public ConditionalOrderSnapshot snapshot() {
        return ConditionalOrderSnapshot.of(this);
    }
}
