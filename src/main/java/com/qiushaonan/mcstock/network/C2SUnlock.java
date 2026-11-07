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
 * 客户端 → 服务端：点解锁页的「解锁」按钮。
 *
 * <p>包体是空的，和 {@code C2SRecycleBar} 同一个理由：投放区就在服务端的
 * {@code StockMenu.bar()} 里，是原版容器槽同步上去的。客户端报「我想解锁这个物品」
 * 反而多一个伪造入口 —— 服务端自己扫一遍 18 格，认定什么、消耗什么、解锁什么全部由它算。
 *
 * <p>⚠️ 这一击**可能什么也不做**（不足一组、已经解锁、或者物品根本不需要解锁）——
 * 那是正常路径，不是错误：服务端会回一条 `message.mcstock.unlock.nothing`。
 */
public record C2SUnlock() implements CustomPacketPayload {
    public static final C2SUnlock INSTANCE = new C2SUnlock();

    public static final CustomPacketPayload.Type<C2SUnlock> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "unlock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SUnlock> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SUnlock payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> StockService.unlock(player));
        }
    }
}
