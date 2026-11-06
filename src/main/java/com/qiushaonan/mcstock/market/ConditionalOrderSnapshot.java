package com.qiushaonan.mcstock.market;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 条件单簿的**存盘快照**：挂单列表 + 发号器。纯 Java（只用 DataFixerUpper 的 Codec），
 * 所以能像 {@link PortfolioSnapshot} 那样脱离 Minecraft 直接用 {@code JsonOps} 单测。
 *
 * <p>{@code ModAttachments.CONDITIONAL_ORDERS} 存的就是它（{@code copyOnDeath} 保留：
 * 死亡不该把玩家挂的单清空）。
 *
 * <p>盘上形状：
 * <pre>
 *   { orders: [ {id:1, stock:0, direction:0, trigger:10500, action:0, qty:100, lev:5}, ... ],
 *     next_id: 2 }
 * </pre>
 * 方向与动作存的是**枚举序号**（不是名字）：序号有 {@code ConditionalOrder.direction(int)} /
 * {@code action(int)} 一处兜底，改名不会让老存档读挂，越界也不会抛异常。
 * {@code qty} / {@code lev} 可缺省（老存档补字段时默认 1），{@code next_id} 缺省补 1。
 */
public record ConditionalOrderSnapshot(List<ConditionalOrder> orders, int nextId) {

    /** 空簿子（附件默认值）。 */
    public static final ConditionalOrderSnapshot EMPTY = new ConditionalOrderSnapshot(List.of(), 1);

    private static final Codec<ConditionalOrder> ORDER_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("id").forGetter(ConditionalOrder::id),
            Codec.INT.fieldOf("stock").forGetter(ConditionalOrder::stock),
            Codec.INT.fieldOf("direction").forGetter(o -> o.direction().ordinal()),
            Codec.LONG.fieldOf("trigger").forGetter(ConditionalOrder::triggerCents),
            Codec.INT.fieldOf("action").forGetter(o -> o.action().ordinal()),
            Codec.INT.optionalFieldOf("qty", 1).forGetter(ConditionalOrder::qty),
            Codec.INT.optionalFieldOf("lev", 1).forGetter(ConditionalOrder::leverage)
    ).apply(inst, ConditionalOrderSnapshot::fromParts));

    /** 序号 → 枚举的兜底转换只在这一处做，读档与收包共用。 */
    private static ConditionalOrder fromParts(int id, int stock, int direction, long trigger,
                                               int action, int qty, int lev) {
        return new ConditionalOrder(id, stock, ConditionalOrder.direction(direction), trigger,
                ConditionalOrder.action(action), qty, lev);
    }

    public static final Codec<ConditionalOrderSnapshot> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ORDER_CODEC.listOf().fieldOf("orders").forGetter(ConditionalOrderSnapshot::orders),
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(ConditionalOrderSnapshot::nextId)
    ).apply(inst, ConditionalOrderSnapshot::new));

    public static ConditionalOrderSnapshot of(ConditionalBook book) {
        return new ConditionalOrderSnapshot(List.copyOf(book.orders()), book.nextId());
    }

    /** 还原成可操作的条件单簿（{@link ConditionalBook#restore} 会做畸形数据收口）。 */
    public ConditionalBook toBook() {
        ConditionalBook book = new ConditionalBook();
        book.restore(orders, nextId);
        return book;
    }
}
