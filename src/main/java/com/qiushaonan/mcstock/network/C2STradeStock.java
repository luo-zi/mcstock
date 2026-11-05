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
 * 客户端 → 服务端：点一次「买入 / 卖出」。
 *
 * <p>字段就是行情页右列那两行填写栏 + 两个主按钮：
 * <ul>
 *   <li>{@code stock} —— 选中的企业（0..2，越界由服务端丢掉这个包）</li>
 *   <li>{@code qty} —— **每次点击成交多少股**（不是手数、不是金额）；
 *       {@code qty <= 0} 是「平掉这只股票的**全部**仓」的约定（「平 仓」按钮走这条）</li>
 *   <li>{@code leverage} —— 杠杆倍数，保证金 = 仓位价值 ÷ 杠杆</li>
 *   <li>{@code buy} —— true = 买入（**新开一个多仓**）/ false = 卖出（**新开一个空仓**）</li>
 * </ul>
 *
 * <p>⚠️ 协议 v2 的语义变化：买卖**不再碰已有的反向仓**。以前「买 100 股」在持有空仓时会先平掉
 * 空仓、剩下的再开多；现在无论手里有什么，都是**再挂一个新仓上去**。逐仓平仓走
 * {@link C2SClosePosition}（持仓列表每行那个「平」按钮）。
 *
 * <p>客户端会把填写栏夹一次（省流量、少一次无用往返），但**服务端必须再夹一次**
 * （{@code QTY_MIN/QTY_MAX}、{@code LEVERAGE_MIN/LEVERAGE_MAX}）—— 客户端发来的数字
 * 只当提示，绝不能当成已校验的事实（AGENTS.md 5.2）。
 *
 * <p>编码用 {@code StreamCodec.composite} 拼三个 VAR_INT 一个 BOOL：字段都是「小而正」的整数，
 * VAR_INT 比 4 字节定长省一半以上；BOOL 是 1 字节。
 */
public record C2STradeStock(int stock, int qty, int leverage, boolean buy) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2STradeStock> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "trade_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2STradeStock> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, C2STradeStock::stock,
                    ByteBufCodecs.VAR_INT, C2STradeStock::qty,
                    ByteBufCodecs.VAR_INT, C2STradeStock::leverage,
                    ByteBufCodecs.BOOL, C2STradeStock::buy,
                    C2STradeStock::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(C2STradeStock payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> StockService.trade(
                    player, payload.stock(), payload.qty(), payload.leverage(), payload.buy()));
        }
    }
}
