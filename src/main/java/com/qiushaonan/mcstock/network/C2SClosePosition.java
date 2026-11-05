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
 * 客户端 → 服务端：平掉**某一个**仓（持仓列表行尾那个「平」按钮）。
 *
 * <p>玩家买入卖出都是**新增仓位**，所以「平仓」必须指名道姓 —— 一个 id 一个仓，
 * 同一只股票上的其它仓一个都不动。id 来自 {@code S2CStockSnapshot.posId}，
 * 是服务端自己发的句柄。
 *
 * <p>服务端权威（AGENTS.md 5.2）：id 是谁的仓、那一仓现在值多少钱、能不能平，
 * 全部由服务端拿自己那份持仓判定 —— 客户端发来的只是一个「我点了哪一行」的提示。
 * id 找不到就回一句「没有可平的持仓」，不做任何账目变动。
 *
 * <p>编码用 {@code StreamCodec.composite} 拼一个 VAR_INT：id 是玩家内自增的小整数，
 * VAR_INT 通常只要 1 字节。
 */
public record C2SClosePosition(int positionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SClosePosition> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "close_position"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SClosePosition> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, C2SClosePosition::positionId,
                    C2SClosePosition::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SClosePosition payload, IPayloadContext context) {
        // 只会是服务端收到（注册时已经限定 SERVERBOUND）
        if (context.player() instanceof ServerPlayer player) {
            // 网络线程 → 主线程：平仓要动玩家附件与现金
            context.enqueueWork(() -> StockService.closePosition(player, payload.positionId()));
        }
    }
}
