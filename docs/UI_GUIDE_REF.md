# MC 股市 GUI 技术参考（附录 / REF）

> **这是附录，不是入门文档。** 想知道「什么东西管什么、这个数是怎么来的」，先看
> **[`docs/UI_GUIDE.md`](UI_GUIDE.md)**（大白话版）。本文只回答「这个控件由哪个常量、写在哪一行、
> 精确公式是什么」——170 多个常量的完整清单在这里，主文档只挑了最常改的那几十个。
>
> 本文是给**自己动手调界面**的人看的：界面上每一个控件/元素「在哪里定义、怎么改位置与大小、改完怎么看效果」。
>
> **行号会随改动漂移**，每次都标了「常量名 / 方法名」——**改之前请以常量名为准**（用编辑器的搜索找它，不要按行号跳）。
> 本文行号是写这份文档时从当时那份代码里读出来的，公式是从 `compute()` / `drawXxx()` 里逐行抄的；
> 后续代码有改动，行号已出现漂移（例如 `MIN_PANEL_HEIGHT` 现在在 `:379`、`RADIUS_*` 在 `:437-439`），
> **常量名永远比行号可靠**。
>
> ✅ **复核记录**：本文里所有「实测」数字已用 `python tools\layout_dump.py <宽> <高>`
> 在当前代码上重新跑过一遍并对齐（主要订正了 `MIN_PANEL_HEIGHT`、回收页明细行数、
> 第 1 节那张尺寸表、第 8.1 节那张排法表）。想知道某个数怎么来的，看主文档第三节那份逐步演算。
>
> **只改布局不需要动代码逻辑，也不需要启动游戏**：改完跑两条命令就能看到矩形和效果图（见第 12 节）。

## 0. 先记住四件事

### 0.1 四个文件各管什么

| 文件 | 管什么 | 你会不会改它 |
|---|---|---|
| `src/main/java/com/qiushaonan/mcstock/client/StockGuiLayout.java` | **所有矩形**（位置 + 大小）和所有布局常量。**唯一的布局来源** | ⭐ 99% 的改动在这里 |
| `src/main/java/com/qiushaonan/mcstock/client/StockScreen.java` | 三个页面 + 帮助覆盖层：谁在什么位置画什么字/图标，控件的命中判定 | 改「画什么颜色/什么字」「点哪里」才动 |
| `src/main/java/com/qiushaonan/mcstock/client/StockWidgets.java` | 贴图拼装原语（9 段拉伸）、按钮、填写栏、小号数字字体、文字对齐 helper | 改「九宫格怎么切」「文字怎么对齐」才动 |
| `src/main/java/com/qiushaonan/mcstock/client/StockTheme.java` | **12 个颜色常量**（涨/跌/底/边框/文字…） | 改配色只动这一个文件 |

配套的**生成脚本**（`tools/`）不是手改的，它们是「照代码生成图/文档」的工具，改完布局要重跑，见第 12 节。

### 0.2 坐标是「屏幕绝对坐标」，不要自己加偏移

`StockGuiLayout.compute(screenW, screenH)`（`StockGuiLayout.java:849`）一次算出全部矩形，返回不可变的 `Layout`。
`StockScreen.init()` 在 `StockScreen.java:236` 调用它，并把 `leftPos/topPos` 按到 `(0,0)`（`StockScreen.java:242`）——
**所以 `Layout` 里的 x/y 就是屏幕坐标，改一个常量，所有下游矩形自动跟着走。**

⚠️ **别动 `leftPos/topPos`**：它必须是 0。原版 `AbstractContainerScreen#render` 会先 `pose().translate(leftPos, topPos)` 再调 `renderBg`，
一旦不是 0，整个界面会整体偏移一次，槽位（用的是绝对坐标）也会跟着错位。

### 0.3 两种坐标别混：格子边框 vs Slot 坐标

- **格子边框**（`barCell` / `gridCell`）：18×18 的框，`x/y` 是框的左上角。
- **Slot 坐标**（`barSlot` / `invSlot` / `hotbarSlot` / `shopInvSlot`）：**+1**，是原版画物品的位置。

`StockGuiLayout.java:1089`（`barSlot`）里写的就是 `bar.x + col*SLOT + 1`。改位置时只改矩形的常量，两者会自动一起走。

### 0.4 单位与缩放

所有常量单位都是**逻辑像素**（GUI 缩放后的像素）。同一个常量在 2K + GUI 缩放 2 和 1080p + 自动缩放下**长得不一样大**，
所以别按「屏占比」调，按「够不够放下 6 位数字 / 中文字」这种像素需求调。

---

## 1. 一条公式链：矩形是怎么推出来的

`compute()` 是自上而下的：面板 → 标题栏/页签 → 内容区 → 三个页面各自排。

```text
PANEL（面板）
 ├ maxW = min(PANEL_MAX_W, screenW - PANEL_SCREEN_MARGIN*2)            StockGuiLayout.java:850
 ├ panelW = clamp(round(screenW * PANEL_W_RATIO), min(PANEL_MIN_W, maxW), maxW)    :852
 ├ panelH = clamp(round(screenH * PANEL_H_RATIO), min(MIN_PANEL_HEIGHT, maxH), maxH)  :855
 └ panel = (px, py, panelW, panelH)，px=(screenW-panelW)/2、py=(screenH-panelH)/2（居中，整数除法向左上取整）:857

 ├ TITLEBAR = (px, py, panelW, TITLE_H=18)                             :861
 │   ├ CLOSE = (panel.right - CLOSE_X_PAD - CLOSE_SIZE, py + CLOSE_Y_PAD, 12, 12)   :862
 │   └ HELP  = (close.x - HELP_CLOSE_GAP - CLOSE_SIZE, close.y, 12, 12)  ← 在关闭**左边**  :864
 │
 ├ TABS：tabW = clamp(panelW/6, TAB_MIN_W=64, TAB_MAX_W=160)            :867
 │        tabs[i] = (tabX0 + i*(tabW+TAB_GAP), py+TITLE_H+TAB_ROW_GAP, tabW, TAB_H=16)  :870-874
 │
 └ CONTENT = (px + CONTENT_PAD, tabY + TAB_H + CONTENT_GAP,
 │            panelW - CONTENT_PAD*2, max(40, panel.bottom - CONTENT_BOTTOM_PAD - contentY))  :876-878
     │
     ├ pageTop = content.y + FLASH_BAND_H（=11）← 给 flash 提示留的一条带   :883
     │
     ├ 【行情页】
     │   ├ LEFT_COL  = (content.x, pageTop, LEFT_COL_W=64, pageH)                       :885
     │   │    └ cards[i] = (leftCol.x, leftCol.y + i*(CARD_H+CARD_GAP), CARD_W=56, CARD_H=44)  :886-889
     │   ├ RIGHT_COL = (content.right - RIGHT_COL_W=100, pageTop, 100, pageH)            :890
     │   ├ chartX = leftCol.right + COL_GAP；chartW = max(120, rightCol.x - COL_GAP - chartX)  :891-892
     │   ├ tableH  = clamp(pageH/3, POS_TABLE_MIN_H=28, POS_TABLE_MAX_H=109)，再按剩余高度加高  :901-913
     │   ├ posList = (chartX, pageTop + pageH - tableH, chartW, tableH)  ← 表格钉在内容区底部  :914
     │   ├ chartPanel = (chartX, pageTop - FLASH_BAND_H, chartW,
     │   │               max(CHART_MIN_H=80, pageH + FLASH_BAND_H - tableH - POS_GAP))    :916-917
     │   │    └ 内部：inset(CHART_INNER_PAD=4) → 表头行 11 → 现价行 11 → 空 2 → 绘图区   :922-928
     │   └ 右列：买/卖 24 高 → 空 6 → 数量行 20 → 空 3 → 杠杆行 20 → 空 6 → 资金情况块（占满剩下）  :935-956
     │
     ├ 【回收页】见第 8 节（8 档试探，会自动换排法）
     └ 【购买页】见第 9 节
```

### 常见窗口尺寸下真实算出来的矩形（实测，不是手算）

> 「回收页排法」一列的「说明行」不再出现：`HINT_H` 已经是 0，那一行**在任何尺寸下都不占高度**（见 8.1）。

| 逻辑分辨率 | 面板 | content | 图表面板 | 持仓表格 | 网格 | 资金块行数 | 回收页排法 |
|---|---|---|---|---|---|---|---|
| 640×360（1080p+自动缩放，**典型**） | 563×317 @(38,21) | (46,55,547,275) | (116,55,371,162) | (116,221,371,109) 6 行 | 20×8 @(139,90) | 7 | 2 行栏 + 合计块 + 明细 **7** 行 |
| 854×480 | 720×422 @(67,29) | (75,63,704,380) | (145,63,528,267) | (145,334,528,109) 6 行 | 20×13 @(247,98) | 7 | 2 行栏 + 合计块 + 明细 **15** 行 |
| 1280×720（面板已顶到 720×440 上限） | 720×440 @(280,140) | (288,174,704,398) | (358,174,528,285) | (358,463,528,109) 6 行 | 20×14 @(460,209) | 7 | 2 行栏 + 合计块 + 明细 **16** 行 |
| 1920×1080（同上，再大也一样） | 720×440 @(600,320) | (608,354,704,398) | (678,354,528,285) | (678,643,528,109) 6 行 | 20×14 @(780,389) | 7 | 2 行栏 + 合计块 + 明细 **16** 行 |
| 427×240（小窗口） | 376×228 @(25,6) | (33,40,360,186) | (103,40,184,124) | (103,168,184,58) 2 行 | 20×3 @(33,75) | 4 | **1 行栏 + 无合计块** + 明细 4 行 |
| 320×240 | 308×228 @(6,6) | (14,40,292,186) | (84,40,120,124) | (84,168,120,58) 2 行 | 16×3 @(16,75) | 4 | **1 行栏 + 无合计块** + 明细 4 行 |
| 213×120（极端） | 201×108 @(6,6) | (14,40,185,66) | (84,40,120,80) | (84,106,120,**0**) 0 行 | 10×1 @(16,75) | 1 | 1 行栏 + 无合计块 + 明细 **0 行** |

> 表里的数字随时可用 `python tools/layout_dump.py <宽> <高>` 复核（第 12.1 节），**改完常量一定抽查 4~5 个尺寸**。

---

## 2. 速查表：我想改 X → 改这个常量 → 在哪个文件

