package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.market.StockService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：挂一张**条件单**（条件单覆盖层里那个「挂单」按钮）。
 *
 * <p>字段全是「我填了什么 / 点了哪个」的提示值：
 * <ul>
 *   <li>{@code stock} 选中的企业；{@code direction} 触发方向序号（0 = ≥ / 1 = ≤）</li>
 *   <li>{@code triggerCents} 触发价（**分**）；{@code action} 动作序号（0 买 / 1 卖 / 2 全平）</li>
 *   <li>{@code qty} 股数、{@code leverage} 杠杆 —— 只在开仓动作里用得上</li>
 * </ul>
 *
 * <p>⚠️ 服务端权威：这六个数全部在 {@code StockService#placeConditionalOrder} 里重算
 * （下标夹取、方向/动作序号兜底、触发价夹进该股票价格区间、数量/杠杆夹到现有档位、
 * 挂单上限、玩家是否在线），客户端一个数都不当事实（AGENTS.md 5.2）。
 *
 * <p>编码手写 {@link StreamCodec#of}：{@code StreamCodec.composite} 最多支持 6 个分量，
 * 这里正好 6 个但混了一个 {@code long}，手写循环更直白也更不容易在改字段时错位。
 */
public record C2SPlaceConditionalOrder(int stock, int direction, long triggerCents,
                                       int action, int qty, int leverage) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SPlaceConditionalOrder> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "place_conditional_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SPlaceConditionalOrder> STREAM_CODEC =
            StreamCodec.of(C2SPlaceConditionalOrder::encode, C2SPlaceConditionalOrder::decode);

    private static void encode(RegistryFriendlyByteBuf buf, C2SPlaceConditionalOrder p) {
        buf.writeVarInt(p.stock);
        buf.writeVarInt(p.direction);
        buf.writeLong(p.triggerCents);
        buf.writeVarInt(p.action);
        buf.writeVarInt(p.qty);
        buf.writeVarInt(p.leverage);
    }

    private static C2SPlaceConditionalOrder decode(RegistryFriendlyByteBuf buf) {
        return new C2SPlaceConditionalOrder(buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SPlaceConditionalOrder payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // 网络线程 → 主线程：要动玩家附件并推快照
            context.enqueueWork(() -> StockService.placeConditionalOrder(player,
                    payload.stock(), payload.direction(), payload.triggerCents(),
                    payload.action(), payload.qty(), payload.leverage()));
        }
    }
}
