"""用当前（占位或成品）图集渲染三张效果图，供布局评审。

屏幕逻辑尺寸可传参：`python tools/gen_gui_mockup.py 640 360`。
默认 640×360 = 1080p 下原版自动 GUI 缩放（3 倍）的逻辑分辨率，也是窗口占屏 88% 的典型观感。

布局矩形**全部来自 StockGuiLayout.compute()**（tools/layout_dump.py 跑真实 Java 代码得到的 JSON），
所以效果图和游戏里画出来的位置一定一致；美术换成品图后重跑本脚本即可看新图。

用法：python tools/gen_gui_mockup.py [screenW screenH]
产出：docs/gui_mockup_recycle.png / gui_mockup_trade.png / gui_mockup_shop.png
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gui_layout import ROOT, argb  # noqa: E402
from layout_dump import (DEFAULT_SCREEN, bar_cell, dump, grid_cell, hotbar_cell, inv_cell,  # noqa: E402
                         slot_cell)
from pixel_font import PixelFont  # noqa: E402
from vanilla_assets import vanilla_icon  # noqa: E402

ATLAS_PATH = ROOT / "src/main/resources/assets/mcstock/textures/gui/stock_market.png"
OUT_SCALE = 2
TABS = ("回收", "行情", "购买")

SCREEN = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) > 2 else DEFAULT_SCREEN
# 可选的版本标签（第三个参数）：除了写 docs/gui_mockup_*.png，还会另存一份
# docs/review/<标签>_*.png。评审时贴带标签的新文件，避免聊天窗口按路径缓存老图。
TAG = sys.argv[3] if len(sys.argv) > 3 else None
DATA = dump(*SCREEN)
CONST = DATA["const"]
SPR = DATA["sprites"]
COL = DATA["colors"]
LAYOUT = DATA["layout"]
ATLAS: Image.Image
FONT = PixelFont()

UP = argb(COL["COLOR_UP"])
DOWN = argb(COL["COLOR_DOWN"])
MUTED = argb(COL["COLOR_MUTED"])
TEXT = argb(COL["COLOR_TEXT"])
SHADOW = argb(COL["COLOR_TEXT_SHADOW"])
BASELINE = argb(COL["COLOR_CHART_BASELINE"])
LIQ = argb(COL["COLOR_LIQUIDATION"])
ACCENT = argb(COL["COLOR_ACCENT"])
PANEL_FILL = argb(COL["COLOR_PANEL_FILL"])
INSET_FILL = argb(COL["COLOR_INSET_FILL"])
GRID_LINE = argb(COL["COLOR_GRID_LINE"])
BORDER = argb(COL["COLOR_BORDER"])


def R(key: str) -> tuple[int, int, int, int]:
    return tuple(LAYOUT[key])


# ==================== 绘制原语 ====================

def blit(img: Image.Image, name: str, x: int, y: int, w: int | None = None, h: int | None = None) -> None:
    u, v, sw, sh = SPR[name]
    region = ATLAS.crop((u, v, u + sw, v + sh))
    if w is not None and h is not None and (w, h) != (sw, sh):
        region = region.resize((max(1, w), max(1, h)), Image.NEAREST)
    img.alpha_composite(region, (x, y))


def blit_slice_h(img: Image.Image, name: str, x: int, y: int, w: int, cap: int = 4, h: int | None = None) -> None:
    """按钮/页签/填写栏的水平 9 段：左右各 cap px 不拉伸，中间拉伸。"""
    u, v, sw, sh = SPR[name]
    src = ATLAS.crop((u, v, u + sw, v + sh))
    if h is not None and h != sh:
        src = src.resize((sw, h), Image.NEAREST)
        sh = h
    img.alpha_composite(src.crop((0, 0, cap, sh)), (x, y))
    img.alpha_composite(src.crop((cap, 0, sw - cap, sh)).resize((max(1, w - cap * 2), sh), Image.NEAREST), (x + cap, y))
    img.alpha_composite(src.crop((sw - cap, 0, sw, sh)), (x + w - cap, y))


def rounded_fill(img: Image.Image, rect, radius: int, fill, outline=None) -> None:
    x, y, w, h = rect
    ImageDraw.Draw(img).rounded_rectangle([x, y, x + w - 1, y + h - 1], radius=radius, fill=fill, outline=outline)


def field_box(img: Image.Image, rect) -> None:
    """填写栏底（游戏里是 EditBox + setBordered(false)，底下铺这张圆角贴图）。"""
    blit_slice_h(img, "FIELD_TILE", rect[0], rect[1], rect[2], cap=CONST["RADIUS_BOX"], h=rect[3])


def panel_frame(img: Image.Image, rect) -> None:
    """面板边框：内部圆角填充，四角 1:1（圆角在贴图里），四边拉伸（与游戏里同一套画法）。"""
    x, y, w, h = rect
    rounded_fill(img, rect, CONST["RADIUS_PANEL"], PANEL_FILL)
    corner = SPR["FRAME_TL"][2]
    blit(img, "FRAME_TL", x, y, corner, corner)
    blit(img, "FRAME_TR", x + w - corner, y, corner, corner)
    blit(img, "FRAME_BL", x, y + h - corner, corner, corner)
    blit(img, "FRAME_BR", x + w - corner, y + h - corner, corner, corner)
    blit(img, "FRAME_TOP", x + corner, y, w - corner * 2, corner)
    blit(img, "FRAME_BOTTOM", x + corner, y + h - corner, w - corner * 2, corner)
    blit(img, "FRAME_LEFT", x, y + corner, corner, h - corner * 2)
    blit(img, "FRAME_RIGHT", x + w - corner, y + corner, corner, h - corner * 2)


def inset_box(img: Image.Image, rect, fill=INSET_FILL, outline=BORDER, grid: int = 0) -> None:
    x, y, w, h = rect
    rounded_fill(img, rect, CONST["RADIUS_BOX"], fill, outline)
    d = ImageDraw.Draw(img)
    for i in range(1, grid):
        yy = y + h * i // grid
        d.line([x + 3, yy, x + w - 4, yy], fill=GRID_LINE)
        xx = x + w * i // grid
        d.line([xx, y + 3, xx, y + h - 4], fill=GRID_LINE)


def slots(img: Image.Image, x: int, y: int, cols: int, rows: int) -> None:
    for r in range(rows):
        for c in range(cols):
            blit(img, "SLOT_TILE", x + c * CONST["SLOT"], y + r * CONST["SLOT"])


def button(img: Image.Image, rect, label: str, state: str = "normal", color=TEXT) -> None:
    x, y, w, h = rect
    name = {"normal": "BTN_NORMAL", "hover": "BTN_HOVER", "pressed": "BTN_PRESSED", "disabled": "BTN_DISABLED"}[state]
    if h == SPR[name][3]:
        blit_slice_h(img, name, x, y, w)
    else:
        blit(img, name, x, y, w, h)
    if label:
        FONT.draw_center(img, x + w // 2, y + (h - FONT.height(label)) // 2, label,
                         color if state != "disabled" else MUTED, SHADOW)


def item_slot(img: Image.Image, cell, item: str, count: int = 0) -> None:
    x, y, w, h = cell
    img.alpha_composite(vanilla_icon(item, 16), (x + 1, y + 1))
    if count:
        text = str(count)
        FONT.draw(img, x + 17 - FONT.width(text), y + 17 - FONT.height(text), text, TEXT, SHADOW)


def field_row(img: Image.Image, label: str, value: str, label_rect, field_rect, up_rect, down_rect, max_rect,
              max_label: str = "MAX") -> None:
    """数量 / 杠杆行：标签 + 填写栏 + ▲ + ▼ + MAX（和游戏里 EditBox + 按钮一一对应）。"""
    FONT.draw(img, label_rect[0], label_rect[1] + (label_rect[3] - FONT.height(label)) // 2, label, MUTED, SHADOW)
    field_box(img, field_rect)
    FONT.draw_right(img, field_rect[0] + field_rect[2] - 4,
                    field_rect[1] + (field_rect[3] - FONT.height(value)) // 2, value, TEXT, SHADOW)

    for rect, icon in ((up_rect, "ICON_UP"), (down_rect, "ICON_DOWN")):
        button(img, rect, "")
        blit(img, icon, rect[0] + (rect[2] - SPR[icon][2]) // 2, rect[1] + (rect[3] - SPR[icon][3]) // 2)
    button(img, max_rect, max_label)


def chrome(img: Image.Image, active_tab: int) -> None:
    """三页共用：面板边框 + 标题栏 + 页签 + 关闭按钮。"""
    panel_frame(img, R("panel"))
    tx, ty, tw, th = R("titleBar")
    blit(img, "TITLE_TILE", tx + 1, ty + 1, tw - 2, th - 1)
    FONT.draw(img, tx + 8, ty + 5, "MC 股市", TEXT, SHADOW)
    FONT.draw_center(img, tx + tw // 2, ty + 5, "余额 1,240 · 净值 1,480", TEXT, SHADOW)
    cx, cy, cw, ch = R("closeBtn")
    FONT.draw_right(img, cx - 8, ty + 5, "2s", MUTED, SHADOW)
    blit(img, "CLOSE_NORMAL", cx, cy, cw, ch)

    for i, label in enumerate(TABS):
        x, y, w, h = tuple(LAYOUT["tabs"][i])
        blit_slice_h(img, "TAB_ACTIVE" if i == active_tab else "TAB_INACTIVE", x, y, w)
        FONT.draw_center(img, x + w // 2, y + (h - 8) // 2, label, TEXT if i == active_tab else MUTED, SHADOW)


def new_canvas() -> Image.Image:
    return Image.new("RGBA", (LAYOUT["screenW"], LAYOUT["screenH"]), (10, 11, 14, 255))


# ==================== 阴阳柱 ====================

def candles(seed: int, start: float, drift: float, vol: float, count: int, tail: float):
    rng = random.Random(seed)
    price = start
    bars = []
    for _ in range(count):
        o = price
        hi = lo = o
        for _ in range(CONST["CANDLE_TICKS"]):
            price *= 1.0 + rng.gauss(drift, vol)
            hi, lo = max(hi, price), min(lo, price)
        bars.append([o, hi, lo, price])
    shift = tail - bars[-1][3]
    return [[b[0] + shift, b[1] + shift, b[2] + shift, b[3] + shift] for b in bars]


def price_tag(img: Image.Image, right_x: int, y: int, text: str, color) -> None:
    """价格刻度处的圆角小标签（留 3px 内边距，免得数字贴着边框）。"""
    w = FONT.width(text) + 8
    ImageDraw.Draw(img).rounded_rectangle([right_x - w, y - 3, right_x, y + FONT.height(text) + 2],
                                          radius=3, fill=(24, 26, 33, 246), outline=tuple(color[:3]))
    FONT.draw(img, right_x - w + 4, y, text, color)


def draw_candles(img: Image.Image, rect, bars, liq) -> None:
    x, y, w, h = rect
    values = [b[1] for b in bars] + [b[2] for b in bars] + ([liq] if liq else [])
    lo, hi = min(values), max(values)
    if hi - lo < 1e-6:
        hi = lo + 1.0
    pad = (hi - lo) * 0.08
    lo, hi = lo - pad, hi + pad

    def py(v):
        return y + h - 1 - int(round((v - lo) / (hi - lo) * (h - 1)))

    d = ImageDraw.Draw(img)
    slot = w / float(len(bars))
    body = max(1, int(slot) - 1)
    for i, (o, hi_, lo_, c) in enumerate(bars):
        cx = x + int(slot * (i + 0.5))
        color = tuple(UP[:3]) if c >= o else tuple(DOWN[:3])
        d.line([cx, py(hi_), cx, py(lo_)], fill=color)
        top, bottom = py(max(o, c)), py(min(o, c))
        left = cx - body // 2
        d.rectangle([left, top, left + body - 1, max(bottom, top)], fill=color)

    price_y = py(bars[-1][3])
    if liq:
        ly = py(liq)
        for px in range(x, x + w, 4):
            d.line([px, ly, px + 1, ly], fill=tuple(LIQ[:3]))
        if abs(ly - price_y) >= 14:
            price_tag(img, x + w - 2, ly - FONT.height(f"{liq:.2f}") // 2, f"{liq:.2f}", LIQ)
    color = UP if bars[-1][3] >= bars[-1][0] else DOWN
    for px in range(x, x + w, 6):
        d.line([px, price_y, px + 3, price_y], fill=tuple(color[:3]))
    price_tag(img, x + w - 2, price_y - FONT.height(f"{bars[-1][3]:.2f}") // 2, f"{bars[-1][3]:.2f}", color)


# ==================== 行情页 ====================

STOCKS = [
    dict(name="钻石矿业", risk="稳健", price=100.00, change=+0.62, icon="diamond", lev="×400", qty="×1",
         liq=99.88, pos="持仓 120 · 爆仓 99.88"),
    dict(name="红石科技", risk="波动", price=47.35, change=-1.20, icon="redstone", lev="×5", qty="×1",
         liq=None, pos="无持仓"),
    dict(name="下界贸易", risk="激进", price=20.04, change=-0.30, icon="blaze_rod", lev="×5", qty="×1",
         liq=18.05, pos="持仓 300 · 爆仓 18.05"),
]
BARS = [
    candles(7, 96.5, 0.00035, 0.0016, LAYOUT["candleCount"], 100.00),
    candles(21, 49.4, -0.00025, 0.0040, LAYOUT["candleCount"], 47.35),
    candles(33, 20.9, -0.00035, 0.0070, LAYOUT["candleCount"], 20.04),
]
FUNDS = ["余额  1,240", "净值  1,480", "本单  1 股", "需付  0.35", "可开  4,960 股", "持仓  120", "爆仓  99.88"]


def render_trade() -> Image.Image:
    img = new_canvas()
    chrome(img, 1)

    # 左列：图标 + 名称 + 涨跌小箭头（箭头位置按名称宽度算，和游戏里同一套规则）
    for i, st in enumerate(STOCKS):
        x, y, w, h = tuple(LAYOUT["cards"][i])
        blit(img, "CARD_SELECTED" if i == 0 else ("CARD_HOVER" if i == 1 else "CARD_NORMAL"), x, y, w, h)
        img.alpha_composite(vanilla_icon(st["icon"], 16),
                            (x + CONST["CARD_ICON_OFF_X"], y + CONST["CARD_ICON_OFF_Y"]))
        FONT.draw(img, x + CONST["CARD_NAME_OFF_X"], y + CONST["CARD_NAME_OFF_Y"], st["name"], TEXT, SHADOW)
        up = st["change"] >= 0
        blit(img, "ICON_UP" if up else "ICON_DOWN",
             x + CONST["CARD_W"] - CONST["CARD_ARROW_PAD_X"] - SPR["ICON_UP"][2],
             y + CONST["CARD_ARROW_OFF_Y"])

    # 中间大屏
    st = STOCKS[0]
    px, py, pw, ph = R("chartPanel")
    panel_frame(img, (px, py, pw, ph))
    hx, hy, hw, hh = R("chartHeader")
    FONT.draw(img, hx, hy, f"{st['name']} · {st['risk']}", TEXT, SHADOW)
    FONT.draw_right(img, hx + hw, hy, f"每根 10 秒 · 近 {LAYOUT['candleCount']} 根", MUTED, SHADOW)
    qx, qy, qw, qh = R("chartPrice")
    FONT.draw(img, qx, qy, f"{st['price']:.2f}", TEXT, SHADOW)
    up = st["change"] >= 0
    color = UP if up else DOWN
    change = f"{st['change']:+.2f}%"
    FONT.draw_right(img, qx + qw, qy, change, color, SHADOW)
    blit(img, "ICON_UP" if up else "ICON_DOWN", qx + qw - FONT.width(change) - 12, qy + 1)

    plot = R("chartPlot")
    inset_box(img, plot, grid=4)
    draw_candles(img, plot, BARS[0], st["liq"])

    # 走势图下方的持仓条：第一行持仓概览，第二行「赚了多少」+ 爆仓价

    # 右列：买入 / 卖出 / 数量行 / 杠杆行 / 资金情况
    button(img, R("btnBuy"), "买 入")
    button(img, R("btnSell"), "卖 出")
    field_row(img, "数量", "1", R("qtyLabel"), R("qtyField"), R("qtyUp"), R("qtyDown"), R("qtyMax"), "MAX")
    field_row(img, "杠杆", "400", R("levLabel"), R("levField"), R("levUp"), R("levDown"), R("levMax"), "MAX")
    bx, by, bw, bh = R("fundsBlock")
    inset_box(img, (bx, by, bw, bh), fill=(26, 28, 35, 255))
    FONT.draw(img, bx + 6, by + 4, "资金情况", ACCENT, SHADOW)
    for i in range(LAYOUT["fundsLines"]):
        FONT.draw(img, bx + 6, by + CONST["FUNDS_TITLE_H"] + 2 + i * CONST["FUNDS_LINE_H"], FUNDS[i],
                  MUTED if i >= 4 else TEXT, SHADOW)
    return img


# ==================== 回收页 ====================

BAR_ITEMS = [("cobblestone", 64), ("dirt", 64), ("oak_log", 64), ("stone", 64),
             ("gravel", 64), ("sand", 64), ("glass", 32), ("netherrack", 64),
             ("cobblestone", 64), ("dirt", 64)]
INV_MAIN = ["diamond", "iron_ingot", "gold_ingot", "redstone", "emerald", "book", "gold_ingot",
            "cobblestone", "dirt", "stone", "glass", "obsidian", "amethyst_shard", "copper_ingot",
            "coal", "torch", "apple", "bread", "wheat", "bone", "string", "feather", "leather",
            "gunpowder", "arrow", "bucket", "name_tag", "diamond_sword"]
INV_HOTBAR = ["cobblestone", "dirt", "oak_log", "cobblestone", "torch", "apple", "bread", "stone", "emerald"]
DETAIL = [
    ("圆石 ×576", "1.00", "576.00"),
    ("泥土 ×128", "1.00", "128.00"),
    ("橡木原木 ×128", "4.00", "512.00"),
    ("石头 ×64", "1.00", "64.00"),
    ("沙砾 ×64", "1.00", "64.00"),
    ("沙子 ×64", "1.00", "64.00"),
    ("玻璃 ×32", "1.00", "32.00"),
    ("下界岩 ×64", "0.00", "0.00"),
]


def render_recycle() -> Image.Image:
    img = new_canvas()
    chrome(img, 0)

    # ⚠️ 顶部说明行（hint）已在界面上**删除**（玩家：「删除所有说明性的文字」），
    #    这里也不画 —— 效果图要和界面一致，否则会误导美术。

    bx, by, bw, bh = R("bar")
    slots(img, bx, by, CONST["BAR_COLS"], CONST["BAR_ROWS"])
    for i, (item, count) in enumerate(BAR_ITEMS):
        item_slot(img, bar_cell(DATA, i % CONST["BAR_COLS"], i // CONST["BAR_COLS"]), item, count)

    ix, iy, iw, ih = R("barInfo")
    # 合计块：第 1 行「合计 N 种 · M 件 · 总价 X」、第 2 行只有不值钱时才有。
    FONT.draw(img, ix, iy + 4, "合计 8 种 · 1088 件 · 总价 1440.00", ACCENT, SHADOW)
    FONT.draw(img, ix, iy + 18, "不值钱 64 件", MUTED, SHADOW)
    button(img, R("recycleBtn"), "回 收  1440.00 金币")

    # 回收明细：一行三列 —— 物品 ×数量 / 单价 / 小计（右两列右对齐）
    # ⚠️ 三列的 x 直接取 **LayoutDump 里 Java 算好的矩形**（`detailName0/Unit0/Amount0`），
    #    不要在这里自己减一遍：这里曾经把 `amount_w` 误写成 `unit_w`，
    #    列 2 的右边缘偏了 6px、和列 3 叠在一起（效果图上看着像「5761.00」）。
    dx, dy, dw, dh = R("barDetail")
    inset_box(img, (dx, dy, dw, dh), fill=(26, 28, 35, 255))
    name_x = LAYOUT["detailName0"][0]
    unit_right = LAYOUT["detailUnit0"][0] + LAYOUT["detailUnit0"][2]
    amount_right = LAYOUT["detailAmount0"][0] + LAYOUT["detailAmount0"][2]
    for i in range(min(LAYOUT["detailLines"], len(DETAIL))):
        line_y = dy + 4 + i * CONST["DETAIL_LINE_H"]
        FONT.draw(img, name_x, line_y, DETAIL[i][0], MUTED, SHADOW)
        FONT.draw_right(img, unit_right, line_y, DETAIL[i][1], MUTED, SHADOW)
        FONT.draw_right(img, amount_right, line_y, DETAIL[i][2], TEXT, SHADOW)

    lx, ly, lw, lh = R("invLabel")
    # ⚠️ 背包标签也是一条**已删除**的说明文字，界面上只剩那条分隔线
    ix2, iy2, iw2, ih2 = R("inv")
    slots(img, ix2, iy2, CONST["INV_COLS"], CONST["INV_ROWS"])
    for i, item in enumerate(INV_MAIN[:CONST["INV_COLS"] * CONST["INV_ROWS"]]):
        item_slot(img, inv_cell(DATA, i), item)
    hx2, hy2, hw2, hh2 = R("hotbar")
    slots(img, hx2, hy2, CONST["INV_COLS"], 1)
    for i, item in enumerate(INV_HOTBAR):
        item_slot(img, hotbar_cell(DATA, i), item)
    return img


# ==================== 购买页 ====================

SHOP_ITEMS = [
    "nether_star", "dragon_egg", "elytra", "totem_of_undying", "trident", "netherite_ingot",
    "diamond", "emerald", "netherite_scrap", "heart_of_the_sea", "nautilus_shell", "echo_shard",
    "shulker_shell", "ender_pearl", "ghast_tear", "blaze_rod", "gold_ingot", "obsidian",
    "iron_ingot", "amethyst_shard", "lapis_lazuli", "redstone", "quartz", "coal",
    "leather", "apple", "bread", "cooked_beef", "wheat", "oak_log",
    "cobblestone", "dirt", "glass", "stone", "sponge", "slime_ball",
    "string", "bone", "gunpowder", "feather", "rotten_flesh", "spider_eye",
    "golden_apple", "sugar_cane", "bamboo", "melon_slice", "honeycomb", "glowstone_dust",
    "carrot", "potato", "beetroot", "wheat_seeds", "arrow", "bucket",
    "diamond_sword", "name_tag", "book", "torch", "copper_ingot", "iron_ingot",
]


def render_shop() -> Image.Image:
    img = new_canvas()
    chrome(img, 2)

    button(img, R("catBtn"), "分类 材料")
    sx, sy, sw, sh = R("searchBox")
    field_box(img, (sx, sy, sw, sh))
    FONT.draw(img, sx + 6, sy + (sh - FONT.height("搜索物品")) // 2, "搜索物品", (120, 126, 138), SHADOW)
    button(img, R("prevBtn"), "")
    blit(img, "ICON_LEFT", R("prevBtn")[0] + 4, R("prevBtn")[1] + 5)
    button(img, R("nextBtn"), "")
    blit(img, "ICON_RIGHT", R("nextBtn")[0] + 4, R("nextBtn")[1] + 5)
    ptx, pty, ptw, pth = R("pageText")
    FONT.draw_center(img, ptx + ptw // 2, pty + (pth - FONT.height("1/9")) // 2, "1/9", TEXT, SHADOW)

    gx, gy, gw, gh = R("grid")
    cols, rows = LAYOUT["gridCols"], LAYOUT["gridRows"]
    slots(img, gx, gy, cols, rows)
    for i, item in enumerate(SHOP_ITEMS[:cols * rows]):
        cell = grid_cell(DATA, i % cols, i // cols)
        img.alpha_composite(vanilla_icon(item, 16), (cell[0] + 1, cell[1] + 1))
    hot_cell = grid_cell(DATA, 6, 0)
    ImageDraw.Draw(img).rounded_rectangle([hot_cell[0], hot_cell[1], hot_cell[0] + 17, hot_cell[1] + 17],
                                          radius=CONST["RADIUS_SLOT"], outline=(255, 255, 255, 235))

    # 底部：玩家背包（和创造力物品栏一样，点上面的格子直接拿）
    shx, shy, shw, shh = R("shopHint")
    FONT.draw_center(img, shx + shw // 2, shy + 2,
                     "左键 = 买 1 组 · 右键 = 买 1 个 · 单价见悬停提示", MUTED, SHADOW)
    lx, ly, lw, lh = R("shopInvLabel")
    FONT.draw(img, lx, ly + 2, "背包（买到的东西直接进来）", MUTED, SHADOW)
    ix, iy, iw, ih = R("shopInv")
    slots(img, ix, iy, CONST["INV_COLS"], CONST["INV_ROWS"])
    for i, item in enumerate(INV_MAIN[:CONST["INV_COLS"] * CONST["INV_ROWS"]]):
        item_slot(img, slot_cell(DATA, "shopInv", i), item)
    hx, hy, hw, hh = R("shopHotbar")
    slots(img, hx, hy, CONST["INV_COLS"], 1)
    for i, item in enumerate(INV_HOTBAR):
        item_slot(img, slot_cell(DATA, "shopHotbar", i), item)
    return img


def save(img: Image.Image, name: str) -> Path:
    big = img.resize((img.width * OUT_SCALE, img.height * OUT_SCALE), Image.NEAREST).convert("RGB")
    out = ROOT / "docs" / name
    out.parent.mkdir(parents=True, exist_ok=True)
    big.save(out)
    if TAG:
        rev = ROOT / "docs" / "review" / f"{TAG}_{name}"
        rev.parent.mkdir(parents=True, exist_ok=True)
        big.save(rev)
        print(f"      评审副本 {rev.relative_to(ROOT)}")
    return out


def main() -> None:
    global ATLAS
    if not ATLAS_PATH.exists():
        raise SystemExit(f"缺图集：{ATLAS_PATH}（先跑 tools/gen_gui_textures.py）")
    ATLAS = Image.open(ATLAS_PATH).convert("RGBA")

    print(f"屏幕逻辑尺寸 {SCREEN[0]}×{SCREEN[1]} → 面板 {R('panel')[2]}×{R('panel') [3]}"
          f"（{CONST['PANEL_W_RATIO']:.0%} 比例，夹在 {CONST['PANEL_MIN_W']}~{CONST['PANEL_MAX_W']}）")
    for img, name in ((render_recycle(), "gui_mockup_recycle.png"),
                      (render_trade(), "gui_mockup_trade.png"),
                      (render_shop(), "gui_mockup_shop.png")):
        out = save(img, name)
        print(f"wrote {out.relative_to(ROOT)} {img.width * OUT_SCALE}x{img.height * OUT_SCALE}")


if __name__ == "__main__":
    main()