| 我想改… | 改哪个常量 / 方法 | 在哪个文件（行号会漂移） |
|---|---|---|
| 面板占屏幕多大 | `PANEL_W_RATIO` / `PANEL_H_RATIO`（0.88 / 0.88） | `StockGuiLayout.java:28`、`:30` |
| 面板最小 / 最大尺寸 | `PANEL_MIN_W/H`（320/200）、`PANEL_MAX_W/H`（720/440） | `StockGuiLayout.java:31-34` |
| 面板离屏幕边缘留白 | `PANEL_SCREEN_MARGIN`（6） | `StockGuiLayout.java:36` |
| 面板居中方式 | `compute()` 里的 `px/py`（改这两个式子） | `StockGuiLayout.java:857` |
| 实际最小高度（矮屏幕时面板的底） | `MIN_PANEL_HEIGHT`（自动 = **242**，由回收页反推） | `StockGuiLayout.java:379` |
| 左列（企业卡片那一列）宽 | `LEFT_COL_W`（64） | `StockGuiLayout.java:74` |
| 右列（买卖/填写栏/资金）宽 | `RIGHT_COL_W`（100）⚠️ 改了要同时看 `FIELD_ROW_TOTAL_W` 断言 | `StockGuiLayout.java:75` |
| 左列与图表、图表与右列之间的空隙 | `COL_GAP`（6） | `StockGuiLayout.java:76` |
| 三张企业卡片的宽 / 高 / 间距 | `CARD_W`（56）/ `CARD_H`（44）/ `CARD_GAP`（6） | `StockGuiLayout.java:78-80` |
| 卡片里图标的位置 | `CARD_ICON_OFF_X/Y`（6/6） | `StockGuiLayout.java:82-83` |
| 卡片右上角涨跌箭头的位置 | `CARD_ARROW_PAD_X`（6，离右边）/ `CARD_ARROW_OFF_Y`（7，离顶边） | `StockGuiLayout.java:84-85` |
| 卡片企业名的位置 | `CARD_NAME_OFF_X`（6）/ `CARD_NAME_OFF_Y`（27） | `StockGuiLayout.java:86-87` |
| 走势图**最小**高度 / 给表格让位时留的余量 | `CHART_MIN_H`（80）/ `CHART_SLACK`（40） | `StockGuiLayout.java:118`、`:120` |
| 走势图**外框**内边距（面板沿） | `CHART_INNER_PAD`（4） | `StockGuiLayout.java:98` |
| 图内表头 / 现价两行的高度、两行之间的空隙 | `CHART_ROW_H`（11）/ `CHART_HEADER_GAP`（2） | `StockGuiLayout.java:100`、`:102` |
| 图右侧价格刻度带的宽度（轴区） | `CHART_AXIS_W`（46） | `StockGuiLayout.java:129` |
| 一根柱子的横向像素 / 圆角 / 根数上下限 | `CANDLE_PX`（7）/ `CANDLE_RADIUS`（2）/ `CANDLE_MIN/MAX`（20/60） | `StockGuiLayout.java:122`、`:135-137` |
| 一根柱子 = 几跳 | `CANDLE_TICKS`（5 跳 = 10 秒） | `StockGuiLayout.java:139` |
| 持仓表格整块高度（表头+数据行总数） | `POS_TABLE_MIN_H`（28）/ `POS_TABLE_MAX_H`（109） | `StockGuiLayout.java:261-262` |
| 表格表头行高 / 数据行高 | `POS_TITLE_H`（11）/ `POS_ROW_H`（16） | `StockGuiLayout.java:239`、`:241` |
| 表格六列的列宽 | `POS_COL_W[]` = `{34,46,30,52,46,POS_CLOSE_W}` | `StockGuiLayout.java:272` |
| 行尾「平」按钮 | `POS_CLOSE_W`（22）⚠️ 高度跟行高走，`POS_CLOSE_H` 已失效 | `StockGuiLayout.java:246` |
| 表格右侧滚动条 | `POS_SCROLLBAR_W`（3） | `StockGuiLayout.java:264` |
| 表格与走势图之间的空隙 | `POS_GAP`（4） | `StockGuiLayout.java:259` |
| 买入 / 卖出按钮高度、间距 | `BTN_MAIN_H`（24）/ `BTN_GAP`（3）/ `BTN_GROUP_GAP`（6） | `StockGuiLayout.java:143`、`:147-148` |
| 「数量/杠杆」两行的高度与行距 | `FIELD_ROW_H`（20）/ `BTN_GAP`（3） | `StockGuiLayout.java:153`、`:147` |
| 数量/杠杆的**标签宽** | `FIELD_LABEL_W`（12） | `StockGuiLayout.java:162` |
| 数字填写栏宽度 | `FIELD_W`（42，6 位数字不会裁） | `StockGuiLayout.java:176` |
| ▲▼ 和 MAX 按钮宽度 | `FIELD_STEP_W`（11）/ `FIELD_MAX_W`（14） | `StockGuiLayout.java:178`、`:180` |
| 填写栏里各段的间隙 | `FIELD_INNER_GAP`（2） | `StockGuiLayout.java:181` |
| 填写栏最多几位 | `FIELD_MAX_CHARS`（6）+ `QTY_MAX`/`LEVERAGE_MAX` | `StockGuiLayout.java:207`、`:202`、`:205` |
| 资金情况块的行高 / 行数上限 | `FUNDS_LINE_H`（13）/ `FUNDS_LINES`（7）/ 标题 `FUNDS_TITLE_H`（12） | `StockGuiLayout.java:209-211` |
| 页签宽 / 高 / 间距 | `TAB_MIN_W`（64）/`TAB_MAX_W`（160）取 `panelW/6`、`TAB_H`（16）、`TAB_GAP`（4） | `StockGuiLayout.java:42-45`、`:41` |
| 页签行上下的空隙 | `TAB_ROW_GAP`（0）/ `CONTENT_GAP`（0）⚠️ 都是 0，见 3.3 | `StockGuiLayout.java:53`、`:60` |
| 关闭 / 帮助按钮的大小与位置 | `CLOSE_SIZE`（12）/ `CLOSE_X_PAD`（4）/ `CLOSE_Y_PAD`（3）/ `HELP_CLOSE_GAP`（6） | `StockGuiLayout.java:65-69` |
| 内容区四周内边距 | `CONTENT_PAD`（8）/ `CONTENT_BOTTOM_PAD`（8） | `StockGuiLayout.java:61-62` |
| 槽位（背包/回收栏/网格）格子边长 | `SLOT`（18） | `StockGuiLayout.java:64` |
| 回收栏的列数 / 行数 | `BAR_COLS`（9）/ `BAR_ROWS`（2） | `StockGuiLayout.java:277-278` |
| 合计块宽度 / 行数 / 行高 | `BAR_INFO_W`（190）/ `BAR_INFO_LINES`（2）/ `BAR_INFO_LINE_H`（13） | `StockGuiLayout.java:279`、`:316-317` |
| 「回收」按钮的宽 / 高 / 最小宽 | `RECYCLE_BTN_W`（170）/ `RECYCLE_BTN_H`（28）/ `RECYCLE_BTN_MIN_W`（76） | `StockGuiLayout.java:287-288`、`:295` |
| 回收明细行高 | `DETAIL_LINE_H`（13） | `StockGuiLayout.java:299` |
| 回收页顶部「说明行」高度 | `HINT_H`（**0**，文字已删、这一行不再占高度）⚠️ 见 8.1 | `StockGuiLayout.java:285` |
| 购买页分类/搜索/翻页那一行的高度 | `FILTER_H`（18） | `StockGuiLayout.java:376` |
| 分类按钮 / 翻页箭头 / 页码宽度 | `CAT_BTN_W`（84）/ `PAGE_BTN_W`（18）/ `PAGE_TEXT_W`（46） | `StockGuiLayout.java:377-379` |
| 搜索框最小宽度 | `SEARCH_MIN_W`（90） | `StockGuiLayout.java:380` |
| 商品网格的列数 / 行数上下限 | `GRID_MIN_COLS`（8）/ `GRID_MAX_COLS`（20）/ `GRID_MAX_ROWS`（16）⚠️ `GRID_MIN_ROWS` 已失效 | `StockGuiLayout.java:399-404` |
| 网格与分类行之间的空隙 | `GRID_GAP_TOP`（6） | `StockGuiLayout.java:381` |
| 帮助面板行高 / 正文条数 | `HELP_LINE_H`（11）/ `HELP_LINES`（20，对应语言键 `gui.mcstock.help.1..20`） | `StockGuiLayout.java:385-386` |
| 帮助面板正文左右内边距 | `HELP_PAD_X`（8，在 Screen 里） | `StockScreen.java:658` |
| 状态提示（flash）那一行的高度 | `FLASH_ROW_H`（11）/ 占位带 `FLASH_BAND_H`（11） | `StockGuiLayout.java:388`、`:398` |
| 圆角半径（面板/方框/槽位） | `RADIUS_PANEL`（6）/ `RADIUS_BOX`（4）/ `RADIUS_SLOT`（3）→ **必须重跑贴图脚本** | `StockGuiLayout.java:421-423` |
| 涨跌 / 底 / 边框 / 文字颜色 | `COLOR_UP` / `COLOR_DOWN` / `COLOR_*`（共 12 个） | `StockTheme.java:23-45` |
| 小号数字字体的大小（图集格高） | `CELL_W`（3）/ `CELL_H`（6）+ `chars` + `SMALL_ADVANCE_PX`（4）三处必须同步 | `tools/gen_small_font.py:63-71`、`StockWidgets.java:647` |
| 图集总尺寸 | `ATLAS_W` / `ATLAS_H`（256×256） | `StockGuiLayout.java:430-431` |
| 图集里每个切片画在哪 | `Sprite(u, v, w, h)` 常量（`FRAME_*` / `BTN_*` / `TAB_*` / `CARD_*` / `ICON_*` …） | `StockGuiLayout.java:438-482` |

---

## 3. 面板外壳：面板 / 标题栏 / 页签 / 状态行

### 3.1 面板本身

| 元素 | 谁算的 | 公式 | 想改位置 | 想改大小 | 牵连 |
|---|---|---|---|---|---|
| 面板矩形 | `compute()` | 见 1. 的公式链（比例 + 上下限 + 居中） | 没有「位置」常量——永远居中，想偏移就改 `px/py` 的式子 | `PANEL_W_RATIO`、`PANEL_H_RATIO`、`PANEL_MAX_W/H` | **一切**都从它推出来：面板一变，三列、图表、表格、网格全部重排 |
| 边框 / 圆角 | `StockWidgets.panelFrame(g, l.panel())` `StockWidgets.java:150` | 9 张 8×8 切片：四角 1:1、四边拉伸、中间 `roundedFill` | — | 圆角 = `RADIUS_PANEL`（贴图里的透明像素！） | 改半径**必须重跑** `gen_gui_textures.py`，否则代码摆的是旧圆角 |
| 面板底纹 | `roundedFill(..., RADIUS_PANEL, COLOR_PANEL_FILL)` `StockWidgets.java:156` | — | — | `COLOR_PANEL_FILL` | 只影响观感 |

### 3.2 标题栏（`TITLE_H = 18`）

| 元素 | 谁算的 / 谁画的 | 公式 | 想改位置 | 想改大小 | 牵连 |
|---|---|---|---|---|---|
| 标题栏矩形 | `compute()` `StockGuiLayout.java:861` | `(px, py, panelW, TITLE_H)` | `TITLE_H` 一变，页签行、内容区、**页面上所有块**都往下移 | `TITLE_H` | ⚠️ 改 `TITLE_H` 会改 `content.y`，**图表上沿与页签下沿的等式仍然成立**（都是推出来的） |
| 标题栏底纹 | `drawChrome` `StockScreen.java:1309` | `TITLE_TILE` 横向平铺 `(title.x+1, title.y+1, title.w-2, title.h-1)` | 只跟标题栏走 | 改图集里 `TITLE_TILE` 那一格（8×18） | 重跑贴图脚本 |
| 标题「MC 股市」 | `drawTitleBarText` `StockScreen.java:1683` | `label(title.inset(8,0), ...)` → x = 面板左 + 8，**垂直居中** | 内缩量 8（写在调用处，不是常量） | 字号（原版字体，全局） | 无 |
| 余额 · 净值 | 同上 `:1666-1670`、`:1686` | `text(title, money, COLOR_TEXT, 0)` → **在整条标题栏里居中** | 改对齐参数（0 = 居中） | — | 它是「标题栏中心」，不与左侧标题做避让 |
| 倒计时（`5 秒`） | 同上 `:1674-1688` | `timerRight = helpBtn.x - 8`；`timer = (timerRight - font.width, title.y, font.width, title.h)` → **右端贴在帮助按钮左边 8px** | 那个 `8`（调用处） | 文字宽度自适应 | ⚠️ 它靠 `helpBtn.x` 定位：`HELP_CLOSE_GAP` 或 `CLOSE_X_PAD` 一改，倒计时跟着挪 |
| 帮助按钮「?」 | 控件层 `StockWidgets.iconButton` `StockWidgets.java:318`，图标 `ICON_HELP` | `(close.x - HELP_CLOSE_GAP - CLOSE_SIZE, close.y, 12, 12)` | `HELP_CLOSE_GAP`（离关闭按钮）、`CLOSE_Y_PAD`（离顶） | `CLOSE_SIZE` | 和关闭按钮共用尺寸；倒计时的右边界跟着它 |
| 关闭按钮「✕」 | 控件层 `StockWidgets.closeButton` `StockWidgets.java:339` | `(panel.right - CLOSE_X_PAD - CLOSE_SIZE, py + CLOSE_Y_PAD, 12, 12)` | `CLOSE_X_PAD`、`CLOSE_Y_PAD` | `CLOSE_SIZE` | ⚠️ **叉必须由按钮自己画**（`StockButton.renderWidget` 里皮肤之后，`StockWidgets.java:833-846`）；挪到 `drawChrome` 里画会被按钮皮肤盖掉（只剩一个幽灵圈，实机踩过） |

### 3.3 页签行

| 元素 | 谁算的 | 公式（`StockGuiLayout.java:867-874`） |
|---|---|---|
| 单个页签宽 | `compute()` | `tabW = clamp(panelW / 6, TAB_MIN_W=64, TAB_MAX_W=160)`。640 屏 → 563/6 = **93** |
| 三页签总宽 + 左端 | 同上 | `tabsW = tabW*3 + TAB_GAP*2`；`tabX0 = px + max(CONTENT_PAD, (panelW - tabsW)/2)` → **居中，但不小于内容区内边距** |
| 页签 y | 同上 | `tabY = py + TITLE_H + TAB_ROW_GAP`（`TAB_ROW_GAP = 0`） |
| 单个页签矩形 | 同上 | `tabs[i] = (tabX0 + i*(tabW + TAB_GAP), tabY, tabW, TAB_H=16)` |

