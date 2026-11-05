package com.qiushaonan.mcstock.client;

/**
 * 股市 GUI 的布局引擎 —— <b>单一事实来源</b>。
 *
 * <p>窗口不再是固定像素：面板占游戏窗口的 {@link #PANEL_W_RATIO}（默认 88%），
 * 并夹在 {@link #PANEL_MIN_W}×{@link #PANEL_MIN_H} ~ {@link #PANEL_MAX_W}×{@link #PANEL_MAX_H} 之间。
 * 上下限是必要的：GUI 缩放调到 1 时逻辑分辨率有 1920×1080，按 88% 算出来的面板
 * 会是一块巨大的空板子，而字号、槽位、物品图标都不会跟着变大。
 * 典型情况下（1080p + 自动 GUI 缩放 → 逻辑 640×360）面板就是 563×323，正好是「占窗口九成」的观感。
 *
 * <p>{@link #compute(int, int)} 按屏幕逻辑尺寸算出所有矩形，返回不可变的 {@link Layout}。
 * 客户端在 {@code Screen#init()} 里调用（开界面和窗口缩放都会走那里），
 * 再把容器槽 {@code Slot#x/y} 按 {@link Layout#barSlots()} / {@link Layout#invSlots()} 摆好。
 *
 * <p>⚠️ 本文件**不能 import 任何 Minecraft 类**：{@code tools/layout_dump.py} 会用
 * {@code javac} 单独编译它 + {@code tools/java/LayoutDump.java}，把布局 dump 成 JSON，
 * 供生成规格文档、占位贴图、效果图的脚本使用。所有坐标只有这一份来源。
 */
public final class StockGuiLayout {

    private StockGuiLayout() {
    }

    // ==================== 窗口比例与上下限 ====================

    /** 面板宽度占屏幕宽度的比例。 */
    public static final double PANEL_W_RATIO = 0.88;
    /** 面板高度占屏幕高度的比例。 */
    public static final double PANEL_H_RATIO = 0.88;
    public static final int PANEL_MIN_W = 320;
    public static final int PANEL_MIN_H = 200;
    public static final int PANEL_MAX_W = 720;
    public static final int PANEL_MAX_H = 440;
    /** 面板与屏幕边缘至少留这么多，免得贴边。 */
    public static final int PANEL_SCREEN_MARGIN = 6;

    // ==================== 固定高度 / 尺寸 ====================

    public static final int TITLE_H = 18;
    public static final int TAB_H = 16;
    public static final int TAB_MIN_W = 64;
    /** 页签宽度按面板宽度的 1/6 取，并夹在这个上限内。 */
    public static final int TAB_MAX_W = 160;
    public static final int TAB_GAP = 4;
    /**
     * 标题栏与页签行之间的空隙。
     *
     * <p>⚠️ 取 0（原来是 2）是为了让**内容区正好从页签下沿开始**：玩家实机反馈
     * 「把趋势图上端与三个标签页下端齐平 —— 页签一结束就是图」。省下的 2px 加上
     * 下面 {@link #CONTENT_GAP} 省掉的 4px，正好把 content.y() 提到页签下沿。
     */
    public static final int TAB_ROW_GAP = 0;
    /**
     * 页签下沿到内容区上沿的空隙。
     *
     * <p>⚠️ 取 0（原来是 4）：这一条 4px 的空白就是玩家说的「图区上沿和页签之间那段空白」。
     * 现在 {@code content.y() == tabY + TAB_H}，走势图的上沿与页签下沿**数值相等**。
     */
    public static final int CONTENT_GAP = 0;
    public static final int CONTENT_PAD = 8;
    public static final int CONTENT_BOTTOM_PAD = 8;
    /** 格子边长（含边框）。 */
    public static final int SLOT = 18;
    public static final int CLOSE_SIZE = 12;
    public static final int CLOSE_X_PAD = 4;
    /** 关闭按钮与帮助按钮之间的间距。 */
    public static final int HELP_CLOSE_GAP = 6;
    public static final int CLOSE_Y_PAD = 3;

    // ==================== 行情页 ====================

    /** 左列窄一点，宽度全部让给走势图。 */
    public static final int LEFT_COL_W = 64;
    public static final int RIGHT_COL_W = 100;
    public static final int COL_GAP = 6;
    /** 企业卡片：图标与箭头一行、名称一行（两行才塞得进 56px 宽）。 */
    public static final int CARD_W = 56;
    public static final int CARD_H = 44;
    public static final int CARD_GAP = 0;
    /** 卡片里 16×16 图标的偏移、涨跌小箭头的右对齐内边距、名称的偏移。 */
    public static final int CARD_ICON_OFF_X = 6;
    public static final int CARD_ICON_OFF_Y = 6;
    public static final int CARD_ARROW_PAD_X = 6;
    public static final int CARD_ARROW_OFF_Y = 7;
    public static final int CARD_NAME_OFF_X = 6;
    public static final int CARD_NAME_OFF_Y = 27;
    /** 名称后面那个涨跌小箭头的横向间距（现只有购买页/其它地方用得上，卡片改成右上角对齐了）。 */
    public static final int CARD_ARROW_GAP = 4;
    /**
     * 左列卡片**下方**那块小结（市场指数）：标题行 + 指数值 + 一行**中性氛围词**，
     * 一共 {@link #INDEX_LINES} 行、每行 {@link #INDEX_LINE_H} 像素。
     *
     * <p>为什么放这里：左列三张卡片只占 {@code 3 × CARD_H = 132}px，而左列高度是整页
     * （640×360 下 264px）—— 下面本来就空着 130 多像素。市场指数要「一直看得到」，
     * 放在这块死区里**零成本**，也不和任何已有元素抢位置；标题栏那条中间空档要跟
     * 「余额 · 净值」抢位置（窄窗口下会被裁），走势图表头行则被新闻条整条占满了。
     *
     * <p>⚠️ 这里**没有涨跌行**、也没有「牛市 / 熊市」这类词：界面不替玩家下买卖判断
     * （口径见 {@code StockScreen#drawMarketIndex}）。
     */
    public static final int INDEX_TOP_GAP = 8;
    /** 大盘块一行的高度与行数（标题 / 指数 / 氛围词；放不下就少画，一行都放不下整块不画）。 */
    public static final int INDEX_LINE_H = 11;
    public static final int INDEX_LINES = 3;
    /** 图表区内部：顶部信息行、现价行、底部两行持仓条、内边距。 */
    public static final int CHART_PAD = 8;
    /**
     * 图表**内部**的内边距（比 {@link #CHART_PAD} 更小）。
     *
     * <p>⚠️ 玩家要求「走势图框里的走势图占比增大」：外框那一圈 {@code CHART_PAD = 8} 留着
     * （面板/内嵌屏需要它），但柱子绘图区不用再让第二道 8px —— 4px 够画圆角边框。
     */
    public static final int CHART_INNER_PAD = 4;
    /** 图内「企业名 + 现价」两行各自的高度（原来是 12 + 2 间隔 + 12 + 6，太占地方）。 */
    public static final int CHART_ROW_H = 11;
    /**
     * 图表表头行里「企业名 · 风险」那一段的**最大宽度**（剩下的全给右侧的新闻条）。
     *
     * <p>为什么要钉一段宽度：表头行现在是「左边企业名 + 右边新闻条」，
     * 而本文件**不许 import MC 的字体**（{@code tools/layout_dump.py} 要单独编译它），
     * 算不出文字宽度。取 132 是按最长的那份文案量的：中文「钻石矿业 · 稳健」约 59px、
     * 英文「Diamond Mining · Steady」约 126px —— 132 让两种语言都放得下，
     * 窄窗口下再用 {@link #CHART_NAME_PCT} 按比例让位给新闻条。
     */
    public static final int CHART_NAME_MAX_W = 132;
    /** 企业名那一段占表头行的百分比上限（窄窗口下新闻条不能被挤没）。 */
    public static final int CHART_NAME_PCT = 45;
    /**
     * **新闻条（横向滚动 marquee）的滚动速度**：{@code 1} = 每 tick 1 逻辑像素。
     *
     * <p>20 tps → **20 逻辑像素/秒**（玩家要求「流动显示」，20px/s 是「看得出在动、又不晃眼」的档位）。
     * 一圈耗时 = {@code (新闻条宽 + 文字宽) ÷ 速度}：640×360 下新闻条 231px、
     * 一条「【公司公告】矿洞塌方 · 钻石矿业 -3.6%」约 190px → 约 21 秒一圈。
     *
     * <p>⚠️ 用**每 tick 像素数**而不是「像素/秒 + 挂钟时间」：暂停菜单、卡顿、
     * 掉帧都不会让它走得更快（见 {@code StockScreen#tickerPhase}）。
     */
    public static final int NEWS_SCROLL_SPEED = 1;
    /** 现价行与绘图区之间的空隙。 */
    public static final int CHART_HEADER_GAP = 2;
    public static final int CHART_HEADER_H = 26;
    /**
     * 走势图下方的**持仓条**两行：已删除（信息并入图下的持仓表格）。
     *
     * <p>保留常量只是为了兼容旧引用；布局里已不再产生这两个矩形。
     */
    @Deprecated
    public static final int CHART_FOOTER1_H = 14;
    @Deprecated
    public static final int CHART_FOOTER2_H = 14;
    /** 图区与第一行持仓条之间的空隙。 */
    public static final int FOOTER_TOP_GAP = 3;
    /** 两行持仓条之间的空隙。 */
    public static final int FOOTER_GAP = 1;
    /** 走势图最小高度：再挤也要留这么多，否则图就没法看了。 */
    public static final int CHART_MIN_H = 80;
    /** 图表在最小值之上还要多留的余量（表格加高时不去碰这部分）。 */
    public static final int CHART_SLACK = 40;
    /** 一根阴阳柱占多少横向像素（据此决定图上放多少根）。 */
    public static final int CANDLE_PX = 7;
    /**
     * 走势图右侧留给**价格刻度**的宽度。
     *
     * <p>刻度标签（高/中/低三个价）画在图区右缘的这条空白带里，不压柱子；
     * 阴阳柱与两条虚线都只在 {@code chartPlot.w() - CHART_AXIS_W} 之内绘制。
     */
    public static final int CHART_AXIS_W = 46;
    /**
     * 阴阳柱**实体**的圆角半径（影线保持 1px 直线）。
     * 实体最窄只有 5~7px、最矮可能 1px，绘制端会把它夹到 {@code min(w,h)/3} 以内，
     * 矮于 3px 时自动退化成方角（圆角会把 1px 的实体整个吃掉）。
     */
    public static final int CANDLE_RADIUS = 2;
    public static final int CANDLE_MIN = 20;
    public static final int CANDLE_MAX = 60;
    /** 一根阴阳柱 = 多少跳（2 秒一跳 → 5 跳 = 10 秒）。 */
    public static final int CANDLE_TICKS = 5;
    /** 市场引擎保留多少跳的原始价格（供上面这些柱子取用）。 */
    public static final int PRICE_HISTORY_TICKS = CANDLE_TICKS * CANDLE_MAX;
    /** 右列：买入 / 卖出 两个主操作按钮。 */
    public static final int BTN_MAIN_H = 24;
    /** 「平仓」按钮比买卖矮一点：它是辅助操作，不抢主按钮的视觉重量。 */
    public static final int BTN_CLOSE_H = 20;
    /** 买卖之间、卖出与平仓之间的间距（实机反馈「调小买卖按钮，让下方控件有更多空间」→ 收到 3）。 */
    public static final int BTN_GAP = 3;
    public static final int BTN_GROUP_GAP = 6;
    /**
     * 右列「数量」「杠杆」两行：一行 = 标签 + 填写栏 + ▲ + ▼ + MAX。
     * 宽度预算（右列 100px）：20 + 2 + 28 + 2 + 12 + 12 + 2 + 22 = 100。
     */
    public static final int FIELD_ROW_H = 20;
    /**
     * 「数量」「杠杆」两个标签的宽度。
     *
     * <p>⚠️ 20 → 16 → **12**：右列整行只有 100px，「6 位数字必须完整可见」这条是硬需求，
     * 所以宽度从标签这里让。12px 在常规中文字体下只能显示一个多字（「数」/「杠」），
     * 由 {@code labelClipped} 自动裁 —— 玩家明确说「删除所有说明性文字」，
     * 一个字的标签配合下面的数字框已经足够辨认。
     */
    public static final int FIELD_LABEL_W = 12;
    /**
     * 数字填写栏的宽度。
     *
     * <p>⚠️⚠️ **按「6 位数字必须完整可见」定**（玩家实机：「调到 1000 只显示 `000`」
     * —— 那是 28px 的框放不下 4 位数字，首位被裁）。
     * <p>框里的值由 {@code StockWidgets.NarrowTextField} 用**小号点阵字体**画，
     * 每位约 4px：6 位 = 24px；本框给到 42，减去左右内缩后内部宽 **32px**，
     * 6 位只占 24 → 富余 8px，任何 6 位数字都不会被裁。
     * <p>右列 100px 的账（按 {@code PixelFont} 实测字宽算，正好放下）：
     * {@code 标签12 + 2 + 框42 + 2 + ▲11 + ▼11 + 2 + MAX14}。MAX 按钮收窄到 14 是为了把
     * 宽度让给数字框 —— 按钮文案同步缩成单个「M」（tooltip 仍是完整的「最大可开股数」）。
     * 见 {@link #FIELD_ROW_TOTAL_W} 与类加载断言：改宽任何一个都会当场炸。
     */
    public static final int FIELD_W = 36;
    /** ▲▼ 各宽（数量/杠杆两行一致）。11px 是「▲/▼ 图标 9px + 左右各 1px」的下限。 */
    public static final int FIELD_STEP_W = 11;
    /** MAX 按钮宽度：文案缩成单个「M」（实测约 9px），14px 留足点击面积。 */
    public static final int FIELD_MAX_W = 14;
    public static final int FIELD_INNER_GAP = 2;
    /**
     * 一整行「标签 + 框 + ▲▼ + MAX」的总宽（必须 ≤ {@code RIGHT_COL_W}）。
     *
     * <p>留着它做自检：{@code StockGuiLayout} 的静态块会断言它不超右列宽，
     * 以后谁改宽了任何一个常量都会当场炸出来，而不是等到实机发现数字被裁。
     */
    public static final int FIELD_ROW_TOTAL_W = FIELD_LABEL_W + FIELD_INNER_GAP + FIELD_W
            + FIELD_INNER_GAP + FIELD_STEP_W * 2 + FIELD_INNER_GAP + FIELD_MAX_W;

