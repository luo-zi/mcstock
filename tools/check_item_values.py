"""估值表键位自查：`item_values/*.json` 里的每个键必须真的存在。

历史背景（2026-10-06 玩家实测漏洞）：估值表写错键**不会报错**，只会静默失效 ——
最坑的一次是把**方块标签**（`#minecraft:base_stone_overworld`、`#minecraft:crops`）写进了
物品估值表：`StockEvents#tagMembers` 只遍历 `BuiltInRegistries.ITEM` 的标签，
于是那几个物品一个价都拿不到，而日志里一片安静。

本脚本对着 `build/moddev/artifacts/*client-extra*.jar`（里面装着原版数据与语言文件）校验：
  * `#xxx:yyy` 必须是 `data/minecraft/tags/item/yyy.json` 里真实存在的**物品**标签；
  * `xxx:yyy` 必须在原版语言里有 `item.minecraft.yyy` 或 `block.minecraft.yyy` 条目。

⚠️ 只校验 `minecraft:` 命名空间的键。模组物品（`allthemodium:` 之类，见 `atm10.json`）
在原版数据里当然查不到，它们的正确性由运行期 `/mcstock audit` 与
`ItemValuator` 的「地板指向不存在的物品」告警来兜。

`unlock_hours` 段（进度档）的键用同一套规则校验，另外多查一条**数值范围**：
必须是 1..720（720 = 30 天，见 `UnlockStages.MAX_HOURS`）。写了 0 等于「没写」，
写了 9999 会被夹到 720 —— 两种都算笔误，脚本直接报出来，别等运行期看日志。

用法：python tools/check_item_values.py
退出码 0 = 全部通过（或找不到 client-extra 而跳过）；1 = 有非法键 / 非法值。
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLE_DIR = ROOT / "src/main/resources/data/mcstock/mcstock/item_values"
ARTIFACT_DIR = ROOT / "build/moddev/artifacts"


def find_client_extra() -> Path | None:
    if not ARTIFACT_DIR.is_dir():
        return None
    for jar in sorted(ARTIFACT_DIR.glob("*client-extra*.jar")):
        return jar
    return None


def namespace_of(key: str) -> str:
    """`#c:ores/iron` → `c`；`minecraft:dirt` → `minecraft`；不带冒号时算原版。"""
    body = key[1:] if key.startswith("#") else key
    return body.split(":", 1)[0] if ":" in body else "minecraft"


def main() -> int:
    files = sorted(TABLE_DIR.glob("*.json"))
    if not files:
        print("[失败] 估值表目录里一个 json 都没有：", TABLE_DIR)
        return 1

    values: dict[str, int] = {}
    stages: dict[str, int] = {}
    skipped_mod: list[str] = []
    bad_stage_values: list[str] = []
    doc_sections = 0
    for path in files:
        table = json.loads(path.read_text(encoding="utf-8"))
        doc_sections += len(table.get("tiers", {}))
        for key, value in table.get("values", {}).items():
            if namespace_of(key) != "minecraft":
                skipped_mod.append(key)
                continue
            values[key] = value
        for key, hours in table.get("unlock_hours", {}).items():
            # 数值范围先查（与命名空间无关：模组键的笔误一样该报）
            if not isinstance(hours, int) or isinstance(hours, bool) or not 1 <= hours <= 720:
                bad_stage_values.append(f"{key} = {hours!r}")
            if namespace_of(key) != "minecraft":
                skipped_mod.append(key)
                continue
            stages[key] = hours

    jar = find_client_extra()
    if jar is None:
        print("[跳过] 找不到 client-extra（先跑一次 .\\gradlew.bat build 生成它）：", ARTIFACT_DIR)
        print(f"       {len(files)} 个文件、{len(values)} 个原版键、跳过 {len(skipped_mod)} 个模组键；"
              f"分档文档 {doc_sections} 段；进度档 {len(stages)} 个原版键")
        return 1 if bad_stage_values else 0

    with zipfile.ZipFile(jar) as zf:
        item_tags = {
            Path(n).stem
            for n in zf.namelist()
            if n.startswith("data/minecraft/tags/item/") and n.endswith(".json")
        }
        en_us = json.loads(zf.read("assets/minecraft/lang/en_us.json").decode("utf-8"))

    bad_tags: list[str] = []
    bad_items: list[str] = []
    # 两段共用同一套键校验：进度档写错键和价格写错键一样是静默失效
    for key in list(values) + list(stages):
        if key.startswith("#"):
            tag = key[1:].split(":", 1)[-1]
            if tag not in item_tags:
                bad_tags.append(key)
        else:
            name = key.split(":", 1)[-1]
            if f"item.minecraft.{name}" not in en_us and f"block.minecraft.{name}" not in en_us:
                bad_items.append(key)

    bad_tags = sorted(set(bad_tags))
    bad_items = sorted(set(bad_items))

    print(f"{len(files)} 个文件：{len(values)} 个原版键"
          f"（物品 {sum(1 for k in values if not k.startswith('#'))}、"
          f"标签 {sum(1 for k in values if k.startswith('#'))}），"
          f"进度档 {len(stages)} 个原版键，"
          f"跳过 {len(skipped_mod)} 个模组键")
    print(f"原版物品标签共 {len(item_tags)} 个")
    failed = False
    if bad_stage_values:
        failed = True
        print(f"[失败] {len(bad_stage_values)} 个进度档数值不合法（必须是 1..720 的整数，"
              f"720 = 30 天；写 0 等于没写、写超了会被夹取）：")
        for entry in bad_stage_values:
            print("    .", entry)
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
        print("[OK] 每个原版键都指向真实存在的物品 / 物品标签，进度档数值都在 1..720")
    print("结果：" + ("**失败**" if failed else "**通过**"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
