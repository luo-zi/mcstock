"""生成占位美术：模块化 GUI 图集（圆角 + 柔和配色）、物品图标、模组 logo、分区标注图。

⚠️ 因为窗口尺寸随游戏窗口变化，图集里**没有整窗底图**：面板由 9 张 8×8 圆角/直边切片拼出，
槽位、卡片、按钮、页签、填写栏都是可拼可拉的小块。分区坐标来自 StockGuiLayout.java。
圆角半径与 StockGuiLayout.RADIUS_* 必须一致。

用法：python tools/gen_gui_textures.py
产出：
    src/main/resources/assets/mcstock/textures/gui/stock_market.png   256×256
    src/main/resources/assets/mcstock/textures/item/stock_terminal.png 16×16
    src/main/resources/assets/mcstock/logo.png                        128×128
    docs/gui_atlas_map.png                                            分区标注图
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gui_layout import PLACEHOLDER as P, ROOT  # noqa: E402
from layout_dump import DEFAULT_SCREEN, dump  # noqa: E402
from pixel_font import PixelFont  # noqa: E402

DATA = dump(*DEFAULT_SCREEN)
SPR = DATA["sprites"]
CONST = DATA["const"]
R_PANEL = CONST["RADIUS_PANEL"]
R_BOX = CONST["RADIUS_BOX"]
R_SLOT = CONST["RADIUS_SLOT"]

ATLAS = ROOT / "src/main/resources/assets/mcstock/textures/gui/stock_market.png"
ITEM_ICON = ROOT / "src/main/resources/assets/mcstock/textures/item/stock_terminal.png"
LOGO = ROOT / "src/main/resources/assets/mcstock/logo.png"
ATLAS_MAP = ROOT / "docs/gui_atlas_map.png"


def rgba(color, alpha: int | None = None):
    if alpha is None:
        return tuple(color) if len(color) == 4 else (*color, 255)
    return (color[0], color[1], color[2], alpha)


def rect_of(name: str) -> tuple[int, int, int, int]:
    return tuple(SPR[name])


def paste(img: Image.Image, tile: Image.Image, name: str) -> None:
    u, v, _, _ = rect_of(name)
    img.alpha_composite(tile, (u, v))


# ==================== 切片 ====================

def rounded_tile(w: int, h: int, radius: int, fill, border, flip_x=False, flip_y=False,
                 round_all=True) -> Image.Image:
    """圆角切片。

    {@code round_all=False} 时只圆左上角：把圆角矩形画到超出右/下的位置再裁掉，
    这样另外三个角是直角 —— 面板四角就是这么来的。
    """
    pad = radius + 2
    tmp = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(tmp)
    x1 = pad + w - 1 if round_all else tmp.width - 1
    y1 = pad + h - 1 if round_all else tmp.height - 1
    d.rounded_rectangle([pad, pad, x1, y1], radius=radius, fill=rgba(fill), outline=rgba(border))
    tile = tmp.crop((pad, pad, pad + w, pad + h))
    if flip_x:
        tile = tile.transpose(Image.FLIP_LEFT_RIGHT)
    if flip_y:
        tile = tile.transpose(Image.FLIP_TOP_BOTTOM)
    return tile


def build_frame_tiles(img: Image.Image) -> None:
    """面板边框：四角圆角（只圆外侧），四边直条，中心纯色。"""
    for name, fx, fy in (("FRAME_TL", False, False), ("FRAME_TR", True, False),
                         ("FRAME_BL", False, True), ("FRAME_BR", True, True)):
        # ⚠️ 必须是 u, v, w, h：写成了 w, h, _, _ 的话，四个角会拿 (u, v) 当宽高 ——
        # 左上/右上/左下变成 0×0（**根本没有圆角**），右下变成 16×16 并溢到 (16..31, 16..31)，
        # 正好把 ICON_UP 那一格（24,20,9×8）糊上一层不透明面板色：
        # 箭头的透明角和抗锯齿边于是叠在灰底上，实机看就是「箭头糊了一圈脏边」。
        u, v, w, h = rect_of(name)
        paste(img, rounded_tile(w, h, R_PANEL, P["panel"], P["border"], flip_x=fx, flip_y=fy, round_all=False), name)

    for name, side in (("FRAME_TOP", "top"), ("FRAME_BOTTOM", "bottom"),
                       ("FRAME_LEFT", "left"), ("FRAME_RIGHT", "right")):
        u, v, w, h = rect_of(name)
        tile = Image.new("RGBA", (w, h), rgba(P["panel"]))
        d = ImageDraw.Draw(tile)
        if side == "top":
            d.line([0, 0, w - 1, 0], fill=rgba(P["border"]))
        elif side == "bottom":
            d.line([0, h - 1, w - 1, h - 1], fill=rgba(P["border"]))
        elif side == "left":
            d.line([0, 0, 0, h - 1], fill=rgba(P["border"]))
        else:
            d.line([w - 1, 0, w - 1, h - 1], fill=rgba(P["border"]))
        paste(img, tile, name)

    u, v, w, h = rect_of("FRAME_CENTER")
    paste(img, Image.new("RGBA", (w, h), rgba(P["panel"])), "FRAME_CENTER")


def build_title_tile(img: Image.Image) -> None:
    u, v, w, h = rect_of("TITLE_TILE")
    tile = Image.new("RGBA", (w, h), rgba(P["panel_header"]))
    d = ImageDraw.Draw(tile)
    d.line([0, h - 1, w - 1, h - 1], fill=rgba((88, 96, 116)))
    d.line([0, 0, w - 1, 0], fill=rgba((255, 255, 255, 10)))
    paste(img, tile, "TITLE_TILE")


def build_slot_tile(img: Image.Image) -> None:
    u, v, w, h = rect_of("SLOT_TILE")
    tile = rounded_tile(w, h, R_SLOT, P["slot_fill"], P["slot_border"])
    d = ImageDraw.Draw(tile)
    d.line([1, 1, w - 2, 1], fill=rgba((18, 20, 26)))
    paste(img, tile, "SLOT_TILE")


def build_field_tile(img: Image.Image) -> None:
    u, v, w, h = rect_of("FIELD_TILE")
    paste(img, rounded_tile(w, h, R_BOX, P["inset"], P["border"]), "FIELD_TILE")


def build_cards(img: Image.Image) -> None:
    for name, fill, border in (("CARD_NORMAL", P["panel_card"], P["border"]),
                               ("CARD_HOVER", (48, 52, 63), P["border_light"]),
                               ("CARD_SELECTED", (52, 47, 39), P["accent"])):
        u, v, w, h = rect_of(name)
        tile = rounded_tile(w, h, R_BOX, fill, border)
        if name == "CARD_SELECTED":
            d = ImageDraw.Draw(tile)
            d.rounded_rectangle([1, 1, 3, h - 2], radius=1, fill=rgba(P["accent"]))
        paste(img, tile, name)


def build_buttons(img: Image.Image) -> None:
    for name, fill, border in (("BTN_NORMAL", P["btn"], P["btn_border"]),
                               ("BTN_HOVER", P["btn_hover"], P["btn_hover_border"]),
                               ("BTN_PRESSED", P["btn_press"], P["btn_press_border"]),
                               ("BTN_DISABLED", P["btn_off"], P["btn_off_border"]),
                               # 🔴 买入 / 🟢 卖出（红涨绿跌，与 StockTheme.COLOR_UP/DOWN 同口径）。
                               # ⚠️ 为什么要在图集里画、而不是运行时给灰皮肤染色：
                               #    `g.setColor` 是**乘法**，灰底 (58,63,76) 乘红色会把蓝分量
                               #    一起留下 —— 实机是一块发浑的脏紫，不是"红按钮"。
                               # ⚠️ 每个色系必须有**三态**（NORMAL/HOVER/PRESSED）：
                               #    StockButton#pickSprite 的按下态会取 PRESSED，
                               #    只画两态的话按下去会突然闪回灰色皮肤。
                               # 对比度（白字 COLOR_TEXT=#EDEFF5，WCAG AA 要 >= 4.5:1）：
                               #    买 常态 9.72 / 悬停 7.53 / 按下 11.57
                               #    卖 常态 7.08 / 悬停 5.05 / 按下  9.14
                               ("BTN_BUY_NORMAL", P["btn_buy"], P["btn_buy_border"]),
                               ("BTN_BUY_HOVER", P["btn_buy_hover"], P["btn_buy_hover_border"]),
                               ("BTN_BUY_PRESSED", P["btn_buy_press"], P["btn_buy_press_border"]),
                               ("BTN_SELL_NORMAL", P["btn_sell"], P["btn_sell_border"]),
                               ("BTN_SELL_HOVER", P["btn_sell_hover"], P["btn_sell_hover_border"]),
                               ("BTN_SELL_PRESSED", P["btn_sell_press"], P["btn_sell_press_border"])):
        u, v, w, h = rect_of(name)
        paste(img, rounded_tile(w, h, R_BOX, fill, border), name)


def build_close_help_icons(img: Image.Image) -> None:
    """关闭叉 + 帮助「?」：**逐像素硬边**画，12×12。

    ⚠️ 这三格以前不在 SPR 里，生成器既不画、标注图也不标，只能事后跑一个
    一次性补丁脚本原地改 png —— 而那种做法改的是图片文件本身，
    **重跑一次生成器就全被清掉**（实机表现：关闭按钮又只剩一个圈、没有 ✕）。
    现在它们登记进 LayoutDump 并由这里画，两边不会再脱节（补丁脚本已删除）。
    ⚠️ 不做抗锯齿：12×12 的小图标一抗锯齿，笔画就被冲淡成灰雾（CLOSE 那两格就是这么坏掉的）。
    ⚠️ 函数名**不能**叫 `build_icons`：那名字下面还有一个画涨跌箭头的同名函数，
    Python 后定义者胜 —— 重名会把这一份整个顶掉（踩过一次：叉没画出来，
    还留下一个空函数体把 `ICON_HELP` 清成透明）。
    """
    muted = P["muted"]
    text = P["text"]
    hover = (244, 138, 132)     # 与 CLOSE_HOVER 原有的强调色一致

    # 叉：两条 1px 对角线，两端各补一格把 8px 撑到 10px
    for name, color in (("CLOSE_NORMAL", muted), ("CLOSE_HOVER", hover)):
        u, v, w, h = rect_of(name)
        tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        for i in range(2, w - 2):
            for dx, dy in ((i, i), (w - 1 - i, i)):
                tile.putpixel((dx, dy), rgba(color))
        for dx, dy in ((1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)):
            tile.putpixel((dx, dy), rgba(color))
        paste(img, tile, name)

    # 「?」：上半个圈（顶横 + 左右两竖）+ 斜向收口 + 中竖 + 底部一点
    u, v, w, h = rect_of("ICON_HELP")
    tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    strokes = ([(x, 2) for x in range(3, 8)]
               + [(3, 3), (3, 4), (3, 5), (7, 3), (7, 4)]
               + [(6, 5), (5, 6), (5, 7), (5, 8), (5, 10)])
    for x, y in strokes:
        tile.putpixel((x, y), rgba(text))
    paste(img, tile, "ICON_HELP")


def build_tabs(img: Image.Image) -> None:
    for name, fill, border, active in (("TAB_ACTIVE", P["tab_on"], P["border_light"], True),
                                       ("TAB_INACTIVE", P["tab_off"], P["border"], False)):
        u, v, w, h = rect_of(name)
        # 只圆上边：往下超出一截再裁掉
        tile = rounded_tile(w, h + R_BOX + 2, R_BOX, fill, border).crop((0, 0, w, h))
        d = ImageDraw.Draw(tile)
        if active:
            d.rounded_rectangle([2, 0, w - 3, 1], radius=1, fill=rgba(P["accent"]))
        else:
            d.rounded_rectangle([4, h - 3, w - 5, h - 2], radius=1, fill=rgba(P["border_light"]))
        paste(img, tile, name)


def build_icons(img: Image.Image) -> None:
    """
    涨跌小箭头等小图标。

    <p>⚠️ 这里是**硬边实心三角**，故意不做 4 倍超采样 + LANCZOS：9×8 这种小图上，
    抗锯齿会沿着斜边留下大量半透明像素（实测 ICON_DOWN 有 61% 的像素 alpha 在 1~253 之间，
    颜色还被 LANCZOS 拉向透明区的黑/灰）。这些像素叠在深色面板上就是一圈发灰的毛边 ——
    玩家反馈的「上涨/下调箭头看上去有点脏」就是这个。
    现在按整数行/列铺跨度：边缘只有 0 或 255，内部是**纯色**（与 StockTheme 的
    COLOR_UP / COLOR_DOWN 同一个 RGB），尺寸仍是图集里原有的 9×8 包围盒。
    """
    def triangle(name: str, direction: str, color) -> None:
        u, v, w, h = rect_of(name)
        c = rgba(color)
        tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(tile)
        if direction in ("up", "down"):
            for y in range(h):
                t = y / max(1, h - 1)
                if direction == "down":
                    t = 1.0 - t
                half = round((w - 1) / 2 * t)          # up: 顶点在 y=0，底边铺满第 h-1 行
                d.line([w // 2 - half, y, w // 2 + half, y], fill=c)
        else:
            for x in range(w):
                t = x / max(1, w - 1)
                if direction == "right":
                    t = 1.0 - t
                half = round((h - 1) / 2 * t)          # left: 顶点在 x=0，底边铺满第 w-1 列
                d.line([x, h // 2 - half, x, h // 2 + half], fill=c)
        paste(img, tile, name)

    triangle("ICON_UP", "up", P["up"])
    triangle("ICON_DOWN", "down", P["down"])
    triangle("ICON_LEFT", "left", P["muted"])
    triangle("ICON_RIGHT", "right", P["muted"])

    u, v, w, h = rect_of("ICON_COIN")
    coin = Image.new("RGBA", (w * 4, h * 4), (0, 0, 0, 0))
    cd = ImageDraw.Draw(coin)
    cd.ellipse([0, 0, w * 4 - 1, h * 4 - 1], fill=rgba(P["accent"]), outline=rgba((168, 134, 56)))
    cd.ellipse([w, h, w * 4 - w - 1, h * 4 - h - 1], outline=rgba((255, 246, 208)))
    paste(img, coin.resize((w, h), Image.LANCZOS), "ICON_COIN")

    for name, color, thick in (("CLOSE_NORMAL", P["muted"], 1), ("CLOSE_HOVER", (244, 138, 132), 1)):
        u, v, w, h = rect_of(name)
        big = Image.new("RGBA", (w * 4, h * 4), (0, 0, 0, 0))
        bd = ImageDraw.Draw(big)
        c = rgba(color)
        for i in range(thick * 2):
            bd.line([10 + i, 10, w * 4 - 10 + i, h * 4 - 10], fill=c)
            bd.line([w * 4 - 10 + i, 10, 10 + i, h * 4 - 10], fill=c)
        paste(img, big.resize((w, h), Image.LANCZOS), name)


# ==================== 图集 ====================

def build_atlas() -> Image.Image:
    img = Image.new("RGBA", (CONST["ATLAS_W"], CONST["ATLAS_H"]), (0, 0, 0, 0))
    build_frame_tiles(img)
    build_title_tile(img)
    build_slot_tile(img)
    build_field_tile(img)
    build_cards(img)
    build_buttons(img)
    build_tabs(img)
    build_icons(img)
    # ⚠️ 必须排在 build_icons 之后：那一个画的是 (24,20) 起的涨跌箭头，
    #    和关闭叉 (24,30)/(40,30)/帮助 (76,30) 是**不同格**，但都靠 paste 覆盖整格，
    #    顺序明确写出来，免得以后有人调整顺序时互相清掉。
    build_close_help_icons(img)
    return img


# ==================== 物品图标 / logo ====================

def build_item_icon() -> Image.Image:
    big = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([4, 8, 59, 55], radius=10, fill=rgba((58, 63, 76)), outline=rgba((124, 134, 158)))
    d.rounded_rectangle([10, 14, 53, 43], radius=6, fill=rgba((30, 33, 41)))
    d.line([14, 36, 24, 32, 36, 34, 48, 20], fill=rgba(P["accent"]), width=3, joint="curve")
    d.ellipse([44, 16, 51, 23], fill=rgba(P["accent"]))
    for x in (20, 28, 36):
        d.ellipse([x, 48, x + 5, 53], fill=rgba(P["accent"]))
    return big.resize((16, 16), Image.LANCZOS)


def build_logo() -> Image.Image:
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([2, 2, 125, 125], radius=28, fill=rgba((35, 38, 46)), outline=rgba(P["accent"]), width=3)
    d.rounded_rectangle([16, 28, 112, 100], radius=12, fill=rgba((26, 28, 35)), outline=rgba(P["border"]))
    for x, top, bottom, color in ((30, 74, 92, P["up"]), (48, 62, 84, P["down"]), (66, 70, 96, P["up"]),
                                  (84, 52, 72, P["down"]), (100, 38, 60, P["up"])):
        d.line([x, top - 10, x, bottom + 8], fill=rgba(color), width=2)
        d.rounded_rectangle([x - 6, top, x + 6, bottom], radius=3, fill=rgba(color))
    d.line([24, 90, 46, 76, 68, 82, 90, 54, 108, 38], fill=rgba(P["accent"]), width=3, joint="curve")
    return img


# ==================== 分区标注图 ====================

def build_atlas_map(atlas: Image.Image) -> Image.Image:
    """左：图集放大 3 倍 + 每个分区画红框并标号；右：图例（编号 / 名称 / 坐标 / 缩略图）。"""
    scale = 3
    legend_w = 330
    row_h = 22
    font = PixelFont()
    big = atlas.resize((atlas.width * scale, atlas.height * scale), Image.NEAREST)

    checker = Image.new("RGB", big.size, (58, 61, 68))
    cd = ImageDraw.Draw(checker)
    for cy in range(0, big.height, 8):
        for cx in range(0, big.width, 8):
            if (cx // 8 + cy // 8) % 2 == 0:
                cd.rectangle([cx, cy, cx + 7, cy + 7], fill=(44, 47, 54))

    entries = sorted(SPR.items(), key=lambda kv: (kv[1][1], kv[1][0]))
    top = 24
    height = max(big.height + top, top + 26 + row_h * len(entries))
    canvas = Image.new("RGB", (big.width + legend_w, height), (22, 24, 30))
    canvas.paste(checker, (0, top))
    canvas.paste(big, (0, top), big)
    d = ImageDraw.Draw(canvas)

    for idx, (key, (u, v, w, h)) in enumerate(entries, start=1):
        x0, y0 = u * scale, top + v * scale
        x1, y1 = (u + w) * scale - 1, top + (v + h) * scale - 1
        d.rounded_rectangle([x0, y0, x1, y1], radius=4, outline=(244, 130, 130))
        font.draw(canvas, max(0, x0 + 2), max(0, y0 + 2), str(idx), (255, 220, 120))

    d.text((8, 7), f"stock_market.png  {atlas.width}x{atlas.height}   (x{scale})   radius: panel={R_PANEL} box={R_BOX} slot={R_SLOT}",
           fill=(255, 220, 120))

    lx = big.width + 12
    d.line([lx - 6, 0, lx - 6, height], fill=(71, 78, 96))
    for idx, (key, (u, v, w, h)) in enumerate(entries, start=1):
        y = top + 8 + row_h * (idx - 1)
        font.draw(canvas, lx, y - 4, f"{idx:>2}. {key}", (238, 240, 246))
        font.draw(canvas, lx, y + 7, f"u={u} v={v} w={w} h={h}", (166, 172, 186))
        swatch = atlas.crop((u, v, u + w, v + h)).resize((16, 16), Image.NEAREST)
        canvas.paste(swatch, (lx + legend_w - 30, y), swatch)
    return canvas


def check_coords() -> None:
    """坐标自检：切片两两不重叠、且都在图集范围内。**不通过就直接中止，不写文件。**

    ⚠️ 这个脚本踩过两次坐标事故，所以把检查固化成硬闸门：
      ① `FRAME_*` 四角把 `(u,v)` 当宽高读（写成 `w,h,_,_`），0×0 的角 + 16×16 溢出把
         `ICON_UP` 那格糊上一层不透明面板色；
      ② 新增的 `BTN_SELL_HOVER` 放在 `(72,168)`，正好压住 `TAB_INACTIVE` 的 `(64..128,168..184)`
         —— 重跑之后页签底图会被按钮皮肤覆盖。
    以后再加切片，跑一次就能当场发现，而不是等实机截图。
    """
    items = sorted(SPR.items())
    problems = []
    for i in range(len(items)):
        n1, a = items[i]
        if a[0] < 0 or a[1] < 0 or a[0] + a[2] > CONST["ATLAS_W"] or a[1] + a[3] > CONST["ATLAS_H"]:
            problems.append(f"{n1} 越界 {tuple(a)}")
        for j in range(i + 1, len(items)):
            n2, b = items[j]
            if not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0]
                    or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1]):
                problems.append(f"{n1} 与 {n2} 重叠")
    if problems:
        raise SystemExit("图集坐标自检失败：\n  " + "\n  ".join(problems))
    print(f"坐标自检通过（{len(SPR)} 个切片两两不重叠、均在 "
          f"{CONST['ATLAS_W']}×{CONST['ATLAS_H']} 内）")


def main() -> None:
    ATLAS.parent.mkdir(parents=True, exist_ok=True)
    ITEM_ICON.parent.mkdir(parents=True, exist_ok=True)
    ATLAS_MAP.parent.mkdir(parents=True, exist_ok=True)

    atlas = build_atlas()
    check_coords()
    atlas.save(ATLAS)
    build_item_icon().save(ITEM_ICON)
    build_logo().save(LOGO)
    build_atlas_map(atlas).save(ATLAS_MAP)

    print(f"wrote {ATLAS.relative_to(ROOT)} {atlas.size} （{len(SPR)} 个分区，圆角 {R_PANEL}/{R_BOX}/{R_SLOT}）")
    print(f"wrote {ITEM_ICON.relative_to(ROOT)} 16x16")
    print(f"wrote {LOGO.relative_to(ROOT)} 128x128")
    print(f"wrote {ATLAS_MAP.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
