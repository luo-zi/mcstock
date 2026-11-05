"""效果图 / 标注图里用的文字绘制。

⚠️ **游戏里用的是原版点阵字体**（中文 Unifont），模组不换字体（见 AGENTS.md 4.6 节）。
所以效果图也必须用接近点阵的观感：系统字体渲染 + 阈值化，别用抗锯齿的平滑字，
否则评审时会以为游戏里就是那种圆润字。

阈值 115 是试出来的：再低会把笔画糊成一团，再高会吃掉细笔画。
字号取 11（中文 ≈ 9px、数字 ≈ 6px）≈ 原版字体的度量。

真机截图（阶段 4）才是最终验收。
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont

_CANDIDATES = [
    (r"C:\Windows\Fonts\msyh.ttc", 11, 115),
    (r"C:\Windows\Fonts\simhei.ttf", 11, 115),
    (r"C:\Windows\Fonts\simsun.ttc", 11, 110),
]


def _pick_font():
    for path, size, threshold in _CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size), size, threshold
    return ImageFont.load_default(), 10, 0


class PixelFont:
    """把文字画成抗锯齿的小字，并提供宽度测量（用于居中 / 右对齐）。"""

    def __init__(self):
        self.font, self.size, self.threshold = _pick_font()
        self._mask_cache: dict[str, Image.Image] = {}

    def _mask(self, text: str) -> Image.Image:
        cached = self._mask_cache.get(text)
        if cached is not None:
            return cached
        width = max(1, int(self.font.getlength(text)) + 8)
        mask = Image.new("L", (width, self.size + 8), 0)
        ImageDraw.Draw(mask).text((2, 2), text, font=self.font, fill=255)
        if self.threshold > 0:
            mask = mask.point(lambda v: 255 if v >= self.threshold else 0)
        bbox = mask.getbbox()
        if bbox:
            mask = mask.crop(bbox)
        self._mask_cache[text] = mask
        return mask

    def width(self, text: str) -> int:
        return self._mask(text).width

    def height(self, text: str) -> int:
        return self._mask(text).height

    def draw(self, img: Image.Image, x: int, y: int, text: str, color, shadow=None) -> None:
        if not text:
            return
        mask = self._mask(text)
        if shadow is not None:
            img.paste(shadow, (x + 1, y + 1), mask)
        img.paste(tuple(color[:3]), (x, y), mask)

    def draw_center(self, img: Image.Image, center_x: int, y: int, text: str, color, shadow=None) -> None:
        self.draw(img, center_x - self.width(text) // 2, y, text, color, shadow)

    def draw_right(self, img: Image.Image, right_x: int, y: int, text: str, color, shadow=None) -> None:
        self.draw(img, right_x - self.width(text), y, text, color, shadow)
