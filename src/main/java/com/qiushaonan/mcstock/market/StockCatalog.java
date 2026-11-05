package com.qiushaonan.mcstock.market;

import com.qiushaonan.mcstock.McStock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 三只股票的「展示层」信息：语言键、图标物品、风险档文案。
 *
 * <p>数值（基准价、波动率、风控常量）全在纯 Java 的 {@link StockDefs} 里 ——
 * 单测要能在没有 Minecraft 的情况下算这些东西，所以别把价格挪到这里来。
 */
public final class StockCatalog {

    private StockCatalog() {
    }

    /** 图标直接复用原版物品，省掉三张自制贴图；顺序必须与 {@link StockDefs#IDS} 一致。 */
    private static final Item[] ICONS = {Items.DIAMOND, Items.REDSTONE, Items.BLAZE_ROD};

    public static Item icon(int stock) {
        return ICONS[stock];
    }

    /** 企业名的语言键，例如 {@code gui.mcstock.stock.diamond_mine}。 */
    public static String nameKey(int stock) {
        return "gui.mcstock.stock." + StockDefs.IDS[stock];
    }

    /** 风险标签的语言键：稳健 / 波动 / 激进。 */
    public static String riskKey(int stock) {
        return switch (StockDefs.RISK[stock]) {
            case 0 -> "gui.mcstock.risk.low";
            case 1 -> "gui.mcstock.risk.mid";
            default -> "gui.mcstock.risk.high";
        };
    }

    /** 调试用：{@code mcstock:price_list} 之类的命令输出里显示 id。 */
    public static String id(int stock) {
        return McStock.MODID + ":" + StockDefs.IDS[stock];
    }

    /** 按 id 找索引，找不到返回 -1。 */
    public static int indexOf(String id) {
        for (int i = 0; i < StockDefs.IDS.length; i++) {
            if (StockDefs.IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
