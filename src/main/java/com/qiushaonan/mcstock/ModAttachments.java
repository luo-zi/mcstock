package com.qiushaonan.mcstock;

import com.mojang.serialization.Codec;
import com.qiushaonan.mcstock.market.ConditionalOrderSnapshot;
import com.qiushaonan.mcstock.market.PortfolioSnapshot;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 玩家附件注册：现金与持仓。
 *
 * <p>两者都 {@code copyOnDeath()}：死亡不该把账户清空（倾家荡产要亏在行情上，不是亏在摔死上）。
 *
 * <p>持仓存的是 {@link PortfolioSnapshot}（**仓位列表** + 发号器）。⚠️ 它的 Codec 与迁移逻辑
 * 都放在 {@code market} 包里的那个纯 Java record 上，不在这里 ——
 * 附件类一 import NeoForge 就没法单测了，而「旧存档三元组要能读成仓位列表」恰恰是最需要单测的一条。
 */
public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, McStock.MODID);

    /** 现金（分）。0 分 = 一分钱没有。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> MONEY =
            ATTACHMENT_TYPES.register("money", () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG)
                    .copyOnDeath()
                    .build());

    /**
     * 持仓快照：一串互相独立的仓位（每仓自己的均价 / 保证金 / 杠杆）。
     *
     * <p>写盘走新格式（{@code positions} 列表）；读档时旧格式（每股票一个净仓的三元组）会被
     * {@link PortfolioSnapshot#CODEC} 自动迁移成仓位列表，不会读档丢数据。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PortfolioSnapshot>> PORTFOLIO =
            ATTACHMENT_TYPES.register("portfolio", () -> AttachmentType.builder(() -> PortfolioSnapshot.EMPTY)
                    .serialize(PortfolioSnapshot.CODEC)
                    .copyOnDeath()
                    .build());

    /**
     * 条件单簿：一串「价格到线就替玩家执行一次交易」的挂单。
     *
     * <p>和持仓一样 {@code copyOnDeath()}：挂在交易所里的单不该因为摔死就消失。
     * Codec 与畸形数据收口都放在 {@link ConditionalOrderSnapshot} / {@code ConditionalBook}
     * 这两个纯 Java 类里，附件类不掺任何玩法逻辑。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ConditionalOrderSnapshot>> CONDITIONAL_ORDERS =
            ATTACHMENT_TYPES.register("conditional_orders",
                    () -> AttachmentType.builder(() -> ConditionalOrderSnapshot.EMPTY)
                            .serialize(ConditionalOrderSnapshot.CODEC)
                            .copyOnDeath()
                            .build());

    public static void reg(IEventBus eventBus) {
        ATTACHMENT_TYPES.register(eventBus);
    }
}