    static {
        // ⚠️ 自检：右列一整行「标签 + 填写栏 + ▲▼ + MAX」必须放得下。
        //    玩家报过「数量框显示 000、首位被裁」—— 根因就是这一行被挤爆。
        //    以后谁把任何一个常量改宽，类加载时就会当场炸出来，而不是等实机才发现。
        if (FIELD_ROW_TOTAL_W > RIGHT_COL_W) {
            throw new IllegalStateException("右列放不下「标签+填写栏+▲▼+MAX」：需要 "
                    + FIELD_ROW_TOTAL_W + "px，右列只有 " + RIGHT_COL_W + "px");
        }
    }
    /** 数量填写栏允许的范围；MAX 按钮 = 按余额和杠杆能吃下的最大股数。 */
    public static final int QTY_MIN = 1;
    public static final int QTY_MAX = 100000;
    /** 杠杆填写栏允许的范围；MAX 按钮 = 上限。 */
    public static final int LEVERAGE_MIN = 1;
    public static final int LEVERAGE_MAX = 400;
    /** 填写栏里最多显示几位（18px + 6px/位 ≈ 28px 宽）。 */
    public static final int FIELD_MAX_CHARS = 6;
    /** 资金情况块：标题 + 每行文字高度（13px，11px 会让两行中文贴在一起）。 */
    public static final int FUNDS_TITLE_H = 12;
    public static final int FUNDS_LINE_H = 13;
    public static final int FUNDS_LINES = 7;
    /**
     * 资金情况块至少留几行（空间不够时先压它，见 {@link #POS_GAP}）。
     *
     * <p>从 7 行压到 2 行是有代价的（「可开 / 爆仓」这类下单前要看的数字会被挤掉），
     * 但那两个数在别处都有：可开 = 数量栏的 MAX 按钮，爆仓 = 图下持仓条。
     * 反过来说，持仓列表**一行都没有**就等于这次新增的功能不存在，所以空间先给列表。
     */
    public static final int FUNDS_MIN_LINES = 2;

    // ---------- 持仓列表（右列底部，可滚动） ----------

    /**
     * 持仓列表：标题行（左边标题、右边页码）+ N 行仓位，最后一行下面没有别的东西。
     *
     * <p>行高 20 = **两行文字**（每行 {@link #POS_LINE2_DY} 的间距）：一行放不下
     * 「企业名 · 多/空 · 股数 · 均价 · 浮盈」五样东西 —— 右列只有 {@link #RIGHT_COL_W} 宽，
     * 光是一个 4 字企业名 + 一个方向字就吃掉 45px。所以拆成两行：
     * <pre>
     *   钻石矿业                    多 100股        ← 名称（常规字号） + 方向 + 股数（小号数字）
     *   均99.20 ×5                  +96.00         ← 均价/杠杆 + 浮动盈亏（小号数字）
     * </pre>
     *
     * <p>⚠️ **中文用常规字号，只有数字走小号点阵字体**
     * （{@code assets/mcstock/font/small.json}，6px 高、1:1 绘制）：
     * 中文来自 Unifont，缩小只会掉笔画；数字是压密度的部分，重新烤一份点阵才清晰。
     * 详见 {@code tools/gen_small_font.py} 开头的推导。
     */
    /**
     * 表头行高度。
     *
     * <p>⚠️⚠️ 11 → **13**（玩家：「持仓的内容位置往下降，**字放在线上了**」）。
     * 表头文字用 {@code centeredY} 居中：11px 行里 9px 字形的墨迹占 y=1..9，
     * 而表头下那条分隔线画在 {@code bottom()-1 = 10} → **净空只有 1px**，实机就是字压线。
     * 13px 之后墨迹占 y=2..10、线在 12 → 净空 2px，正好达标。
     * <p>同时它也是**第一行数据的顶**（{@code posRow(0).y = POS_TITLE_H}），
     * 所以这一改顺带把「第一行数据贴着表头线」也解决了（间隔 0 → 2px）。
     */
    public static final int POS_TITLE_H = 13;
    /**
     * 数据行高度：一行里横向排「方向 · 数量 · 倍数 · 盈亏 · 距爆仓 · [平仓]」。
     *
     * <p>⚠️ 16 → **17**：16px 里墨迹占 y=3..11、行底分隔线在 15 → 净空 4px（本来就够），
     * 但配合表头抬高之后整表节奏更稳；行数在常用档位上**不受影响**（见下方注释的验算）。
     */
    public static final int POS_ROW_H = 17;
    /** 第二行文字相对行顶的偏移（第一行是 {@code POS_LINE1_DY}）。 */
    public static final int POS_LINE1_DY = 0;
    public static final int POS_LINE2_DY = 10;
    /** 行尾「平仓」按钮（表格里的那一个）。 */
    public static final int POS_CLOSE_W = 20;
    public static final int POS_CLOSE_H = 12;
    /** 按钮离表格右缘（滚动条之内）、以及文字与按钮之间的空隙。 */
    public static final int POS_CLOSE_PAD_X = 1;
    public static final int POS_CLOSE_GAP = 2;
    /** 行文字离表格左缘的内边距。 */
    public static final int POS_TEXT_PAD_X = 0;
    /** 表头行右端留给页码（「1/3」）的宽度。 */
    public static final int POS_PAGE_W = 20;
    /** 表格**至少**显示几行、**最多**同屏几行（表头之外的数据行）。 */
    public static final int POS_MIN_ROWS = 2;
    public static final int POS_MAX_ROWS = 6;
    /** 表格与走势图之间的空隙。 */
    public static final int POS_GAP = 4;
    /** 表格整体高度按内容区的这个比例取，再夹在下面两个上下限之间。 */
    public static final int POS_TABLE_MIN_H = 11 + 16 + 1;      // 表头 + 至少 1 行
    /**
     * 表格高度上限。
     *
     * <p>⚠️ 原来是「表头 + 6 行」＝ 107px，那个上限**把行数卡在 6**（`posVisibleRows()` 按高度反推），
     * 所以玩家说「把持仓框上端往上画点」时，光加高度不加这个上限是没用的 ——
     * 高度加了 10px，行数还是 6（实测）。
     * 现在放到「表头 + 8 行」，真正的高度仍由「内容区比例 + 图表下限」决定，
     * 所以不会无脑变高；`POS_TABLE_LIFT` 让出的那一截也就真的变成行数了。
     */
    public static final int POS_TABLE_MAX_H = 11 + 8 * 16 + 2;
    /**
     * 持仓表格**额外往上抬**的高度（玩家：「把持仓框上端往上画点」）。
     *
     * <p>表格变高这么多、走势图相应变矮；不够让就自动少抬（见 {@code compute} 里的
     * 「图表还能保住 {@link #CHART_MIN_H}」那一步），**不引入任何手写坐标**。
     */
    public static final int POS_TABLE_LIFT = 10;
    /** 表格右缘那条竖向滚动条的宽度（轨道），滑块占满宽度。 */
    public static final int POS_SCROLLBAR_W = 3;
    /**
     * 表格的列宽（像素）：方向 / 数量 / 倍数 / 盈亏 / 距爆仓 / 平仓按钮。
     *
     * <p>⚠️ 这些是**固定列宽**，各行必须用同一套 x —— 否则数值左右参差就读不出表格感。
     * 宽度按「中文列名 4 字 ≈ 40px」「数字列 5~7 字符 ≈ 30~45px」估的；
     * 窄窗口下 {@code posCols()} 会按可用宽度整体缩放（见那里的注释）。
     */
    public static final int[] POS_COL_W = {30, 30, 30, 30, 30, POS_CLOSE_W};

    // ==================== 回收页 ====================

    /**
     * 回收页顶部说明行的高度。
     *
     * <p>⚠️⚠️ 14 → **0**：那行字（{@code gui.mcstock.recycle.hint}）已经按玩家要求删掉了，
     * 但布局还在给它留 14px —— 后果是「明明放得下，却把明细列表丢掉一行」。
     * 归零之后省下的高度按既有优先级全给明细列表。
     * 常量保留（而不是删掉）是为了让 {@code hint} 矩形的语义不变：它现在恒为 0 高，
     * {@code RecyclePlan.fits()} 会把它当成「这一档丢了它」。
     */
    public static final int HINT_H = 0;
    public static final int BAR_COLS = 9;
    public static final int BAR_ROWS = 2;
    public static final int BAR_INFO_W = 190;
    /**
     * 说明行与回收栏之间的空隙。
     *
     * <p>⚠️ 14 → 8 → **4**：这一条是明细列表上面唯一的可压缩余量。
     * 收到 4 之后，640×340 那档才能挤出「至少一行明细」（玩家：「中间那片空白不该有」）。
     */
    public static final int BAR_TOP_GAP = 4;
    public static final int RECYCLE_BTN_W = 170;
    public static final int RECYCLE_BTN_H = 28;
    /**
     * 「回 收」按钮的宽度上限 / 下限。
     *
     * <p>⚠️ 上限是玩家实机要求（「按钮非常宽，文字贴在左端，右侧一大截空着」）：
     * 按钮不再拉满整行，也不越过内容区右内边距。下限保证窄窗口下还点得着。
     */
    public static final int RECYCLE_BTN_MIN_W = 76;
    /** 回收栏与右侧「合计块 / 按钮」那一列之间的间距（≥6，和别的块一个口径）。 */
    public static final int GROUP_INNER_GAP = 20;
    /** 回收明细（一行一条，三列：物品×数量 / 单价 / 小计）。 */
    public static final int DETAIL_LINE_H = 13;
    /**
     * 明细的右侧两列（单价 / 小计）宽度。
     *
     * <p>⚠️ 玩家要求「另外写清楚单价和总价」，于是每行从「名称 ×N ……… 件数」改成三列。
     * 右对齐的两列都按**小号点阵字体**量宽（每位 4px）：
     * <pre>
     *   单价：最多 6 位（`100000` = 24px）→ 给 30px
     *   小计：最多 7 位（`1000000` = 28px，含小数点则 8 位 32px）→ 给 36px
     * </pre>
     * 列宽不够时**优先保小计、其次单价**（见 {@code drawRecycleText} 里的让位顺序）。
     */
    public static final int DETAIL_UNIT_W = 30;
    public static final int DETAIL_AMOUNT_W = 36;
    /** 明细三列之间的最小空隙（小号数字彼此离太近会糊在一起）。 */
    public static final int DETAIL_COL_GAP = 4;
    public static final int INV_COLS = 9;
    public static final int INV_ROWS = 3;
    public static final int INV_LABEL_H = 12;

