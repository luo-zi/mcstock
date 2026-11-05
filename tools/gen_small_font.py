# -*- coding: utf-8 -*-
"""生成**小号数字点阵字体**（持仓列表专用），并同时产出它的 font json。

为什么要自己烤一份，而不是 `pose().scale(0.75f)` 把大字缩小：

* MC 的文字 = 「字体图集 + UV」，缩小等价于 UV 缩放。默认字体的图集是**最近邻**采样的，
  缩小 5~7px 的字形会直接掉笔画（中文走 Unifont 更糊）。
* 真正清晰的做法是**按小尺寸重新烤一份点阵**，在原尺寸下 1:1 绘制。

本脚本产出两个文件（都由脚本生成，避免手写 `chars` 网格错位）：

```text
src/main/resources/assets/mcstock/textures/font/small.png    # 点阵图集
src/main/resources/assets/mcstock/font/small.json            # bitmap provider 定义
```

## 几何约定（必须和 MC 的 BitmapProvider 对上）

`BitmapProvider.load()` 的算法（1.21.1 源码，见 `build/moddev/artifacts/*-sources.jar`）：

```text
cellW = imageW / chars[0].length()      # 每个字形格子的宽
cellH = imageH / chars.length          # 每个字形格子的高
scale = json.height / cellH            # 只有 height == cellH 时才是 1:1（不缩放、不糊）
advance = round(inkWidth * scale) + 1  # inkWidth = 字形在格子里**最右一个不透明列 + 1**
bearingTop = json.ascent
绘制位置： top = 7 - ascent           # 相对「文字绘制 y」
           ink 底部 = (7 - ascent) + 格子里的行号 + 1
```

原版 `include/default.json` 的 ascii 字体是 `ascent = 7`、格子 8×8，数字墨迹占格子的
第 0~6 行（实测 `textures/font/ascii.png` 的 '0'/'9' 都是 7 行高）→ 墨迹底边落在 `y + 7`。

⚠️ 所以这里取 **`ascent = height = cellH`**：墨迹底边 = `(7 - cellH) + cellH = y + 7`，
和原版数字**同一条基线**，混排时天然对齐，不需要任何手工 y 偏移。

## 字形设计

* 格子 `3 × 6`，墨迹铺满 6 行 → 数字 6px 高（原版是 7px 高），底边对齐基线；
* 每位数字 advance = `3 + 1 = 4`（原版是 `5 + 1 = 6`）→ 数字宽度只有原来的 2/3；
* 只放**数字与符号**，不放中文：中文走 Unifont，缩小只会掉笔画；
  而且某个字符在指定字体里找不到时 MC 画的是「缺字方块」（`SpecialGlyphs.MISSING`），
  不会回退到默认字体 —— 所以中文必须留在默认字体里画。

用法：`python tools\\gen_small_font.py`（顺带打一张 8 倍放大的自检图到 `docs/review/`）。
"""

from __future__ import annotations

import json
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PNG_PATH = os.path.join(ROOT, "src", "main", "resources", "assets", "mcstock",
                        "textures", "font", "small.png")
JSON_PATH = os.path.join(ROOT, "src", "main", "resources", "assets", "mcstock",
                         "font", "small.json")
PREVIEW_PATH = os.path.join(ROOT, "docs", "review", "small_font_v1.png")

#: 格子尺寸（像素）。高度 = 墨迹高度，见文件头的 baseline 推导。
CELL_W = 3
CELL_H = 6
#: 声明高度：等于格子高度 → 1:1 采样（不缩放就不会糊）。
HEIGHT = CELL_H
#: 基线：等于格子高度 → 墨迹底边落在绘制 y + 7（与原版数字一致）。
ASCENT = CELL_H

#: 字符集（顺序 = 图集里的排列顺序，必须与 json 的 chars 行完全一致）。
CHARS = "0123456789.,%+-/×·"

#: 每个字形 = 6 行 × 3 列，'#' = 点亮，'.' = 透明。
#: 数字铺满 6 行；运算符（+ - × ·）居中在第 2~4 行 —— 它们本来就不该顶到基线以上。
GLYPHS: dict[str, list[str]] = {
    "0": ["###", "#.#", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "#..", "###"],
    "3": ["###", "..#", ".##", "..#", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "#.#", "###"],
    "7": ["###", "..#", "..#", "..#", "..#", "..#"],
    "8": ["###", "#.#", "###", "#.#", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "..#", "###"],
    ".": ["...", "...", "...", "...", "...", "#.."],
    ",": ["...", "...", "...", "...", "..#", "#.."],
    "%": ["#.#", "..#", ".#.", ".#.", "#..", "#.#"],
    "+": ["...", "...", ".#.", "###", ".#.", "..."],
    "-": ["...", "...", "...", "###", "...", "..."],
    "/": ["..#", "..#", ".#.", ".#.", "#..", "#.."],
    "×": ["...", "...", "#.#", ".#.", "#.#", "..."],
    "·": ["...", "...", "...", "#..", "...", "..."],
}


