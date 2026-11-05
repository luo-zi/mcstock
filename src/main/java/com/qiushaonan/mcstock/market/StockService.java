package com.qiushaonan.mcstock.market;

import com.qiushaonan.mcstock.ModAttachments;
import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.menu.RecycleRules;
import com.qiushaonan.mcstock.menu.StockMenu;
import com.qiushaonan.mcstock.network.S2CShopCatalog;
import com.qiushaonan.mcstock.network.S2CSlotValue;
import com.qiushaonan.mcstock.network.S2CStockSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端业务总入口：开界面、下单、回收、购买、强平、推快照。
 *
 * <p>所有判定都在这里做（客户端只能发「我点了哪个按钮」），金额内部一律用**分**。
 * 网络包只负责搬运，GUI 只负责画。
 */
public final class StockService {

    private StockService() {
    }

    /**
     * 待发提示：{@link #flash} 把本地化后的文本暂存在这里，下一次推快照时带出去、发完即清。
     *
     * <p>⚠️ 为什么不靠调用点接返回值：调用点有十几处，只要有一处写成
     * {@code flash(...); sendSnapshot(player, "");}（丢了返回值），那一条提示就永远不会显示 ——
     * 实机反馈「点了购买没反应」正是这么来的（`flash` 已经返回了「余额不够」，
     * 但快照里带的还是空串）。暂存表让「提示」和「推快照」解耦，少写一次也不会丢。
     */
    private static final Map<UUID, String> PENDING_FLASH = new ConcurrentHashMap<>();

    /** 收包/结算日志：购买与下单这类「点了没反应」的问题，只有日志能说清卡在哪一步。 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // ⚠️ 购买点差（回收价 / 采购价）**只有一处实现**：{@link ItemValuator#PURCHASE_MARKUP}
    //    （现在是 1.18）。这里原来还留着一份同名的 1.15 常量，从来没被用过，2026-10-06 调价时
    //    直接删掉 —— 两份「同一个口径」的常量迟早会对不上（本项目在「一份口径两处实现」上吃过亏）。

    // ==================== 账户读写 ====================

    public static long cash(ServerPlayer player) {
        Long value = player.getData(ModAttachments.MONEY.get());
        return value == null ? 0L : value;
    }

    /**
     * 写现金（分）。
     *
     * <p>⚠️⚠️ **不夹下限**（2026-10-06 起）：现金允许为负 = 玩家可以欠债，而且**没有负债上限**。
     * 这里曾经是 {@code Math.max(0L, cents)}，配合爆仓结算与 {@code TradeService} 里另外两处
     * 同样的夹取，效果是「穿仓亏空被系统免掉」。既然要允许负债，这三处必须一起去掉 ——
     * 只去掉一处的话，负现金会在下一次结算时被悄悄抹平，自动还债永远等不到 {@code cash < 0}。
     *
     * <p>UI 侧对负余额有明确表现：标题栏的「余额 / 净值」转成
     * {@code StockTheme.COLOR_LIQUIDATION}（红）并带负号。
     */
    public static void setCash(ServerPlayer player, long cents) {
        player.setData(ModAttachments.MONEY.get(), cents);
    }

    public static Portfolio portfolio(ServerPlayer player) {
        return player.getData(ModAttachments.PORTFOLIO.get()).toPortfolio();
    }

    public static void savePortfolio(ServerPlayer player, Portfolio portfolio) {
        player.setData(ModAttachments.PORTFOLIO.get(), PortfolioSnapshot.of(portfolio));
    }

    /** 净值 = 现金 + Σ(保证金 + 浮动盈亏)。 */
    public static long netWorth(ServerPlayer player, Portfolio portfolio, long[] prices) {
        return cash(player) + portfolio.totalMargin() + portfolio.totalPnl(prices);
    }

    // ==================== 界面 ====================