    /**
     * 回收页右侧「合计块」的**行数**与行高。
     *
     * <p>行高给 13 而不是按字号给 9：中文 Unifont 下两行之间要留一点气口，
     * 挤在一起会糊成一段（这是 {@code FUNDS_LINE_H} 同一个理由）。
     *
     * <p>⚠️ 行数的沿革：3 → **2**（删掉「同类的会一起卖」那一行，省下的 13px 给明细）→
     * 现在两行是「合计 N 种 · M 件 · 总价 X」+「不值钱 N 件」（第二行没有不值钱的就不画）。
     */
    public static final int BAR_INFO_LINES = 2;
    public static final int BAR_INFO_LINE_H = 13;
    /** 合计块文字与回收按钮之间的空隙。 */
    public static final int BAR_INFO_BTN_GAP = 8;
    /**
     * 回收按钮与明细框之间的空隙（明细框从按钮下方再让开这么多）。
     *
     * <p>⚠️ 4 → **2**：480×270（1920×1080 全屏 + 缩放 4 的<b>逻辑</b>分辨率）
     * 那一档原来明细框只有 13px 高 —— 一行文字 9px 正好把框占满，
     * **下边框直接压在字上**（玩家截图里"文字重叠/压在边框上"就是这一档）。
     * 明细框至少要有「上内缩 3 + 字 9 + 下内缩 3」= 15px 才不压线，
     * 这 2px 就是从这条空隙里让出来的（它比 `BAR_TOP_GAP` 更富裕、也不影响别的块）。
     */
    public static final int RECYCLE_BTN_DETAIL_GAP = 2;
    /**
     * 明细框**边框内缩**：行文字相对 {@code barDetail} 的上/左偏移。
     *
     * <p>⚠️ 以前是硬编码的 {@code padY = 3}，而框高是按「整行数 × {@link #DETAIL_LINE_H}」
     * 算的 —— 框正好一行高（13px）时，文字就从框顶 +3 排到 +11，
     * **底部只剩 2px、边框描边正好压在最后一行的下缘上**。
     * 所以 ① 内缩必须是常量，② 框高必须**大于一行**才允许画（见 {@link #DETAIL_MIN_H}）。
     */
    public static final int DETAIL_TEXT_INSET = 3;
    /**
     * 明细框能画内容的**最小高度**：上下各留 {@link #DETAIL_TEXT_INSET} + 一行文字。
     *
     * <p>比这个矮就**整块不画**（{@code detailLines()} 返回 0，背景框也不画），
     * 而不是画一个"字贴着边框"的框 —— 那正是 480×270 那一档的 bug。
     */
    public static final int DETAIL_MIN_H = DETAIL_TEXT_INSET * 2 + 9;
    /**
     * 合计块 + 回收按钮那一坨的总高度：3 行文字 + 空隙 + 按钮。
     * 回收页右列按这个高度排，明细框从它下面接着排。
     */
    public static final int BAR_INFO_BLOCK_H =
            BAR_INFO_LINES * BAR_INFO_LINE_H + BAR_INFO_BTN_GAP + RECYCLE_BTN_H;
    /**
     * 明细框「有地方就至少给几行」。
     *
     * <p>⚠️ 它是**软下限**：屏幕实在矮时明细会一路缩到 0 行（整块不画）。
     * 回收页的硬需求是「背包 36 格 + 合计块 + 回收按钮」——
     * 玩家得靠 shift 点背包才能往回收栏放东西，没背包这一页就没法玩（实机反馈）。
     *
     * <p>取 0 而不是 1：这一项参与「回收按钮贴在背包上方」的定位，
     * 取 1 会白留一行高度，把明细框本来画得下的 2~3 行挤掉。
     */
    public static final int DETAIL_MIN_LINES = 0;
    /** 明细框与其下背包之间的空隙（同时决定回收按钮离背包多远）。 */
    public static final int DETAIL_BOTTOM_GAP = 4;

    /**
     * 面板的**实际最小高度** —— 由回收页那些「一定要放下」的东西反推，而不是随手写的数字。
     *
     * <p>回收页的纵向硬需求（自上而下，对应 {@link #compute} 里的坐标算式）。
     * ⚠️ 下面这些数字是**当前常量的实际值**（2026-10-05 订正过一轮，原来是旧值）：
     * <pre>
     *   TITLE_H 18 + TAB_ROW_GAP 0 + TAB_H 16 + CONTENT_GAP 0   ← 标题栏 + 页签
     *   HINT_H 0                  ← 顶部说明（文字已删，不再留高度）
     *   BAR_TOP_GAP 4
     *   BAR_ROWS × SLOT 36        ← 回收栏 18 格
     *   BAR_INFO_BLOCK_H 62       ← 右列：2 行合计文字（BAR_INFO_LINES×13）+ 8 + 回收按钮 28
     *   RECYCLE_BTN_DETAIL_GAP 4
     *   DETAIL_MIN_LINES × DETAIL_LINE_H = 0   ← 软下限，只保证「还能露一行」
     *   DETAIL_BOTTOM_GAP 4
     *   INV_LABEL_H 12 + 3×18 + 6 + 18 = 90     ← **背包 27 格 + 快捷栏 9 格，必须完整可见**
     *   CONTENT_BOTTOM_PAD 8
     * </pre>
     *
     * <p>⚠️ 屏幕比这个还矮时（例如 854×480 + GUI 缩放 4 → 逻辑 213×120，面板被夹到 108），
     * {@code compute} 会**先把明细框缩到 0 行**，背包仍然钉在内容区底部；真的连背包都放不下
     * 才会裁掉底部 —— 但任何尺寸下**元素之间都不会重叠**。
     */
    public static final int MIN_CONTENT_HEIGHT =
            HINT_H + BAR_TOP_GAP + BAR_ROWS * SLOT + BAR_INFO_BLOCK_H
                    + RECYCLE_BTN_DETAIL_GAP + DETAIL_MIN_LINES * DETAIL_LINE_H
                    + DETAIL_BOTTOM_GAP + INV_LABEL_H + INV_ROWS * SLOT + 6 + SLOT;

    /** 见 {@link #MIN_CONTENT_HEIGHT}。 */
    public static final int MIN_PANEL_HEIGHT =
            TITLE_H + TAB_ROW_GAP + TAB_H + CONTENT_GAP + MIN_CONTENT_HEIGHT + CONTENT_BOTTOM_PAD;

    // ==================== 购买页 ====================

    public static final int FILTER_H = 18;
    public static final int CAT_BTN_W = 84;
    public static final int PAGE_BTN_W = 18;
    public static final int PAGE_TEXT_W = 46;
    public static final int SEARCH_MIN_W = 90;
    public static final int GRID_GAP_TOP = 6;
    /** 购买页底部放玩家背包（和创造模式物品栏一样：点格子直接拿）。 */
    /**
     * 购买页网格下方那条提示行的**保留高度**。
     *
     * <p>⚠️ 12 → 0（与回收页的 {@link #HINT_H} 同一条）：那行操作说明
     * （{@code gui.mcstock.shop.hint}）已按玩家要求删除，布局不该再留高度。
     * 网格因此能一直吃到背包说明的正上方。
     * （{@link #SHOP_HINT_MIN_H} 是同一件事的别名，保留给旧引用。）
     */
    public static final int SHOP_HINT_H = 0;
    /** 帮助覆盖层：一行正文的高度，以及正文条数（与 {@code gui.mcstock.help.N} 的 N 对应）。 */
    public static final int HELP_LINE_H = 11;
    public static final int HELP_LINES = 20;
    /** 标题栏下方那条页面级状态行（服务端 flash 提示）的高度。 */
    public static final int FLASH_ROW_H = 11;
    /**
     * 内容区顶部给 flash 状态行**预留**的高度。
     *
     * <p>⚠️ 这一条是真机截图抓出来的：以前 {@code flashRow()} 直接取 {@code content.y()}，
     * 而左列公司卡 / 走势图 / 右列按钮**也都从 {@code content.y()} 开始** ——
     * 于是提示永远压在页面第一行元素上（购买页看上去就是「飘在物品网格中间」）。
     * 现在从内容区顶部切出这一条带，三页的页面内容统一从
     * {@code content.y() + FLASH_BAND_H} 开始，**互不重叠**。
     */
    public static final int FLASH_BAND_H = 11;
    public static final int GRID_MIN_COLS = 8;
    public static final int GRID_MIN_ROWS = 4;
    /** 网格列数上限：GUI 缩放 1 时逻辑宽度有 1920，不设上限会铺出 39 列，太散。 */
    public static final int GRID_MAX_COLS = 20;
    /** 网格行数上限（屏幕上沿到背包之间的高度够时也不会无限长下去）。 */
    public static final int GRID_MAX_ROWS = 16;
    /**
     * 网格下方那条提示行的**保留高度**。
     *
     * <p>⚠️ 0：玩家要求「删掉这里的字」，于是不再为它留预留带，网格直接吃到背包说明上方。
     * 保留这个常量是为了让 {@code shopHint} 的矩形语义不变（宽高可能为 0，绘制端必须容忍）。
     */
    public static final int SHOP_HINT_MIN_H = 0;

    // ==================== 圆角（柔和观感的关键） ====================

    /**
     * 圆角半径。这些值**必须和贴图里的圆角一致**：
     * 面板四角的 8×8 切片、卡片、按钮、页签、填写栏的圆角都是按这里画的。
     * 代码只负责把切片摆对位置，圆角本身是贴图里的透明像素 —— 所以改半径要同时重跑
     * {@code tools/gen_gui_textures.py}。
     */
    public static final int RADIUS_PANEL = 6;
    public static final int RADIUS_BOX = 4;
    public static final int RADIUS_SLOT = 3;

    // ==================== 贴图纸（模块化切片，256 × 256） ====================
    //
    // ⚠️ 因为窗口尺寸可变，**没有整窗底图**了：面板边框是 9 张 8×8 切片，
    // 槽位、卡片、按钮、页签都是可以拼/拉的小块。美术画这些小块即可。

    public static final int ATLAS_W = 256;
    public static final int ATLAS_H = 256;

    /** 贴图纸上的一个分区（源图坐标 + 尺寸）。 */
    public record Sprite(int u, int v, int w, int h) {
    }