- **想改宽**：`TAB_MIN_W` / `TAB_MAX_W`（或把 `panelW/6` 换成别的比例，`StockGuiLayout.java:867`）。
- **想改高**：`TAB_H`。⚠️ 它同时决定 `content.y`（`contentY = tabY + TAB_H + CONTENT_GAP`），**改高 = 整个内容区下移**。
- **想改间距 / 首尾对齐**：`TAB_GAP`、`CONTENT_PAD`（后者同时是内容区左右内边距，会一起变）。
- **高亮怎么画**（两个地方都画，实机只看得到其中一个）：
  1. 美术层：`drawChrome`（`StockScreen.java:1318-1322`）按当前页 blit `TAB_ACTIVE` / `TAB_INACTIVE`（9 段拉伸，1280 宽下 `TAB_MAX_W` 拉伸中间段）；
  2. 控件层：页签是**普通按钮控件**（`StockScreen.java:274-280` → `StockWidgets.button`），
     `StockButton.renderWidget` 会在**更晚的控件层**画 `BTN_NORMAL/HOVER/PRESSED` 皮肤（`StockWidgets.java:834-838`）。
- ⚠️ **实测结论**：`BTN_*` 四态中间是**完全不透明**的（图集实测中心 alpha = 255，只有 20 个透明像素是四角），
  所以**同一矩形上的 `TAB_ACTIVE` 会被页签按钮的 `BTN_NORMAL` 整个盖住** —— 实机上三个页签未悬停时长得一模一样（底图相同、文字都是 `COLOR_MUTED`）。
  想要「当前页真高亮」，要么把页签改成 `StockWidgets.bareButton`（`StockWidgets.java:305`，只留 `TAB_*` 底图、自己画字），
  要么给 `StockButton` 加一个「选中态」皮肤 —— **光改图集或改常量是没用的**。

### 3.4 状态提示行（flash）

| 元素 | 谁算的 / 谁画的 | 说明 |
|---|---|---|
| 占位带 | `FLASH_BAND_H`（11）`StockGuiLayout.java:398` | 三页的页面内容一律从 `contentTop() = content.y + FLASH_BAND_H` 开始（`StockGuiLayout.java:621`），把内容区最上面一条**留给 flash**，避免提示压住第一行控件 |
| 提示矩形 | `flashRow()` `StockGuiLayout.java:611` | `(content.x+2, content.y, content.w-4, FLASH_ROW_H=11)` |
| 提示怎么画 | `drawFlashLine` `StockScreen.java:1521-1547` | 圆角底衬 + 强调色文字（快过期转灰）；**购买页完全不画**（`:1526`）；4 秒 TTL（`FLASH_TTL_MS`，`:179`） |
| 想改高 | — | `FLASH_ROW_H`（提示自身高度）与 `FLASH_BAND_H`（给页面内容让开的带）**是两个常量，要一起改**；`FLASH_BAND_H` 一改，三页所有内容整体下移 |

---

## 4. 左列：三张企业卡片

| 元素 | 谁算的 / 谁画的 | 公式 | 改位置 | 改大小 | 牵连 |
|---|---|---|---|---|---|
| 左列矩形 | `compute()` `StockGuiLayout.java:885` | `(content.x, pageTop, LEFT_COL_W, pageH)` | 随 `content.x`（= `CONTENT_PAD`） | `LEFT_COL_W` | ⚠️ **图表 x 直接由它推出来**：`chartX = leftCol.right + COL_GAP`（`:891`）。改 `LEFT_COL_W` → 图表左移/右移且宽度反向变化 |
| 卡片矩形 | `compute()` `:886-889` | `cards[i] = (leftCol.x, leftCol.y + i*(CARD_H + CARD_GAP), CARD_W, CARD_H)` | 随左列 | `CARD_W`/`CARD_H`/`CARD_GAP` | ⚠️ 三张卡片总高 = `3*CARD_H + 2*CARD_GAP`（当前 144）——`CARD_H` 一改，卡片整体变高，**但左列高度 `pageH` 不变**（它由内容区决定），所以卡片只是「在列里更高/更矮」，不会挤到别的块。卡片列**不参与**任何高度分配 |
| 卡片底图 | `StockWidgets.card` `StockWidgets.java:275` | 按状态**只画一张**：选中 → `CARD_SELECTED`、悬停 → `CARD_HOVER`、否则 `CARD_NORMAL`，走「横竖都 9 段」拉伸 | — | 切片随 `CARD_W/H` 拉伸 | ⚠️ **三态互斥**：别顺手把三张都画上，会看到错位叠图 |
| 卡片命中热区 | `bareButton`（`StockScreen.java:315-319`） | 矩形就是卡片矩形，**不画一个像素** | — | — | 卡片按钮**不能**画 `BTN_*` 皮肤（会变成「卡片里套了个按钮」） |
| 卡片图标（16×16） | `drawTradeBg` `StockScreen.java:1365` | `renderItem(icon, card.x + CARD_ICON_OFF_X, card.y + CARD_ICON_OFF_Y)` | `CARD_ICON_OFF_X/Y`（6/6） | 固定 16×16（物品图标） | 只影响卡片内部 |
| 企业名 | `drawTradeText` `StockScreen.java:1796-1800` | `nameRow = (card.x + CARD_NAME_OFF_X, card.y + CARD_NAME_OFF_Y, card.w - 2*CARD_NAME_OFF_X, font.lineHeight)`，垂直居中于该行 | `CARD_NAME_OFF_X/Y`（6/27） | 宽度跟着 `CARD_W` 缩 | 选中卡片名字 `COLOR_TEXT`、其余 `COLOR_MUTED` |
| 右上角涨跌箭头 | 同上 `:1802-1806` | `arrowX = card.right - CARD_ARROW_PAD_X - arrow.w`；`arrowY = card.y + CARD_ARROW_OFF_Y`；图标 `ICON_UP`/`ICON_DOWN`（9×8） | `CARD_ARROW_PAD_X`（6）/ `CARD_ARROW_OFF_Y`（7） | 图集里那两格 | `CARD_ARROW_GAP`（4）**不是**给卡片用的（卡片改成右上角对齐了），它现在只用在现价行的箭头（`StockScreen.java:1824`） |

---

## 5. 中间：走势图

### 5.1 面板与图内四段

| 元素 | 谁算的 / 谁画的 | 公式 | 改位置 | 改大小 | 牵连 |
|---|---|---|---|---|---|
| 图表面板 `chartPanel` | `compute()` `StockGuiLayout.java:916` | `(chartX, pageTop - FLASH_BAND_H, chartW, max(CHART_MIN_H, pageH + FLASH_BAND_H - tableH - POS_GAP))` | x 由 `LEFT_COL_W + COL_GAP` 决定；y **恒等于页签下沿**（`content.y`） | 高度由「上面钉死、下面让给表格」决定；`CHART_MIN_H` 是地板 | ⚠️ 高度与持仓表格**此消彼长**：`tableH` 大 → 图矮。见 6.1 |
| 面板边框 | `drawTradeBg` `StockScreen.java:1370` → `panelFrame` | 9 段边框，圆角 `RADIUS_PANEL` | — | 重跑贴图脚本 | — |
| 内缩后的可用区 `padded` | `compute()` `:922` | `chartPanel.inset(CHART_INNER_PAD, CHART_INNER_PAD)` | `CHART_INNER_PAD`（4） | 同上 | 它是表头/现价/绘图区的共同父矩形 |
| 表头行（企业名 · 风险） | `compute()` `:923` + 画在 `StockScreen.java:1812-1815` | `chartHeader = (padded.x, padded.y, padded.w, CHART_ROW_H=11)`，**左对齐 + 垂直居中** | `CHART_ROW_H` 改高度（两行一起变） | 宽度 = `padded.w` | 表头 + 现价两行共 `2*CHART_ROW_H + CHART_HEADER_GAP = 24`；⚠️ `CHART_HEADER_H`（26）是**失效常量**，改了没用 |
| 现价行（现价 + 涨跌） | `compute()` `:924` + `StockScreen.java:1818-1825` | `chartPrice = (padded.x, chartHeader.bottom, padded.w, CHART_ROW_H)`；现价左对齐 `COLOR_TEXT`，涨跌右对齐（颜色跟涨跌），箭头画在数字左边 | 同上 | 同上 | 涨跌箭头 x = `priceRow.right - font.width(text) - CARD_ARROW_GAP - 9` |
| 绘图区 `chartPlot` | `compute()` `:925-928` | `plotY = chartPrice.bottom + CHART_HEADER_GAP`；`plotH = max(24, padded.bottom - plotY)` | 跟着上面两行 | 吃掉两行以下的**全部**剩余高度（没有别的块和它抢） | 绘图区高度一变，柱子的「每格多少分」跟着变（量程自适应，不会拉伸变形） |
| 绘图区底衬 + 网格 | `drawTradeBg` `:1371` + `drawChartGrid` `:1424-1429` | 内嵌屏 `COLOR_INSET_FILL` + `COLOR_BORDER`；网格**只画 2×2**：一条横中线 + 一条竖中线（竖线只在**柱子区**里画） | 网格线位置是算出来的（中点），没有常量 | 想加减网格线要改 `drawChartGrid` | 网格线颜色 `COLOR_GRID_LINE` |

### 5.2 柱子区 vs 轴区（**零重叠是硬约定**）

| 区域 | 谁算的 | 公式（`StockGuiLayout.java:1133-1149`） | 里面只能画 |
|---|---|---|---|
| 柱子区 `chartCandle(l)` | `chartCandle()` | `(plot.x, plot.y, max(1, plot.w - min(CHART_AXIS_W, plot.w/2)), plot.h)` | 阴阳柱、影线、现价虚线、爆仓虚线、超量程短横、爆仓方位小三角 |
| 轴区 `chartAxis(l)` | `chartAxis()` | `(candle.right, plot.y, plot.right - candle.right, plot.h)`，宽度 = `CHART_AXIS_W`（46，窗很窄时最多占一半） | 整条底衬、三个价格刻度、现价价签、爆仓价签、爆仓价文字 |

- **想改轴区宽**：`CHART_AXIS_W`（`StockGuiLayout.java:129`）→ 柱子区自动反向变化。
- 640×360 实测：`chartPlot = (120,83,363,130)` → 柱子区 `(120,83,317,130)`、轴区 `(437,83,46,130)`。
- ⚠️ 这两个区**靠约定不重叠**，没有任何断言兜底；画价格文字时还把 `scissor` 夹在轴区里（`StockScreen.java:2374`），越界会被硬裁。

### 5.3 柱子本身

| 元素 | 谁算的 / 谁画的 | 公式 |
|---|---|---|
| 每根占的槽宽 | `drawCandles` `StockScreen.java:2176` | `slotW = CANDLE_PX`（7），**固定**，不随根数变化 |
| 一屏最多几根（实际） | 同上 `:2177` | `slotCount = max(1, 柱子区.w / slotW)` → 640 屏 = `317/7` = **45 根** |
| 「画多少根」的另一个数 | `Layout.candleCount` `StockGuiLayout.java:929` | `clamp(chartPlot.w / CANDLE_PX, 20, 60)` → 640 屏 = **51**。⚠️ 这个数**没有被渲染端使用**（渲染端用上面的 45），只在 dump/规格书里出现；别拿它当准 |
| 柱体宽 | 同上 `:2235` | `bodyW = max(1, slotW - 2)` = **5px** |
| 柱心 x | 同上 `:2251` | `cx = 柱子区.x +（这根在窗口里的槽位下标）* slotW + slotW/2`（**左对齐 + 固定槽宽**，攒满一窗后整体左移一格） |
| 影线 | 同上 `:2260` | `fill(cx, yHigh, cx+1, yLow+1)`，1px、`COLOR_BORDER` |
| 实体 | 同上 `:2268-2271` | `fillSoft(body, CANDLE_RADIUS, 涨跌色, COLOR_INSET_FILL)` + `outline(body, COLOR_BORDER)`；半径自动夹到 `min(w,h)/3`，矮于 3px 退化成方角 |
| 超出量程的提示 | 同上 `:2273-2278` | 贴柱区上下边缘画 3px 同色短线（`cx-1 .. cx+2`） |
| 涨跌色 | `StockTheme.COLOR_UP` / `COLOR_DOWN` | **A 股口径：红涨绿跌**（`c >= o` 用 `COLOR_UP`） |
| 窗口起点 | 同上 `:2178-2187` | 锚在**第一根有数据的柱子**：数据不满一窗时从柱子区左缘依次占用，满了才滚动 |

### 5.4 两根虚线

