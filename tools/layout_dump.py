"""跑真实布局代码，把 StockGuiLayout.compute() 的结果取成 Python 字典。

为什么不用 Python 重写一遍布局公式：那样就有两份公式，迟早对不上。
这里用 javac 编译 StockGuiLayout.java（纯 Java、不依赖 Minecraft）+ tools/java/LayoutDump.java，
由后者输出 JSON，再由本模块解析。布局公式只有 Java 那一份。

用法：
    from layout_dump import dump, slot_of, bar_cell, inv_cell, hotbar_cell, grid_cell
    lay = dump(640, 360)          # 一次布局（默认 640×360 = 1080p 自动缩放下的逻辑分辨率）
    lay["layout"]["panel"]        # [x, y, w, h]
    lay["const"]["PANEL_W_RATIO"] # 常量
    lay["sprites"]["BTN_NORMAL"]  # [u, v, w, h]
"""
from __future__ import annotations

import functools
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_SRC = ROOT / "src/main/java/com/qiushaonan/mcstock/client/StockGuiLayout.java"
THEME_SRC = ROOT / "src/main/java/com/qiushaonan/mcstock/client/StockTheme.java"
DUMP_SRC = ROOT / "tools/java/LayoutDump.java"
BUILD_DIR = ROOT / "tools/_layout_build"

DEFAULT_SCREEN = (640, 360)  # 1080p + 自动 GUI 缩放（4 倍 → 480×270 太挤，3 倍 → 640×360）


def _java_home() -> Path | None:
    for cand in (os.environ.get("JAVA_HOME"), r"E:\java21"):
        if cand and (Path(cand) / "bin" / "javac.exe").exists():
            return Path(cand)
    return None


def _tool(name: str) -> str:
    home = _java_home()
    if home is not None:
        exe = home / "bin" / f"{name}.exe"
        if exe.exists():
            return str(exe)
    return name


def _build() -> None:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    marker = BUILD_DIR / "LayoutDump.class"
    sources = [JAVA_SRC, THEME_SRC, DUMP_SRC]
    newest = max(p.stat().st_mtime for p in sources)
    if marker.exists() and marker.stat().st_mtime >= newest:
        return
    subprocess.run(
        [_tool("javac"), "-encoding", "UTF-8", "-d", str(BUILD_DIR),
         str(JAVA_SRC), str(THEME_SRC), str(DUMP_SRC)],
        check=True,
    )


@functools.lru_cache(maxsize=16)
def dump(screen_w: int = DEFAULT_SCREEN[0], screen_h: int = DEFAULT_SCREEN[1]) -> dict:
    """返回 {'const': {...}, 'layout': {...}, 'sprites': {...}}。"""
    _build()
    res = subprocess.run(
        [_tool("java"), "-cp", str(BUILD_DIR), "LayoutDump", str(screen_w), str(screen_h)],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    return json.loads(res.stdout)


def rect(lay: dict, key: str) -> tuple[int, int, int, int]:
    return tuple(lay["layout"][key])


def tab(lay: dict, index: int):
    return tuple(lay["layout"]["tabs"][index])


def card(lay: dict, index: int):
    return tuple(lay["layout"]["cards"][index])


def slot_of(cell) -> tuple[int, int, int, int]:
    """格子边框 → Slot 坐标（原版约定：物品画在边框 +1 处）。"""
    x, y, w, h = cell
    return (x + 1, y + 1, w - 2, h - 2)


def bar_cell(lay: dict, col: int, row: int):
    """回收栏第 col 列、row 行的格子边框（与 StockGuiLayout.barCell 同式）。"""
    slot = lay["const"]["SLOT"]
    bx, by, _, _ = rect(lay, "bar")
    return (bx + col * slot, by + row * slot, slot, slot)


def inv_cell(lay: dict, index: int):
    slot = lay["const"]["SLOT"]
    ix, iy, _, _ = rect(lay, "inv")
    return (ix + (index % 9) * slot, iy + (index // 9) * slot, slot, slot)


def hotbar_cell(lay: dict, col: int):
    slot = lay["const"]["SLOT"]
    hx, hy, _, _ = rect(lay, "hotbar")
    return (hx + col * slot, hy, slot, slot)


def grid_cell(lay: dict, col: int, row: int):
    slot = lay["const"]["SLOT"]
    gx, gy, _, _ = rect(lay, "grid")
    return (gx + col * slot, gy + row * slot, slot, slot)


def slot_cell(lay: dict, key: str, index: int, cols: int = 9):
    """任意一个 9 列网格矩形（inv / hotbar / shopInv / shopHotbar）里第 index 格的边框坐标。"""
    slot = lay["const"]["SLOT"]
    x, y, _, _ = rect(lay, key)
    return (x + (index % cols) * slot, y + (index // cols) * slot, slot, slot)


if __name__ == "__main__":
    import sys

    w = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SCREEN[0]
    h = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_SCREEN[1]
    data = dump(w, h)
    print("screen:", w, "x", h)
    for k in ("panel", "titleBar", "content", "leftCol", "chartPanel", "chartPlot", "rightCol", "fundsBlock", "bar", "grid"):
        print(f"  {k:12s}", rect(data, k))
    print("  candleCount ", data["layout"]["candleCount"])
    print("  pageSize    ", data["layout"]["pageSize"], f"({data['layout']['gridCols']}×{data['layout']['gridRows']})")