    // 面板边框 9 段（四角 8×8 + 四边 8×8 + 中心 8×8）
    public static final Sprite FRAME_TL = new Sprite(0, 0, 8, 8);
    public static final Sprite FRAME_TOP = new Sprite(8, 0, 8, 8);
    public static final Sprite FRAME_TR = new Sprite(16, 0, 8, 8);
    public static final Sprite FRAME_LEFT = new Sprite(0, 8, 8, 8);
    public static final Sprite FRAME_CENTER = new Sprite(8, 8, 8, 8);
    public static final Sprite FRAME_RIGHT = new Sprite(16, 8, 8, 8);
    public static final Sprite FRAME_BL = new Sprite(0, 16, 8, 8);
    public static final Sprite FRAME_BOTTOM = new Sprite(8, 16, 8, 8);
    public static final Sprite FRAME_BR = new Sprite(16, 16, 8, 8);
    // 标题栏底纹（横向平铺的 8×18 小块）
    public static final Sprite TITLE_TILE = new Sprite(24, 0, 8, 18);
    // 图标
    public static final Sprite ICON_UP = new Sprite(24, 20, 9, 8);
    public static final Sprite ICON_DOWN = new Sprite(33, 20, 9, 8);
    public static final Sprite ICON_LEFT = new Sprite(42, 20, 9, 8);
    public static final Sprite ICON_RIGHT = new Sprite(51, 20, 9, 8);
    public static final Sprite ICON_COIN = new Sprite(60, 20, 8, 8);
    // 关闭按钮
    public static final Sprite CLOSE_NORMAL = new Sprite(24, 30, 12, 12);
    public static final Sprite CLOSE_HOVER = new Sprite(40, 30, 12, 12);
    /**
     * 帮助按钮上的「?」（12×12）。
     *
     * <p>⚠️ 这一格是**新增**的（图集原来空着），和 CLOSE 两格一起由
     * {@code tools/patch_close_help_sprites.py} **硬边逐像素**重绘 —— 别改成「画大图再缩放」：
     * CLOSE 两格当初就是那么做的，LANCZOS 把线冲淡到 alpha ≤ 40，
     * 实机上只剩一个幽灵圈（玩家反馈「关闭处只有一个圈，没有叉」）。
     */
    public static final Sprite ICON_HELP = new Sprite(76, 30, 12, 12);
    // 槽位贴图（18×18，直接按格子数量铺）
    public static final Sprite SLOT_TILE = new Sprite(0, 32, 18, 18);
    // 填写栏底（48×20，水平 9 段拉伸：左右各 4px 不拉伸）。EditBox 用 setBordered(false) 画在上面
    public static final Sprite FIELD_TILE = new Sprite(0, 184, 48, 20);
    // 企业卡片（固定 56×44，左列宽度固定，所以不需要拉伸）
    public static final Sprite CARD_NORMAL = new Sprite(0, 56, 56, 44);
    public static final Sprite CARD_HOVER = new Sprite(56, 56, 56, 44);
    public static final Sprite CARD_SELECTED = new Sprite(112, 56, 56, 44);
    // 按钮四态（72×16，9 段横向拉伸：左右各 4px 不拉伸，中间 64px 拉伸）
    public static final Sprite BTN_NORMAL = new Sprite(0, 104, 72, 16);
    public static final Sprite BTN_HOVER = new Sprite(0, 120, 72, 16);
    public static final Sprite BTN_PRESSED = new Sprite(0, 136, 72, 16);
    public static final Sprite BTN_DISABLED = new Sprite(0, 152, 72, 16);
    // 页签两态（64×16，9 段横向拉伸）
    public static final Sprite TAB_ACTIVE = new Sprite(0, 168, 64, 16);
    public static final Sprite TAB_INACTIVE = new Sprite(64, 168, 64, 16);

    // ---------- 买入 / 卖出按钮（红涨绿跌，与 StockTheme.COLOR_UP/DOWN 同一套口径）----------
    //
    // ⚠️ 为什么不去染灰皮肤：`g.setColor(...)` 是**乘法**染色，灰色底 (58,63,76) 乘上红色
    //    会把蓝色分量也一起留下、整块发浑（玩家要的是"红按钮"，不是"脏紫按钮"）。
    //    所以底色与描边直接在图集里画好（tools/gen_gui_textures.py），这里只登记坐标。
    //
    // ⚠️ 每个色系要有**三态**：`StockButton#pickSprite` 的按下态会取 PRESSED，
    //    只给 NORMAL/HOVER 的话按下去会突然变回灰色皮肤。
    //    禁用态沿用中性的 {@link #BTN_DISABLED}（买入/卖出永远是 active，
    //    真出现禁用时用中性灰比"灰红"更容易一眼看出"不能点"）。
    //
    // 坐标都落在图集底部的空白区（x 72..144 / 144..216、y 208 起），已按占用表核对无重叠。
    // ⚠️ 别再往 y=168..200 那一带放：x=64..128 那里是 TAB_INACTIVE（64×16），
    //    往右排会正好压上去（这个坑踩过一次，见 gen_gui_textures.py 的坐标自检）。
    public static final Sprite BTN_BUY_NORMAL = new Sprite(72, 208, 72, 16);
    public static final Sprite BTN_BUY_HOVER = new Sprite(72, 224, 72, 16);
    public static final Sprite BTN_BUY_PRESSED = new Sprite(72, 240, 72, 16);
    public static final Sprite BTN_SELL_NORMAL = new Sprite(144, 208, 72, 16);
    public static final Sprite BTN_SELL_HOVER = new Sprite(144, 224, 72, 16);
    public static final Sprite BTN_SELL_PRESSED = new Sprite(144, 240, 72, 16);

    /**
     * 一组按钮皮肤（四态）。
     *
     * <p>给 {@code StockWidgets} 的按钮工厂用：默认那组是灰色 {@code BTN_*}，
     * 买入/卖出各用一组红/绿。⚠️ 放在这里而不是 {@code StockWidgets}，
     * 是为了让坐标仍然**只有 {@code StockGuiLayout} 一个来源**（AGENTS.md 5.3）。
     */
    public record Skin(Sprite normal, Sprite hover, Sprite pressed, Sprite disabled) {
        /** 默认灰色皮肤（页签以外的所有普通按钮）。 */
        public static final Skin PLAIN = new Skin(BTN_NORMAL, BTN_HOVER, BTN_PRESSED, BTN_DISABLED);
        public static final Skin BUY = new Skin(BTN_BUY_NORMAL, BTN_BUY_HOVER, BTN_BUY_PRESSED, BTN_DISABLED);
        public static final Skin SELL = new Skin(BTN_SELL_NORMAL, BTN_SELL_HOVER, BTN_SELL_PRESSED, BTN_DISABLED);
    }

    // ==================== 矩形与布局结果 ====================

