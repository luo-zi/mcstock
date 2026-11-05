"""从原版资源包里取 16×16 物品/方块贴图，供效果图拼装真实图标。

资源包来自现有工程的 moddev 产物（只读，不改动它）：
    E:\\elysia_realme_mod\\build\\moddev\\artifacts\\neoforge-*-client-extra-aka-minecraft-resources.jar
拿不到就退化成画一个灰格子，效果图仍然能出。
"""
from __future__ import annotations

import io
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

_CANDIDATE_JARS = [
    Path(r"E:\elysia_realme_mod\build\moddev\artifacts"),
]
_CACHE: dict[str, Image.Image | None] = {}


def _find_jar() -> Path | None:
    for folder in _CANDIDATE_JARS:
        if folder.is_dir():
            for jar in sorted(folder.glob("*client-extra*.jar")):
                return jar
    return None


def _load_index() -> dict[str, str]:
    jar = _find_jar()
    if jar is None:
        return {}
    index: dict[str, str] = {}
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.startswith("assets/minecraft/textures/item/") and name.endswith(".png"):
                index.setdefault(Path(name).stem, name)
            elif name.startswith("assets/minecraft/textures/block/") and name.endswith(".png"):
                index.setdefault(Path(name).stem, name)
    return index


_INDEX: dict[str, str] | None = None


def vanilla_icon(name: str, size: int = 16) -> Image.Image:
    """按物品 id 取图标；找不到就返回灰色占位方块。"""
    global _INDEX
    if _INDEX is None:
        _INDEX = _load_index()

    key = f"{name}@{size}"
    if key in _CACHE:
        return _CACHE[key].copy()

    img: Image.Image | None = None
    entry = _INDEX.get(name)
    jar = _find_jar()
    if entry and jar is not None:
        with zipfile.ZipFile(jar) as zf:
            with zf.open(entry) as fh:
                img = Image.open(io.BytesIO(fh.read())).convert("RGBA")
        if img.size != (size, size):
            img = img.resize((size, size), Image.NEAREST)

    if img is None:
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([1, 1, size - 2, size - 2], fill=(150, 40, 160, 255), outline=(220, 220, 220, 255))

    _CACHE[key] = img
    return img.copy()
