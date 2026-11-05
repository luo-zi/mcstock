package com.qiushaonan.mcstock.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * 股市 GUI 的**小控件与贴图拼装原语** —— {@link StockScreen} 的绘图与控件工具箱。
 *
 * <p>为什么单独一个文件：{@code StockScreen} 已经要管三个页面的排版，再塞进「9 段拉伸怎么切」
 * 和「按钮怎么换皮」就没法读了。这里是**无状态**的工具集合，唯一的事实来源仍然是
 * {@link StockGuiLayout}（矩形）与 {@link StockTheme}（颜色）。
 *
 * <h2>三条贯穿全文件的约定</h2>
 * <ol>
 *   <li><b>贴图切片一律按「目的尺寸 = 源尺寸」画</b>：{@code GuiGraphics#blit} 的 {@code uWidth/vHeight}
 *       是**取样大小**，跟目的宽高无关 —— 想缩放就必须显式传目的宽高。效果图脚本
 *       （{@code tools/gen_gui_mockup.py}）用的也是这一套规则，两边必须一致。</li>
 *   <li><b>按钮/页签/填写栏是「水平 9 段」</b>：左右各 {@link #SLICE_CAP}px 原样画、中间拉伸。
 *       两个 4px 端帽正好把 {@link StockGuiLayout#RADIUS_BOX} 的圆角包住，所以拉长不会把圆角拉花。
 *       竖直方向直接拉伸整段（按钮 16px 高、填写栏 20px 高，都靠这一步适配）。</li>
 *   <li><b>颜色只从 {@link StockTheme} 取</b>，不在这个文件里写字面量（AGENTS.md 4.5）。</li>
 * </ol>
 */
public final class StockWidgets {

    private StockWidgets() {
    }

    /** 贴图纸：256×256，分区定义在 {@link StockGuiLayout}。 */
    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("mcstock",
            "textures/gui/stock_market.png");

    /** 按钮/页签/填写栏左右**不拉伸**的端帽宽度（= 圆角半径 4，见 {@code gen_gui_textures}）。 */
    public static final int SLICE_CAP = StockGuiLayout.RADIUS_BOX;

    // ==================== 贴图拼装 ====================

    /** 按源尺寸 1:1 画一张切片。 */
    public static void blit(GuiGraphics g, StockGuiLayout.Sprite sprite, int x, int y) {
        blit(g, sprite, x, y, sprite.w(), sprite.h());
    }

    /** 把切片缩放到指定尺寸画（缩放用线性过滤，小幅缩放看不出模糊）。 */
    public static void blit(GuiGraphics g, StockGuiLayout.Sprite sprite, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.blit(ATLAS, x, y, w, h, sprite.u(), sprite.v(), sprite.w(), sprite.h(),
                StockGuiLayout.ATLAS_W, StockGuiLayout.ATLAS_H);
    }

    /**
     * 水平 9 段拉伸：左右各 {@link #SLICE_CAP}px 原样、中间拉伸到撑满，**竖直方向整体拉伸**。
     *
     * <p>⚠️ 采样区域必须传**切片自己的** {@code sprite.w()/h()}，绝不能用目标宽高 ——
     * {@code GuiGraphics#blit} 的 {@code uWidth/vHeight} 是「从贴图上取多大一块」，
     * 它把这 16 行**拉伸**到目标高度。一旦误把目标高度（比如卡片 44）当采样高度传进去，
     * 就会顺着贴图往下多取 28 行，把下面的 {@code BTN_HOVER} / {@code BTN_PRESSED} 各取一段
     * 一起画进同一个元素里 —— 实机上就是「一个按钮里叠了三段颜色、边界正好落在 1/3 与 2/3 高度」。
     *
     * <p>适合「高度正好等于切片高度」的元素（页签 16、填写栏 20）。
     * 高度不等于切片高度时请用 {@link #blitSlicedV}，免得 1px 描边被拉糊。
     */
    public static void blitSliced(GuiGraphics g, StockGuiLayout.Sprite sprite, int x, int y, int w, int h) {
        int cap = SLICE_CAP;
        int sw = sprite.w();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (sw <= cap * 2 || w <= cap * 2) {
            blit(g, sprite, x, y, w, h);
            return;
        }
        int midW = w - cap * 2;
        // 左端帽 / 右端帽：源 4px 宽 → 目的 4px 宽（1:1，圆角完好）；竖直方向拉伸到 h
        blit(g, sprite, 0, 0, cap, sprite.h(), x, y, cap, h);
        // 中间：源 (sw - 8)px → 目的 midW
        blit(g, sprite, cap, 0, sw - cap * 2, sprite.h(), x + cap, y, midW, h);
        // 右端帽
        blit(g, sprite, sw - cap, 0, cap, sprite.h(), x + w - cap, y, cap, h);
    }

    /**
     * 横向 + 纵向都是 9 段：左右各 {@code SLICE_CAP}px、上下各 {@code SLICE_CAP}px **都不拉伸**，
     * 只有正中间那一块被拉伸。
     *
     * <p>给「高度 ≠ 切片高度」的元素用（卡片 44、买入/卖出 30、▲▼/MAX 20、分类/翻页 18）：
     * 只做纵向拉伸会把 1px 描边拉成 2~3px 的糊边，这里把上下两条边按原样画、中间拉长即可。
     *
     * <p>目标高度 ≤ 8px 时两端的 4px 端帽在目标里装不下，退回单轴版本 {@link #blitSliced}。
     */
    public static void blitSlicedV(GuiGraphics g, StockGuiLayout.Sprite sprite, int x, int y, int w, int h) {
        int cap = SLICE_CAP;
        int sw = sprite.w();
        int sh = sprite.h();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (h <= cap * 2 || sh <= cap * 2) {
            blitSliced(g, sprite, x, y, w, h);
            return;
        }
        int midH = h - cap * 2;
        if (sw <= cap * 2 || w <= cap * 2) {
            // 太窄：水平方向切不了三段，退化成「竖直 3 段」
            blit(g, sprite, 0, 0, sw, cap, x, y, w, cap);
            blit(g, sprite, 0, cap, sw, sh - cap * 2, x, y + cap, w, midH);
            blit(g, sprite, 0, sh - cap, sw, cap, x, y + h - cap, w, cap);
            return;
        }
        int midW = w - cap * 2;
        // 四角 1:1
        blit(g, sprite, 0, 0, cap, cap, x, y, cap, cap);
        blit(g, sprite, sw - cap, 0, cap, cap, x + w - cap, y, cap, cap);
        blit(g, sprite, 0, sh - cap, cap, cap, x, y + h - cap, cap, cap);
        blit(g, sprite, sw - cap, sh - cap, cap, cap, x + w - cap, y + h - cap, cap, cap);
        // 上下两条边：横向拉伸、纵向 1:1（1px 描边因此不会被拉糊）
        blit(g, sprite, cap, 0, sw - cap * 2, cap, x + cap, y, midW, cap);
        blit(g, sprite, cap, sh - cap, sw - cap * 2, cap, x + cap, y + h - cap, midW, cap);
        // 左右两条边：纵向拉伸、横向 1:1
        blit(g, sprite, 0, cap, cap, sh - cap * 2, x, y + cap, cap, midH);
        blit(g, sprite, sw - cap, cap, cap, sh - cap * 2, x + w - cap, y + cap, cap, midH);
        // 正中间：两个方向都拉伸
        blit(g, sprite, cap, cap, sw - cap * 2, sh - cap * 2, x + cap, y + cap, midW, midH);
    }

    /** 带源偏移的切片 blit（9 段的中间段/端帽用），源尺寸 = 目的尺寸时就是纯粹的「取样一块」。 */
    private static void blit(GuiGraphics g, StockGuiLayout.Sprite sprite,
                             int srcOffX, int srcOffY, int srcW, int srcH,
                             int x, int y, int w, int h) {
        g.blit(ATLAS, x, y, w, h, sprite.u() + srcOffX, sprite.v() + srcOffY, srcW, srcH,
                StockGuiLayout.ATLAS_W, StockGuiLayout.ATLAS_H);
    }

    /**
     * 面板边框：内部圆角填充 + 四角 1:1 + 四边拉伸。
     *
     * <p>和 {@code tools/gen_gui_mockup.py#panel_frame} 是同一套画法。{@code fill} 只能画方角，
     * 所以**圆角是贴图里的透明像素**：代码只负责把 8×8 的四角摆在四个角上。
     */
    public static void panelFrame(GuiGraphics g, StockGuiLayout.Rect rect) {
        int x = rect.x();
        int y = rect.y();
        int w = rect.w();
        int h = rect.h();
        int corner = StockGuiLayout.FRAME_TL.w();   // 8
        roundedFill(g, rect, StockGuiLayout.RADIUS_PANEL, StockTheme.COLOR_PANEL_FILL);
        blit(g, StockGuiLayout.FRAME_TL, x, y, corner, corner);
        blit(g, StockGuiLayout.FRAME_TR, x + w - corner, y, corner, corner);
        blit(g, StockGuiLayout.FRAME_BL, x, y + h - corner, corner, corner);
        blit(g, StockGuiLayout.FRAME_BR, x + w - corner, y + h - corner, corner, corner);
        blit(g, StockGuiLayout.FRAME_TOP, x + corner, y, w - corner * 2, corner);
        blit(g, StockGuiLayout.FRAME_BOTTOM, x + corner, y + h - corner, w - corner * 2, corner);
        blit(g, StockGuiLayout.FRAME_LEFT, x, y + corner, corner, h - corner * 2);
        blit(g, StockGuiLayout.FRAME_RIGHT, x + w - corner, y + corner, corner, h - corner * 2);
    }

    /**
     * 用纯色画一个**圆角**矩形（4 个角各挖掉一小块）。
     *
     * <p>存在的理由：{@link GuiGraphics#fill} 只有方角。内嵌屏（阴阳柱区 / 资金情况块 / 回收明细）
     * 是半透明的深色底，叠在面板底纹上 —— 直接用贴图会把面板底纹一起盖掉，所以角上用
     * {@link GuiGraphics#fill} 逐行内缩补一个小三角，观感与贴图里的圆角一致。
     */
    public static void roundedFill(GuiGraphics g, StockGuiLayout.Rect rect, int radius, int argb) {
        int x = rect.x();
        int y = rect.y();
        int w = rect.w();
        int h = rect.h();
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, argb);
        for (int i = 0; i < r; i++) {
            // 从圆角圆心出发，按半径算这一行该内缩多少像素（整数近似：i 行内缩 r - round(sqrt(r² - (r-1-i)²)))
            int dy = r - 1 - i;
            int inset = r - (int) Math.round(Math.sqrt((double) (r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, argb);
        }
    }

    /**
     * 内嵌屏底：圆角纯色底 + 1px 描边（效果图里的「深色内嵌屏」）。
     *
     * <p>描边是四条 1px 的直角细线，只有四个角是圆的 —— 和效果图一致（那边是
     * {@code ImageDraw.rounded_rectangle} 的 outline，小尺寸下肉眼看不出差别）。
     */
    public static void insetBox(GuiGraphics g, StockGuiLayout.Rect rect, int fill, int border) {
        roundedFill(g, rect, StockGuiLayout.RADIUS_BOX, fill);
        int x = rect.x();
        int y = rect.y();
        int w = rect.w();
        int h = rect.h();
        if (w <= 1 || h <= 1) {
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + 1, border);                 // 上
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);         // 下
        g.fill(x, y + 1, x + 1, y + h - 1, border);                 // 左
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);         // 右
    }

    /** 画一行槽位边框（{@code SLOT_TILE} 按格子 1:1 铺）。 */
    public static void slotGrid(GuiGraphics g, int x, int y, int cols, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                blit(g, StockGuiLayout.SLOT_TILE,
                        x + col * StockGuiLayout.SLOT, y + row * StockGuiLayout.SLOT);
            }
        }
    }

    /**
     * 把一个矩形画成**圆角**，圆角处用 {@code cornerColor} 补（而不是挖成透明）。
     *
     * <p>为什么不复用 {@link #roundedFill}：那个用 {@code fill(..., 0)} 把四角擦成透明，
     * 只适合「底下什么都没有」的场景。阴阳柱的实体是画在**内嵌屏 + 网格线**之上的，
     * 擦成透明会把网格线一起擦出四个洞；这里改成用「它周围的底色」重画那 4 个角，
     * 观感是和别处一致的柔和圆角。
     *
     * <p>半径会自动收：实体最矮只有 1px、最窄只有 5px 时圆角会把整根柱子吃掉，
     * 所以 {@code radius} 被夹到 {@code min(w, h) / 3} 以内，太小时直接退化成方角。
     */
    public static void fillSoft(GuiGraphics g, StockGuiLayout.Rect rect, int radius,
                                int color, int cornerColor) {
        int x = rect.x();
        int y = rect.y();
        int w = rect.w();
        int h = rect.h();
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 3));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);      // 太矮 / 太窄：方角
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int dy = r - 1 - i;
            int inset = r - (int) Math.round(Math.sqrt((double) (r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
        // 四角被内缩让出来的那一小块，用周围底色补回去（半径 1~2 时就是 4 个像素）
        g.fill(x, y, x + r, y + 1, cornerColor);
        g.fill(x + w - r, y, x + w, y + 1, cornerColor);
        g.fill(x, y + h - 1, x + r, y + h, cornerColor);
        g.fill(x + w - r, y + h - 1, x + w, y + h, cornerColor);
    }

    /**
     * 企业卡片：**按状态只画一张**切片（三态互斥）。
     *
     * <p>⚠️ 「三态互斥」是刻意的：卡片只有一张底图，selected / hovered / normal 三者按优先级
     * 取**一个**。如果哪天有人顺手把三张都画上（或者画完 normal 又叠 hover），
     * 一家企业看上去就会变成两三个错位的格子 —— 这是实机反馈过的观感问题，
     * 所以这里把选择逻辑收在一个方法里，别在调用处散开写。
     *
     * <p>三张切片的尺寸来自 {@link StockGuiLayout#CARD_NORMAL} 等（当前 56×44），
     * 但**画的时候用传入的矩形尺寸** —— 以后布局再改卡片宽度（比如又收窄成 48），
     * 只要图集里那三张切片的 u 位置跟着改，这里不用动。
     */
    public static void card(GuiGraphics g, StockGuiLayout.Rect rect, boolean selected, boolean hovered) {
        StockGuiLayout.Sprite sprite = selected ? StockGuiLayout.CARD_SELECTED
                : (hovered ? StockGuiLayout.CARD_HOVER : StockGuiLayout.CARD_NORMAL);
        // 卡片切片 56×44 与目标同尺寸，这里仍走「横竖都 9 段」：
        // 以后卡片高度改了（布局常量一调就会变）也不会把 1px 描边拉糊
        blitSlicedV(g, sprite, rect.x(), rect.y(), rect.w(), rect.h());
    }

    // ==================== 控件 ====================

    /**
     * 用某个矩形的位置和尺寸造一个**没有贴图**的按钮（贴图/文字由本类自己的子类画）。
     *
     * <p>用 {@link Button.Builder} 而不是自己 new：{@code Button} 的构造器是 {@code protected}，
     * 而且 Builder 顺手把「宽度高度」这套样板收掉了。按钮的默认外观会被
     * {@link StockButton#renderWidget} 整个覆盖掉，所以不会看到原版的灰色贴图。
     */
    public static Button button(StockGuiLayout.Rect rect, Component label, Runnable onPress) {
        return Button.builder(label, b -> onPress.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(StockButton::new);
    }

    /**
     * 指定**皮肤组**的按钮（买入=红 / 卖出=绿，见 {@link StockGuiLayout.Skin}）。
     *
     * <p>⚠️ 皮肤是**图集里画好的切片**，不是 {@code g.setColor} 染出来的：
     * 染色是乘法，灰底 (58,63,76) 乘红色会把蓝分量一起留下、整块发浑。
     * 所以红/绿底与描边由 {@code tools/gen_gui_textures.py} 直接生成，
     * 这里只负责按状态挑切片（四态齐全 —— 少了 PRESSED，按下去会突然变回灰色）。
     */
    public static Button button(StockGuiLayout.Rect rect, Component label,
                                StockGuiLayout.Skin skin, Runnable onPress) {
        return Button.builder(label, b -> onPress.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(b -> new StockButton(b, null, null, true, false, skin));
    }

    /**
     * **没有底图**的按钮：只负责命中判定，外观完全由别处画。
     *
     * <p>给「自己已经有底图」的元素用（企业卡片：底图是 {@code CARD_*} 那一片）。
     * ⚠️ 这类按钮**不能再画 {@code BTN_*} 皮肤**：卡片上叠一层按钮皮肤，实机看上去就是
     * 「卡片框里又套了一个（还被纵向拉伸切开的）按钮」—— 明确反馈过的观感问题。
     */
    public static Button bareButton(StockGuiLayout.Rect rect, Component label, Runnable onPress) {
        return Button.builder(label, b -> onPress.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(b -> new StockButton(b, null, false));
    }

    /**
     * 上一页 / 下一页那种带箭头的按钮：**只画箭头图案，不画文字**。
     *
     * <p>⚠️ 标签必须传 {@link Component#empty()}：{@code StockButton} 的画法是「有图案画图案、
     * 否则画 {@code getMessage()} 的文字」，把「上一页」这种提示当成 message 传进去，
     * 结果就是箭头和文字叠在一起。提示改走 tooltip。
     */
    public static Button iconButton(StockGuiLayout.Rect rect, StockGuiLayout.Sprite icon,
                                    Component tooltip, Runnable onPress) {
        Button built = Button.builder(Component.empty(), b -> onPress.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(b -> new StockButton(b, icon, true));
        built.setTooltip(Tooltip.create(tooltip));
        return built;
    }

    /**
     * 页签按钮：**带选中态**（活动页用 {@code TAB_ACTIVE}，其余用 {@code TAB_INACTIVE}）。
     *
     * <p>⚠️⚠️ 为什么页签底必须由**按钮自己**画：以前是 {@code StockScreen#drawChrome}
     * （属于 {@code renderBg}）画 {@code TAB_ACTIVE/TAB_INACTIVE}，而页签是带皮肤的
     * {@link StockButton} —— 皮肤在 {@code super.render} 阶段绘制、**晚于** renderBg，
     * 而 {@code BTN_NORMAL/HOVER} 实测中心 alpha = **255（完全不透明）**，
     * 于是三个页签看上去一模一样，玩家根本看不出自己在哪一页（可用性问题）。
     * 现在「皮肤」本身就按选中态选切片，顺序天然正确、也不再多画一层。
     */
    public static Button tabButton(StockGuiLayout.Rect rect, Component label, boolean selected,
                                   Runnable onPress) {
        return Button.builder(label, b -> onPress.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(b -> new StockButton(b, null, null, true, selected));
    }

    /**
     * 关闭按钮：12×12，自己画 {@code CLOSE_*} 那两态**叉形图标**，**不画任何文字**。
     *
     * <p>⚠️⚠️ **叉必须由按钮自己画，而且必须画在皮肤之后。**
     * 以前是「控件负责画皮肤底、{@code StockScreen#drawChrome} 负责画叉」，而
     * {@code AbstractContainerScreen#render} 的顺序是
     * {@code renderBg → renderSlots → renderLabels → super.render(控件)} ——
     * {@code drawChrome} 在 {@code renderBg} 里，于是它画的叉**被随后绘制的按钮皮肤盖掉**，
     * 实机上就只剩一个深色圆环（玩家反馈「关闭处只有一个圈，没有叉」）。
     * 现在走 {@link StockButton} 的 {@code icon} 通路：皮肤先画、图标后画（见 renderWidget），
     * 顺序天然正确，不依赖任何外部绘制。
     */
    public static Button closeButton(StockGuiLayout.Rect rect, Runnable onClose) {
        Button built = Button.builder(Component.empty(), b -> onClose.run())
                .bounds(rect.x(), rect.y(), rect.w(), rect.h())
                .build(b -> new StockButton(b, StockGuiLayout.CLOSE_NORMAL, StockGuiLayout.CLOSE_HOVER, true));
        built.setTooltip(Tooltip.create(Component.translatable("gui.mcstock.close")));
        return built;
    }

    /**
     * 一个数字填写栏：圆角底由 {@link #fieldBox} 画，控件本身 {@code setBordered(false)}，
     * 正好坐在那片圆角上。
     *
     * <p>数字用 filter 挡掉：EditBox 的 filter 是「拒绝这次输入」而不是
     * 「事后清理」，所以粘贴 {@code "12a"} 会整段被拒，不会出现「先写进去再删掉」的闪动。
     * （空串放行，玩家才能把内容全删光重打。）
     */
    /** 填写栏里 EditBox 相对底衬的左右内缩（圆角底是 9 段贴图，文字离左边框留一点才不贴边）。 */
    public static final int FIELD_TEXT_PAD_X = 6;

    /**
     * 填写栏里 EditBox 相对底衬的**上下内缩** —— 由行高与字体行高算出来，和
     * {@link #centeredY} 同一口径。
     *
     * <p>为什么必须有这一段：{@code setBordered(false)} 时原版把文字画在
     * {@code (getX(), getY())}（源码：{@code int j = bordered ? getX() + 4 : getX();
     * int k = bordered ? getY() + (height - 8) / 2 : getY();}），
     * 所以**传进去的矩形上沿就是文字顶端**。
     *
     * <p>⚠️ 用 {@code (rect.h() - font.lineHeight) / 2} 而不是写死常数：
     * 20px 的数量/杠杆栏 → 5；18px 的搜索框 → 4。
     * 像素实测过 —— 之前写死 2 的时候，数字墨迹比整行中线高 4.5px、
     * 比同一行的中文标签高 3px（特写图里一眼可见）。
     */
    public static int fieldTextPadY(Font font, StockGuiLayout.Rect rect) {
        return Math.max(0, (rect.h() - font.lineHeight) / 2);
    }

    /**
     * 一个数字填写栏：圆角底由 {@link #fieldBox} 画，控件本身 {@code setBordered(false)}，
     * 正好坐在那片圆角上。
     *
     * <p>⚠️ <b>必须把传给 EditBox 的矩形往里内缩</b>（实机反馈「数字贴在输入框左上角」）：
     * {@code setBordered(false)} 之后原版把 {@code textX} 从 {@code x + 4} 改成 {@code x}、
     * 纵向又是按 **8px 行高**算的 {@code (h - 8) / 2}，而中文 Unifont 的
     * {@link Font#lineHeight} 是 9~10 —— 于是文字同时偏左、偏上。
     * 与其子类化去改 {@code renderWidget}（原版那儿是 private 字段算出来的），
     * 不如**直接把框内缩**：文字与光标就落在我们给的矩形里，
     * 而视觉上的「框」是我们的 {@code FIELD_TILE} 底衬，两者解耦最稳。
     *
     * <p>内缩量见 {@link #FIELD_TEXT_PAD_X} / {@link #fieldTextPadY}。
     * 以数量栏（{@code FIELD_W × FIELD_ROW_H} = 28×20）为例，最终传给 EditBox 的是
     * {@code (x + 6, y + 5, 18, 10)}，而 {@code setBordered(false)} 下文字就画在
     * {@code (x + 6, y + 5)} —— 文字左边距 6px，纵向占 {@code y+5 .. y+14}，
     * 20px 行的视觉中线是 y+10，与同一行中文标签（{@code centeredY} = y+5）完全一致。
     *
     * <p>数字用 filter 挡掉：EditBox 的 filter 是「拒绝这次输入」而不是
     * 「事后清理」，所以粘贴 {@code "12a"} 会整段被拒，不会出现「先写进去再删掉」的闪动。
     * （空串放行，玩家才能把内容全删光重打。）
     */
    public static EditBox numberField(Font font, StockGuiLayout.Rect rect, int maxChars, Component narration) {
        int padY = fieldTextPadY(font, rect);
        // ⚠️ 值是**小号点阵字体**画的（见 {@link NarrowTextField}）：常规字体 6 位要 35px，
        //    而右列整行只挤得出 32px 内宽（标签 16 + 框 42 + ▲▼12+12 + MAX18 + 3×2 = 100 正好满），
        //    常规字体下 `100000` 必被裁（玩家实测「输入 1000 只显示 000」就是这个）。
        //    小号字体每位 4px：6 位 = 24px，稳稳放得下。
        EditBox box = new NarrowTextField(font,
                rect.x() + FIELD_TEXT_PAD_X, rect.y() + padY,
                Math.max(8, rect.w() - FIELD_TEXT_PAD_X - 4),
                Math.max(8, rect.h() - padY * 2), narration);
        box.setBordered(false);
        box.setMaxLength(maxChars);
        box.setTextColor(StockTheme.COLOR_TEXT);
        box.setTextColorUneditable(StockTheme.COLOR_MUTED);
        // 空串放行（玩家全删光再重打），其余必须是纯数字 —— 解析失败时保留上一次的有效值
        box.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        return box;
    }

    /**
     * **文本**填写栏（搜索框）：常规字体、可收任意字符。
     *
     * <p>⚠️⚠️ 为什么必须和 {@link #numberField} 分开（玩家实测「购买页搜索栏不可用」）：
     * 搜索框以前是用 {@code numberField(...)} 建的，而那个工厂返回的是
     * {@link NarrowTextField} —— 它用**小号点阵字体**画值，而
     * {@code small.json} 的字符集只有 {@code "0123456789.,%+-/×·"}，**一个字母都没有**。
     * 于是输入 {@code diamond} 时：filter 其实已经被按长度放行（见下面 StockScreen 里的覆写）、
     * `this.search` 也真的更新了，但**屏幕上什么都看不见**（字形缺失），
     * 玩家看到的就是「打字没反应 / 输不进去」。
     *
     * <p>所以文本输入一律走这个工厂：普通 {@code EditBox} + 原版字体 + 只限长度。
     * **数字框继续用 {@link #numberField}（只收数字）**，两者的过滤规则不要互相串。
     */
    public static EditBox textField(Font font, StockGuiLayout.Rect rect, int maxChars, Component narration) {
        int padY = fieldTextPadY(font, rect);
        EditBox box = new EditBox(font,
                rect.x() + FIELD_TEXT_PAD_X, rect.y() + padY,
                Math.max(8, rect.w() - FIELD_TEXT_PAD_X - 4),
                Math.max(8, rect.h() - padY * 2), narration);
        box.setBordered(false);
        box.setMaxLength(maxChars);
        box.setTextColor(StockTheme.COLOR_TEXT);
        box.setTextColorUneditable(StockTheme.COLOR_MUTED);
        // 只限长度，不限字符集 —— 搜索词里有字母、中文、空格都得能进去
        box.setFilter(text -> text == null || text.length() <= maxChars);
        return box;
    }

    /**
     * 数字填写栏本体：**小号点阵字体 + 右对齐 + 放不下从左边截**。
     *
     * <p>为什么要自己画：{@code EditBox} 用的字体是构造时传进去的原版字体，而且**没有**
     * {@code setFont}（已核对 1.21.1 的公开方法表），所以只能重写 {@link #renderWidget}。
     *
     * <p>三个要点：
     * <ul>
     *   <li><b>小号字体</b>：每位数字约 4px（字模 3 + 字距 1），6 位 = 24px；
     *       常规字体要 35px，右列挤不出来。</li>
     *   <li><b>右对齐</b>：个位固定贴右边 —— 位数变化时看上去是「左边补进来的」，
     *       而不是整串字往右跳。</li>
     *   <li><b>放不下从左边截</b>：正常 6 位一定放得下，这一条只是兜底；截左边能保证末位可见。</li>
     * </ul>
     * 光标按同一套宽度换算画，所以点进去拖选、左右移动都和看到的字对得上。
     */
    private static final class NarrowTextField extends EditBox {

        private NarrowTextField(Font font, int x, int y, int w, int h, Component narration) {
            super(font, x, y, w, h, narration);
        }

        /** 小号字体的串宽（自己算，不去问 Font —— 那会按默认字体算成近两倍）。 */
        private static int smallWidthOf(String text) {
            return text.length() * SMALL_ADVANCE_PX;
        }

        /** {@code EditBox#isEditable()} 是 private，用原版的 {@link #active} 代替（语义等价）。 */
        private boolean editable() {
            return this.active;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }
            String value = getValue();
            int textW = smallWidthOf(value);
            int from = 0;
            while (textW > getWidth() && from < value.length()) {
                from++;
                textW = smallWidthOf(value.substring(from));
            }
            String shown = value.substring(from);
            if (shown.isEmpty()) {
                return;
            }
            // isBordered() == false 时原版把文字画在 (getX(), getY())，这里照同一口径
            int textX = getX() + Math.max(0, getWidth() - textW);
            int textY = getY();
            smallText(g, Minecraft.getInstance().font, textX, textY, shown,
                    editable() ? StockTheme.COLOR_TEXT : StockTheme.COLOR_MUTED);
            // 光标：按小号字体的步进算，位置和看到的字对得上（原版那条按默认字体算，会错位）
            if (isFocused() && editable()) {
                int caret = Math.max(0, Math.min(shown.length(), getCursorPosition() - from));
                int caretX = textX + caret * SMALL_ADVANCE_PX;
                g.fill(caretX, textY - 1, caretX + 1, textY + 7, StockTheme.COLOR_TEXT);
            }
        }
    }

    /**
     * 填写栏底：{@code FIELD_TILE} 拉满整行（和效果图 {@code field_box} 同一套）。
     *
     * <p>切片高 20、目标高也是 20（{@link StockGuiLayout#FIELD_ROW_H}），
     * 走「横竖都 9 段」在高度相等时和单轴版本完全一致，选它只是为了让这条规则统一。
     */
    public static void fieldBox(GuiGraphics g, StockGuiLayout.Rect rect) {
        blitSlicedV(g, StockGuiLayout.FIELD_TILE, rect.x(), rect.y(), rect.w(), rect.h());
    }

    /**
     * 「数量」/「杠杆」一整行：▲ + ▼ + MAX 三个按钮 + 填写栏控件。
     *
     * <p>填写栏的**圆角底**由调用方在 {@code renderBg} 里用 {@link #fieldBox} 画
     * （那是背景层的事，控件只负责自己那一块），标签同理 —— 这里只造会响应输入的控件。
     *
     * @param label    用于给 ▲▼ 生成提示文字（「数量 +」/「杠杆 -」）
     * @param stepUp   点 ▲ 时执行（按档位进一档）
     * @param stepDown 点 ▼ 时执行
     * @param max      点 MAX 时执行（数量 = 按余额算的最大股数；杠杆 = 上限）
     */
    public static void stepperRow(StockScreen screen, Component label, EditBox field,
                                  StockGuiLayout.Rect upRect, StockGuiLayout.Rect downRect,
                                  StockGuiLayout.Rect maxRect, Component maxLabel,
                                  Runnable stepUp, Runnable stepDown, Runnable max) {
        Button up = iconButton(upRect, StockGuiLayout.ICON_UP, label.copy().append(" +"), stepUp);
        Button down = iconButton(downRect, StockGuiLayout.ICON_DOWN, label.copy().append(" -"), stepDown);
        Button maxBtn = button(maxRect, maxLabel, max);
        up.setTooltip(Tooltip.create(label.copy().append(" +")));
        down.setTooltip(Tooltip.create(label.copy().append(" -")));
        // ⚠️ 走 screen.registerWidget 而不是 screen.addRenderableWidget：
        // 后者是 Screen 的 protected 方法，从**别的类**里调不到（javac 实测直接报访问控制错误）
        screen.registerWidget(up);
        screen.registerWidget(down);
        screen.registerWidget(maxBtn);
        screen.registerWidget(field);
    }

    /**
     * 在矩形里**左对齐、垂直居中**画一行字。
     *
     * <p>⚠️ 垂直居中这件事必须统一走这里（AGENTS.md 的实机反馈：文字和所在的行/框对不上）：
     * 「效果图脚本」是 Python 按**字模顶端**对齐画的，而原版字体 {@code drawString} 的 y 是
     * <b>字模盒顶端</b>、而且行高是 {@link Font#lineHeight}（中文 Unifont 下常见 9，不是 8）——
     * 直接拿 {@code rect.y()} 当文字 y，画出来会整体偏上 1~3px，
     * 于是「字贴在框的上沿、下面空一截」。统一用
     * {@code y = rect.y() + (rect.h() - font.lineHeight) / 2} 就一致了。
     */
    public static void label(GuiGraphics g, Font font, StockGuiLayout.Rect rect, Component text, int color) {
        g.drawString(font, text, rect.x(), centeredY(font, rect), color, true);
    }

    /** 同上，带阴影开关（阴阳柱上的价格标签是画在深色底上的，不要阴影更干净）。 */
    public static void label(GuiGraphics g, Font font, StockGuiLayout.Rect rect, Component text,
                             int color, boolean shadow) {
        g.drawString(font, text, rect.x(), centeredY(font, rect), color, shadow);
    }

    /** 在矩形里画一行字，水平方向按 {@code align} 摆：{@code -1} 左 / {@code 0} 居中 / {@code 1} 右。 */
    public static void text(GuiGraphics g, Font font, StockGuiLayout.Rect rect, Component text,
                            int color, int align) {
        int x = alignedX(font, rect, text, align);
        g.drawString(font, text, x, centeredY(font, rect), color, true);
    }

    /**
     * 在矩形里画一行字，**自己给 y**（用于明细这种「一个块里排多行」的场景）。
     *
     * <p>{@code topY} 是这一行的**文字顶端**（调用方按 {@code 块.y + 4 + i * 行高} 算），
     * 不再做额外偏移 —— 因为行高本来就已经包含了行间距。
     */
    public static void line(GuiGraphics g, Font font, StockGuiLayout.Rect rect, Component text, int topY, int color) {
        g.drawString(font, text, rect.x(), topY, color, true);
    }

    /** 右对齐的一行（金额小计、爆仓价这种靠右的）。{@code rightPad} 是离矩形右边留多少像素。 */
    public static void right(GuiGraphics g, Font font, StockGuiLayout.Rect rect, Component text,
                             int rightPad, int topY, int color) {
        g.drawString(font, text, rect.right() - rightPad - font.width(text), topY, color, true);
    }

    /** 垂直居中的 y（统一入口，别在别处再手写一遍这个算式）。 */
    public static int centeredY(Font font, StockGuiLayout.Rect rect) {
        return rect.y() + (rect.h() - font.lineHeight) / 2;
    }

    /**
     * 在矩形里左对齐画一行字，**放不下就把尾部裁掉**。
     *
     * <p>为什么不用省略号：中文点阵下「…」本身也要占 9px，反而更容易挤；
     * 这里直接把多余的字丢掉。
     *
     * <p>用途：窄窗口下的「持仓条」（实机反馈「仓位栏的字会重叠」）——
     * 那两行是「左一段 + 右一段」，空间不足时必须有一方让位。
     * **让位规则由调用方决定**（我们选的是「保左边、裁右边」），这个 helper 只负责裁干净。
     *
     * @return 真正画出来的字符数（0 = 一个字都没画）
     */
    public static int labelClipped(GuiGraphics g, Font font, StockGuiLayout.Rect rect,
                                   Component text, int color) {
        String s = text.getString();
        int limit = rect.w();
        if (limit <= 0 || s.isEmpty()) {
            return 0;
        }
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end)) > limit) {
            end--;
        }
        if (end <= 0) {
            return 0;
        }
        g.drawString(font, s.substring(0, end), rect.x(), centeredY(font, rect), color, true);
        return end;
    }

    /** 水平对齐的 x。 */
    public static int alignedX(Font font, StockGuiLayout.Rect rect, Component text, int align) {
        int w = font.width(text);
        return switch (align) {
            case 0 -> rect.x() + (rect.w() - w) / 2;
            case 1 -> rect.right() - w;
            default -> rect.x();
        };
    }

    // ==================== 小号数字字体（持仓列表用） ====================
    //
    // 为什么不是 pose().scale(0.75f)：MC 的文字 = 图集 + UV，缩小等价于 UV 缩放，
    // 5~7px 的点阵缩小必然掉笔画（中文走 Unifont 更糊）。清晰的做法是**按小尺寸重烤一份**
    // 点阵、在原尺寸下 1:1 画 —— 图集与定义由 tools/gen_small_font.py 生成。
    //
    // ⚠️ 中文**不能**套这个字体：MC 在指定字体里找不到字形时画的是「缺字方块」，
    // 不会回退到默认字体（FontSet#getGlyphInfo → SpecialGlyphs.MISSING）。
    // 所以这一套只给数字与符号用，中文照旧走默认字体。

    /** 小号数字字体 id（定义在 {@code assets/mcstock/font/small.json}）。 */
    public static final ResourceLocation FONT_SMALL =
            ResourceLocation.fromNamespaceAndPath("mcstock", "small");

    /** 把一串数字/符号包成小号字体（颜色仍由 {@code drawString} 的 color 参数给）。 */
    public static Component small(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withFont(FONT_SMALL));
    }

    /**
     * 直接画一串小号数字（不参与混排时用这个）。
     *
     * <p>⚠️ **小数点后的两位用更暗的颜色**（玩家要求：「数字的逗号分隔容易与小数点看混」，
     * 去掉逗号之后再把小数部分压暗，`21533.96` 里的 `.96` 一眼就能和小数点分开）。
     * 这里是所有小号数字的唯一出口（持仓表格四个数字列、填写栏、回收明细、资金情况…），
     * 所以只改这一处就全局生效 —— 不去动每一处调用点。
     */
    public static void smallText(GuiGraphics g, Font font, int x, int y, String text, int color) {
        int dot = text.indexOf('.');
        if (dot < 0) {
            g.drawString(font, small(text), x, y, color, false);
            return;
        }
        // 整数部分（含正负号）用原色，`.` 与小数部分压暗
        String whole = text.substring(0, dot);
        String frac = text.substring(dot);
        g.drawString(font, small(whole), x, y, color, false);
        g.drawString(font, small(frac), x + whole.length() * SMALL_ADVANCE_PX, y,
                dim(color), false);
    }

    /**
     * 在矩形里**右对齐**画一串小号数字（小数点后两位照样压暗）。
     *
     * <p>回收明细的「单价 / 小计」两列用它：右对齐的列在位数变化时是「左边补进来的」，
     * 数字不会左右乱跳（与持仓表格的数字列同一套口径）。
     * 放不下就整串不画（宁可空着，也不和左列的名称糊在一起）。
     */
    public static void smallRight(GuiGraphics g, Font font, StockGuiLayout.Rect rect,
                                  String text, int color) {
        int w = text.length() * SMALL_ADVANCE_PX;
        if (w > rect.w()) {
            return;
        }
        smallText(g, font, rect.right() - w, centeredY(font, rect), text, color);
    }

    /** 小号点阵字体每字符的步进（3px 字模 + 1px 字距，与 {@code small.json} 的 advance 一致）。 */
    public static final int SMALL_ADVANCE_PX = 4;

    /**
     * 把颜色压暗一档（给小数部分用）：透明度不降（点阵字已经很细），
     * 只把 RGB 各乘 {@code 0.62}，在深色面板上就是「同一个色系的暗一档」。
     */
    public static int dim(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * 0.62);
        int gg = (int) (((argb >> 8) & 0xFF) * 0.62);
        int b = (int) ((argb & 0xFF) * 0.62);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /**
     * 混排里的一段：中文字号 + 小号数字可以接在同一行上。
     *
     * <p>字体已经写进 {@code text} 的 Style（{@link #small}），所以宽度量算（
     * {@link Font#width(net.minecraft.network.chat.FormattedText)} 会按 Style 里的字体算）
     * 与绘制天然一致，不用自己维护一张宽度表。
     *
     * @param gap 纯空隙（{@code text == null} 时用），省得为了一个间距去拼空格组件
     */
    public record Piece(Component text, int color, boolean shadow, int gap) {

        /** 常规字号的文字（带阴影，和界面其它地方一致）。 */
        public static Piece of(Component text, int color) {
            return new Piece(text, color, true, 0);
        }

        /** 小号数字（不带阴影：1:1 点阵加阴影会糊成两倍粗）。 */
        public static Piece num(String text, int color) {
            return new Piece(small(text), color, false, 0);
        }

        /** 一段空隙。 */
        public static Piece gap(int width) {
            return new Piece(null, 0, false, Math.max(0, width));
        }
    }

    /** 一串段的总宽。 */
    public static int piecesWidth(Font font, java.util.List<Piece> pieces) {
        int w = 0;
        for (Piece piece : pieces) {
            w += piece.text() == null ? piece.gap() : font.width(piece.text());
        }
        return w;
    }

    /**
     * 从左往右依次画一串段，返回画完之后的 x。
     *
     * <p>{@code maxRight} 之后的段**整段丢掉**（不做半段裁剪）：
     * 混排里被切一半的数字比少一个数字更难读，而调用方本来就会把不重要的段放右边。
     */
    public static int pieces(GuiGraphics g, Font font, int x, int y,
                             java.util.List<Piece> pieces, int maxRight) {
        int cursor = x;
        for (Piece piece : pieces) {
            if (piece.text() == null) {
                cursor += piece.gap();
                continue;
            }
            int w = font.width(piece.text());
            if (cursor + w > maxRight) {
                break;
            }
            g.drawString(font, piece.text(), cursor, y, piece.color(), piece.shadow());
            cursor += w;
        }
        return cursor;
    }

    /** 右对齐画一串段（{@code right} = 这一串文字自己的右边界）。 */
    public static int piecesRight(GuiGraphics g, Font font, int right, int y,
                                  java.util.List<Piece> pieces) {
        return pieces(g, font, right - piecesWidth(font, pieces), y, pieces, Integer.MAX_VALUE);
    }

    /**
     * 列表用的「数字变字符串」格式化：金额（分）→ {@code 1234.56}（负数带号）。
     *
     * <p>⚠️⚠️ **不带千分位分隔符**（玩家要求：「数字的逗号分隔，容易与小数点看混，删除逗号」）。
     * 点阵小字下 `1,000.00` 与 `1.000` 几乎分不出来，所以全项目统一直出数字。
     * 原来的分组循环（{@code (digits.length() - i) % 3 == 0} 就插逗号）已删除。
     * 与之配套的还有 {@link #count(long)} 与 {@link #signedMoney(long)}（同一条链路）。
     * 服务端 {@code StockService.formatPrice} 本来就是直出，两端口径一致。
     */
    public static String money(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        long whole = abs / 100;
        long frac = abs % 100;
        return (negative ? "-" : "") + whole + "." + (frac < 10 ? "0" : "") + frac;
    }

    /** 金额（分）→ {@code +1234.56} / {@code -0.10}（浮动盈亏、距爆仓这种要带号的显示）。 */
    public static String signedMoney(long cents) {
        return (cents >= 0 ? "+" : "") + money(cents);
    }

    /**
     * 股数 / 件数 → {@code 1234}（**不带千分位**，负号照原样）。
     *
     * <p>与 {@link #money(long)} 同一条规则：全项目不出现分组逗号，
     * 免得「1,000 股」在点阵字下被读成「1.000 股」。
     */
    public static String count(long value) {
        return Long.toString(value);
    }

    /** 千分比 → {@code +1.60%}（服务端推的涨跌就是千分比整数）。 */
    public static String percent(int permille) {
        int abs = Math.abs(permille);
        return (permille >= 0 ? "+" : "-") + (abs / 10) + "." + (abs % 10) + "%";
    }

    /**
     * 相对误差（百分比，带号）——「距爆仓 -50.0%」那种。
     *
     * <p>⚠️⚠️ 这里原来是 {@code (value - base) * 1000.0 / base} 配 {@code "%+.1f%%"} ——
     * 算出来的是**千分比**（per-mille）却按百分号印，于是所有数字都**大 10 倍**：
     * 玩家实测「1 股 @92.46、×1、保证金 92.46，爆仓价 46.23，距爆仓应该是 -50.0%」，
     * 界面上显示的是 **-500.0%**（截图里 -502.2% 是价格有小数尾差的同一现象）。
     * 现在统一按百分比算：{@code (value - base) * 100.0 / base}。
     *
     * <p>算例（{@code value}=爆仓价、{@code base}=现价）：
     * <pre>
     *   92.46 → 71.66   ⇒ (7166-9246)*100/9246  = -22.5%      （×2 杠杆：保证金亏 45%）
     *   92.46 → 92.36   ⇒ (9236-9246)*100/9246  = -0.11%      （×400 杠杆：爆仓线离现价 0.11%）
     *   92.46 → 92.46   ⇒ 0.0%
     *   92.46 → 184.92  ⇒ +100.0%（空头）
     *   92.46 → 0       ⇒ -100.0%（爆仓价归零的极端）
     * </pre>
     * 符号约定：**负数 = 爆仓价在现价之下**（多头，价格跌到那里就爆），
     * 正数 = 在上方（空头）。和「距爆仓」的直觉一致：越接近 0 越危险。
     */
    public static String relativePermille(long value, long base) {
        if (base == 0) {
            return "0.0%";
        }
        double percent = (value - base) * 100.0 / base;
        return String.format("%+.1f%%", percent);
    }

    /**
     * 造一个「按矩形布置」的抽象按钮。
     *
     * <p>为什么不用 {@code Button} 的默认外观：我们的按钮是 {@code BTN_*} 四态贴图（9 段拉伸），
     * 和原版 {@code widget/button} 的灰底完全不搭。这里把 {@link AbstractWidget#renderWidget}
     * 整个接管，顺便自己画标签 —— 原版那套 {@code renderScrollingString} 会把长标签滚动起来，
     * 中文字号下反而更容易糊。
     *
     * <p><b>两种模式</b>：{@code skin = true} 画 {@code BTN_*} 四态底图（买入/卖出、页签、
     * 分类、翻页、▲▼、MAX）；{@code skin = false} 只做命中判定、**一个像素都不画**
     * （企业卡片：底图由 {@link #card} 提供，再叠一层按钮皮肤就会变成「卡片里又套一个按钮」）。
     */
    private static final class StockButton extends Button {

        /** 有图案就画图案（▲▼◀▶、✕），没有就画 {@code getMessage()} 的文字。 */
        private final StockGuiLayout.Sprite icon;

        /** 悬停时换的图案（只有关闭按钮用；为 null 表示图标不随悬停变）。 */
        private final StockGuiLayout.Sprite iconHover;

        /** 是否画 {@code BTN_*} 底图。false = 纯热区（外观由调用方自己画）。 */
        private final boolean skin;

        /**
         * 皮肤组（四态）。默认 {@link StockGuiLayout.Skin#PLAIN}（灰）；
         * 买入用 {@link StockGuiLayout.Skin#BUY}（红）、卖出用 {@link StockGuiLayout.Skin#SELL}（绿）。
         */
        private final StockGuiLayout.Skin skinSet;

        /**
         * 选中态（只有页签用）：true 时皮肤换成 {@code TAB_ACTIVE}，否则 {@code TAB_INACTIVE}。
         *
         * <p>这就是「页签高亮」的落点 —— 必须画在按钮自己的 renderWidget 里，
         * 否则会被按钮皮肤（实测 alpha = 255）盖住（见 {@link #tabButton} 的注释）。
         */
        private boolean selected;

        private StockButton(Button.Builder builder) {
            this(builder, null, null, true);
        }

        private StockButton(Button.Builder builder, StockGuiLayout.Sprite icon, boolean skin) {
            this(builder, icon, null, skin);
        }

        private StockButton(Button.Builder builder, StockGuiLayout.Sprite icon,
                            StockGuiLayout.Sprite iconHover, boolean skin) {
            this(builder, icon, iconHover, skin, false);
        }

        private StockButton(Button.Builder builder, StockGuiLayout.Sprite icon,
                            StockGuiLayout.Sprite iconHover, boolean skin, boolean selected) {
            this(builder, icon, iconHover, skin, selected, StockGuiLayout.Skin.PLAIN);
        }

        private StockButton(Button.Builder builder, StockGuiLayout.Sprite icon,
                            StockGuiLayout.Sprite iconHover, boolean skin, boolean selected,
                            StockGuiLayout.Skin skinSet) {
            super(builder);
            this.icon = icon;
            this.iconHover = iconHover;
            this.skin = skin;
            this.selected = selected;
            this.skinSet = skinSet == null ? StockGuiLayout.Skin.PLAIN : skinSet;
        }

        /** 切换选中态（页签用）：只改切片，不重建控件。 */
        void setSelected(boolean value) {
            this.selected = value;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (this.skin) {
                // 高度 ≠ 16 时走「横竖都 9 段」，1px 描边不会被拉糊；
                // 高度正好 16（页签）时两条路等价，走 blitSlicedV 也完全一致
                blitSlicedV(g, pickSprite(), getX(), getY(), getWidth(), getHeight());
            }
            // ⚠️ 图标**必须画在皮肤之后**：反过来的话皮肤会把它盖住
            // （关闭按钮的 ✕ 曾经就是这么丢的，见 closeButton 的注释）。
            if (this.icon != null) {
                StockGuiLayout.Sprite drawn = this.iconHover != null && this.isHoveredOrFocused()
                        ? this.iconHover : this.icon;
                blit(g, drawn, getX() + (getWidth() - drawn.w()) / 2,
                        getY() + (getHeight() - drawn.h()) / 2);
                return;
            }
            Component message = getMessage();
            if (message.getString().isEmpty()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int color = this.active ? (this.isHoveredOrFocused() ? StockTheme.COLOR_TEXT : StockTheme.COLOR_MUTED)
                    : StockTheme.COLOR_MUTED;
            int tx = getX() + (getWidth() - font.width(message)) / 2;
            int ty = getY() + (getHeight() - font.lineHeight) / 2;
            g.drawString(font, message, tx, ty, color, true);
        }

        /**
         * 四态：禁用 → 按下 → 悬停 → 常态；**选中态优先**（页签用 TAB_ACTIVE/TAB_INACTIVE）。
         *
         * <p>页签的三态外观（玩家可见）：
         * <pre>
         *   活动页签        → TAB_ACTIVE   (0,168,64,16)    皮肤 + 常规色文字
         *   非活动页签      → TAB_INACTIVE (64,168,64,16)   皮肤 + 灰字
         *   悬停（非活动）  → TAB_INACTIVE + 文字转亮（不换切片 —— 页签只有两张底图）
         * </pre>
         * 页签的高度正好 16，和切片同高，所以 9 段拉伸不会糊。
         *
         * <p>⚠️ 普通按钮的切片从 {@link #skinSet} 取（买=红 / 卖=绿 / 其余=灰），
         * **不要**在这里写死 {@code BTN_NORMAL}：那样买入/卖出的按下态会变回灰色。
         */
        private StockGuiLayout.Sprite pickSprite() {
            if (!this.active) {
                return this.skinSet.disabled();
            }
            if (this.selected) {
                return StockGuiLayout.TAB_ACTIVE;
            }
            if (this.isHoveredOrFocused() && Minecraft.getInstance().mouseHandler.isLeftPressed()) {
                return this.skinSet.pressed();
            }
            return this.isHoveredOrFocused() ? this.skinSet.hover() : this.skinSet.normal();
        }

        /**
         * 旁白文字（无障碍）：可见性必须是 {@code public}。
         *
         * <p>{@code Button} 把这个方法重写成了 {@code public}，这里再收窄成 {@code protected}
         * 会编译失败（javac：「正在尝试分配更低的访问权限; 以前为 public」）。
         */
        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
