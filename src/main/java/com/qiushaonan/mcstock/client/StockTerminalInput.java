package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.network.C2SOpenStock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 交易所终端的**客户端输入拦截**：拿着终端右键 → 开股市，而不是开箱子 / 用方块。
 *
 * <h2>为什么必须拦在客户端</h2>
 * <p>「对着箱子右键」在原版里是 <b>方块交互</b>：包先发给服务端，服务端直接
 * {@code ChestBlock#use} 把箱子打开，物品的 {@code Item#use} 根本不会被调用
 * （{@code useOn} 返回 PASS 也救不回来 —— 箱子自己就把事件消费了）。
 * 所以想让「对着任何方块右键都开股市」，唯一的入口就是**在客户端把这一下右键吃掉**。
 *
 * <h2>为什么用 {@link InputEvent.InteractionKeyMappingTriggered} 而不是原始按键监听</h2>
 * <p>它是原版 {@code Minecraft#startUseItem} 里由 {@code ClientHooks.onClickInput} 派发的事件：
 * <b>打空气 / 打方块 / 打生物三条路都会经过它</b>，而且带着 {@link InteractionHand} —— 一条路管完。
 * 换成 {@code PlayerInteractEvent.RightClickBlock} 之类的服务端事件就得同时照顾
 * 「空手点空气」和「副手使用」，还得在服务端取消，绕远且容易漏。
 *
 * <p>⚠️ <b>取消事件</b>（{@code setCanceled(true)}）是必须的：不取消的话，原版照样会
 * 继续走「对方块使用」那条路，结果是股市界面开着、箱子也开着。
 *
 * <p>⚠️ <b>渲染线程</b>：这是纯客户端事件，本来就在渲染/主线程上，直接发包是安全的
 * （{@code PacketDistributor.sendToServer} 内部会往网络层投递）。
 *
 * <p>服务端还有一条兜底（{@code StockTerminalItem#use}）：万一有别的路径绕过了这个事件
 * （自动点击器、其它模组代跑右键），服务端那一侧仍然能把界面开出来。
 */
@EventBusSubscriber(modid = McStock.MODID, value = Dist.CLIENT)
public final class StockTerminalInput {

    private StockTerminalInput() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        // 只看「使用物品」（右键）。左键是攻击、中键是选取方块，都不管
        if (!event.isUseItem()) {
            return;
        }
        // ⚠️ 只在**游戏世界**里处理：界面开着的时候（聊天、背包、死亡界面……）右键可能是
        // 别的界面的操作，这里一拦就会「在背包里点一下右键，股市莫名其妙弹出来」。
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) {
            return;
        }
        // 用事件给的手，而不是自己去猜主手/副手：事件对左右手各派发一次，
        // 每次的 getHand() 就是「这一下是这只手触发的」
        InteractionHand hand = event.getHand();
        Player player = mc.player;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.stock_terminal.get())) {
            return;
        }
        // 吃掉这一下：不再走对象 / 方块 / 实体的使用逻辑（对着箱子右键也只会开股市）
        event.setCanceled(true);
        // 手上会照常挥一下（event.setSwingHand(false) 可以关掉，但「挥一下」更接近原版右键的反馈）
        PacketDistributor.sendToServer(new C2SOpenStock());
    }
}