| 元素 | 谁算的 / 谁画的 | 规则 |
|---|---|---|
| 现价虚线 | `drawCandles` `StockScreen.java:2286-2292` | 颜色跟最新一根柱（涨跌）；右端 = `min(柱子区.right, 柱子区.x + 已占槽数*slotW)`（**不铺满整区**）；虚线节奏 `dashedHLine`：每 4px 画 2px（`:2535-2539`） |
| 爆仓虚线 | 同上 `:2297-2305` | **只在量程内才画**（`liq ∈ [low, high]`）；取「**离爆仓最近**的那个仓」（`StockClientState.nearestLiquidation`） |
| 爆仓在量程外 | `drawLiquidationMarker` `:2334-2377` | 柱子区边缘一个 5→3→1 的小三角（`candleZone.x + 3`）+ 轴区里一行文字；⚠️ 文字必须**避让轴区里已有的每一行**（现价签/爆仓签/三个刻度），垂直距离 < `ROW_CLEARANCE`（11）就整行不画（`:2360-2373`） |
| 两个价签太近 | 同上 `:2304` | `abs(liqY - priceY) < 14` → 只画现价签 |

### 5.5 价格标签与三个刻度

| 元素 | 谁画的 | 公式 |
|---|---|---|
| 轴区底衬 | `drawPriceAxis` `StockScreen.java:2492` | 整条轴区铺 `COLOR_INSET_FILL`（挡住可能贴边的柱子/虚线） |
| 三个刻度 | 同上 `:2493-2510` | 值 = 高 / 中 / 低；y 由 `scaleY(i)`（`:2519`）：**上 = 柱子区.y、下 = 柱子区.bottom-1、中 = 价格中线**（上下两个刻度与柱区边缘**数值相等**，不是等比换算） |
| 刻度文字对齐 | 同上 `:2500-2509` | 上刻度**字形顶**贴柱区上沿、下刻度字形底贴柱区下沿、中刻度以线为中心；横向 `x = 轴区.x + 2`，颜色 `COLOR_MUTED`、**不带阴影** |
| 现价价签 | `priceTag` `:2558-2571` | `w = min(轴区.w, font.width(text)+6)`、`h = font.lineHeight+4`、`x = 轴区.right - w`、`top = clamp(y - h/2)`；深底 + 彩色 1px 描边；数字左内边距 **3px**（不能省，小字号下会连成色带） |
| 现价价签的避让 | `canDrawPriceTag` `:2426-2446` | 与三个刻度任一行距离 < 11px 就**整个不画**（那时 `priceY` 置成 `MIN_VALUE` 通知下游） |
| 价格 → y | `priceToY` `:2528-2532` | `y = plot.bottom - 1 - round((v-low)/(high-low) * (plot.h-1))`，再夹进 `[plot.y, plot.bottom-1]` |

### 5.6 量程（不是布局，但常被误当成「布局变了」）

`stabilizedAxis`（`StockScreen.java:2080-2138`）算的是 y 轴量程，跟矩形无关：
包含基准价 + 量化步长（基准价 0.4%，`:2034`）+ 最小跨度（柱子少 2%、够了 0.6%，`:2042`/`:2050`）+ 收缩滞回（`:2054`）。
**它只让柱子高低变化，不会改任何矩形。** 调布局时看到「柱子变矮了」，先确认是不是表格抢了绘图区高度，而不是去动量程常量。

---

## 6. 图下持仓表格

> ⚠️ 先说一条：`StockGuiLayout.java:223-238` 的类注释还在描述**旧版两行式持仓列表**（「行高 20 = 两行文字」「行尾一个按钮」），
> 现在的实现是**一行六列**（方向/数量/倍数/盈亏/距爆仓/平），`POS_LINE1_DY`、`POS_LINE2_DY` 这两个常量**已经没人用**。以代码为准。

### 6.1 整块位置与高度（谁抢谁的空间）

```java
// StockGuiLayout.java:901-917
int tableH = clamp(pageH / 3, POS_TABLE_MIN_H /*28*/, POS_TABLE_MAX_H /*109*/);
int tableRoom = pageH - CHART_MIN_H /*80*/ - CHART_SLACK /*40*/;
if (tableRoom > POS_TABLE_MIN_H) tableH = clamp(max(tableH, tableRoom), POS_TABLE_MIN_H, POS_TABLE_MAX_H);
if (pageH - tableH < CHART_MIN_H) tableH = max(0, pageH - CHART_MIN_H);   // 图不能再矮了，先保图
if (tableH < POS_TITLE_H + POS_ROW_H /*27*/) tableH = 0;                  // 连表头+一行都放不下 → 整块不画
Rect posTable = (chartX, pageTop + pageH - tableH, chartW, tableH);       // 钉在内容区底部
Rect posList  = posTable;                                                // 旧名字保留
```

- **想改位置**：表格 x/宽 = 图表 x/宽（`chartX` / `chartW`），改它们要动 `LEFT_COL_W` / `RIGHT_COL_W` / `COL_GAP`；纵向钉在内容区底部，没有独立常量。
- **想改高度**：`POS_TABLE_MIN_H` / `POS_TABLE_MAX_H`（或把 `pageH/3`、`CHART_SLACK` 改掉）。**一行 ≈ `POS_ROW_H`（16）+ 表头**
- **牵连**：⚠️ 表格高度和图区高度是**同一个 `pageH` 里分**：表格每多一行（16px），走势图就少 16px；`POS_TABLE_MAX_H`（109）决定最多同屏 6 行。
- **等式（没有断言，改了要自己核）**：`posList.y == chartPanel.bottom + POS_GAP`；`chartPanel.y == 页签下沿`（tabY + TAB_H）。
  640×360 实测两边都是 55 / 221（自检命令见 12.1）。⚠️ 极端小窗口会破（213×120 实测 `posList.y=106 < chartPanel.bottom+POS_GAP=124`，那时表格高度已经是 0、整块不画）。

### 6.2 表头行 / 数据行

| 元素 | 谁算的 / 谁画的 | 公式 |
|---|---|---|
| 表头矩形 | `posHeader()` `StockGuiLayout.java:538` | `(posList.x, posList.y, posList.w, POS_TITLE_H=11)` |
| 表头列名 | `drawPosHeaderCells` `StockScreen.java:1937-1946` | 用 `posCol(col, -1)` 取格（行号传 -1 就落在表头行），灰字 `COLOR_MUTED`，放不下会裁尾（`labelClipped`） |
| 表头右端页码「1/3」 | `posPage()` `StockGuiLayout.java:626` + `StockScreen.java:1877-1880` | `w = max(0, min(POS_PAGE_W=26, header.w/3))`，`x = header.right - w - POS_TEXT_PAD_X`，右对齐 |
| 表头下分隔线 | `drawPositionListBg` `StockScreen.java:1398` | `fill(header.x+4, header.bottom-1, header.right-4, header.bottom)` |
| 数据行矩形 | `posRow(i)` `StockGuiLayout.java:638` | `(posList.x, posList.y + POS_TITLE_H + i*POS_ROW_H, posList.w, POS_ROW_H)` |
| 一屏几行 | `posVisibleRows()` `:533` | `max(0, (posList.h - POS_TITLE_H) / POS_ROW_H)` → 109 高 = **6 行**，58 高 = **2 行**，可以为 **0**（绘制端必须容忍 0） |
| 行内容 | `drawPositionRow` `StockScreen.java:1957-1982` | 第 0 列中文（多=红/空=绿），第 1~4 列数字**右对齐**（小号字体），行尾第 5 列是「平」按钮（控件层） |
| 行分隔线 / 悬停高亮 | `StockScreen.java:1904-1913` | 行底线 `COLOR_BORDER`；鼠标所在行铺 `COLOR_PANEL_FILL` |
| 硬裁剪 | `StockScreen.java:1887` | 行区 `enableScissor(list.x, header.bottom, list.right, list.bottom)`，越界不画 |
| 空列表 | `StockScreen.java:1888-1893` | 在第一行位置画 `gui.mcstock.pos.empty`（灰字），内缩 `POS_TEXT_PAD_X` |

### 6.3 六列的列宽与对齐

```java
// StockGuiLayout.java:552-571（posCol(col, row)）
int avail     = max(1, posList.w - POS_SCROLLBAR_W /*3*/);      // 先让开滚动条
int btnW      = min(POS_CLOSE_W /*22*/, max(1, avail/2));       // 末列：不被压得太小
int dataAvail = max(1, avail - btnW - COL_GAP /*6*/);           // 前五列分剩下的宽度
int dataTotal = POS_COL_W[0..4] 之和 /*208*/;
// col 0..4：x 依次累加 max(1, POS_COL_W[i] * dataAvail / dataTotal)，宽度同式
// col 5   ：x = rowRect.x + dataAvail + COL_GAP，宽 = btnW
```

| 列 | 序号 | 基准宽 `POS_COL_W` | 对齐 | 颜色 |
|---|---|---|---|---|
| 方向 | 0 | 34 | 左（中文，`labelClipped`） | 多 = `COLOR_UP`，空 = `COLOR_DOWN` |
| 数量 | 1 | 46 | **右**（小号数字） | `COLOR_TEXT` |
| 倍数 | 2 | 30 | **右**（`x` + 杠杆） | `COLOR_MUTED` |
| 盈亏 | 3 | 52 | **右**（带号） | 正 `COLOR_UP` / 负 `COLOR_DOWN` |
| 距爆仓 | 4 | 46 | **右**（相对现价百分比） | `COLOR_LIQUIDATION` |
| 平仓按钮 | 5 | `POS_CLOSE_W` = 22 | — | 控件自己画 |

- **想改列宽**：改 `POS_COL_W` 数组（`StockGuiLayout.java:272`）。⚠️ 它是**固定比例表**：窄窗口下按 `dataAvail/dataTotal` 整体等比缩放，所以列宽只有在 640 宽以上才等于你写的值。
- 640×360 实测（`avail=368`、`dataAvail=340`）：列宽 = 55 / 75 / 49 / 85 / 75，末列 22；`posCol(5,0) = (462,232,22,16)`。
- **想改对齐**：改 `drawPositionRow`（`StockScreen.java:1965-1981`），数字统一走 `numRight`（`:1985-1993`，放不下就整段不画，不会糊到相邻列）。
- **文字最右边界**：`posTextRight(row)`（`StockGuiLayout.java:599`）= `posList.right - 3 - POS_TEXT_PAD_X - 1`。

### 6.4 行尾「平」按钮（唯一的平仓入口）

| 项 | 值 / 位置 |
|---|---|
| 矩形 | `posCloseBtn(i)` = `posCol(5, i)`（`StockGuiLayout.java:644`） |
| 宽 | `POS_CLOSE_W`（22）——**不参与压缩**，永远是 22（窗窄时最多缩到 `avail/2`） |
| 高 | **跟数据行高走 = `POS_ROW_H`（16）**；`POS_CLOSE_H`（12）、`POS_CLOSE_PAD_X`（1）、`POS_CLOSE_GAP`（2）**都已失效**，改了没用 |
| 谁建 | `rebuildPositionWidgets` `StockScreen.java:470-493`：**每行一个动态控件**，签名变化才重建（`containerTick` → 下一 tick 执行） |
| 文案 / 提示 | `gui.mcstock.btn.close_row` / `gui.mcstock.btn.close_row.tip` |
| 点击 | `sendClosePosition(id)` → `C2SClosePosition`（`:975`）；客户端只说「点了哪一行」 |
| 想改大小 | 改 `POS_CLOSE_W` 和 `POS_ROW_H`；⚠️ 改 `POS_ROW_H` 会同时改一屏行数与表格总高（见 6.1） |

### 6.5 滚动条（轨道 + 滑块 + 拖动）

| 元素 | 谁算的 / 谁画的 | 公式 |
|---|---|---|
| 轨道 | `posScrollTrack()` `StockGuiLayout.java:579` | `(posList.right - POS_SCROLLBAR_W - 1, header.bottom, POS_SCROLLBAR_W=3, posList.bottom - header.bottom)` |
| 滑块 | `posScrollThumb(firstRow, totalRows)` `:586` | 高 `h = max(8, track.h * 可见行 / 总行)`；`y = track.y + (track.h - h) * min(firstRow, maxFirst) / maxFirst`；不需要滚时返回 **0 高**（不画） |
| 画 | `drawPositions` `StockScreen.java:1914-1921` | 轨道 `COLOR_INSET_FILL`；滑块 `roundedFill(..., 1, COLOR_ACCENT)` |
| 拖动 | `mouseDragged` `StockScreen.java:768-789` | `t = (mouseY - track.y - 抓取偏移) / (track.h - thumb.h)`；`firstRow = round(t * (总行 - 可见行))`；抓取偏移在 `mouseClicked`（`:718-726`）里记 |
| 滚轮 | `mouseScrolled` `:804-820` | 一格 = **一屏**（`scrollPositions`，`:855-865`），只吃 `posList` 区域内的事件 |

---

## 7. 右列：买卖 / 数量杠杆 / 资金情况

### 7.1 买入 · 卖出

