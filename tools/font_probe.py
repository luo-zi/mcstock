"""小工具：比较几个中文字体在小字号下的可读性（阈值 + 可选膨胀）。

只用于挑选效果图/占位图的字体，不参与模组构建。
用法：python tools/font_probe.py
输出：tools/_font_probe.png（放大 4 倍便于观察）
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_font_probe.png")
SAMPLE = "钻石矿业 稳健 持仓 120 · 爆仓 99.88 余额 1,240 数量 全部 回收 1,728"
CANDS = [
    ("msyh.ttc", 9, 70, 0),
    ("msyh.ttc", 9, 70, 1),
    ("msyh.ttc", 10, 85, 1),
    ("msyh.ttc", 10, 60, 0),
    ("simhei.ttf", 10, 80, 1),
    ("simsun.ttc", 11, 80, 1),
]


def render(font_name: str, size: int, threshold: int, dilate: int) -> Image.Image:
    path = os.path.join(r"C:\Windows\Fonts", font_name)
    font = ImageFont.truetype(path, size)
    tmp = Image.new("L", (1000, size + 10), 0)
    ImageDraw.Draw(tmp).text((3, 3), SAMPLE, font=font, fill=255)
    for _ in range(dilate):
        tmp = tmp.filter(ImageFilter.MaxFilter(3))
    tmp = tmp.point(lambda v: 255 if v >= threshold else 0)
    bbox = tmp.getbbox() or (0, 0, 1, 1)
    tmp = tmp.crop((0, 0, min(980, bbox[2] + 4), size + 10))
    img = Image.new("RGB", tmp.size, (24, 26, 32))
    img.paste((235, 238, 245), (0, 0), tmp)
    return img


def main() -> None:
    imgs = [(f"{n}@{s} t{t} d{d}", render(n, s, t, d)) for n, s, t, d in CANDS]
    pad = 6
    w = max(i.width for _, i in imgs) * 4 + pad * 2
    h = sum(i.height * 4 + pad for _, i in imgs) + pad
    canvas = Image.new("RGB", (w, h), (12, 13, 16))
    y = pad
    label_font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 12)
    for label, img in imgs:
        big = img.resize((img.width * 4, img.height * 4), Image.NEAREST)
        canvas.paste(big, (pad, y))
        ImageDraw.Draw(canvas).text((pad + big.width + 6, y), label, font=label_font, fill=(255, 220, 120))
        y += big.height + pad
    canvas.save(OUT)
    print("wrote", OUT, canvas.size)


if __name__ == "__main__":
    main()
