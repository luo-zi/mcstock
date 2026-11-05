package com.qiushaonan.mcstock;

import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.menu.ModMenus;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * MC 股市 —— 主模组类。
 *
 * <p>玩法一句话：把背包里任何物品 shift 进回收栏换金币 → 行情页选数量与杠杆（最高 ×400）→
 * 赌 2 秒一跳的阴阳柱行情（做多/做空/爆仓）→ 购买页把赚到的钱换回物资。
 *
 * <p>本类只做一件事：把各个 holder 类的 {@code reg(eventBus)} 串起来。
 * 网络包由 {@code network/ModNetwork} 自己用 {@code @EventBusSubscriber} 注册，
 * 事件由 {@code StockEvents} 注册，都不在这里。
 */
@Mod(McStock.MODID)
public class McStock {
    /** 模组 ID，必须与 META-INF/neoforge.mods.toml 中的 modId 一致。 */
    public static final String MODID = "mcstock";

    public McStock(IEventBus modEventBus) {
        ModItems.reg(modEventBus);
        ModCreativeTabs.reg(modEventBus);
        ModAttachments.reg(modEventBus);
        ModMenus.reg(modEventBus);
    }

    /** 统一构造本模组的资源 ID（{@code mcstock:path}）。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