| 元素 | 谁算的 / 谁画的 | 公式 | 改位置 | 改大小 | 牵连 |
|---|---|---|---|---|---|
| 买入 | `compute()` `StockGuiLayout.java:935` + 控件 `StockScreen.java:323` | `(rightCol.x, rightCol.y, rightCol.w, BTN_MAIN_H=24)` | 随右列（`RIGHT_COL_W`） | `BTN_MAIN_H` | ⚠️ 高度一变，下面**所有**右列元素整体下移 |
| 卖出 | `:936` | `(rightCol.x, btnBuy.bottom + BTN_GAP=3, rightCol.w, 24)` | 同上 | `BTN_GAP` / `BTN_MAIN_H` | 同上 |
| 底图 | 控件层 `StockButton.renderWidget` `StockWidgets.java:834-838` | 四态 `BTN_NORMAL/HOVER/PRESSED/DISABLED`，横竖都 9 段 | — | 图集切片 | ⚠️ **不要在 `renderBg` 里再画一张常态底图**（会叠两层，悬停时一层不动一层在变） |
| 文案 | `gui.mcstock.btn.buy` / `btn.sell` | 居中，悬停 `COLOR_TEXT`、常态/禁用 `COLOR_MUTED` | — | — | — |
| 平仓 | —— | ⚠️ 右列**没有**全局平仓按钮（已删除），只在表格每行末尾 | — | — | `sendClose()`（`StockScreen.java:964`）目前没有调用者 |

### 7.2 「数量」「杠杆」两行

一行 = 标签 + 填写栏 + ▲ + ▼ + MAX（`compute()` `StockGuiLayout.java:938-951`）：

```text
rowQtyY = btnSell.bottom + BTN_GROUP_GAP(6)
rowLevY = rowQtyY + FIELD_ROW_H(20) + BTN_GAP(3)
标签   (rightCol.x,                     rowY, FIELD_LABEL_W=12, FIELD_ROW_H=20)
填写栏 (rightCol.x + 12 + FIELD_INNER_GAP=2, rowY, FIELD_W=42, 20)
▲      (填写栏右 + 2,                    rowY, FIELD_STEP_W=11, 20)
▼      (▲右,                            rowY, FIELD_STEP_W=11, 20)
MAX    (▼右 + FIELD_INNER_GAP=2,         rowY, FIELD_MAX_W=14, 20)
```

| 项 | 值 | 想改 |
|---|---|---|
| 行高 | `FIELD_ROW_H`（20） | 改它 → 两行一起变；⚠️ 下面资金块起点跟着下移 |
| 两行之间的空隙 | `BTN_GAP`（3） | 改它会影响「买卖间距」和「杠杆与资金块间距」吗？→ **不会**，后者是 `BTN_GROUP_GAP`（6），它同时管「卖出 → 数量行」和「杠杆行 → 资金块」 |
| 标签宽 | `FIELD_LABEL_W`（12） | 只能显示一个多字（「数」/「杠」），放不下会自动裁（`labelClipped`） |
| 填写栏宽 | `FIELD_W`（42）→ 内部可用 32px | 已按「6 位数字必须完整可见」定死；改小会出现「输入 1000 只显示 000」 |
| ▲▼ 宽 | `FIELD_STEP_W`（11） | 图标 9px + 左右各 1px，是下限 |
| MAX 宽 | `FIELD_MAX_W`（14） | 文案是单个「M」（tooltip 里才是完整的「最大可开股数」） |
| 整行总宽 | `FIELD_ROW_TOTAL_W` = 12+2+42+2+11+11+2+14 = **96** | ⚠️ **有类加载断言**：`> RIGHT_COL_W` 时直接抛 `IllegalStateException`（`StockGuiLayout.java:191-199`）。这是全项目**唯一**一处运行时会炸的自检，改宽任何一个字段常量都可能当场炸在启动/开界面时 |
| 填写栏底 | `fieldBox`（`StockWidgets.java:485`）画 `FIELD_TILE` | — |
| 输入控件 | `numberField` `StockWidgets.java:398` | 内缩 `FIELD_TEXT_PAD_X`（6）/ `fieldTextPadY`（(行高-字体行高)/2）；值是**小号点阵字体 + 右对齐 + 放不下从左边截**（`NarrowTextField`，`:433-477`） |
| ▲▼ 档位 | 查档位表 `QTY_STEPS`（`StockScreen.java:91-93`，16 档）、`LEVERAGE_STEPS`（`:102-104`，18 档） | 想改「点一下跳多少」就改这两张表，**不是**改布局常量 |
| 取值上限 | `QTY_MIN/MAX`（1/100000）、`LEVERAGE_MIN/MAX`（1/400） | `StockGuiLayout.java:201-205` |

### 7.3 资金情况块

| 项 | 值 / 公式 | 想改 |
|---|---|---|
| 矩形 | `fundsY = rowLevY + FIELD_ROW_H + BTN_GROUP_GAP`；`fundsBlock = (rightCol.x, fundsY, rightCol.w, max(FUNDS_TITLE_H, rightCol.bottom - fundsY))`（`StockGuiLayout.java:954-956`） | 它**吃掉右列剩下的全部高度**；想给它更多空间只能缩上面的块或 `RIGHT_COL_W`（宽度） |
| 标题 | `(block.x+6, block.y, block.w-12, FUNDS_TITLE_H=12)`，`COLOR_ACCENT`（`StockScreen.java:2577-2579`） | `FUNDS_TITLE_H` |
| 行 | `firstRow = (block.x+6, block.y+FUNDS_TITLE_H, block.w-12, FUNDS_LINE_H=13)`，第 i 行再 `+ i*FUNDS_LINE_H`（`StockScreen.java:2606-2616`） | `FUNDS_LINE_H`；⚠️ 行数**不是常量直接决定**的 |
| 行数上限 | `fundsLines() = max(1, min(FUNDS_LINES=7, (block.h - FUNDS_TITLE_H) / FUNDS_LINE_H))`（`StockGuiLayout.java:523`） | 想「少画几行」改 `FUNDS_LINES`；`FUNDS_MIN_LINES`（2）**已失效**（只在注释里出现，代码没用它）。实测：158px 高 → 7 行；69px → 4 行；12px → 1 行 |
| 七行内容 | 余额 / 净值 / 本单 / 需付 / 可开 / 持仓 / 爆仓（`StockScreen.java:2593-2603`），前三行 `COLOR_TEXT`、其余 `COLOR_MUTED` | 改文案改语言键 `gui.mcstock.funds.*` |
| 底衬 | `drawTradeBg` `StockScreen.java:1380` → `insetBox(fundsBlock, COLOR_PANEL_FILL, COLOR_BORDER)` | 颜色 |

---

## 8. 回收页

### 8.1 纵向顺序与「三档排法」（**这一页会自动换排法，别以为改了没生效**）

```text
（内容区顶部 11px 是 flash 带）
说明行 HINT_H=0       ← ⚠️ 文字已删（StockScreen.java:1695-1698）；高度也已经是 0，**不再占位**
BAR_TOP_GAP=4
回收栏（左，9×18=162 宽 × 1~2 行）      合计块（右，BAR_INFO_W=190）
                                        「回收」按钮（RECYCLE_BTN_W×H = 170×28，可缩到 76）
回收明细（通栏，content.x+4 起，宽 content.w-8，行高 DETAIL_LINE_H=13）
DETAIL_BOTTOM_GAP=4
背包说明占位（INV_LABEL_H=12 ← 文字已删，只剩一条分隔线，StockScreen.java:1344-1346）
背包 27 格（9×3）→ 空 6 → 快捷栏 9 格 → CONTENT_BOTTOM_PAD=8
```

三种排法（枚举在 `StockGuiLayout.java:757-764`，实现在 `planRecycle` `:784-847`）：

| 排法 | 做什么 | 省下多少 |
|---|---|---|
| `NORMAL` | 回收栏 + 合计块（合计块在回收栏右边、按钮在合计块下面）| 0 |
| `MERGED_INFO` | **合计块整块不画**（`barInfo` 变成 0×0，金额并进按钮文案），按钮移到回收栏那一行的右边 | 合计块 34px + 间隔 8px = **42px** |
| `COMPACT` | 同上，另外按钮高度**夹在回收栏高度之内**、与回收栏竖直居中对齐 | 再多省 ≈10px（1 行栏时按钮 28 → 18） |

> ⚠️ `MERGED_INFO` 与 `COMPACT` **都会丢掉合计块**（代码里 `merged = mode != RecycleMode.NORMAL`）；
> `COMPACT` 更省，两者同分时按数组顺序 `COMPACT` 先出现，所以实际总是它赢（`MERGED_INFO` 基本选不中）。

`compute()` 把下面 8 档**全算一遍**，只留下「所有块都完整落在内容区内、互不重叠、明细要么 0 行要么 ≥1 行」的，
再按**分数**挑最高的那一档 —— 不是「第一个装得下就用」（那样会出现「降级档反而比原档更省」的非单调跳变，实测抓到过）：

| 档 | 说明行 | 明细 | 回收栏 | 排法 | 得分 |
|---|---|---|---|---|---|
| 1 | 有（`HINT_H = 0`，恒为 0 高） | 有 | 2 行 | NORMAL | 100 + 10 + 1 |
| 2 | 无 | 有 | 2 行 | NORMAL | 100 + 10 |
| 3 | 无 | 有 | **1 行** | NORMAL | 100 + 10 |
| 4 | 无 | 有 | 1 行 | COMPACT | 100 |
| 5 | 无 | 有 | 1 行 | MERGED_INFO | 100（同分，4 先出现所以轮不到它） |
| 6 | 无 | 无 | 2 行 | NORMAL | 10 + 1 |
| 7 | 无 | 无 | 1 行 | COMPACT | 0 |
| 8 | 无 | 无 | 1 行 | MERGED_INFO | 0 |

评分权重在 `StockGuiLayout.java:1049-1058`：**有明细 +100**、有合计块 +10、回收栏满 2 行 +1；同分取**先出现**的那档。

⚠️ **真实优先级是「明细 > 合计块 > 回收栏 2 行」**，不是「先丢明细」：
为了保住明细，程序宁可把回收栏折成 1 行、把合计块整块丢掉。
硬需求只有三条：回收栏、背包、回收按钮必须完整落在内容区内；块之间不许重叠；明细要么 0 行、要么至少 1 行。

**实测（`layout_dump`，640 宽逐像素扫高度）**：640×**250** 起才同时保得住「2 行栏 + 合计块 + ≥1 行明细」；
640×**249** 及以下立刻掉到「1 行栏 + 无合计块」——差 1px 就整套换掉。

| 逻辑分辨率 | 实际选中的排法 | 明细行数 |
|---|---|---|
| 640×360 | 2 行栏 + 合计块 + 明细 | **7** |
| 640×300 | 2 行栏 + 合计块 + 明细 | 3 |
| 640×250（保住「2 行栏 + 合计块」的下限） | 2 行栏 + 合计块 + 明细 | 1 |
| 640×249 | **1 行栏 + 无合计块**（换来更多明细） | 4 |
| 640×200 | 1 行栏 + 无合计块 + 明细 | 1 |
| 640×180 | 1 行栏 + 无合计块 + **无明细** | 0 |
| 640×120 | 1 行栏 + 无合计块 + 无明细（已到最省的一档） | 0 |
| 854×480 | 2 行栏 + 合计块 + 明细 | 15 |
| 427×240 / 320×240 | 1 行栏 + 无合计块 + 明细 | 4 |

> 结论：**改完回收页的常量必须多跑几个高度**（至少 200 / 240 / 249 / 250 / 300 / 360，或按你的目标分辨率）。
> 高度只差 1px 就可能把整套排法换掉。

### 8.2 回收栏（18 格：到底几行？）

