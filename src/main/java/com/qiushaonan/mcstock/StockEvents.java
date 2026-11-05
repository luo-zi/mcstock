package com.qiushaonan.mcstock;

import com.qiushaonan.mcstock.command.McStockCommand;
import com.qiushaonan.mcstock.market.AerialistService;
import com.qiushaonan.mcstock.market.ItemValueRules;
import com.qiushaonan.mcstock.market.ItemValuator;
import com.qiushaonan.mcstock.market.MinecraftRecipeGraph;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.market.StockService;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模组事件装配：行情 tick、登录推目录、启动/换数据包后重建估值表。
 *
 * <p>估值表（物品 → 金币）依赖**配方**，只能在服务端资源加载完之后重建，
 * 时机有两个：服务器启动（{@link ServerStartedEvent}）与 {@code /reload}
 * （{@link AddReloadListenerEvent} 注册的 JSON 监听器；这里在下次登录或下次启动时会重建）。
 *
 * <h2>⚠️ 每个入口都包在 {@link Errors#guard} 里（线上加固，2026-10-06）</h2>
 * 整合包里 {@code AddReloadListenerEvent} / {@code ServerStartedEvent} 是**一百多个模组共用**的
 * 生命周期节点：我们在这里抛一个异常，服务端初始化就会当场断掉，别人的模组跟着一起崩
 * （线上那份 FTB Quests 崩溃报告就是这个形态）。所以这里的规矩是：
 * <ul>
 *   <li>每个入口都 <b>try/catch 到 Throwable</b>（{@link Errors} 会把 OOM 一类重新抛出），
 *       异常**只打一次日志**，同类问题之后静默计数；</li>
 *   <li>出问题**优雅降级**：估值表建不起来 → 买卖给一句「估值暂不可用」；
 *       行情 tick 连续失败 → <b>停掉行情</b>而不是崩服（{@link #marketHalted}）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = McStock.MODID)
public final class StockEvents {

    private StockEvents() {
    }

    /** 估值表是否已建（登录时兜底，避免每个玩家都重算一遍）。 */
    private static volatile boolean valuesBuilt;

    /**
     * 估值表是不是「建不起来」（配方图或数据包整体失败）。
     *
     * <p>降级口径：这时购买 / 回收会明确告诉玩家「估值暂不可用」，而不是让玩家对着
     * 一堆「不值钱 / 买不了」发呆（{@link StockService} 会读这个标志）。
     * ⚠️ 它**只影响提示文案**，绝不影响任何价格计算 —— 价格仍然只由估值表决定。
     */
    private static volatile boolean valuesUnavailable;

    /** 行情 tick 连续失败次数；超过 {@link #MAX_TICK_FAILURES} 就停掉行情。 */
    private static int tickFailures;
    /** 行情已停（tick 连续失败太多次）。停了之后不再跳价，玩家界面照常能开、能看最后一帧。 */
    private static volatile boolean marketHalted;

    private static final int MAX_TICK_FAILURES = 3;

    /** 估值表当前是否不可用（给 {@link StockService} 的降级提示用）。 */
    public static boolean valuesUnavailable() {
        return valuesUnavailable;
    }

    /** 行情是否已经停掉（调试命令 / 诊断用）。 */
    public static boolean marketHalted() {
        return marketHalted;
    }

    /**
     * 行情 tick：跳价、强平、给开着界面的玩家推快照。
     *
     * <p>⚠️ 这是**唯一**每 tick 都跑的入口，也是最容易「一个坏数据把服务器刷崩」的地方
     * （每 40 tick 跳一次价、每 20 tick 扫一次负债）。所以：
     * 抛异常 → 记一次日志 → 计数；连续 {@value #MAX_TICK_FAILURES} 次就整体停掉行情。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (marketHalted) {
            return;
        }
        Boolean ok = Errors.guard("StockEvents#onServerTick", "行情 tick 出错（本次跳过）", () -> {
            StockService.onServerTick(event.getServer());
            // 负债自动还债：每 DEBT_CHECK_TICKS（20 tick = 1 秒）扫一次「还欠着钱」的玩家。
            // ⚠️ 复用同一个 ServerTickEvent.Post，**不新建事件类**；跳价是 2 秒一跳，
            //    1 秒的检查粒度意味着玩家挖到东西之后最多 1 秒就被卖掉，同时不必每 tick 扫背包。
            if (event.getServer().getTickCount() % StockDefs.DEBT_CHECK_TICKS == 0) {
                StockService.autoSellToRepayAll(event.getServer());
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
        if (Boolean.TRUE.equals(ok)) {
            tickFailures = 0;   // 跑通一次就清零：偶发失败不该累积成「停掉行情」
            return;
        }
        if (++tickFailures >= MAX_TICK_FAILURES) {
            marketHalted = true;
            Errors.error("行情 tick 连续失败 " + MAX_TICK_FAILURES + " 次，已停掉行情"
                    + "（服务端不受影响、界面照常能开，只是价格不再跳；重启服务器或重进即可恢复）", null);
        }
    }

    /** 注册调试命令 {@code /mcstock}（权限 2，见 {@link McStockCommand}）。 */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        Errors.guard("StockEvents#onRegisterCommands", "注册 /mcstock 命令失败（命令不可用，其余功能照常）",
                () -> McStockCommand.register(event.getDispatcher()));
    }

    /** 启动完成后建一次估值表（配方此时才可用）。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Errors.guard("StockEvents#onServerStarted", "服务端启动时重建估值表失败（买卖会提示估值暂不可用）",
                () -> rebuildValues(event.getServer().overworld()));
    }

    /**
     * 数据包 reload：注册估值 JSON 监听器；reload 完把已建标记清掉，下次登录重建。
     *
     * <p>⚠️ 这是**最敏感**的一个入口：{@code AddReloadListenerEvent} 的监听器列表是全局的，
     * 我们在这里抛异常会打断整个 reload 链（别人的监听器可能因此拿不到自己的数据 ——
     * 线上那份「FTB Quests 的 INSTANCE 为 null」正是「某个监听器没跑成功」的典型后果）。
     * 所以：注册失败只记一条日志，并让 {@link #valuesBuilt} 保持「未建」状态，下次登录再试。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        Errors.guard("StockEvents#onAddReloadListener", "注册估值 JSON 监听器失败（本次 reload 后估值可能不刷新）",
                () -> event.addListener(ItemValueRules.listener()));
        valuesBuilt = false;
        // 数据包换了，之前「建不起来」的结论要作废，下次登录重新试一遍
        valuesUnavailable = false;
    }

    /** 玩家登录：补一次估值表（若还没建）、发首次启动资金、推送可购买目录 + 当前行情。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Errors.guard("StockEvents#onLogin", "玩家登录初始化失败（界面/资金可能不完整，重连可恢复）", () -> {
            if (!valuesBuilt) {
                rebuildValues(player.serverLevel());
            }
            grantStartingMoney(player);
            // 目录**不在这里发**：登录时会先派发 OnDatapackSyncEvent，那边统一重建 + 分包推送，
            // 避免同一份 8 片的目录发两遍（也保证「目录一定是在数据包生效之后发的」）。
            StockService.sendSnapshot(player, "");
        });
    }

    /**
     * 数据包同步完成（登录时的首次同步 + {@code /reload} 之后）：重建估值表并**重推目录**。
     *
     * <p>为什么要接这个事件：{@link #onAddReloadListener} 只在 reload **开始前**注册监听器，
     * 那时配方表还没换；而 {@code /reload} 之后在线玩家手里的目录还是旧的（价格全变了）。
     * {@code OnDatapackSyncEvent} 正是「数据包已经生效」的那一刻，这里重建 + 重推最准。
     *
     * <p>⚠️ 同样包在护栏里：这是**每个玩家都会走到**的路径，抛异常会打断数据包同步。
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Errors.guard("StockEvents#onDatapackSync", "数据包同步后重建估值 / 重推目录失败（重进服务器即可）", () -> {
            java.util.List<ServerPlayer> players = event.getRelevantPlayers().toList();
            if (players.isEmpty()) {
                return;
            }
            // 估值表按「当前 RecipeManager」重建一次就够（全服共享），再逐玩家推目录
            rebuildValues(players.get(0).serverLevel());
            if (valuesUnavailable()) {
                return;   // 建不起来就别推残缺目录，让客户端继续用旧的那份
            }
            for (ServerPlayer player : players) {
                StockService.sendCatalog(player);
            }
        });
    }

    /**
     * **摔落高度记录**（「空中飞人」成就的前置）：必须在伤害结算**之前**拿到高度。
     *
     * <p>为什么不在死亡事件里直接读 {@code player.fallDistance}：落地那一瞬间它已经被重置成 0 了，
     * 读到 0 就永远判不出「百格」。这里把 {@code LivingFallEvent#getDistance()} 按 UUID 存进
     * {@link AerialistService} 的短生命周期缓存（覆盖 / 不够高就删 / 死亡即取即删 / 2 秒过期）。
     */
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // ⚠️ 摔落路径上一律不许抛：异常会让落地伤害结算直接断掉
        Errors.guard("StockEvents#onLivingFall", "记录摔落高度失败（只影响「空中飞人」成就）",
                () -> AerialistService.recordFall(player, event.getDistance()));
    }

    /**
     * **「空中飞人」结算**：负债 + 百格摔死 → 授成就 + 清债 + 提示（见 {@link AerialistService}）。
     *
     * <p>清债与授勋都放在死亡事件里做（先于重生复制附件，{@code MONEY} 是 {@code copyOnDeath}，
     * 所以重生后看到的就是 0）。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // ⚠️ 死亡路径上一律不许抛：异常会把死亡结算（掉落、重生、计分板…）全打断
        Errors.guard("StockEvents#onLivingDeath", "「空中飞人」成就结算失败（死亡与重生照常进行）",
                () -> AerialistService.onDeath(player, event.getSource()));
    }

    /**
     * 首次进入游戏的**启动资金**：没有它，购买页点了只会弹「余额不够」——
     * 玩家必须先捡一堆东西丢回收栏才能开始玩，上手门槛太高（实机反馈「购买不了」就是它）。
     *
     * <p>只发一次：用玩家 PersistentData 里的标记记住（原版就负责跨存档存这一份数据，
     * 不用为它再加一个附件类型）。注意**不能**用「余额为 0 就补发」来判断 ——
     * 那样输光了的玩家每次重进都会白拿一笔。
     */
    private static void grantStartingMoney(ServerPlayer player) {
        net.minecraft.nbt.CompoundTag data = player.getPersistentData();
        if (data.getBoolean(GRANTED_KEY)) {
            return;
        }
        data.putBoolean(GRANTED_KEY, true);
        StockService.setCash(player, StockService.cash(player) + STARTING_CENTS);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.mcstock.starting_money", StockService.formatPrice(STARTING_CENTS)), false);
    }

    /** 启动资金（分）：1000.00 金币 —— 够在稳健股上开一手 ×1，也能买几组便宜材料。 */
    private static final long STARTING_CENTS = 100_000L;
    private static final String GRANTED_KEY = "mcstock_starting_money_granted";

    /** 换维度/复活后重推一次（客户端状态可能已过期）。 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Errors.guard("StockEvents#onChangedDimension", "换维度后重推行情失败（界面重开一次即可）",
                () -> StockService.sendSnapshot(player, ""));
    }

    /**
     * 用当前估值规则 + 配方图重建估值表。
     *
     * <p>⚠️ 加固后**不抛异常**：任一步失败都把 {@link #valuesUnavailable} 置位并记一条日志，
     * 买卖走「估值暂不可用」的降级提示；重建成功则清掉这个标志。
     */
    public static void rebuildValues(ServerLevel level) {
        Boolean ok = Errors.guard("StockEvents#rebuildValues", "重建估值表失败（买卖会提示估值暂不可用）",
                () -> {
                    MinecraftRecipeGraph graph = MinecraftRecipeGraph.of(level);
                    Set<ResourceLocation> allItems = new HashSet<>();
                    for (Item item : BuiltInRegistries.ITEM) {
                        allItems.add(BuiltInRegistries.ITEM.getKey(item));
                    }
                    ItemValuator.rebuild(ItemValueRules.itemSeeds(), ItemValueRules.tagSeeds(),
                            tagMembers(), allItems, graph);
                    valuesBuilt = true;
                    return Boolean.TRUE;
                },
                Boolean.FALSE);
        valuesUnavailable = !Boolean.TRUE.equals(ok);
        if (valuesUnavailable) {
            valuesBuilt = false;   // 下次登录/启动再试
        }
    }

    /**
     * 把物品标签展开成「tag → 成员物品」。
     *
     * <p>估值规则里的 `#minecraft:logs` 这种条目要靠它落到具体物品上；
     * 放在这里而不是定价类里，是为了让定价类完全不碰注册表（方便单测）。
     *
     * <p>⚠️ 整体包一层护栏：{@code getTagNames()} 会遍历**所有模组**注册的标签，
     * 其中一个模组的标签坏了不该让我们连种子表都用不上（拿不到成员时那个标签条目就当没写）。
     */
    private static Map<ResourceLocation, Set<ResourceLocation>> tagMembers() {
        Map<ResourceLocation, Set<ResourceLocation>> out = new HashMap<>();
        Errors.guard("StockEvents#tagMembers", "展开物品标签失败（受影响的是带 # 的估值条目）", () -> {
            Registry<Item> registry = BuiltInRegistries.ITEM;
            registry.getTagNames().forEach(tagKey -> Errors.guard(
                    "StockEvents#tagMembers#" + tagKey.location(),
                    "展开物品标签 " + tagKey.location() + " 失败（该标签的估值条目按未写处理）",
                    () -> {
                        Optional<HolderSet.Named<Item>> named = registry.getTag((TagKey<Item>) tagKey);
                        named.ifPresent(holders -> {
                            Set<ResourceLocation> members = new HashSet<>();
                            holders.forEach(holder -> holder.unwrapKey()
                                    .ifPresent(key -> members.add(key.location())));
                            if (!members.isEmpty()) {
                                out.put(tagKey.location(), members);
                            }
                        });
                    }));
        });
        return out;
    }
}
