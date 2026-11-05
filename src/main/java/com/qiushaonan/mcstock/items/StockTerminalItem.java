package com.qiushaonan.mcstock.items;

import com.qiushaonan.mcstock.market.StockService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 交易所终端：右键打开股市。
 *
 * <p>有两条入口，故意的：
 * <ol>
 *   <li><b>客户端输入拦截</b>（{@code client/StockTerminalInput}）取消右键并发包 —— 这是主路径，
 *       因为只有这样才能做到「对着箱子/工作台右键也开股市而不是开箱子」；</li>
 *   <li><b>服务端 {@link #use}</b> 兜底 —— 万一有别的路径绕过了客户端事件（自动点击、其它模组），
 *       服务端这一侧仍然能把界面打开。正常路径下不会被触发，所以不会重复开菜单。</li>
 * </ol>
 */
public class StockTerminalItem extends Item {

    public StockTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            StockService.openScreen(serverPlayer);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mcstock.stock_terminal.desc"));
    }
}
