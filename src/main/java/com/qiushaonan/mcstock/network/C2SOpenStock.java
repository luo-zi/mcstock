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
 * 客户端 → 服务端：打开股市界面（右键交易所终端）。
 *
 * <p>包体是空的：客户端**只说「我按了」**。手上拿的是不是终端、冷却、旁观者、
 * 该给哪个菜单，全部由服务端在 {@link StockService#openScreen(ServerPlayer)} 里判定 ——
 * 这样改玩法不用动网络协议，客户端也伪造不了「我拿着终端」。
 *
 * <p>注意：真正的「打开容器菜单」一定要走服务端（{@code player.openMenu(...)} 会下发
 * 菜单类型与 containerId）；客户端本地 {@code setScreen} 出来的界面是没有服务端容器的。
 */
public record C2SOpenStock() implements CustomPacketPayload {
    /** 空包的唯一实例：{@link StreamCodec#unit} 收发都用它，不额外占字节。 */
    public static final C2SOpenStock INSTANCE = new C2SOpenStock();

    public static final CustomPacketPayload.Type<C2SOpenStock> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "open_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SOpenStock> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SOpenStock payload, IPayloadContext context) {
        // 只会是服务端收到（注册时已经限定 SERVERBOUND），player() 就是发包的那个玩家
        if (context.player() instanceof ServerPlayer player) {
            // 网络线程 → 主线程：开菜单会碰世界与玩家状态，必须在主线程做
            context.enqueueWork(() -> StockService.openScreen(player));
        }
    }
}
