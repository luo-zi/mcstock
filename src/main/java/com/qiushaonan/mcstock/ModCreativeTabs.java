package com.qiushaonan.mcstock;

import com.qiushaonan.mcstock.items.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 创造模式物品栏。 */
public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, McStock.MODID);

    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.stock_terminal.get()))
                    .title(Component.translatable("creativetab.mcstock.main"))
                    .displayItems((parameters, output) -> output.accept(ModItems.stock_terminal))
                    .build());

    public static void reg(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}
