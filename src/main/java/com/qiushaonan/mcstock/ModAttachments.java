package com.qiushaonan.mcstock;

import com.mojang.serialization.Codec;
import com.qiushaonan.mcstock.market.ConditionalOrderSnapshot;
import com.qiushaonan.mcstock.market.PortfolioSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.Set;

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

    /** 集合的 Codec：按列表落盘，读回来重新装成不可变集合（顺序无意义）。 */
    private static final Codec<Set<ResourceLocation>> UNLOCKED_CODEC =
            Codec.list(ResourceLocation.CODEC).xmap(Set::copyOf, List::copyOf);

    /**
     * 已解锁的物品 id（「投放区交过一组」的凭证）。
     *
     * <p>存成**不可变的 {@link Set}**：写入一律用 {@code setData(新集合)} 整体替换，
     * 绝不在旧集合上就地改 —— 附件是 copyOnDeath 的，序列化拿到的必须是一份稳定的快照。
     * 规模是「这个人解锁过的物品数」，正常玩几百条，不会长到需要分页。
     *
     * <p>⚠️ 只存**显式解锁**的那些。有种子价的原版物品不算解锁（见
     * {@code UnlockRules#requiresUnlock}）—— 它们本来就不上锁，
     * 存进来只会让这份表从几百条涨到一千三百条，而且数据包一改就得跟着迁移。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Set<ResourceLocation>>> UNLOCKED =
            ATTACHMENT_TYPES.register("unlocked",
                    () -> AttachmentType.builder(() -> Set.<ResourceLocation>of())
                            .serialize(UNLOCKED_CODEC)
                            .copyOnDeath()
                            .build());

    /** 「物品 → 上次买它的游戏时刻（tick）」这张表的 Codec（读回来重新装成不可变 Map）。 */
    private static final Codec<java.util.Map<ResourceLocation, Long>> COOLDOWN_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.LONG)
                    .xmap(map -> java.util.Map.copyOf(map), map -> map);

    /**
     * 采购冷却：物品 id → 上次买它的**游戏时刻**（{@code level.getGameTime()}，单位 tick）。
     *
     * <p>为什么需要它：一件越贵的东西越不该被连着买 —— 冷却时长由买价推出来
     * （{@link com.qiushaonan.mcstock.market.ItemValuator#cooldownSeconds}），
     * 所以这里只存「什么时候买的」，时长每次现算，改价格不用迁移存档。
     *
     * <p>⚠️ <b>只记要冷却的那些</b>（时长 ≥ 1 分钟）：便宜物品的冷却等于没有，
     * 记进来只会让这张表无谓地长大。写入时顺手清掉已经过期的条目，
     * 所以表的规模是「最近买过的高价物品数」，稳定在几十条。
     *
     * <p>{@code copyOnDeath()}：冷却不该因为摔死就清零（那是拿命刷冷却）。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<java.util.Map<ResourceLocation, Long>>> PURCHASE_COOLDOWN =
            ATTACHMENT_TYPES.register("purchase_cooldown",
                    () -> AttachmentType.builder(() -> java.util.Map.<ResourceLocation, Long>of())
                            .serialize(COOLDOWN_CODEC)
                            .copyOnDeath()
                            .build());

    public static void reg(IEventBus eventBus) {
        ATTACHMENT_TYPES.register(eventBus);
    }
}