| 问题 | 答案 |
|---|---|
| 回收栏矩形谁算的 | `planRecycle` `StockGuiLayout.java:798`：`bar = (groupX, cursor, BAR_COLS*SLOT=162, barH)`，`barH = (singleRowBar ? 1 : BAR_ROWS) * SLOT` |
| 「两行还是折成一行」由谁决定 | **只由布局试探决定**（上表第 3/4/5 档把 `singleRowBar` 置 true）。它是**矩形高度** |
| 那 18 格槽位呢 | `StockMenu.placeSlots`（`StockMenu.java:150`）→ `slotRect` → `StockGuiLayout.barSlot(layout, index)`（`:1089`），里面 `row = index / BAR_COLS` 是**写死的 2 行** |
| 格子底纹呢 | `drawRecycleBg` `StockScreen.java:1329`：`slotGrid(bar.x, bar.y, BAR_COLS, BAR_ROWS)`，也是**写死的 2 行** |
| 所以？ | **「折成一行」只是把矩形改矮，18 格永远按 2 行摆**。第二行 9 格会落到 `bar` 矩形下方 18px 处 |
| 会不会压到别的东西 | 实测：在 320~1280 宽、120~540 高的全部尺寸里，凡是选中「1 行栏」的档位都**同时把合计块折掉了**，明细从 `bar.y+50` 才开始 —— 第二行格子落在空白里，不会压到明细。⚠️ 但如果以后有人改动试探顺序/合计块高度，让「1 行栏 + 有合计块」这一档被选中，第二行格子就会插进合计块/明细那一带（`barDetail.y` 只比 `bar.y` 低 22px） |
| 想改列数/行数 | `BAR_COLS`（9）/ `BAR_ROWS`（2）；⚠️ 改 `BAR_ROWS` 只影响矩形高度（`SLOT*BAR_ROWS`）与底纹行数，槽位行数由 `barSlot` 的 `index / BAR_COLS` 决定，两者要一起看 |
| 想改位置 | 横向：`groupX = content.x + max(0, (content.w - groupW)/2)`，`groupW = 162 + GROUP_INNER_GAP(20) + BAR_INFO_W(190)`；纵向：`BAR_TOP_GAP`（4）+ 是否保留说明行 |

### 8.3 合计块 / 「回收」按钮

| 元素 | 谁算的 / 谁画的 | 公式 | 想改 |
|---|---|---|---|
| 合计块 | `planRecycle` `:803-812` | `infoX = bar.right + GROUP_INNER_GAP`；`barInfo = (infoX, cursor, BAR_INFO_W=190, infoH)`；`infoH = BAR_INFO_LINES*BAR_INFO_LINE_H + BAR_INFO_BTN_GAP = 2*13+8 = 34` | `BAR_INFO_W`、`BAR_INFO_LINES`、`BAR_INFO_LINE_H`；⚠️ `BAR_INFO_BLOCK_H`（`BAR_INFO_LINES*行高 + 间隔 + 按钮高`，`:326`）是**派生常量**，改上面任何一个都会跟着变 |
| 合计块两行文字 | `drawRecycleText` `StockScreen.java:1705-1713` | 把块按 `max(font.lineHeight, info.h / BAR_INFO_LINES)` 切成两行，各自垂直居中：第 0 行「合计 xx 金币」（`COLOR_ACCENT`）、第 1 行「N 种 · M 组」（`COLOR_TEXT`） | 语言键 `gui.mcstock.recycle.total` / `.kinds` |
| 「回收」按钮 | `planRecycle` `:813-820` + 控件 `StockScreen.java:299` | `btnRightLimit = content.right - CONTENT_PAD`；`btnW = max(RECYCLE_BTN_MIN_W=76, min(RECYCLE_BTN_W=170, btnRightLimit - infoX))`；`y = cursor + infoH + BAR_INFO_BTN_GAP`（NORMAL）或 `cursor`（折进按钮时） | `RECYCLE_BTN_W`（上限）、`RECYCLE_BTN_MIN_W`（下限）、`RECYCLE_BTN_H`、`GROUP_INNER_GAP`、`BAR_INFO_BTN_GAP` |
| 按钮文案 | `recycleButtonLabel` `StockScreen.java:2664-2669` | 合计为 0 → 「回收」；否则「回收 xx.xx」——**金额就在按钮上**，这也是合计块被折掉时不心疼的原因 | 语言键 `gui.mcstock.recycle.button` / `.empty` |
| ⚠️ 折掉合计块时那两行字 | `drawRecycleText` 不看矩形宽高，仍会按 `barInfo`（此时 0×0）画那两行 | 它落在按钮所在的那块区域上；**实机观感以真机为准**（见第 15 节） | 想彻底不画，就在 `drawRecycleText` 里加一句 `if (info.w() <= 0) return;` |

### 8.4 回收明细

| 项 | 公式 |
|---|---|
| 起点 | `detailY = max(bar 下沿, recycleBtn 下沿) + RECYCLE_BTN_DETAIL_GAP(4)`（`StockGuiLayout.java:828`，⚠️ 注释里专门写了：直接沿用 cursor 会让 NORMAL 档的明细压住按钮） |
| 行数 | `rows = keepDetail ? max(0, (invLabel.bottom - DETAIL_BOTTOM_GAP(4) - detailY) / DETAIL_LINE_H(13)) : 0`（`:843`），绘制端用 `detailLines()`（`:655`） |
| 矩形 | `barDetail = (content.x + 4, detailY, content.w - 8, rows*13)`（`:845`） |
| 画 | `drawRecycleText` `StockScreen.java:1718-1752`：内边距 `padX=8`、`padY=3`；左边「名称 ×件数」（`COLOR_MUTED`）、右边件数（`COLOR_TEXT`，`right()` 右对齐）；空栏画「暂无明细」；放不下最后一行改画「还有 N 种」（`:1733-1739`） |
| **0 行时** | 整块（含内嵌屏底）都不画（`StockScreen.java:1340-1342`）——不是 bug，是刻意的优先级 |

### 8.5 背包 + 快捷栏（回收页）

| 元素 | 公式（`planRecycle` `:834-838`） |
|---|---|
| 背包块总高 | `invBlockH = INV_ROWS*SLOT + 6 + SLOT = 78`（背包 54 + 间隔 6 + 快捷栏 18） |
| 背包说明锚点 | `invLabelY = content.bottom - invBlockH - INV_LABEL_H(12)`（`INV_LABEL_H` 必须算进去，否则整块会往下多出 12px、快捷栏越过内容区底边） |
| 背包矩形 | `inv = (content.x + (content.w - 162)/2, invLabel.bottom, 162, 54)` |
| 快捷栏 | `hotbar = (inv.x, inv.bottom + 6, 162, 18)` |
| 说明行的线 | `drawRecycleBg` `StockScreen.java:1346`：`fill(label.x, label.y-3, label.right, label.y-2)`（文字已删，只剩这条分隔线） |
| 想改位置 | 背包永远**钉在内容区底部**：想上移只能改 `CONTENT_BOTTOM_PAD` 或让上面的块变矮 |

### 8.6 收口：`clampToContent`

所有回收页矩形最后统一过一遍 `clampToContent(content, rect)`（`StockGuiLayout.java:678-689`，调用在 `:1019-1026`）：
只平移不缩放，保证「回收栏/背包/按钮」永远完整落在内容区内（实机反馈过它们被顶出面板）。
**行情页和购买页没有这道收口**，极端小窗口下会越界（实测 213×120 时 `chartPanel` 底部 120 > 内容区底部 106）。

---

## 9. 购买页

### 9.1 顶行：分类 / 搜索 / 页码 / 翻页箭头

```java
// StockGuiLayout.java:1030-1036（shopTop = content.y + FLASH_BAND_H）
catBtn    = (content.x,                     shopTop, CAT_BTN_W=84,  FILTER_H=18)
nextBtn   = (content.right - PAGE_BTN_W=18,  shopTop, 18, 18)
pageText  = (nextBtn.x - PAGE_TEXT_W=46 - 2, shopTop, 46, 18)      // 右对齐画「1/9」
prevBtn   = (pageText.x - PAGE_BTN_W - 2,    shopTop, 18, 18)
searchBox = (catBtn.right + 6, shopTop, max(SEARCH_MIN_W=90, prevBtn.x - 6 - (catBtn.right + 6)), 18)
```

| 元素 | 谁画的 | 想改 |
|---|---|---|
| 分类按钮 | 控件 `StockScreen.java:379-381`，文案「分类：全部」并随点击循环（六个键在 `StockScreen.java:107-114`） | `CAT_BTN_W`、`FILTER_H` |
| 搜索框 | 控件 `StockScreen.java:387-401`（不是数字框：filter 被换成「长度 ≤ 32」），底衬 `drawShopBg` `:1437` | 位置：`SEARCH_MIN_W` + 上面的算式；⚠️ 搜索框宽度是「剩下的全给它」，动 `CAT_BTN_W`/`PAGE_*` 都会改它 |
| 页码 | `drawShopText` `StockScreen.java:2621-2626`，居中 | `PAGE_TEXT_W` |
| 翻页箭头 | 控件 `StockScreen.java:382-385`，图标 `ICON_LEFT` / `ICON_RIGHT`（9×8，居中画在按钮里） | `PAGE_BTN_W`、`FILTER_H` |
| 滚轮翻页 | `mouseScrolled` `:810-813`（鼠标必须在网格里） | — |

### 9.2 网格矩形与行数/列数算法

```java
// StockGuiLayout.java:1039-1058
int shopInvBlockH = INV_LABEL_H(12) + 54 + 6 + 18 = 90;
int shopInvY      = content.bottom - shopInvBlockH;          // 背包块钉底
int gridBottomLimit = shopHint.y - 6;                        // shopHint.y == shopInvLabel.y
int gridCols = clamp(content.w / SLOT, GRID_MIN_COLS=8, GRID_MAX_COLS=20);
int fitRows  = max(1, (gridBottomLimit - (shopTop + FILTER_H + GRID_GAP_TOP=6)) / SLOT);
int gridRows = clamp(fitRows, 1, GRID_MAX_ROWS=16);
grid = (content.x + (content.w - gridCols*SLOT)/2, shopTop + FILTER_H + GRID_GAP_TOP,
        gridCols*SLOT, gridRows*SLOT);
pageSize = gridCols * gridRows;                              // 一页几格
```

| 我想改… | 动哪个 | 说明 |
|---|---|---|
| 一行几个 | 不用改常量 | 由 `content.w / SLOT` 自动算，夹在 `GRID_MIN_COLS(8) ~ GRID_MAX_COLS(20)`。想固定列数就把 `:1053` 那一行换成常数 |
| 一屏几行 | `GRID_MAX_ROWS`（16，防超长屏铺上百行）/ `GRID_GAP_TOP` / 背包块位置 | `fitRows` 吃掉「分类行到背包之间」的全部高度 |
| ⚠️ 行数下限 | **代码里是 1，不是 4** | `GRID_MIN_ROWS = 4`（`StockGuiLayout.java:400`）**没有被使用**；实测 427×240 → 3 行、213×120 → 1 行。`AGENTS.md` 里写的「`GRID_MIN_ROWS` 保底 4 行」与当前代码不符；想真的保底 4 行，把 `:1055` 的 `clamp(fitRows, 1, GRID_MAX_ROWS)` 改成 `clamp(fitRows, GRID_MIN_ROWS, GRID_MAX_ROWS)` |
| 格子大小 | `SLOT`（18） | 改它等于全局改，槽位、回收栏、网格一起变 |
| 网格与背包之间的那条提示 | `shopHint` 高度 = `SHOP_HINT_MIN_H = 0` | ⚠️ 文字已删、高度 0，但**它还占着一个 y 坐标**（`gridBottomLimit = shopHint.y - 6`），所以它仍然决定网格能长到哪；`SHOP_HINT_H`（12）已失效 |

### 9.3 物品格 / 组数角标 / tooltip

| 元素 | 谁画的 | 说明 |
|---|---|---|
| 格子底纹 | `drawShopBg` `StockScreen.java:1435`：`slotGrid(grid.x, grid.y, gridCols, gridRows)` | 跟网格矩形走 |
| 物品图标 | `:1455`：`renderItem(stack, cell.x + 1, cell.y + 1)` | 用 `gridCell(l, col, row)` 取格（格子边框坐标，不是 Slot 坐标） |
| 组数角标 | `:1456-1460`：一组几个画在格子右下（`renderItemDecorations`） | 表示「左键买一组会买多少」 |
| 点击 / 悬停换算 | `absoluteShopIndex(col,row)`（`StockScreen.java:887-893`）= `shopPage*pageSize + row*gridCols + col`；点击 `shopIndexAt` `:901-916`、悬停 `startIndexOnGrid` `:2649-2655`、绘制 `:1449` **三处都走同一个入口** | ⚠️ 历史上这里有三份实现，漏了一份 → 「悬停显示第 2 页的钻石、点下去买到第 1 页的泥土」。**改换算只改 `absoluteShopIndex`** |
| 点击行为 | `mouseClicked` `:727-752`：左键买 1 个、**Shift+左键买 1 组**、右键也买 1 个；⚠️ 网格判定必须在 `super.mouseClicked` **之前** | — |
| tooltip | `drawShopText` `:2633-2645`：物品名 + 单价 + 一组价 | — |

### 9.4 底部背包 / 快捷栏（购买页）

`shopInvLabel / shopInv / shopHotbar`（`StockGuiLayout.java:1043-1045`）与回收页同款，只是整体钉在内容区底部：
`shopInv = (居中, label.bottom, 162, 54)`、`shopHotbar = (inv.x, inv.bottom+6, 162, 18)`。
槽位由 `StockMenu.slotRect` 摆（购买页用 `shopInvSlot` / `shopHotbarSlot`，`StockGuiLayout.java:1113-1122`）。

