package com.qiushaonan.mcstock.client;

/**
 * 股市 GUI 的颜色常量 —— 美术与代码共用的一份调色板。
 *
 * <p>全部写成 8 位 {@code 0xAARRGGBB}，直接用 GuiGraphics 的 {@code fill} /
 * {@code drawString}。{@code tools/layout_dump.py} 会把这些值 dump 进 JSON，
 * 生成规格书与效果图，所以美术照着文档调色就能和代码对齐。
 *
 * <p>整体走「柔和」路线：底色比纯黑浅一档、描边比底色只深一点、涨跌色降饱和，
 * 避免高对比的硬边。想更硬朗就把 {@link #COLOR_PANEL_FILL} 调暗、{@link #COLOR_BORDER} 调亮。
 *
 * <p>⚠️ 涨跌配色按 **A 股习惯：红涨绿跌**（阴阳柱的同款约定）。
 * 想换成欧美习惯（绿涨红跌）只要把 {@link #COLOR_UP} 与 {@link #COLOR_DOWN}
 * 的两个值对调即可，代码没有别处写死颜色。
 */
public final class StockTheme {

    private StockTheme() {
    }

    /** 上涨 / 盈利（柔和的珊瑚红）。 */
    public static final int COLOR_UP = 0xFFE06A62;
    /** 下跌 / 亏损（柔和的草绿）。 */
    public static final int COLOR_DOWN = 0xFF6FBF7A;
    /** 次要文字（风险标签、单位、说明）。 */
    public static final int COLOR_MUTED = 0xFFA6ACBA;
    /** 主文字。 */
    public static final int COLOR_TEXT = 0xFFEDEFF5;
    /** 文字阴影：比底色深一点点就够，纯黑会显硬。 */
    public static final int COLOR_TEXT_SHADOW = 0xFF262932;
    /** 阴阳柱图的网格/基准线。 */
    public static final int COLOR_CHART_BASELINE = 0xFF6B7285;
    /** 爆仓价警示（红线与红字）。 */
    public static final int COLOR_LIQUIDATION = 0xFFF2706B;
    /** 强调色（选中卡片描边、页签下划线、金币）。 */
    public static final int COLOR_ACCENT = 0xFFE6C879;
    /** 面板底色（代码画大块背景时用；边框与圆角由贴图提供）。 */
    public static final int COLOR_PANEL_FILL = 0xFF23262E;
    /** 内嵌屏底色（阴阳柱区、资金情况块）。 */
    public static final int COLOR_INSET_FILL = 0xFF191B22;
    /** 内嵌屏网格线。 */
    public static final int COLOR_GRID_LINE = 0xFF2C313C;
    /** 面板/分区描边（代码画细线时用）。 */
    public static final int COLOR_BORDER = 0xFF474E60;
}
