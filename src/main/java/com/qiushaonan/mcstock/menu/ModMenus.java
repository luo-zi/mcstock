package com.qiushaonan.mcstock.menu;

import com.qiushaonan.mcstock.McStock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 容器菜单注册。
 *
 * <p>和项目里其它 holder 类一个套路：一个 {@link DeferredRegister}，字段名与资源 ID 一致，
 * 由 {@code McStock} 的主构造函数统一调 {@link #reg(IEventBus)} 挂到模组总线上。
 *
 * <p>只有**一个**菜单类型：回收页和购买页共用它（行情页没有容器槽）。
 * 三个页面靠客户端 {@code StockMenu#placeSlots(page, layout)} 摆槽位坐标来切换 ——
 * 切页不需要重开界面、也不需要第二个 {@code MenuType}。
 */
public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, McStock.MODID);

    /**
     * 股市界面（{@code mcstock:stock}）。
     *
     * <p>{@code FeatureFlags.DEFAULT_FLAGS} = 原版那一套功能开关（21.1 里它就等于
     * {@code VANILLA_SET}）：写了它，连原版功能开关都关掉的服务器上这个菜单会被自动禁用，
     * 不用自己再挑开关。构造器就是 {@link StockMenu#StockMenu(int, net.minecraft.world.entity.player.Inventory)}，
     * 签名必须严格是 {@code (int, Inventory)} —— {@link MenuType.MenuSupplier} 就是这么要求的，
     * 客户端收到「打开菜单」包时会用同一个构造器在本地建一份镜像菜单。
     */
    public static final Supplier<MenuType<StockMenu>> stock =
            MENUS.register("stock", () -> new MenuType<StockMenu>(StockMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ModMenus() {
    }

    public static void reg(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