    /** 面板相对/屏幕绝对坐标下的一个矩形（x 向右、y 向下）。 */
    public record Rect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public Rect inset(int dx, int dy) {
            return new Rect(x + dx, y + dy, Math.max(0, w - dx * 2), Math.max(0, h - dy * 2));
        }
    }

    /**
     * 一次布局的结果。所有坐标都是**屏幕绝对坐标**（布局算完就直接画/摆槽位，不用再加偏移）。
     */
    public record Layout(
            int screenW, int screenH,
            Rect panel, Rect titleBar, Rect[] tabs, Rect content, Rect closeBtn, Rect helpBtn,
            // 行情页
            Rect leftCol, Rect[] cards, Rect indexBlock, Rect chartPanel, Rect chartHeader,
            Rect chartTicker, Rect chartPrice, Rect chartPlot,
            Rect rightCol, Rect btnBuy, Rect btnSell, Rect fundsBlock, Rect posList,
            int candleCount,
            // 行情页右列：数量行 / 杠杆行（标签 + 填写栏 + ▲ + ▼ + MAX）
            Rect qtyLabel, Rect qtyField, Rect qtyUp, Rect qtyDown, Rect qtyMax,
            Rect levLabel, Rect levField, Rect levUp, Rect levDown, Rect levMax,
            // 回收页
            Rect hint, Rect bar, Rect barInfo, Rect recycleBtn, Rect barDetail, Rect invLabel, Rect inv, Rect hotbar,
            // 购买页（创造模式物品栏那种：上面网格点一下就拿，下面玩家背包）
            Rect catBtn, Rect searchBox, Rect prevBtn, Rect pageText, Rect nextBtn,
            Rect grid, Rect shopInvLabel, Rect shopInv, Rect shopHotbar, Rect shopHint,
            int gridCols, int gridRows, int pageSize
    ) {
        /** 小屏幕上「资金情况」放不下 7 行，按可用高度决定画几行。 */
        public int fundsLines() {
            return Math.max(1, Math.min(FUNDS_LINES, (fundsBlock.h() - FUNDS_TITLE_H) / FUNDS_LINE_H));
        }

        /**
         * 持仓列表同屏能显示几行（滚动窗口的高度）。
         *
         * <p>可以返回 **0**（极小窗口下标题以外一行都放不下）：那时列表只画标题与「暂无持仓」，
         * 绘制端与控件端都必须按 0 处理（别建按钮、别画行）。
         */
        public int posVisibleRows() {
            return Math.max(0, (posList.h() - POS_TITLE_H) / POS_ROW_H);
        }

        /** 列表标题行（左标题、右页码都画在这一行的两端）。 */
        public Rect posHeader() {
            return new Rect(posList.x(), posList.y(), posList.w(), POS_TITLE_H);
        }

        /**
         * 表格的**列矩形**（第 {@code col} 列，横跨整行高度）。
         *
         * <p>列宽来自 {@link #POS_COL_W}，窗口窄时按比例压缩（表格宽度 = 走势图宽度：
         * 640×360 下 371px、427×240 下只剩 184px），但**行尾「平仓」按钮那一列不参与压缩**、
         * 永远保持 {@link #POS_CLOSE_W} —— 它是**唯一**的平仓入口，被压成 16px 就点不准了
         * （实测按比例压到 16px，还和前邻列贴成 0 间距）。数据列则按剩余宽度等比缩放。
         *
         * <p>列序：0=方向 1=数量 2=倍数 3=盈亏 4=距爆仓 5=平仓按钮。
         */
        public Rect posCol(int col, int row) {
            Rect rowRect = posRow(row);
            // ⚠️ 列**用满整个表格宽度**（原来又减了一次 POS_TEXT_PAD_X*2，导致「距爆仓」和
            //    「平仓」两列挤在一起、末列还被压缩）。现在只从滚动条那 3px 里让开。
            int avail = Math.max(1, posList.w() - POS_SCROLLBAR_W);
            int btnW = Math.min(POS_CLOSE_W, Math.max(1, avail / 2));
            int dataAvail = Math.max(1, avail - btnW - COL_GAP);
            // 数据列（0..4）的宽度表
            int dataTotal = POS_COL_W[0] + POS_COL_W[1] + POS_COL_W[2] + POS_COL_W[3] + POS_COL_W[4];
            int x = rowRect.x();
            int idx = Math.max(0, Math.min(col, POS_COL_W.length - 1));
            if (idx == 5) {
                return new Rect(x + dataAvail + COL_GAP, rowRect.y(), btnW, rowRect.h());
            }
            for (int i = 0; i < idx; i++) {
                x += Math.max(1, POS_COL_W[i] * dataAvail / dataTotal);
            }
            int cw = Math.max(1, POS_COL_W[idx] * dataAvail / dataTotal);
            return new Rect(x, rowRect.y(), cw, rowRect.h());
        }

        /**
         * 表格右缘那条**竖向滚动条**的轨道（滑块在 {@link #posScrollThumb}）。
         *
         * <p>轨道 = 数据行区的整条高度；滑块高度 = 可见行 / 总行 × 轨道高（最小 8px，
         * 否则行数一多滑块就细成一根线、没法拖）。
         */
        public Rect posScrollTrack() {
            Rect header = posHeader();
            return new Rect(posList.right() - POS_SCROLLBAR_W - 1, header.bottom(),
                    POS_SCROLLBAR_W, Math.max(0, posList.bottom() - header.bottom()));
        }

        /** 滚动条滑块：{@code firstRow} 是当前首行、{@code totalRows} 是总行数。 */
        public Rect posScrollThumb(int firstRow, int totalRows) {
            Rect track = posScrollTrack();
            int rows = posVisibleRows();
            if (track.h() <= 0 || totalRows <= 0 || rows <= 0 || totalRows <= rows) {
                return new Rect(track.x(), track.y(), 0, 0);        // 不用滚：不画滑块
            }
            int h = Math.max(8, track.h() * rows / totalRows);
            int maxFirst = Math.max(1, totalRows - rows);
            int y = track.y() + (track.h() - h) * Math.min(firstRow, maxFirst) / maxFirst;
            return new Rect(track.x(), y, track.w(), h);
        }

        /** 表格文字能用到的最右边界（再往右是滚动条）。 */
        public int posTextRight(int row) {
            return posList.right() - POS_SCROLLBAR_W - POS_TEXT_PAD_X - 1;
        }

        /**
         * 标题栏下方那条**账户级状态行**（画服务端 flash 提示）。
         *
         * <p>位置在 {@code content} 的**顶部一行**：那一行在任何页面都是空白留白
         * （行情页的卡片/大屏、回收页的说明、购买页的分类行都从 content.y() 开始，
         * 但 flash 只在有事件时出现，短暂压一行可接受的空白比「被图表盖住」好得多）。
         * 宽度取内容区一半，避免长提示横贯整屏。
         */
        public Rect flashRow() {
            return new Rect(content.x() + 2, content.y(), Math.max(0, content.w() - 4), FLASH_ROW_H);
        }

        /**
         * 页面内容真正开始的那条 y（= {@code content.y() + FLASH_BAND_H}）。
         *
         * <p>三页的页面内容都从这里开始，把内容区顶部那一条留给 flash 状态行。
         * **所有从 content.y() 起步的块都要改用这个值**，否则提示又会压上去。
         */
        public int contentTop() {
            return content.y() + FLASH_BAND_H;
        }

        /** 标题行右端的页码块（右对齐画「1/3」）。 */
        public Rect posPage() {
            Rect header = posHeader();
            int w = Math.max(0, Math.min(POS_PAGE_W, header.w() / 3));
            return new Rect(header.right() - w - POS_TEXT_PAD_X, header.y(), w, header.h());
        }

        /**
         * 表格第 {@code i} 行（{@code i} 是**当前这一屏**的行号，不含滚动偏移）。
         *
         * <p>行矩形不是容器槽、也不是控件：它是文字与分隔线的绘制范围，
         * 行尾「平仓」按钮另有 {@link #posCloseBtn(int)}。
         */
        public Rect posRow(int i) {
            return new Rect(posList.x(), posList.y() + POS_TITLE_H + i * POS_ROW_H,
                    Math.max(0, posList.w()), POS_ROW_H);
        }

        /** 第 {@code i} 行行尾那个「平仓」按钮的矩形（每行一个，由屏幕动态建/删控件）。 */
        public Rect posCloseBtn(int i) {
            return posCol(5, i);
        }

        /**
         * 回收明细能画几行。
         *
         * <p>⚠️ 允许返回 **0**：屏幕太矮时明细框会被压成 0 高（整块不画），
         * 这是刻意的优先级 —— 背包必须完整可见（回收页要靠 shift 点背包），
         * 明细是可有可无的信息。绘制端必须处理 0（别画标题、别画行）。
         */
        public int detailLines() {
            // ⚠️ 扣掉上下内缩（DETAIL_TEXT_INSET × 2）：框高 = 内缩×2 + 行数×行高，
            //    所以能画的行数是 (h − 内缩×2) / 行高。
            //    框太矮（< DETAIL_MIN_H）时这里返回 0 → 背景框与文字都不画。
            if (barDetail.h() < DETAIL_MIN_H) {
                return 0;
            }
            return Math.max(0, (barDetail.h() - DETAIL_TEXT_INSET * 2) / DETAIL_LINE_H);
        }

        /**
         * 回收明细一行的三列矩形（{@code col}：0 = 物品×数量，1 = 单价，2 = 小计）。
         *
         * <p>⚠️ 玩家要求「另外写清楚单价和总价」，于是每行从两段（名称 / 件数）改成三列。
         * 布局放在这里而不是绘制端手算，理由同 AGENTS.md 5.3。
         *
         * <p>让位顺序（宽度不够时**优先保小计**、其次单价，最后才压名称列）：
         * <pre>
         *   名称列 = 剩下的全部（最少 DETAIL_COL_GAP）
         *   单价   = DETAIL_UNIT_W，实在挤不下就 0（那一列不画）
         *   小计   = DETAIL_AMOUNT_W，永远优先保留
         * </pre>
         * 两列都是**右对齐**画小号数字，所以它们的右边缘是稳定的：
         * {@code right - padX - DETAIL_AMOUNT_W} 与 {@code right - padX}。
         */
        public Rect detailCol(int col, int rowIndex) {
            int padX = 8;
            // ⚠️ 纵向起点必须带上 DETAIL_TEXT_INSET（框的上内缩），否则第一行会压上边框、
            //    最后一行会压下边框（480×270 那一档的 bug）。
            int top = barDetail.y() + DETAIL_TEXT_INSET + rowIndex * DETAIL_LINE_H;
            int amountX = barDetail.right() - padX - DETAIL_AMOUNT_W;
            int unitX = amountX - DETAIL_COL_GAP - DETAIL_UNIT_W;
            int nameX = barDetail.x() + padX;
            return switch (col) {
                case 1 -> new Rect(unitX, top, DETAIL_UNIT_W, DETAIL_LINE_H);
                case 2 -> new Rect(amountX, top, DETAIL_AMOUNT_W, DETAIL_LINE_H);
                default -> new Rect(nameX, top,
                        Math.max(DETAIL_COL_GAP, unitX - DETAIL_COL_GAP - nameX), DETAIL_LINE_H);
            };
        }

        /** 行情页右列里「数量」「杠杆」两行的矩形，按 index 0/1 取。 */
        public Rect fieldRow(int index) {
            return index == 0
                    ? new Rect(qtyLabel.x(), qtyLabel.y(), rightCol.w(), FIELD_ROW_H)
                    : new Rect(levLabel.x(), levLabel.y(), rightCol.w(), FIELD_ROW_H);
        }

        /**
         * 左列「大盘」块的第 {@code i} 行（{@code 0} = 标题、{@code 1} = 指数、{@code 2} = 涨跌、
         * {@code 3} = 大盘状态）。超出行数返回 0 高矩形，绘制端按 {@link #indexLines()} 循环即可。
         */
        public Rect indexLine(int i) {
            return new Rect(indexBlock.x(), indexBlock.y() + i * INDEX_LINE_H, indexBlock.w(), INDEX_LINE_H);
        }

        /**
         * 「大盘」块能画几行。
         *
         * <p>可以返回 **0**（左列矮到卡片下面没有余地）：那时整块不画 ——
         * 大盘指数是锦上添花，卡片（选股票）才是这一列的本职。
         */
        public int indexLines() {
            return Math.max(0, Math.min(INDEX_LINES, indexBlock.h() / INDEX_LINE_H));
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 把矩形整体**夹进容器**（不缩放，只平移；实在比容器还大就压到容器尺寸并贴左上）。
     *
     * <p>它是回收页布局的**收口**：所有块算完之后都过一遍这里，保证绝不会出现
     * 「回收栏飞到面板外 / 负坐标」这种回归（实机反馈过）。写死坐标的代码不值得信任，
     * 收口这一道比逐块算对更可靠。
     */
    private static Rect clampToContent(Rect container, Rect rect) {
        if (rect.w() <= 0 || rect.h() <= 0) {
            // 宽高为 0 = 这一块被丢弃，不画；位置仍夹一下，免得留下荒谬的坐标
            return new Rect(clamp(rect.x(), container.x(), container.right()),
                    clamp(rect.y(), container.y(), container.bottom()), rect.w(), rect.h());
        }
        int w = Math.min(rect.w(), container.w());
        int h = Math.min(rect.h(), container.h());
        int x = clamp(rect.x(), container.x(), container.right() - w);
        int y = clamp(rect.y(), container.y(), container.bottom() - h);
        return new Rect(x, y, w, h);
    }

    /**
     * 回收页的一次排布方案（纯数据）。
     *
     * <p>{@link #fits(Rect)} = 「这一方案的所有块都完整落在内容区内」—— 由 {@link #compute}
     * 逐档试探，第一档装得下就用它。这样「丢哪一块 / 回收栏压几行」是**声明式**的，
     * 不用在坐标里做加减位移（上一版就是位移写错，把回收栏推出了面板）。
     */
    private record RecyclePlan(Rect hint, Rect bar, Rect barInfo, Rect recycleBtn, Rect barDetail,
                               Rect invLabel, Rect inv, Rect hotbar) {

        /**
         * 是否所有块都在内容区内。
         *
         * <p>宽或高为 0 的块表示「这一档把它丢了」，直接跳过 —— 所以判定天然就是
         * 「没被丢掉的块必须完整落在内容区里」。
         */
        boolean fits(Rect content) {
            // 硬需求：**回收栏、背包块、回收按钮**必须完整落在内容区内（实机反馈过它们被顶出去）
            boolean hard = inside(content, bar) && inside(content, invLabel)
                    && inside(content, inv) && inside(content, hotbar) && inside(content, recycleBtn);
            // 软需求：说明 / 合计块 / 明细 —— 要么完整在里面，要么被丢弃（宽高 0）
            boolean soft = inside(content, hint) && inside(content, barInfo) && inside(content, barDetail);
            // ⚠️ **要明细就必须至少能画下「内缩 + 一行」**：明细高度是整数行，算下来不足一行时会
            //    变成 0 高却仍被算作「在计划里」，于是这一档被判成符合、玩家看到的却是
            //    「中间一大片空白」（640×340 实测就是选中了带明细的档、明细却是 0 行）。
            //    这里直接否决，逼着上层换下一档（压回收栏 / 折合计块 / 丢说明），直到真挤出一行。
            // ⚠️ 阈值必须是 DETAIL_MIN_H（= 内缩×2 + 一行），与 detailLines() 同一口径：
            //    用 DETAIL_LINE_H 的话，13px 的框会「算作有明细」但 detailLines() 返回 0、
            //    绘制端一个字都不画 —— 又是一次「计划里有、屏幕上没有」。
            boolean detailEnough = barDetail.h() == 0 || barDetail.h() >= DETAIL_MIN_H;
            return hard && soft && detailEnough && !anyOverlap();
        }

        /**
         * 画出来的块两两不重叠。
         *
         * <p>逐档试探时用它当否决条件：任何一档只要会压在一起就换下一档，
         * 于是「不重叠」是**搜出来的**，而不是靠坐标算对（实机已经因为算错回归过一次）。
         */
        private boolean anyOverlap() {
            Rect[] all = {hint, bar, barInfo, recycleBtn, barDetail, invLabel, inv, hotbar};
            for (int i = 0; i < all.length; i++) {
                for (int j = i + 1; j < all.length; j++) {
                    if (overlaps(all[i], all[j])) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean overlaps(Rect a, Rect b) {
            if (a.w() <= 0 || a.h() <= 0 || b.w() <= 0 || b.h() <= 0) {
                return false;   // 丢弃的块不参与
            }
            return !(a.right() <= b.x() || b.right() <= a.x()
                    || a.bottom() <= b.y() || b.bottom() <= a.y());
        }

        private static boolean inside(Rect container, Rect r) {
            if (r.w() <= 0 || r.h() <= 0) {
                return true;    // 丢弃的块不参与判定
            }
            return r.x() >= container.x() && r.y() >= container.y()
                    && r.right() <= container.right() && r.bottom() <= container.bottom();
        }
    }

    /** 回收页的三种纵向排法（从最宽裕到最省）。 */
    private enum RecycleMode {
        /** 常规：说明 + 回收栏 + 合计块 + 按钮 + 明细，竖着排。 */
        NORMAL,
        /** 合计块折进按钮：按钮占满右列、合计块整块不画，省 47px 高度。 */
        MERGED_INFO,
        /** 紧凑：按钮**并排**放在回收栏右边（同一水平带），只省下按钮那 28px + 空隙。 */
        COMPACT
    }

    /**
     * 回收页排布：给定「要不要说明 / 要不要明细 / 回收栏是否压成 1 行 / 用哪种纵向排法」，
     * 算出全部矩形。
     *
     * <p>只做加法，不做位移；装不装得下由调用方拿真实 content 矩形判定
     * （见 {@link #compute} 里那几档试探）。明细行数由「到背包上沿还剩多少」反推，
     * 允许 0 行（整块不画）。
     *
     * <p>三种排法的取舍：
     * <ul>
     *   <li>{@link RecycleMode#NORMAL}：信息最全（合计块三行 + 按钮在它下面）；</li>
     *   <li>{@link RecycleMode#MERGED_INFO}：合计块不画、金额并进按钮文案 —— 窄而矮的窗口里
     *       这是让「回收栏 + 按钮 + 背包」都放得下的关键；</li>
     *   <li>{@link RecycleMode#COMPACT}：按钮与回收栏**同一水平带**。逻辑高 200 那类窗口
     *       连「回收栏 + 按钮 + 背包」的纵向三连都放不下（背包块自己就 90px），
     *       把按钮挪到回收栏右边就能省下它那一格高度 —— 回收栏与背包都保留。</li>
     * </ul>
     */
    private static RecyclePlan planRecycle(Rect content, boolean keepHint, boolean keepDetail,
                                           boolean singleRowBar, RecycleMode mode,
                                           int invBlockH, int infoH, int barW, int groupW, int groupX) {
        int barH = (singleRowBar ? 1 : BAR_ROWS) * SLOT;
        boolean compact = mode == RecycleMode.COMPACT;
        boolean merged = mode != RecycleMode.NORMAL;

        // ⚠️ 从 contentTop() 起（内容区顶部那一条留给 flash 状态行）
        int cursor = content.y() + FLASH_BAND_H;
        Rect hint = new Rect(content.x(), cursor, content.w(), keepHint ? HINT_H : 0);
        if (keepHint) {
            cursor += HINT_H;
        }
        cursor += BAR_TOP_GAP;
        Rect bar = new Rect(groupX, cursor, barW, barH);
        int barRight = bar.right();

        // 合计块：NORMAL 才画。⚠️ 与回收栏之间留 GROUP_INNER_GAP（原来写死 20，
        // 而且 compact 档按钮直接贴到 barRight 上 → 玩家看到「按钮压住合计块」）。
        Rect barInfo;
        int btnY;
        int infoX = barRight + GROUP_INNER_GAP;
        if (merged) {
            barInfo = new Rect(infoX, cursor, 0, 0);
            btnY = cursor;
        } else {
            barInfo = new Rect(infoX, cursor, BAR_INFO_W, infoH);
            btnY = cursor + infoH + BAR_INFO_BTN_GAP;
        }
        // ⚠️ 按钮宽度**必须有上限**（玩家：「右上那个回收按钮非常宽，文字贴在左端、右侧一大截空着」）：
        //    ① 上限 RECYCLE_BTN_W；② 不许越过内容区右内边距（content.right() - CONTENT_PAD，与其它块同一口径）。
        int btnRightLimit = content.right() - CONTENT_PAD;
        int btnW = Math.max(RECYCLE_BTN_MIN_W, Math.min(RECYCLE_BTN_W, btnRightLimit - infoX));
        // 紧凑模式下按钮与回收栏同一水平带（**竖直居中**）。
        // ⚠️⚠️ 但按钮**绝不允许比回收栏高**：探针扫过 640×300..370 发现，
        //    「回收栏压成 1 行」那一档（栏高 18）配 28px 的按钮时，按钮下沿比回收栏低 10px，
        //    把明细往下推 —— 于是**降级档反而比原档更高**，明细行数在相邻高度之间
        //    反复从 5 掉到 0（实测 304/318/334/348/362 这几档）。现在把按钮高度夹在栏高之内，
        //    「降级」才是真的降级（单调不减）。
        int btnH = compact ? Math.min(RECYCLE_BTN_H, barH) : RECYCLE_BTN_H;
        int btnRowY = compact
                ? cursor + Math.max(0, (barH - btnH) / 2)
                : btnY;
        Rect recycleBtn = new Rect(infoX, btnRowY, btnW, btnH);
        cursor += barH;
        if (merged && !compact) {
            cursor += RECYCLE_BTN_H;
        }
        // ⚠️ 明细的起点要按**这一档里按钮的实际下沿**算：
        //    NORMAL 档按钮在合计块下面（还没走到），MERGED 档按钮已经走过了。
        //    直接沿用 cursor 会让 NORMAL 档的明细框压住按钮（实机验证脚本抓出来的）。
        int detailY = Math.max(cursor, recycleBtn.bottom()) + RECYCLE_BTN_DETAIL_GAP;

        // 背包块：贴内容区底部（这就是「背包永远完整可见」的表达）。
        // ⚠️ invLabel 现在高度为 0（那行「背包（shift 点击…）」标签已按玩家要求删除），
        //    但它仍是**背包的 x/顶端锚点**，所以 invBlockH 必须把 INV_LABEL_H 算进去，
        //    否则整块会往下多出 12px、快捷栏越过内容区底边（实测抓出来的）。
        int invLabelY = content.bottom() - invBlockH - INV_LABEL_H;
        Rect invLabel = new Rect(content.x() + (content.w() - INV_COLS * SLOT) / 2, invLabelY,
                INV_COLS * SLOT, INV_LABEL_H);
        Rect inv = new Rect(invLabel.x(), invLabel.bottom(), INV_COLS * SLOT, INV_ROWS * SLOT);
        Rect hotbar = new Rect(inv.x(), inv.bottom() + 6, INV_COLS * SLOT, SLOT);

        // 明细：吃掉按钮与背包之间的**全部**空隙（整行一条），行数向上取整到放得下为止。
        // ⚠️ 玩家要求「中间的余量全部给回收明细列表（列表越高越好）」——
        //    这里只留 DETAIL_BOTTOM_GAP 作为与背包说明之间的呼吸位，其余全给明细。
        // ⚠️⚠️ **必须把终点夹在 DETAIL_BOTTOM_GAP 之内**：明细高度取了整行数之后，
        //    余下的零头如果不夹掉，明细矩形就会比「invLabel.top − GAP」多出几个像素、
        //    压到背包上，于是 `fits()` 判它不合格 → 整档被否 → 退到**没有明细**的档。
        //    表现就是「窗口只高了几像素，明细反而从 6 行掉到 0 行」（探针在 640 宽的
        //    304/318/334/348/362 这几档反复抓到）。夹掉之后只是少画一行，不会整档被否。
        // ⚠️⚠️ **终点要留出「不可见的标签高度」**：{@code invLabel} 现在是 0 内容但仍有 12px
        //    高度（它同时是背包的 x/顶端锚点），而背包本身从 {@code invLabel.bottom()} 才开始。
        //    原来只减 DETAIL_BOTTOM_GAP，明细矩形会比「背包顶 − GAP」多出几个像素、
        //    压到背包上 → `fits()` 判整档不合格 → 退到**没有明细**的档。
        //    表现就是「窗口只高了几像素，明细反而从 6 行掉到 0 行」（探针在 640 宽的
        //    304/318/334/348/362 这几档反复抓到）。多留 INV_LABEL_H 之后只是少画一行。
        int detailAvail = Math.max(0, invLabel.bottom() - DETAIL_BOTTOM_GAP - INV_LABEL_H - detailY);
        // ⚠️⚠️ 行数必须留出**上下内缩**：可用高度要先扣掉 DETAIL_TEXT_INSET × 2，
        //    再按整行数切。否则「框正好一行高」时文字会贴到上边框、下边框压到字上
        //    （480×270 那一档：框 13px、行 13px、文字只在上方留了 3px、下方 0px）。
        int textRoom = Math.max(0, detailAvail - DETAIL_TEXT_INSET * 2);
        int rows = keepDetail ? textRoom / DETAIL_LINE_H : 0;
        int detailH = rows > 0 ? rows * DETAIL_LINE_H + DETAIL_TEXT_INSET * 2 : 0;
        if (detailH > detailAvail) {
            detailH = 0;        // 挤不下就整块不画，别画一个压线的框
        }
        Rect barDetail = new Rect(content.x() + 4, detailY, content.w() - 8, detailH);
        return new RecyclePlan(hint, bar, barInfo, recycleBtn, barDetail, invLabel, inv, hotbar);
    }
    /** 按屏幕逻辑尺寸算一次布局。 */
    public static Layout compute(int screenW, int screenH) {
        int maxW = Math.min(PANEL_MAX_W, screenW - PANEL_SCREEN_MARGIN * 2);
        int maxH = Math.min(PANEL_MAX_H, screenH - PANEL_SCREEN_MARGIN * 2);
        int panelW = clamp((int) Math.round(screenW * PANEL_W_RATIO), Math.min(PANEL_MIN_W, maxW), maxW);
        // 下限用 MIN_PANEL_HEIGHT（由回收页的纵向排布反推，见常量注释）：
        // 面板比它更矮时回收按钮会挤进明细框。屏幕实在装不下就夹到 maxH，宁可裁掉底部也不重叠。
        int panelH = clamp((int) Math.round(screenH * PANEL_H_RATIO),
                Math.min(MIN_PANEL_HEIGHT, maxH), maxH);
        int px = (screenW - panelW) / 2;
        int py = (screenH - panelH) / 2;

        Rect panel = new Rect(px, py, panelW, panelH);
        Rect titleBar = new Rect(px, py, panelW, TITLE_H);
        Rect closeBtn = new Rect(px + panelW - CLOSE_X_PAD - CLOSE_SIZE, py + CLOSE_Y_PAD, CLOSE_SIZE, CLOSE_SIZE);
        // 帮助按钮在关闭按钮**左边**（同一行、同一尺寸、同一垂直内缩）
        Rect helpBtn = new Rect(closeBtn.x() - HELP_CLOSE_GAP - CLOSE_SIZE, closeBtn.y(),
                CLOSE_SIZE, CLOSE_SIZE);

        int tabW = clamp(panelW / 6, TAB_MIN_W, TAB_MAX_W);
        int tabsW = tabW * 3 + TAB_GAP * 2;
        int tabX0 = px + Math.max(CONTENT_PAD, (panelW - tabsW) / 2);
        int tabY = py + TITLE_H + TAB_ROW_GAP;
        Rect[] tabs = new Rect[3];
        for (int i = 0; i < 3; i++) {
            tabs[i] = new Rect(tabX0 + i * (tabW + TAB_GAP), tabY, tabW, TAB_H);
        }

        int contentY = tabY + TAB_H + CONTENT_GAP;
        Rect content = new Rect(px + CONTENT_PAD, contentY, panelW - CONTENT_PAD * 2,
                Math.max(40, py + panelH - CONTENT_BOTTOM_PAD - contentY));

        // ---------- 行情页：左列卡片 / 中间大屏 / 右列操作 ----------
        // ⚠️ 纵向起点一律用 contentTop()（= content.y() + FLASH_BAND_H）：
        //    内容区顶部那一条留给 flash 状态行，页面内容不许压上去（真机截图抓出来的）。
        int pageTop = content.y() + FLASH_BAND_H;
        int pageH = Math.max(40, content.h() - FLASH_BAND_H);
        Rect leftCol = new Rect(content.x(), pageTop, LEFT_COL_W, pageH);
        Rect[] cards = new Rect[3];
        for (int i = 0; i < 3; i++) {
            cards[i] = new Rect(leftCol.x(), leftCol.y() + i * (CARD_H + CARD_GAP), CARD_W, CARD_H);
        }
        // 大盘块：**卡片正下方**那一整块死区（左列高度是整页，三张卡只占 132px）。
        // 高度由「卡片下沿 → 左列下沿」算出，再按行高取整；一行都放不下就是 0 高
        // （indexLines() 返回 0，绘制端整块不画）。
        // ⚠️ 起点要**夹进左列**：窗口小到卡片已经贴到底边时，`cards[2].bottom() + GAP` 会落到
        //    左列外面 —— 那种「0 高矩形」虽然不会画出来，却破坏了
        //    「任何块的矩形都落在它的容器里」这条不变量（StockGuiLayoutTest 会当场抓住）。
        int indexY = Math.min(cards[2].bottom() + INDEX_TOP_GAP, leftCol.bottom());
        int indexRoom = Math.max(0, leftCol.bottom() - indexY);
        Rect indexBlock = new Rect(leftCol.x(), indexY, LEFT_COL_W,
                Math.min(INDEX_LINES * INDEX_LINE_H, indexRoom / INDEX_LINE_H * INDEX_LINE_H));
        Rect rightCol = new Rect(content.right() - RIGHT_COL_W, pageTop, RIGHT_COL_W, pageH);
        int chartX = leftCol.right() + COL_GAP;
        int chartW = Math.max(120, rightCol.x() - COL_GAP - chartX);
        // ---------- 中间列：走势图（上）+ 持仓表格（下） ----------
        //
        // ⚠️ 按玩家手绘图改版：持仓列表从右列搬到**走势图正下方**、做成横向表格；
        //   右列只留「买卖 + 数量/杠杆 + 信息块（占满剩余高度）」。
        //   图下原来那两行「持仓 N 股 · 均价 …」摘要**已删除**（表格信息更全，重复且占高度）。
        //
        // 空间优先级：买卖 > 数量/杠杆 > 信息块(≥ FUNDS_MIN_LINES 行)
        //            > 走势图(≥ CHART_MIN_H) > 持仓表格(≥ 表头 + 1 行，放不下整块不画)
        int tableH = clamp(pageH / 3, POS_TABLE_MIN_H, POS_TABLE_MAX_H);
        // 图区有富余时把表格加高（图区可让出的部分 = 超过 CHART_MIN_H 的那截，
        // 再留 CHART_SLACK 给图表，别把图压到临界值）
        int tableRoom = pageH - CHART_MIN_H - CHART_SLACK;
        if (tableRoom > POS_TABLE_MIN_H) {
            tableH = clamp(Math.max(tableH, tableRoom), POS_TABLE_MIN_H, POS_TABLE_MAX_H);
        }
        if (pageH - tableH < CHART_MIN_H) {
            tableH = Math.max(0, pageH - CHART_MIN_H);
        }
        // ⚠️ 玩家要求「把持仓框上端往上画点」：表格再加 POS_TABLE_LIFT，走势图相应变矮。
        //    加完如果图表守不住 CHART_MIN_H，就自动少抬（让出的高度 = 上面那步留下的余量），
        //    所以这里**不会**把图表压到下限以下，也不需要写死坐标。
        int liftRoom = Math.max(0, pageH - CHART_MIN_H - tableH);
        tableH += Math.min(POS_TABLE_LIFT, liftRoom);
        if (tableH < POS_TITLE_H + POS_ROW_H) {
            tableH = 0;     // 连表头 + 一行都放不下：整块不画，把高度全让给走势图
        }
        Rect posTable = new Rect(chartX, pageTop + pageH - tableH, chartW, tableH);
        // 图区下沿与表头上沿之间留 POS_GAP（自检里断言 posList.y() >= chartPanel.bottom() + POS_GAP）
        Rect chartPanel = new Rect(chartX, pageTop - FLASH_BAND_H, chartW,
                Math.max(CHART_MIN_H, pageH + FLASH_BAND_H - tableH - POS_GAP));
        // ⚠️ 图区内部只留 CHART_INNER_PAD（4px，够画圆角边框就行）；**不再额外让出 FLASH_BAND_H**
        //    （玩家要求「走势图框里的走势图占比增大」）。flash 提示那一条改成**纯叠加层**：
        //    pill 自己有半透明底衬，浮在图区上沿那一小块表头留白上，4 秒后消失，
        //    不为它牺牲柱高。表头/现价两行也一起收窄，把纵向余量全给绘图区。
        Rect padded = chartPanel.inset(CHART_INNER_PAD, CHART_INNER_PAD);
        // 表头行一分为二：左边「企业名 · 风险」、右边**新闻条**（横向滚动的 marquee）。
        // ⚠️ 两块是**不相交**的两个矩形（不是叠在一起画）：企业名那一段的宽度 = min(132, 行宽×45%)，
        //    剩下的全给新闻条，一直到图表右内边距 —— 也就是玩家画框的那一段
        //    （原来放「每根 10 秒 · 近 N 根」图例的位置，图例早删了、位置还在）。
        int headerNameW = Math.min(CHART_NAME_MAX_W, padded.w() * CHART_NAME_PCT / 100);
        Rect chartHeader = new Rect(padded.x(), padded.y(), headerNameW, CHART_ROW_H);
        Rect chartTicker = new Rect(padded.x() + headerNameW, padded.y(),
                Math.max(0, padded.w() - headerNameW), CHART_ROW_H);
        Rect chartPrice = new Rect(padded.x(), chartHeader.bottom(), padded.w(), CHART_ROW_H);
        int plotY = chartPrice.bottom() + CHART_HEADER_GAP;
        // 图下不再有持仓摘要两行 → 省下的高度全部给绘图区
        int plotH = Math.max(24, padded.bottom() - plotY);
        Rect chartPlot = new Rect(padded.x(), plotY, padded.w(), plotH);
        int candleCount = clamp(chartPlot.w() / CANDLE_PX, CANDLE_MIN, CANDLE_MAX);

        // 右列：买入 / 卖出 / 数量行 / 杠杆行 / 信息块（占满剩余高度）
        //
        // ⚠️ 右列**没有**全局「平仓」按钮（玩家原话：「平仓按钮移动到单个仓位后面啊」）——
        //    平仓只在**持仓表格每行末尾**。省下的 20px + 间距全部给了数量/杠杆行与信息块。
        Rect btnBuy = new Rect(rightCol.x(), rightCol.y(), rightCol.w(), BTN_MAIN_H);
        Rect btnSell = new Rect(rightCol.x(), btnBuy.bottom() + BTN_GAP, rightCol.w(), BTN_MAIN_H);

        int rowQtyY = btnSell.bottom() + BTN_GROUP_GAP;
        int rowLevY = rowQtyY + FIELD_ROW_H + BTN_GAP;
        Rect qtyLabel = new Rect(rightCol.x(), rowQtyY, FIELD_LABEL_W, FIELD_ROW_H);
        Rect levLabel = new Rect(rightCol.x(), rowLevY, FIELD_LABEL_W, FIELD_ROW_H);
        int fieldX = rightCol.x() + FIELD_LABEL_W + FIELD_INNER_GAP;
        Rect qtyField = new Rect(fieldX, rowQtyY, FIELD_W, FIELD_ROW_H);
        Rect levField = new Rect(fieldX, rowLevY, FIELD_W, FIELD_ROW_H);
        int upX = fieldX + FIELD_W + FIELD_INNER_GAP;
        Rect qtyUp = new Rect(upX, rowQtyY, FIELD_STEP_W, FIELD_ROW_H);
        Rect levUp = new Rect(upX, rowLevY, FIELD_STEP_W, FIELD_ROW_H);
        Rect qtyDown = new Rect(qtyUp.right(), rowQtyY, FIELD_STEP_W, FIELD_ROW_H);
        Rect levDown = new Rect(levUp.right(), rowLevY, FIELD_STEP_W, FIELD_ROW_H);
        Rect qtyMax = new Rect(qtyDown.right() + FIELD_INNER_GAP, rowQtyY, FIELD_MAX_W, FIELD_ROW_H);
        Rect levMax = new Rect(levDown.right() + FIELD_INNER_GAP, rowLevY, FIELD_MAX_W, FIELD_ROW_H);

        // 信息块：数量/杠杆下方一直到右列底部，**占满整条高度**（不再和持仓表格抢空间）
        int fundsY = rowLevY + FIELD_ROW_H + BTN_GROUP_GAP;
        Rect fundsBlock = new Rect(rightCol.x(), fundsY, rightCol.w(),
                Math.max(FUNDS_TITLE_H, rightCol.bottom() - fundsY));

        // 兼容别名：posList 现在指「图下那张表」（旧名字保留，两个文件不用一起大改）
        Rect posList = posTable;

        // ---------- 回收页 ----------
        //
        // 纵向顺序：说明 → 回收栏（左）/ 合计块 → 回收按钮 → 明细框（通栏） → 背包说明 → 背包 → 快捷栏。
        //
        // ⚠️ 三条保证（实机反馈反复定下来的，别改回去）：
        //   【保证 1】**回收栏 18 格与背包 36 格永远完整落在内容区内** —— 一个收东西、一个点回收，
        //            缺一不可。空间不足时优先把回收栏从 2 行压成 1 行（只显示前 9 格），
        //            **绝不把它挪出面板**（上一版「整块丢弃 + 整体上移」把它推到天上去了）。
        //   【保证 2】任何块的最终矩形都夹在 {@code content} 之内（最后统一 {@code clampToContent} 收口），
        //            不靠各块自己算对。
        //   【保证 3】画出来的块两两不重叠。
        //
        // 丢弃顺序（从最不重要开始）：① 顶部说明 → ② 明细框 → ③ 回收栏 2 行压 1 行。
        // 做法：把排布写成「给三个开关算一遍坐标」的纯函数，先试最全的方案，
        //      高度不够就按顺序关开关重算，最后统一夹取。
        // ⚠️ 背包说明那 12px **不再预留**（玩家要求删掉「背包（shift 点击 → 进回收栏）」那一行），
        //    背包直接贴内容区底部；省下的 12px 连同合并块那 13px 一起给回收明细列表。
        int invBlockH = INV_ROWS * SLOT + 6 + SLOT;                  // 背包 + 间隔 + 快捷栏
        int infoH = BAR_INFO_LINES * BAR_INFO_LINE_H + BAR_INFO_BTN_GAP;
        int barW = BAR_COLS * SLOT;
        int groupW = barW + 20 + BAR_INFO_W;
        int groupX = content.x() + Math.max(0, (content.w() - groupW) / 2);

        // ⚠️⚠️ **按优先级取"最好且装得下"的那一档**，不是「第一个装得下的」。
        //    原来用「第一个 fits 就 break」：某个高度下常规档差一点装不下、于是跳到降级档，
        //    而**降级档反而更高/更早把明细挤掉**，结果明细行数在相邻的窗口高度之间
        //    反复从 5 掉到 0（探针在 640 宽 304/318/334/348/362 这几档都抓到过）。
        //    现在遍历所有档、按下面的优先级挑一个 fits 的，高度一变只会让明细行数单调变化。
        //
        // 优先级（玩家口径，从高到低）：
        //   ① 明细列表 ≥1 行（玩家明确要求「余量全给明细」，能画就必须画）
        //   ② 合计块（信息） ③ 顶部说明行（已删，恒为 0）
        //   ④ 在都装得下的前提下：回收栏 2 行优于 1 行
        // {说明, 明细, 回收栏单行, 排法}
        RecyclePlan plan = null;
        int bestScore = Integer.MIN_VALUE;
        Object[][] options = {
                {true, true, false, RecycleMode.NORMAL},
                {false, true, false, RecycleMode.NORMAL},
                {false, true, true, RecycleMode.NORMAL},
                {false, true, true, RecycleMode.COMPACT},
                {false, true, true, RecycleMode.MERGED_INFO},
                {false, false, false, RecycleMode.NORMAL},
                {false, false, true, RecycleMode.COMPACT},
                {false, false, true, RecycleMode.MERGED_INFO},
        };
        for (Object[] option : options) {
            RecyclePlan candidate = planRecycle(content, (Boolean) option[0], (Boolean) option[1],
                    (Boolean) option[2], (RecycleMode) option[3],
                    invBlockH, infoH, barW, groupW, groupX);
            if (!candidate.fits(content)) {
                continue;
            }
            int score = 0;
            if (candidate.barDetail.h() > 0) {
                score += 100;                                   // ① 有明细
            }
            if (candidate.barInfo.w() > 0 && candidate.barInfo.h() > 0) {
                score += 10;                                    // ② 有合计块（没被折进按钮）
            }
            if (candidate.bar.h() == BAR_ROWS * SLOT) {
                score += 1;                                     // ④ 回收栏是完整的 2 行
            }
            if (score > bestScore) {
                bestScore = score;
                plan = candidate;
            }
        }
        if (plan == null) {
            // 连最省的一档都放不下（窗口小到离谱，例如逻辑 213×120）：仍然按这一档出图，
            // 由 clampToContent 收口 —— 宁可裁掉底部，也不给负坐标、不让块互相压。
            plan = planRecycle(content, false, false, true, RecycleMode.COMPACT,
                    invBlockH, infoH, barW, groupW, groupX);
        }

        Rect hint = clampToContent(content, plan.hint);
        Rect bar = clampToContent(content, plan.bar);
        Rect barInfo = clampToContent(content, plan.barInfo);
        Rect recycleBtn = clampToContent(content, plan.recycleBtn);
        Rect barDetail = clampToContent(content, plan.barDetail);
        Rect invLabel = clampToContent(content, plan.invLabel);
        Rect inv = clampToContent(content, plan.inv);
        Rect hotbar = clampToContent(content, plan.hotbar);

        // ---------- 购买页 ----------
        // ⚠️ 同样从 contentTop() 起：顶部那一条留给 flash 状态行
        int shopTop = content.y() + FLASH_BAND_H;
        Rect catBtn = new Rect(content.x(), shopTop, CAT_BTN_W, FILTER_H);
        Rect nextBtn = new Rect(content.right() - PAGE_BTN_W, shopTop, PAGE_BTN_W, FILTER_H);
        Rect pageText = new Rect(nextBtn.x() - PAGE_TEXT_W - 2, shopTop, PAGE_TEXT_W, FILTER_H);
        Rect prevBtn = new Rect(pageText.x() - PAGE_BTN_W - 2, shopTop, PAGE_BTN_W, FILTER_H);
        int searchW = Math.max(SEARCH_MIN_W, prevBtn.x() - 6 - (catBtn.right() + 6));
        Rect searchBox = new Rect(catBtn.right() + 6, shopTop, searchW, FILTER_H);

        // 购买页：创造模式物品栏那种布局 —— 上面网格点一下就拿，下面玩家背包
        int shopInvW = INV_COLS * SLOT;
        int shopInvH = INV_ROWS * SLOT;
        int shopInvBlockH = INV_LABEL_H + shopInvH + 6 + SLOT;
        int shopInvY = content.bottom() - shopInvBlockH;
        Rect shopInvLabel = new Rect(content.x() + (content.w() - shopInvW) / 2, shopInvY, shopInvW, INV_LABEL_H);
        Rect shopInv = new Rect(shopInvLabel.x(), shopInvLabel.bottom(), shopInvW, shopInvH);
        Rect shopHotbar = new Rect(shopInv.x(), shopInv.bottom() + 6, shopInvW, SLOT);
        // ⚠️ 网格下那条提示行**高度为 0**（玩家：「删掉这里的字」）—— 不留预留带，
        //    让网格一直吃到背包说明的正上方（原来这里让出 SHOP_HINT_H + 4 = 16px）。
        Rect shopHint = new Rect(content.x() + 4, shopInvLabel.y(), content.w() - 8, SHOP_HINT_MIN_H);

        int gridBottomLimit = shopHint.y() - 6;
        // ⚠️ 行数**吃掉分类行与背包之间的全部可用高度**（玩家：「商品网格要填满」「一屏能看到 6~7 行」）。
        //    上限 GRID_MAX_ROWS = 16 只是防「超长屏铺出上百行」；下限 1 行，放不下时不许叠到背包上。
        int gridCols = clamp(content.w() / SLOT, GRID_MIN_COLS, GRID_MAX_COLS);
        int fitRows = Math.max(1, (gridBottomLimit - (shopTop + FILTER_H + GRID_GAP_TOP)) / SLOT);
        int gridRows = clamp(fitRows, 1, GRID_MAX_ROWS);
        int gridW = gridCols * SLOT;
        int gridHExact = gridRows * SLOT;
        Rect grid = new Rect(content.x() + (content.w() - gridW) / 2, shopTop + FILTER_H + GRID_GAP_TOP, gridW, gridHExact);

        return new Layout(screenW, screenH, panel, titleBar, tabs, content, closeBtn, helpBtn,
                leftCol, cards, indexBlock, chartPanel, chartHeader, chartTicker, chartPrice, chartPlot,
                rightCol, btnBuy, btnSell, fundsBlock, posList, candleCount,
                qtyLabel, qtyField, qtyUp, qtyDown, qtyMax,
                levLabel, levField, levUp, levDown, levMax,
                hint, bar, barInfo, recycleBtn, barDetail, invLabel, inv, hotbar,
                catBtn, searchBox, prevBtn, pageText, nextBtn,
                grid, shopInvLabel, shopInv, shopHotbar, shopHint,
                gridCols, gridRows, gridCols * gridRows);
    }

    // ==================== 便捷取值（画的时候用） ====================

    /** 第 i 个页签。 */
    public static Rect tab(Layout l, int index) {
        return l.tabs()[index];
    }

    /** 第 i 张企业卡片。 */
    public static Rect card(Layout l, int index) {
        return l.cards()[index];
    }

    /**
     * 「屏幕外」的槽位坐标（原版靠把槽摆到画布外来隐藏它）。
     *
     * <p>与 {@code StockMenu.OFFSCREEN} 同一个值：全项目**只留这一个**常量，
     * 免得两边写不同的数、出现「以为藏起来了其实没藏」的槽。
     */
    public static final int OFFSCREEN = -10000;
    /** 屏幕外槽位矩形（回收栏折行时把第 2 行那 9 格藏掉，见 {@link #barRows}）。 */
    public static final Rect OFFSCREEN_RECT =
            new Rect(OFFSCREEN, OFFSCREEN, SLOT - 2, SLOT - 2);

    /**
     * 回收栏第 row 行、col 列的**格子边框**左上角。
     *
     * <p>⚠️ 折行时不可见的行返回屏幕外矩形（与 {@link #barSlot} 同一口径），
     * 免得有调用方按 {@code BAR_ROWS} 硬循环时把格子画到空白区上。
     */
    public static Rect barCell(Layout l, int col, int row) {
        if (row < 0 || row >= barRows(l)) {
            return OFFSCREEN_RECT;
        }
        return new Rect(l.bar().x() + col * SLOT, l.bar().y() + row * SLOT, SLOT, SLOT);
    }

    /**
     * 回收栏实际显示几行（1 或 2）。
     *
     * <p>⚠️⚠️ 折行必须**三处同步**：栏高（{@code bar} 矩形）、格子底纹（绘制）、
     * 以及 {@link #barSlot} 给出的**可见槽位**。以前只有栏高折了，底纹与 18 个槽位
     * 仍按 {@link #BAR_ROWS} = 2 铺 —— 第二行 9 格会落在**没有底纹的空白区**上，
     * 玩家能往那 9 个"看不见的格子"里塞东西（实机反馈「回收页又出 bug 了」）。
     * 现在统一用这个方法反推，谁都不用自己猜。
     */
    public static int barRows(Layout l) {
        int rows = l.bar().h() / SLOT;
        return clamp(rows, 0, BAR_ROWS);
    }

    /**
     * 回收栏第 index 格的**Slot 坐标**（= 格子边框 + 1，原版约定）。
     *
     * <p>⚠️ 超出**可见行数**的槽位一律摆到屏幕外：栏折成 1 行时，第 2 行那 9 格
     * 既要隐藏底纹、也不该再接收物品（三处同步，见 {@link #barRows}）。
     * 物品本身仍留在容器里，栏展回 2 行时原样出现。
     */
    public static Rect barSlot(Layout l, int index) {
        int col = index % BAR_COLS;
        int row = index / BAR_COLS;
        if (row >= barRows(l)) {
            return OFFSCREEN_RECT;
        }
        return new Rect(l.bar().x() + col * SLOT + 1, l.bar().y() + row * SLOT + 1, 16, 16);
    }

    /** 背包第 index 格（0..26）的 Slot 坐标。 */
    public static Rect invSlot(Layout l, int index) {
        int col = index % INV_COLS;
        int row = index / INV_COLS;
        return new Rect(l.inv().x() + col * SLOT + 1, l.inv().y() + row * SLOT + 1, 16, 16);
    }

    /** 快捷栏第 col 格的 Slot 坐标。 */
    public static Rect hotbarSlot(Layout l, int col) {
        return new Rect(l.hotbar().x() + col * SLOT + 1, l.hotbar().y() + 1, 16, 16);
    }

    /** 购买页第 col 列、row 行的**格子边框**左上角。 */
    public static Rect gridCell(Layout l, int col, int row) {
        return new Rect(l.grid().x() + col * SLOT, l.grid().y() + row * SLOT, SLOT, SLOT);
    }

    /** 购买页底部背包第 index 格（0..26）的 Slot 坐标。 */
    public static Rect shopInvSlot(Layout l, int index) {
        int col = index % INV_COLS;
        int row = index / INV_COLS;
        return new Rect(l.shopInv().x() + col * SLOT + 1, l.shopInv().y() + row * SLOT + 1, 16, 16);
    }

    /** 购买页底部快捷栏第 col 格的 Slot 坐标。 */
    public static Rect shopHotbarSlot(Layout l, int col) {
        return new Rect(l.shopHotbar().x() + col * SLOT + 1, l.shopHotbar().y() + 1, 16, 16);
    }

    // ==================== 走势图的两个互斥区 ====================

    /**
     * 走势图的**柱子区**（左）—— 阴阳柱、现价虚线、爆仓虚线**只能画在这里**。
     *
     * <p>与 {@link #chartAxis(Layout)} **零重叠**是硬约定：柱子右对齐之后，最新一根正好
     * 落在右侧价签的位置上，实机反馈「数字挡住柱子」。所以从 {@code chartPlot} 里切出
     * 右侧 {@link #CHART_AXIS_W} 作为轴区，两个区互不相交，谁都别越界。
     */
    public static Rect chartCandle(Layout l) {
        Rect plot = l.chartPlot();
        return new Rect(plot.x(), plot.y(),
                Math.max(1, plot.w() - Math.min(CHART_AXIS_W, plot.w() / 2)), plot.h());
    }

    /**
     * 走势图的**轴区**（右）—— 现价标签与三个价格刻度**只能画在这里**。
     *
     * <p>它自己带一条底衬（绘制端画），压在网格/柱子之上也不会有「数字和线糊在一起」的问题。
     * 与 {@link #chartCandle(Layout)} 相邻但不重叠。
     */
    public static Rect chartAxis(Layout l) {
        Rect plot = l.chartPlot();
        Rect candle = chartCandle(l);
        return new Rect(candle.right(), plot.y(), plot.right() - candle.right(), plot.h());
    }
}