---

## 10. 帮助面板（覆盖层）

| 元素 | 谁算的 / 谁画的 | 公式 / 值 |
|---|---|---|
| 覆盖层矩形 | `drawHelpOverlay` `StockScreen.java:579` | `box = l.content()`（**就是内容区**，标题栏与页签保持可见可点） |
| 底衬 + 边框 | `:585-589` | 实心 `COLOR_PANEL_FILL` 铺满 + 四边各 1px `COLOR_ACCENT` |
| 标题行 | `:591-594` | `(box.x + 8, box.y + 2, box.w - 16, HELP_LINE_H=11)`，`COLOR_ACCENT` |
| 正文起点 / 行高 | `:597-598` | `top = 标题.bottom + 2`；`rowH = HELP_LINE_H`（11） |
| 可视行数 | `:600-602` | `bottom = box.bottom - 2 - rowH`（底部给页码留一行）；`rows = max(0, (bottom - top) / rowH)` |
| 左右内边距 | `HELP_PAD_X`（8，`StockScreen.java:658`） | `textX = box.x + 8` |
| **折行宽度** | 绘制 `:605` 用 `box.w - HELP_PAD_X*2`；折行计算 `helpLines()` `:638` 用 `layout.content().w - HELP_PAD_X*2`（`max(40, ...)`） | 两处**都是「内容区宽 - 16」**，改 `CONTENT_PAD` 会让它们一起变 |
| 硬裁剪 | `:608` | `enableScissor(box.x+1, top, box.right-1, box.bottom-1)` —— 折行算错也不会越出面板 |
| 页码行 | `:620-626` | `(textX, box.bottom - rowH - 1, textW, rowH)`，右对齐；只在内容比可视区多时画（`gui.mcstock.help.more`） |
| 正文内容 | `helpLines()` `:636-655`：键 `gui.mcstock.help.1` … `gui.mcstock.help.{HELP_LINES}`（20 条）；键不存在（原样回显）时跳过；**按字符宽度折行**（中文没有空格可断） | 加/减条数要同时改 `HELP_LINES`（`StockGuiLayout.java:386`）与两份 lang |
| 其它控件怎么办 | `applyHelpVisibility()` `:564-570`：打开时隐藏所有 `AbstractWidget`（帮助按钮自己不隐藏）；`render()` `:1269-1275` 直接早退，**只画覆盖层** | ⚠️ 光盖一层绘制挡不住控件（控件是独立的事件接收者，会点到看不见的按钮） |
| 交互 | ESC 关覆盖层、上下键/滚轮翻页（`:829-847`、`:805-809`） | 打开时其它按键一律吃掉 |

---

## 11. 主题色（`StockTheme.java`）

**只有这一个文件写死颜色**（`AGENTS.md` 4.5）；绘制代码一律用这些常量。

| 常量 | 值 (AARRGGBB) | 用途（代码里实际用在哪） | 改它会影响 |
|---|---|---|---|
| `COLOR_UP` | `0xFFE06A62` 珊瑚红 | **涨 / 盈利**：阳线柱、多仓方向字、正盈亏、涨跌箭头、现价虚线（那一根是阳线时） | 全界面所有「涨」 |
| `COLOR_DOWN` | `0xFF6FBF7A` 草绿 | **跌 / 亏损**：阴线柱、空仓方向字、负盈亏 | 全界面所有「跌」 |
| `COLOR_MUTED` | `0xFFA6ACBA` | 次要文字：单位、风险标签、列名、未选中的卡片名、▲▼/MAX 文案常态、帮助页码、刻度数字、明细左侧 | 大面积，改深/改浅影响可读性 |
| `COLOR_TEXT` | `0xFFEDEFF5` | 主文字：标题、余额净值、现价、选中卡片名、明细右侧件数、表格数字列、按钮悬停色、输入框文字与光标 | 同上 |
| `COLOR_TEXT_SHADOW` | `0xFF262932` | ⚠️ **代码里没有任何地方用它**（所有文字走 `drawString(..., shadow=true)`，阴影色由原版自己算）。它只出现在 `layout_dump` 输出和**效果图**里 → 改它效果图会变、游戏里不会变 | 仅 `docs/` 的图 |
| `COLOR_CHART_BASELINE` | `0xFF6B7285` | ⚠️ **同样没有被代码使用**（图的网格线用的是 `COLOR_GRID_LINE`） | 仅 `docs/` 的图 |
| `COLOR_LIQUIDATION` | `0xFFF2706B` | 爆仓：爆仓虚线、爆仓价签、距爆仓那一列、边缘指示三角 | 风险相关的全部 |
| `COLOR_ACCENT` | `0xFFE6C879` 金 | 强调：帮助面板边框/标题、合计金额、资金块标题、flash 文字、滚动条滑块、卡片选中描边（贴图）、金币图标 | 视觉重心 |
| `COLOR_PANEL_FILL` | `0xFF23262E` | 面板底色、帮助覆盖层底、持仓行悬停高亮、资金块底 | 整体明暗 |
| `COLOR_INSET_FILL` | `0xFF191B22` | 内嵌屏底：绘图区、持仓表格、回收明细、合计块、滚动条轨道、**柱体圆角补色** | 内嵌块观感 |
| `COLOR_GRID_LINE` | `0xFF2C313C` | 图的 2×2 网格线、持仓表格行分隔线 | — |
| `COLOR_BORDER` | `0xFF474E60` | 1px 描边：内嵌屏边框、柱体描边与影线、持仓表格行底线、回收栏上方那条分隔线 | 硬边强度 |

**想换成欧美习惯（绿涨红跌）**：把 `COLOR_UP` 与 `COLOR_DOWN` 两个值对调即可（代码里没有第二处写死涨跌色）。

---

## 12. 改完怎么预览

### 12.1 只改布局、不启动游戏（**最常用**）

```powershell
cd E:\mc_stock
python tools\layout_dump.py 640 360
```

- 它用 `javac` 单独编译 `StockGuiLayout.java` + `tools/java/LayoutDump.java`（**所以布局引擎不许 import 任何 Minecraft 类**），
  再跑 `compute(640,360)` 打印矩形。首次跑会编译到 `tools/_layout_build/`，之后读缓存。
- 现在打印的是**摘要**（panel / titleBar / content / leftCol / chartPanel / chartPlot / rightCol / fundsBlock / bar / grid + candleCount + pageSize）。
  想看别的矩形，直接调 `dump()`：

```powershell
python -c "import sys;sys.path.insert(0,'tools');from layout_dump import dump;d=dump(640,360);L=d['layout'];print(L['posList'],L['posCol0'],L['posCloseBtn0'],L['barDetail'],L['detailLines'])"
```

- **两条等式自检**（把下面的宽高换成你正在调的尺寸）：

```powershell
python -c "import sys;sys.path.insert(0,'tools');from layout_dump import dump;d=dump(640,360);L=d['layout'];c=d['const'];print('chartPanel.y=',L['chartPanel'][1],' 页签下沿=',L['tabs'][0][1]+L['tabs'][0][3]);print('posList.y=',L['posList'][1],' chartPanel.bottom+POS_GAP=',L['chartPanel'][1]+L['chartPanel'][3]+c['POS_GAP'])"
```

  实测（640×360）：`chartPanel.y = 55 = 页签下沿`；`posList.y = 221 = chartPanel.bottom + 4`。
  ⚠️ 这两条**只在注释里约定，没有断言兜底**（见第 13 节第 2 条）。

### 12.2 生成效果图（用美术脚本预览，最直观）

```powershell
python tools\gen_gui_mockup.py 640 360 试改1
```

- 产出：`docs/gui_mockup_recycle.png` / `gui_mockup_trade.png` / `gui_mockup_shop.png`（**固定路径，会被覆盖**）
  **外加** 三张带标签的评审副本 `docs/review/试改1_gui_mockup_recycle.png`、`..._trade.png`、`..._shop.png`（2 倍放大，1280×720）。
  ⚠️ 文件名是 **`<标签>_gui_mockup_<页>.png`**（标签是前缀，不是 `<标签>_<页>.png`）。
- 它读的是**同一份 `compute()` 结果**（走 `layout_dump`），所以效果图里的位置和游戏里一致；图集用 `src/main/resources/assets/mcstock/textures/gui/stock_market.png`。
- 评审时请贴**带标签**的新路径，别贴固定路径（聊天窗口/图床会按路径缓存老图，`AGENTS.md` 6.8）。
- 本机实测：`exit 0`，三张图正常写出（这条命令**目前是好的**，见第 13 节第 11 条）。

### 12.3 看游戏里的真实效果

```powershell
.\gradlew.bat runClient      # 单机进世界，手持「交易所终端」右键开界面
```

进游戏后随手把 GUI 缩放改一改（设置 → 视频设置 → GUI 缩放），就能看到同一份布局在别的逻辑分辨率下的样子 —— 这是唯一能确认「字号/中文/图标是不是真的放得下」的方法。
接口类改动（布局、贴图、字体）**光看效果图不算验收**：`AGENTS.md` 7.6 那张表里的四个事故全都「通过了当时所有脚本」，只有真机截图能看见。

### 12.4 什么时候必须重跑哪个脚本

| 你改了什么 | 必须跑 | 不跑会怎样 |
|---|---|---|
| 任何布局常量（宽高/间距/位置） | `python tools\layout_dump.py <宽> <高>`（自检）+ `python tools\gen_gui_mockup.py 640 360 <标签>`（回给对方看） | 效果图和文档还是旧布局，评审看的不是你改的东西 |
| 圆角半径 `RADIUS_PANEL/BOX/SLOT` | `python tools\gen_gui_textures.py` | ⚠️ 圆角是**贴图里的透明像素**，代码只负责摆位置：不重跑，圆角大小不变（只是切片被拉伸） |
| 图集里任何切片的大小/位置（`Sprite` 常量、`ATLAS_W/H`、卡片/按钮/页签切片） | `python tools\gen_gui_textures.py` | 代码取的坐标和贴图上画的对不上 → 实机「按钮里叠三段颜色」那种错位 |
| 顺手重跑了 `gen_gui_textures.py` | **再跑一次** `python tools\patch_close_help_sprites.py` | 图集里 `CLOSE_NORMAL/HOVER` 会被重画成 LANCZOS 版（叉被冲淡成幽灵圈），`ICON_HELP` 的「?」也不在生成脚本里 |
| 小号数字字体（字号、字形、字符集） | `python tools\gen_small_font.py` | `small.json` 与 `small.png` 不同步 → provider 按错误宽度切片、整行错位（有 `SmallFontAssetTest` 兜底） |
| 任何语言键（新增/删除/改名） | `python tools\check_lang_keys.py` | 键差集不为空（退出码 ≠ 0）。实测当前 **130 个键，差集为空** |
| 改了「价格/数量/名字」等文案 | 同上 | 同上；两份 lang 的键集合也必须一致 |
| 所有改动收尾 | `.\gradlew.bat build` | 编译 + 55 个单测（纯 Java，不启动游戏） |

> `python tools\gen_gui_spec.py`（生成给美术的规格书 `docs/gui_spec.md`）本机实测也能跑（`exit 0`）。
> 它和 `gen_gui_mockup.py` 都吃 `layout_dump` 的 JSON，所以**新增矩形时要去 `tools/java/LayoutDump.java` 里补一行 dump**，否则文档和效果图里看不到它（`AGENTS.md` 5.3）。
> ⚠️ 已知漏项：`ICON_HELP`（帮助按钮的「?」，`StockGuiLayout.java:466`）**没有**进 `LayoutDump`，所以规格书/效果图里看不到它（图集里它由 `patch_close_help_sprites.py` 直接画在 (76,30)）。

---

## 13. 常见坑（改 UI 时它们会怎么坑到你）

1. **`Slot#x/y` 是 `public final`，槽位位置只能靠「换对象」改**
   容器槽（回收栏 18 格、背包 36 格）的位置不在布局里直接写，而是 `StockScreen.init()` 把 `Layout` 交给 `StockMenu.placeSlots`（`StockMenu.java:150`），
   由它**造一个新 `Slot` 顶掉列表里那一项**（`Slot#x/y` 改不了，也没有 setter；原版读的就是这两个字段）。
   → 你改 `bar`/`inv`/`hotbar`/`shopInv` 的矩形，槽位会跟着走；但**如果你在 `compute()` 里新加了一个「放物品的矩形」，槽位不会自动出现**，得在 `StockMenu.slotRect`（`StockMenu.java:181`）里加一条。

