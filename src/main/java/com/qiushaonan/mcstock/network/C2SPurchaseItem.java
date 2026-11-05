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
 * 客户端 → 服务端：在采购页点一下格子买东西。
 *
 * @param item  买哪个物品（注册名，例如 {@code minecraft:cobblestone}）
 * @param count 买几个；<b>{@code count <= 0} 约定为「买 1 组」</b>（= 该物品的一组数量，
 *              可堆叠物品是 64，不可堆叠的是 1）。约定写成「非正数」而不是「64」，
 *              是为了让客户端不必知道服务端用哪套换算 —— 换算与定价都在
 *              {@link StockService#purchase(ServerPlayer, ResourceLocation, int)} 里。
 *
 * <p>物品用 {@link ResourceLocation} 而不是 {@code ItemStack}：服务端只认「注册名 + 数量」，
 * 收不到也不想收客户端拼出来的 NBT / 组件（否则就是「客户端说了算」）。物品存不存在、
 * 能不能买（在不在采购目录里）、钱够不够，全部由服务端查表判定。
 */
public record C2SPurchaseItem(ResourceLocation item, int count) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SPurchaseItem> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "purchase_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SPurchaseItem> STREAM_CODEC =
            StreamCodec.composite(
                    // ResourceLocation.STREAM_CODEC 是 StreamCodec<ByteBuf, ResourceLocation>，
                    // 泛型上可以当作 <? super RegistryFriendlyByteBuf, ResourceLocation> 用
                    ResourceLocation.STREAM_CODEC, C2SPurchaseItem::item,
                    ByteBufCodecs.VAR_INT, C2SPurchaseItem::count,
                    C2SPurchaseItem::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2SPurchaseItem payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // count 原样透传：正数 = 买几个，<= 0 = 买 1 组，判定收在 StockService 里一处
            context.enqueueWork(() -> StockService.purchase(player, payload.item(), payload.count()));
        }
    }
}
