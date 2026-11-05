package com.qiushaonan.mcstock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.market.ItemValuator;
import com.qiushaonan.mcstock.market.MarketEngine;
import com.qiushaonan.mcstock.market.Portfolio;
import com.qiushaonan.mcstock.market.Position;
import com.qiushaonan.mcstock.market.StockCatalog;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.market.StockMarketData;
import com.qiushaonan.mcstock.market.StockService;
import com.qiushaonan.mcstock.menu.StockMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * {@code /mcstock} —— 调试 / 验收命令，**权限 2**（原版 op 等级 2 = 游戏管理员）。
 *
 * <p>注册走 NeoForge 的 {@code RegisterCommandsEvent}（见 {@link com.qiushaonan.mcstock.StockEvents}），
 * 和项目里其它事件一个风格：不在主类里手写，也不自己 new 一个 dispatcher。
 *
 * <pre>
 *   /mcstock money                      自己的余额（金币，两位小数）+ 净值
 *   /mcstock money set &lt;金额&gt;           直接设置余额（允许小数）
 *   /mcstock money add &lt;金额&gt;           加钱
 *   /mcstock price                      三只股票的现价与涨跌
 *   /mcstock price set &lt;股票&gt; &lt;价格&gt;    直接设价（夹在价格上下限内）
 *   /mcstock advance                    立刻跳一次价（不用等 2 秒）
 *   /mcstock value [hand|held]          手持物品的估值 / 采购价 / 来源（种子值？标签值？推导值？）
 *   /mcstock positions                  自己的全部仓位（id/股票/多空/股数/均价/保证金/浮盈）
 * </pre>
 *
 * <h2>四条实现约定（改这个文件前先读）</h2>
 * <ol>
 *   <li><b>钱与价格一律用 {@link StockService#formatPrice}</b>（分 → 「100.00」两位小数），
 *       不在这里另写一套格式化；金额内部单位是**分**，输入按**金币**收（见 {@link #parseCoins}）。</li>
 *   <li><b>「id → 下标」只有一处实现</b>：{@link #parseStockId} 只做规范化（纯函数、可单测），
 *       真正的查表交给 {@link StockCatalog#indexOf(String)}（见 AGENTS.md：同类换算只允许有一份）。</li>
 *   <li><b>涨跌用千分比</b>（与 {@code S2CStockSnapshot} 的 {@code changes} 同一算法），
 *       显示时才 ÷10 变成百分比 —— 千分比当百分比印会让数字大 10 倍，本项目为此踩过坑。</li>
 *   <li><b>不改玩法状态</b>：命令只调现成的公开入口（{@code StockService} 的账户读写、
 *       {@code MarketEngine.debugSetPrice/advanceAll}、{@link StockMarketData#setDirty()}）；
 *       没有一条判定是这里自己发明的，权限 2 只是「能调它」，不是「绕过它」。</li>
 * </ol>
 *
 * <p>文案全部走 {@code message.mcstock.cmd.*} 语言键（两份 lang 都要有，
 * 由 {@code tools/check_lang_keys.py} 把关）：玩家看到的按客户端语言渲染，
 * 服务端控制台执行时按服务端语言渲染（所以 en_us 那份不是摆设）。
 *
 * <p>⚠️ 本类**不允许**有 Minecraft/Brigadier 类型的静态字段：单测里
 * {@code McStockCommandTest} 会直接调 {@link #parseCoins} / {@link #parseStockId}，
 * 类初始化一旦碰了 Minecraft 就会在没启动游戏的 JVM 里炸（和 {@code StockCatalog} 握着
 * {@code Items.DIAMOND} 是同一类问题，所以 id→下标 的查表被刻意留在方法里、不放进静态字段）。
 */
public final class McStockCommand {

    private McStockCommand() {
    }

    /** 权限等级 2 = 原版 op / 游戏管理员（命令方块与单人作弊存档同样满足）。 */
    private static final int PERMISSION_LEVEL = 2;

    /** 金额上限（金币）：10 亿金币 = 1000 亿分，离 {@code long} 溢出还差得远，也挡住了粘贴一长串数字。 */
    private static final long MAX_COINS = 1_000_000_000L;

    /** 金额参数名（子命令里出现多次，抽成常量免得拼错）。 */
    private static final String ARG_AMOUNT = "amount";
    private static final String ARG_STOCK = "stock";
    private static final String ARG_PRICE = "price";

    /** 注册 {@code /mcstock}。由 {@code StockEvents} 在 {@code RegisterCommandsEvent} 里调用。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcstock")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("money")
                        .executes(McStockCommand::showMoney)
                        .then(Commands.literal("set")
                                .then(Commands.argument(ARG_AMOUNT, StringArgumentType.word())
                                        .executes(ctx -> changeMoney(ctx, false))))
                        .then(Commands.literal("add")
                                .then(Commands.argument(ARG_AMOUNT, StringArgumentType.word())
                                        .executes(ctx -> changeMoney(ctx, true)))))
                .then(Commands.literal("price")
                        .executes(McStockCommand::showPrices)
                        .then(Commands.literal("set")
                                .then(Commands.argument(ARG_STOCK, StringArgumentType.word())
                                        // 补全给的是**裸 id**（diamond_mine），parseStockId 也认带命名空间的写法
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(StockDefs.IDS, builder))
                                        .then(Commands.argument(ARG_PRICE, StringArgumentType.word())
                                                .executes(McStockCommand::setPrice)))))
                .then(Commands.literal("advance")
                        .executes(McStockCommand::advance))
                .then(Commands.literal("value")
                        .executes(McStockCommand::showValue)
                        .then(Commands.literal("hand").executes(McStockCommand::showValue))
                        .then(Commands.literal("held").executes(McStockCommand::showValue)))
                .then(Commands.literal("positions")
                        .executes(McStockCommand::showPositions)));
    }

    // ==================== 子命令：money ====================

    /** {@code /mcstock money}：余额 + 净值（净值 = 现金 + Σ(保证金 + 浮盈)，服务端口径）。 */
    private static int showMoney(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        long cash = StockService.cash(player);
        long net = StockService.netWorth(player, StockService.portfolio(player), prices(source));
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.money",
                StockService.formatPrice(cash), StockService.formatPrice(net)), false);
        return 1;
    }

    /** {@code /mcstock money set|add <金额>}：金额按**金币**收，允许两位小数。 */
    private static int changeMoney(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, ARG_AMOUNT);
        Coins amount = parseCoins(raw);
        if (!amount.ok()) {
            source.sendFailure(Component.translatable("message.mcstock.cmd.bad_amount", raw));
            return 0;
        }
        // ⚠️ 订正（2026-10-06）：这里原来写着「setCash 自己会把负数夹到 0」—— 那条 clamp 已经删掉
        //    （现金允许为负 = 允许负债）。2026-10-21 起 parseCoins **接受负号**，好让管理员直接造负债
        //    验证界面；普通玩家碰不到这条权限 2 的命令，玩法约束靠的是不给玩家命令。
        long target = add ? StockService.cash(player) + amount.cents() : amount.cents();
        StockService.setCash(player, target);
        long now = StockService.cash(player);   // 以读回来的为准
        if (add) {
            source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.money_add",
                    StockService.formatPrice(amount.cents()), StockService.formatPrice(now)), false);
        } else {
            source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.money_set",
                    StockService.formatPrice(now)), false);
        }
        refresh(player);
        return 1;
    }

    // ==================== 子命令：price ====================

    /** {@code /mcstock price}：三只股票的现价 + 相对上一次跳价的涨跌。 */
    private static int showPrices(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        sendPriceLines(source, engine(source));
        return 1;
    }

    /** {@code /mcstock price set <股票> <价格>}：直接设价（{@code debugSetPrice} 会夹进上下限）。 */
    private static int setPrice(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String rawStock = StringArgumentType.getString(ctx, ARG_STOCK);
        // 规范化是纯函数，查表是 StockCatalog 那一份 —— 两件事分开，前者可单测
        int stock = StockCatalog.indexOf(parseStockId(rawStock));
        if (stock < 0) {
            source.sendFailure(Component.translatable("message.mcstock.cmd.bad_stock", rawStock,
                    String.join(" / ", StockDefs.IDS)));
            return 0;
        }
        String rawPrice = StringArgumentType.getString(ctx, ARG_PRICE);
        Coins price = parseCoins(rawPrice);
        if (!price.ok()) {
            source.sendFailure(Component.translatable("message.mcstock.cmd.bad_amount", rawPrice));
            return 0;
        }
        MarketEngine engine = engine(source);
        long before = engine.price(stock);
        engine.debugSetPrice(stock, price.cents());
        // 行情变了要标脏，否则这次改动可能不写盘（存档只在脏了的时候落盘）
        StockMarketData.get(source.getLevel()).setDirty();
        long after = engine.price(stock);
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.price_set",
                Component.translatable(StockCatalog.nameKey(stock)),
                StockService.formatPrice(after), StockService.formatPrice(before)), false);
        ServerPlayer player = player(source);
        if (player != null) {
            refresh(player);
        }
        return 1;
    }

    /** {@code /mcstock advance}：立刻跳一次价（正式玩法里由服务端每 {@code PRICE_TICK_TICKS} tick 跳）。 */
    private static int advance(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        StockMarketData market = StockMarketData.get(source.getLevel());
        MarketEngine engine = market.engine();
        engine.advanceAll();
        market.setDirty();
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.advance",
                Integer.toString(StockDefs.PRICE_TICK_TICKS),
                Integer.toString(StockDefs.PRICE_TICK_TICKS / 20)), false);
        sendPriceLines(source, engine);
        ServerPlayer player = player(source);
        if (player != null) {
            refresh(player);
        }
        return 1;
    }

    // ==================== 子命令：value ====================

    /**
     * {@code /mcstock value [hand|held]}：手持物品的**估价**、**采购价**、以及它是不是
     * 种子值 / 标签值 / 推导值（{@link ItemValuator#origin}）。
     *
     * <p>为什么需要它：回收价（估价）与采购价（估价 × 1.18）都来自一张会自动推导的表，
     * 「这个东西为什么是这个价」在数据包里是查不到的 —— 只能反过来问估值器。
     */
    private static int showValue(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("message.mcstock.cmd.value_empty"));
            return 0;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int value = ItemValuator.value(id);
        int purchase = ItemValuator.purchasePrice(id);
        Component origin = originText(ItemValuator.origin(id));
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.value",
                stack.getHoverName(), id.toString(),
                Integer.toString(value), Integer.toString(purchase), origin), false);
        return 1;
    }

    /** 来源明细 → 一行文字（物品种子 / 标签种子 / 配方推导 / 无来源）。 */
    private static Component originText(ItemValuator.Origin origin) {
        return switch (origin.source()) {
            case ITEM_SEED -> Component.translatable("message.mcstock.cmd.source.item_seed",
                    Integer.toString(origin.declared()));
            case TAG_SEED -> Component.translatable("message.mcstock.cmd.source.tag_seed",
                    origin.tag() == null ? "?" : origin.tag().toString(),
                    Integer.toString(origin.declared()));
            case DERIVED -> Component.translatable("message.mcstock.cmd.source.derived");
            case NONE -> Component.translatable("message.mcstock.cmd.source.none");
        };
    }

    // ==================== 子命令：positions ====================

    /**
     * {@code /mcstock positions}：自己的全部仓位，一行一个（id / 股票 / 多空 / 股数 / 均价 /
     * 保证金 / 浮盈）。
     *
     * <p>仓位是**互相独立的**（见 {@code Portfolio}）：同一只股票可以同时挂多仓和空仓，
     * 每行都是自己那一仓的均价与保证金 —— 所以这里不做任何「按股票合并」，照 {@code positions()}
     * 的顺序逐行打印，行号就是「平这一个仓」要用的 id。
     */
    private static int showPositions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        Portfolio portfolio = StockService.portfolio(player);
        List<Position> positions = portfolio.positions();
        if (positions.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.positions_empty"), false);
            return 1;
        }
        long[] prices = prices(source);
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.positions_header",
                Integer.toString(positions.size())), false);
        for (Position position : positions) {
            long price = prices[position.stock()];
            long pnl = position.pnl(price);
            source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.position_line",
                    Integer.toString(position.id()),
                    Component.translatable(StockCatalog.nameKey(position.stock())),
                    Component.translatable(position.isLong()
                            ? "gui.mcstock.pos.long" : "gui.mcstock.pos.short"),
                    Integer.toString(position.absShares()),
                    StockService.formatPrice(position.entryCents()),
                    StockService.formatPrice(position.marginCents()),
                    StockService.formatSigned(pnl)), false);
        }
        return 1;
    }

    // ==================== 参数解析（纯函数，单测覆盖） ====================

    /**
     * 金额文本 → **分**。接受 {@code 1000}、{@code 12.34}、{@code .5}、{@code 5.}、{@code +7}，
     * 前后空白随便写；小数**最多两位**，多写的位直接截掉（不四舍五入 —— 和
     * {@link StockService#formatPrice} 的两位小数口径一致，四舍五入会出现「输入和余额差一分」）。
     *
     * <p>**接受负号**（{@code -100} = 负债 100 金币）：这是**权限 2 的调试命令**，用来看负债时的
     * 界面与「空中飞人」成就，别让管理员为了造负债去摔一次；普通玩家碰不到这条命令，
     * 而「负债只能由穿仓产生」这条玩法约束靠的是不给玩家命令，不是靠这里拒收负号。
     *
     * <p>纯 Java（只用 String 与 long），所以能在不启动游戏的 JVM 里单测。
     *
     * @return {@code ok=false} 时 {@code cents=0}，调用方必须给提示，**不要静默当成 0**
     */
    public static Coins parseCoins(String text) {
        if (text == null) {
            return Coins.BAD;
        }
        String s = text.trim();
        boolean negative = false;
        if (s.startsWith("+")) {
            s = s.substring(1);     // 复制粘贴里常见的写法
        } else if (s.startsWith("-")) {
            negative = true;         // 负数 = 负债（仅调试命令使用）
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return Coins.BAD;
        }
        int dot = s.indexOf('.');
        String whole = dot < 0 ? s : s.substring(0, dot);
        String frac = dot < 0 ? "" : s.substring(dot + 1);
        if (dot >= 0 && s.indexOf('.', dot + 1) >= 0) {
            return Coins.BAD;       // "1.2.3"
        }
        if (whole.isEmpty() && frac.isEmpty()) {
            return Coins.BAD;       // 只有一个 "."
        }
        if (!isAsciiDigits(whole) || !isAsciiDigits(frac)) {
            return Coins.BAD;
        }
        String wholePart = whole.isEmpty() ? "0" : whole;
        String fracPart = (frac + "00").substring(0, 2);    // 补齐两位 / 截断到两位
        try {
            long coins = Long.parseLong(wholePart);
            if (coins > MAX_COINS) {
                return Coins.BAD;
            }
            long cents = coins * 100L + Long.parseLong(fracPart);
            return new Coins(true, negative ? -cents : cents);
        } catch (NumberFormatException e) {
            // 一大串数字（超过 long）：当成非法输入，而不是溢出成别的数
            return Coins.BAD;
        }
    }

    /**
     * 股票 id 文本 → {@link StockDefs#IDS} 里的**裸 id**；不认识返回 {@code null}。
     *
     * <p>接受 {@code diamond_mine} / {@code mcstock:diamond_mine} / 大小写混写 / 前后空白。
     * 只认本模组的命名空间：{@code minecraft:diamond_mine} 那种是打字打错了，直接报错比猜好。
     *
     * <p>⚠️ 返回值是 **id 而不是下标**：下标一律交给 {@link StockCatalog#indexOf(String)} 查
     * （「id → 下标」只允许有一份实现）。这样这个函数就是纯 Java 的，能在没启动游戏的
     * JVM 里单测 —— {@code StockCatalog} 的静态初始化握着 {@code Items.DIAMOND} 那几个图标，
     * 在纯 JUnit 里会直接炸（这也是本类不许有 MC 静态字段的原因）。
     */
    public static String parseStockId(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        int colon = text.indexOf(':');
        if (colon >= 0) {
            if (!text.startsWith(McStock.MODID + ":")) {
                return null;
            }
            text = text.substring(colon + 1);
        }
        for (String id : StockDefs.IDS) {
            if (id.equals(text)) {
                return id;
            }
        }
        return null;
    }

    private static boolean isAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@link #parseCoins} 的结果：{@code ok=false} 表示「这个字符串不是金额」。 */
    public record Coins(boolean ok, long cents) {

        private static final Coins BAD = new Coins(false, 0L);
    }

    // ==================== 小工具 ====================

    /** 需要「自己」的子命令：不是玩家就回一句失败并返回 {@code null}（控制台没有余额/持仓）。 */
    private static ServerPlayer player(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.translatable("message.mcstock.cmd.player_only"));
        return null;
    }

    /** 行情存档（三只股票全服共享，挂在主世界的 SavedData 上）。 */
    private static MarketEngine engine(CommandSourceStack source) {
        return StockMarketData.get(source.getLevel()).engine();
    }

    /** 三只股票的现价（分）。 */
    private static long[] prices(CommandSourceStack source) {
        return engine(source).prices();
    }

    /** 打三行「股票 价格（涨跌）」。{@code price} 与 {@code advance} 共用。 */
    private static void sendPriceLines(CommandSourceStack source, MarketEngine engine) {
        source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.price_header",
                Integer.toString(StockDefs.PRICE_TICK_TICKS),
                Integer.toString(StockDefs.PRICE_TICK_TICKS / 20)), false);
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            final int index = stock;
            source.sendSuccess(() -> Component.translatable("message.mcstock.cmd.price_line",
                    Component.translatable(StockCatalog.nameKey(index)),
                    StockService.formatPrice(engine.price(index)),
                    percentText(changePermille(engine, index))), false);
        }
    }

    /**
     * 相对上一次跳价的涨跌，单位**千分比**。
     *
     * <p>⚠️ 与 {@code StockService.sendSnapshot} 里算 {@code changes[]} 的算式**逐字一致**
     * （那是客户端界面用的同一份口径）。千分比 ≠ 百分比：显示时必须 ÷10，本项目踩过
     * 「千分比当百分比印、界面上所有数字大 10 倍」的坑（-502.2%）。
     */
    private static int changePermille(MarketEngine engine, int stock) {
        long price = engine.price(stock);
        long prev = engine.previousPrice(stock);
        return prev > 0 ? (int) Math.round((price - prev) * 1000.0 / prev) : 0;
    }

    /** 千分比 → 「+1.23%」（这里才做 ÷10，别在别处再算一遍百分比）。 */
    private static String percentText(int permille) {
        return String.format(Locale.ROOT, "%+.2f%%", permille / 10.0);
    }

    /**
     * 改完行情/账户之后，玩家若正开着股市界面就顺手推一份快照。
     *
     * <p>服务端的正常推送是「跳价时推完整快照 / 每秒推一次轻量包」两档，
     * 调试命令改的东西要等到那一拍才会出现在界面上（K 线更是要等下一次跳价）。
     * 这里只是调现成的 {@link StockService#sendSnapshot}（纯读 + 发包），不改任何状态。
     */
    private static void refresh(ServerPlayer player) {
        if (player.containerMenu instanceof StockMenu) {
            StockService.sendSnapshot(player, "");
        }
    }
}
