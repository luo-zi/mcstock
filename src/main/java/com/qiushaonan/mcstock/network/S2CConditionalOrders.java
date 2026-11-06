package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import com.qiushaonan.mcstock.market.ConditionalBook;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：这个玩家当前挂着的**全部条件单**（整份替换，不是增量）。
 *
 * <p>为什么单独一个包而不塞进 {@link S2CStockSnapshot}：快照每 2 秒（轻量档每秒）都要推一次，
 * 里面光 K 线就占 720 个整数；条件单是「挂/撤/触发」才变的低频数据，单独发可以让快照的
 * 16 个分量保持不动，也省得为它在那个大 record 里再插一组数组（改了字段顺序就得换协议号）。
 * 服务端在**每次推快照时顺手带一份**（{@code StockService#sendSnapshot}），所以两端永远同帧。
 *
 * <p>⚠️ 线路上限故意比 {@link ConditionalBook#MAX_ORDERS} 宽松：解码异常在客户端 = 直接掉线，
 * 以后把上限从 8 调大时应该表现为「界面少显示几行」，不该表现为「一进服就被踢」。
 * 多出来的部分按声明长度读完再截断，流的位置由长度前缀保证不错位。
 */
public record S2CConditionalOrders(View[] orders) implements CustomPacketPayload {

    /** 协议里一张单的形状（枚举发序号，客户端用同一个纯 Java 枚举翻译）。 */
    public record View(int id, int stock, int direction, long triggerCents,
                       int action, int qty, int leverage) {
    }

    /** 线路上允许的最大条数（真实最多 {@link ConditionalBook#MAX_ORDERS} 条）。 */
    public static final int MAX_WIRE = ConditionalBook.MAX_ORDERS * 4;

    private static final View[] NO_ORDERS = new View[0];

    public static final CustomPacketPayload.Type<S2CConditionalOrders> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "conditional_orders"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CConditionalOrders> STREAM_CODEC =
            StreamCodec.of(S2CConditionalOrders::encode, S2CConditionalOrders::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CConditionalOrders p) {
        View[] orders = p.orders == null ? NO_ORDERS : p.orders;
        int count = Math.min(orders.length, MAX_WIRE);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            View v = orders[i];
            if (v == null) {
                // 占位也要把七个字段写满，否则后面的条目会整体错位
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeLong(0L);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                continue;
            }
            buf.writeVarInt(v.id());
            buf.writeVarInt(v.stock());
            buf.writeVarInt(v.direction());
            buf.writeLong(v.triggerCents());
            buf.writeVarInt(v.action());
            buf.writeVarInt(v.qty());
            buf.writeVarInt(v.leverage());
        }
    }

    private static S2CConditionalOrders decode(RegistryFriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(MAX_WIRE, buf.readVarInt()));
        View[] orders = count == 0 ? NO_ORDERS : new View[count];
        for (int i = 0; i < count; i++) {
            orders[i] = new View(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
        return new S2CConditionalOrders(orders);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CConditionalOrders payload, IPayloadContext context) {
        // 只会在客户端收到（注册时限定 CLIENTBOUND）；服务端永远不会执行到这个方法体
        context.enqueueWork(() -> ClientPacketSink.onConditionalOrders(payload));
    }
}