2. **「自检」只有一条真的会报错，其余是算式约定**
   - 会当场抛异常：`FIELD_ROW_TOTAL_W > RIGHT_COL_W` → `IllegalStateException`（`StockGuiLayout.java:191-199`，类加载时）。改填写栏/标签/▲▼/MAX 任何一个宽度都可能炸在开界面那一刻。
   - **不会报错**：`chartPanel.y == 页签下沿`、`posList.y >= chartPanel.bottom + POS_GAP` 只是注释里的约定（`StockGuiLayout.java:53-60`、`:915`），
     代码里没有断言，`layout_dump.py` 也只打印、不判定。**改错不会报错，只会画歪** → 请照 12.1 的两条等式自己核。
   - 回收页有第三道保险：`RecyclePlan.fits()`（`:707-719`）会**丢掉装不下的块**（说明/合计块/明细）或换排法，`clampToContent`（`:678`）再把所有块夹进内容区。

3. **回收页会「自动丢弃 + 自动折行」，别以为改了没生效**
   装不下的块会被静默丢掉（宽高变 0），回收栏可能从 2 行折成 1 行，排法可能从「合计块三行」变成「金额并进按钮」。
   实测 640×350 是「无说明/2 行栏/有合计块/**无明细**」，而更矮的 640×330 反而「有说明/2 行栏/有合计块/4 行明细」——**非单调**。
   → 改完至少抽查 5 个高度（见 8.1 的表），并确认你改的那一块**真的还在**。

4. **文字必须走 `StockWidgets` 的 `label / text / line / right`，直接 `drawString` 会对不齐**
   这四个 helper 都做了**矩形内垂直居中**：`y = rect.y + (rect.h - font.lineHeight)/2`（`StockWidgets.java:561`）。
   原版 `drawString` 的 y 是**字模盒顶端**、行高是 `font.lineHeight`（中文 Unifont 常见 9），拿 `rect.y()` 直接画会整体偏上 1~3px（「字贴着框上沿、下面空一截」，实机踩过）。
   逐行排多行文字用 `line(...)`（自己给 y），右对齐用 `right(...)`，超宽裁尾用 `labelClipped(...)`。

5. **小号数字字体（`mcstock:small`）只能给数字/符号用，中文套上去就是方块**
   MC 在指定字体里找不到字形时画的是 `SpecialGlyphs.MISSING`（缺字方块），**不会回退默认字体**。
   数字走 `StockWidgets.small(...)` / `smallText(...)`（6px 高、1:1、每字符 4px）；中文（企业名、多/空、均、列名）一律保持常规字号。
   ⚠️ 也**不要**用 `pose().scale()` 缩中文：MC 文字是图集 + UV，缩小 = UV 采样，点阵必然掉笔画。
   实例：倍数列原来用 `×`（U+00D7），小字体图集里缺这个字形 → 显示成 `▯1`，现在改用 ASCII `x`（`StockScreen.java:1972-1975`）。

6. **改字号要三处同步**
   `tools/gen_small_font.py` 的 `CELL_W=3 / CELL_H=6 / CHARS`（`:63-71`）→ 生成 `small.json`（`height`/`ascent` = 6）与 `textures/font/small.png`；
   而 `StockWidgets.SMALL_ADVANCE_PX = 4`（`StockWidgets.java:647`）是**代码里写死的步进**，它与 `small.json` 的 advance 必须一致（填写栏的光标位置、右对齐都按它算）。
   只改一处 → 字宽对不上、光标错位；`SmallFontAssetTest` 会把 json 与图集对不上的情况当场照出来。

7. **贴图脚本会覆盖手工改动**
   `gen_gui_textures.py` 是「按布局常量程序化重画整张图集」，它重画的 `CLOSE_NORMAL/HOVER` 是 LANCZOS 版（叉被冲淡成幽灵圈，实机反馈过「关闭处只有一个圈，没有叉」）。
   → 重跑它之后**必须再跑** `patch_close_help_sprites.py`（硬边重画叉 + 补 `ICON_HELP` 的「?」）。
   同理：美术给了成品贴图之后，别随手跑 `gen_gui_textures.py`（会把成品盖回占位图）。

8. **关闭按钮的叉必须在「控件层」画，不能在 `renderBg` 里画**
   绘制顺序是 `renderBg（手绘：卡片/图表/槽位底）→ renderSlots（容器槽）→ renderLabels（文字）→ super.render（控件）→ tooltip`。
   在 `drawChrome`（`renderBg` 里）画的东西会被随后画的 `StockButton` 皮肤整个盖住。现在叉走 `StockButton` 的 `icon` 通路（皮肤之后画，`StockWidgets.java:833-846`）。

9. **帮助覆盖层必须在 `render(...)` 整体早退**
   只在 `renderBg` 早退挡不住「容器槽 + 控件」两层（实机截图里买卖按钮、输入框照样可见）。
   现在的做法：`render()` 里 `helpOpen` 就直接 `drawHelpOverlay` 并 return（`StockScreen.java:1269`）+ `applyHelpVisibility()` 把控件全隐藏（`:564`）——
   **隐藏是必要的**，控件是独立的事件接收者，光盖住会点到看不见的买入/卖出/页签。

10. **「屏幕局部下标 → 全局列表下标」只允许一份实现**（购买页）
    网格的点击/悬停/绘制三条路径**都必须调 `absoluteShopIndex`**（`StockScreen.java:887`）。
    历史上它们各写一份、其中一份漏了页偏移 → 「悬停显示第 2 页的钻石、点下去买的是第 1 页同位置的泥土」。

11. **`AGENTS.md` 第 6 节第 13/22 条说「`gen_gui_spec.py` / `gen_gui_mockup.py` 已损坏（`KeyError: chartFooter1`）」——这条已经过时**
    本机实测（2026 年、当前代码）：两个脚本**都不再引用** `chartFooter`，`gen_gui_spec.py` 与 `gen_gui_mockup.py 640 360 试改1` 都是 `exit 0` 并正常写出文件。
    可以放心按 12.2 的流程用。⚠️ 但 `chartFooter1/chartFooter2` 这两个**常量**还留在 `StockGuiLayout.java:110-112`（标了 `@Deprecated`），`LayoutDump.java:78-79` 还在 dump 它们 —— 删常量要连 dump 一起删。

12. **改动收尾跑一遍 `.\gradlew.bat build`**
    编译 + 55 个单测（纯 Java，不启动游戏）。布局类改动不会让单测变红（单测不依赖 Minecraft 类、也不测布局），
    但它会拦住编译错误和 `SmallFontAssetTest`（字体 json ↔ 图集对不上）这类事故。

---

## 14. 这些常量**已经失效**，改它们不会有效果（别浪费时间）

下面这些常量还在 `StockGuiLayout` 里、还能编译、还会出现在 `layout_dump` 的 JSON 里，**但没有任何绘制/布局代码使用它们**
（多是从旧版界面留下的）。想让它们生效，得先改回代码里用起来。

| 常量 | 行号 | 现状 |
|---|---|---|
| `CHART_PAD` | `:91` | 走势图外框内边距，现在用 `CHART_INNER_PAD`（4） |
| `CHART_HEADER_H` | `:103` | 表头两块的真实高度是 `2*CHART_ROW_H + CHART_HEADER_GAP` = 24 |
| `CHART_FOOTER1_H` / `CHART_FOOTER2_H` | `:110`、`:112` | 旧的「图下持仓条」两行，已删（保留常量只为兼容 `LayoutDump`） |
| `FOOTER_TOP_GAP` / `FOOTER_GAP` | `:114`、`:116` | 同上，没人用 |
| `BTN_CLOSE_H` | `:145` | 旧的右列「平仓」按钮高度；右列平仓按钮已删，表格里的「平」按钮高度 = `POS_ROW_H` |
| `FUNDS_MIN_LINES` | `:219` | 资金块的实际行数由 `fundsLines()` 算（`FUNDS_LINES` 是上限） |
| `POS_LINE1_DY` / `POS_LINE2_DY` | `:243-244` | 旧的「一行两行文字」持仓列表；现在是一行六列 |
| `POS_CLOSE_H` | `:247` | 「平」按钮高度跟 `POS_ROW_H` 走 |
| `POS_CLOSE_PAD_X` / `POS_CLOSE_GAP` | `:249-250` | 列位置的间隙现在由 `COL_GAP` 与 `posCol()` 决定 |
| `POS_MIN_ROWS` / `POS_MAX_ROWS` | `:256-257` | 表格高度的真实上下限是 `POS_TABLE_MIN_H` / `POS_TABLE_MAX_H` |
| `SHOP_HINT_H` | `:383` | 购买页提示行已删，真实高度是 `SHOP_HINT_MIN_H`（0） |
| `GRID_MIN_ROWS` | `:400` | 网格行数下限**实际上是 1**（`:1055` 的 clamp 写的是 1），见 9.2 |
| `COLOR_TEXT_SHADOW` | `StockTheme.java:31` | 代码没引用（阴影由原版算），只影响效果图 |
| `COLOR_CHART_BASELINE` | `StockTheme.java:33` | 代码没引用（网格线用 `COLOR_GRID_LINE`），只影响效果图 |

---

## 15. 我不确定 / 需要实机确认的地方

1. **「合计块被折掉」时那两行字会不会露出来**：布局层把 `barInfo` 变成 0×0（`planRecycle` `:806-812`），而 `drawRecycleText`（`StockScreen.java:1705-1713`）**不看宽高**，仍会按那个矩形画「合计 xx / N 种 · M 组」。
   它的坐标正好落在「回收」按钮所在的那块区域上。绘制顺序上控件在文字之后画（`renderLabels` → `super.render`），所以**大概率被按钮盖住、观感就是「合计块消失了」**；
   但我没有实机截图确认（`docs/review/` 里的老图是旧版界面，不能作依据）。要绝对干净就在 `drawRecycleText` 里加 `if (info.w() <= 0) return;`。
2. **`StockGuiLayout.java:351-372` 那段 `MIN_PANEL_HEIGHT` 的推导注释是旧值**：那段注释本身已经订正过一轮，
   但**别拿旧文档里抄的数字**当准 —— 当前常量的实际值是 `18 + 0 + 16 + 0 + 200 + 8` → **`MIN_PANEL_HEIGHT = 242`**
   （`layout_dump` 实测 `const.MIN_PANEL_HEIGHT = 242`、`const.MIN_CONTENT_HEIGHT = 200`）。
   改回收页常量时以 `layout_dump` 打出来的 `MIN_PANEL_HEIGHT` 为准。
3. **页签「高亮」实机只剩 `BTN_*` 皮肤**（第 3.3 节的实测推导：`TAB_ACTIVE` 被同矩形的不透明按钮皮肤盖住，三个页签未悬停时外观相同）。
   这条是从图集 alpha（中心 255）+ 绘制层级推出来的，**建议实机对着页签看一眼**再决定要不要改（改法见 3.3）。
4. **`Layout.candleCount`（640 屏 = 51）与实际画的柱子数（45）不一致**：渲染端自己算 `柱子区.w / CANDLE_PX`，`candleCount` 只出现在 dump/规格书里。
   我确认它没被 `StockScreen` 使用；如果哪天有人拿它当「一屏多少根」的依据，就会对不上。
5. **`docs/review/` 里的历史截图已经和当前界面不一致**（例如 `live3_recycle.png` 还带着「同类的会一起卖」「回收栏是空的…」「背包（shift 点击 → 进回收栏）」这些**已经从代码里删掉的文字**）。
   判断当前界面请以 `layout_dump` 输出 + 新跑的效果图 + 实机为准。
6. **极端小窗口**（逻辑分辨率 ~200×120 那种，只有 854×480 + GUI 缩放 4 才会出现）：行情页没有 `clampToContent` 收口，实测 `chartPanel` 底部会越过内容区底边（120 > 106），
   持仓表格则整块不画（高度 0）。这种尺寸下界面上一定不好看，**要不要专门处理取决于你要不要支持它**。

---

### 附：常用命令速查

```powershell
cd E:\mc_stock
python tools\layout_dump.py 640 360                    # 布局矩形摘要（不启动游戏）
python tools\gen_gui_mockup.py 640 360 试改1            # 三张效果图 + docs/review/试改1_gui_mockup_*.png
python tools\gen_gui_spec.py                           # docs/gui_spec.md（给美术的规格书）
python tools\gen_gui_textures.py                       # 图集/物品图标/logo/docs/gui_atlas_map.png
python tools\patch_close_help_sprites.py               # 重跑图集后补清晰的 ✕ 与 ?
python tools\gen_small_font.py                         # small.json + textures/font/small.png
python tools\check_lang_keys.py                        # 语言键差集必须为空（退出码 0）
.\gradlew.bat build                                    # 编译 + 55 个单测
.\gradlew.bat runClient                                # 进游戏看真实效果
```
