"""占位美术的调色板与公共路径（布局请用 tools/layout_dump.py）。

只影响「美术交图之前」的占位贴图与效果图；成品图替换后这些颜色不再出现在游戏里。
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PLACEHOLDER = {
    "panel": (35, 38, 46),
    "panel_header": (41, 45, 54),
    "panel_card": (40, 43, 52),
    "border": (71, 78, 96),
    "border_light": (104, 114, 136),
    "slot_fill": (28, 30, 38),
    "slot_border": (68, 74, 90),
    "inset": (25, 27, 34),
    "grid": (44, 49, 60),
    "text": (237, 239, 245),
    "muted": (166, 172, 186),
    "accent": (230, 200, 121),
    "up": (224, 106, 98),
    "down": (111, 191, 122),
    "danger": (242, 112, 107),
    "btn": (58, 63, 76),
    "btn_border": (92, 100, 118),
    "btn_hover": (74, 80, 96),
    "btn_hover_border": (118, 128, 150),
    "btn_press": (46, 50, 60),
    "btn_press_border": (76, 83, 100),
    "btn_off": (44, 47, 54),
    "btn_off_border": (64, 69, 80),
    # 买入（红）/ 卖出（绿）：底色是「把 COLOR_UP / COLOR_DOWN 压暗 + 降饱和」的暗调，
    # 保证白字（StockTheme.COLOR_TEXT = 0xFFEDEFF5）对比度够；描边用原色。
    # 悬停整体提亮一档（同色相，不换色相）；按下比常态再压暗一档。
    # ⚠️ 这几个值是算过对比度的，别随手改（见 gen_gui_textures.py 的 build_buttons 注释）。
    "btn_buy": (92, 46, 42),
    "btn_buy_border": (224, 106, 98),
    "btn_buy_hover": (118, 58, 52),
    "btn_buy_hover_border": (240, 138, 130),
    "btn_buy_press": (74, 37, 34),
    "btn_buy_press_border": (196, 92, 86),
    "btn_sell": (48, 88, 57),
    "btn_sell_border": (111, 191, 122),
    "btn_sell_hover": (62, 112, 72),
    "btn_sell_hover_border": (140, 215, 150),
    "btn_sell_press": (38, 70, 46),
    "btn_sell_press_border": (94, 165, 104),
    "tab_on": (54, 59, 72),
    "tab_off": (38, 41, 49),
}


def argb(value: str) -> tuple[int, int, int, int]:
    """把 Java 那边的 "0xAARRGGBB" 转成 (r, g, b, a)。"""
    n = int(value, 16)
    return ((n >> 16) & 0xFF, (n >> 8) & 0xFF, n & 0xFF, (n >> 24) & 0xFF)
