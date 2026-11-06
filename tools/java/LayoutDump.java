import com.qiushaonan.mcstock.client.StockGuiLayout;
import com.qiushaonan.mcstock.client.StockGuiLayout.Layout;
import com.qiushaonan.mcstock.client.StockGuiLayout.Rect;
import com.qiushaonan.mcstock.client.StockTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 布局 dump 工具（不参与模组构建）。
 *
 * <p>{@code StockGuiLayout} 是纯 Java、不 import 任何 Minecraft 类，所以可以单独用 javac 编译，
 * 再由 {@code tools/layout_dump.py} 调用本类把 {@link StockGuiLayout#compute(int, int)} 的结果
 * 打成 JSON。生成规格文档 / 占位贴图 / 效果图的脚本都吃这份 JSON —— 布局公式只有 Java 那一份。
 *
 * <p>矩形统一输出成 {@code [x, y, w, h]} 数组。
 *
 * <p>用法：{@code java -cp tools/_layout_build LayoutDump 640 360}
 */
public final class LayoutDump {

    private LayoutDump() {
    }

    public static void main(String[] args) {
        int screenW = args.length > 0 ? Integer.parseInt(args[0]) : 640;
        int screenH = args.length > 1 ? Integer.parseInt(args[1]) : 360;
        Layout l = StockGuiLayout.compute(screenW, screenH);

        // ---------------- 常量 ----------------
        List<String> c = new ArrayList<>();
        add(c, "PANEL_W_RATIO", StockGuiLayout.PANEL_W_RATIO);
        add(c, "PANEL_H_RATIO", StockGuiLayout.PANEL_H_RATIO);
        add(c, "PANEL_MIN_W", StockGuiLayout.PANEL_MIN_W);
        add(c, "PANEL_MIN_H", StockGuiLayout.PANEL_MIN_H);
        // 实际生效的最小高度（由回收页纵向排布反推，见 StockGuiLayout.MIN_PANEL_HEIGHT）
        add(c, "MIN_PANEL_HEIGHT", StockGuiLayout.MIN_PANEL_HEIGHT);
        add(c, "MIN_CONTENT_HEIGHT", StockGuiLayout.MIN_CONTENT_HEIGHT);
        add(c, "BAR_TOP_GAP", StockGuiLayout.BAR_TOP_GAP);
        add(c, "BAR_INFO_LINES", StockGuiLayout.BAR_INFO_LINES);
        add(c, "BAR_INFO_LINE_H", StockGuiLayout.BAR_INFO_LINE_H);
        add(c, "BAR_INFO_BTN_GAP", StockGuiLayout.BAR_INFO_BTN_GAP);
        add(c, "BAR_INFO_BLOCK_H", StockGuiLayout.BAR_INFO_BLOCK_H);
        add(c, "RECYCLE_BTN_DETAIL_GAP", StockGuiLayout.RECYCLE_BTN_DETAIL_GAP);
        add(c, "DETAIL_MIN_LINES", StockGuiLayout.DETAIL_MIN_LINES);
        add(c, "DETAIL_BOTTOM_GAP", StockGuiLayout.DETAIL_BOTTOM_GAP);
        add(c, "CHART_AXIS_W", StockGuiLayout.CHART_AXIS_W);
        add(c, "CANDLE_RADIUS", StockGuiLayout.CANDLE_RADIUS);
        add(c, "PANEL_MAX_W", StockGuiLayout.PANEL_MAX_W);
        add(c, "PANEL_MAX_H", StockGuiLayout.PANEL_MAX_H);
        add(c, "PANEL_SCREEN_MARGIN", StockGuiLayout.PANEL_SCREEN_MARGIN);
        add(c, "TITLE_H", StockGuiLayout.TITLE_H);
        add(c, "TAB_H", StockGuiLayout.TAB_H);
        add(c, "TAB_MIN_W", StockGuiLayout.TAB_MIN_W);
        add(c, "TAB_MAX_W", StockGuiLayout.TAB_MAX_W);
        add(c, "TAB_GAP", StockGuiLayout.TAB_GAP);
        add(c, "TAB_ROW_GAP", StockGuiLayout.TAB_ROW_GAP);
        add(c, "CONTENT_GAP", StockGuiLayout.CONTENT_GAP);
        add(c, "CONTENT_PAD", StockGuiLayout.CONTENT_PAD);
        add(c, "CONTENT_BOTTOM_PAD", StockGuiLayout.CONTENT_BOTTOM_PAD);
        add(c, "SLOT", StockGuiLayout.SLOT);
        add(c, "CLOSE_SIZE", StockGuiLayout.CLOSE_SIZE);
        add(c, "LEFT_COL_W", StockGuiLayout.LEFT_COL_W);
        add(c, "RIGHT_COL_W", StockGuiLayout.RIGHT_COL_W);
        add(c, "COL_GAP", StockGuiLayout.COL_GAP);
        add(c, "CARD_W", StockGuiLayout.CARD_W);
        add(c, "CARD_H", StockGuiLayout.CARD_H);
        add(c, "CARD_GAP", StockGuiLayout.CARD_GAP);
        add(c, "CARD_ICON_OFF_X", StockGuiLayout.CARD_ICON_OFF_X);
        add(c, "CARD_ICON_OFF_Y", StockGuiLayout.CARD_ICON_OFF_Y);
        add(c, "CARD_NAME_OFF_X", StockGuiLayout.CARD_NAME_OFF_X);
        add(c, "CARD_NAME_OFF_Y", StockGuiLayout.CARD_NAME_OFF_Y);
        add(c, "CARD_ARROW_PAD_X", StockGuiLayout.CARD_ARROW_PAD_X);
        add(c, "CARD_ARROW_OFF_Y", StockGuiLayout.CARD_ARROW_OFF_Y);
        add(c, "CARD_ARROW_GAP", StockGuiLayout.CARD_ARROW_GAP);
        add(c, "CHART_PAD", StockGuiLayout.CHART_PAD);
        add(c, "CHART_INNER_PAD", StockGuiLayout.CHART_INNER_PAD);
        add(c, "CHART_ROW_H", StockGuiLayout.CHART_ROW_H);
        add(c, "CHART_NAME_MAX_W", StockGuiLayout.CHART_NAME_MAX_W);
        add(c, "CHART_NAME_PCT", StockGuiLayout.CHART_NAME_PCT);
        add(c, "NEWS_SCROLL_SPEED", StockGuiLayout.NEWS_SCROLL_SPEED);
        add(c, "INDEX_TOP_GAP", StockGuiLayout.INDEX_TOP_GAP);
        add(c, "INDEX_LINE_H", StockGuiLayout.INDEX_LINE_H);
        add(c, "INDEX_LINES", StockGuiLayout.INDEX_LINES);
        add(c, "CHART_HEADER_H", StockGuiLayout.CHART_HEADER_H);
        add(c, "CHART_FOOTER1_H", StockGuiLayout.CHART_FOOTER1_H);
        add(c, "CHART_FOOTER2_H", StockGuiLayout.CHART_FOOTER2_H);
        add(c, "CANDLE_PX", StockGuiLayout.CANDLE_PX);
        add(c, "CANDLE_MIN", StockGuiLayout.CANDLE_MIN);
        add(c, "CANDLE_MAX", StockGuiLayout.CANDLE_MAX);
        add(c, "CANDLE_TICKS", StockGuiLayout.CANDLE_TICKS);
        add(c, "PRICE_HISTORY_TICKS", StockGuiLayout.PRICE_HISTORY_TICKS);
        add(c, "BTN_MAIN_H", StockGuiLayout.BTN_MAIN_H);
        add(c, "BTN_CLOSE_H", StockGuiLayout.BTN_CLOSE_H);
        add(c, "FOOTER_TOP_GAP", StockGuiLayout.FOOTER_TOP_GAP);
        add(c, "FOOTER_GAP", StockGuiLayout.FOOTER_GAP);
        add(c, "HELP_LINE_H", StockGuiLayout.HELP_LINE_H);
        add(c, "HELP_LINES", StockGuiLayout.HELP_LINES);
        add(c, "FLASH_ROW_H", StockGuiLayout.FLASH_ROW_H);
        add(c, "HELP_CLOSE_GAP", StockGuiLayout.HELP_CLOSE_GAP);
        add(c, "BTN_GAP", StockGuiLayout.BTN_GAP);
        add(c, "BTN_GROUP_GAP", StockGuiLayout.BTN_GROUP_GAP);
        add(c, "FIELD_ROW_H", StockGuiLayout.FIELD_ROW_H);
        add(c, "FIELD_LABEL_W", StockGuiLayout.FIELD_LABEL_W);
        add(c, "FIELD_W", StockGuiLayout.FIELD_W);
        add(c, "FIELD_STEP_W", StockGuiLayout.FIELD_STEP_W);
        add(c, "FIELD_MAX_W", StockGuiLayout.FIELD_MAX_W);
        add(c, "FIELD_INNER_GAP", StockGuiLayout.FIELD_INNER_GAP);
        add(c, "FIELD_ROW_TOTAL_W", StockGuiLayout.FIELD_ROW_TOTAL_W);
        add(c, "QTY_MIN", StockGuiLayout.QTY_MIN);
        add(c, "QTY_MAX", StockGuiLayout.QTY_MAX);
        add(c, "LEVERAGE_MIN", StockGuiLayout.LEVERAGE_MIN);
        add(c, "LEVERAGE_MAX", StockGuiLayout.LEVERAGE_MAX);
        add(c, "FIELD_MAX_CHARS", StockGuiLayout.FIELD_MAX_CHARS);
        add(c, "FUNDS_TITLE_H", StockGuiLayout.FUNDS_TITLE_H);
        add(c, "FUNDS_LINE_H", StockGuiLayout.FUNDS_LINE_H);
        add(c, "FUNDS_LINES", StockGuiLayout.FUNDS_LINES);
        add(c, "FUNDS_MIN_LINES", StockGuiLayout.FUNDS_MIN_LINES);
        // 持仓列表（行情页右列底部）：行高是**两行文字**，中文常规字号 + 数字小号点阵
        add(c, "POS_TITLE_H", StockGuiLayout.POS_TITLE_H);
        add(c, "POS_ROW_H", StockGuiLayout.POS_ROW_H);
        add(c, "POS_LINE1_DY", StockGuiLayout.POS_LINE1_DY);
        add(c, "POS_LINE2_DY", StockGuiLayout.POS_LINE2_DY);
        add(c, "POS_CLOSE_W", StockGuiLayout.POS_CLOSE_W);
        add(c, "POS_CLOSE_H", StockGuiLayout.POS_CLOSE_H);
        add(c, "POS_CLOSE_PAD_X", StockGuiLayout.POS_CLOSE_PAD_X);
        add(c, "POS_CLOSE_GAP", StockGuiLayout.POS_CLOSE_GAP);
        add(c, "POS_TEXT_PAD_X", StockGuiLayout.POS_TEXT_PAD_X);
        add(c, "POS_PAGE_W", StockGuiLayout.POS_PAGE_W);
        add(c, "POS_MIN_ROWS", StockGuiLayout.POS_MIN_ROWS);
        add(c, "POS_MAX_ROWS", StockGuiLayout.POS_MAX_ROWS);
        add(c, "POS_GAP", StockGuiLayout.POS_GAP);
        add(c, "HINT_H", StockGuiLayout.HINT_H);
        add(c, "BAR_COLS", StockGuiLayout.BAR_COLS);
        add(c, "BAR_ROWS", StockGuiLayout.BAR_ROWS);
        add(c, "BAR_INFO_W", StockGuiLayout.BAR_INFO_W);
        add(c, "RECYCLE_BTN_W", StockGuiLayout.RECYCLE_BTN_W);
        add(c, "RECYCLE_BTN_H", StockGuiLayout.RECYCLE_BTN_H);
        add(c, "DETAIL_LINE_H", StockGuiLayout.DETAIL_LINE_H);
        add(c, "DETAIL_UNIT_W", StockGuiLayout.DETAIL_UNIT_W);
        add(c, "DETAIL_AMOUNT_W", StockGuiLayout.DETAIL_AMOUNT_W);
        add(c, "DETAIL_COL_GAP", StockGuiLayout.DETAIL_COL_GAP);
        add(c, "DETAIL_TEXT_INSET", StockGuiLayout.DETAIL_TEXT_INSET);
        add(c, "DETAIL_MIN_H", StockGuiLayout.DETAIL_MIN_H);
        add(c, "INV_COLS", StockGuiLayout.INV_COLS);
        add(c, "INV_ROWS", StockGuiLayout.INV_ROWS);
        add(c, "INV_LABEL_H", StockGuiLayout.INV_LABEL_H);
        add(c, "FILTER_H", StockGuiLayout.FILTER_H);
        add(c, "CAT_BTN_W", StockGuiLayout.CAT_BTN_W);
        add(c, "PAGE_BTN_W", StockGuiLayout.PAGE_BTN_W);
        add(c, "PAGE_TEXT_W", StockGuiLayout.PAGE_TEXT_W);
        add(c, "SHOP_HINT_H", StockGuiLayout.SHOP_HINT_H);
        add(c, "GRID_MIN_COLS", StockGuiLayout.GRID_MIN_COLS);
        add(c, "GRID_MAX_COLS", StockGuiLayout.GRID_MAX_COLS);
        add(c, "GRID_MIN_ROWS", StockGuiLayout.GRID_MIN_ROWS);
        add(c, "ATLAS_W", StockGuiLayout.ATLAS_W);
        add(c, "ATLAS_H", StockGuiLayout.ATLAS_H);
        add(c, "RADIUS_PANEL", StockGuiLayout.RADIUS_PANEL);
        add(c, "RADIUS_BOX", StockGuiLayout.RADIUS_BOX);
        add(c, "RADIUS_SLOT", StockGuiLayout.RADIUS_SLOT);
        // 条件单覆盖层
        add(c, "COND_BTN_W", StockGuiLayout.COND_BTN_W);
        add(c, "COND_PAD_X", StockGuiLayout.COND_PAD_X);
        add(c, "COND_PAD_Y", StockGuiLayout.COND_PAD_Y);
        add(c, "COND_TITLE_H", StockGuiLayout.COND_TITLE_H);
        add(c, "COND_INFO_H", StockGuiLayout.COND_INFO_H);
        add(c, "COND_FORM_H", StockGuiLayout.COND_FORM_H);
        add(c, "COND_HELP_H", StockGuiLayout.COND_HELP_H);
        add(c, "COND_GAP", StockGuiLayout.COND_GAP);
        add(c, "COND_ROW_H", StockGuiLayout.COND_ROW_H);
        add(c, "COND_CANCEL_W", StockGuiLayout.COND_CANCEL_W);
        add(c, "COND_MAX_VISIBLE_ROWS", StockGuiLayout.COND_MAX_VISIBLE_ROWS);

        // ---------------- 算出来的矩形 ----------------
        List<String> a = new ArrayList<>();
        add(a, "screenW", l.screenW());
        add(a, "screenH", l.screenH());
        rect(a, "panel", l.panel());
        rect(a, "titleBar", l.titleBar());
        rect(a, "closeBtn", l.closeBtn());
        rect(a, "helpBtn", l.helpBtn());
        rects(a, "tabs", l.tabs());
        rect(a, "content", l.content());
        rect(a, "leftCol", l.leftCol());
        rects(a, "cards", l.cards());
        rect(a, "indexBlock", l.indexBlock());
        add(a, "indexLines", l.indexLines());
        for (int i = 0; i < StockGuiLayout.INDEX_LINES; i++) {
            rect(a, "indexLine" + i, l.indexLine(i));
        }
        rect(a, "chartPanel", l.chartPanel());
        rect(a, "chartHeader", l.chartHeader());
        rect(a, "chartTicker", l.chartTicker());
        rect(a, "chartPrice", l.chartPrice());
        rect(a, "chartPlot", l.chartPlot());
        rect(a, "rightCol", l.rightCol());
        rect(a, "btnBuy", l.btnBuy());
        rect(a, "btnSell", l.btnSell());
        rect(a, "fundsBlock", l.fundsBlock());
        rect(a, "posList", l.posList());
        rect(a, "posScrollTrack", l.posScrollTrack());
        rect(a, "posRow0", l.posRow(0));
        rect(a, "posCloseBtn0", l.posCloseBtn(0));
        for (int col = 0; col < 6; col++) {
            rect(a, "posCol" + col, l.posCol(col, 0));
        }
        rect(a, "posHeader", l.posHeader());
        rect(a, "posPage", l.posPage());
        rect(a, "posRow0", l.posRow(0));
        rect(a, "posCloseBtn0", l.posCloseBtn(0));
        add(a, "posTextRight0", l.posTextRight(0));
        rect(a, "qtyLabel", l.qtyLabel());
        rect(a, "qtyField", l.qtyField());
        rect(a, "qtyUp", l.qtyUp());
        rect(a, "qtyDown", l.qtyDown());
        rect(a, "qtyMax", l.qtyMax());
        rect(a, "levLabel", l.levLabel());
        rect(a, "levField", l.levField());
        rect(a, "levUp", l.levUp());
        rect(a, "levDown", l.levDown());
        rect(a, "levMax", l.levMax());
        add(a, "candleCount", l.candleCount());
        rect(a, "hint", l.hint());
        rect(a, "bar", l.bar());
        rect(a, "barInfo", l.barInfo());
        rect(a, "recycleBtn", l.recycleBtn());
        rect(a, "barDetail", l.barDetail());
        // 明细一行的三列（0=物品×数量 / 1=单价 / 2=小计）
        rect(a, "detailName0", l.detailCol(0, 0));
        rect(a, "detailUnit0", l.detailCol(1, 0));
        rect(a, "detailAmount0", l.detailCol(2, 0));
        rect(a, "invLabel", l.invLabel());
        rect(a, "inv", l.inv());
        rect(a, "hotbar", l.hotbar());
        rect(a, "catBtn", l.catBtn());
        rect(a, "searchBox", l.searchBox());
        rect(a, "prevBtn", l.prevBtn());
        rect(a, "pageText", l.pageText());
        rect(a, "nextBtn", l.nextBtn());
        rect(a, "grid", l.grid());
        rect(a, "shopInvLabel", l.shopInvLabel());
        rect(a, "shopInv", l.shopInv());
        rect(a, "shopHotbar", l.shopHotbar());
        rect(a, "shopHint", l.shopHint());
        // 条件单覆盖层（浮层，与三页的块不共享空间）
        rect(a, "condBtn", l.condBtn());
        rect(a, "condPanel", l.condPanel());
        rect(a, "condTitle", l.condTitle());
        rect(a, "condInfo", l.condInfo());
        rect(a, "condTriggerField", l.condTriggerField());
        rect(a, "condDirBtn", l.condDirBtn());
        rect(a, "condActionBtn", l.condActionBtn());
        rect(a, "condPlaceBtn", l.condPlaceBtn());
        rect(a, "condHelp", l.condHelp());
        rect(a, "condList", l.condList());
        rect(a, "condRow0", l.condRow(0));
        rect(a, "condText0", l.condText(0));
        rect(a, "condCancelBtn0", l.condCancelBtn(0));
        add(a, "condVisibleRows", l.condVisibleRows());
        add(a, "gridCols", l.gridCols());
        add(a, "gridRows", l.gridRows());
        add(a, "pageSize", l.pageSize());
        add(a, "fundsLines", l.fundsLines());
        add(a, "posVisibleRows", l.posVisibleRows());
        add(a, "detailLines", l.detailLines());

        // ---------------- 图集分区 ----------------
        List<String> s = new ArrayList<>();
        sprite(s, "FRAME_TL", StockGuiLayout.FRAME_TL);
        sprite(s, "FRAME_TOP", StockGuiLayout.FRAME_TOP);
        sprite(s, "FRAME_TR", StockGuiLayout.FRAME_TR);
        sprite(s, "FRAME_LEFT", StockGuiLayout.FRAME_LEFT);
        sprite(s, "FRAME_CENTER", StockGuiLayout.FRAME_CENTER);
        sprite(s, "FRAME_RIGHT", StockGuiLayout.FRAME_RIGHT);
        sprite(s, "FRAME_BL", StockGuiLayout.FRAME_BL);
        sprite(s, "FRAME_BOTTOM", StockGuiLayout.FRAME_BOTTOM);
        sprite(s, "FRAME_BR", StockGuiLayout.FRAME_BR);
        sprite(s, "TITLE_TILE", StockGuiLayout.TITLE_TILE);
        sprite(s, "ICON_UP", StockGuiLayout.ICON_UP);
        sprite(s, "ICON_DOWN", StockGuiLayout.ICON_DOWN);
        sprite(s, "ICON_LEFT", StockGuiLayout.ICON_LEFT);
        sprite(s, "ICON_RIGHT", StockGuiLayout.ICON_RIGHT);
        sprite(s, "ICON_COIN", StockGuiLayout.ICON_COIN);
        sprite(s, "CLOSE_NORMAL", StockGuiLayout.CLOSE_NORMAL);
        sprite(s, "CLOSE_HOVER", StockGuiLayout.CLOSE_HOVER);
        // ⚠️ ICON_HELP 也要登记：以前它不在 SPR 里，gen_gui_textures.py 既不会画它、
        //    标注图也不会标它，只能靠一个一次性补丁脚本事后原地改 png ——
        //    而那种做法重跑生成器就被整片清掉（✕ 又变回一个圈）。
        //    登记之后生成器自己画，两边不会再脱节。
        sprite(s, "ICON_HELP", StockGuiLayout.ICON_HELP);
        sprite(s, "SLOT_TILE", StockGuiLayout.SLOT_TILE);
        sprite(s, "FIELD_TILE", StockGuiLayout.FIELD_TILE);
        sprite(s, "CARD_NORMAL", StockGuiLayout.CARD_NORMAL);
        sprite(s, "CARD_HOVER", StockGuiLayout.CARD_HOVER);
        sprite(s, "CARD_SELECTED", StockGuiLayout.CARD_SELECTED);
        sprite(s, "BTN_NORMAL", StockGuiLayout.BTN_NORMAL);
        sprite(s, "BTN_HOVER", StockGuiLayout.BTN_HOVER);
        sprite(s, "BTN_PRESSED", StockGuiLayout.BTN_PRESSED);
        sprite(s, "BTN_DISABLED", StockGuiLayout.BTN_DISABLED);
        sprite(s, "TAB_ACTIVE", StockGuiLayout.TAB_ACTIVE);
        sprite(s, "TAB_INACTIVE", StockGuiLayout.TAB_INACTIVE);
        sprite(s, "BTN_BUY_NORMAL", StockGuiLayout.BTN_BUY_NORMAL);
        sprite(s, "BTN_BUY_HOVER", StockGuiLayout.BTN_BUY_HOVER);
        sprite(s, "BTN_BUY_PRESSED", StockGuiLayout.BTN_BUY_PRESSED);
        sprite(s, "BTN_SELL_NORMAL", StockGuiLayout.BTN_SELL_NORMAL);
        sprite(s, "BTN_SELL_HOVER", StockGuiLayout.BTN_SELL_HOVER);
        sprite(s, "BTN_SELL_PRESSED", StockGuiLayout.BTN_SELL_PRESSED);

        // ---------------- 颜色（来自 StockTheme，美术与代码共用） ----------------
        List<String> col = new ArrayList<>();
        color(col, "COLOR_UP", StockTheme.COLOR_UP);
        color(col, "COLOR_DOWN", StockTheme.COLOR_DOWN);
        color(col, "COLOR_MUTED", StockTheme.COLOR_MUTED);
        color(col, "COLOR_TEXT", StockTheme.COLOR_TEXT);
        color(col, "COLOR_TEXT_SHADOW", StockTheme.COLOR_TEXT_SHADOW);
        color(col, "COLOR_CHART_BASELINE", StockTheme.COLOR_CHART_BASELINE);
        color(col, "COLOR_LIQUIDATION", StockTheme.COLOR_LIQUIDATION);
        color(col, "COLOR_ACCENT", StockTheme.COLOR_ACCENT);
        color(col, "COLOR_PANEL_FILL", StockTheme.COLOR_PANEL_FILL);
        color(col, "COLOR_INSET_FILL", StockTheme.COLOR_INSET_FILL);
        color(col, "COLOR_GRID_LINE", StockTheme.COLOR_GRID_LINE);
        color(col, "COLOR_BORDER", StockTheme.COLOR_BORDER);

        System.out.println("{\"const\":{" + String.join(",", c) + "},"
                + "\"layout\":{" + String.join(",", a) + "},"
                + "\"sprites\":{" + String.join(",", s) + "},"
                + "\"colors\":{" + String.join(",", col) + "}}");
    }

    private static void add(List<String> out, String key, Object value) {
        out.add("\"" + key + "\":" + value);
    }

    private static void rect(List<String> out, String key, Rect r) {
        out.add("\"" + key + "\":[" + r.x() + "," + r.y() + "," + r.w() + "," + r.h() + "]");
    }

    private static void rects(List<String> out, String key, Rect[] arr) {
        StringBuilder sb = new StringBuilder("\"").append(key).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(arr[i].x()).append(',').append(arr[i].y())
                    .append(',').append(arr[i].w()).append(',').append(arr[i].h()).append(']');
        }
        out.add(sb.append(']').toString());
    }

    private static void sprite(List<String> out, String key, StockGuiLayout.Sprite sp) {
        out.add("\"" + key + "\":[" + sp.u() + "," + sp.v() + "," + sp.w() + "," + sp.h() + "]");
    }

    /** 颜色写成字符串 "0xAARRGGBB"，方便 JSON 解析。 */
    private static void color(List<String> out, String key, int argb) {
        out.add("\"" + key + "\":\"0x" + String.format("%08X", Integer.toUnsignedLong(argb)) + "\"");
    }
}
