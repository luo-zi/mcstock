package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.client.StockGuiLayout.Layout;
import com.qiushaonan.mcstock.client.StockGuiLayout.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行情页两块**新增矩形**的布局单测：
 * 走势图表头行右侧的**新闻条**（{@link Layout#chartTicker()}）与左列卡片下方的大盘块
 * （{@link Layout#indexBlock()}）。
 *
 * <p>{@code StockGuiLayout} 是纯 Java（不 import 任何 Minecraft 类），所以这里能在
 * 纯 JVM 里对**真实布局代码**跑尺寸矩阵 —— 玩家口径里的「不许与任何现有块重叠」
 * 就靠这些断言钉住，而不是靠肉眼看某一档截图。
 *
 * <p>⚠️ 只对**行情页自己的块**判重叠：三页共用同一个屏幕区域（购买页的网格本来就压在
 * 走势图的位置上），它们永远不会同时画出来，跨页判重叠没有意义。
 */
class StockGuiLayoutTest {

    /** 玩家口径里点名的两档，外加窄窗口与大屏（保证公式在所有档位都成立）。 */
    private static final int[][] SCREENS = {
            {640, 360}, {640, 340}, {854, 480}, {480, 270}, {427, 240}, {320, 200},
    };

    /** 行情页上**已有**的块（不含容器关系：chartPanel 本来就包着那四行，leftCol 包着卡片）。 */
    private static List<Rect> marketBlocks(Layout l) {
        List<Rect> blocks = new ArrayList<>();
        blocks.add(l.titleBar());
        blocks.add(l.closeBtn());
        blocks.add(l.helpBtn());
        for (Rect tab : l.tabs()) {
            blocks.add(tab);
        }
        blocks.add(l.leftCol());
        for (Rect card : l.cards()) {
            blocks.add(card);
        }
        blocks.add(l.chartPanel());
        blocks.add(l.chartHeader());
        blocks.add(l.chartPrice());
        blocks.add(l.chartPlot());
        blocks.add(l.rightCol());
        blocks.add(l.btnBuy());
        blocks.add(l.btnSell());
        blocks.add(l.fundsBlock());
        blocks.add(l.posList());
        return blocks;
    }

    /** 只属于**内容区**的那些块（标题栏 / 页签是面板外框的一部分，不参与「不出内容区」的判定）。 */
    private static List<Rect> contentBlocks(Layout l) {
        List<Rect> blocks = new ArrayList<>();
        blocks.add(l.leftCol());
        for (Rect card : l.cards()) {
            blocks.add(card);
        }
        blocks.add(l.indexBlock());
        blocks.add(l.chartPanel());
        blocks.add(l.chartTicker());
        blocks.add(l.chartPrice());
        blocks.add(l.chartPlot());
        blocks.add(l.rightCol());
        blocks.add(l.btnBuy());
        blocks.add(l.btnSell());
        blocks.add(l.fundsBlock());
        blocks.add(l.posList());
        return blocks;
    }

    private static boolean overlaps(Rect a, Rect b) {
        if (a.w() <= 0 || a.h() <= 0 || b.w() <= 0 || b.h() <= 0) {
            return false;
        }
        return !(a.right() <= b.x() || b.right() <= a.x() || a.bottom() <= b.y() || b.bottom() <= a.y());
    }

    private static boolean contains(Rect outer, Rect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    /** 新闻条：与「企业名」那一段严格不重叠，且不碰行情页上任何别的块。 */
    @Test
    void tickerNeverOverlapsAnything() {
        for (int[] screen : SCREENS) {
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            Rect ticker = l.chartTicker();
            String where = screen[0] + "x" + screen[1];
            if (ticker.w() <= 0) {
                continue;   // 极窄窗口下新闻条被挤成 0 宽：不画，也谈不上重叠
            }
            assertTrue(contains(l.chartPanel(), ticker), where + "：新闻条必须落在图表面板里");
            assertTrue(ticker.right() <= l.chartPanel().right() - StockGuiLayout.CHART_INNER_PAD,
                    where + "：新闻条右缘不能越过图表面板的内边距");
            assertFalse(overlaps(ticker, l.chartHeader()),
                    where + "：新闻条与企业名那一段必须严格分开（表头行一分为二）");
            for (Rect block : marketBlocks(l)) {
                if (block.equals(l.chartPanel())) {
                    continue;   // 图表面板是**父容器**（新闻条就画在它里面），不算重叠
                }
                assertFalse(overlaps(ticker, block),
                        where + "：新闻条与 " + block + " 重叠了");
            }
        }
    }

    /** 表头行被切成两块之后，**原来的整行范围一点没变**（既没挪窝也没留缝）。 */
    @Test
    void headerSplitKeepsTheOriginalRowExtent() {
        for (int[] screen : SCREENS) {
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            Rect name = l.chartHeader();
            Rect ticker = l.chartTicker();
            Rect padded = l.chartPanel().inset(StockGuiLayout.CHART_INNER_PAD, StockGuiLayout.CHART_INNER_PAD);
            assertEquals(padded.x(), name.x(), screen[0] + "x" + screen[1] + "：企业名那一段仍从内边距处开始");
            assertEquals(name.y(), ticker.y());
            assertEquals(name.h(), ticker.h());
            assertEquals(name.right(), ticker.x(), "两块必须首尾相接，不许留缝");
            assertEquals(padded.right(), ticker.right(), "新闻条一直延伸到图表右内边距");
            assertEquals(StockGuiLayout.CHART_ROW_H, name.h(), "表头行高不变");
            assertEquals(l.chartHeader().bottom(), l.chartPrice().y(), "现价行仍紧贴表头行下面");
        }
    }

    /** 新闻条宽度：常用档位要够读，窄窗口下也不能被挤没。 */
    @Test
    void tickerKeepsAUsableWidth() {
        Layout wide = StockGuiLayout.compute(640, 360);
        assertTrue(wide.chartTicker().w() >= 150,
                "640×360 下新闻条至少 150px（实测 " + wide.chartTicker().w() + "），否则滚动的字太少");
        for (int[] screen : SCREENS) {
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            assertTrue(l.chartTicker().w() > 0,
                    screen[0] + "x" + screen[1] + " 下新闻条被挤成 0 宽了");
            assertTrue(l.chartHeader().w() > 0, "企业名那一段也不能被挤成 0 宽");
        }
    }

    /** 大盘块：在卡片下方、不与任何块重叠，装不下时整块高度归零。 */
    @Test
    void indexBlockSitsUnderTheCardsWithoutOverlapping() {
        for (int[] screen : SCREENS) {
            String where = screen[0] + "x" + screen[1];
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            Rect block = l.indexBlock();
            assertTrue(contains(l.leftCol(), block), where + "：大盘块必须在左列里");
            assertTrue(block.y() >= l.cards()[2].bottom(), where + "：大盘块必须在三张卡片下面");
            for (Rect block2 : marketBlocks(l)) {
                if (block2.equals(l.leftCol())) {
                    continue;   // 左列本来就包着它
                }
                assertFalse(overlaps(block, block2), where + "：大盘块与 " + block2 + " 重叠了");
            }
            if (block.h() <= 0) {
                assertEquals(0, l.indexLines(), where + "：高度为 0 时必须一行都不画");
            } else {
                assertTrue(l.indexLines() >= 1, where + "：有高度就至少画一行");
                assertEquals(block.h() / StockGuiLayout.INDEX_LINE_H, l.indexLines());
                for (int i = 0; i < l.indexLines(); i++) {
                    Rect line = l.indexLine(i);
                    assertTrue(contains(block, line), where + "：第 " + i + " 行必须在块里");
                }
            }
        }
    }

    /** 640×360 / 640×340 这两档必须有足够的高度画满四行（指数 / 涨跌 / 状态都要看得见）。 */
    @Test
    void indexBlockIsFullyVisibleAtTheCommonResolutions() {
        for (int[] screen : new int[][]{{640, 360}, {640, 340}}) {
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            assertEquals(StockGuiLayout.INDEX_LINES, l.indexLines(),
                    screen[0] + "x" + screen[1] + " 下大盘块应该能画满 " + StockGuiLayout.INDEX_LINES + " 行");
            assertTrue(l.indexBlock().w() >= 60, "大盘块宽度要能放开「1032.4」这种数字");
        }
    }

    /** 行情页的所有块仍然都在内容区里（新块不能把别人顶出去）。 */
    @Test
    void marketBlocksStayInsideTheContentArea() {
        for (int[] screen : SCREENS) {
            String where = screen[0] + "x" + screen[1];
            Layout l = StockGuiLayout.compute(screen[0], screen[1]);
            Rect content = l.content();
            for (Rect block : contentBlocks(l)) {
                if (block.w() <= 0 || block.h() <= 0) {
                    continue;
                }
                assertTrue(block.x() >= content.x() - 1 && block.right() <= content.right() + 1,
                        where + "：" + block + " 横向越出内容区");
                assertTrue(block.y() >= content.y() - 1 && block.bottom() <= content.bottom() + 1,
                        where + "：" + block + " 纵向越出内容区");
            }
        }
    }
}