    /** 打开股市界面（客户端右键终端会走这里）。 */
    public static void openScreen(ServerPlayer player) {
        // ⚠️ StockMenu 的构造器必须是 (int, Inventory)（MenuType.MenuSupplier 只认这个），
        // 而 SimpleMenuProvider 要的是 (int, Inventory, Player) 三元 —— 用 lambda 把玩家参数丢掉。
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inventory, p) -> new StockMenu(id, inventory),
                Component.translatable("gui.mcstock.title")));
        sendSnapshot(player, "");
    }

    /** 关掉界面时由菜单调用，用来收尾（目前只是把回收栏里的东西还回去）。 */
    public static void onMenuClosed(ServerPlayer player) {
        sendSnapshot(player, "");
    }

    // ==================== 行情 tick ====================

    /**
     * 服务端每 tick：推进行情 → 逐玩家检查强平 → 给开着界面的玩家推快照。
     *
     * <p>⚠️ **别每 tick 推快照**：一个完整快照 ≈ 3 KB（阴阳柱占 2.2 KB），
     * 每 tick 推一次就是 ~60 KB/s/人。这里只在「刚跳完价」（带 K 线）或每秒一次（不带 K 线，
     * 客户端沿用上一份）时推。
     */
    public static void onServerTick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        StockMarketData market = StockMarketData.get(overworld);
        boolean advanced = market.engine().ticksToNext() <= 1;
        market.engine().tick();
        if (advanced) {
            // 刚跳完价：先把该爆的仓位爆掉（顺带把爆仓提示写进这次的快照），再推最新行情
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String flash = settleLiquidations(player, market.engine());
                if (player.containerMenu instanceof StockMenu) {
                    sendSnapshot(player, flash, true);
                }
            }
            market.setDirty();
        } else if (server.getTickCount() % 20 == 0) {
            // 每秒一次：只推状态（不带 K 线），倒计时才会动
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof StockMenu) {
                    sendSnapshot(player, "", false);
                }
            }
        }
    }

    /**
     * 逐仓检查爆仓：按跳价后的**真实价格**结算（可以亏穿保证金，**现金允许为负**）。
     *
     * <p>⭐ 逐仓判定：一只股票上有好几个仓时，只有真正到线的那几个会被清掉，
     * 同一只股票上其它仓、其它股票的仓一个都不动（{@code PositionMath.liquidated} 逐仓调用）。
     *
     * <p>⚠️ 结算出来的现金**不再夹到 0**（2026-10-06）：夹掉就等于系统替玩家免掉穿仓的亏空，
     * 而负债玩法（{@code StockService#autoSellToRepay}：负债时自动卖背包里的可回收物还债）
     * 正是建立在「现金可以是负数」上的。
     *
     * @return 写给 GUI 提示行的文本（没有爆仓就是空串）
     */
    private static String settleLiquidations(ServerPlayer player, MarketEngine engine) {
        Portfolio portfolio = portfolio(player);
        if (portfolio.isEmpty()) {
            return "";
        }
        long cash = cash(player);
        boolean changed = false;
        String message = "";
        // 遍历拷贝：liquidate 会把仓从 portfolio 里摘掉，不能拿活视图边遍历边改
        for (Position position : new ArrayList<>(portfolio.positions())) {
            long price = engine.price(position.stock());
            if (!position.liquidated(price)) {
                continue;
            }
            long delta = TradeService.liquidate(portfolio, position.id(), price);
            cash += delta;
            changed = true;
            // ⚠️ 余额 <= 0 那条的文案不能再写「账户归零」：现在打到负数就是**欠债**，
            //    所以它报的是结算后的真实余额（可能是 -45.00），而不是「刚好 0」。
            message = flash(player, cash <= 0
                            ? "message.mcstock.liquidated_wiped"
                            : "message.mcstock.liquidated",
                    Component.translatable(StockCatalog.nameKey(position.stock())),
                    cash <= 0 ? formatPrice(cash) : formatSigned(Math.min(0L, delta)));
        }
        if (changed) {
            setCash(player, cash);
            savePortfolio(player, portfolio);
        }
        return message;
    }

    // ==================== 背包口径（唯一的实现，别再各写一份） ====================

    /**
     * 玩家背包里**算数的那几格**：主背包 + 快捷栏（{@code 0..35}）与副手。
     *
     * <p>⚠️⚠️ 这里刻意**不用** {@code Inventory#getContainerSize()}：它返回 41 ——
     * 后 4 格是**盔甲**、最后 1 格是副手。踩过的坑（2026-10-06 修）：
     * 那时候「回收时同类一起卖」按 41 格扫，玩家在回收栏里放一块铁、身上正好穿着铁胸甲，
     * **胸甲会被一起卖掉**。所以「哪些格子算背包」全项目只留这一份实现。
     *
     * <p>⚠️ 现在的使用者只剩两条（{@code recycle} 已经**不再**用背包了，见它的 javadoc）：
     * 计数（{@link #countOf(ServerPlayer, Item)}）与负债自动还债（{@link #debtCandidates}）。
     */
    private static final int[] BACKPACK_SLOTS = backpackSlots();

    private static int[] backpackSlots() {
        int[] slots = new int[net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE + 1];
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            slots[i] = i;
        }
        slots[net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE] =
                net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
        return slots;
    }

    // ==================== 负债：自动卖回收物还债 ====================
    //
    // 玩家原话：「负债情况下挖到的可回收直接卖掉还款」。
    // 纯逻辑（挑哪些、卖几件、还多少）全在 market/DebtService 里（可单测），这里只做三件事：
    // ① 扫背包凑出候选；② 用「服务端才有」的信息当谓词（终端 / 耐久 / 估价）；③ 扣物品 + 记账 + 提示。

    /**
     * 这一秒还欠着钱的玩家（UUID）。
     *
     * <p>只用来判断「债务是不是**刚刚**还清」（还清那一刻要推一条**不同的**提示）。
     * 内存态就够了：它跟着「现金 < 0」这个事实走 —— 玩家下线时现金不会变，
     * 所以下次上线重新判定不会给出假提示。
     */
    private static final java.util.Set<UUID> IN_DEBT = ConcurrentHashMap.newKeySet();

    /** 每 {@link StockDefs#DEBT_CHECK_TICKS} tick 调一次（见 {@code StockEvents#onServerTick}）。 */
    public static void autoSellToRepayAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // ⚠️ 加固（2026-10-06）：**逐个玩家**兜底。一个玩家的坏状态（异常附件数据、
            //    别的模组换掉了他的背包实现…）不该让整场扫背包挂掉 ——
            //    更不该连续失败三次把整个行情停掉（见 StockEvents#onServerTick）。
            com.qiushaonan.mcstock.Errors.guard("StockService#autoSellToRepay",
                    "负债自动还债在某个玩家身上失败（跳过该玩家，下一次检查再试）",
                    () -> autoSellToRepay(player));
        }
    }

    /**
     * 负债时的自动还债：把背包里**有估价、无耐久**的东西按**回收价**卖掉，直到债务还清（停在 0 或正数）。
     *
     * <p>红线（照抄需求）：
     * <ul>
     *   <li>只在 {@code cash < 0} 时工作，还清立刻停；</li>
     *   <li>只扫**背包 36 格 + 副手**（{@code Inventory.INVENTORY_SIZE} 与 {@code SLOT_OFFHAND}）——
     *       ⚠️ 不能用 {@code getContainerSize()}：它是 41，后 5 格是**盔甲**，
     *       那样会把玩家身上穿着的胸甲/靴子当成库存卖掉；</li>
     *   <li>**有耐久的一律不卖**（镐子 / 剑 / 护甲）：1.21.1 的耐久是数据组件，
     *       {@code Item} 上没有 {@code getMaxDamage()}，判定只能问 {@code ItemStack}；</li>
     *   <li>交易所终端不卖；回收栏里的东西不碰（那是回收页的容器，玩家自己决定）。</li>
     * </ul>
     */
    public static void autoSellToRepay(ServerPlayer player) {
        UUID id = player.getUUID();
        long cash = cash(player);
        if (cash >= 0L) {
            // 不负债：一件都不卖。但「上一秒还在欠、这一秒不欠了」要给一条提示 ——
            // 玩家用回收页 / 平仓把钱还上也走这条（不只自动出售这一种还债方式）。
            if (IN_DEBT.remove(id)) {
                flash(player, "message.mcstock.debt.cleared", formatPrice(cash));
                pushIfScreenOpen(player);
            }
            return;
        }

        List<DebtService.Entry<Item>> entries = debtCandidates(player);
        DebtService.Result<Item> result = DebtService.sellToRepay(entries, cash,
                item -> ItemValuator.value(BuiltInRegistries.ITEM.getKey(item)),
                StockService::sellableWhileInDebt);

        if (result.soldAnything()) {
            for (DebtService.Sale<Item> sale : result.sales()) {
                ItemStack stack = player.getInventory().getItem(sale.slot());
                // 二次校验：同一 tick 里别的东西（拾取 / 丢弃 / 合成）可能刚动过这一格
                if (stack.isEmpty() || stack.getItem() != sale.key()) {
                    continue;
                }
                stack.shrink(sale.count());
                player.getInventory().setItem(sale.slot(), stack);
            }
            setCash(player, result.cashCents());
            if (result.settled()) {
                IN_DEBT.remove(id);
                flash(player, "message.mcstock.debt.cleared", formatPrice(result.cashCents()));
            } else {
                IN_DEBT.add(id);
                flash(player, "message.mcstock.debt.sold", result.soldItems(),
                        formatPrice(result.gainedCents()), formatPrice(result.debtCents()));
            }
            pushIfScreenOpen(player);
        } else {
            IN_DEBT.add(id);        // 背包里没东西可卖：继续欠着，下一秒再看
        }
    }

    /** 背包 36 格 + 副手，做成 {@link DebtService} 要的候选列表（空栈与空手跳过）。 */
    private static List<DebtService.Entry<Item>> debtCandidates(ServerPlayer player) {
        List<DebtService.Entry<Item>> out = new ArrayList<>();
        for (int slot : BACKPACK_SLOTS) {
            addCandidate(out, slot, player.getInventory().getItem(slot));
        }
        return out;
    }

    private static void addCandidate(List<DebtService.Entry<Item>> out, int slot, ItemStack stack) {
        if (!stack.isEmpty()) {
            out.add(new DebtService.Entry<>(slot, stack.getItem(), stack.getCount()));
        }
    }

    /**
     * 「这件东西负债时能不能自动卖」：交易所终端不行、**任何有耐久的物品**都不行（含全新未使用）。
     *
     * <p>⚠️ 这条**故意比手动回收严**：手动那条（{@code StockMenu.BarSlot#mayPlace} →
     * {@link RecycleRules#acceptsManual}）允许卖**未使用过**的装备 —— 玩家自己放进去、自己点按钮，
     * 一把新剑本身就等于一堆材料。而这里是**未经玩家同意**的变卖，
     * 玩家可能正穿着 / 正准备用那套装备 —— <b>宁可少还一点债，也不动玩家没用过的家当</b>。
     * 两条的**共同底线**是「用过的不能按新价卖」。
     * **改这条之前先想想另一边**，别再各写一份判据。
     *
     * <p>⚠️ 1.21.1 里最大耐久是**数据组件**（{@code Item#getMaxDamage()} 根本不存在），
     * 所以问的是它的默认实例 —— 这里判的是「这个**类型**有没有耐久条」，
     * 不是「手上这把还剩多少」，用默认实例是对的。
     */
    private static boolean sellableWhileInDebt(Item item) {
        return RecycleRules.acceptsAutoSell(item.getDefaultInstance().getMaxDamage(),
                item == ModItems.stock_terminal.get());
    }

    /** 玩家正开着股市界面时顺手推一份快照（余额与提示行当场更新；没开就只走动作栏）。 */
    private static void pushIfScreenOpen(ServerPlayer player) {
        if (player.containerMenu instanceof StockMenu) {
            sendSnapshot(player, "");
        }
    }

    // ==================== 下单 ====================

    /**
     * 买入 / 卖出：**都是新开一个仓**（买入 = 多仓、卖出 = 空仓），永不自动对冲、永不合并。
     *
     * <p>{@code qty <= 0} 保留旧约定 = 「平掉**这只股票的**全部仓」（「平 仓」按钮走这条）：
     * 它现在是一次遍历、逐个 {@code TradeService.close}。逐仓平仓用
     * {@link #closePosition(ServerPlayer, int)}（持仓列表每行那个「平」按钮）。
     */
    public static void trade(ServerPlayer player, int stock, int qty, int leverage, boolean buy) {
        if (stock < 0 || stock >= StockDefs.COUNT) {
            return;
        }
        if (qty <= 0) {
            closeAll(player, stock);
            return;
        }
        int clampedQty = StockDefs.clamp(qty, StockDefs.QTY_MIN, StockDefs.QTY_MAX);
        int clampedLeverage = StockDefs.clamp(leverage, StockDefs.LEVERAGE_MIN, StockDefs.LEVERAGE_MAX);
        StockMarketData market = StockMarketData.get(player.serverLevel());
        long price = market.engine().price(stock);
        Portfolio portfolio = portfolio(player);
        long before = cash(player);

        TradeService.Outcome out = TradeService.open(before, portfolio, stock, price,
                clampedQty, clampedLeverage, buy);
        switch (out.status()) {
            case OPENED -> {
                setCash(player, out.cashCents());
                savePortfolio(player, portfolio);
                flash(player, buy ? "message.mcstock.traded.open" : "message.mcstock.traded.short",
                        out.openedQty(), formatPrice(price), formatPrice(out.marginCents()),
                        formatPrice(out.feeCents()));
            }
            case NO_CASH -> flash(player, "message.mcstock.no_cash",
                    formatPrice(out.marginCents() + out.feeCents()), formatPrice(before));
            case FULL -> flash(player, "message.mcstock.pos_full", Portfolio.MAX_POSITIONS);
            case NO_POSITION, CLOSED -> {
                // TradeService.open 不会返回这两个状态
            }
            case BAD_QTY -> flash(player, "message.mcstock.bad_qty", StockDefs.QTY_MAX);
        }
        sendSnapshot(player, "");
    }

    /**
     * 市价**平掉这只股票的全部仓**（「平 仓」按钮）：逐个仓按它自己的均价结算。
     *
     * <p>没有持仓时只回一句提示，不做任何账目变动。
     */
    public static void closeAll(ServerPlayer player, int stock) {
        Portfolio portfolio = portfolio(player);
        List<Position> mine = portfolio.forStock(stock);
        if (mine.isEmpty()) {
            sendSnapshot(player, flash(player, "message.mcstock.no_position"));
            return;
        }
        StockMarketData market = StockMarketData.get(player.serverLevel());
        long price = market.engine().price(stock);
        long cash = cash(player);
        int closedShares = 0;
        long realized = 0;
        for (Position position : mine) {
            TradeService.Outcome out = TradeService.close(cash, portfolio, position.id(), price);
            if (out.status() != TradeService.Status.CLOSED) {
                continue;
            }
            cash = out.cashCents();
            closedShares += out.closedQty();
            realized += out.realizedPnlCents();
        }
        setCash(player, cash);
        savePortfolio(player, portfolio);
        sendSnapshot(player, flash(player, "message.mcstock.closed_all",
                closedShares, formatSigned(realized)));
    }

    /**
     * 平掉**某一个**仓（持仓列表行尾那个「平」按钮，{@code C2SClosePosition}）。
     *
     * <p>id 是服务端自己发的句柄，但**仍然要重新查一遍**：那一仓可能已经被爆掉、
     * 被「平 仓」一起平掉，或者根本不是这个玩家的（AGENTS.md 5.2：客户端说的只当提示）。
     */
    public static void closePosition(ServerPlayer player, int positionId) {
        Portfolio portfolio = portfolio(player);
        Position position = portfolio.byId(positionId);
        if (position == null) {
            sendSnapshot(player, flash(player, "message.mcstock.no_position"));
            return;
        }
        StockMarketData market = StockMarketData.get(player.serverLevel());
        long price = market.engine().price(position.stock());
        TradeService.Outcome out = TradeService.close(cash(player), portfolio, positionId, price);
        if (out.status() != TradeService.Status.CLOSED) {
            sendSnapshot(player, flash(player, "message.mcstock.no_position"));
            return;
        }
        setCash(player, out.cashCents());
        savePortfolio(player, portfolio);
        sendSnapshot(player, flash(player, "message.mcstock.traded.close",
                out.closedQty(), formatSigned(out.realizedPnlCents())));
    }

    // ==================== 回收 ====================

    /**
     * 回收：**只卖回收栏里那 18 格**，背包里的一件都不动。
     *
     * <p>⚠️⚠️ **2026-10-06 行为变更（玩家原话：「放进回收栏的才能卖啊」）**：
     * 以前这里会把**背包里同类**的堆一起卖掉（早期需求），现在**取消了** ——
     * 玩家 shift 进回收栏多少就卖多少，没进栏的东西绝不动。
     * <ul>
     *   <li>判定范围只有 {@code menu.bar()}（{@link StockMenu} 那 18 格）；</li>
     *   <li>估价为 0 的物品**留在栏里**（不静默吞掉玩家的东西），只统计条数报给玩家；</li>
     *   <li>{@code message.mcstock.recycle_partial} 的语义随之变成「回收栏里有 N 件不值钱、没换成钱」。</li>
     * </ul>
     *
     * <p>⚠️ **别和「负债自动还债」搞混**（{@link #autoSellToRepay}）：那条机制**仍然会扫背包**
     * （{@link #debtCandidates} 走 {@link #BACKPACK_SLOTS}），因为那是还债、不是玩家主动回收 ——
     * 玩家欠着钱时本来就不该靠"把东西留在包里"躲债。
     * 两条口径的区别就一句话：**回收 = 只看回收栏；自动还债 = 看背包。**
     */
    public static void recycle(ServerPlayer player) {
        if (!(player.containerMenu instanceof StockMenu menu)) {
            return;
        }
        var bar = menu.bar();
        Map<Item, Integer> kinds = new LinkedHashMap<>();
        for (int i = 0; i < bar.getContainerSize(); i++) {
            ItemStack stack = bar.getItem(i);
            if (!stack.isEmpty()) {
                kinds.merge(stack.getItem(), 1, Integer::sum);
            }
        }
        if (kinds.isEmpty()) {
            flash(player, "message.mcstock.recycle_nothing");
            sendSnapshot(player, "");
            return;
        }

        long gained = 0;
        int sold = 0;
        int worthless = 0;
        for (Item item : kinds.keySet()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            int unit = ItemValuator.value(id);
            if (unit <= 0) {
                // ⚠️ 只数**回收栏里**的不值钱件数（不再加 countOf(player, item)）：
                //    报出来的数要能让玩家对上自己放进栏里的量。
                worthless += countOf(bar, item);
                continue;
            }
            long amount = 0;
            for (int i = 0; i < bar.getContainerSize(); i++) {
                ItemStack stack = bar.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    amount += (long) unit * stack.getCount();
                    sold += stack.getCount();
                    bar.setItem(i, ItemStack.EMPTY);
                }
            }
            // ⚠️⚠️ 这里原来还有一段 `for (int slot : BACKPACK_SLOTS)`，把背包里同类的也一起卖掉 ——
            //    2026-10-06 按玩家要求**已删除**（「放进回收栏的才能卖啊」）。
            //    不要加回来：明确要求「只卖回收栏里的」。
            //    （负债自动还债扫背包是另一条机制，见 recycle 的 javadoc。）
            gained += amount * 100L;   // 金币 → 分
        }

        if (gained > 0) {
            setCash(player, cash(player) + gained);
            flash(player, "message.mcstock.recycled", sold, formatPrice(gained));
        } else {
            // ⚠️ 降级口径（同 purchase）：估值表整个不可用时说清原因，别让玩家以为东西不值钱。
            flash(player, com.qiushaonan.mcstock.StockEvents.valuesUnavailable()
                    ? "message.mcstock.values_unavailable"
                    : "message.mcstock.recycle_nothing");
        }
        if (worthless > 0 && gained > 0) {
            // 顺带提一句：不值钱的那几件**留在回收栏里**（不静默吞掉）
            player.displayClientMessage(Component.translatable("message.mcstock.recycle_partial", worthless), false);
        }
        bar.setChanged();
        sendSnapshot(player, "");
    }

    private static int countOf(net.minecraft.world.Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 同上，但只数**玩家背包算数的那几格**（主背包 / 快捷栏 / 副手，不含盔甲）。 */
    private static int countOf(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot : BACKPACK_SLOTS) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ==================== 购买 ====================

    /** 购买：价格 = 估价 × 1.18，物品直接进背包（塞不下掉脚下）。{@code count <= 0} 表示一组。 */
    public static void purchase(ServerPlayer player, ResourceLocation itemId, int count) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        long unit0 = ItemValuator.purchasePrice(itemId);
        LOGGER.debug("[mcstock] purchase 收到: item={} count={} 单价(分)={} 余额(分)={}",
                itemId, count, unit0 * 100L, cash(player));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            // 以前这里静默 return —— 客户端点了没反应、日志里也什么都看不到，最难查的一类问题
            LOGGER.warn("[mcstock] purchase 失败: 未知物品 {}", itemId);
            sendSnapshot(player, flash(player, "message.mcstock.purchase_unsellable"));
            return;
        }
        int unit = ItemValuator.purchasePrice(itemId);
        if (unit <= 0) {
            // ⚠️ 降级口径（2026-10-06 加固）：如果估值表整个没建起来（配方图/数据包在整合包里
            //    加载失败），「不可购买」会让玩家以为是自己钱不够或物品不对 ——
            //    这里直接说清是「估值暂不可用」，并给一条恢复提示。价格计算本身不受影响。
            flash(player, com.qiushaonan.mcstock.StockEvents.valuesUnavailable()
                    ? "message.mcstock.values_unavailable"
                    : "message.mcstock.purchase_unsellable");
            sendSnapshot(player, "");
            return;
        }
        int want = count <= 0 ? new ItemStack(item).getMaxStackSize() : count;
        want = Math.max(1, Math.min(4096, want));
        long price = (long) unit * 100L;              // 分
        long total = price * want;
        long money = cash(player);
        if (money < price) {
            flash(player, "message.mcstock.purchase_no_cash", formatPrice(price));
            sendSnapshot(player, "");
            return;
        }
        if (total > money) {
            want = (int) (money / price);             // 钱不够整组：按买得起的数量买
            total = price * want;
        }
        setCash(player, money - total);

        ItemStack stack = new ItemStack(item, want);
        int left = stack.getCount();
        player.getInventory().add(stack);
        left = stack.getCount();
        if (left > 0) {
            player.drop(stack, false);
            flash(player, "message.mcstock.inventory_full");
        }
        // 提示里带上**单价**与**总价**（玩家要求「另外写清楚单价和总价」）：
        // 「购买 石头 ×1（单价 2.00）→ -2.00 金币」。钱不够被砍量时 want 已经改过，
        // 所以两个数都按**实际成交**的量算，不会报成玩家最初想要的那个数。
        flash(player, "message.mcstock.purchased", item.getDescription(), want,
                formatPrice(price), formatPrice(total));
        sendSnapshot(player, "");
    }

    // ==================== 推送 ====================

    /** 组装并发送行情快照 + 回收栏合计（带 K 线，用在开界面/交易后这种一次性推送）。 */
    public static void sendSnapshot(ServerPlayer player, String flashText) {
        sendSnapshot(player, flashText, true);
    }

    /**
     * @param withCandles 是否带阴阳柱数据。不带时客户端沿用上一份（K 线只在跳价时变），
     *                    每秒一次的倒计时推送靠这个把包从 3 KB 压到 ~0.8 KB。
     */
    public static void sendSnapshot(ServerPlayer player, String flashText, boolean withCandles) {
        // 显式传了就用它；传空串时取「待发提示」（flash() 刚写进去的那条），取完即清
        String pendingFlash = (flashText == null || flashText.isEmpty())
                ? PENDING_FLASH.remove(player.getUUID())
                : flashText;
        StockMarketData market = StockMarketData.get(player.serverLevel());
        MarketEngine engine = market.engine();
        Portfolio portfolio = portfolio(player);
        long[] prices = engine.prices();

        // 持仓：五个等长数组（每仓一项）。浮盈 / 爆仓价**不发**，客户端拿 PositionMath 自己算
        List<Position> positions = portfolio.positions();
        int posCount = Math.min(positions.size(), S2CStockSnapshot.MAX_POSITIONS);
        int[] posId = new int[posCount];
        int[] posStock = new int[posCount];
        int[] posShares = new int[posCount];
        long[] posEntry = new long[posCount];
        long[] posMargin = new long[posCount];
        for (int i = 0; i < posCount; i++) {
            Position p = positions.get(i);
            posId[i] = p.id();
            posStock[i] = p.stock();
            posShares[i] = p.shares();
            posEntry[i] = p.entryCents();
            posMargin[i] = p.marginCents();
        }

        int[] changes = new int[StockDefs.COUNT];
        for (int i = 0; i < StockDefs.COUNT; i++) {
            long prev = engine.previousPrice(i);
            changes[i] = prev > 0 ? (int) Math.round((prices[i] - prev) * 1000.0 / prev) : 0;
        }

        int[] flat = new int[0];
        if (withCandles) {
            int[][] candles = engine.candles(StockDefs.CANDLE_MAX);
            flat = new int[StockDefs.COUNT * StockDefs.CANDLE_MAX * 4];
            for (int s = 0; s < StockDefs.COUNT; s++) {
                System.arraycopy(candles[s], 0, flat, s * StockDefs.CANDLE_MAX * 4,
                        Math.min(candles[s].length, StockDefs.CANDLE_MAX * 4));
            }
        }

        // 新闻流：整份推过去（最多 NEWS_KEEP 条，最新在最前）。
        // ⚠️ 发的是**语言键 + 结构化字段**，不是翻译好的文本 —— 理由见 S2CStockSnapshot 的类注释。
        //    年龄在这一刻算好（跳 → tick），客户端再叠加「本地经过了多少毫秒」做淡出与接替。
        List<MarketNews> feed = engine.newsFeed();
        S2CStockSnapshot.NewsItem[] news = new S2CStockSnapshot.NewsItem[feed.size()];
        for (int i = 0; i < feed.size(); i++) {
            MarketNews item = feed.get(i);
            news[i] = new S2CStockSnapshot.NewsItem(item.stock(), item.nameKey(), item.type().ordinal(),
                    item.permille(), item.ageTicks(engine.advances()), item.clarified());
        }

        S2CStockSnapshot payload = new S2CStockSnapshot(
                cash(player), netWorth(player, portfolio, prices),
                (engine.ticksToNext() + 19) / 20,
                toIntArray(prices), changes,
                posId, posStock, posShares, posEntry, posMargin,
                flat,
                engine.indexDeci(), engine.indexChangePermille(), engine.regime().ordinal(), news,
                pendingFlash == null ? "" : pendingFlash);
        PacketDistributor.sendToPlayer(player, payload);

        if (player.containerMenu instanceof StockMenu menu) {
            PacketDistributor.sendToPlayer(player, slotValueOf(menu));
        }
    }

    /**
     * 组一份回收栏行情：合计 + 种类 + **逐格单件价**。
     *
     * <p>⚠️⚠️ 逐格价是**后加的**（真机 bug：三种物品只要件数相同就显示同一个单价/小计）。
     * 客户端拿不到估值表，只能由这里把「每格的单件回收价（分）」一起推过去，
     * 明细才能按「该行单价 × 该行件数」逐行算 —— **均价不能当单价用**。
     * 空格给 0，长度固定 {@link StockMenu#BAR_SLOTS}。
     */
    private static S2CSlotValue slotValueOf(StockMenu menu) {
        int[] perSlot = new int[menu.bar().getContainerSize()];
        Map<Item, Boolean> kinds = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < menu.bar().getContainerSize(); i++) {
            ItemStack stack = menu.bar().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            kinds.put(stack.getItem(), true);
            int unit = ItemValuator.value(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (unit > 0) {
                perSlot[i] = unit * 100;                  // 金币 → 分（与 total 同口径）
                total += (long) unit * stack.getCount() * 100L;
            }
        }
        return S2CSlotValue.of(total, kinds.size(), perSlot);
    }

    /** 回收栏合计：返回 {金额（分）, 种类数}。 */
    public static long[] recycleTotals(StockMenu menu) {
        Map<Item, Boolean> kinds = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < menu.bar().getContainerSize(); i++) {
            ItemStack stack = menu.bar().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            kinds.put(stack.getItem(), true);
            int unit = ItemValuator.value(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (unit > 0) {
                total += (long) unit * stack.getCount() * 100L;
            }
        }
        return new long[]{total, kinds.size()};
    }

    /** 推一份可购买目录（登录时 + 数据包 reload 后）。 */
    public static void sendCatalog(ServerPlayer player) {
        List<ResourceLocation> items = new ArrayList<>();
        List<Integer> prices = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            int price = ItemValuator.purchasePrice(id);
            if (price > 0) {
                items.add(id);
                prices.add(price);
            }
        }
        // ⚠️ 按价格**升序**：购买页第一页必须是买得起的东西。
        // 原来排的是降序（想「第一页放硬货」），实机结果正好相反 —— 第一页全是下界合金装备/鞘翅这类
        // 几百上千金的货，玩家点一下就「余额不够」，看起来就是「购买页点了没反应」。
        Integer[] order = new Integer[items.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(prices.get(a), prices.get(b)));
        ResourceLocation[] ids = new ResourceLocation[order.length];
        int[] priceArr = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            ids[i] = items.get(order[i]);
            priceArr[i] = prices.get(order[i]);
        }

        // ⚠️ 硬上限（线上事故之后加的，2026-10-06）：目录**绝不允许无上限地塞进包**。
        //    超过 MAX_TOTAL_ENTRIES 就按价格从高到低截断（数组已按价格升序，所以保留末尾那段），
        //    只记一条 WARN（去重，免得每次登录都刷一遍）。
        int start = S2CShopCatalog.truncationStart(ids.length, S2CShopCatalog.MAX_TOTAL_ENTRIES);
        if (start > 0) {
            com.qiushaonan.mcstock.Errors.logOnce("StockService#sendCatalog#truncated",
                    "可购买目录过大：共 " + ids.length + " 条，只下发最贵的 " + S2CShopCatalog.MAX_TOTAL_ENTRIES
                            + " 条（其余物品仍然能回收，只是不在购买页里；"
                            + "要全部下发就得改成分页从服务端拉取）");
            ids = java.util.Arrays.copyOfRange(ids, start, ids.length);
            priceArr = java.util.Arrays.copyOfRange(priceArr, start, priceArr.length);
        }

        // ⚠️ 分包发送（同一次事故）：7799 条 → 8 片，每片 ≤ 1024 条。
        //    顺序发出、不等回执（NeoForge 保证同一连接内有序）；客户端累积到齐才替换目录。
        for (S2CShopCatalog part : S2CShopCatalog.split(ids, priceArr)) {
            PacketDistributor.sendToPlayer(player, part);
        }
    }

    // ==================== 小工具 ====================

    private static int[] toIntArray(long[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (int) values[i];
        }
        return out;
    }

    /**
     * 推一行提示：动作栏（服务端语言）+ 返回同一段文本给 GUI 提示行。
     * <p>包里的 flash 字段直接传本地化后的字符串，这样动作栏和界面提示行永远一致。
     */
    private static String flash(ServerPlayer player, String key, Object... args) {
        Component message = Component.translatable(key, args);
        player.displayClientMessage(message, true);
        String text = message.getString();
        PENDING_FLASH.put(player.getUUID(), text);
        return text;
    }

    /** 分 → 「100.00」这种两位小数文本（提示行用）。 */
    public static String formatPrice(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        return (negative ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    /** 带正负号的金额文本，用来显示盈亏。 */
    public static String formatSigned(long cents) {
        return (cents >= 0 ? "+" : "") + formatPrice(cents);
    }
}
