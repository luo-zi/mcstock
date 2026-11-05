"""估值表键位自查：`item_values/core.json` 里的每个键必须真的存在。

历史背景（2026-10-06 玩家实测漏洞）：估值表写错键**不会报错**，只会静默失效 ——
最坑的一次是把**方块标签**（`#minecraft:base_stone_overworld`、`#minecraft:crops`）写进了
物品估值表：`StockEvents#tagMembers` 只遍历 `BuiltInRegistries.ITEM` 的标签，
于是那几个物品一个价都拿不到，而日志里一片安静。

本脚本对着 `build/moddev/artifacts/*client-extra*.jar`（里面装着原版数据与语言文件）校验：
  * `#xxx:yyy` 必须是 `data/minecraft/tags/item/yyy.json` 里真实存在的**物品**标签；
  * `xxx:yyy` 必须在原版语言里有 `item.minecraft.yyy` 或 `block.minecraft.yyy` 条目。

用法：python tools/check_item_values.py
退出码 0 = 全部通过（或找不到 client-extra 而跳过）；1 = 有非法键。
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "src/main/resources/data/mcstock/mcstock/item_values/core.json"
ARTIFACT_DIR = ROOT / "build/moddev/artifacts"


def find_client_extra() -> Path | None:
    if not ARTIFACT_DIR.is_dir():
        return None
    for jar in sorted(ARTIFACT_DIR.glob("*client-extra*.jar")):
        return jar
    return None


def main() -> int:
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    values: dict[str, int] = table["values"]

    jar = find_client_extra()
    if jar is None:
        print("[跳过] 找不到 client-extra（先跑一次 .\\gradlew.bat build 生成它）：", ARTIFACT_DIR)
        print(f"       表里有 {len(values)} 个键；分档文档 {len(table.get('tiers', {}))} 段")
        return 0

    with zipfile.ZipFile(jar) as zf:
        item_tags = {
            Path(n).stem
            for n in zf.namelist()
            if n.startswith("data/minecraft/tags/item/") and n.endswith(".json")
        }
        en_us = json.loads(zf.read("assets/minecraft/lang/en_us.json").decode("utf-8"))

    bad_tags: list[str] = []
    bad_items: list[str] = []
    for key in values:
        if key.startswith("#"):
            tag = key[1:].split(":", 1)[-1]
            if tag not in item_tags:
                bad_tags.append(key)
        else:
            name = key.split(":", 1)[-1]
            if f"item.minecraft.{name}" not in en_us and f"block.minecraft.{name}" not in en_us:
                bad_items.append(key)

    print(f"估值表：{len(values)} 个键（物品 {sum(1 for k in values if not k.startswith('#'))}、"
          f"标签 {sum(1 for k in values if k.startswith('#'))}）")
    print(f"原版物品标签共 {len(item_tags)} 个")
    failed = False
    if bad_tags:
        failed = True
        print(f"[失败] {len(bad_tags)} 个标签不是**物品**标签（写了也不生效）：")
        for key in bad_tags:
            print("    .", key)
    if bad_items:
        failed = True
        print(f"[失败] {len(bad_items)} 个物品 id 在原版里不存在（拼错了？）：")
        for key in bad_items:
            print("    .", key)
    if not failed:
        print("[OK] 每个键都指向真实存在的物品 / 物品标签")
    print("结果：" + ("**失败**" if failed else "**通过**"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
