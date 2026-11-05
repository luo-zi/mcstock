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
 * 客户端 → 服务端：立刻给我一份**带阴阳柱**的完整快照。
 *
 * <p>为什么需要这个包：服务端为了省带宽，只在**跳价那一刻**推带 K 线的完整快照
 * （2 秒一次），其余每秒只推「状态」（不带 K 线，客户端沿用上一份）。
 * 于是玩家切页签、点另一家企业时，要等下一次跳价才能拿到新图 —— 实机反馈就是
 * 「点完企业/切完标签页要等挺久才出走势」。
 *
 * <p>所以客户端在这些「主动切换」的时机发一个空包，服务端**当场**回一份完整快照。
 * 这是纯读操作：没有任何状态改动，所以不需要额外的频率限制（每个包换 ~3 KB 下行，
 * 而玩家点页签的频率远低于每秒一次）。
 */
public record C2SRequestSnapshot() implements CustomPacketPayload {
    /** 空包的唯一实例。 */
    public static final C2SRequestSnapshot INSTANCE = new C2SRequestSnapshot();

    public static final CustomPacketPayload.Type<C2SRequestSnapshot> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "request_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestSnapshot> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SRequestSnapshot payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> StockService.sendSnapshot(player, ""));
        }
    }
}
