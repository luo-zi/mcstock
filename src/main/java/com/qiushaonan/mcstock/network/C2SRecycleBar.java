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
 * 客户端 → 服务端：点「回收」按钮，把回收栏里的东西换成金币。
 *
 * <p>包体是空的，而且**故意不带物品清单**：回收栏就在服务端的 {@code StockMenu} 里，
 * 里面的 18 格是原版容器槽（{@code ClientboundContainerSetContentPacket} / {@code ...SetSlotPacket}）
 * 同步过来的，服务端读自己的那一份永远比客户端报的准。客户端报清单反而多一个「伪造估值」的入口。
 */
public record C2SRecycleBar() implements CustomPacketPayload {
    public static final C2SRecycleBar INSTANCE = new C2SRecycleBar();

    public static final CustomPacketPayload.Type<C2SRecycleBar> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "recycle_bar"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRecycleBar> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRecycleBar payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> StockService.recycle(player));
        }
    }
}
