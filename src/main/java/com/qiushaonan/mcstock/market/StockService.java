package com.qiushaonan.mcstock.market;

import com.qiushaonan.mcstock.ModAttachments;
import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.menu.RecycleRules;
import com.qiushaonan.mcstock.menu.StockMenu;
import com.qiushaonan.mcstock.network.S2CConditionalOrders;
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

    /** 玩家的条件单簿（从附件还原，畸形存档在 {@link ConditionalBook#restore} 里被收口）。 */
    public static ConditionalBook conditionalBook(ServerPlayer player) {
        return player.getData(ModAttachments.CONDITIONAL_ORDERS.get()).toBook();
    }

    public static void saveConditionalBook(ServerPlayer player, ConditionalBook book) {
        player.setData(ModAttachments.CONDITIONAL_ORDERS.get(), book.snapshot());
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
        // 已解锁表**跟着界面走**：进界面推一次，之后只在解锁成功时推（低频数据，
        // 绝不能塞进每 2 秒一次的行情快照里 —— 那会让每次跳价都多背几百个物品 id）
        sendUnlocked(player);
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
            // 刚跳完价：① 先把该爆的仓位爆掉 → ② 再判定条件单（顺序要紧：爆仓会改现金与持仓，
            // 条件单必须按「爆完之后的真实账户」成交）→ ③ 推最新行情 + 最新条件单簿
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String flash = settleLiquidations(player, market.engine());
                String condFlash = settleConditionalOrders(player, market.engine());
                if (!condFlash.isEmpty()) {
                    flash = condFlash;      // 触发是更晚、更主动的事件，提示以它为准
                }
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

    // ==================== 条件单 ====================
    //
    // 纯数据（订单形状 / 触发判定 / 夹取）在 ConditionalOrder 与 ConditionalBook 里，能直接单测；
    // 这里只做「读服务端账户 → 判定 → 按现价成交 → 记账 + 提示」这条 Minecraft 侧链路。
    // 客户端发来的六个字段一律只当提示值（AGENTS.md 5.2）。

    /**
     * 挂一张条件单（{@code C2SPlaceConditionalOrder}）。
     *
     * <p>⚠️ 挂单**只登记条件，不冻结任何钱**：保证金与手续费在触发那一刻按当时的现价、
     * 当时的余额检查（见 {@link #executeConditionalOrder}）。这样不引入「预留资金」这套第二账本，
     * 也不会出现「挂了 8 张单之后余额看起来少了但没成交」的怪现象。
     */
    public static void placeConditionalOrder(ServerPlayer player, int stock, int directionOrdinal,
                                              long triggerCents, int actionOrdinal,
                                              int qty, int leverage) {
        if (stock < 0 || stock >= StockDefs.COUNT || triggerCents <= 0L) {
            flash(player, "message.mcstock.cond.bad");
            sendSnapshot(player, "");
            return;
        }
        ConditionalBook book = conditionalBook(player);
        ConditionalOrder order = book.add(stock, directionOrdinal, triggerCents, actionOrdinal, qty, leverage);
        if (order == null) {
            flash(player, "message.mcstock.cond.full", ConditionalBook.MAX_ORDERS);
            sendSnapshot(player, "");
            return;
        }
        saveConditionalBook(player, book);
        flash(player, "message.mcstock.cond.placed", order.id(),
                Component.translatable(StockCatalog.nameKey(order.stock())),
                order.direction() == ConditionalOrder.Direction.ABOVE ? "≥" : "≤",
                formatPrice(order.triggerCents()),
                Component.translatable(ConditionalOrder.actionKey(order.action())));
        sendSnapshot(player, "");
    }

    /** 撤掉一张条件单（{@code C2SCancelConditionalOrder}）。 */
    public static void cancelConditionalOrder(ServerPlayer player, int orderId) {
        ConditionalBook book = conditionalBook(player);
        if (book.cancel(orderId) == null) {
            flash(player, "message.mcstock.cond.not_found", orderId);
        } else {
            saveConditionalBook(player, book);
            flash(player, "message.mcstock.cond.cancelled", orderId);
        }
        sendSnapshot(player, "");
    }

    /** 撤掉全部条件单（调试命令 {@code /mcstock cond clear}）。 */
    public static void clearConditionalOrders(ServerPlayer player) {
        ConditionalBook book = conditionalBook(player);
        int count = book.size();
        book.clear();
        saveConditionalBook(player, book);
        flash(player, "message.mcstock.cond.cleared", count);
        sendSnapshot(player, "");
    }

    /**
     * 一次跳价之后：**判定并执行**所有到线的条件单。
     *
     * <p>⚠️ 只在价格真的推进过时调（{@link #onServerTick} 的 {@code advanced} 分支，
     * 以及调试命令改价/跳价后），挂单当下不判定 —— 否则「挂单」就变成「市价单」了。
     * 每张触发的单**无论成交与否都会摘掉**（一次性），避免余额不足时每 2 秒刷一次失败提示。
     *
     * @return 写给 GUI 提示行的文本（一张都没触发就是空串）
     */
    public static String settleConditionalOrders(ServerPlayer player, MarketEngine engine) {
        ConditionalBook book = conditionalBook(player);
        if (book.isEmpty()) {
            return "";
        }
        long[] prices = engine.prices();
        // due() 返回拷贝：下面会边遍历边从 book 里摘单，不能拿活视图改
        List<ConditionalOrder> due = book.due(prices);
        if (due.isEmpty()) {
            return "";
        }
        String message = "";
        for (ConditionalOrder order : due) {
            book.cancel(order.id());
            Component result = executeConditionalOrder(player, order, prices[order.stock()]);
            message = flash(player, "message.mcstock.cond.triggered", order.id(),
                    Component.translatable(StockCatalog.nameKey(order.stock())),
                    formatPrice(prices[order.stock()]), result);
        }
        saveConditionalBook(player, book);
        return message;
    }

    /**
     * 按**跳价后的真实现价**执行一张已触发的单，并返回一行结果文案。
     *
     * <p>开仓走 {@link TradeService#open}（同一个手续费/保证金口径，绝不在这里另算一遍）；
     * 全平走 {@link #closeAllAt}（与「平 仓」按钮共用的纯结算）。
     */
    private static Component executeConditionalOrder(ServerPlayer player, ConditionalOrder order, long price) {
        Portfolio portfolio = portfolio(player);
        switch (order.action()) {
            case OPEN_LONG, OPEN_SHORT -> {
                boolean buy = order.action() == ConditionalOrder.Action.OPEN_LONG;
                TradeService.Outcome out = TradeService.open(cash(player), portfolio,
                        order.stock(), price, order.qty(), order.leverage(), buy);
                switch (out.status()) {
                    case OPENED -> {
                        setCash(player, out.cashCents());
                        savePortfolio(player, portfolio);
                        return Component.translatable("message.mcstock.cond.opened",
                                Component.translatable(buy ? "gui.mcstock.btn.buy" : "gui.mcstock.btn.sell"),
                                out.openedQty(), formatPrice(price));
                    }
                    case NO_CASH -> {
                        return Component.translatable("message.mcstock.cond.no_cash",
                                formatPrice(out.marginCents() + out.feeCents()));
                    }
                    case FULL -> {
                        return Component.translatable("message.mcstock.cond.pos_full", Portfolio.MAX_POSITIONS);
                    }
                    default -> {
                        return Component.translatable("message.mcstock.cond.failed");
                    }
                }
            }
            case CLOSE_ALL -> {
                if (portfolio.forStock(order.stock()).isEmpty()) {
                    return Component.translatable("message.mcstock.cond.no_position");
                }
                CloseAllResult closed = closeAllAt(cash(player), portfolio, order.stock(), price);
                setCash(player, closed.cashCents());
                savePortfolio(player, portfolio);
                return Component.translatable("message.mcstock.cond.closed",
                        closed.closedShares(), formatSigned(closed.realizedPnlCents()));
            }
            default -> {
                return Component.translatable("message.mcstock.cond.failed");
            }
        }
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
        CloseAllResult result = closeAllAt(cash(player), portfolio, stock, price);
        setCash(player, result.cashCents());
        savePortfolio(player, portfolio);
        sendSnapshot(player, flash(player, "message.mcstock.closed_all",
                result.closedShares(), formatSigned(result.realizedPnlCents())));
    }

    /**
     * 「平掉这只股票的全部仓」的**纯结算**（只改传入的 {@link Portfolio} + 返回新现金，不发提示、
     * 不推包、不写附件）：市价「平 仓」按钮与条件单的 {@code CLOSE_ALL} 动作共用这一份实现，
     * 免得两处各写一遍逐仓结算、以后手续费口径改漏一处。
     */
    private record CloseAllResult(long cashCents, int closedShares, long realizedPnlCents) {
    }

    private static CloseAllResult closeAllAt(long cashCents, Portfolio portfolio, int stock, long priceCents) {
        long cash = cashCents;
        int shares = 0;
        long realized = 0L;
        for (Position position : portfolio.forStock(stock)) {
            TradeService.Outcome out = TradeService.close(cash, portfolio, position.id(), priceCents);
            if (out.status() != TradeService.Status.CLOSED) {
                continue;
            }
            cash = out.cashCents();
            shares += out.closedQty();
            realized += out.realizedPnlCents();
        }
        return new CloseAllResult(cash, shares, realized);
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

    // ==================== 解锁 ====================

    /**
     * 这个玩家已经解锁了哪些物品（不可变快照，可以安全地跨线程读）。
     *
     * <p>⚠️ 只包含**显式解锁**的条目。有种子价的原版物品不在里面 ——
     * 它们本来就不上锁，判断要经过 {@link #isUnlocked(ServerPlayer, ResourceLocation)}。
     */
    public static java.util.Set<ResourceLocation> unlocked(ServerPlayer player) {
        java.util.Set<ResourceLocation> set = player.getData(ModAttachments.UNLOCKED.get());
        return set == null ? java.util.Set.of() : set;
    }

    /**
     * 这个物品**现在能不能买**（在价格合法之后、扣钱之前的最后一道门）。
     *
     * <p>口径全部由 {@link UnlockRules#requiresUnlock} 决定：
     * <ul>
     *   <li>数据包写了价的原版物品 → 永远解锁（它们是购买页的基础物资，也是兑换尺度的锚）；</li>
     *   <li>推导价 &lt; 500 金币的模组物品 → 不上锁（买了也不构成「跳级」）；</li>
     *   <li>其余 → 必须在这个玩家的已解锁集合里。</li>
     * </ul>
     * ⚠️ 估值表整个没建起来时 {@link ItemValuator#origin} 一律返回 {@code NONE}，
     * 那会被判成「不是种子 → 要解锁」。所以价格 &lt;= 0 的情况必须在调用方就被挡掉
     * （{@code requiresUnlock} 对 0 价返回 {@code false}，这里再挡一次也不会误封）。
     */
    public static boolean isUnlocked(ServerPlayer player, ResourceLocation id) {
        if (id == null) {
            return false;
        }
        int price = ItemValuator.purchasePrice(id);
        ItemValuator.Origin origin = ItemValuator.origin(id);
        boolean seeded = origin.source() == ItemValuator.Source.ITEM_SEED
                || origin.source() == ItemValuator.Source.TAG_SEED;
        return !UnlockRules.requiresUnlock(price, seeded) || unlocked(player).contains(id);
    }

    /**
     * 解锁：把投放区（= 回收栏那 18 格）里**凑满一组的物品**各消耗一组，换永久购买资格。
     *
     * <p>⚠️ 三条「不静默吞东西」的规矩（单测在 {@code UnlockRulesTest}）：
     * <ol>
     *   <li>只消耗**恰好一组**，多出来的原样留在投放区；</li>
     *   <li>不足一组的物品**一件都不动**（不让玩家「先交一半」）；</li>
     *   <li>已解锁 / 本来就不上锁的物品**一件都不动**（否则点一下就把东西吞了）。</li>
     * </ol>
     *
     * <p>物品是按 <b>{@code getMaxStackSize()}</b> 判定「一组」的 —— 那是游戏自己给的批量定义，
     * 模组改成 16 或 1 也自动跟着走，不需要任何配置。
     */
    public static void unlock(ServerPlayer player) {
        if (!(player.containerMenu instanceof StockMenu menu)) {
            return;
        }
        var bar = menu.bar();

        // ① 先把投放区按「物品 → 总件数」聚起来（跨格子累加，不强求一组放在同一格）
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < bar.getContainerSize(); i++) {
            ItemStack stack = bar.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            flash(player, "message.mcstock.unlock.nothing");
            sendSnapshot(player, "");
            return;
        }

        // ② 算计划：need = 堆叠上限，already = 不用再解锁（已解锁 or 本来不上锁）
        List<UnlockRules.Candidate> plan = UnlockRules.plan(counts,
                StockService::stackLimit, id -> isUnlocked(player, id));

        int ready = UnlockRules.readyCount(plan);
        if (ready == 0) {
            // 一件都不够：报「最接近的那一件」差多少，别只说一句「不行」
            UnlockRules.Candidate closest = null;
            for (UnlockRules.Candidate c : plan) {
                if (c.already() || c.have() <= 0) {
                    continue;
                }
                if (closest == null || c.missing() < closest.missing()) {
                    closest = c;
                }
            }
            if (closest == null) {
                flash(player, "message.mcstock.unlock.already");
            } else {
                Item closestItem = BuiltInRegistries.ITEM.get(closest.item());
                // 查不到就退回 id 字符串：这条路是玩家点一下就会走的，宁丑不抛
                Component name = closestItem == null
                        ? Component.literal(String.valueOf(closest.item()))
                        : closestItem.getDescription();
                flash(player, "message.mcstock.unlock.short", name, closest.have(), closest.need());
            }
            sendSnapshot(player, "");
            return;
        }

        // ③ 逐个消耗「恰好一组」并记进附件
        java.util.Set<ResourceLocation> next = new java.util.HashSet<>(unlocked(player));
        int unlockedNow = 0;
        Item lastName = null;
        for (UnlockRules.Candidate c : plan) {
            if (!c.complete()) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(c.item());
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            consume(bar, item, c.need());
            next.add(c.item());
            unlockedNow++;
            lastName = item;
        }

        // ⚠️ 整份替换：附件是 copyOnDeath 的，序列化拿到的必须是一份稳定的快照
        player.setData(ModAttachments.UNLOCKED.get(), java.util.Set.copyOf(next));
        bar.setChanged();

        if (unlockedNow == 1 && lastName != null) {
            // ⚠️ 1.21.1 里显示名在 ItemStack 上（{@code getHoverName()}），不在 Item 上 ——
            //    这里用 {@code Item#getDescription()}，和 purchase 那条提示同一个口径
            flash(player, "message.mcstock.unlock.done_one", lastName.getDescription());
        } else {
            flash(player, "message.mcstock.unlock.done", unlockedNow);
        }
        sendUnlocked(player);
        sendSnapshot(player, "");
    }

    /**
     * 这个物品「一组」是多少（{@code getMaxStackSize()}）。
     *
     * <p>查不到 / AIR 一律按 1：宁可让门槛变松（一组=1），也不要因为一个坏 id
     * 让整页解锁抛异常 —— 这条路和 {@link StockService#purchase} 一样，是玩家点一下就会走的。
     */
    private static int stackLimit(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return 1;
        }
        return Math.max(1, new ItemStack(item).getMaxStackSize());
    }

    /**
     * 从投放区里**正好**拿走 {@code amount} 个某种物品（从前往后扣，扣满就停）。
     *
     * <p>⚠️ 不能整格清空：玩家可能把 3 组堆在同一格里，我们只该拿走一组。
     * 空掉的格子必须置 {@link ItemStack#EMPTY}（而不是 shrink 到 0 的残留栈），
     * 否则原版容器会显示一个「0 个」的鬼影格。
     */
    private static void consume(net.minecraft.world.SimpleContainer bar, Item item, int amount) {
        int left = amount;
        for (int i = 0; i < bar.getContainerSize() && left > 0; i++) {
            ItemStack stack = bar.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                bar.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** 把这个玩家的已解锁表整份推给客户端（进界面、解锁成功后各一次）。 */
    public static void sendUnlocked(ServerPlayer player) {
        java.util.Set<ResourceLocation> set = unlocked(player);
        PacketDistributor.sendToPlayer(player, new com.qiushaonan.mcstock.network.S2CUnlocked(
                List.copyOf(set)));
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
        // ⚠️ 解锁门在**扣钱之前**：先判资格再判余额 —— 不然玩家会看到「余额不足」，
        //    去赚钱回来还是买不了，白跑一趟（这条顺序是有意的，别对调）。
        if (!isUnlocked(player, itemId)) {
            flash(player, "message.mcstock.unlock.locked", item.getDescription());
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
        // 显式传了就用它；传空串时取「待发提示」（flash() 刚写进去的那条）。
        // ⚠️ 两种路径都 remove：显式那条本来就是刚才 flash() 存进来的同一段文本，
        //    不清掉的话下一次 sendSnapshot(player, "") 会把这条旧提示再放出来一遍。
        String pending = PENDING_FLASH.remove(player.getUUID());
        String pendingFlash = (flashText == null || flashText.isEmpty()) ? pending : flashText;
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
        // 条件单簿整份替换：与快照同帧，客户端不会出现「价格是新的、挂单列表是旧的」半帧状态
        sendConditionalOrders(player);

        if (player.containerMenu instanceof StockMenu menu) {
            PacketDistributor.sendToPlayer(player, slotValueOf(menu));
        }
    }

    /**
     * 把玩家当前的条件单簿整份推过去（{@link S2CConditionalOrders}，最多 {@link ConditionalBook#MAX_ORDERS} 条）。
     *
     * <p>服务端只发**结构化字段**（下标 / 序号 / 分），语言键与文字全在客户端按自己的语言渲染 ——
     * 与新闻流同一个理由（服务端语言常常是 en、客户端是 zh）。
     */
    private static void sendConditionalOrders(ServerPlayer player) {
        List<ConditionalOrder> orders = conditionalBook(player).orders();
        int n = Math.min(orders.size(), ConditionalBook.MAX_ORDERS);
        S2CConditionalOrders.View[] views = new S2CConditionalOrders.View[n];
        for (int i = 0; i < n; i++) {
            ConditionalOrder o = orders.get(i);
            views[i] = new S2CConditionalOrders.View(o.id(), o.stock(), o.direction().ordinal(),
                    o.triggerCents(), o.action().ordinal(), o.qty(), o.leverage());
        }
        PacketDistributor.sendToPlayer(player, new S2CConditionalOrders(views));
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
