"""生成 docs/gui_spec.md —— 给美术的规格书（比例、锚点、分区、颜色）。

⚠️ 生成的文件不要手改：改布局请改 StockGuiLayout.java，然后重跑本脚本。

用法：python tools/gen_gui_spec.py [screenW screenH]
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gui_layout import ROOT, argb  # noqa: E402
from layout_dump import DEFAULT_SCREEN, dump  # noqa: E402

SCREEN = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) > 2 else DEFAULT_SCREEN
DATA = dump(*SCREEN)
C = DATA["const"]
L = DATA["layout"]
S = DATA["sprites"]
COL = DATA["colors"]
OUT = ROOT / "docs/gui_spec.md"

SPRITE_PURPOSE = {
    "FRAME_TL": "面板边框左上角（8×8，1:1 画）",
    "FRAME_TOP": "面板上边框（8×8，**横向拉伸**到面板宽度）",
    "FRAME_TR": "面板边框右上角",
    "FRAME_LEFT": "面板左边框（8×8，**纵向拉伸**到面板高度）",
    "FRAME_CENTER": "面板内部底纹（颜色请与 COLOR_PANEL_FILL 对齐；代码用纯色填充，不铺这张）",
    "FRAME_RIGHT": "面板右边框",
    "FRAME_BL": "面板边框左下角",
    "FRAME_BOTTOM": "面板下边框（横向拉伸）",
    "FRAME_BR": "面板边框右下角",
    "TITLE_TILE": "标题栏底纹（8×18，横向拉伸；底下带一条 1px 分隔线）",
    "ICON_UP": "涨 / 阳线方向箭头",
    "ICON_DOWN": "跌 / 阴线方向箭头",
    "ICON_LEFT": "上一页",
    "ICON_RIGHT": "下一页",
    "ICON_COIN": "货币图标",
    "CLOSE_NORMAL": "关闭按钮常态（12×12）",
    "CLOSE_HOVER": "关闭按钮悬停",
    "SLOT_TILE": "槽位（18×18，按格子数量 1:1 铺；圆角 3）",
    "FIELD_TILE": "填写栏底（48×20，水平 9 段拉伸；圆角 4）。搜索框、数量/杠杆填写栏共用",
    "CARD_NORMAL": "行情页左列企业卡片·常态（76×44，1:1，圆角 4）",
    "CARD_HOVER": "企业卡片·悬停",
    "CARD_SELECTED": "企业卡片·选中（左边带一条强调竖条）",
    "BTN_NORMAL": "按钮常态（72×16，左右各 4px 不拉伸，中间拉伸；圆角 4 正好落在左右各 4px 里）",
    "BTN_HOVER": "按钮悬停",
    "BTN_PRESSED": "按钮按下",
    "BTN_DISABLED": "按钮禁用（钱不够 / 没持仓 / 无可回收物）",
    "TAB_ACTIVE": "当前页签（64×16，同样左右 4px 不拉伸）",
    "TAB_INACTIVE": "其余页签",
}


def r(rect) -> str:
    return f"{rect[0]}, {rect[1]}"


def wh(rect) -> str:
    return f"{rect[2]} × {rect[3]}"


def table(rows) -> str:
    head = "| 元素 | x, y | w × h | 说明 |\n|---|---|---|---|\n"
    return head + "\n".join(f"| {a} | {b} | {c} | {d} |" for a, b, c, d in rows) + "\n"


def main() -> None:
    p = []
    p.append("# MC 股市 —— GUI 规格（美术交付用）\n")
    p.append(
        "> ⚠️ 本文件由 `tools/gen_gui_spec.py` 跑真实布局代码生成，**不要手改**。\n"
        "> 改布局 = 改 `src/main/java/com/qiushaonan/mcstock/client/StockGuiLayout.java`"
        "（或 `StockTheme.java` 的颜色）→ 重跑 `tools/gen_gui_spec.py`、`gen_gui_textures.py`、`gen_gui_mockup.py`。\n"
    )

    p.append("## 1. 最重要的一条：窗口随游戏窗口变化\n")
    p.append(
        f"- 面板尺寸 = **屏幕逻辑尺寸 × {C['PANEL_W_RATIO']:.0%}**（宽高各算一次），"
        f"夹在 **{C['PANEL_MIN_W']}×{C['PANEL_MIN_H']} ~ {C['PANEL_MAX_W']}×{C['PANEL_MAX_H']}** 之间，"
        f"并至少离屏幕边缘 {C['PANEL_SCREEN_MARGIN']}px；面板水平垂直居中。\n"
        "- ⚠️ **所以贴图纸里没有「整窗底图」**：面板由 9 张 8×8 边框切片拼出来，槽位/卡片/按钮/页签都是可拼可拉的小块。\n"
        "- 上下限是必要的：GUI 缩放调到 1 时逻辑分辨率有 1920×1080，按比例算出来的面板会是一块巨大的空板子，"
        "而字号、槽位、物品图标都不会跟着变大。\n"
        f"- 参考：1080p + 原版自动 GUI 缩放（3 倍）→ 逻辑 {DEFAULT_SCREEN[0]}×{DEFAULT_SCREEN[1]} → "
        f"面板 {L['panel'][2]}×{L['panel'][3]}。\n"
    )

    p.append("## 2. 本次生成的实例（屏幕 %d×%d）\n" % (L["screenW"], L["screenH"]))
    p.append(f"- 面板：`{r(L['panel'])}`，{wh(L['panel'])}；内容区 `{r(L['content'])}`，{wh(L['content'])}。")
    p.append(f"- 阴阳柱根数：**{L['candleCount']}** 根（每根 ≈ {C['CANDLE_PX']}px 宽）；购买页每页 **{L['pageSize']}** 格"
             f"（{L['gridCols']} × {L['gridRows']}）。")
    p.append(f"- 小屏幕上「资金情况」只画得下 {L['fundsLines']} 行、回收明细 {L['detailLines']} 行（代码按可用高度自动裁剪）。\n")
    p.append("下面表格里的数字都是**这个屏幕尺寸下的实例**；布局公式见第 3 节。\n")

    p.append("## 2.5 柔和观感：圆角与配色\n")
    p.append(
        f"- **圆角半径**：面板 `{C['RADIUS_PANEL']}`、卡片/按钮/页签/填写栏 `{C['RADIUS_BOX']}`、槽位 `{C['RADIUS_SLOT']}`。\n"
        f"  这些数字在 `StockGuiLayout.RADIUS_*`，**必须和贴图里画的圆角一致**（圆角是贴图的透明像素，代码只负责摆位置）。\n"
        "- 面板不是整张底图：四角 8×8 切片**只圆外侧那个角**，另外三个角是直角（靠「把圆角矩形画出边界再裁」实现），"
        "所以任意宽高拼出来都是圆角窗口。\n"
        "- 按钮/页签的圆角半径 = 左右不拉伸的 4px，所以拉伸中间段不会破坏圆角。\n"
        "- 配色走低对比路线：底色比纯黑浅一档、描边比底色只深一点、涨跌色降饱和。"
        "⚠️ **涨跌仍然是 A 股习惯：红涨绿跌**。\n"
        "- 文字本身是原版点阵字体（中文 Unifont），**没法在模组里换圆体**：见 AGENTS.md 第 6 节第 10 条的取舍说明。\n"
    )

    p.append("## 3. 布局公式（相对锚点）\n")
    p.append("```text\n"
             f"panel        = 居中，宽 = 屏宽×{C['PANEL_W_RATIO']} 高 = 屏高×{C['PANEL_H_RATIO']}，"
             f"夹在 {C['PANEL_MIN_W']}×{C['PANEL_MIN_H']} ~ {C['PANEL_MAX_W']}×{C['PANEL_MAX_H']}\n"
             f"titleBar     = 面板顶部，高 {C['TITLE_H']}；关闭按钮 {C['CLOSE_SIZE']}×{C['CLOSE_SIZE']} 贴右上角\n"
             f"tabs         = 标题栏下 {C['TAB_ROW_GAP']}px，宽 = 面板宽/6（夹 {C['TAB_MIN_W']}~{C['TAB_MAX_W']}），"
             f"三个居中排，间距 {C['TAB_GAP']}，高 {C['TAB_H']}\n"
             f"content      = 页签下 {C['CONTENT_GAP']}px，左右内缩 {C['CONTENT_PAD']}px，底部内缩 {C['CONTENT_BOTTOM_PAD']}px\n"
             f"行情页        leftCol 宽 {C['LEFT_COL_W']} / rightCol 宽 {C['RIGHT_COL_W']} / 中间留给大屏，栏间距 {C['COL_GAP']}\n"
             f"              卡片 {C['CARD_W']}×{C['CARD_H']}，纵向间距 {C['CARD_GAP']}\n"
             f"右列顺序      买入 / 卖出（高 {C['BTN_MAIN_H']}）→ 数量行 / 杠杆行（高 {C['FIELD_ROW_H']}）→ 资金情况（占剩余高度）\n"
             f"数量·杠杆行    标签 {C['FIELD_LABEL_W']} + 填写栏 {C['FIELD_W']} + ▲ {C['FIELD_STEP_W']} + ▼ {C['FIELD_STEP_W']} "
             f"+ MAX {C['FIELD_MAX_W']}（间隙 {C['FIELD_INNER_GAP']}）\n"
             f"              数量范围 {C['QTY_MIN']}~{C['QTY_MAX']} 股；杠杆范围 {C['LEVERAGE_MIN']}~{C['LEVERAGE_MAX']}；"
             f"填写栏最多 {C['FIELD_MAX_CHARS']} 字符\n"
             f"回收页        回收栏 {C['BAR_COLS']}×{C['BAR_ROWS']} 格（格 {C['SLOT']}px），右侧合计块宽 {C['BAR_INFO_W']}，"
             f"回收按钮 {C['RECYCLE_BTN_W']}×{C['RECYCLE_BTN_H']}，明细行高 {C['DETAIL_LINE_H']}\n"
             f"购买页        网格列数 = 内容宽/{C['SLOT']}（夹 {C['GRID_MIN_COLS']}~{C['GRID_MAX_COLS']}），"
             f"行数按剩余高度算（至少 {C['GRID_MIN_ROWS']}）；底下固定放玩家背包 27 格 + 快捷栏 9 格\n"
             "```\n")

    p.append("### 3.1 行情页三栏（按上面公式算出来的本次实例）\n")
    p.append(table([
        ("左列卡片（3 张，纵向排）", r(L["cards"][0]), wh(L["cards"][0]),
         f"卡片里是 16×16 物品图标 + 企业名 + **涨跌小箭头**；纵向间距 {C['CARD_GAP']}，整列宽 {C['LEFT_COL_W']}"),
        ("↳ 名称 / 涨跌箭头", f"卡X + {C['CARD_NAME_OFF_X']} / 名称右侧 +{C['CARD_ARROW_GAP']}px",
         f"卡Y + {C['CARD_NAME_OFF_Y']} , 9 × 8",
         "箭头用 ICON_UP / ICON_DOWN，位置随名称宽度算（名称渲染宽度 + 间距），所以不是固定矩形"),
        ("中间大屏（含内边距）", r(L["chartPanel"]), wh(L["chartPanel"]), "深色内嵌屏，代码画底色 + 网格"),
        ("↳ 企业名 · 风险", r(L["chartHeader"]), wh(L["chartHeader"]), "大屏左上角"),
        ("↳ 现价 + 涨跌", r(L["chartPrice"]), wh(L["chartPrice"]), "现价左、涨跌右（颜色红涨绿跌）"),
        ("↳ 阴阳柱区", r(L["chartPlot"]), wh(L["chartPlot"]), f"代码绘制；每根 = {C['CANDLE_TICKS']} 跳（10 秒）"),
        ("右列·买入", r(L["btnBuy"]), wh(L["btnBuy"]), "主操作按钮"),
        ("右列·卖出", r(L["btnSell"]), wh(L["btnSell"]), "主操作按钮"),
        ("右列·数量行", r(L["qtyLabel"]), wh(L["qtyLabel"]),
         f"「数量」标签 + 填写栏 {wh(L['qtyField'])}（FIELD_TILE 圆角底 + EditBox）"
         f" + ▲▼ {wh(L['qtyUp'])} + MAX {wh(L['qtyMax'])}"),
        ("右列·杠杆行", r(L["levLabel"]), wh(L["levLabel"]), "同上，值域 1~400"),
        ("右列·资金情况", r(L["fundsBlock"]), wh(L["fundsBlock"]),
         f"标题 + 最多 {C['FUNDS_LINES']} 行（余额/净值/本单/需付/可开/持仓/爆仓），行高 {C['FUNDS_LINE_H']}"),
    ]))

    p.append("### 3.2 回收页\n")
    p.append(table([
        ("顶部说明", r(L["hint"]), wh(L["hint"]), "一行字"),
        ("回收栏（18 格）", r(L["bar"]), wh(L["bar"]), f"{C['BAR_COLS']}×{C['BAR_ROWS']}；Slot 坐标 = 格子边框 + 1"),
        ("右侧合计块", r(L["barInfo"]), wh(L["barInfo"]),
         "合计 N 种 · M 件 · 总价 X（第 2 行只有不值钱时才有）"),
        ("回收按钮", r(L["recycleBtn"]), wh(L["recycleBtn"]), "动态标签「回 收 1728.00 金币」"),
        ("回收明细", r(L["barDetail"]), wh(L["barDetail"]),
         f"每行三列：物品 ×数量 / 单价 / 小计（右两列小号字体右对齐）；行高 {C['DETAIL_LINE_H']}"),
        ("背包说明", r(L["invLabel"]), wh(L["invLabel"]), "灰色小字，上面一条分隔线"),
        ("背包 27 格", r(L["inv"]), wh(L["inv"]), f"{C['INV_COLS']}×{C['INV_ROWS']}；Slot 坐标"),
        ("快捷栏 9 格", r(L["hotbar"]), wh(L["hotbar"]), "Slot 坐标"),
    ]))

    p.append("### 3.3 购买页（创造模式物品栏那种：点格子直接买）\n")
    p.append(table([
        ("分类按钮", r(L["catBtn"]), wh(L["catBtn"]), "循环：全部 / 材料 / 食物 / 方块 / 工具 / 其他"),
        ("搜索框", r(L["searchBox"]), wh(L["searchBox"]), "FIELD_TILE 圆角底 + EditBox（setBordered(false)）"),
        ("上一页 / 下一页", f"{r(L['prevBtn'])} / {r(L['nextBtn'])}", wh(L["prevBtn"]), "BTN_* + ICON_LEFT / ICON_RIGHT"),
        ("页码", r(L["pageText"]), wh(L["pageText"]), "居中「1/9」，由代码算"),
        ("物品网格（点一下直接买）", r(L["grid"]), wh(L["grid"]),
         f"{L['gridCols']}×{L['gridRows']} = {L['pageSize']} 格/页；左键买 1 组、右键买 1 个，没有数量/购买按钮"),
        ("提示行", r(L["shopHint"]), wh(L["shopHint"]), "「左键 = 买 1 组 · 右键 = 买 1 个 · 单价见悬停提示」"),
        ("背包说明", r(L["shopInvLabel"]), wh(L["shopInvLabel"]), "灰色小字"),
        ("背包 27 格", r(L["shopInv"]), wh(L["shopInv"]), "买到的东西直接进这里；Slot 坐标"),
        ("快捷栏 9 格", r(L["shopHotbar"]), wh(L["shopHotbar"]), "Slot 坐标"),
    ]))

    p.append(f"## 4. 贴图纸分区（{C['ATLAS_W']} × {C['ATLAS_H']}）\n")
    p.append("| 分区 | u, v | w × h | 用途 |\n|---|---|---|---|\n" + "\n".join(
        f"| {name} | {s[0]}, {s[1]} | {s[2]} × {s[3]} | {SPRITE_PURPOSE.get(name, '')} |"
        for name, s in S.items()
    ) + "\n")
    p.append("### 拼装规则\n")
    p.append(
        "- **面板**：内部用纯色填充（`COLOR_PANEL_FILL`），四角 1:1，四边沿轴向拉伸。\n"
        "- **标题栏**：`TITLE_TILE` 横向拉伸到面板宽度，高 18 固定。\n"
        "- **按钮 / 页签**：左右各 4px 不拉伸，中间拉伸；高度不拉伸（按钮高 16 或 20/24/28/30，代码会整体缩放）。\n"
        "- **槽位**：`SLOT_TILE` 1:1 铺，18px 一格；物品画在格子 +1 处（原版 `Slot` 约定）。\n"
        "- **卡片**：76×44 固定，不拉伸（左列宽度固定）。\n"
        "- **阴阳柱 / 网格 / 明细分隔线**：全部由代码画，不用贴图（颜色见下表）。\n"
    )

    p.append("## 5. 颜色常量（代码与美术共用）\n")
    p.append("| 常量 | 值 | 用途 |\n|---|---|---|\n" + "\n".join(
        f"| {name} | #{v[4:6]}{v[6:8]}{v[8:10]} | {_color_use(name)} |" for name, v in COL.items()
    ) + "\n")
    p.append(
        "⚠️ **涨跌配色是 A 股习惯：红涨绿跌**（`COLOR_UP` 是红色）。换欧美习惯只要把 `StockTheme` 里这两个值对调。\n"
    )

    p.append("## 6. 替换流程\n")
    p.append(
        f"1. 按上表画好 `stock_market.png`（{C['ATLAS_W']}×{C['ATLAS_H']}，分区位置一致）。\n"
        "2. 覆盖 `src/main/resources/assets/mcstock/textures/gui/stock_market.png`。\n"
        "3. 重跑 `python tools/gen_gui_textures.py`（刷新分区标注图）与 `python tools/gen_gui_mockup.py`（用你的图重出三张效果图）。\n"
        "4. 想挪元素 / 改比例（而不只是换皮）：改 `StockGuiLayout.java` 的常量，重跑脚本即可，游戏代码不用动。\n"
        "5. 想看别的窗口尺寸下的排版：`python tools/gen_gui_mockup.py 480 270`（GUI 缩放 4）或 `1280 720`。\n"
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(p), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size} bytes)")


def _color_use(name: str) -> str:
    return {
        "COLOR_UP": "上涨 / 盈利：阳线（收盘≥开盘）与涨跌数字",
        "COLOR_DOWN": "下跌 / 亏损：阴线与涨跌数字",
        "COLOR_MUTED": "次要文字（说明、单位、种类）",
        "COLOR_TEXT": "主文字",
        "COLOR_TEXT_SHADOW": "文字阴影",
        "COLOR_CHART_BASELINE": "K 线网格 / 基准线",
        "COLOR_LIQUIDATION": "爆仓价红线与预警文字",
        "COLOR_ACCENT": "选中卡片描边、页签下划线、金币、合计金额",
        "COLOR_PANEL_FILL": "面板内部填充（代码用，和 FRAME_CENTER 对齐）",
        "COLOR_INSET_FILL": "内嵌屏底色（阴阳柱区、资金情况块）",
        "COLOR_GRID_LINE": "内嵌屏网格线",
        "COLOR_BORDER": "分区描边 / 分隔线",
    }.get(name, "")


if __name__ == "__main__":
    main()
