package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.command.McStockCommand;
import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.market.ConditionalOrder;
import com.qiushaonan.mcstock.market.EventType;
import com.qiushaonan.mcstock.market.MarketRegime;
import com.qiushaonan.mcstock.market.Position;
import com.qiushaonan.mcstock.market.PositionMath;
import com.qiushaonan.mcstock.market.StockCatalog;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.menu.StockMenu;
import com.qiushaonan.mcstock.network.C2SCancelConditionalOrder;
import com.qiushaonan.mcstock.network.C2SClosePosition;
import com.qiushaonan.mcstock.network.C2SPlaceConditionalOrder;
import com.qiushaonan.mcstock.network.C2SPurchaseItem;
import com.qiushaonan.mcstock.network.C2SRecycleBar;
import com.qiushaonan.mcstock.network.C2SRequestSnapshot;
import com.qiushaonan.mcstock.network.C2STradeStock;
import com.qiushaonan.mcstock.network.S2CStockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股市主界面：**回收 / 行情 / 购买**三个页面共用一个屏幕。
 *
 * <h2>为什么是 {@link AbstractContainerScreen} 而不是普通 Screen</h2>
 * <p>回收页有 18 格回收栏 + 36 格玩家背包、购买页有 36 格玩家背包，这些都是**真正的容器槽**
 * （服务端 {@link StockMenu} 持有，靠原版槽位包同步）。所以必须继承容器屏 —— 原版那套
 * 「点击 → {@code Slot#x/y} → 发包」的链路是现成的，自己拿普通 Screen 重做一遍等于重造轮子，
 * 而且背包里的物品会拿不起来。
 *
 * <h2>三条硬性约定（改这个文件前先读）</h2>
 * <ol>
 *   <li><b>所有矩形都从 {@link StockGuiLayout#compute(int, int)} 来</b>，这里一个写死的坐标都不许有
 *       （AGENTS.md 5.3）。窗口尺寸变了只需重新 {@code compute}，绘制代码一行不用改。</li>
 *   <li><b>坐标是屏幕绝对坐标</b>：{@link StockGuiLayout.Layout} 里的矩形已经是绝对坐标，
 *       所以 {@code init()} 里把 {@code leftPos/topPos} 按到 (0,0) —— 原版
 *       {@code AbstractContainerScreen#render} 会 {@code pose().translate(leftPos, topPos)} 之后才调
 *       {@code renderBg}，不按到 0 就会整体偏移一次。槽位坐标也是绝对值，同样受益。</li>
 *   <li><b>每帧都从 {@link StockClientState} 现读</b>：服务端每跳价推一份完整快照，
 *       客户端**一个数都不自己算**（价格、余额、持仓、爆仓价都是服务端算好的）。
 *       这里唯一的本地计算是「数量 MAX」这种输入辅助，而且服务端还会再夹一遍。</li>
 * </ol>
 */
public class StockScreen extends AbstractContainerScreen<StockMenu> {

    /**
     * 三个页面：0 回收 / 1 行情 / 2 购买（页签顺序也是这个）。
     *
     * <p>⚠️ 直接用 {@link StockMenu} 里那几个常量，**不要在这里另起一套数字**：
     * {@code StockMenu#placeSlots(int, StockGuiLayout.Layout)} 按同一组数字决定
     * 「这一页哪些槽摆在哪、哪些槽丢到屏幕外」，两边一旦不一致，就会出现
     * 「回收页看到一个空背包」这种对不上的症状。
     */
    private static final int PAGE_RECYCLE = StockMenu.PAGE_RECYCLE;
    private static final int PAGE_TRADE = StockMenu.PAGE_MARKET;
    private static final int PAGE_SHOP = StockMenu.PAGE_SHOP;

    /**
     * 数量 ▲▼ 的档位表：**1-2-5 系列**（低段密、高段疏）。
     *
     * <p>⚠️ 原来是 ×10 档进（1/10/100/…，只有 6 档）—— 玩家反馈「点一下从 1 跳到 10 会让人抓不住」。
     * 现在 16 档：低段 1→2→5 每次只翻一倍多，高段才拉开。
     * <p>用「查档位表」而不是「乘法」，是为了让 37 这种手输的中间值也对得上：
     * ▲ 取第一个大于当前值的档、▼ 取最后一个小于当前值的档。
     */
    private static final int[] QTY_STEPS = {
            1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000,
    };

    /**
     * 杠杆 ▲▼ 的档位表：**1-2-3-5-8…** 系列，低杠杆段更密。
     *
     * <p>⚠️ 原来是 9 档（1/2/5/10/…/400）—— 同理太粗。现在 18 档：
     * 低倍段每档只差一点（因为**爆仓距离在低倍时变化最快**：×1 是 45%、×2 是 22.5%、
     * ×3 是 16.7%，差一档就是天壤之别），高倍段才拉到 50。
     */
    private static final int[] LEVERAGE_STEPS = {
            1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 250, 300, 350, 400,
    };

    /** 购买页分类循环的六个键（顺序 = 点击顺序）。 */
    private static final String[] CATEGORY_KEYS = {
            "gui.mcstock.shop.cat.all",
            "gui.mcstock.shop.cat.material",
            "gui.mcstock.shop.cat.food",
            "gui.mcstock.shop.cat.block",
            "gui.mcstock.shop.cat.tool",
            "gui.mcstock.shop.cat.other",
    };

    // ==================== 状态 ====================

    private StockGuiLayout.Layout layout;
    private int page = PAGE_TRADE;
    private int shopPage = 0;
    private int category = 0;
    private String search = "";

    private EditBox qtyField;
    private EditBox levField;
    private EditBox searchBox;

    /** 分类按钮：切分类时只改它的文案，不重建控件（重建会把搜索框的输入清掉）。 */
    private Button categoryButton;

    // ⚠️ 右列原来那个**全局「平仓」按钮已删除**（玩家原话：「平仓按钮移动到单个仓位后面啊」）。
    //    现在平仓只有一个入口：持仓表格**每行末尾**的那个按钮（见 posButtons / sendClosePosition）。
    //    StockService 里「qty <= 0 = 平掉这只股票的全部仓」的语义保留着，界面不再暴露。

    /** 帮助按钮（标题栏右端、关闭按钮左边）。 */
    private Button helpButton;

    /**
     * 帮助覆盖层是否打开。
     *
     * <p>打开时：内容区被一块帮助面板盖住（**不动容器槽**：槽位坐标由菜单管，这里只盖绘制）、
     * 其它控件全部隐藏（避免穿透点击）、ESC 关掉的只是覆盖层而不是整个界面。
     */
    private boolean helpOpen;
    /** 帮助覆盖层的滚动行偏移（内容比可视区多时用滚轮/方向键翻）。 */
    private int helpScroll;

    // ---------- 条件单覆盖层（标题栏按钮打开，与帮助覆盖层互斥） ----------

    /**
     * 条件单覆盖层是否打开。
     *
     * <p>与帮助覆盖层同一个套路：打开时 {@link #render} 早退，只画这一层 + 手动绘制本层控件，
     * 其它控件全部隐藏 —— **光盖住不隐藏**的话玩家会点到看不见的买入/卖出/容器槽
     * （帮助面板那条踩过的坑）。
     */
    private boolean condOpen;
    /** 挂单列表的滚动行偏移（一屏 {@code Layout#condVisibleRows()} 行）。 */
    private int condScroll;
    /** 标题栏「条件单」按钮：打开覆盖层时它是「再点一下关掉」的入口。 */
    private Button condButton;
    /**
     * 条件单覆盖层的控件：打开时只有这些 visible，早退的 {@link #render} 也只手动绘制这些。
     *
     * <p>⚠️ 行尾「撤」**不是控件**（行数每帧都可能变），它是画出来的框 + {@link #mouseClicked}
     * 里的命中判定 —— 和购买页网格同一套做法，省掉「每 tick 拆装按钮」的复杂度。
     */
    private final List<AbstractWidget> condWidgets = new ArrayList<>();
    private EditBox condTriggerField;
    /** 触发价填写栏的内容（重建控件后要恢复玩家刚输入的一半数字）。 */
    private String condTriggerText = "";
    private Button condDirButton;
    private Button condActionButton;
    /** 表单当前选中的方向 / 动作序号（与协议、枚举同一组序号）。 */
    private int condDirection = 0;
    private int condAction = 0;

    // ---------- 持仓列表（右列底部，可滚动，每行一个「平」按钮） ----------

    /**
     * 持仓列表的行控件：{@code key = 仓位 id，value = 行尾「平」按钮}。
     *
     * <p>⚠️ <b>为什么是「每行动态建/删」而不是一次建满</b>：仓位数量、顺序、滚动位置每 tick 都可能变
     * （开仓 / 平仓 / 爆仓 / 滚轮），控件必须跟着走；而且按钮回调里绝不能直接改控件表
     * （见 {@link #pendingAction}），所以增删统一走
     * {@code containerTick() → defer(rebuildPositionWidgets)}。
     */
    private final Map<Integer, Button> posButtons = new LinkedHashMap<>();

    /** 持仓列表的滚动偏移（单位 = **一行**；一屏 = {@code Layout#posVisibleRows()} 行）。 */
    private int posScroll = 0;

    /** 是否正在拖表格右缘的滚动条滑块。 */
    private boolean draggingPosScrollbar;
    /** 按下时鼠标在滑块内部的偏移（记住它才不会「一按滑块就跳到指针下」）。 */
    private int posScrollGrabDy;

    /**
     * flash 提示的出现时刻与「上一次看到的内容」。
     *
     * <p>⚠️ 为什么要本地记时间：{@code StockClientState.flash} 是**服务端语义**的字段
     * （空串 = 没有新提示、不清空），它自己不会过期，所以一旦有提示就永久留在屏幕上
     * （实机反馈「左上角的提示不会消退」）。本地这份到期逻辑只作用于**显示**，
     * 不动那个字段 —— 服务端下次推新内容时这里重新计时。
     */
    private long flashShownAtMs;
    private String flashSeen = "";
    /** 提示显示多久后自动消失（毫秒）。 */
    private static final long FLASH_TTL_MS = 4000L;
    /** 最后这段时间换成灰色，做个「快要没了」的提示。 */
    private static final long FLASH_FADE_MS = 1000L;

    // ---------- 新闻条（走势图表头行右侧的横向滚动 marquee） ----------

    /**
     * 一条新闻滚动显示多久（tick）才换下一条：{@code 500 tick = 25 秒}。
     *
     * <p>比静态提示长得多（flash 只有 4 秒）—— 滚动条要读完需要时间：
     * 640×360 下一条新闻滚一圈约 21 秒（见 {@link StockGuiLayout#NEWS_SCROLL_SPEED}），
     * 25 秒刚好让玩家把一条完整的看完再换下一条。
     */
    private static final int NEWS_HOLD_TICKS = 500;
    /**
     * 超过这个年龄的新闻就不再滚了（毫秒）：5 分钟。
     *
     * <p>没有这道闸，隔了半小时打开界面还会滚动播报「半小时前矿洞塌方」，
     * 玩家会以为刚刚发生了事。全都不新鲜时这一行**留空**（玩家要求：不要再放图例文字）。
     */
    private static final long NEWS_MAX_AGE_MS = 5 * 60 * 1000L;

    /** 当前滚动的是哪一条（按「股票 + 语言键」认人：新新闻插进来时列表会整体后移一位）。 */
    private int tickerStock = Integer.MIN_VALUE;
    private String tickerKey = null;
    /** 这一条还能滚多久（tick）。 */
    private int tickerHoldTicks;
    /** 滚动相位（像素）：0 = 文字左端贴在矩形右缘（还没进来）。 */
    private int tickerPhase;
    /** 当前这条的成品文本与宽度（每帧重拼会白算一遍，缓存起来）。 */
    private Component tickerText = Component.empty();
    private int tickerTextW;

    /** 上一次建好的行控件「签名」（滚动位置 + 可见行数 + 各仓 id）：变了才重建，别每 tick 拆了重装。 */
    private String posWidgetSignature = "";

    /**
     * 待重建持仓行控件的标志（幂等）。
     *
     * <p>⚠️ 为什么不用 {@code defer(this::rebuildPositionWidgets)}：`defer` 是**单槽**的
     * （{@code pendingAction} 只保留最后一个），同一 tick 里后续任何一次 defer 都会把
     * 这次重建顶掉 —— 删掉的「平」按钮就永远回收不了（实机：表格已空、按钮还在）。
     */
    private boolean posRebuildPending;

    /** 排序缓存的依据：快照一来 {@code StockClientState.positions} 就整份换新，比引用即可。 */
    private Position[] posCacheSource;
    private List<Position> posCache = new ArrayList<>();

    /** 当前页签的三个按钮（切页后要改文案，所以留引用）。 */
    private final Button[] tabButtons = new Button[3];

    /** 购买页当前过滤后的显示列表（物品 + 单价分）。每帧重建太亏，只在目录/过滤条件变化时重算。 */
    private final List<ShopEntry> shopView = new ArrayList<>();

    /** 回收明细缓存：物品 → 件数（同一帧画多行时不用反复扫 18 格）。 */
    private final Map<Item, Integer> recycleCounts = new LinkedHashMap<>();
    private long recycleTotalCents;
    private int recycleStacks;
    /**
     * 回收栏里**估不出价**的件数（估价 0，卖了不给钱）。
     *
     * <p>用途：合计块的第二行「不值钱 N 件」。玩家靠它解释「为什么我放了 576 件、
     * 总价却比预想少」—— 不值钱的东西会被留在栏里、不换成钱（见 {@code StockService#recycle}）。
     * 怎么算出来的：**逐格单价**由服务端给（{@code StockClientState.recycleUnitValues}），
     * 单价为 0 的就是估不出价的那种，把它的件数累加起来即可
     * （见 {@link RecycleRow#counted()}）。⚠️ 不要用「合计 ÷ 总件数」反推 —— 那是均价。
     */
    private int recycleUnpricedItems;
    /**
     * **逐种物品**的明细行（单价 / 小计 / 件数）。
     *
     * <p>⚠️ 键与 {@link #recycleCounts} 一致（同一种物品一行）。单价来自服务端的**逐格**单价，
     * 不是「合计 ÷ 总件数」那个均价 —— 均价会让三种不同物品显示成同一组数（真机 bug）。
     */
    private final Map<Item, RecycleRow> recycleRows = new LinkedHashMap<>();
    /** 扫描时临时用的「物品 → 该物品占用的格子里最大的单价」，扫完即清。 */
    private final Map<Item, Integer> recycleSlotUnits = new LinkedHashMap<>();

    /** 一帧的矩形（供鼠标判定用，避免每次都重算布局）。 */
    private StockGuiLayout.Rect hoveredGridCell;

    /**
     * 延迟到下一 tick 执行的切换动作。
     *
     * <p>⚠️ <b>为什么不能直接在按钮回调里 {@code rebuildWidgets()}</b>：按钮的点击是在
     * {@code Screen#mouseClicked → children 遍历} 里派发的，而 {@code rebuildWidgets()}
     * 会 {@code clearWidgets()} + 重新 {@code addRenderableWidget} —— 等于在遍历
     * {@code renderables} 的过程中把这张表整个换掉（{@code ConcurrentModificationException}
     * 或「点了一下什么都没发生」都出现过）。原版自己切界面走的是
     * {@code Minecraft#setScreen}（同样是延迟生效），这里照抄这个思路：
     * 回调里只登记一个动作，等这一帧过去、下个 tick 再真正切页。
     */
    private Runnable pendingAction;

    public StockScreen(StockMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void init() {
        super.init();
        // ① 布局：唯一来源。开界面与窗口缩放都会走到这里
        this.layout = StockGuiLayout.compute(this.width, this.height);

        // ② 原版的 render() 会 pose().translate(leftPos, topPos) 之后才调 renderBg，
        //    而 layout 给的是屏幕绝对坐标；把 leftPos/topPos 按到 (0,0) 让那次平移变成恒等，
        //    槽位坐标（同样是绝对值）也就跟着对了。imageWidth/Height 只影响「面板外算不算外部」，
        //    这里同步成面板尺寸，免得点到面板右侧被当成「界面外」而丢东西。
        this.leftPos = 0;
        this.topPos = 0;
        this.imageWidth = this.layout.panel().w();
        this.imageHeight = this.layout.panel().h();

        // ③ 容器槽：由菜单按页面摆（行情页会把 54 个槽全挪到屏幕外，免得点到看不见的槽）
        this.menu.placeSlots(this.page, this.layout);

        buildWidgets();

        // ④ 刚开界面就主动要一份带 K 线的完整快照。
        //    服务端只在**跳价那一刻**（每 2 秒）推带 K 线的完整快照，其余每次只推不带 K 线的
        //    轻量包（倒计时用）。刚打开界面时手里可能一份 K 线都没有，干等最多 2 秒才出柱 ——
        //    主动问一次，服务端立刻回一份，走势图当场就有。
        requestSnapshot();

        // ⑤ 覆盖层的开关状态要在重建控件之后重新落到控件可见性上
        //    （rebuildWidgets 会清掉所有控件、重新 addRenderableWidget，可见性回到默认 true）
        applyOverlayVisibility();
    }

    /**
     * 建/重建全部控件。
     *
     * <p>{@link #setPage(int)} 会调它 —— 切页要换标签、要显示/隐藏搜索框，
     * 而 {@code rebuildWidgets()} 走的也是 {@code init()}，所以这里必须是**幂等**的：
     * 每次都从头把控件建一遍，不在旧对象上改。
     */
    private void buildWidgets() {
        StockGuiLayout.Layout l = this.layout;

        // ---------- 页签（三个页面共用） ----------
        for (int i = 0; i < 3; i++) {
            final int target = i;
            Component label = Component.translatable(tabKey(i));
            Button tab = StockWidgets.tabButton(l.tabs()[i], label, i == this.page,
                    () -> defer(() -> setPage(target)));
            this.tabButtons[i] = tab;
            addRenderableWidget(tab);
        }

        // ---------- 关闭按钮 + 帮助按钮（标题栏右端，帮助在关闭左边） ----------
        addRenderableWidget(StockWidgets.closeButton(l.closeBtn(), this::onClose));
        this.helpButton = StockWidgets.iconButton(l.helpBtn(), StockGuiLayout.ICON_HELP,
                Component.translatable("gui.mcstock.help.title"), this::toggleHelp);
        addRenderableWidget(this.helpButton);
        // 「条件单」文字按钮（帮助按钮左边）：打开条件单覆盖层
        this.condButton = StockWidgets.button(l.condBtn(),
                Component.translatable("gui.mcstock.cond.button"), this::toggleCond);
        addRenderableWidget(this.condButton);
        buildCondWidgets();

        // ---------- 行情页：左列卡片（整张卡片就是按钮）+ 买入/卖出 + 数量/杠杆 + 持仓列表 ----------
        if (this.page == PAGE_TRADE) {
            buildTradeWidgets();
            rebuildPositionWidgets();
        } else {
            this.posButtons.clear();
            this.posWidgetSignature = "";
        }

        // ---------- 回收页：回收按钮 ----------
        if (this.page == PAGE_RECYCLE) {
            addRenderableWidget(StockWidgets.button(l.recycleBtn(),
                    recycleButtonLabel(StockClientState.recycleTotal), this::sendRecycle));
        }

        // ---------- 购买页：分类 / 搜索 / 翻页 ----------
        if (this.page == PAGE_SHOP) {
            buildShopWidgets();
        }
    }

    private void buildTradeWidgets() {
        StockGuiLayout.Layout l = this.layout;

        // 左列三张企业卡片：**纯热区按钮**（bareButton 一个像素都不画），底图由 renderBg 里的
        // StockWidgets.card(...) 提供 —— 否则卡片上会再叠一层 BTN_* 皮肤，看着像「卡片里套了个按钮」。
        // 热区的 bounds 就是卡片矩形，所以「点到哪一张」和「看到的是哪一张」永远一致。
        for (int i = 0; i < l.cards().length && i < StockDefs.COUNT; i++) {
            final int stock = i;
            addRenderableWidget(StockWidgets.bareButton(l.cards()[i], Component.empty(),
                    () -> selectStock(stock)));
        }

        // 买入 / 卖出 / 平仓：只发「我点了哪个按钮 + 当前填的数」，服务端会重算一切。
        // 平仓发 qty = 0：协议约定「非正数 = 全部平仓」，服务端走 TradeService.close(..., 0)。
        // 🔴 买入 = 红皮肤、🟢 卖出 = 绿皮肤（与 A 股「红涨绿跌」一致）。
        //    ⚠️ 颜色是**图集里画好的切片**，不是 g.setColor 染的（染色是乘法，灰底会发浑）；
        //    走 StockGuiLayout.Skin.BUY / SELL，坐标仍只有 StockGuiLayout 一个来源。
        addRenderableWidget(StockWidgets.button(l.btnBuy(), Component.translatable("gui.mcstock.btn.buy"),
                StockGuiLayout.Skin.BUY, () -> sendTrade(true)));
        addRenderableWidget(StockWidgets.button(l.btnSell(), Component.translatable("gui.mcstock.btn.sell"),
                StockGuiLayout.Skin.SELL, () -> sendTrade(false)));
        // ⚠️ 右列**没有**全局「平仓」按钮（玩家原话：「平仓按钮移动到单个仓位后面啊」）：
        //    平仓只走**持仓表格每行末尾那一个**按钮，点谁平谁（C2SClosePosition）。
        //    服务端「qty <= 0 = 平掉这只股票的全部仓」的语义保留着，但界面不再暴露
        //    （以后要做「一键全平」再说），所以 sendClose() 暂时没有调用者。

        // 数量行 / 杠杆行
        // ⚠️ 顺序不能反：EditBox#setValue 会触发 responder，先挂 responder 再 setValue 会把
        // 「打开界面时同步一次初值」当成玩家输入，白跑一次夹取（还会把 StockClientState 里的值
        // 按当前文本框内容覆盖）。先填值、后挂回调。
        this.qtyField = StockWidgets.numberField(this.font, l.qtyField(), StockGuiLayout.FIELD_MAX_CHARS,
                Component.translatable("gui.mcstock.field.qty"));
        this.qtyField.setValue(Integer.toString(qty()));
        this.qtyField.setResponder(text -> {
            if (text != null && !text.isEmpty()) {
                try {
                    StockClientState.qty = clampQty(Integer.parseInt(text));
                } catch (NumberFormatException ignored) {
                    // filter 只放行数字，这里进不来；真进来了就保留上一次的有效值
                }
            }
        });
        this.levField = StockWidgets.numberField(this.font, l.levField(), StockGuiLayout.FIELD_MAX_CHARS,
                Component.translatable("gui.mcstock.field.leverage"));
        this.levField.setValue(Integer.toString(leverage()));
        this.levField.setResponder(text -> {
            if (text != null && !text.isEmpty()) {
                try {
                    StockClientState.leverage = clampLeverage(Integer.parseInt(text));
                } catch (NumberFormatException ignored) {
                    // 同上
                }
            }
        });

        Component maxLabel = Component.translatable("gui.mcstock.btn.max");
        Component qtyLabel = Component.translatable("gui.mcstock.field.qty");
        Component levLabel = Component.translatable("gui.mcstock.field.leverage");
        StockWidgets.stepperRow(this, qtyLabel, this.qtyField,
                l.qtyUp(), l.qtyDown(), l.qtyMax(), maxLabel,
                () -> applyQty(stepUp(QTY_STEPS, qty())),
                () -> applyQty(stepDown(QTY_STEPS, qty())),
                () -> applyQty(maxAffordableQty()));
        StockWidgets.stepperRow(this, levLabel, this.levField,
                l.levUp(), l.levDown(), l.levMax(), maxLabel,
                () -> applyLeverage(stepUp(LEVERAGE_STEPS, leverage())),
                () -> applyLeverage(stepDown(LEVERAGE_STEPS, leverage())),
                () -> applyLeverage(StockGuiLayout.LEVERAGE_MAX));
    }

    /**
     * 条件单覆盖层的表单控件：**一次建好、常驻 children**，靠 {@code visible} 控制显隐。
     *
     * <p>为什么不做「打开时再建」：覆盖层打开时 {@link #render} 早退，控件表不会被原版渲染，
     * 但鼠标/键盘事件仍由原版派发 —— 常驻控件 + 显隐切换是最不容易漏事件的一种。
     * 行尾「撤」是纯绘制 + 命中判定（行数会变），所以不进这张表。
     */
    private void buildCondWidgets() {
        StockGuiLayout.Layout l = this.layout;
        this.condWidgets.clear();
        this.condWidgets.add(this.condButton);

        // 触发价：普通字体 EditBox（要能显示小数点）。数字/点/最多两位小数，
        // 解析复用 /mcstock 那个纯函数 parseCoins（金币 → 分），不在这里另写一套金额换算。
        this.condTriggerField = StockWidgets.textField(this.font, l.condTriggerField(), 12,
                Component.translatable("gui.mcstock.cond.field.trigger"));
        this.condTriggerField.setFilter(text -> text == null || text.matches("\\d*\\.?\\d{0,2}"));
        this.condTriggerField.setValue(this.condTriggerText.isEmpty()
                ? StockClientState.price(StockClientState.priceOf(clampStock(StockClientState.selected)))
                : this.condTriggerText);
        this.condTriggerField.setResponder(text -> this.condTriggerText = text == null ? "" : text);
        addRenderableWidget(this.condTriggerField);
        this.condWidgets.add(this.condTriggerField);

        this.condDirButton = StockWidgets.button(l.condDirBtn(), condDirLabel(), this::cycleCondDirection);
        addRenderableWidget(this.condDirButton);
        this.condWidgets.add(this.condDirButton);

        this.condActionButton = StockWidgets.button(l.condActionBtn(), condActionLabel(), this::cycleCondAction);
        addRenderableWidget(this.condActionButton);
        this.condWidgets.add(this.condActionButton);

        Button placeButton = StockWidgets.button(l.condPlaceBtn(),
                Component.translatable("gui.mcstock.cond.btn.place"), this::sendCondPlace);
        addRenderableWidget(placeButton);
        this.condWidgets.add(placeButton);
    }

    private Component condDirLabel() {
        return Component.translatable(ConditionalOrder.directionKey(
                ConditionalOrder.direction(this.condDirection)));
    }

    private Component condActionLabel() {
        return Component.translatable(ConditionalOrder.actionKey(
                ConditionalOrder.action(this.condAction)));
    }

    private void cycleCondDirection() {
        this.condDirection = this.condDirection == 0 ? 1 : 0;
        if (this.condDirButton != null) {
            this.condDirButton.setMessage(condDirLabel());
        }
    }

    private void cycleCondAction() {
        this.condAction = (this.condAction + 1) % ConditionalOrder.Action.values().length;
        if (this.condActionButton != null) {
            this.condActionButton.setMessage(condActionLabel());
        }
    }

    /** 挂一张单：客户端只发「我填了什么」，六个字段全部由服务端重算。 */
    private void sendCondPlace() {
        if (this.condTriggerField == null) {
            return;
        }
        McStockCommand.Coins price = McStockCommand.parseCoins(this.condTriggerField.getValue());
        if (!price.ok() || price.cents() <= 0L) {
            // 本地没资格改服务端的提示行：把非法输入恢复成当前现价，给一个可见的反馈
            this.condTriggerField.setValue(StockClientState.price(
                    StockClientState.priceOf(clampStock(StockClientState.selected))));
            return;
        }
        PacketDistributor.sendToServer(new C2SPlaceConditionalOrder(
                clampStock(StockClientState.selected), this.condDirection, price.cents(),
                this.condAction, qty(), leverage()));
    }

    private void buildShopWidgets() {
        StockGuiLayout.Layout l = this.layout;

        this.categoryButton = StockWidgets.button(l.catBtn(),
                Component.translatable("gui.mcstock.shop.category", categoryName()), this::cycleCategory);
        addRenderableWidget(this.categoryButton);
        addRenderableWidget(StockWidgets.iconButton(l.prevBtn(), StockGuiLayout.ICON_LEFT,
                Component.translatable("gui.mcstock.shop.prev"), () -> turnPage(-1)));
        addRenderableWidget(StockWidgets.iconButton(l.nextBtn(), StockGuiLayout.ICON_RIGHT,
                Component.translatable("gui.mcstock.shop.next"), () -> turnPage(1)));

        // ⚠️⚠️ 搜索框走 **textField**（普通 EditBox + 原版字体 + 只限长度），
        //    **不能**用 numberField：那个工厂返回的是自绘小号点阵字体的 NarrowTextField，
        //    而小号字体的字符集只有 `0123456789.,%+-/×·`、**没有字母** ——
        //    输入 diamond 时 this.search 其实更新了、filter 也放行了，
        //    但屏幕上一个字形都画不出来，玩家看到的就是「搜索栏不可用」。
        //    （数字框继续用 numberField，两套过滤规则不要互相串。）
        this.searchBox = StockWidgets.textField(this.font, l.searchBox(), 32,
                Component.translatable("gui.mcstock.shop.search"));
        // ⚠️ 顺序：先 setValue 再 setResponder —— EditBox#setValue 会触发 responder，
        //    反过来会把「打开界面时同步一次初值」当成玩家输入白跑一次过滤（注释见 init 那段）。
        this.searchBox.setValue(this.search);
        this.searchBox.setHint(Component.translatable("gui.mcstock.shop.search"));
        this.searchBox.setResponder(text -> {
            String next = text == null ? "" : text;
            if (!next.equals(this.search)) {
                this.search = next;
                this.shopPage = 0;      // 换了搜索词就从第一页看起
                refreshShopView();
            }
        });
        addRenderableWidget(this.searchBox);

        refreshShopView();
    }

    @Override
    public void removed() {
        super.removed();
        clearSearchFocus();
        this.pendingAction = null;
    }

    /**
     * 每 tick 收一次「上一帧登记的延迟动作」。
     *
     * <p>原版 {@code AbstractContainerScreen#tick()} 是 {@code final} 的（内部再做容器同步），
     * 所以这里覆盖的是 {@code Screen#tick()} 的下一层 {@link #containerTick()} —— 它每个 tick
     * 都会被 {@code tick()} 调到，正好当「下一帧」用。
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        Runnable action = this.pendingAction;
        if (action != null) {
            this.pendingAction = null;      // 先清再跑：动作里再登记新动作也不会被自己吃掉
            action.run();
        }
        // flash 提示的本地计时：内容变了就重新计时（这就是「新提示到来时重新计时」）
        String flashNow = StockClientState.flash == null ? "" : StockClientState.flash;
        if (!flashNow.equals(this.flashSeen)) {
            this.flashSeen = flashNow;
            this.flashShownAtMs = flashNow.isEmpty() ? 0L : System.currentTimeMillis();
        }
        // 新闻条的选片与滚动相位：**每 tick 推进**（不用挂钟 —— 暂停菜单里 world tick 停了，
        // 挂钟还在走，回来会发现字幕跳了一大段）
        tickNewsTicker();
        // 持仓表格的「平仓」按钮：**每行一个是动态控件**，签名变了（开/平/爆仓 → 行数或顺序变了、
        // 滚轮/拖滚动条 → 滚动位置变了、改窗口 → 可见行数变了）才登记一次重建，
        // 真正的增删留到下一 tick（见 pendingAction 的注释）。
        if (this.page == PAGE_TRADE) {
            // 窗口缩放会让「可见行数」变化，旧的滚动偏移可能已经越界 —— 每 tick 夹一次，
            // 免得改窗口后列表停在空屏上（签名里含行数，越界会触发重建，但先夹住更稳）。
            List<Position> shown = displayedPositions();
            this.posScroll = clampScroll(this.posScroll, shown.size());
            String signature = positionWidgetSignature();
            // ⚠️⚠️ 用**独立的标志位**登记重建，不能只靠 `defer(this::rebuildPositionWidgets)`：
            //    `defer()` 是「只保留最后一个动作」的单槽（pendingAction），
            //    同一 tick 里后面任何一个 defer(...) 都会把这次重建**顶掉**，
            //    于是删掉的按钮再也回收不了 —— 实机表现就是玩家报的
            //    「按平仓后表格已显示暂无持仓，右上角仍留着一个『平』按钮」。
            //    标志位是幂等的：无论被顶掉几次，下一 tick 都会补跑一次。
            if (!signature.equals(this.posWidgetSignature) || (shown.isEmpty() && !this.posButtons.isEmpty())) {
                this.posWidgetSignature = signature;
                this.posRebuildPending = true;
            }
        } else if (!this.posButtons.isEmpty()) {
            this.posRebuildPending = true;
        }
        if (this.posRebuildPending) {
            this.posRebuildPending = false;
            rebuildPositionWidgets();
        }
    }

    /**
     * 新闻条的状态机（每 tick 走一步）：**选片**（滚哪一条）→ **计时**（该换了没有）→ **相位**（滚到哪了）。
     *
     * <p>规则（玩家口径）：
     * <ul>
     *   <li>只滚**最新的一条**；它滚够 {@link #NEWS_HOLD_TICKS}（25 秒）之后，
     *       接着滚列表里**下一条还够新鲜**的新闻 —— 于是界面会连续播报最近若干条；</li>
     *   <li>整份列表都不新鲜（都超过 {@link #NEWS_MAX_AGE_MS}）时**留空**，一个字都不画
     *       （玩家：这一行不要再放图例/说明文字）；</li>
     *   <li>相位用 **tick 累加**，不用 {@code System.currentTimeMillis()}：暂停菜单里
     *       world tick 停了，挂钟还在走，一回来字幕就会跳掉一大段。</li>
     * </ul>
     *
     * <p>「当前是哪一条」按 {@code (股票, 语言键)} 认人而不是记下标：服务端推来的列表是
     * **最新在最前**的，新来一条会把整份列表往后挤一位，记下标就会在半途换成另一条。
     */
    private void tickNewsTicker() {
        S2CStockSnapshot.NewsItem[] feed = StockClientState.news == null
                ? new S2CStockSnapshot.NewsItem[0] : StockClientState.news;
        int index = indexOfShown(feed);
        if (this.tickerHoldTicks > 0) {
            this.tickerHoldTicks--;
        }
        if (index < 0 || this.tickerHoldTicks <= 0) {
            // 该换了：从当前这条之后找第一条还够新鲜的（找不到就留空，下一 tick 从头再来）
            int next = nextFresh(feed, index < 0 ? 0 : index + 1);
            showTicker(next >= 0 ? feed[next] : null);
        }
        // 相位：文字比矩形宽才滚；滚完一整圈（矩形宽 + 文字宽）从头再来
        StockGuiLayout.Rect row = this.layout == null ? null : this.layout.chartTicker();
        if (row == null || this.tickerText.getString().isEmpty()) {
            this.tickerPhase = 0;
            return;
        }
        if (this.tickerTextW <= row.w()) {
            this.tickerPhase = 0;                // 放得下就不滚（别为了滚动而滚动）
            return;
        }
        this.tickerPhase += StockGuiLayout.NEWS_SCROLL_SPEED;
        if (this.tickerPhase > row.w() + this.tickerTextW) {
            this.tickerPhase = 0;
        }
    }

    /** 当前显示的那条在列表里的下标（按「股票 + 语言键」找）；不在列表里返回 {@code -1}。 */
    private int indexOfShown(S2CStockSnapshot.NewsItem[] feed) {
        if (this.tickerKey == null) {
            return -1;
        }
        for (int i = 0; i < feed.length; i++) {
            S2CStockSnapshot.NewsItem item = feed[i];
            if (item != null && item.stock() == this.tickerStock && this.tickerKey.equals(item.nameKey())) {
                return i;
            }
        }
        return -1;
    }

    /** 从 {@code from} 开始，第一条「还够新鲜」的新闻下标；没有返回 {@code -1}。 */
    private int nextFresh(S2CStockSnapshot.NewsItem[] feed, int from) {
        for (int i = Math.max(0, from); i < feed.length; i++) {
            S2CStockSnapshot.NewsItem item = feed[i];
            if (item != null && newsAgeMs(item) <= NEWS_MAX_AGE_MS) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 一条新闻现在的年龄（毫秒）= 服务端给的 {@code ageTicks} + 收到这份快照之后的本地时间。
     *
     * <p>快照每秒一份，所以两次快照之间年龄是平滑累加的；快照到达那一刻接上服务端的新值。
     */
    private static long newsAgeMs(S2CStockSnapshot.NewsItem item) {
        long base = Math.max(0, item.ageTicks()) * 50L;      // 1 tick = 50ms
        long since = StockClientState.snapshotAtMs == 0L
                ? 0L : Math.max(0L, System.currentTimeMillis() - StockClientState.snapshotAtMs);
        return base + since;
    }

    /** 切换新闻条正在显示的那一条（{@code null} = 留空），并重算文本与复位相位。 */
    private void showTicker(S2CStockSnapshot.NewsItem item) {
        if (item == null) {
            this.tickerStock = Integer.MIN_VALUE;
            this.tickerKey = null;
            this.tickerText = Component.empty();
            this.tickerTextW = 0;
            this.tickerPhase = 0;
            this.tickerHoldTicks = 0;
            return;
        }
        this.tickerStock = item.stock();
        this.tickerKey = item.nameKey();
        this.tickerText = tickerLine(item);
        this.tickerTextW = this.font.width(this.tickerText);
        this.tickerPhase = 0;
        this.tickerHoldTicks = NEWS_HOLD_TICKS;
    }

    /**
     * 一条新闻的成品文本：**「【类型】企业名：发生了什么事」**（大盘那条是「【大盘】市场交投转趋温和」）。
     *
     * <p>⚠️⚠️ **不带数字、不带正负号、不按方向染色** —— 玩家定的口径（原话）：
     * 「不要直接写涨幅和熊市之类的，**玩家看到会直接买或卖**。我的初衷是让人远离赌博」。
     * 机制照旧影响价格，但界面只回答「发生了什么」，**不替玩家下买卖判断**：
     * <ul>
     *   <li>删掉了以前 `-3.6%` 的尾巴（那是把「该买该卖」直接印在脸上）；</li>
     *   <li>颜色恒为 {@link StockTheme#COLOR_ACCENT}（黄），不按 {@code permille} 的正负染色；</li>
     *   <li>文案是**叙述句**（「矿区发生塌方」），不是行情播报腔，也不出现「利好 / 利空」；</li>
     *   <li>传闻类走类型标签「坊间传闻」，让玩家知道它未必属实（而不是暗示「快买」）。</li>
     * </ul>
     *
     * <p>类型标签保留：类型是**玩法信息**（传闻可能被澄清、政策类会牵连多只股票），
     * 它描述消息的性质，不是方向。
     */
    private Component tickerLine(S2CStockSnapshot.NewsItem item) {
        EventType type = EventType.byOrdinal(item.type());
        Component body = item.stock() < 0
                ? Component.translatable(item.nameKey())
                : Component.translatable("gui.mcstock.news.stock",
                        Component.translatable(StockCatalog.nameKey(item.stock())),
                        Component.translatable(item.nameKey()));
        return Component.translatable("gui.mcstock.news.format",
                Component.translatable(type.nameKey()), body);
    }

    /**
     * 建/删持仓列表的行控件：**每行一个「平」按钮**，点哪一行就平哪一个仓。
     *
     * <p>调用时机只有三种，全都是「延迟到下一 tick」：{@code init()/rebuildWidgets()}（开界面 / 改窗口 /
     * 切页，见 {@link #buildWidgets}）、{@link #containerTick()} 发现签名变了、
     * {@link #mouseScrolled}（滚动）。**绝不在按钮回调里调它** —— 那是在渲染/点击派发的遍历途中
     * 改控件表，见 {@link #pendingAction} 的注释。
     */
    private void rebuildPositionWidgets() {
        for (Button button : this.posButtons.values()) {
            removeWidget(button);
        }
        this.posButtons.clear();
        if (this.page != PAGE_TRADE || this.layout == null) {
            this.posWidgetSignature = "";
            return;
        }
        List<Position> shown = displayedPositions();
        int rows = visiblePositionRows(shown);
        for (int i = 0; i < rows; i++) {
            Position position = shown.get(this.posScroll + i);
            final int id = position.id();
            Button button = StockWidgets.button(this.layout.posCloseBtn(i),
                    Component.translatable("gui.mcstock.btn.close_row"),
                    // 回调里只登记动作：真正的发包在下个 tick（既避开遍历中改表，也顺手合并连点）
                    () -> defer(() -> sendClosePosition(id)));
            button.setTooltip(Tooltip.create(Component.translatable("gui.mcstock.btn.close_row.tip")));
            this.posButtons.put(id, button);
            addRenderableWidget(button);
        }
        this.posWidgetSignature = positionWidgetSignature();
    }

    /** 这一帧该建几个行控件（可见行数 与 「剩下几个仓」取小）。 */
    private int visiblePositionRows(List<Position> shown) {
        if (this.layout == null) {
            return 0;
        }
        return Math.max(0, Math.min(this.layout.posVisibleRows(), shown.size() - this.posScroll));
    }

    /** 行控件的签名：滚动位置 + 可见行数 + 屏上各仓的 id。 */
    private String positionWidgetSignature() {
        List<Position> shown = displayedPositions();
        StringBuilder sb = new StringBuilder();
        sb.append(this.posScroll).append('/')
                .append(this.layout == null ? 0 : this.layout.posVisibleRows());
        for (int i = 0; i < visiblePositionRows(shown); i++) {
            sb.append('|').append(shown.get(this.posScroll + i).id());
        }
        return sb.toString();
    }

    /**
     * 持仓列表要显示的顺序：**全部仓**，按「股票下标 → 仓 id」排（顺序稳定，不会因为价格跳动而换位）。
     *
     * <p>缓存在 {@link #posCache}：一帧里要读好几次（建控件、画底、画字、算签名），
     * 而快照每次都是整份新数组，比引用就知道要不要重排。
     */
    private List<Position> displayedPositions() {
        if (this.posCacheSource != StockClientState.positions) {
            this.posCacheSource = StockClientState.positions;
            this.posCache = StockClientState.sortedPositions();
            // 仓位数变了（平掉几个 / 爆仓）：滚动位置可能已经越界，拉回最后一屏
            this.posScroll = clampScroll(this.posScroll, this.posCache.size());
        }
        return this.posCache;
    }

    /** 把滚动偏移夹进合法范围（0 ~ 最后一屏的起始行）。 */
    private int clampScroll(int offset, int total) {
        int rows = this.layout == null ? 0 : this.layout.posVisibleRows();
        if (rows <= 0 || total <= rows) {
            return 0;
        }
        int pages = (total + rows - 1) / rows;
        return Math.max(0, Math.min((pages - 1) * rows, offset));
    }

    /** 登记一个「这一帧结束后再执行」的动作（切页 / 换分类都要重建控件，见 {@link #pendingAction}）。 */
    private void defer(Runnable action) {
        this.pendingAction = action;
    }

    // ==================== 帮助覆盖层 ====================

    /** 开关帮助覆盖层：打开时把其它控件藏起来（连带内容区），只留帮助面板。 */
    private void toggleHelp() {
        this.helpOpen = !this.helpOpen;
        if (this.helpOpen) {
            this.condOpen = false;      // 两个覆盖层互斥：同时开会出现两套底衬
            this.helpScroll = 0;
        }
        this.setFocused(null);
        applyOverlayVisibility();
    }

    /** 开关条件单覆盖层；打开时把触发价预填成当前选中企业的现价（玩家一般就在这个价上下挂单）。 */
    private void toggleCond() {
        this.condOpen = !this.condOpen;
        if (this.condOpen) {
            this.helpOpen = false;
            this.condScroll = 0;
            if (this.condTriggerField != null) {
                this.condTriggerField.setValue(StockClientState.price(
                        StockClientState.priceOf(clampStock(StockClientState.selected))));
            }
        }
        this.setFocused(this.condOpen ? this.condTriggerField : null);
        applyOverlayVisibility();
    }

    /**
     * 覆盖层打开时隐藏所有**不属于这一层**的控件。
     *
     * <p>为什么隐藏而不是只盖一层绘制：控件是**独立于绘制**的事件接收者，
     * 光盖住不隐藏的话，玩家会点到看不见的买入/卖出/页签/容器槽
     * （实机踩过「点到看不见的东西」这类坑）。帮助按钮、条件单按钮分别是各自的关闭入口。
     *
     * <p>两个覆盖层互斥：条件单打开时只留 {@link #condWidgets}；帮助打开时只留帮助按钮；
     * 都关闭时条件单控件一律隐藏，其余全部可见。
     */
    private void applyOverlayVisibility() {
        for (var renderable : this.renderables) {
            if (renderable instanceof AbstractWidget widget) {
                boolean condWidget = this.condWidgets.contains(widget);
                if (this.condOpen) {
                    widget.visible = condWidget;
                } else if (this.helpOpen) {
                    widget.visible = widget == this.helpButton;
                } else {
                    widget.visible = !condWidget;
                }
            }
        }
    }

    /**
     * 画帮助覆盖层：内容区上一块内嵌面板 + 分节文字（滚轮/方向键翻页）。
     *
     * <p>画在 {@code renderLabels} 的**最后一步**，所以它在所有背景元素（图表、槽位、卡片）之上；
     * 覆盖范围只到 {@code content}（标题栏与页签保持可见可点）。
     */
    private void drawHelpOverlay(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        StockGuiLayout.Rect box = l.content();
        // ⚠️ **必须先铺一层完全不透明的底**，再画字，而且要把面板内容区**整块**盖住
        //    （含左列公司卡与右列）。两层保险：
        //      ① 覆盖层打开时 renderBg 已经早退（卡片/图表是手绘的，光隐藏控件挡不住）；
        //      ② 这里再铺一层实心 COLOR_PANEL_FILL + 1px 强调色边框。
        //    玩家报的「教程的字和公司的字、图标混在一起」就是这两条缺一导致的。
        g.fill(box.x(), box.y(), box.right(), box.bottom(), StockTheme.COLOR_PANEL_FILL);
        g.fill(box.x(), box.y(), box.right(), box.y() + 1, StockTheme.COLOR_ACCENT);
        g.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), StockTheme.COLOR_ACCENT);
        g.fill(box.x(), box.y(), box.x() + 1, box.bottom(), StockTheme.COLOR_ACCENT);
        g.fill(box.right() - 1, box.y(), box.right(), box.bottom(), StockTheme.COLOR_ACCENT);

        StockGuiLayout.Rect title = new StockGuiLayout.Rect(box.x() + 8, box.y() + 2,
                Math.max(0, box.w() - 16), StockGuiLayout.HELP_LINE_H);
        StockWidgets.label(g, font, title, Component.translatable("gui.mcstock.help.title"),
                StockTheme.COLOR_ACCENT);

        List<Component> lines = helpLines();
        int top = title.bottom() + 2;
        int rowH = StockGuiLayout.HELP_LINE_H;
        // 底部要给「滚轮翻页」那行页码留位置，否则最后一行正文会压在页码/边框上
        int bottom = box.bottom() - 2 - rowH;
        int rows = Math.max(0, (bottom - top) / rowH);
        int maxScroll = Math.max(0, lines.size() - rows);
        this.helpScroll = Math.max(0, Math.min(maxScroll, this.helpScroll));
        // ⚠️ 折行宽度 = **覆盖层内宽 − 左右内边距**（两边各 8px），一处算错就会顶到右边框上
        int textW = Math.max(0, box.w() - HELP_PAD_X * 2);
        int textX = box.x() + HELP_PAD_X;
        // 正文硬裁剪在覆盖层矩形内：就算折行算错了，也绝不会越出面板
        g.enableScissor(box.x() + 1, top, box.right() - 1, box.bottom() - 1);
        for (int i = 0; i < rows; i++) {
            int index = this.helpScroll + i;
            if (index >= lines.size()) {
                break;
            }
            StockWidgets.labelClipped(g, font,
                    new StockGuiLayout.Rect(textX, top + i * rowH, textW, rowH),
                    lines.get(index), StockTheme.COLOR_TEXT);
        }
        g.disableScissor();
        // 底部页码：只在内容比可视区多的时候出现；文字本身也在内宽之内
        if (maxScroll > 0) {
            StockWidgets.text(g, font,
                    new StockGuiLayout.Rect(textX, box.bottom() - rowH - 1, textW, rowH),
                    Component.translatable("gui.mcstock.help.more",
                            Integer.toString(this.helpScroll + 1), Integer.toString(maxScroll + 1)),
                    StockTheme.COLOR_MUTED, 1);
        }
    }

    /**
     * 帮助正文：一条一行，按语言键取，**超宽自动折行**。
     *
     * <p>为什么要折行：中文一节能到 800px，而内容区只有 547px（640×360），
     * 直接 {@code labelClipped} 会把后半句整句裁掉 —— 帮助里最关键的「确切数值」往往在句尾。
     * 折行按字符宽度累加，不做「按词断行」（中文没有空格可断）。
     */
    private List<Component> helpLines() {
        List<Component> out = new ArrayList<>();
        int limit = this.layout == null ? 200 : Math.max(40, this.layout.content().w() - HELP_PAD_X * 2);
        for (int i = 1; i <= StockGuiLayout.HELP_LINES; i++) {
            String raw = Component.translatable("gui.mcstock.help." + i).getString();
            if (raw.isEmpty() || raw.startsWith("gui.mcstock.help.")) {
                continue;       // 键不存在（原样回显）时跳过，别在面板里显示一堆键名
            }
            int from = 0;
            while (from < raw.length()) {
                int end = from + 1;
                while (end < raw.length() && this.font.width(raw.substring(from, end + 1)) <= limit) {
                    end++;
                }
                out.add(Component.literal(raw.substring(from, end)));
                from = end;
            }
        }
        return out;
    }

    /** 帮助面板正文的左右内边距（折行宽度与绘制 x 都用它，避免两处各写一个数）。 */
    private static final int HELP_PAD_X = 8;

    // ==================== 条件单覆盖层 ====================

    /**
     * 画条件单覆盖层。
     *
     * <p>⚠️ 与 {@link #drawHelpOverlay} 的差别：这里有**控件**（填写栏 + 三个按钮），
     * 所以 {@link #render} 早退之后必须手动调用 {@code widget.render(...)} 把它们画出来；
     * 顺序是「外壳 → 底衬/行框 → 控件 → 文字」，文字在最上层（行号、撤字不会被按钮盖住）。
     */
    private void drawCondOverlay(GuiGraphics g, StockGuiLayout.Layout l, Font font,
                                 int mouseX, int mouseY, float partialTick) {
        drawChrome(g, l, mouseX, mouseY);
        drawTitleBarText(g, l, font);
        drawCondBg(g, l);
        for (AbstractWidget widget : this.condWidgets) {
            if (widget.visible) {
                widget.render(g, mouseX, mouseY, partialTick);
            }
        }
        drawCondText(g, l, font);
    }

    /** 覆盖层的底：不透明衬底 + 强调色边框 + 填写栏圆角底 + 挂单列表内嵌屏。 */
    private void drawCondBg(GuiGraphics g, StockGuiLayout.Layout l) {
        StockGuiLayout.Rect box = l.condPanel();
        g.fill(box.x(), box.y(), box.right(), box.bottom(), StockTheme.COLOR_PANEL_FILL);
        g.fill(box.x(), box.y(), box.right(), box.y() + 1, StockTheme.COLOR_ACCENT);
        g.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), StockTheme.COLOR_ACCENT);
        g.fill(box.x(), box.y(), box.x() + 1, box.bottom(), StockTheme.COLOR_ACCENT);
        g.fill(box.right() - 1, box.y(), box.right(), box.bottom(), StockTheme.COLOR_ACCENT);
        StockWidgets.fieldBox(g, l.condTriggerField());
        if (l.condList().h() > 0) {
            StockWidgets.insetBox(g, l.condList(), StockTheme.COLOR_INSET_FILL, StockTheme.COLOR_BORDER);
        }
        clampCondScroll(l);
        int rows = Math.min(l.condVisibleRows(),
                Math.max(0, StockClientState.conditionalOrders.length - this.condScroll));
        for (int i = 0; i < rows; i++) {
            StockGuiLayout.Rect row = l.condRow(i);
            if (i > 0) {
                g.fill(row.x() + 3, row.y(), row.right() - 3, row.y() + 1, StockTheme.COLOR_GRID_LINE);
            }
            StockGuiLayout.Rect cancel = l.condCancelBtn(i);
            g.fill(cancel.x(), cancel.y(), cancel.right(), cancel.bottom(), StockTheme.COLOR_BORDER);
        }
    }

    /** 覆盖层的文字：标题 / 当前企业与数量杠杆 / 说明 / 每行挂单 / 行尾「撤」。 */
    private void drawCondText(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        clampCondScroll(l);
        StockWidgets.label(g, font, l.condTitle(),
                Component.translatable("gui.mcstock.cond.title"), StockTheme.COLOR_ACCENT);
        int stock = clampStock(StockClientState.selected);
        StockWidgets.labelClipped(g, font, l.condInfo(),
                Component.translatable("gui.mcstock.cond.info",
                        Component.translatable(StockCatalog.nameKey(stock)),
                        StockClientState.price(StockClientState.priceOf(stock)), qty(), leverage()),
                StockTheme.COLOR_MUTED);
        StockWidgets.labelClipped(g, font, l.condHelp(),
                Component.translatable("gui.mcstock.cond.help"), StockTheme.COLOR_MUTED);

        ConditionalOrder[] orders = StockClientState.conditionalOrders;
        if (l.condVisibleRows() <= 0) {
            return;
        }
        if (orders.length == 0) {
            StockWidgets.labelClipped(g, font, l.condText(0),
                    Component.translatable("gui.mcstock.cond.empty"), StockTheme.COLOR_MUTED);
            return;
        }
        int rows = Math.min(l.condVisibleRows(), Math.max(0, orders.length - this.condScroll));
        for (int i = 0; i < rows; i++) {
            ConditionalOrder order = orders[this.condScroll + i];
            Component line = Component.translatable("gui.mcstock.cond.row",
                    order.id(),
                    Component.translatable(StockCatalog.nameKey(order.stock())),
                    Component.translatable(ConditionalOrder.directionKey(order.direction())),
                    StockClientState.price(order.triggerCents()),
                    Component.translatable(ConditionalOrder.actionKey(order.action())),
                    order.qty(), order.leverage());
            StockWidgets.labelClipped(g, font, l.condText(i), line, StockTheme.COLOR_TEXT);
            StockWidgets.text(g, font, l.condCancelBtn(i),
                    Component.translatable("gui.mcstock.cond.btn.cancel"), StockTheme.COLOR_TEXT, 0);
        }
    }

    /** 挂单列表最多能往上滚几行（滚轮与每帧收口共用这一份换算）。 */
    private int condMaxScroll() {
        int rows = this.layout == null ? 0 : this.layout.condVisibleRows();
        return Math.max(0, StockClientState.conditionalOrders.length - rows);
    }

    private void clampCondScroll(StockGuiLayout.Layout l) {
        int rows = l == null ? 0 : l.condVisibleRows();
        int max = Math.max(0, StockClientState.conditionalOrders.length - rows);
        this.condScroll = Math.max(0, Math.min(max, this.condScroll));
    }

    // ==================== 主动要一份完整快照 ====================

    /**
     * 向服务端要一份**带 K 线**的完整快照（{@code C2SRequestSnapshot}，空包）。
     *
     * <p>为什么需要：服务端的推送是分两档的 —— 跳价那一刻推「完整快照」（含 K 线），
     * 其余每秒只推一份不带 K 线的轻量包（只更新倒计时/价格）。于是「刚开界面」「刚切页」
     * 这些时刻手里可能一份 K 线都没有，玩家要干等最多 2 秒才看到走势。主动问一次就没有这个空窗。
     *
     * <p>服务端收到后当场回一份完整快照（{@code StockService#sendSnapshot}，纯读操作、不改状态），
     * 所以这里不做频率限制：玩家点页签 / 点卡片的频率远低于「每秒一次」。
     */
    private void requestSnapshot() {
        PacketDistributor.sendToServer(C2SRequestSnapshot.INSTANCE);
    }

    /**
     * ⚠️ **必须返回 false**：单人游戏里返回 true 会让世界暂停，而行情是**服务端每 100 tick 跳一次价**的
     * —— 世界一停，「2 秒一跳」的倒计时就永远停在 2s，整个玩法变成静态图片。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 切页：重新摆槽位 + 重建控件。三个页签和所有内部跳转都走这里。 */
    private void setPage(int next) {
        int clamped = Math.max(0, Math.min(2, next));
        if (this.page == clamped) {
            return;
        }
        this.page = clamped;
        clearSearchFocus();
        this.menu.placeSlots(this.page, this.layout);
        // rebuildWidgets() 会先 clearWidgets() 再 init()，等于「重新算布局 + 重建控件」一条龙
        rebuildWidgets();
        // 切页后立刻要一份带 K 线的快照（同 init：不然要等下一次跳价才看到走势）
        requestSnapshot();
        // 页签的选中态：重建控件时已按 i == this.page 设好（见 buildWidgets 的 tabButton 调用），
        // 这里不用再补 —— setPage 之后走的就是那条重建路径。
    }

    // ==================== 输入 ====================

    /**
     * 购买页的网格点击。
     *
     * <p>网格**不是容器槽**（AGENTS.md 4.7：点一下直接下单），所以没有 {@code Slot} 可以挂点击，
     * 必须在这里自己判定格子。
     *
     * <p>⚠️ <b>顺序很关键：网格判定必须在 {@code super.mouseClicked(...)} 之前</b>。
     * 原版容器屏只要在任何一条路径上返回 true（点到背包槽、正在拖拽、快捷移动……），
     * 后面就再也不会执行 —— 实机反馈「点击商品没反应」就是踩在这里：
     * 先问 super，super 一旦吃掉这一击，网格判定形同不存在。
     * 所以现在先判网格，命中就发包并 return true；没命中才把这一击交给原版。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.condOpen) {
            // ⚠️ 覆盖层打开时**不走 super.mouseClicked**：那条路径会命中底下的容器槽
            //    （行情页的槽虽然被摆到屏幕外，回收/购买页的槽可都还在原位）。
            //    render() 早退了，事件通道却还在，所以这里只把这一击交给本层控件。
            for (AbstractWidget widget : this.condWidgets) {
                if (widget.visible && widget.mouseClicked(mouseX, mouseY, button)) {
                    this.setFocused(widget);
                    return true;
                }
            }
            // 行尾「撤」是画出来的框（不是控件），自己按矩形命中
            if (button == 0 && this.layout != null) {
                clampCondScroll(this.layout);
                int rows = Math.min(this.layout.condVisibleRows(),
                        Math.max(0, StockClientState.conditionalOrders.length - this.condScroll));
                for (int i = 0; i < rows; i++) {
                    if (inside(this.layout.condCancelBtn(i), mouseX, mouseY)) {
                        int index = this.condScroll + i;
                        if (index < StockClientState.conditionalOrders.length) {
                            PacketDistributor.sendToServer(new C2SCancelConditionalOrder(
                                    StockClientState.conditionalOrders[index].id()));
                        }
                        return true;
                    }
                }
            }
            return true;   // 覆盖层里的一切点击都吃掉，绝不穿透
        }
        // 持仓表格右缘的滚动条滑块：按住就开始拖（在控件派发**之前**判，
        // 免得滑块压在按钮边缘时被按钮抢走）
        if (this.page == PAGE_TRADE && button == 0 && this.layout != null) {
            StockGuiLayout.Rect thumb = this.layout.posScrollThumb(this.posScroll,
                    displayedPositions().size());
            if (thumb.h() > 0 && inside(thumb, mouseX, mouseY)) {
                this.draggingPosScrollbar = true;
                this.posScrollGrabDy = (int) mouseY - thumb.y();
                return true;
            }
        }
        if (this.page == PAGE_SHOP && this.layout != null && (button == 0 || button == 1)) {
            int index = shopIndexAt(mouseX, mouseY);
            // ⚠️ 这里**不再**打诊断日志（曾经有一对「clickIndex vs hoverIndex 应当相等」的探针）。
            //    那条不变式靠 `absoluteShopIndex` 一处换算保证：shopIndexAt 与 startIndexOnGrid
            //    都走它，所以两者**必然一致** —— 谁要是把换算写成两份，那条不变式就断了。
            if (index >= 0 && index < this.shopView.size()) {
                ShopEntry entry = this.shopView.get(index);
                // 左键 = 买 1 个；Shift + 左键 = 买 1 组（count <= 0 是协议里的「买 1 组」，
                // 让服务端去查这个物品一组几个 —— 客户端不猜堆叠数，改堆叠规则不用动客户端）。
                // 右键也保留成「买 1 个」，方便习惯右键的人。
                int count = (button == 0 && hasShiftDown()) ? 0 : 1;
                PacketDistributor.sendToServer(new C2SPurchaseItem(entry.id(), count));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 拖动持仓表格右缘的滚动条滑块。
     *
     * <p>做法：把「鼠标 y 在轨道里的比例」换算成「首行下标」——
     * <pre>
     *   t = (mouseY - trackTop - grabbedOffset) / (trackH - thumbH)   // 0..1
     *   firstRow = round(t * (totalRows - visibleRows))
     * </pre>
     * {@code grabbedOffset} 是按下时鼠标在滑块内部的偏移，记住它才不会「一按滑块就跳到指针下」。
     * 换算结果夹进合法范围，然后照常走「登记重建、下一 tick 执行」。
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.condOpen) {
            return true;   // 覆盖层里没有可拖的东西，也不让底下容器的拖拽生效
        }
        if (button == 0 && this.draggingPosScrollbar && this.layout != null) {
            StockGuiLayout.Rect track = this.layout.posScrollTrack();
            int rows = this.layout.posVisibleRows();
            List<Position> shown = displayedPositions();
            int total = shown.size();
            if (track.h() > 0 && rows > 0 && total > rows) {
                StockGuiLayout.Rect thumb = this.layout.posScrollThumb(this.posScroll, total);
                int span = Math.max(1, track.h() - thumb.h());
                int maxFirst = Math.max(1, total - rows);
                double t = (mouseY - track.y() - this.posScrollGrabDy) / span;
                int first = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * maxFirst);
                int next = clampScroll(first, total);
                if (next != this.posScroll) {
                    this.posScroll = next;
                    defer(this::rebuildPositionWidgets);
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingPosScrollbar = false;
        if (this.condOpen) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 鼠标滚轮：购买页翻页 / 行情页滚持仓表格 / 帮助覆盖层翻页。
     * 原版的滚动是给创造模式物品栏翻页用的，这里自己接。
     *
     * <p>⚠️ 三处滚动**互不影响**：各判各的区域、各吃各的事件。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.condOpen && scrollY != 0 && this.layout != null
                && inside(this.layout.condList(), mouseX, mouseY)) {
            clampCondScroll(this.layout);
            this.condScroll = Math.max(0, Math.min(condMaxScroll(),
                    this.condScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        if (this.condOpen) {
            return true;      // 覆盖层打开时滚轮不做别的事
        }
        if (this.helpOpen && scrollY != 0) {
            // 帮助覆盖层打开时，滚轮只翻帮助的页，不影响底下的表格/购买页
            this.helpScroll = Math.max(0, this.helpScroll + (scrollY > 0 ? -1 : 1));
            return true;
        }
        if (this.page == PAGE_SHOP && scrollY != 0 && shopIndexAt(mouseX, mouseY) >= 0) {
            turnPage(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (this.page == PAGE_TRADE && scrollY != 0 && this.layout != null
                && inside(this.layout.posList(), mouseX, mouseY)) {
            scrollPositions(scrollY > 0 ? 1 : -1);
            return true;    // 吃掉：这一页的滚轮没有别的用途
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * ESC / 方向键。
     *
     * <p>帮助覆盖层打开时 **ESC 只关掉覆盖层**，不关整个界面 —— 否则玩家按 ESC 的直觉是
     * 「退出一层」，直接把股市关了会很突兀。上下方向键也给帮助翻页（滚轮之外的键盘路径）。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.condOpen) {
            // ESC 只关覆盖层；其余按键交给 super，让触发价填写栏能正常打字
            // （帮助覆盖层会吃掉所有按键，条件单这里不能照抄）
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                toggleCond();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (this.helpOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                toggleHelp();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) {
                this.helpScroll++;
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
                this.helpScroll = Math.max(0, this.helpScroll - 1);
                return true;
            }
            // 帮助打开时其它按键一律吃掉：避免底下的输入框/按钮收到键
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 滚持仓列表：一格 = **一屏**（玩家看到的是「第几屏」，页码才对得上）。
     *
     * <p>滚动改的是控件位置与行内容，所以要重建行控件 —— 照样走「登记动作、下一 tick 执行」，
     * 不在滚轮处理里当场改控件表。
     */
    private void scrollPositions(int pages) {
        if (this.layout == null || this.layout.posVisibleRows() <= 0) {
            return;
        }
        int rows = this.layout.posVisibleRows();
        int next = clampScroll(this.posScroll + pages * rows, displayedPositions().size());
        if (next != this.posScroll) {
            this.posScroll = next;
            defer(this::rebuildPositionWidgets);
        }
    }

    /** 点在矩形里？鼠标事件给的是**未缩放的逻辑坐标**，布局矩形也是，可以直接比。 */
    private static boolean inside(StockGuiLayout.Rect rect, double x, double y) {
        return x >= rect.x() && y >= rect.y() && x < rect.right() && y < rect.bottom();
    }

    /**
     * 网格坐标 → **shopView 的绝对下标**（唯一换算入口）。
     *
     * <p>⚠️⚠️ <b>为什么必须只有一个入口</b>：这个换算以前有**两份实现** ——
     * 点击路径 {@code shopIndexAt} 返回的是「本页第几格」（`row * gridCols + col`，**漏了页偏移**），
     * 而 tooltip 路径 {@code startIndexOnGrid} 加了页偏移。于是
     * **悬停显示的是第 2 页的钻石、点下去买的是第 1 页同位置的泥土**
     * （玩家实机反馈：「购买页面切换后，买东西还是按第一页的买」）。
     * 现在两条路径都走这一个函数，从根上断掉「算法不一致」的可能 ——
     * 以后要改换算，只改这里。
     *
     * @param col 网格列（0 起）
     * @param row 网格行（0 起）
     * @return {@code shopView} 的绝对下标（**不含**越界夹取，调用方各自判 {@code < shopView.size()}）
     */
    private int absoluteShopIndex(int col, int row) {
        StockGuiLayout.Layout l = this.layout;
        if (l == null) {
            return -1;
        }
        return this.shopPage * l.pageSize() + row * l.gridCols() + col;
    }

    /**
     * 鼠标在购买页网格的哪一格上，换算成 {@code shopView} 的**绝对下标**（不在网格里返回 -1）。
     *
     * <p>越界（含「这一页已经翻过了、格子是空的」）返回 -1：返回值**已经做过页内边界检查**，
     * 调用方还要再判一次 {@code < shopView.size()}（分类/搜索会把列表变短）。
     */
    private int shopIndexAt(double mouseX, double mouseY) {
        StockGuiLayout.Layout l = this.layout;
        if (l == null) {
            return -1;
        }
        StockGuiLayout.Rect grid = l.grid();
        if (mouseX < grid.x() || mouseY < grid.y() || mouseX >= grid.right() || mouseY >= grid.bottom()) {
            return -1;
        }
        int col = (int) ((mouseX - grid.x()) / StockGuiLayout.SLOT);
        int row = (int) ((mouseY - grid.y()) / StockGuiLayout.SLOT);
        if (col < 0 || row < 0 || col >= l.gridCols() || row >= l.gridRows()) {
            return -1;
        }
        return absoluteShopIndex(col, row);
    }

    // ==================== 与「绝对坐标」有关的三处原版行为 ====================
    //
    // 原版容器屏的坐标体系是「GUI 局部坐标 = 槽位坐标 - (leftPos, topPos)」，而我们的
    // leftPos/topPos 被按到了 (0,0)、槽位坐标又是布局给出的**屏幕绝对坐标**。
    // 于是下面三处原版行为必须跟着改，否则会出现「点得到但判成没点到」「点到面板外丢东西」：

    /**
     * 悬停判定：原版会先减掉 {@code leftPos/topPos}，我们这里是 0，所以直接用绝对坐标比。
     *
     * <p>这个方法是 {@code private Slot findSlot} 与渲染高亮共用的，**不改它就点不到背包槽**。
     */
    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= (double) (x - 1) && mouseX < (double) (x + width + 1)
                && mouseY >= (double) (y - 1) && mouseY < (double) (y + height + 1);
    }

    /**
     * 「点到界面外 = 把手上的东西丢出去」：我们**不做**这件事。
     *
     * <p>原版按 {@code imageWidth/Height} 那个矩形判定，而本界面的可见区域是
     * {@link StockGuiLayout.Layout#panel()} —— 面板外面还有一圈背景，玩家在那儿点一下
     * 就把手里的物品丢了，属于纯粹的误伤。要丢东西用 Q 键。
     */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        return false;
    }

    // ==================== 业务动作 ====================

    private void sendTrade(boolean buy) {
        commitFields();
        PacketDistributor.sendToServer(new C2STradeStock(StockClientState.selected, qty(),
                leverage(), buy));
    }

    /**
     * 一键平仓：{@code qty = 0} 是协议里的「**平掉这只股票的全部仓**」约定。
     *
     * <p>服务端 {@code StockService.trade} 收到 {@code qty <= 0} 会走 {@code closeAll} ——
     * 按市价把这只股票上的仓位**逐个**平掉（各按各的均价结算），并回一条「平仓 N 股 · 结算 ±X」。
     * 客户端**不需要**知道持了多少股：数量由服务端自己那份持仓决定（客户端说了不算，符合 AGENTS.md 5.2）。
     *
     * <p>逐仓平仓见 {@link #sendClosePosition(int)}（持仓列表每行那个「平」按钮）。
     */
    private void sendClose() {
        PacketDistributor.sendToServer(new C2STradeStock(StockClientState.selected, 0,
                leverage(), true));
    }

    /**
     * 平掉**某一个**仓：把服务端发来的仓位 id 原样送回去（{@link C2SClosePosition}）。
     *
     * <p>客户端只说「我点了哪一行」——这个 id 是不是自己的仓、现在值多少钱，服务端会重新查一遍。
     * 手上这一帧的 id 可能已经过期（那一仓刚好被爆掉），服务端回一句「没有可平的持仓」即可。
     */
    private void sendClosePosition(int positionId) {
        PacketDistributor.sendToServer(new C2SClosePosition(positionId));
    }

    private void sendRecycle() {
        PacketDistributor.sendToServer(new C2SRecycleBar());
    }

    /**
     * 切分类：只换文案 + 重算列表，**不重建控件**。
     *
     * <p>为什么不用 {@code rebuildWidgets()}（哪怕延迟一 tick 也不行）：重建会把
     * {@link EditBox} 换成新对象，搜索框里已经输入的词、光标位置、焦点全丢。
     * 分类按钮的文案是它自己的一块数据，直接 {@code setMessage} 就够
     * （{@code AbstractWidget.setMessage} 是 public）。
     */
    private void cycleCategory() {
        this.category = (this.category + 1) % CATEGORY_KEYS.length;
        this.shopPage = 0;
        if (this.categoryButton != null) {
            this.categoryButton.setMessage(
                    Component.translatable("gui.mcstock.shop.category", categoryName()));
        }
        refreshShopView();
        // 分类/翻页都在客户端过滤，但顺手要一份快照能让「目录刚变、钱刚花掉」这类状态立刻刷新
        requestSnapshot();
    }

    /**
     * 点企业卡片：换选中项 + 主动要一份带 K 线的快照。
     *
     * <p>选中项是纯客户端的（{@code StockClientState.selected}），换一家并不需要新数据 ——
     * 但快照里**每只股票都有自己的 K 线**，刚开界面时手里可能只有一份旧数据，
     * 顺手再要一次，切过去立刻有图。
     */
    private void selectStock(int stock) {
        StockClientState.selected = Math.max(0, Math.min(StockDefs.COUNT - 1, stock));
        requestSnapshot();
    }

    private void turnPage(int delta) {
        int total = pageCount();
        int next = this.shopPage + delta;
        if (next < 0) {
            next = total - 1;   // 翻到头就绕回去，省得玩家以为坏了
        } else if (next >= total) {
            next = 0;
        }
        this.shopPage = next;
        requestSnapshot();
    }

    /** 把两个填写栏里的文字夹进合法区间，写回 {@link StockClientState} 并回写文本框。 */
    private void commitFields() {
        applyQty(parseOr(this.qtyField, qty()));
        applyLeverage(parseOr(this.levField, leverage()));
    }

    private static int parseOr(EditBox box, int fallback) {
        if (box == null) {
            return fallback;
        }
        String text = box.getValue();
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyQty(int value) {
        int clamped = clampQty(value);
        StockClientState.qty = clamped;
        if (this.qtyField != null) {
            this.qtyField.setValue(Integer.toString(clamped));
        }
    }

    private void applyLeverage(int value) {
        int clamped = clampLeverage(value);
        StockClientState.leverage = clamped;
        if (this.levField != null) {
            this.levField.setValue(Integer.toString(clamped));
        }
    }

    /**
     * 「数量 MAX」= 按现在余额 + 现在杠杆吃得住的最大股数。
     *
     * <p>⚠️ 走 {@link PositionMath#maxAffordable}（服务端 {@code TradeService} 用的**同一个函数**），
     * 别在这里另写一遍公式 —— 客户端的 MAX 只是「填个好看的数字」，服务端照样会再夹一次；
     * 两边公式一旦漂移，玩家就会看到「MAX 填进去却提示余额不够」。
     */
    private int maxAffordableQty() {
        int stock = StockClientState.selected;
        if (stock < 0 || stock >= StockDefs.COUNT) {
            return StockGuiLayout.QTY_MIN;
        }
        long price = StockClientState.prices[stock];
        int max = PositionMath.maxAffordable(StockClientState.cash, price, leverage());
        return Math.max(StockGuiLayout.QTY_MIN, Math.min(StockGuiLayout.QTY_MAX, max));
    }

    private static int qty() {
        return clampQty(StockClientState.qty);
    }

    private static int leverage() {
        return clampLeverage(StockClientState.leverage);
    }

    private static int clampQty(int value) {
        return Math.max(StockGuiLayout.QTY_MIN, Math.min(StockGuiLayout.QTY_MAX, value));
    }

    private static int clampLeverage(int value) {
        return Math.max(StockGuiLayout.LEVERAGE_MIN, Math.min(StockGuiLayout.LEVERAGE_MAX, value));
    }

    /** 在档位表里往上走一档（已经到头就停在最后一档）。 */
    private static int stepUp(int[] ladder, int current) {
        for (int value : ladder) {
            if (value > current) {
                return value;
            }
        }
        return ladder[ladder.length - 1];
    }

    /** 在档位表里往下走一档（已经到底就停在第一档）。 */
    private static int stepDown(int[] ladder, int current) {
        for (int i = ladder.length - 1; i >= 0; i--) {
            if (ladder[i] < current) {
                return ladder[i];
            }
        }
        return ladder[0];
    }

    // ==================== 购买页的目录 / 过滤 ====================

    /** 购买页当前显示的一条：注册名 + 单价（分）。 */
    private record ShopEntry(ResourceLocation id, int unitCents, ItemStack stack) {
    }

    /**
     * 重建购买页的显示列表：目录 → 分类 → 搜索。
     *
     * <p>目录来自服务端（{@code S2CShopCatalog} → {@link StockClientState#shopItems} /
     * {@link StockClientState#shopPrices}）；**过滤与分页在客户端做**，因为整份目录已经
     * 一次性在手里了，点一次分类不必再跑一趟网络。
     */
    private void refreshShopView() {
        this.shopView.clear();
        int count = StockClientState.shopCount();
        String needle = this.search == null ? "" : this.search.trim().toLowerCase();
        for (int i = 0; i < count; i++) {
            ResourceLocation id = StockClientState.shopItems[i];
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (!matchesCategory(stack)) {
                continue;
            }
            if (!needle.isEmpty() && !matchesSearch(stack, id, needle)) {
                continue;
            }
            this.shopView.add(new ShopEntry(id, StockClientState.shopPrices[i] * 100, stack));
        }
        int total = pageCount();
        if (this.shopPage >= total) {
            this.shopPage = 0;
        }
    }

    private static boolean matchesSearch(ItemStack stack, ResourceLocation id, String needle) {
        // 名称（本地化后）与注册名都比一遍：中文名搜得到，英文 id 也搜得到
        return stack.getHoverName().getString().toLowerCase().contains(needle)
                || id.toString().toLowerCase().contains(needle);
    }

    /**
     * 分类判定（顺序有讲究，先特判后泛判）。
     *
     * <p>为什么不复用服务端：服务端那份表只管「能不能买、多少钱」，分类纯粹是**展示**问题，
     * 而且原版标签 {@code ItemTags} 在客户端是同步过的，这里判定和在服务端判定结果一致。
     */
    private boolean matchesCategory(ItemStack stack) {
        if (this.category == 0) {
            return true;
        }
        boolean food = isFood(stack);
        boolean tool = isTool(stack);
        boolean block = stack.getItem() instanceof BlockItem;
        return switch (this.category) {
            case 1 -> !food && !tool && (isMaterial(stack) || block);
            case 2 -> food;
            case 3 -> !food && !tool && block;
            case 4 -> tool;
            default -> !food && !tool && !block && !isMaterial(stack);
        };
    }

    /** 食物：用「吃/喝」这个使用动作判定，比维护一张食物表可靠（模组加的食物也能认出来）。 */
    private static boolean isFood(ItemStack stack) {
        UseAnim anim = stack.getItem().getUseAnimation(stack);
        return anim == UseAnim.EAT || anim == UseAnim.DRINK;
    }

    /** 工具/武器：原版的工具标签 + 有耐久的东西。 */
    private static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
                || stack.is(ItemTags.ARROWS) || stack.getMaxStackSize() == 1;
    }

    /** 材料：把原版「一堆同类方块/杂项」的标签打包当成材料（没有 ingot/gem 这种总标签，只能逐个列）。 */
    private static boolean isMaterial(ItemStack stack) {
        return stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS) || stack.is(ItemTags.WOOL)
                || stack.is(ItemTags.SAND) || stack.is(ItemTags.DIRT) || stack.is(ItemTags.TERRACOTTA)
                || stack.is(ItemTags.COALS) || stack.is(ItemTags.FLOWERS) || stack.is(ItemTags.FENCES)
                || stack.is(ItemTags.STAIRS) || stack.is(ItemTags.SLABS) || stack.is(ItemTags.WALLS)
                || stack.is(ItemTags.DOORS) || stack.is(ItemTags.TRAPDOORS) || stack.is(ItemTags.BUTTONS)
                || stack.is(ItemTags.BEDS) || stack.is(ItemTags.RAILS) || stack.is(ItemTags.LEAVES)
                || stack.is(ItemTags.STONE_BRICKS) || stack.is(ItemTags.BEACON_PAYMENT_ITEMS)
                || stack.is(ItemTags.TRIM_MATERIALS) || stack.is(ItemTags.DECORATED_POT_SHERDS)
                || stack.is(ItemTags.STONE_CRAFTING_MATERIALS);
    }

    private Component categoryName() {
        return Component.translatable(CATEGORY_KEYS[this.category]);
    }

    /** 过滤后一共几页（至少 1 页，空目录时页码显示 1/1 而不是 1/0）。 */
    private int pageCount() {
        int size = this.layout == null ? 0 : this.layout.pageSize();
        if (size <= 0) {
            return 1;
        }
        return Math.max(1, (this.shopView.size() + size - 1) / size);
    }

    private void clearSearchFocus() {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
        }
    }

    private static String tabKey(int page) {
        return switch (page) {
            case PAGE_RECYCLE -> "gui.mcstock.tab.recycle";
            case PAGE_SHOP -> "gui.mcstock.tab.shop";
            default -> "gui.mcstock.tab.trade";
        };
    }

    // ==================== 绘制：背景层 ====================

    /**
     * ⚠️⚠️ **帮助覆盖层打开时，整个界面只画覆盖层这一层。**
     *
     * <p>为什么必须在 {@code render} 这一层早退，而不是在 {@code renderBg} 里早退 ——
     * 原版 {@code AbstractContainerScreen#render} 的调用链是
     * （类名见 {@code AbstractContainerScreen}，方法体已核对）：
     * <pre>
     *   render(g, mouseX, mouseY, partialTick)
     *     ├─ if (this.renderBackground) ...            ← 世界模糊底，本项目关掉了
     *     ├─ pose.translate(leftPos, topPos)           ← leftPos/topPos 已按到 (0,0)
     *     ├─ renderBg(...)                             ← 手绘层：卡片、图表、槽位底、网格
     *     │    └─ drawChrome / drawTradeBg / drawRecycleBg / drawShopBg
     *     ├─ renderSlots(...)                          ← **容器槽与槽里的物品**（独立一层！）
     *     ├─ renderLabels(...)                         ← 文字层
     *     ├─ super.render(...)                         ← **所有控件**：买卖按钮、数量/杠杆
     *     │                                               输入框、页签、行尾「平仓」按钮
     *     └─ renderTooltip(...)
     * </pre>
     * 所以我上一轮只在 {@code renderBg} 早退是**不完整的**：控件（{@code super.render}）、
     * 容器槽（{@code renderSlots}）照样会画出来 —— 截图里「买卖按钮、数量/杠杆行清晰可见」
     * 就是这个原因（卡片/图表被挡住了，但控件层没有被挡住）。
     *
     * <p>现在改成：{@code helpOpen} 为真时**只画覆盖层**，{@code renderBg / renderSlots /
     * super.render / tooltip} 一个都不调 —— 覆盖层是唯一的绘制者，物理上不可能透出任何东西。
     * 覆盖层自己铺满 {@code content} 的实心底衬（见 {@link #drawHelpOverlay}），
     * 并顺手把标题栏下方那一行 flash 也盖住。
     */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.helpOpen && this.layout != null) {
            drawHelpOverlay(g, this.layout, this.font);
            return;
        }
        if (this.condOpen && this.layout != null) {
            drawCondOverlay(g, this.layout, this.font, mouseX, mouseY, partialTick);
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 画所有「在控件后面」的东西：面板、标题栏、页签底、卡片底、内嵌屏、槽位框、阴阳柱……
     *
     * <p>⚠️ 原版 {@code AbstractContainerScreen#render} 在调用本方法**之前**已经
     * {@code pose().translate(leftPos, topPos)}，而 {@code init()} 里把两者按到了 (0,0)，
     * 所以这里的坐标就是屏幕绝对坐标 —— 直接使用 layout 的矩形即可。
     *
     * <p>帮助覆盖层打开时这里也早退（虽然 {@link #render} 已经拦住了整条链，
     * 但留这一道是因为 {@code renderBg} 是 protected、别的路径也可能直接调到它）。
     */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        StockGuiLayout.Layout l = this.layout;
        if (l == null) {
            return;
        }
        drawChrome(g, l, mouseX, mouseY);
        if (this.helpOpen) {
            return;
        }
        if (this.condOpen) {
            // render() 已经早退，这里留一道是为了 renderBg 被别的路径直接调到时也不画底下的页
            drawCondBg(g, l);
            return;
        }
        switch (this.page) {
            case PAGE_RECYCLE -> drawRecycleBg(g, l);
            case PAGE_SHOP -> drawShopBg(g, l, mouseX, mouseY);
            default -> drawTradeBg(g, l, mouseX, mouseY);
        }
    }

    /** 三个页面共用的外壳：面板边框 + 标题栏 + 页签 + 关闭按钮。 */
    private void drawChrome(GuiGraphics g, StockGuiLayout.Layout l, int mouseX, int mouseY) {
        StockWidgets.panelFrame(g, l.panel());

        StockGuiLayout.Rect title = l.titleBar();
        StockWidgets.blit(g, StockGuiLayout.TITLE_TILE, title.x() + 1, title.y() + 1,
                title.w() - 2, title.h() - 1);

        // ⚠️ 页签底**不在这里画**（与关闭按钮的叉同一个病根）：
        //    这里（renderBg）画的 TAB_ACTIVE/TAB_INACTIVE 会被随后绘制的页签**按钮皮肤**
        //    BTN_NORMAL/HOVER 整个盖住 —— 实测那几个切片中心 alpha = **255（完全不透明）**，
        //    于是三个页签外观一模一样，玩家看不出自己在哪一页（可用性问题）。
        //    现在由 StockButton 的**选中态**自己选切片（见 StockWidgets#tabButton），
        //    画在按钮的 renderWidget 里、皮肤位置就是那个切片本身，顺序天然正确。
    }

    // ==================== 绘制：回收页 ====================

    private void drawRecycleBg(GuiGraphics g, StockGuiLayout.Layout l) {
        StockGuiLayout.Rect bar = l.bar();
        // ⚠️⚠️ 底纹行数必须和**栏高**一致（用 barRows() 反推），不能写死 BAR_ROWS：
        //    栏折成 1 行时如果还铺 2 行底纹，第 2 行就画到空白区/明细上了；
        //    更要紧的是那一行**槽位也还活着**（见 StockGuiLayout#barSlot）——
        //    玩家能往 9 个看不见的格子里塞东西。现在三处（栏高/底纹/槽位）同一个来源。
        StockWidgets.slotGrid(g, bar.x(), bar.y(), StockGuiLayout.BAR_COLS, StockGuiLayout.barRows(l));

        StockGuiLayout.Rect inv = l.inv();
        StockWidgets.slotGrid(g, inv.x(), inv.y(), StockGuiLayout.INV_COLS, StockGuiLayout.INV_ROWS);
        StockGuiLayout.Rect hotbar = l.hotbar();
        StockWidgets.slotGrid(g, hotbar.x(), hotbar.y(), StockGuiLayout.INV_COLS, 1);

        // 合计块 + 回收明细都是内嵌屏。
        // ⚠️⚠️ **被折叠（宽或高为 0）的块一律不画**：`RecyclePlan` 会把合计块折成 0×0
        //     （MERGED_INFO/COMPACT 档把它折进按钮），那时画一个 0 高的圆角框只会剩一条描边；
        //     更要命的是文字层（drawRecycleText）如果不判就会把「合计 xx」画到**回收按钮**那块上
        //     —— 属于被按钮盖住的潜伏重叠。所以每一处取块矩形的地方都先判尺寸。
        if (l.barInfo().w() > 0 && l.barInfo().h() > 0) {
            StockWidgets.insetBox(g, l.barInfo(), StockTheme.COLOR_INSET_FILL, StockTheme.COLOR_BORDER);
        }
        if (l.detailLines() > 0 && l.barDetail().w() > 0 && l.barDetail().h() > 0) {
            StockWidgets.insetBox(g, l.barDetail(), StockTheme.COLOR_INSET_FILL, StockTheme.COLOR_BORDER);
        }

        // 背包上面那条分隔线（效果图里就是一条细线）
        StockGuiLayout.Rect label = l.invLabel();
        g.fill(label.x(), label.y() - 3, label.right(), label.y() - 2, StockTheme.COLOR_BORDER);
    }

    // ==================== 绘制：行情页 ====================

    private void drawTradeBg(GuiGraphics g, StockGuiLayout.Layout l, int mouseX, int mouseY) {
        int selected = clampStock(StockClientState.selected);

        // 左列三张卡片：**按状态只画一张**切片（三态互斥，见 StockWidgets#card）。
        // 命中判定用鼠标位置自己算 —— 和 StockWidgets.button(l.cards()[i], ...) 给的按钮
        // 热区是**同一个矩形**（bounds 就是卡片的 x/y/w/h），所以「外观」和「点到哪一张」永远一致。
        // 另外：卡片按钮用的是 StockButton（无底纹、整块换皮），它不会去画原版 widget/button 的灰底，
        // 所以卡片上**只有这一张**卡片切片，不存在「按钮底图叠在卡片上图」的情况。
        for (int i = 0; i < l.cards().length && i < StockDefs.COUNT; i++) {
            StockGuiLayout.Rect card = l.cards()[i];
            boolean hover = card.x() <= mouseX && mouseX < card.right()
                    && card.y() <= mouseY && mouseY < card.bottom();
            StockWidgets.card(g, card, i == selected, hover);
            // 图标（名称与右上角箭头在 renderLabels 里画，那里才拿得到 Font）
            g.renderItem(new ItemStack(StockCatalog.icon(i)),
                    card.x() + StockGuiLayout.CARD_ICON_OFF_X, card.y() + StockGuiLayout.CARD_ICON_OFF_Y);
        }

        // 中间大屏：面板边框 + 三个内嵌块（现价行 / 阴阳柱 / 持仓条）
        StockWidgets.panelFrame(g, l.chartPanel());
        StockWidgets.insetBox(g, l.chartPlot(), StockTheme.COLOR_INSET_FILL, StockTheme.COLOR_BORDER);
        drawChartGrid(g, l.chartPlot(), StockGuiLayout.chartCandle(l));

        // 右列：两行填写栏底 + 资金情况块。
        // ⚠️ 买入/卖出**不在这里画底图** —— 它们是会变状态的控件（常态/悬停/按下/禁用），
        // 底图由 StockButton 自己在 renderWidget 里按状态画一次。在这里再画一张「常态」底图，
        // 会和控件那张叠成两层（悬停时更明显：一层不动、一层在变）。
        StockWidgets.fieldBox(g, l.qtyField());
        StockWidgets.fieldBox(g, l.levField());
        StockWidgets.insetBox(g, l.fundsBlock(), StockTheme.COLOR_PANEL_FILL, StockTheme.COLOR_BORDER);
        drawPositionListBg(g, l, mouseX, mouseY);
    }

    /**
     * 持仓列表的**底**：内嵌屏 + 标题行分隔线 + 行间隔线 + 悬停高亮。
     *
     * <p>文字与「平」按钮分别在 {@code renderLabels} 与控件层画，这里只画背景 ——
     * 底在控件下面，文字在最上面，三层不打架。
     */
    private void drawPositionListBg(GuiGraphics g, StockGuiLayout.Layout l, int mouseX, int mouseY) {
        StockGuiLayout.Rect list = l.posList();
        if (list.w() <= 0 || list.h() <= 0) {
            return;
        }
        StockWidgets.insetBox(g, list, StockTheme.COLOR_INSET_FILL, StockTheme.COLOR_BORDER);

        StockGuiLayout.Rect header = l.posHeader();
        g.fill(header.x() + 4, header.bottom() - 1, header.right() - 4, header.bottom(),
                StockTheme.COLOR_BORDER);

        List<Position> shown = displayedPositions();
        int rows = l.posVisibleRows();
        for (int i = 0; i < rows; i++) {
            StockGuiLayout.Rect row = l.posRow(i);
            if (i > 0) {
                // 行与行之间的细分隔线（从标题下面那条开始，第 0 行不画）
                g.fill(row.x() + 3, row.y(), row.right() - 3, row.y() + 1, StockTheme.COLOR_GRID_LINE);
            }
            int index = this.posScroll + i;
            if (index < shown.size() && inside(row, mouseX, mouseY)) {
                // 悬停高亮：整行铺一层比底稍亮的色（和资金情况块同一个底色，不会跳）
                g.fill(row.x(), row.y(), row.right(), row.bottom(), StockTheme.COLOR_PANEL_FILL);
            }
        }
    }

    /**
     * 阴阳柱区的网格线：**2×2**（一条横中线 + 一条竖中线）。
     *
     * <p>实机反馈「数字挡住柱子」时就发现 4×4 那套多余竖线全在刻度带附近打架 ——
     * 量程是自适应的，等分线本来也没有刻度含义，减到 2×2 既留住了「中间在哪」的参照，
     * 又不会和右侧的价格刻度抢视觉。竖线只画在**柱子区**内，轴区保持干净。
     */
    private void drawChartGrid(GuiGraphics g, StockGuiLayout.Rect plot, StockGuiLayout.Rect candleZone) {
        int mid = plot.y() + plot.h() / 2;
        g.fill(candleZone.x() + 3, mid, candleZone.right() - 3, mid + 1, StockTheme.COLOR_GRID_LINE);
        int cx = candleZone.x() + candleZone.w() / 2;
        g.fill(cx, plot.y() + 3, cx + 1, plot.bottom() - 3, StockTheme.COLOR_GRID_LINE);
    }

    // ==================== 绘制：购买页 ====================

    private void drawShopBg(GuiGraphics g, StockGuiLayout.Layout l, int mouseX, int mouseY) {
        StockGuiLayout.Rect grid = l.grid();
        StockWidgets.slotGrid(g, grid.x(), grid.y(), l.gridCols(), l.gridRows());

        StockWidgets.fieldBox(g, l.searchBox());

        // 网格里画物品图标 + 数量（数量只画在「买 1 个」也能买得起的格子上没意义，
        // 这里画的是**该物品一组多少个**，让玩家一眼知道左键会买多少）
        this.hoveredGridCell = null;
        int cols = l.gridCols();
        for (int i = 0; i < l.pageSize(); i++) {
            // ⚠️ 本页第 i 格 → (col,row) → **shopView 绝对下标**，走的是和点击/tooltip
            //    完全同一个入口（absoluteShopIndex）。这一处原来是手写 `shopPage * pageSize + i`，
            //    虽然等价，但三份实现本身就是「悬停钻石、买到泥土」那个 bug 的温床 —— 现在只剩一份。
            int col = i % cols;
            int row = i / cols;
            int viewIndex = absoluteShopIndex(col, row);
            if (viewIndex < 0 || viewIndex >= this.shopView.size()) {
                break;
            }
            StockGuiLayout.Rect cell = StockGuiLayout.gridCell(l, col, row);
            ItemStack stack = this.shopView.get(viewIndex).stack();
            g.renderItem(stack, cell.x() + 1, cell.y() + 1);
            int group = Math.max(1, stack.getMaxStackSize());
            if (group > 1) {
                g.renderItemDecorations(this.font, stack.copyWithCount(group), cell.x() + 1, cell.y() + 1,
                        Integer.toString(group));
            }
            if (cell.x() <= mouseX && mouseX < cell.right() && cell.y() <= mouseY && mouseY < cell.bottom()) {
                this.hoveredGridCell = cell;
            }
        }

        // 底部玩家背包（和创造模式物品栏一样：这些是真正的容器槽）
        StockGuiLayout.Rect inv = l.shopInv();
        StockWidgets.slotGrid(g, inv.x(), inv.y(), StockGuiLayout.INV_COLS, StockGuiLayout.INV_ROWS);
        StockGuiLayout.Rect hotbar = l.shopHotbar();
        StockWidgets.slotGrid(g, hotbar.x(), hotbar.y(), StockGuiLayout.INV_COLS, 1);
    }

    // ==================== 绘制：文字层 ====================

    /**
     * 所有文字 + 悬停提示都在这里画。
     *
     * <p>为什么放在 {@code renderLabels}：原版 {@code render()} 的顺序是
     * 「renderBg → 控件 → 物品槽 → renderLabels」，所以这里画的东西一定**压在所有图层最上面**，
     * 不需要自己去碰 z 值；而且此时物品已经被槽位画完，文字盖在物品上也不会被吃掉。
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        StockGuiLayout.Layout l = this.layout;
        if (l == null) {
            return;
        }
        Font font = this.font;

        drawTitleBarText(g, l, font);
        if (this.condOpen) {
            return;      // 条件单覆盖层由 render() 单独画，走不到这里
        }
        switch (this.page) {
            case PAGE_RECYCLE -> drawRecycleText(g, l, font);
            case PAGE_SHOP -> drawShopText(g, l, font, mouseX, mouseY);
            default -> drawTradeText(g, l, font, mouseX, mouseY);
        }
        // 帮助覆盖层由 {@link #render} 单独画（那里整条链都早退了），走不到这里；
        // 万一以后有人改了那条早退，这里也不会画出「两套内容」。
        if (this.helpOpen) {
            return;
        }
        // 页面级提示行（服务端 flash）画在所有页面内容之上：它以前挂在购买页的提示行上，
        // 但那条提示行在图区/网格下面，实机上会被盖住（玩家反馈「警告文字会被趋势图覆盖」）。
        drawFlashLine(g, l, font);
    }

    /**
     * 标题栏下方那一条**页面级状态行**，画服务端最近一次操作的提示（{@code StockClientState.flash}）。
     *
     * <p>三个决定：
     * <ol>
     *   <li><b>只在所属页面显示</b>（实机反馈：玩家不想要「平仓 2 股 · 结算 +0.54」出现在购买页）
     *       —— 分流规则见 {@link #flashBelongsToThisPage(String)}；</li>
     *   <li><b>画在所有页面内容之上</b>（{@code renderLabels} 的最后一步）—— 以前的写法挂在购买页
     *       那行小提示上，位置在图表/网格之下，实机就是「被趋势图盖住」；</li>
     *   <li><b>有生存时间</b>：{@link #FLASH_TTL_MS} 之后自动消失（最后 {@link #FLASH_FADE_MS}
     *       换成灰色），否则会像玩家报的那样「左上角的提示不会消退」永久留着。</li>
     * </ol>
     * 位置在 {@code content} 顶部那一行（各页都从 {@code content.y()} 开始画各自的第一块，
     * 但那一行留白是共用的，所以「有时压住标题」不会发生）。
     *
     * <p>⚠️ 它是**最高优先级**的一层：走势图表头行那条新闻条在它显示期间会主动让位
     * （见 {@link #flashVisible()} 与 {@link #drawChartTicker}）。
     */
    private void drawFlashLine(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        if (!flashVisible()) {
            return;
        }
        long age = System.currentTimeMillis() - this.flashShownAtMs;
        boolean fading = age >= FLASH_TTL_MS - FLASH_FADE_MS;
        StockGuiLayout.Rect row = l.flashRow();
        // 底衬 + 强调色文字：它是「刚刚发生了什么」，要比普通说明更显眼；淡出时整体转灰
        StockWidgets.roundedFill(g, row, 3, fading ? 0x80181A20 : 0xC020232B);
        StockWidgets.labelClipped(g, font,
                new StockGuiLayout.Rect(row.x() + 6, row.y(), Math.max(0, row.w() - 12), row.h()),
                Component.literal(this.flashSeen), fading ? StockTheme.COLOR_MUTED : StockTheme.COLOR_ACCENT);
    }

    /**
     * 这一帧**是不是正在显示 flash 提示**（新闻条要靠它让位）。
     *
     * <p>把 {@code drawFlashLine} 原来那一串早退条件收进一个方法，是为了让「新闻条要不要让位」
     * 与「flash 到底画不画」**共用同一份判据** —— 两边各写一套迟早会对不上
     * （典型症状：flash 没画、新闻条却也没了，或者反过来字压在一起）。
     *
     * <p>四个条件（与 flash 的既有语义完全一致）：
     * <ol>
     *   <li>购买页**永远没有** flash（玩家要求交易类提示不出现在购买页）；</li>
     *   <li>有内容（空串 = 没有新提示，客户端保留上一次的）；</li>
     *   <li>这条提示属于当前页面（模板匹配，见 {@link #flashBelongsToThisPage(String)}）；</li>
     *   <li>还没过 {@link #FLASH_TTL_MS}（最后 {@link #FLASH_FADE_MS} 是淡出段，仍然算「在显示」）。</li>
     * </ol>
     *
     * <p>⚠️ 判据里的文本统一取 {@link #flashSeen}（而不是 {@code StockClientState.flash}）：
     * 内容和它的计时器是在 {@code containerTick} 里**一起**更新的，取同一个字段才不会出现
     * 「新文案配旧计时器」（那样一条刚到的提示可能只剩零点几秒就淡出）。
     */
    private boolean flashVisible() {
        if (this.page == PAGE_SHOP) {
            // ⚠️ 购买页**完全不画**交易类提示（玩家明确要求：「交易类提示在购买页必须完全不画」）。
            //    这里直接早退、**不走「认不出来就三页都显示」那条退化路径** ——
            //    否则任何没登记进模板表的交易提示（比如以后改了 traded.* 的文案）又会漏到购买页上。
            //    以后要加「购买页也能看」的提示，请显式加进 flashBelongsToThisPage 的白名单。
            return false;
        }
        if (this.flashSeen == null || this.flashSeen.isEmpty() || this.flashShownAtMs == 0L) {
            return false;
        }
        if (!flashBelongsToThisPage(this.flashSeen)) {
            return false;
        }
        return System.currentTimeMillis() - this.flashShownAtMs < FLASH_TTL_MS;
    }

    /**
     * 这条提示属不属于**当前页面**。
     *
     * <p>⚠️⚠️ **为什么不能按「键名前缀」判断（上一轮就是这么错的）**：
     * 服务端 {@code StockService.flash(player, key, args...)} 里是
     * {@code Component.translatable(key, args).getString()} —— **在服务端就翻译好了**，
     * 再 {@code PENDING_FLASH.put(uuid, text)} 把**成品文本**塞进快照。
     * 所以客户端拿到的 {@code StockClientState.flash} 是「平仓 1 股 · 结算 +0.48」这种
     * 已翻译文本，**根本不含 {@code message.mcstock.} 前缀** ——
     * 按前缀判定的结果必然是「认不出来 → 三页都显示」。
     * 截图里购买页出现平仓提示，就是这么来的。
     *
     * <p>现在的做法：拿 flash 文本去和 lang 里 {@code message.mcstock.*} 的**值**做
     * **模板匹配**（{@link #FLASH_SHAPES}），反查它属于哪一类。
     * 用「值」而不是「键」，中英文都成立；用「模板」而不是「前缀」，是因为
     * EN 的 {@code traded.open='Bought %s x %s - margin %s, fee %s'} 与
     * {@code purchased='Bought %s x%s -> -%s coins'} **前缀都是 Bought**，
     * 只有把中间的 {@code x } / {@code x} 一起比才分得开（实测前缀法在这两条上会误判）。
     *
     * <p>语言不一致时（多人服客户端语言 ≠ 服务端语言）会落到「认不出来」那一档，
     * 那时三页都显示 —— 属于**安全退化**，宁可多显示也不吞提示。
     */
    private boolean flashBelongsToThisPage(String flash) {
        int owner = flashPageOf(flash == null ? "" : flash.trim());
        if (owner < 0) {
            return true;                    // 认不出来：三页都显示
        }
        return switch (this.page) {
            case PAGE_RECYCLE -> owner == 1;
            case PAGE_SHOP -> owner == 2;
            default -> owner == 0;
        };
    }

    /**
     * 按「lang 值模板」反查这条提示属于哪一页：{@code 0}=行情 {@code 1}=回收 {@code 2}=购买，
     * {@code -1}=认不出来。模板在类初始化时从 lang 现取（见 {@link #flashShapes}），改文案不用动代码。
     */
    private static int flashPageOf(String text) {
        for (int page = 0; page < FLASH_SHAPES.length; page++) {
            for (String shape : FLASH_SHAPES[page]) {
                if (matchesShape(shape, text)) {
                    return page;
                }
            }
        }
        return -1;
    }

    /**
     * 模板匹配：{@link #PARAM} 占位符吃**一个或多个**字符（参数长度不定，可能是 7 也可能是 1000）。
     *
     * <p>贪心 + 回溯。模板都很短（≤60 字符），所以最坏情况也可以忽略。
     */
    private static boolean matchesShape(String shape, String text) {
        if (shape.indexOf(PARAM) < 0) {
            return shape.equals(text);
        }
        if (shape.isEmpty()) {
            return text.isEmpty();
        }
        char head = shape.charAt(0);
        if (head == PARAM) {
            // 参数位：先试吃掉尽量多的字符，再逐字符回退
            for (int take = text.length(); take >= 1; take--) {
                if (matchesShape(shape.substring(1), text.substring(take))) {
                    return true;
                }
            }
            return false;
        }
        if (text.isEmpty() || text.charAt(0) != head) {
            return false;
        }
        return matchesShape(shape.substring(1), text.substring(1));
    }

    /** 模板里的「参数位」哨兵（{@code %s} 被换成它）。用不可打印字符，正常文案里不会出现。 */
    private static final char PARAM = '\u0001';

    /**
     * 三类提示的「已翻译文本模板」，类初始化时从 lang 取（见 {@link #flashShapes}）。
     *
     * <p>⚠️ 键名要和服务端实际用的一致 —— {@code StockService} 里发过哪些键，这里就列哪些。
     * 漏一个的后果只是「那一条认不出来 → 三页都显示」，不会崩。
     */
    private static final String[][] FLASH_SHAPES = {
            // 0 = 行情页：下单 / 平仓 / 爆仓 / 余额不够 / 数量非法 / 仓位满
            flashShapes("traded.open", "traded.close", "traded.short", "closed_all", "liquidated",
                    "liquidated_wiped", "no_cash", "no_position", "bad_qty", "pos_full",
                    // 条件单也属于行情页：挂单/撤单/触发提示不该漏到购买页上
                    "cond.placed", "cond.cancelled", "cond.cleared", "cond.not_found",
                    "cond.full", "cond.bad", "cond.triggered"),
            // 1 = 回收页
            flashShapes("recycled", "recycle_nothing", "recycle_partial"),
            // 2 = 购买页
            flashShapes("purchased", "purchase_no_cash", "purchase_unsellable", "inventory_full"),
    };

    /** 把若干 lang 键的值变成模板（{@code %s} → {@link #PARAM}）。 */
    private static String[] flashShapes(String... keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = Component.translatable("message.mcstock." + keys[i]).getString()
                    .replace("%s", String.valueOf(PARAM));
        }
        return out;
    }

    /**
     * 标题栏三块文字：左边标题、中间「余额 · 净值」、右边倒计时。
     *
     * <p>⚠️ 全部走 {@link StockWidgets#text} / {@link StockWidgets#label} 的**垂直居中**，
     * 不要手写 {@code title.y() + 5}：那是照效果图脚本（Python 按字模顶端对齐）抄来的偏移，
     * 而原版字体的 {@code drawString} y 是字模盒顶端、行高又是 {@code font.lineHeight}，
     * 直接抄会整体偏上 2px 左右。
     *
     * <p>⚠️ **余额 / 净值为负时整段转红**（{@link StockTheme#COLOR_LIQUIDATION}）并把负号带出来：
     * 现在允许负债（没有下限），红色的负余额是玩家唯一能立刻看见的警示。
     * 两段各自判色（不是整行一个色）：完全可能「现金 −10.00、但持仓浮盈把净值顶成正数」，
     * 那时只有余额该是红的。所以这里把一整行拆成「余额 / 分隔符 / 净值」三段自己摆位，
     * 而不是拼成一个 Component 再用一个颜色画。
     */
    private void drawTitleBarText(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        StockGuiLayout.Rect title = l.titleBar();

        Component cashText = Component.translatable("gui.mcstock.balance",
                StockWidgets.money(StockClientState.cash));
        Component separator = Component.literal(" · ");
        Component netText = Component.translatable("gui.mcstock.networth",
                StockWidgets.money(StockClientState.netWorth));
        int cashW = font.width(cashText);
        int gapW = font.width(separator);
        int netW = font.width(netText);
        // 与影响图脚本同一套居中算式（等价的 StockWidgets.text(..., align 0)）
        int x = title.x() + (title.w() - (cashW + gapW + netW)) / 2;
        int y = StockWidgets.centeredY(font, title);
        g.drawString(font, cashText, x, y, debtColor(StockClientState.cash), true);
        g.drawString(font, separator, x + cashW, y, StockTheme.COLOR_MUTED, true);
        g.drawString(font, netText, x + cashW + gapW, y, debtColor(StockClientState.netWorth), true);

        // 倒计时贴关闭按钮左边（效果图就是「2s | ×」这个排布）——先算宽度，再挤出一个自己的矩形。
        // ⚠️ 右边界要避开**帮助按钮**（它在关闭按钮左边）：直接贴 close.x() 会被帮助按钮压住。
        Component seconds = Component.translatable("gui.mcstock.seconds",
                Integer.toString(Math.max(0, StockClientState.secondsToNext)));
        int secondsW = font.width(seconds);
        int timerRight = l.helpBtn().x() - 8;
        StockGuiLayout.Rect timer = new StockGuiLayout.Rect(timerRight - secondsW,
                title.y(), secondsW, title.h());

        // 左：标题（离左边 8px，所以用一个内缩的矩形去左对齐）
        StockWidgets.label(g, font, title.inset(8, 0), Component.translatable("gui.mcstock.title"),
                StockTheme.COLOR_TEXT);
        // 右：倒计时
        StockWidgets.label(g, font, timer, seconds, StockTheme.COLOR_MUTED);
    }

    /** 金额为负（负债）时用警戒红，否则用主文字色 —— 余额与净值共用一个口径。 */
    private static int debtColor(long cents) {
        return cents < 0L ? StockTheme.COLOR_LIQUIDATION : StockTheme.COLOR_TEXT;
    }

    // ---------- 回收页文字 ----------

    private void drawRecycleText(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        refreshRecycleCache();
        // ⚠️ 顶部那句「把要卖的东西 shift 进回收栏 → 点回收一次卖光」**已删**
        //    （玩家：「删除所有说明性的文字，在教程里面写就行了」）。
        //    于是回收页只剩：回收栏 / 合计块 / 回收按钮 / 明细列表 / 背包 —— 没有一句教程话术。
        //    语言键 gui.mcstock.recycle.hint 保留在 lang 里（不再引用）。

        // ⚠️⚠️ **合计块被折叠时（宽或高为 0）一整段跳过**。
        //    这是玩家报的「合计块被折掉时仍在画它的文字」：MERGED_INFO / COMPACT 档把
        //    barInfo 折成 0×0 折进回收按钮，但这里照画「合计 xx」「N 种 · M 组」两行，
        //    坐标正好落在**回收按钮**那块上（被按钮盖住 → 潜伏重叠，换层序就会露出来）。
        StockGuiLayout.Rect info = l.barInfo();
        if (info.w() > 0 && info.h() > 0) {
            int infoLineH = Math.max(font.lineHeight,
                    info.h() / Math.max(1, StockGuiLayout.BAR_INFO_LINES));
            StockWidgets.label(g, font, row(info, 0, infoLineH),
                    Component.translatable("gui.mcstock.recycle.total",
                            Integer.toString(this.recycleCounts.size()),
                            Integer.toString(this.recycleStacks),
                            StockWidgets.money(this.recycleTotalCents)),
                    StockTheme.COLOR_ACCENT);
            // 第二行：不值钱的有几件 —— **0 件就不画**（块里那 13px 空着，不写「不值钱 0 件」
            // 这种噪声）。玩家靠它解释「为什么放了 576 件、总价却比预想少」。
            if (this.recycleUnpricedItems > 0) {
                StockWidgets.label(g, font, row(info, 1, infoLineH),
                        Component.translatable("gui.mcstock.recycle.unpriced",
                                Integer.toString(this.recycleUnpricedItems)),
                        StockTheme.COLOR_MUTED);
            }
        }

        // 回收明细：一行一种（左边名称 ×件数，右边件数）。
        // ⚠️ detailLines() 在矮窗口下会是 0：那时整块（含标题）都不画，
        // 而不是画一行「放不下」的提示 —— 空间已经给了背包。
        // ⚠️ 同样加尺寸守卫（可折叠块的统一规则，见 drawRecycleBg 的注释）。
        int detailRows = l.detailLines();
        if (detailRows > 0 && l.barDetail().w() > 0 && l.barDetail().h() > 0) {
            StockGuiLayout.Rect detail = l.barDetail();
            int padX = 8;
            // ⚠️ 纵向内缩**必须用布局里那个常量**（DETAIL_TEXT_INSET），不能自己写 3：
            //    框高是按「内缩×2 + 整行数」算的，两边各写一份就会对不上 ——
            //    480×270 那一档就是因为框只有一行高、文字却从框顶 +3 排下去，
            //    下边框正好压在最后一行字上（玩家截图里的"文字压在边框上"）。
            int padY = StockGuiLayout.DETAIL_TEXT_INSET;
            if (this.recycleCounts.isEmpty()) {
                // ⚠️ 这里原来画的是「回收栏是空的：把背包的东西 shift 进来」—— 那是**教程话术**，
                //    玩家要求「删除所有说明性的文字，在教程里面写就行了」，所以换成纯状态词
                //    「暂无明细」。语言键 gui.mcstock.recycle.detail_empty 保留在 lang 里（不再引用）。
                StockWidgets.label(g, font, detail.inset(padX, 0),
                        Component.translatable("gui.mcstock.recycle.detail_none"), StockTheme.COLOR_MUTED);
            } else {
                int line = 0;
                for (Map.Entry<Item, Integer> entry : this.recycleCounts.entrySet()) {
                    int topY = detail.y() + padY + line * StockGuiLayout.DETAIL_LINE_H;
                    if (line >= detailRows) {
                        // 放不下就在最后一行提示还有多少种，别让玩家以为漏卖了
                        StockWidgets.line(g, font, detail.inset(padX, 0),
                                Component.translatable("gui.mcstock.recycle.more",
                                        this.recycleCounts.size() - line),
                                topY - StockGuiLayout.DETAIL_LINE_H, StockTheme.COLOR_MUTED);
                        break;
                    }
                    Component name = new ItemStack(entry.getKey()).getHoverName();
                    // 第 1 列：物品名 ×数量（常规字号，整列被裁也不会压到右两列 —— 名字太长就砍尾）
                    StockWidgets.line(g, font, l.detailCol(0, line),
                            Component.literal("").append(name)
                                    .append(" \u00d7" + StockWidgets.count(entry.getValue())),
                            topY, StockTheme.COLOR_MUTED);
                    // 第 2 列：单价（小号点阵字体，右对齐）
                    // 第 3 列：小计 = **这一行自己的**单价 × **这一行自己的**件数
                    // ⚠️⚠️ 必须取本行的 RecycleRow，**不能**用循环外的公共值：
                    //    曾经用「合计 ÷ 总件数」的均价当单价，三种不同物品只要件数相同
                    //    就显示成完全一样的两列（真机截图：32 下界合金锭 / 32 金锭 /
                    //    32 下界合金碎片 全是 776.66 与 24053.12）。均价不是单价。
                    RecycleRow row = this.recycleRows.get(entry.getKey());
                    long unitCents = row == null ? 0L : row.unitCents();
                    long lineCents = row == null ? 0L : row.amountCents();
                    StockWidgets.smallRight(g, font, l.detailCol(1, line),
                            StockWidgets.money(unitCents), StockTheme.COLOR_MUTED);
                    StockWidgets.smallRight(g, font, l.detailCol(2, line),
                            StockWidgets.money(lineCents), StockTheme.COLOR_TEXT);
                    line++;
                }
            }
        }

        // ⚠️ 背包上方那行「背包（shift 点击 → 进回收栏）」标签**已删除**（玩家要求）。
        //    背包本身照常显示（槽位由菜单摆），只是不再有那行字 —— 腾出的高度已经
        //    在布局里并给回收明细列表（见 StockGuiLayout#planRecycle 与 BAR_INFO_LINES）。
        //    语言键 gui.mcstock.recycle.inv 保留在 lang 里（不再引用，留着无害）。
    }

    /** 把一个矩形按行高切成第 {@code index} 行的子矩形（行数按内容给，不做等分假设）。 */
    private static StockGuiLayout.Rect row(StockGuiLayout.Rect rect, int index, int lineH) {
        return new StockGuiLayout.Rect(rect.x(), rect.y() + index * lineH, rect.w(), lineH);
    }

    /**
     * 扫一遍回收栏，统计「每种物品多少件 / 一共几件 / **它自己的单价**」。18 格而已，一帧一次可以接受。
     *
     * <p>⚠️⚠️ **单价必须逐格取服务端发来的值**（{@code StockClientState.recycleUnitValues}），
     * 不能用「合计 ÷ 总件数」反推：那是**均价**，三种不同物品只要件数相同就会算出同一个数，
     * 明细三行一模一样（真机 bug，见 {@link RecycleRow} 的类注释）。
     * 客户端没有 {@code ItemValuator} 那份估价表，所以单价只能由服务端给。
     */
    private void refreshRecycleCache() {
        this.recycleCounts.clear();
        this.recycleRows.clear();
        this.recycleTotalCents = StockClientState.recycleTotal;
        this.recycleStacks = 0;
        this.recycleUnpricedItems = 0;
        int[] slotUnits = StockClientState.recycleUnitValues;
        for (int i = 0; i < this.menu.bar().getContainerSize(); i++) {
            ItemStack stack = this.menu.bar().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            this.recycleCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            this.recycleStacks += stack.getCount();
            // 这一格的单价（分）；同一种物品可能占好几格，取其中最大的（见 RecycleRow#unitFromSlots）
            int unit = i < slotUnits.length ? slotUnits[i] : 0;
            this.recycleSlotUnits.merge(stack.getItem(), unit, Math::max);
        }
        // 逐种算「单价 / 小计」，并把不值钱的件数统计出来（估价 0 → 小计 0 → 不计入总价）
        for (Map.Entry<Item, Integer> entry : this.recycleCounts.entrySet()) {
            long unit = this.recycleSlotUnits.getOrDefault(entry.getKey(), 0);
            RecycleRow row = RecycleRow.of(entry.getValue(), unit);
            this.recycleRows.put(entry.getKey(), row);
            if (!row.counted()) {
                this.recycleUnpricedItems += entry.getValue();
            }
        }
        this.recycleSlotUnits.clear();
    }

    // ---------- 行情页文字 ----------

    private void drawTradeText(GuiGraphics g, StockGuiLayout.Layout l, Font font, int mouseX, int mouseY) {
        int stock = clampStock(StockClientState.selected);
        long price = StockClientState.prices[stock];
        int change = StockClientState.changesPermille[stock];
        int changeColor = change >= 0 ? StockTheme.COLOR_UP : StockTheme.COLOR_DOWN;

        // 左列卡片：第一行「图标 + 右上角涨跌箭头」，第二行企业名。
        // 两行的落点都用 StockGuiLayout 的 CARD_* 偏移拼出来，再交给统一的垂直居中 helper。
        for (int i = 0; i < l.cards().length && i < StockDefs.COUNT; i++) {
            StockGuiLayout.Rect card = l.cards()[i];
            Component name = Component.translatable(StockCatalog.nameKey(i));
            boolean up = StockClientState.changesPermille[i] >= 0;

            // 名称行：从 CARD_NAME_OFF_Y 往下留一行高（宽度左右各缩进 CARD_NAME_OFF_X）
            StockGuiLayout.Rect nameRow = new StockGuiLayout.Rect(
                    card.x() + StockGuiLayout.CARD_NAME_OFF_X, card.y() + StockGuiLayout.CARD_NAME_OFF_Y,
                    card.w() - StockGuiLayout.CARD_NAME_OFF_X * 2, font.lineHeight);
            StockWidgets.label(g, font, nameRow, name,
                    i == stock ? StockTheme.COLOR_TEXT : StockTheme.COLOR_MUTED);

            // 涨跌箭头：右上角，x = 卡右 - CARD_ARROW_PAD_X - 箭头宽，y = 卡顶 + CARD_ARROW_OFF_Y
            StockGuiLayout.Sprite arrow = up ? StockGuiLayout.ICON_UP : StockGuiLayout.ICON_DOWN;
            int arrowX = card.right() - StockGuiLayout.CARD_ARROW_PAD_X - arrow.w();
            int arrowY = card.y() + StockGuiLayout.CARD_ARROW_OFF_Y;
            StockWidgets.blit(g, arrow, arrowX, arrowY);
        }

        // 大屏表头：企业名 · 风险（左） —— 右侧那一段是**新闻条**（见 drawChartTicker）
        // ⚠️ 右侧原来还有一行图例「每根 10 秒 · 近 N 根」——**已删**（玩家：「删除所有说明性的文字，
        //    在教程里面写就行了」，节拍与根数属于教程内容）。语言键 gui.mcstock.chart.legend 保留。
        StockGuiLayout.Rect header = l.chartHeader();
        StockWidgets.labelClipped(g, font, header, Component.translatable(StockCatalog.nameKey(stock)).copy()
                .append(" · ")
                .append(Component.translatable(StockCatalog.riskKey(stock))), StockTheme.COLOR_TEXT);

        // 新闻条：走势图表头行右侧那一段，横向滚动，**黄色**（玩家：「放在这里，流动显示。黄色字体」）
        drawChartTicker(g, l, font);

        // 左列卡片下面的「大盘」小结（指数 / 涨跌 / 大盘状态）
        drawMarketIndex(g, l, font);

        // 现价（左） + 涨跌（右，箭头在数字左边）
        StockGuiLayout.Rect priceRow = l.chartPrice();
        StockWidgets.label(g, font, priceRow, Component.literal(StockWidgets.money(price)),
                StockTheme.COLOR_TEXT);
        Component changeText = Component.literal(StockWidgets.percent(change));
        StockWidgets.text(g, font, priceRow, changeText, changeColor, 1);
        StockWidgets.blit(g, change >= 0 ? StockGuiLayout.ICON_UP : StockGuiLayout.ICON_DOWN,
                priceRow.right() - font.width(changeText) - StockGuiLayout.CARD_ARROW_GAP - 9,
                StockWidgets.centeredY(font, priceRow) - 4);

        // 阴阳柱 + 现价/爆仓虚线
        drawCandles(g, l, stock, price);

        // 右列：**数量/杠杆两行保持原样**（玩家明确要求别删）→ 资金情况 → 图下的持仓表格
        StockWidgets.label(g, font, l.qtyLabel(), Component.translatable("gui.mcstock.field.qty"),
                StockTheme.COLOR_MUTED);
        StockWidgets.label(g, font, l.levLabel(), Component.translatable("gui.mcstock.field.leverage"),
                StockTheme.COLOR_MUTED);
        drawFunds(g, l, font, stock, price);
        // 持仓表格画在**走势图下方**（横跨图宽）；图下原来那两行持仓摘要已删 —— 表格信息更全
        drawPositions(g, l, font, mouseX, mouseY);
    }

    /**
     * 走势图表头行**右侧那一段**的新闻条：横向滚动（marquee），黄色字体。
     *
     * <p>玩家口径（原话）：「放在这里，流动显示。黄色字体」，框画在从「企业名 · 稳健」右侧
     * 一直延伸到图表面板右内边距的那一条 —— 也就是原来放「每根 10 秒 · 近 N 根」图例的位置
     * （图例上几轮已删，矩形还在）。矩形由 {@link StockGuiLayout.Layout#chartTicker()} 给出，
     * 这里**一个坐标都不写死**。
     *
     * <p>三条硬要求：
     * <ol>
     *   <li><b>速度</b> {@link StockGuiLayout#NEWS_SCROLL_SPEED}（1 像素/tick = 20 像素/秒），
     *       相位在 {@link #tickNewsTicker()} 里按 tick 累加；</li>
     *   <li><b>必须 scissor 裁在这条矩形内</b> —— 否则滚动的字会压到企业名、轴区或右列上；</li>
     *   <li>颜色恒为 {@link StockTheme#COLOR_ACCENT}（柔和金黄），**不按利好利空染色**
     *       （玩家明确要黄色）；方向信息由文字里的百分比符号表达。</li>
     * </ol>
     *
     * <p>⚠️ <b>优先级：flash 优先，新闻条让位</b>。这一行（`chartTicker`）与 flash 胶囊所在的
     * `flashRow()` 天然重叠 7px（那是第 3 层的既有设计：胶囊是**有意**浮在图表上沿的），
     * 两者同时画就会「字压在一起」—— 玩家对这个最敏感。所以**行情页正在显示 flash 的那 4 秒里
     * 直接跳过新闻条的绘制**：flash 是即时反馈（下单 / 平仓 / 爆仓）优先级更高，
     * 4 秒后它自己消失，新闻条**从原相位接着滚**（相位与选片都在 {@link #tickNewsTicker()} 里，
     * 不受这一帧画不画影响 —— 所以这里只 `return`，**绝不去动 `tickerPhase`**）。
     */
    private void drawChartTicker(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        if (flashVisible()) {
            return;                 // flash 优先：让位，但相位照常推进（见方法注释）
        }
        StockGuiLayout.Rect row = l.chartTicker();
        if (row.w() <= 0 || row.h() <= 0) {
            return;
        }
        String text = this.tickerText.getString();
        if (text.isEmpty()) {
            return;                     // 没有（新鲜的）新闻：这一行留空，一个字都不画
        }
        // 文字比矩形宽时从右往左滚：x = 矩形右缘 − 相位（相位 0 = 还没进来）
        int x = this.tickerTextW <= row.w()
                ? row.x()
                : row.right() - this.tickerPhase;
        g.enableScissor(row.x(), row.y(), row.right(), row.bottom());
        g.drawString(font, this.tickerText, x, StockWidgets.centeredY(font, row),
                StockTheme.COLOR_ACCENT, true);
        g.disableScissor();
    }

    /**
     * 左列卡片下面的**市场指数**小结：标题 / 指数值 / 中性氛围词（**三行**）。
     *
     * <p>三只股票等权合成（{@code 1000 × mean(price / base)}），指数值用 {@code indexDeci ÷ 10}
     * 显示一位小数。
     *
     * <p>⚠️⚠️ **刻意不显示涨跌、也不显示「牛市 / 熊市」**（玩家原话：「不要直接写涨幅和熊市之类的，
     * 玩家看到会直接买或卖。我的初衷是让人远离赌博」）：
     * <ul>
     *   <li>涨跌那一行**整行删掉**；协议里 {@code indexChangePermille} 仍然在传（机制没变），
     *       只是不画 —— 边界上留一句注释，免得以后有人「顺手补回来」；</li>
     *   <li>状态那一行走 {@code MarketRegime#nameKey()}，词表是**中性氛围词**
     *       （平静 / 温和 / 起伏 / 动荡），不含方向；而且**统一用灰色**，
     *       不再按 bias 正负染成红/绿（染色本身就是方向暗示）。</li>
     * </ul>
     *
     * <p>行数与矩形都来自布局（{@link StockGuiLayout.Layout#indexLines()}），
     * 左列矮到卡片下面放不下时返回 0 行、整块不画。
     */
    private void drawMarketIndex(GuiGraphics g, StockGuiLayout.Layout l, Font font) {
        int lines = l.indexLines();
        if (lines <= 0) {
            return;
        }
        StockWidgets.labelClipped(g, font, l.indexLine(0),
                Component.translatable("gui.mcstock.index.title"), StockTheme.COLOR_MUTED);
        if (lines > 1) {
            StockWidgets.labelClipped(g, font, l.indexLine(1),
                    Component.literal(indexText(StockClientState.indexDeci)), StockTheme.COLOR_TEXT);
        }
        if (lines > 2) {
            // 中性氛围词：只描述交投气氛，不告诉玩家该买还是该卖（所以也不按方向染色）
            StockWidgets.labelClipped(g, font, l.indexLine(2),
                    Component.translatable(StockClientState.regime.nameKey()), StockTheme.COLOR_MUTED);
        }
    }

    /** 大盘指数（×10）→ 「1032.4」（一位小数，与需求里的示例同形）。 */
    private static String indexText(int indexDeci) {
        int abs = Math.abs(indexDeci);
        return (indexDeci < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10);
    }

    // ---------- 持仓列表（右列底部，可滚动） ----------

    /**
     * 画持仓列表：标题行（左「持仓」右页码）+ 若干行仓位，最后按需给一行悬停详情。
     *
     * <p>每行的内容（两行文字）：
     * <pre>
     *   钻石矿业                    多 100股      ← 企业名（常规字号）+ 方向 + 股数（小号数字）
     *   均99.20 ×5                  +96.00       ← 均价 / 杠杆 + 浮动盈亏（小号数字，红涨绿跌）
     * </pre>
     * 行尾的「平」按钮是**控件**（{@link #rebuildPositionWidgets}），不在这里画。
     *
     * <p>⚠️ 字号策略（玩家要求「持仓栏字号调小」）：**只有数字走小号点阵字体**
     * （{@link StockWidgets#small} → {@code assets/mcstock/font/small.json}，6px 高、1:1 绘制），
     * 中文（企业名 / 多空 / 股 / 均）保持常规字号 —— 中文来自 Unifont，
     * 缩小只会掉笔画；而密度压力全在数字那一列。绝不用 {@code pose().scale()} 缩中文。
     *
     * <p>⚠️ 列表里画的是**全部仓位**（不只当前选中的那只股票）：玩家要的是「滚动看每个仓」，
     * 每行都带企业名，平仓按钮也按仓 id 走，所以不必先切股票。
     */
    private void drawPositions(GuiGraphics g, StockGuiLayout.Layout l, Font font, int mouseX, int mouseY) {
        StockGuiLayout.Rect list = l.posList();
        if (list.w() <= 0 || list.h() <= 0) {
            return;
        }
        List<Position> shown = displayedPositions();
        int rows = l.posVisibleRows();
        int pages = rows <= 0 ? 1 : Math.max(1, (shown.size() + rows - 1) / rows);
        int page = rows <= 0 ? 1 : Math.min(pages, this.posScroll / rows + 1);

        // 表头行：**只有列名 + 右页码**。
        // ⚠️⚠️ 原来这里还画了一个「持仓」标题，位置是 `posHeader.x() + POS_TEXT_PAD_X`，
        // 而**表头第一列（方向）也落在同一个位置** —— 实机截图里就是「持仓」和「方向」
        // 叠在一起（玩家报的「表头第一列与持仓标题重叠」）。两者只能留一个：
        // 按玩家建议**保留列名「方向」**，去掉独立的「持仓」标题 —— 表格右侧有页码、
        // 行尾有「平仓」按钮，这块是什么已经不需要额外一行字来解释。
        drawPosHeaderCells(g, font, l);
        StockWidgets.text(g, font, l.posPage(),
                Component.translatable("gui.mcstock.pos.page",
                        Integer.toString(page), Integer.toString(pages)),
                page > 1 || pages > 1 ? StockTheme.COLOR_TEXT : StockTheme.COLOR_MUTED, 1);

        // 行区：硬裁剪（滚动窗口只画得下 rows 行，越界的一律切掉）
        if (rows <= 0) {
            // 极小窗口：连一行都放不下，只留表头。不画 0 高的 scissor（那会把后面的东西也裁掉）。
            return;
        }
        g.enableScissor(list.x(), l.posHeader().bottom(), list.right(), list.bottom());
        if (shown.isEmpty()) {
            StockGuiLayout.Rect first = l.posRow(0);
            StockWidgets.label(g, font, new StockGuiLayout.Rect(first.x() + StockGuiLayout.POS_TEXT_PAD_X,
                            first.y(), Math.max(0, first.w() - StockGuiLayout.POS_TEXT_PAD_X * 2), first.h()),
                    Component.translatable("gui.mcstock.pos.empty"), StockTheme.COLOR_MUTED);
        }
        Position hovered = null;
        int hoveredRow = -1;
        for (int i = 0; i < rows; i++) {
            int index = this.posScroll + i;
            if (index >= shown.size()) {
                break;
            }
            Position position = shown.get(index);
            drawPositionRow(g, l, font, i, position);
            // 行分隔线：横向表格靠它才读得出「一行是一行」
            StockGuiLayout.Rect row = l.posRow(i);
            g.fill(row.x(), row.bottom() - 1, row.right(), row.bottom(), StockTheme.COLOR_BORDER);
            if (inside(row, mouseX, mouseY)) {
                hovered = position;
                hoveredRow = i;
            }
        }
        g.disableScissor();

        // 滚动条：轨道 + 滑块（滑块可以拖，见 mouseDragged）
        StockGuiLayout.Rect track = l.posScrollTrack();
        if (track.h() > 0) {
            g.fill(track.x(), track.y(), track.right(), track.bottom(), StockTheme.COLOR_INSET_FILL);
            StockGuiLayout.Rect thumb = l.posScrollThumb(this.posScroll, shown.size());
            if (thumb.h() > 0) {
                StockWidgets.roundedFill(g, thumb, 1, StockTheme.COLOR_ACCENT);
            }
        }

        // 悬停详情：一行放不下五样东西，完整信息（均价 / 保证金 / 爆仓 / 盈亏%）放 tooltip。
        // 鼠标压在「平仓」按钮上时让位 —— 那是控件的 tooltip，两个一起弹会叠在一起。
        if (hovered != null && !(hoveredRow >= 0 && inside(l.posCloseBtn(hoveredRow), mouseX, mouseY))) {
            drawPositionTooltip(g, font, hovered, mouseX, mouseY);
        }
    }

    /**
     * 表格的表头列名（灰字）。
     *
     * <p>列序与数据行严格一致：{@code 0=方向 1=数量 2=倍数 3=盈亏 4=距爆仓}，
     * 第 5 列是行尾的「平仓」按钮（它由控件自己画字，这里不重复）。
     * 表头用 {@code posCol(col, -1)} 取格 —— 行号传 -1 就落在表头那一行上。
     */
    private void drawPosHeaderCells(GuiGraphics g, Font font, StockGuiLayout.Layout l) {
        String[] keys = {
                "gui.mcstock.pos.col.dir", "gui.mcstock.pos.col.shares", "gui.mcstock.pos.col.lev",
                "gui.mcstock.pos.col.pnl", "gui.mcstock.pos.col.liq",
        };
        for (int col = 0; col < keys.length; col++) {
            StockWidgets.labelClipped(g, font, l.posCol(col, -1),
                    Component.translatable(keys[col]), StockTheme.COLOR_MUTED);
        }
    }

    /**
     * 画表格的一行数据：方向 · 数量 · 倍数 · 盈亏 · 距爆仓。
     *
     * <p>行尾的「平仓」按钮**不在**这里画 —— 它是每行一个的动态控件
     * （{@link #rebuildPositionWidgets}），点谁平谁（{@code C2SClosePosition(positionId)}）。
     *
     * <p>字号策略：中文列（方向）走常规字号，纯数字列走 6px 点阵小字体
     * （{@link StockWidgets#small}）。
     *
     * <p>⚠️ **对齐：一律左对齐**（玩家：「持仓数据左对齐」）——
     * 五列的数据与**表头文字用同一个起始 x**（都是 {@code posCol(...).x()}），
     * 所以列名与数字天然对得上；带正负号的列（盈亏 {@code +96.00} / 距爆仓 {@code -50.0%}）
     * 左对齐后**符号落在同一条竖直线上**，比右对齐更好扫。
     * 行尾的「平仓」按钮位置不变（仍是 {@code posCol(5, row)}，贴该列右端）。
     */
    private void drawPositionRow(GuiGraphics g, StockGuiLayout.Layout l, Font font, int rowIndex,
                                 Position position) {
        long price = StockClientState.priceOf(position.stock());
        long pnl = position.pnl(price);
        long leverage = Math.max(1, position.leverage());
        long liq = position.liquidationPrice();

        // 第 0 列：方向（做多=红 / 做空=绿，A 股口径）
        StockWidgets.labelClipped(g, font, l.posCol(0, rowIndex), Component.translatable(position.isLong()
                        ? "gui.mcstock.pos.long" : "gui.mcstock.pos.short"),
                position.isLong() ? StockTheme.COLOR_UP : StockTheme.COLOR_DOWN);

        // 第 1~4 列：纯数字，**左对齐**（起始 x 与表头列名一致）
        numLeft(g, font, l.posCol(1, rowIndex), StockWidgets.count(position.absShares()),
                StockTheme.COLOR_TEXT);
        // 倍数：用 ASCII 'x' 而不是 '×' —— 两个字形都在 mcstock:small 里（U+00D7 已确认在
        // small.json 的 chars 里），但 ASCII 更保险：万一以后有人重跑字体脚本漏了它，
        // 也不会退化成 SpecialGlyphs.MISSING 那个方块（玩家报过「倍数列显示 ▯1」）。
        numLeft(g, font, l.posCol(2, rowIndex), "x" + leverage, StockTheme.COLOR_MUTED);
        numLeft(g, font, l.posCol(3, rowIndex), StockWidgets.signedMoney(pnl),
                pnl >= 0 ? StockTheme.COLOR_UP : StockTheme.COLOR_DOWN);
        // 距爆仓线：用相对现价的百分比（比绝对值直观，也短）
        numLeft(g, font, l.posCol(4, rowIndex),
                liq > 0 ? StockWidgets.relativePermille(liq, price) : "-",
                StockTheme.COLOR_LIQUIDATION);
    }

    /**
     * 在单元格里**左对齐**画一个小号数字（6px 点阵字体），起始 x 与表头列名相同。
     *
     * <p>放不下就不画（宁可空着，也别和相邻列糊在一起）—— 正常宽度下五列都放得下，
     * 这一条只是窄窗口的兜底；真要裁就整段不画，不做「截一半」那种半截数字。
     */
    private void numLeft(GuiGraphics g, Font font, StockGuiLayout.Rect cell, String text, int color) {
        // 宽度直接问字体本身：小号字体写在 Style 里，Font#width 会按 Style 里的字体量算
        // （StockWidgets#small 的注释写了这条，所以不用自己维护宽度表）
        int w = font.width(StockWidgets.small(text));
        if (w > cell.w()) {
            return;
        }
        StockWidgets.smallText(g, font, cell.x(), StockWidgets.centeredY(font, cell), text, color);
    }

    /** 在单元格里右对齐画一个小号数字（当前没人用；回收明细的右对齐走 `StockWidgets#smallRight`）。 */
    @SuppressWarnings("unused")
    private void numRight(GuiGraphics g, Font font, StockGuiLayout.Rect cell, String text, int color) {
        // 宽度直接问字体本身：小号字体写在 Style 里，Font#width 会按 Style 里的字体量算
        // （StockWidgets#small 的注释写了这条，所以不用自己维护宽度表）
        int w = font.width(StockWidgets.small(text));
        if (w > cell.w()) {
            return;
        }
        StockWidgets.smallText(g, font, cell.right() - w, StockWidgets.centeredY(font, cell), text, color);
    }

    /** 悬停某一行时的详细说明（一行塞不下的那些数，这里一次给全）。 */
    private void drawPositionTooltip(GuiGraphics g, Font font, Position position, int mouseX, int mouseY) {
        long price = StockClientState.priceOf(position.stock());
        long pnl = position.pnl(price);
        long liq = position.liquidationPrice();
        String percent = position.marginCents() > 0
                ? String.format("%+.2f%%", pnl * 100.0 / position.marginCents()) : "0.00%";
        List<Component> lines = new ArrayList<>();
        long leverage = Math.max(1, position.leverage());
        lines.add(Component.translatable(StockCatalog.nameKey(position.stock())).copy()
                .append(" · ")
                .append(Component.translatable(position.isLong()
                        ? "gui.mcstock.pos.long" : "gui.mcstock.pos.short"))
                .append(" · ×" + leverage));
        lines.add(Component.translatable("gui.mcstock.chart.position",
                StockWidgets.count(position.absShares()), StockWidgets.money(position.entryCents()),
                Long.toString(leverage)));
        lines.add(Component.translatable("gui.mcstock.pos.margin", StockWidgets.money(position.marginCents())));
        lines.add(Component.translatable("gui.mcstock.chart.pnl", StockWidgets.signedMoney(pnl), percent));
        lines.add(Component.translatable("gui.mcstock.chart.liq_line",
                StockWidgets.money(liq), StockWidgets.relativePermille(liq, price)));
        g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    // ==================== 走势图量程（防抖） ====================

    /**
     * 每只股票**上一次画出来的量程**（分）。{@code 0} 且 high 也为 0 时表示还没画过。
     *
     * <p>为什么要有记忆：量程若每帧纯按「当前可见柱子的极值」重算，那么每跳一次价、
     * 甚至每加入一根新柱子，整张图所有柱子都会**整体上下平移 / 缩放**一次 ——
     * 玩家看到的不是「价格在动」，而是「画面在抖」（实机反馈「变化的时候看着很奇怪」）。
     * 加上滞回之后，只有极值真的越界才扩张、回到内侧才收缩。
     */
    private final long[] axisLow = new long[StockDefs.COUNT];
    private final long[] axisHigh = new long[StockDefs.COUNT];

    /** 量程量化步长 = 基准价的 **0.4%**（100.00 → 40 分）。
     *  原先的 0.1% 太细：极值在边界上蹭一点就够一个 step，量程会一步一步往前挪 —— 实测就是「乱跳」。 */
    private static final double AXIS_STEP_RATIO = 0.004;
    /**
     * **早期**（可见柱 &lt; {@link #AXIS_SPARSE_CANDLES} 根）的最小跨度 = 基准价的 **2%**。
     *
     * <p>像素实测反馈：原来取 10%（≈±5%）时，单跳只有 12~14 分的波动落在这套量程里
     * **只有 1~3 逻辑像素高**，画面上就是价格轴左边的一个小红点 —— 玩家说的「看着很奇怪」
     * 主要就是这个。2% 让一根柱子至少看得出高度。
     */
    private static final double AXIS_MIN_SPAN_RATIO_SPARSE = 0.02;
    /**
     * **数据够了**（可见柱 ≥ {@link #AXIS_SPARSE_CANDLES} 根）之后的最小跨度 = 基准价的 **0.6%**。
     *
     * <p>取 0.6% 而不是 0：跳价是 2 秒一次（{@code PRICE_TICK_TICKS = 40}）、
     * 单跳幅度约 0.6%，留这一档保证「行情在极小范围内磨」的时候单跳仍有几个像素，
     * 不至于退化成一条直线；其余交给数据极值。
     */
    private static final double AXIS_MIN_SPAN_RATIO_FULL = 0.006;
    /** 「柱子够多了」的门槛：8 根 × 5 跳 = 40 跳 ≈ 80 秒行情。 */
    private static final int AXIS_SPARSE_CANDLES = 8;
    /** 收缩滞回：极值回到当前量程内侧这个比例以内，才允许把量程缩回去。 */
    private static final double AXIS_SHRINK_MARGIN = 0.30;
    /** 量程在极值外再各留这么多（留白）。 */
    private static final double AXIS_PAD_RATIO = 0.05;

    /**
     * 算出一只股票这一次该用的量程 {@code {low, high}}：**含基准价 + 量化 + 滞回 + 最小跨度**。
     *
     * <p>四件事各治一个抖动来源：
     * <ol>
     *   <li><b>包含基准价</b> —— 它是行情的中枢（均值回归的目标）。早期只有 2~3 根柱子、
     *       价格又贴着基准价来回抖时，只按极值算会把量程压到几十分的宽度，放大到失真；</li>
     *   <li><b>量化到步长</b> —— 上下界吸附到 {@code 基准价 × 0.1%} 的整数倍（向外取整），
     *       避免「每次都算出差几分的量程」造成的持续微抖；</li>
     *   <li><b>滞回</b> —— 扩张立即生效（否则柱子会画到框外），收缩必须等极值回到
     *       当前量程内侧 {@link #AXIS_SHRINK_MARGIN} 以内，消除边界横跳的频闪；</li>
     *   <li><b>最小跨度</b> —— 随可见柱数自适应：柱子少时用
     *       {@link #AXIS_MIN_SPAN_RATIO_SPARSE}（2%，让单跳看得出高度），
     *       柱子多了降到 {@link #AXIS_MIN_SPAN_RATIO_FULL}（0.6%）交给数据。</li>
     * </ol>
     *
     * <p>⚠️ 它**写回** {@link #axisLow}/{@link #axisHigh}：每只股票各记一份，切股票互不影响。
     * 价格回到量程中间之后，下一帧自然收缩回最小跨度。
     *
     * @param hasData 这一帧有没有有效柱子（没有时不要把「极值」当真，只保证最小跨度）
     * @param visibleCandles 这一帧可见（有数据）的柱子根数 —— 决定用哪一档最小跨度
     */
    private long[] stabilizedAxis(int stock, long valueLow, long valueHigh, boolean hasData,
                                  int visibleCandles) {
        int index = Math.max(0, Math.min(StockDefs.COUNT - 1, stock));
        long base = StockDefs.BASE_CENTS[index];
        long step = Math.max(1L, Math.round(base * AXIS_STEP_RATIO));
        // 最小跨度随「柱子够不够多」自适应：早期放大到 2%，数据够了降到 0.6%
        double minRatio = visibleCandles >= AXIS_SPARSE_CANDLES
                ? AXIS_MIN_SPAN_RATIO_FULL : AXIS_MIN_SPAN_RATIO_SPARSE;
        long minSpan = Math.max(step * 2, Math.round(base * minRatio));

        // ① 目标量程 = **已完成柱**的极值 ∪ 基准价
        //    ⚠️ 调用方传进来的极值已经排除了「进行中」的最后一根：它的 高/低/收 每跳都会变，
        //    算进量程就等于「每 2 秒把整张图缩放一次」（实机反馈「所有柱子乱跳」）。
        //    ⚠️ **不含爆仓价**：×400 时爆仓离现价只有 0.1%、×1 时可能离得极远（甚至跌破 0），
        //    把它算进量程会把十几根柱子一起压成小不点（实机反馈「买入/卖出之后柱子被压得太小」）。
        //    爆仓价改走「图边缘指示」，见 drawLiquidationMarker(...) —— 绝不为它缩放量程。
        long targetLow = valueLow;
        long targetHigh = valueHigh;
        if (hasData) {
            targetLow = Math.min(targetLow, base);
            targetHigh = Math.max(targetHigh, base);
        }
        // ② 最小跨度：以基准价为中心撑开
        if (targetHigh - targetLow < minSpan) {
            long mid = (targetHigh + targetLow) / 2;
            targetLow = mid - minSpan / 2;
            targetHigh = mid + minSpan / 2;
        }
        // ③ 留白 + 量化（向外取整，保证极值一定落在量程内）
        long pad = Math.max(step, Math.round((targetHigh - targetLow) * AXIS_PAD_RATIO));
        long wantLow = Math.floorDiv(targetLow - pad, step) * step;
        long wantHigh = -Math.floorDiv(-(targetHigh + pad), step) * step;   // 向上取整

        long low = this.axisLow[index];
        long high = this.axisHigh[index];
        if (low >= high) {
            // 第一次画：直接用目标量程
            low = wantLow;
            high = wantHigh;
        } else {
            // ④ 扩张要**超过整整一个步长**才动（不然极值在边界上蹭来蹭去会连续微调量程）；
            //    收缩要留滞回余量（极值退回内侧 AXIS_SHRINK_MARGIN 以里才允许缩）。
            if (wantLow < low - step) {
                low = wantLow;
            }
            if (wantHigh > high + step) {
                high = wantHigh;
            }
            long span = high - low;
            long margin = Math.max(step, Math.round(span * AXIS_SHRINK_MARGIN));
            if (wantLow >= low + margin && wantHigh <= high - margin) {
                low = wantLow;      // 极值已经退到内侧足够深，可以缩
                high = wantHigh;
            }
        }
        this.axisLow[index] = low;
        this.axisHigh[index] = high;
        return new long[]{low, high};
    }

    /**
     * 阴阳柱：自适应量程（含基准价、带滞回）画 K 线 + 两条虚线 + 右侧价格刻度。
     *
     * <p>数据约定见 {@link StockClientState#candles}：{@code [candle * 4 + (开,高,低,收)]}，
     * 单位**分**，四价全 0 = 这根还没有（开盘头几帧）。
     *
     * <p>⚠️ <b>量程必须自适应</b>（AGENTS.md 6.5）：×400 杠杆时爆仓离现价只有 0.11%，
     * 固定量程下爆仓线会和现价线糊成一条，玩家根本看不出「离爆仓还有多远」。
     *
     * <p>⚠️ <b>柱子左对齐、固定槽宽、滚动窗口</b>（实机反馈「不会向左边靠到框左边」）：
     * 槽宽恒为 {@link StockGuiLayout#CANDLE_PX}，x <b>只由槽位下标</b>决定
     * （{@code x = zone.x() + slotIndex * slotW}），数据不足时从左边依次占用；
     * 攒满一窗之后整体左移一格。**绝不用 {@code zone.w()/根数} 这类会随根数变化的槽宽** ——
     * 那会让「2 根 → 3 根」这种变化把所有柱子的 x 全部重算一遍（横向也在跳）。
     */
    private void drawCandles(GuiGraphics g, StockGuiLayout.Layout l, int stock, long price) {
        StockGuiLayout.Rect plot = l.chartPlot();
        // ⚠️ 硬性分区（实机反馈「数字挡住柱子」）：
        //   candleZone 画柱子 + 两条虚线；axisZone 画现价标签 + 三个刻度，**两者零重叠**。
        StockGuiLayout.Rect candleZone = StockGuiLayout.chartCandle(l);
        StockGuiLayout.Rect axisZone = StockGuiLayout.chartAxis(l);
        int[] raw = stock < StockClientState.candles.length ? StockClientState.candles[stock] : null;
        if (raw == null || raw.length == 0) {
            StockWidgets.text(g, this.font, plot,
                    Component.translatable("gui.mcstock.chart.waiting"), StockTheme.COLOR_MUTED, 0);
            return;
        }
        // 服务端给什么就画什么：分箱已经由 MarketEngine.candles() 按**绝对 advances** 算好，
        // 一根柱的 tick 区间写满之后永不重算，所以客户端不需要（也不该）再做任何缓存/冻结。
        int total = raw.length / 4;
        if (total <= 0 || isBlank(raw, total - 1)) {
            StockWidgets.text(g, this.font, plot,
                    Component.translatable("gui.mcstock.chart.waiting"), StockTheme.COLOR_MUTED, 0);
            return;
        }
        // ① 固定槽宽 + 固定窗口容量（**不随根数变化**）
        int slotW = StockGuiLayout.CANDLE_PX;
        int slotCount = Math.max(1, candleZone.w() / slotW);
        // ② 窗口锚在**第一根有数据的柱子**上，而不是数组末尾：
        //    服务端固定发 CANDLE_MAX 格、早期高位全是 0，锚在末尾会让仅有的几根柱子
        //    被推到最右边（玩家反馈「不会向左边靠到框左边」）。
        int firstData = 0;
        while (firstData < total && isBlank(raw, firstData)) {
            firstData++;
        }
        int dataCount = total - firstData;
        // 数据还没填满一窗 → 从**柱子区左缘**开始依次占用；填满之后 → 整体左移一格滚动
        int start = dataCount <= slotCount ? firstData : total - slotCount;
        // ③ 找「已完成柱」的最后一根下标：最新那根是**进行中**的，它的 高/低/收 每跳都变，
        //    把它算进量程就等于每 2 秒把整张图缩放一次（实盘反馈「所有柱子乱跳」）。
        int lastClosed = total - 2;
        while (lastClosed >= start && isBlank(raw, lastClosed)) {
            lastClosed--;
        }
        if (lastClosed < start) {
            // 只有一根进行中的柱子：不拿它当量程依据，但仍然把它画出来
            lastClosed = start - 1;
        }

        long valueLow = Long.MAX_VALUE;
        long valueHigh = Long.MIN_VALUE;
        int closedCount = 0;
        // ⚠️ 量程只用**已完成**的柱子（不含进行中的最后一根）
        for (int i = start; i < total; i++) {
            if (isBlank(raw, i) || i > lastClosed) {
                continue;       // 空位、以及「进行中」的那一根，都不参与量程
            }
            int o = raw[i * 4];
            int h = raw[i * 4 + 1];
            int lo = raw[i * 4 + 2];
            int c = raw[i * 4 + 3];
            valueLow = Math.min(valueLow, Math.min(lo, Math.min(o, c)));
            valueHigh = Math.max(valueHigh, Math.max(h, Math.max(o, c)));
            closedCount++;
        }
        if (closedCount == 0) {
            // 还没有任何一根完成：拿进行中那根的开盘价当唯一参照，量程只用最小跨度兜底
            int o = raw[(total - 1) * 4];
            if (o > 0) {
                valueLow = o;
                valueHigh = o;
            } else {
                StockWidgets.text(g, this.font, plot,
                        Component.translatable("gui.mcstock.chart.waiting"), StockTheme.COLOR_MUTED, 0);
                return;
            }
        }
        // 爆仓虚线画**离爆仓最近**的那个仓（同一只股票可以有好几个仓，爆仓价各不相同：
        // 给玩家看「哪一个先爆」才有意义，见 StockClientState.nearestLiquidation）
        Position nearestLiq = StockClientState.nearestLiquidation(stock);
        long liq = nearestLiq == null ? 0L : nearestLiq.liquidationPrice();
        long[] axis = stabilizedAxis(stock, valueLow, valueHigh, true, closedCount);
        long low = axis[0];
        long high = axis[1];

        int bodyW = Math.max(1, slotW - 2);
        // ⚠️ 硬裁剪：柱子 / 影线 / 溢出提示全部只允许出现在柱子区矩形内。
        //    纵向本来已有 priceToY 的 clamp 兜底，这里再加一道 scissor ——
        //    两道防线都留着，是因为「画到框外」属于一眼就能看见的观感事故
        //    （实机反馈过「偶尔会跌到最小刻度线以下」）。
        g.enableScissor(candleZone.x(), candleZone.y(), candleZone.right(), candleZone.bottom());
        for (int i = start; i < total; i++) {
            int o = raw[i * 4];
            int h = raw[i * 4 + 1];
            int lo = raw[i * 4 + 2];
            int c = raw[i * 4 + 3];
            if (o == 0 || lo == 0) {
                continue;
            }
            // 左对齐：x 只由「这根在窗口里的槽位下标」决定（与当前根数无关）
            int slotIndex = i - start;
            int cx = candleZone.x() + slotIndex * slotW + slotW / 2;
            int color = c >= o ? StockTheme.COLOR_UP : StockTheme.COLOR_DOWN;   // A 股：红涨绿跌
            // 这一根的极值是否超出了量程？（超出的部分会被夹在边缘，下面给它画个短横提示）
            boolean overTop = h > high;
            boolean overBottom = lo < low;
            // 进行中的那根可能超出量程（我们故意不为它缩放整张图）：
            // priceToY 自带夹取，所以它顶到边缘就停住，不会画出框外。
            int yHigh = priceToY(h, low, high, candleZone);
            int yLow = priceToY(lo, low, high, candleZone);
            g.fill(cx, yHigh, cx + 1, yLow + 1, StockTheme.COLOR_BORDER);        // 影线（1px 细线）
            int yOpen = priceToY(o, low, high, candleZone);
            int yClose = priceToY(c, low, high, candleZone);
            int top = Math.min(yOpen, yClose);
            int bottom = Math.max(yOpen, yClose);
            int left = cx - bodyW / 2;
            // 实体：圆角矩形（圆角用内嵌屏底色补，免得擦出洞）＋ 1px 深色描边，
            // 这样 GUI 缩放小时红绿也分得清（实机反馈「颜色不够区分」）。
            StockGuiLayout.Rect body = new StockGuiLayout.Rect(left, top, bodyW, bottom - top + 1);
            StockWidgets.fillSoft(g, body, StockGuiLayout.CANDLE_RADIUS, color,
                    StockTheme.COLOR_INSET_FILL);
            outline(g, body, StockTheme.COLOR_BORDER);
            // 超出量程的短横提示：贴着柱区边缘画一条同色 3px 短线，让「被裁」看起来是有意的
            if (overTop) {
                g.fill(cx - 1, candleZone.y(), cx + 2, candleZone.y() + 1, color);
            }
            if (overBottom) {
                g.fill(cx - 1, candleZone.bottom() - 1, cx + 2, candleZone.bottom(), color);
            }
        }
        g.disableScissor();

        // 现价虚线：从**最新那根柱子**（进行中的那根）画到窗口左缘，颜色跟着它的涨跌。
        // ⚠️ 线只画到「最新柱的右缘」为止 —— 柱子左对齐之后，右端可能还留着空白槽位，
        //    线铺满整个区会显得「价格已经涨到窗口外」。
        // ⚠️ 同样套 scissor：现价可能超出量程（比如刚跳价那一瞬），不裁就会画到框外。
        int newestX = candleZone.x() + (total - start) * slotW;
        int lineRight = Math.min(candleZone.right(), newestX);
        int priceColor = raw[(total - 1) * 4 + 3] >= raw[(total - 1) * 4]
                ? StockTheme.COLOR_UP : StockTheme.COLOR_DOWN;
        int priceY = priceToY(price, low, high, candleZone);
        g.enableScissor(candleZone.x(), candleZone.y(), candleZone.right(), candleZone.bottom());
        dashedHLine(g, candleZone.x(), lineRight, priceY, priceColor);

        // 爆仓价：**只在量程内时画成虚线**；落在量程外就改成「边缘指示」。
        // ⚠️ 绝不为它扩大/缩放量程 —— 量程里已经没有它了（见 stabilizedAxis 的注释），
        //    否则 ×1 杠杆那种「离现价极远」的爆仓价会把十几根柱子一起压成小不点。
        boolean liqInRange = liq > 0 && liq >= low && liq <= high;
        int liqY = 0;
        boolean showLiqTag = false;
        if (liqInRange) {
            liqY = priceToY(liq, low, high, candleZone);
            dashedHLine(g, candleZone.x(), lineRight, liqY, StockTheme.COLOR_LIQUIDATION);
            // 两个标签叠得太近只画现价那个（AGENTS.md 6.6：小字号下会糊成一片）
            showLiqTag = Math.abs(liqY - priceY) >= 14;
        }
        g.disableScissor();
        // 轴区：先铺整条底衬（把越过边界的网格/柱子盖住），再画三个刻度，最后压现价/爆仓价签
        drawPriceAxis(g, axisZone, candleZone, low, high);
        // 现价价签：和刻度标签离得太近就不画（否则就是截图里那团叠字）
        if (canDrawPriceTag(axisZone, candleZone, low, high, priceY)) {
            priceTag(g, axisZone, priceY, StockWidgets.money(price), priceColor);
        } else {
            priceY = Integer.MIN_VALUE;     // 告诉下面的避让逻辑：这个标签实际没画
        }
        if (showLiqTag) {
            priceTag(g, axisZone, liqY, StockWidgets.money(liq), StockTheme.COLOR_LIQUIDATION);
        } else if (liq > 0 && !liqInRange) {
            // 在量程外：柱子区边缘画个小三角指向它所在的方位，价格写在轴区外侧
            drawLiquidationMarker(g, candleZone, axisZone, low, high, liq, liq > high,
                    priceY, Integer.MIN_VALUE);
        }
    }

    /**
     * **爆仓价在量程之外**时的边缘指示：柱子区对应一侧的小三角 + 轴区外侧的价格文字。
     *
     * <p>为什么不干脆把量程撑开去容纳它：×1 杠杆的爆仓价可能离现价远到 100%
     * （甚至算出负数），撑开之后十几根柱子会被压成几个像素高，走势彻底看不出来
     * （实机反馈「买入/卖出之后柱子被压得太小」）。所以改成
     * 「**不去动量程，只在边上告诉你它在哪个方向**」。
     *
     * @param above true = 爆仓价在量程**上方**（价格再涨上去就会碰到它）
     */
    private void drawLiquidationMarker(GuiGraphics g, StockGuiLayout.Rect candleZone,
                                       StockGuiLayout.Rect axisZone, long low, long high,
                                       long liq, boolean above,
                                       int priceY, int liqTagY) {
        int color = StockTheme.COLOR_LIQUIDATION;
        // 小三角：朝上/朝下各 5px 宽、3px 高（很小的一个提示，不抢柱子的视线）
        int tipY = above ? candleZone.y() + 1 : candleZone.bottom() - 4;
        for (int row = 0; row < 3; row++) {
            int w = 5 - row * 2;                       // 5 → 3 → 1
            int x = candleZone.x() + 3 + row;
            int y = above ? tipY + row : tipY - row;
            g.fill(x, y, x + w, y + 1, color);
        }
        // 价格文字：**只允许画在轴区里**。
        // 轴区宽只有 CHART_AXIS_W（46px），而「爆仓 129.80（在上方）」这种中文长句能到 110px ——
        // 不裁的话它会一路压到右列的买入/卖出按钮上（实机反馈「爆仓线多的字会覆盖在买入卖出上」）。
        // 两道保险：① 文案放不下就退化成「只写金额 + 方位箭头」（方位由柱子区那个小三角表达）；
        //          ② 无论如何再套一层 scissor，像素绝不越过轴区右边界。
        Component full = Component.translatable(
                above ? "gui.mcstock.chart.liq_above" : "gui.mcstock.chart.liq_below",
                StockWidgets.money(liq));
        Component text = this.font.width(full) <= axisZone.w() - 2
                ? full
                : Component.literal((above ? "↑ " : "↓ ") + StockWidgets.money(liq));
        int h = this.font.lineHeight + 2;
        int y = above ? axisZone.y() : axisZone.bottom() - h;
        // ⚠️⚠️ **轴区里已经有的每一行字都要避让**（玩家实机反馈「现价价签旁边有重叠的一坨字」）：
        //    以前只比了现价 / 爆仓两个价签，**没比三个刻度标签** —— 于是「爆仓 129.80（在上方）」
        //    正好压在刻度数字上，截图里就是那团叠字。
        //    规则（玩家给的）：与现价价签、爆仓价签、三个刻度标签中的**任何一个**
        //    垂直距离 < 一行高（font.lineHeight + 2 = 11px）时，**一律不画这行字**，只留三角。
        StockGuiLayout.Rect candidate = new StockGuiLayout.Rect(axisZone.x() + 1, y,
                Math.max(0, axisZone.w() - 2), h);
        for (StockGuiLayout.Rect occupied : axisOccupiedRows(axisZone, candleZone, low, high, priceY,
                liqTagY)) {
            if (candidate.bottom() + ROW_CLEARANCE > occupied.y()
                    && occupied.bottom() + ROW_CLEARANCE > candidate.y()) {
                return;         // 垂直方向挨得太近：这行字不画（三角已经指明了方位）
            }
        }
        g.enableScissor(axisZone.x(), axisZone.y(), axisZone.right(), axisZone.bottom());
        StockWidgets.labelClipped(g, this.font, candidate, text, color);
        g.disableScissor();
    }

    /** 两行字之间必须留出的垂直净空（一行高；玩家给的规则就是「< 一行高就不画」）。 */
    private static final int ROW_CLEARANCE = 11;

    /**
     * 轴区里**已经被占用的行**（现价价签 / 爆仓价签 / 三个刻度标签），供爆仓文字避让。
     *
     * <p>坐标算法与 {@link #drawPriceAxis} 里画刻度时**逐字一致** —— 两处必须同步，
     * 否则「避让判定用的位置」和「实际画出来的位置」会对不上（那正是上一版没躲开的原因）。
     */
    private List<StockGuiLayout.Rect> axisOccupiedRows(StockGuiLayout.Rect axisZone,
                                                       StockGuiLayout.Rect candleZone,
                                                       long low, long high, int priceY, int liqTagY) {
        List<StockGuiLayout.Rect> out = new ArrayList<>();
        int h = this.font.lineHeight;
        // 三个刻度
        for (int i = 0; i < 3; i++) {
            int y = scaleY(i, low, high, candleZone);
            int top = switch (i) {
                case 0 -> candleZone.y();
                case 2 -> candleZone.bottom() - 1 - h;
                default -> y - h / 2;
            };
            top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - h, top));
            out.add(new StockGuiLayout.Rect(axisZone.x(), top, axisZone.w(), h));
        }
        // 现价价签 + 爆仓价签（liqTagY == Integer.MIN_VALUE 表示那个没画）
        // ⚠️ 顺序把它放在刻度**之后**，与绘制流程一致（刻度先画、价签后压）
        int tagH = this.font.lineHeight + 4;
        if (priceY != Integer.MIN_VALUE) {
            int top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - tagH, priceY - tagH / 2));
            out.add(new StockGuiLayout.Rect(axisZone.x(), top, axisZone.w(), tagH));
        }
        if (liqTagY != Integer.MIN_VALUE) {
            int top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - tagH, liqTagY - tagH / 2));
            out.add(new StockGuiLayout.Rect(axisZone.x(), top, axisZone.w(), tagH));
        }
        return out;
    }

    /**
     * 现价价签能不能画：与**三个刻度标签**都必须留出一行净空。
     *
     * <p>⚠️ 这是玩家截图里那团叠字（「99.43」旁边一坨 `+50 82`）的直接原因：
     * 刻度是固定的三条（量程高/中/低），而现价随时可能跳到其中一条**正上方** ——
     * 两者都在轴区、都画在同一条 x 带上，挨在一起就糊成一团。
     * 规则与爆仓文字一致：垂直距离 &lt; 一行高（{@link #ROW_CLEARANCE}）就不画那个价签。
     */
    private boolean canDrawPriceTag(StockGuiLayout.Rect axisZone, StockGuiLayout.Rect candleZone,
                                    long low, long high, int priceY) {
        int tagH = this.font.lineHeight + 4;
        int top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - tagH, priceY - tagH / 2));
        int bottom = top + tagH;
        int h = this.font.lineHeight;
        for (int i = 0; i < 3; i++) {
            int y = scaleY(i, low, high, candleZone);
            int labelTop = switch (i) {
                case 0 -> candleZone.y();
                case 2 -> candleZone.bottom() - 1 - h;
                default -> y - h / 2;
            };
            labelTop = Math.max(axisZone.y(), Math.min(axisZone.bottom() - h, labelTop));
            int labelBottom = labelTop + h;
            if (bottom + ROW_CLEARANCE > labelTop && labelBottom + ROW_CLEARANCE > top) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这根阴阳柱是不是**空位**（还没有数据）。
     *
     * <p>服务端在历史不足时会补 0（{@code MarketEngine.candles()} 前面那些块），
     * 约定「开或低为 0」= 这根没有数据。
     */
    private static boolean isBlank(int[] raw, int index) {
        return raw[index * 4] == 0 || raw[index * 4 + 2] == 0;
    }

    /** 给矩形描 1px 深色边（柱子实体的「深色描边」，四角不画，保持圆润）。 */
    private static void outline(GuiGraphics g, StockGuiLayout.Rect r, int color) {
        if (r.w() < 3 || r.h() < 3) {
            return;     // 太小就不描了，否则整根柱子会被描边吃掉
        }
        g.fill(r.x() + 1, r.y(), r.right() - 1, r.y() + 1, color);
        g.fill(r.x() + 1, r.bottom() - 1, r.right() - 1, r.bottom(), color);
        g.fill(r.x(), r.y() + 1, r.x() + 1, r.bottom() - 1, color);
        g.fill(r.right() - 1, r.y() + 1, r.right(), r.bottom() - 1, color);
    }

    /**
     * 图区右侧的**价格轴**：3 个刻度（量程高 / 中 / 低）。
     *
     * <p>为什么需要：只有现价与爆仓价两个价签时，玩家看不出「这根柱子涨到多少」——
     * 量程是自适应的，没有刻度就没有任何参照（实机反馈「看不明白」的原因之一）。
     *
     * <p>⚠️ <b>上下两个刻度必须与柱子区的上下边缘严格对齐</b>（实机反馈「偶尔会跌到最小刻度线以下」）：
     * 之前是两个问题叠在一起 ——
     * ① 刻度文字用的是「外框顶 = y」的 {@code label(...)}，而字模在框里是**垂直居中**的，
     *    于是最上面那个刻度的**字形**比柱子区上边缘低 2px、最下面那个比下边缘高 1px；
     * ② 柱子/影线的 y 被夹在柱区内（最下沿是 {@code bottom()-1}），本来就是贴边的。
     * 两者一叠加，视觉上就成了「柱子跌到刻度线以下」。
     * 现在改成：**刻度线的 y 由 {@link #scaleY} 给出**（上=柱区 y、下=柱区 bottom()-1、中=中点），
     * 文字**按字形对齐**到那条线上 —— 中刻度让字形居中，上/下刻度让字形刚好贴住边缘。
     *
     * <p>整条轴区先铺一层不透明底衬：立柱区最右侧的柱子/虚线**可能贴到边界**，
     * 底衬保证「柱子区与轴区零重叠」这条约定在视觉上也成立。
     */
    private void drawPriceAxis(GuiGraphics g, StockGuiLayout.Rect axisZone, StockGuiLayout.Rect candleZone,
                               long low, long high) {
        if (axisZone.w() <= 0 || axisZone.h() <= 0) {
            return;
        }
        g.fill(axisZone.x(), axisZone.y(), axisZone.right(), axisZone.bottom(), StockTheme.COLOR_INSET_FILL);
        for (int i = 0; i < 3; i++) {
            long value = i == 0 ? high : (i == 2 ? low : (low + high) / 2);
            int y = scaleY(i, low, high, candleZone);
            Component text = Component.literal(StockWidgets.money(value));
            int w = this.font.width(text);
            int h = this.font.lineHeight;
            int x = axisZone.x() + 2;
            // top 是「字形顶端」：上刻度贴柱区上边缘、下刻度贴柱区下边缘（字形底 = bottom-1）、
            // 中刻度以那条线为中心
            int top = switch (i) {
                case 0 -> candleZone.y();
                case 2 -> candleZone.bottom() - 1 - h;
                default -> y - h / 2;
            };
            top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - h, top));
            StockWidgets.label(g, this.font, new StockGuiLayout.Rect(x, top, w, h), text,
                    StockTheme.COLOR_MUTED, false);
        }
    }

    /**
     * 第 i 个刻度（0 = 量程高、1 = 中、2 = 量程低）所在的扫描线 y。
     *
     * <p>上和下**直接返回柱区的边缘**，不再由量程等比换算 —— 这样「刻度线」和
     * 「柱子能画到的最远位置」在数值上就是同一个像素，对齐永远成立（哪怕以后柱区加了内边距）。
     */
    private static int scaleY(int index, long low, long high, StockGuiLayout.Rect candleZone) {
        return switch (index) {
            case 0 -> candleZone.y();
            case 2 -> candleZone.bottom() - 1;
            default -> priceToY((low + high) / 2, low, high, candleZone);
        };
    }

    /** 价格（分）→ 图上的 y 像素（低在下、高在上）。 */
    private static int priceToY(long value, long low, long high, StockGuiLayout.Rect plot) {
        double ratio = (double) (value - low) / (double) Math.max(1L, high - low);
        int y = plot.bottom() - 1 - (int) Math.round(ratio * (plot.h() - 1));
        return Math.max(plot.y(), Math.min(plot.bottom() - 1, y));
    }

    /** 一段一段的虚线（每 4px 画 2px）。 */
    private static void dashedHLine(GuiGraphics g, int x1, int x2, int y, int color) {
        for (int x = x1; x < x2; x += 4) {
            g.fill(x, y, Math.min(x + 2, x2), y + 1, color);
        }
    }

    /**
     * 价格小标签：深底 + 彩色描边 + 彩色数字，贴在图区右缘。
     *
     * <p>⚠️ 内边距不能省（AGENTS.md 6.6）：小字号下数字本身是「空心」的，
     * 标签框贴着数字会连成一条色带，看不出写的是多少。
     */
    /**
     * 价格小标签：深底 + 彩色描边 + 彩色数字。
     *
     * <p>⚠️ **只能画在轴区里**（{@link StockGuiLayout#chartAxis}）：柱子右对齐之后最新一根
     * 正好贴着柱子区右缘，价签再宽一点就会压到它上面 —— 实机反馈的「数字挡住柱子」。
     * 所以横向以 {@code axisZone} 为界（越界部分被夹掉），纵向用柱子区的量程换算。
     *
     * <p>⚠️ 内边距不能省（AGENTS.md 6.6）：小字号下数字本身是「空心」的，
     * 标签框贴着数字会连成一条色带，看不出写的是多少。轴区只有 46px 宽，
     * 所以这里横向只留 3px 内边距（数一数「−100.00」= 7 字符 ≈ 40px，刚好放得下）。
     */
    private void priceTag(GuiGraphics g, StockGuiLayout.Rect axisZone, int y, String text, int color) {
        Font font = this.font;
        int w = Math.min(axisZone.w(), font.width(text) + 6);
        int h = font.lineHeight + 4;
        int x = axisZone.right() - w;
        int top = Math.max(axisZone.y(), Math.min(axisZone.bottom() - h, y - h / 2));
        StockWidgets.roundedFill(g, new StockGuiLayout.Rect(x, top, w, h), 3, 0xF0181A21);
        g.fill(x, top, x + w, top + 1, color);
        g.fill(x, top + h - 1, x + w, top + h, color);
        g.fill(x, top, x + 1, top + h, color);
        g.fill(x + w - 1, top, x + w, top + h, color);
        StockWidgets.label(g, font, new StockGuiLayout.Rect(x + 3, top, Math.max(0, w - 6), h),
                Component.literal(text), color, false);
    }

    /** 资金情况块：标题 + 最多 {@link StockGuiLayout.Layout#fundsLines()} 行。 */
    private void drawFunds(GuiGraphics g, StockGuiLayout.Layout l, Font font, int stock, long price) {
        StockGuiLayout.Rect block = l.fundsBlock();
        // 标题也是一行：用 FUNDS_TITLE_H 高的行矩形做垂直居中
        StockWidgets.label(g, font, new StockGuiLayout.Rect(block.x() + 6, block.y(),
                        block.w() - 12, StockGuiLayout.FUNDS_TITLE_H),
                Component.translatable("gui.mcstock.funds.title"), StockTheme.COLOR_ACCENT);

        int qty = qty();
        int lev = leverage();
        long notional = price * qty;
        long margin = PositionMath.marginFor(price, qty, lev);
        long fee = PositionMath.feeFor(notional);
        long need = margin + fee;
        long maxShares = PositionMath.maxAffordable(StockClientState.cash, price, lev);
        // 「持仓」那一行改成**仓位数**（如「3 个仓」）：独立仓位之后「这只股票持了多少股」
        // 已经不是一个数了（多空可以同时挂着），合计股数与逐仓明细在下面的持仓表里。
        // 「爆仓」那一行取这只股票里**离爆仓最近**的那个仓（没有持仓就是「-」）。
        //
        // ⚠️ 第一行原来是「余额」—— 玩家要求去掉（顶部标题栏已经有「余额 x · 净值 y」，重复），
        //    换成**「扛风险」**：当前填写的杠杆下，价格反向走多少就会爆仓。
        //    口径与 PositionMath 一致（保证金亏掉 45% 即爆仓）→ **扛风险 = 45% ÷ 杠杆**。
        //    它随数量/杠杆输入框**实时变化**（用的是本帧的 leverage()，不用等下单）。
        //    ⚠️ 与下面「爆仓」那一行的区别：**爆仓 = 已有仓位的爆仓价**，
        //       扛风险 = **当前准备开的这一单能扛多少**（还没下单也有数），两者不重复。
        Position nearest = StockClientState.nearestLiquidation(stock);

        Component[] rows = {
                Component.translatable("gui.mcstock.funds.risk", riskText(lev)),
                Component.translatable("gui.mcstock.funds.net", StockWidgets.money(StockClientState.netWorth)),
                Component.translatable("gui.mcstock.funds.qty", StockWidgets.count(qty)),
                Component.translatable("gui.mcstock.funds.need", StockWidgets.money(need)),
                Component.translatable("gui.mcstock.funds.max", StockWidgets.count(maxShares)),
                Component.translatable("gui.mcstock.funds.shares",
                        StockWidgets.count(StockClientState.positionCount())),
                Component.translatable("gui.mcstock.funds.liq",
                        nearest == null ? "-" : StockWidgets.money(nearest.liquidationPrice())),
        };
        int lines = Math.min(l.fundsLines(), rows.length);
        // 每一行也是一个「行矩形」，交给统一的垂直居中 helper —— 不再手写 +2 那种微调
        StockGuiLayout.Rect firstRow = new StockGuiLayout.Rect(block.x() + 6,
                block.y() + StockGuiLayout.FUNDS_TITLE_H, block.w() - 12, StockGuiLayout.FUNDS_LINE_H);
        for (int i = 0; i < lines; i++) {
            StockGuiLayout.Rect lineRect = row(firstRow, i, StockGuiLayout.FUNDS_LINE_H);
            if (lineRect.bottom() > block.bottom()) {
                break;
            }
            // 前两行（扛风险/净值）是「事实」，后面几行是「这笔单子会怎样」，用灰色压一档。
            // ⚠️ 净值那一行（i == 1）为负时同样转红：它和标题栏那个净值是同一个数，
            //    一处红一处白会让人以为是两个字段。
            int color = i <= 1 ? (i == 1 ? debtColor(StockClientState.netWorth) : StockTheme.COLOR_TEXT)
                    : StockTheme.COLOR_MUTED;
            // ⚠️ 用 labelClipped 而不是 label：资金块只有 100px 宽（文本区 88px），
            //    「扛风险 ±0.13%」实测 74px 放得下；但留一道裁切兜底，
            //    免得像当年「仓位栏的字会重叠」那样溢出到右列外面。
            StockWidgets.labelClipped(g, font, lineRect, rows[i], color);
        }
    }

    /**
     * 「扛风险」的数值文本：**{@code StockDefs.MARGIN_CALL_PERCENT}% ÷ 杠杆**（现在是 **45% ÷ 杠杆**），
     * 固定两位小数（如 {@code 0.11%}）。
     *
     * <p>口径来自 {@code PositionMath} 的爆仓判定 —— 保证金亏掉 45% 即强平，
     * 所以价格反向变动 {@code 45% / leverage} 就触及爆仓线。
     * <pre>
     *   ×1   → 45.00%      ×2   → 22.50%     ×5   →  9.00%
     *   ×50  →  0.90%      ×100 →  0.45%     ×400 →  0.11%
     * </pre>
     * ⚠️ 分子**直接取常量**（不要写死 45 / 0.45）：2026-10-06 爆仓线从 50 收到 45 时，
     * 这里要是写死就会和 {@code PositionMath} 对不上 —— 界面上写着「扛 0.13%」、
     * 实际 0.10% 就爆了。
     * <p>⚠️ 统一两位小数、**不做「去掉末尾 0」的简化**：这个项目踩过「千分比当百分比印」
     * 那种精度/口径的坑（`±0.1%` 会让 0.1125% 与 0.11% 看起来一样），所以宁可显示 `45.00%`
     * 也要口径统一。
     */
    private static String riskText(int leverage) {
        int lev = Math.max(1, leverage);
        return String.format("%.2f%%", StockDefs.MARGIN_CALL_PERCENT / (double) lev);
    }

    // ---------- 购买页文字 ----------

    private void drawShopText(GuiGraphics g, StockGuiLayout.Layout l, Font font, int mouseX, int mouseY) {
        // 页码：居中「1/9」
        StockWidgets.text(g, font, l.pageText(),
                Component.translatable("gui.mcstock.shop.page",
                        Integer.toString(this.shopPage + 1), Integer.toString(pageCount())),
                StockTheme.COLOR_TEXT, 0);

        // 提示行：**整行删掉**（玩家：「删除所有说明性的文字，在教程里面写就行了」）。
        // 购买页现在只有：分类 / 搜索 / 页码 / 物品格 / 背包 —— 一个字都不画。
        // 语言键 gui.mcstock.shop.hint / shop.inv / shop.empty 保留在 lang 里（不再引用）。

        // 悬停：物品名 + 单价（网格不是容器槽，原版不会自动给 tooltip）
        if (this.hoveredGridCell != null) {
            int index = startIndexOnGrid(l, this.hoveredGridCell);
            if (index >= 0 && index < this.shopView.size()) {
                ShopEntry entry = this.shopView.get(index);
                List<Component> lines = new ArrayList<>();
                lines.add(entry.stack().getHoverName());
                lines.add(Component.translatable("gui.mcstock.shop.unit_price",
                        StockWidgets.money(entry.unitCents())));
                lines.add(Component.translatable("gui.mcstock.shop.stack_price",
                        StockWidgets.money((long) entry.unitCents() * Math.max(1, entry.stack().getMaxStackSize()))));
                g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            }
        }
    }

    /** 鼠标所在格子在**这一页**里的下标（换算回 shopView 的绝对下标）。 */
    private int startIndexOnGrid(StockGuiLayout.Layout l, StockGuiLayout.Rect cell) {
        int col = (cell.x() - l.grid().x()) / StockGuiLayout.SLOT;
        int row = (cell.y() - l.grid().y()) / StockGuiLayout.SLOT;
        // ⚠️ 与点击路径共用同一个换算入口（见 absoluteShopIndex 的注释：
        //    这两条路径以前各写一份、其中一份漏了页偏移，就是「悬停钻石、买到泥土」的根因）
        return absoluteShopIndex(col, row);
    }

    // ==================== 小工具 ====================

    private static int clampStock(int stock) {
        return Math.max(0, Math.min(StockDefs.COUNT - 1, stock));
    }

    /** 回收按钮的标签：有东西可卖就带上金额，空的时候只写「回收」。 */
    private static Component recycleButtonLabel(long totalCents) {
        if (totalCents <= 0) {
            return Component.translatable("gui.mcstock.recycle.empty");
        }
        return Component.translatable("gui.mcstock.recycle.button", StockWidgets.money(totalCents));
    }

    /** 拿在手上的确实是终端才允许开界面（服务端还会再查一遍，这里只是省一次往返）。 */
    public static boolean holdingTerminal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return mc.player.getMainHandItem().is(ModItems.stock_terminal.get())
                || mc.player.getOffhandItem().is(ModItems.stock_terminal.get());
    }

    /** 供调试/其它界面判断用：当前在哪一页（0 回收 / 1 行情 / 2 购买）。 */
    public int currentPage() {
        return this.page;
    }

    /**
     * 把控件登记到屏幕上的**公开入口**。
     *
     * <p>为什么需要它：{@code Screen#addRenderableWidget} 是 {@code protected}，
     * {@link StockWidgets#stepperRow} 这类「工具箱」方法在**别的类**里，够不到它
     * （javac 实测：{@code <T>addRenderableWidget(T) 在 Screen 中是 protected 访问控制}）。
     * 与其把工具箱的每个方法都改成回调式（回调里再登记），不如在这里开一个一行宽的公开口子 ——
     * 它只把原版那个方法转出去，没有别的事。
     */
    public void registerWidget(Button widget) {
        addRenderableWidget(widget);
    }

    /** 同上，{@link EditBox} 版（重载而不是泛型：泛型在这里会和外层那个 {@code <T extends ...>} 撞上）。 */
    public void registerWidget(EditBox widget) {
        addRenderableWidget(widget);
    }
}
