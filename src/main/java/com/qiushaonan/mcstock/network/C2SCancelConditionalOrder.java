package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.market.StockService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：撤掉**某一张**条件单（列表每行末尾那个「撤」）。
 *
 * <p>{@code orderId} 是服务端自己发的句柄（{@link S2CConditionalOrders.View#id()}），
 * 但**仍然要重新查一遍**：那张单可能刚刚触发、被别的设备撤掉，或者根本不是这个玩家的。
 * 找不到就回一句「没有这张单」，不做任何账目变动。
 */
public record C2SCancelConditionalOrder(int orderId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SCancelConditionalOrder> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "cancel_conditional_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SCancelConditionalOrder> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, C2SCancelConditionalOrder::orderId,
                    C2SCancelConditionalOrder::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SCancelConditionalOrder payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> StockService.cancelConditionalOrder(player, payload.orderId()));
        }
    }
}