def build_atlas() -> Image.Image:
    """把 GLYPHS 画成一张 (CELL_W × len(CHARS)) × CELL_H 的 RGBA 图集。"""
    img = Image.new("RGBA", (CELL_W * len(CHARS), CELL_H), (0, 0, 0, 0))
    px = img.load()
    for index, ch in enumerate(CHARS):
        rows = GLYPHS[ch]
        if len(rows) != CELL_H:
            raise SystemExit(f"字形 {ch!r} 不是 {CELL_H} 行")
        for y, row in enumerate(rows):
            if len(row) != CELL_W:
                raise SystemExit(f"字形 {ch!r} 第 {y} 行不是 {CELL_W} 列：{row!r}")
            for x, cell in enumerate(row):
                if cell == "#":
                    # 纯白 + 全不透明：MC 把 bitmap provider 当「彩色纹理」，
                    # 顶点色会乘上来（和原版字体同一套），所以颜色由代码给。
                    px[index * CELL_W + x, y] = (255, 255, 255, 255)
                elif cell != ".":
                    raise SystemExit(f"字形 {ch!r} 里有非法字符 {cell!r}")
    return img


def ink_width(img: Image.Image, index: int) -> int:
    """复刻 MC 的 getActualGlyphWidth：格子里最右一个不透明列 + 1。"""
    px = img.load()
    for x in range(CELL_W - 1, -1, -1):
        for y in range(CELL_H):
            if px[index * CELL_W + x, y][3] != 0:
                return x + 1
    return 0


def main() -> None:
    img = build_atlas()
    os.makedirs(os.path.dirname(PNG_PATH), exist_ok=True)
    os.makedirs(os.path.dirname(JSON_PATH), exist_ok=True)
    img.save(PNG_PATH)

    definition = {
        "providers": [
            {
                "type": "bitmap",
                "file": "mcstock:font/small.png",
                "height": HEIGHT,
                "ascent": ASCENT,
                "chars": [CHARS],
            }
        ]
    }
    with open(JSON_PATH, "w", encoding="utf-8", newline="\n") as fp:
        json.dump(definition, fp, ensure_ascii=False, indent=2)
        fp.write("\n")

    # 自检：把 MC 会算出来的 advance 打出来（数字应当是 4、'.'/'·' 是 2）
    advances = {}
    for index, ch in enumerate(CHARS):
        advances[ch] = ink_width(img, index) + 1
    print(f"图集 {img.size[0]}×{img.size[1]}  格子 {CELL_W}×{CELL_H}  "
          f"height={HEIGHT} ascent={ASCENT}（1:1，墨迹底边 = y+7）")
    print("advance:", " ".join(f"{ch}={advances[ch]}" for ch in CHARS))
    print("数字宽 = 4px/位（原版 6px）→ 「100.00」= 22px（原版 36px）")

    write_preview(img, advances)
    print(f"已写 {os.path.relpath(PNG_PATH, ROOT)}")
    print(f"已写 {os.path.relpath(JSON_PATH, ROOT)}")
    print(f"自检图 {os.path.relpath(PREVIEW_PATH, ROOT)}")


def write_preview(img: Image.Image, advances: dict[str, int]) -> None:
    """8 倍最近邻放大 + 一条模拟的持仓行，方便肉眼验收「清晰、可读」。"""
    zoom = 8
    cell = img.resize((img.size[0] * zoom, img.size[1] * zoom), Image.NEAREST)
    samples = ["100股", "99.20", "+96.00", "1,234.56", "-0.12%", "×400", "12/34"]
    pad = 6
    width = max(cell.size[0], 200) + pad * 2
    height = cell.size[1] + pad * 2 + len(samples) * (CELL_H * zoom + 4) + 14
    canvas = Image.new("RGB", (width, height), (24, 26, 33))
    canvas.paste(cell, (pad, pad), cell)
    y = pad + cell.size[1] + 10
    # 模拟行：小号数字按真实 advance 逐位摆放，直观看出「一位几像素」
    for text in samples:
        x = pad
        for ch in text:
            if ch in GLYPHS:
                index = CHARS.index(ch)
                glyph = img.crop((index * CELL_W, 0, (index + 1) * CELL_W, CELL_H))
                big = glyph.resize((CELL_W * zoom, CELL_H * zoom), Image.NEAREST)
                canvas.paste(big, (x, y), big)
                x += advances[ch] * zoom
            else:                       # 中文等：这里只留出原版字号的空位
                x += 9 * zoom
        y += CELL_H * zoom + 4
    os.makedirs(os.path.dirname(PREVIEW_PATH), exist_ok=True)
    canvas.save(PREVIEW_PATH)


if __name__ == "__main__":
    main()
