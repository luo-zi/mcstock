package com.qiushaonan.mcstock.items;

import com.qiushaonan.mcstock.McStock;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册。
 *
 * <p>注册字段名与资源 ID 一致，使用 {@code snake_case}。
 *
 * <p>阶段 1（GUI 规格定稿）里只有 {@code stock_terminal} 一个占位物品：
 * 它现在是个普通物品，右键逻辑、菜单、网络包都在阶段 3 接上。
 */
public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McStock.MODID);

    /** 交易所终端：右键打开股市（客户端输入拦截 + 服务端 use 双入口，见类注释）。 */
    public static final DeferredItem<Item> stock_terminal = ITEMS.register("stock_terminal",
            () -> new StockTerminalItem(new Item.Properties().stacksTo(1)));

    public static void reg(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
