"""lang 键差集校验：把 Java 源码里所有 Component.translatable("…") 的字面量抓出来，
和两份 lang 的键做差集 —— **差集必须为空**。

用法：python tools/check_lang_keys.py
退出码 0 = 通过；1 = 有缺失键（会把缺失的键与出现位置打出来）。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_DIR = ROOT / "src/main/java"
LANG_DIR = ROOT / "src/main/resources/assets/mcstock/lang"

# Component.translatable("gui.mcstock.xxx") / Component.translatable("gui.mcstock.xxx", args…)
# 以及 StockWidgets 里等价的两参写法，一并覆盖（只认以命名空间开头的字面量）。
PATTERN = re.compile(r'translatable\(\s*"((?:gui|message|item|block)\.mcstock\.[A-Za-z0-9_.]+)"')

# 运行期拼出来的键（前缀 + 数组下标 / 循环下标）无法静态抓取。这里登记的是
# **「代码会去查、但 lang 里不要求存在」**的那一档：查不到时运行期会跳过或回显，
# 属于设计内（例如帮助正文只写了 1..9，而 HELP_LINES 留到 20 是为了以后加节）。
OPTIONAL_PREFIXES = ("gui.mcstock.help.",)

# 会被引用到的「动态键」：由数组字面量 / 三元表达式给出，静态抓不到字面量，
# 但**必须**存在于 lang 里，所以在这里显式登记（改这些表时记得同步）。
DYNAMIC_REQUIRED = {
    # StockScreen#drawPosHeaderCells 的列名表
    "gui.mcstock.pos.col.dir", "gui.mcstock.pos.col.shares", "gui.mcstock.pos.col.lev",
    "gui.mcstock.pos.col.pnl", "gui.mcstock.pos.col.liq",
    # 持仓方向 / 图表里的三元选择
    "gui.mcstock.pos.long", "gui.mcstock.pos.short", "gui.mcstock.pos.shares_unit",
    "gui.mcstock.chart.liq_above", "gui.mcstock.chart.liq_below",
    "gui.mcstock.chart.positions", "gui.mcstock.chart.no_position", "gui.mcstock.pos.avg",
    # 风险档位 / 购买页分类（StockScreen#CATEGORY_KEYS 数组）
    "gui.mcstock.risk.low", "gui.mcstock.risk.mid", "gui.mcstock.risk.high",
    "gui.mcstock.shop.cat.all", "gui.mcstock.shop.cat.material", "gui.mcstock.shop.cat.food",
    "gui.mcstock.shop.cat.block", "gui.mcstock.shop.cat.tool", "gui.mcstock.shop.cat.other",
    "gui.mcstock.btn.close",
    # 行情事件名（StockEventTable 里按 id 拼 "gui.mcstock.event." + id，静态抓不到字面量）
    "gui.mcstock.event.automation_line", "gui.mcstock.event.blaze_shortage",
    "gui.mcstock.event.circuit_short", "gui.mcstock.event.deep_core_tech",
    "gui.mcstock.event.engineer_poached", "gui.mcstock.event.fortress_route",
    "gui.mcstock.event.giant_vein", "gui.mcstock.event.military_order",
    "gui.mcstock.event.mine_collapse", "gui.mcstock.event.miners_strike",
    "gui.mcstock.event.order_rumor", "gui.mcstock.event.patent_granted",
    "gui.mcstock.event.piglin_caravan", "gui.mcstock.event.portal_congestion",
    "gui.mcstock.event.route_rumor", "gui.mcstock.event.smuggling_raid",
    "gui.mcstock.event.tariff_hike", "gui.mcstock.event.vein_rumor",
    # 估值降级提示（StockService 里按 StockEvents.valuesUnavailable() 二选一时引用的字面量）
    "message.mcstock.values_unavailable",
    # 新闻类型标签（EventType 枚举里拼 "gui.mcstock.newstype." + id）
    "gui.mcstock.newstype.company", "gui.mcstock.newstype.industry", "gui.mcstock.newstype.macro",
    "gui.mcstock.newstype.rumor", "gui.mcstock.newstype.market", "gui.mcstock.newstype.spike",
    # 插针（盘中异动）的两条中性文案（MarketEngine 里拼 "gui.mcstock.spike." + id）
    "gui.mcstock.spike.terminal_glitch", "gui.mcstock.spike.large_sell_order",
    # 大盘状态（MarketRegime 枚举里拼 "gui.mcstock.regime." + id）
    "gui.mcstock.regime.calm", "gui.mcstock.regime.bull",
    "gui.mcstock.regime.bear", "gui.mcstock.regime.volatile",
    # 大盘状态播报用的中性叙述句（"gui.mcstock.regime." + id + ".news"）
    "gui.mcstock.regime.calm.news", "gui.mcstock.regime.bull.news",
    "gui.mcstock.regime.bear.news", "gui.mcstock.regime.volatile.news",
    # 新闻条格式（StockScreen#tickerLine 里按变量传键）
    "gui.mcstock.news.format", "gui.mcstock.index.title",
}


def main() -> int:
    used: dict[str, list[str]] = {}
    for java in JAVA_DIR.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        for m in PATTERN.finditer(text):
            key = m.group(1)
            if key.endswith("."):
                continue        # "gui.mcstock.help." + i 这种前缀拼接：不是完整键
            line = text.count("\n", 0, m.start()) + 1
            used.setdefault(key, []).append(f"{java.relative_to(ROOT)}:{line}")

    langs = {}
    for name in ("zh_cn", "en_us"):
        langs[name] = set(json.loads((LANG_DIR / f"{name}.json").read_text(encoding="utf-8")))

    referenced = set(used) | DYNAMIC_REQUIRED
    failed = False
    for name, keys in langs.items():
        missing = sorted(k for k in referenced if k not in keys)
        print(f"[{name}] 引用 {len(referenced)} 个键，lang 有 {len(keys)} 个；缺失 {len(missing)} 个")
        for k in missing:
            where = "、".join(used.get(k, ["<动态引用>"]))
            print(f"    [MISSING] {k}    <- {where}")
            failed = True

    # 反向：lang 里有、代码里也没提到的（只提示，不算失败 —— 服务端也会用 lang 键）
    def mentioned_anywhere(key: str) -> bool:
        for java in JAVA_DIR.rglob("*.java"):
            if key in java.read_text(encoding="utf-8"):
                return True
        return False

    unused = [k for k in sorted(langs["zh_cn"])
              if k.startswith("gui.mcstock") and k not in referenced and not mentioned_anywhere(k)
              and not k.startswith(OPTIONAL_PREFIXES)]
    if unused:
        print(f"[提示] lang 里有 {len(unused)} 个 gui.mcstock 键在客户端代码里完全没出现"
              f"（可能是服务端用或已废弃）：")
        for k in unused:
            print(f"    . {k}")

    # 两份 lang 的键集合必须一致
    only_zh = sorted(langs["zh_cn"] - langs["en_us"])
    only_en = sorted(langs["en_us"] - langs["zh_cn"])
    if only_zh or only_en:
        print(f"[失败] 两份 lang 键不一致：zh 独有 {only_zh}；en 独有 {only_en}")
        failed = True
    else:
        print(f"[OK] 两份 lang 键集合一致（各 {len(langs['zh_cn'])} 个）")

    print("结果：" + ("**失败**" if failed else "**通过（差集为空）**"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
