package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.menu.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 模组的客户端初始化入口。
 *
 * <p>目前只做一件事：把 {@link ModMenus#stock} 这个菜单类型绑到 {@link StockScreen}。
 * 没有这一步，服务端 {@code player.openMenu(...)} 下发的菜单在客户端**找不到界面工厂**，
 * 结果是「打开失败」或者直接崩在 {@code MenuScreens#create}。
 *
 * <p>为什么用 {@code @EventBusSubscriber(modid = ..., value = Dist.CLIENT)} 而不是主类里
 * {@code modEventBus.addListener(...)}：{@link RegisterMenuScreensEvent} 是
 * <b>模组总线</b>事件（{@code IModBusEvent}），而且**只在物理客户端触发**；
 * 用注解标 {@code Dist.CLIENT} 可以让服务端环境根本不加载这个类
 * （专用服务器上没有 {@link StockScreen} 依赖的一大堆仅客户端类）。
 *
 * <p>⚠️ 字段名是 {@link ModMenus#stock}（小写，和资源 ID 一致，见 AGENTS.md 5.1），
 * 类型是 {@code Supplier<MenuType<StockMenu>>} 而不是 {@code DeferredHolder} ——
 * 两者都能 {@code get()}，这里直接用 {@code get()} 拿菜单类型即可。
 */
@EventBusSubscriber(modid = McStock.MODID, value = Dist.CLIENT)
public final class McStockClient {

    private McStockClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        // get() 返回 MenuType<StockMenu>，满足 register(MenuType<? extends M>, ScreenConstructor<M, U>)：
        // M 推成 StockMenu，U 推成 StockScreen（AbstractContainerScreen 本身就 implements MenuAccess<T>）
        event.register(ModMenus.stock.get(), StockScreen::new);
    }
}
