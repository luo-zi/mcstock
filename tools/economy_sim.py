# -*- coding: utf-8 -*-
"""经济模拟：**打平胜率 / 爆仓时间 / 劳动敏感性**（补 Java 单测没算的三件事）。

> **与 Java 单测的分工**：`src/test/java/.../market/EconomySimulationTest.java` 已经算过
> 「A 追事件 / B 死多×400 / C 频繁往返 三条策略亏、D 只回收赚」的**净收益**，本脚本
> **不重复**那个结论。本脚本补的是 Java 那边没有的三件事：
>
> 1. **打平所需胜率** —— 手续费 + 买卖价差决定的盈亏平衡价格变动，换算成「胜率要多少 / 要连续做对几次」；
> 2. **杠杆 → 爆仓时间** —— 杠杆 1/10/100/400 的「从开仓到爆仓的跳数中位数 + 10%/90% 分位」；
> 3. **劳动效率敏感性** —— 挖矿 32/64/128 个圆石每分钟的累计金币，并与 A/B/C 并排。
>
> 另外补一条：**多久能把 1000 金币亏光**（A/B/C 各自的中位时间）。
> 四条策略的完整净值表仍然照跑（before/after 对照），作为上面这些数字的上下文。

## 三组参数：original（最初版）/ before（上一版·严厉）/ after（本版·回调后）

**只有 `after` 是真实生效的**（文件头那七个旋钮**直接从 Java 源码解析**，改源码自动跟上）；
`original` / `before` 是历史值，硬编码在脚本里做对照。

| 项 | original（最初版） | before（上一版·严厉） | **after（本版·回调后）** |
|---|---|---|---|
| 手续费 `FEE_RATE` | 0.001 | 0.0035 | **源码现值** |
| 购买倍率 `PURCHASE_MARKUP` | 1.15 | 1.25 | **源码现值** |
| 均值回归 `MEAN_REVERSION` | 0.015 | 0.03 | **源码现值** |
| 爆仓线 `MARGIN_CALL_PERCENT` | 50 | 40 | **源码现值** |
| 大盘 bias | 牛 +0.0012 / 熊 −0.0010 | 牛 +0.0006 / 熊 −0.0016 | **源码现值** |
| 利空事件幅度 | ×1.0 | ×1.15 | **源码现值**（`BEARISH_EVENT_SCALE`） |
| 插针 | 无 | 0.05%/跳 | **源码现值**（单跳 −8~15%，3~5 跳回补 60~80%） |

## 这个脚本在复刻什么

Python 逐行复刻下面这份 Java 的真实数值与**结算顺序**（after 的数值全部从源码读出来，不猜）：

| 角色 | 源码 |
|---|---|
| 全部常量（σ / 均值回归 / 单跳上限 / 价格区间 / 手续费率 / 爆仓线 / 杠杆上下限 / 基准价 / β / 事件概率 / 成簇 / **插针** / **利空放大**） | `market/StockDefs.java` |
| 一跳的价格公式、护栏顺序、大盘状态机、事件注册、传闻澄清、成簇、**插针与回补**、随机消耗顺序 | `market/MarketEngine.java` |
| 大盘 bias / volMultiplier / 转移表 / 持续跳数 | `market/MarketRegime.java` |
| 冲击的「当场跳变 70% + 半衰期拖尾 30%」衰减曲线 | `market/MarketShock.java` |
| 18 条具名事件 + **利空幅度整体放大**（静态块） | `market/StockEventTable.java` |
| 保证金 / 手续费（向上取整）/ 爆仓价 / 可开上限 | `market/PositionMath.java` |
| 开仓 / 平仓 / 爆仓结算口径 | `market/TradeService.java` |
| 回收价 = 估价、购买价 = `ceil(估价 × PURCHASE_MARKUP)` | `market/ItemValuator.java`、`market/StockService.java` |
| 圆石 = 1 金币 | `data/mcstock/mcstock/item_values/core.json` |

> ⚠️ 源码在本次工作期间被改过（`StockDefs` / `MarketRegime` / `ItemValuator` /
> `StockEventTable` / `MarketEngine` 的 mtime 集中在 2026-10-05 21:05~21:07）。
> 本脚本按**改完之后的**状态复刻，即 `after`。源码若再改，脚本需要重新核对。

## 四条策略（起点都是 1000 金币）

* **A 追事件**：出现利好事件那一跳，用全部现金 × 50 倍杠杆开多，持有到（爆仓 or 60 跳）平仓，再等下一条；
* **B 死多 ×400**：开局全部现金 × 400 开多一次，持有到爆仓或跑完；
* **C 无脑往返**：每 10 跳用全部现金买 1 股、下一跳卖出，重复；
* **D 只回收不交易**（对照组 = 劳动）：挖矿收入按「每分钟 64 个圆石 × 1 金币」线性计入，不碰股市。

## 用法

```text
python tools/economy_sim.py --ticks 1800 --params both --seed 20261006 --runs 200
python tools/economy_sim.py --ticks 1800 --params after       # 只看源码现值（本版）
python tools/economy_sim.py --ticks 1800 --params before      # 只看上一版（严厉）
python tools/economy_sim.py --ticks 1800 --params original    # 只看最初版
python tools/economy_sim.py --ticks 18000 --params both
python tools/economy_sim.py --ticks 1800 --params after --json   # 机器可读
```

输出 Markdown 表格到 stdout。**只读源码、不改任何 Java / Gradle 文件。**

## 单位口径

* 内部一律用**分**（`long`/`int`），和 Java 一致 —— ×400 杠杆时爆仓线离现价只有 `40%÷400 = 0.1%`，
  用浮点金币算会算错爆仓。只有打印时除以 100 变成**金币**（两位小数）。
* 「1 小时游戏时间 = 1800 tick」；跳价间隔 40 tick ⇒ **45 跳/小时**。
  所以所有「每跳」概率换算成真实时间要 ×2 秒。
* 种子固定、随机源单一 `random.Random`。**同一份种子下，两组参数在绝大多少跳上消耗的抽签次数一致**
  （「插针」那条即使 before 关着也照样抽命中判定），所以 before / after 基本是逐跳对拍。
  ⚠️ **唯一的例外**：after 组真的抽中插针时会多消耗 4 次（跌幅 / 回补跳数 / 回补比例 / 新闻 id），
  此后两组的随机序列会错位 —— 实测 3000 跳里差 34 次（约 4~5 次插针）。
  这是插针的**必然代价**，不是 bug；结论层面（两边都是负期望）不受影响。
"""
from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
from dataclasses import dataclass, replace
from typing import Callable, Dict, List, Optional, Sequence, Tuple

# ⚠️ Windows 控制台默认是 GBK，中文 + 表格字符会直接 UnicodeEncodeError。
#    这里强制 stdout 走 UTF-8（重定向到文件时同样生效）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:       # pragma: no cover - 老环境没有 reconfigure 就算了
    pass

# ==========================================================================================
# 一、常量（**逐条对应 StockDefs.java**，改动前请先回去核对源码）
# ==========================================================================================

COUNT = 3
IDS = ("diamond_mine", "redstone_tech", "nether_trade")
NAMES = ("钻石矿业", "红石科技", "下界贸易")

BASE_CENTS = (10_000, 5_000, 2_000)          # 基准价（分）：100.00 / 50.00 / 20.00
VOLATILITY = (0.006, 0.012, 0.025)           # σ：稳健 0.6% / 波动 1.2% / 激进 2.5%
BETA = (0.7, 1.0, 1.5)                       # 对大盘 bias 的敏感度

# ---- 下面这七个是「正在调的经济旋钮」：**直接从 Java 源码解析**，不再手工抄 ----
#      理由：这些数改了三轮（原版 → 收紧 → 回调），脚本里再抄一份必然对不上。

def _java(rel_path: str, pattern: str, cast=float):
    """从 Java 源码里抠一个常量；找不到就**直接报错**（宁可不跑，也不用过期数值）。"""
    import re
    from pathlib import Path
    root = Path(__file__).resolve().parents[1] / "src/main/java/com/qiushaonan/mcstock"
    source = (root / rel_path).read_text(encoding="utf-8")
    found = re.search(pattern, source)
    if found is None:
        raise SystemExit(f"economy_sim: 在 {rel_path} 里找不到 {pattern!r} —— 源码改过了，请更新本脚本的正则")
    return cast(found.group(1))


PRICE_TICK_TICKS = 40                        # 40 tick = 2 秒跳一次价
MEAN_REVERSION = _java("market/StockDefs.java", r"MEAN_REVERSION = ([\d.]+)")       # 往基准价回归的比例
MAX_STEP = 0.25                              # 单跳 ±25%
EVENT_CHANCE = 0.0025                        # 每只股票**每跳** 0.25%（≈ 13 分钟/只）
VOLATILE_EVENT_MULTIPLIER = 2.0

CLUSTER_CHANCE = 0.30
CLUSTER_MAX_CHAIN = 3
CLUSTER_MIN_DELAY = 1
CLUSTER_MAX_DELAY = 3

# ---- 2026-10-06 源码新增（见 StockDefs 注释） ----
BEARISH_EVENT_SCALE = _java("market/StockDefs.java", r"BEARISH_EVENT_SCALE = ([\d.]+)")
SPIKE_CHANCE = _java("market/StockDefs.java", r"SPIKE_CHANCE = ([\d.]+)")           # 每只股票每跳
SPIKE_MIN_DROP = 0.08
SPIKE_MAX_DROP = 0.15
SPIKE_REBOUND_MIN = 0.60
SPIKE_REBOUND_MAX = 0.80
SPIKE_REBOUND_MIN_JUMPS = 3
SPIKE_REBOUND_MAX_JUMPS = 5
SPIKE_NEWS_IDS = ("terminal_glitch", "large_sell_order")

FLOOR_RATIO = 0.02
CEIL_RATIO = 5.0

FEE_RATE = _java("market/StockDefs.java", r"FEE_RATE = ([\d.]+)")            # 仓位价值的费率，开平各一次
MARGIN_CALL_PERCENT = _java("market/StockDefs.java", r"MARGIN_CALL_PERCENT = (\d+)", int)
LEVERAGE_MIN, LEVERAGE_MAX = 1, 400
QTY_MIN, QTY_MAX = 1, 100_000

PURCHASE_MARKUP = _java("market/ItemValuator.java", r"PURCHASE_MARKUP = ([\d.]+)")   # 购买价 = ceil(估价 × 它)
BULL_BIAS = _java("market/MarketRegime.java", r'BULL\("bull",\s*([-\d.]+)')
BEAR_BIAS = _java("market/MarketRegime.java", r'BEAR\("bear",\s*([-\d.]+)')
COBBLESTONE_COINS = 1                        # core.json: minecraft:cobblestone = 1

# ⚠️ 唯一的**假设值**（不在源码里）：玩家挖矿效率，个圆石/分钟。
#    1 分钟 = 1200 tick；回收价 = 估价 × 1.00（StockService.recycle: unit = ItemValuator.value）。
#    三档敏感性（32 / 64 / 128）见文档；--mining 可改。
CURRENT_MINING_RATE = 64.0

STEP_SHARE = 0.70                            # MarketShock：一条冲击里「当场跳变」的占比
SHOCK_MAX_MAGNITUDE = 8.0                    # 事件表幅度区间（静态自检用）
SHOCK_MIN_MAGNITUDE = 3.0

# MarketRegime：id → (bias, volMultiplier, minJumps, maxJumps)
# ⚠️ bias 那两格由下面的参数组覆盖（original / before / after 各不相同），
#    这里的默认值只用于「没被覆盖时」的兜底 —— 直接取源码现值。
REGIMES = {
    "calm": (0.0, 1.0, 60, 180),
    "bull": (BULL_BIAS, 1.0, 60, 300),
    "bear": (BEAR_BIAS, 1.15, 60, 240),
    "volatile": (-0.0004, 2.0, 30, 120),
}
REGIME_ORDER = ("calm", "bull", "bear", "volatile")
# MarketRegime.TRANSITIONS：行和 = 1（源码里有静态自检）
TRANSITIONS = {
    "calm":     (0.45, 0.25, 0.20, 0.10),
    "bull":     (0.40, 0.30, 0.10, 0.20),
    "bear":     (0.40, 0.10, 0.30, 0.20),
    "volatile": (0.40, 0.20, 0.20, 0.20),
}

# 事件类型（EventType）：成簇时「再抽一条同类型」用；MARKET 不参与成簇
TYPE_COMPANY, TYPE_INDUSTRY, TYPE_MACRO, TYPE_RUMOR, TYPE_MARKET = (
    "company", "industry", "macro", "rumor", "market")


@dataclass(frozen=True)
class EventDef:
    """StockEventTable.EventDef 的镜像。"""
    id: str
    type: str
    direction: int              # +1 利好 / -1 利空
    magnitude: float            # 累计幅度（该股票自己的 σ 倍数）
    half_life: int
    sigma_multiple: float
    targets: Tuple[Tuple[int, float], ...]
    clarify_after: int = 0      # 传闻专用
    clarify_factor: float = 0.0

    def weight(self) -> float:
        """StockEventTable.weightOf：由幅度推出权重（9 − 幅度），小事件更常见。"""
        return max(0.5, 9.0 - self.magnitude)


# StockEventTable.TABLE 的三张表，顺序 = 股票下标
EVENT_TABLE: Tuple[Tuple[EventDef, ...], ...] = (
    # ---------- 0 钻石矿业（稳健 σ=0.6%） ----------
    (
        EventDef("mine_collapse", TYPE_COMPANY, -1, 6.0, 30, 2.0, ((0, 1.0),)),
        EventDef("giant_vein", TYPE_COMPANY, +1, 6.0, 45, 1.8, ((0, 1.0),)),
        EventDef("deep_core_tech", TYPE_INDUSTRY, +1, 5.0, 60, 1.5, ((0, 1.0), (1, 0.3))),
        EventDef("smuggling_raid", TYPE_MACRO, -1, 4.5, 25, 2.2, ((0, 1.0), (2, 0.4))),
        EventDef("miners_strike", TYPE_COMPANY, -1, 3.5, 20, 1.6, ((0, 1.0),)),
        EventDef("vein_rumor", TYPE_RUMOR, +1, 5.0, 25, 2.0, ((0, 1.0),), 18, 0.70),
    ),
    # ---------- 1 红石科技（波动 σ=1.2%） ----------
    (
        EventDef("patent_granted", TYPE_COMPANY, +1, 5.0, 40, 1.8, ((1, 1.0),)),
        EventDef("circuit_short", TYPE_COMPANY, -1, 5.0, 25, 2.0, ((1, 1.0),)),
        EventDef("military_order", TYPE_MACRO, +1, 7.0, 50, 2.4, ((1, 1.0), (0, 0.35))),
        EventDef("engineer_poached", TYPE_COMPANY, -1, 4.0, 30, 1.6, ((1, 1.0),)),
        EventDef("automation_line", TYPE_INDUSTRY, +1, 3.5, 60, 1.5, ((1, 1.0), (2, 0.25))),
        EventDef("order_rumor", TYPE_RUMOR, +1, 6.0, 20, 2.6, ((1, 1.0), (0, 0.2)), 15, 0.75),
    ),
    # ---------- 2 下界贸易（激进 σ=2.5%） ----------
    (
        EventDef("portal_congestion", TYPE_INDUSTRY, -1, 4.0, 20, 2.0, ((2, 1.0), (0, -0.25))),
        EventDef("piglin_caravan", TYPE_COMPANY, +1, 5.0, 30, 2.0, ((2, 1.0),)),
        EventDef("blaze_shortage", TYPE_INDUSTRY, +1, 4.5, 35, 2.2, ((2, 1.0), (1, -0.3))),
        EventDef("tariff_hike", TYPE_MACRO, -1, 6.0, 45, 2.4, ((2, 1.0), (0, 0.3), (1, 0.3))),
        EventDef("fortress_route", TYPE_MACRO, +1, 8.0, 60, 2.6, ((2, 1.0), (0, 0.2), (1, 0.2))),
        EventDef("route_rumor", TYPE_RUMOR, +1, 6.5, 25, 2.8, ((2, 1.0), (0, 0.15)), 20, 0.70),
    ),
)

# 成簇用：(股票, 行号) 按类型分组
BY_TYPE: Dict[str, List[Tuple[int, int]]] = {}
for _s, _defs in enumerate(EVENT_TABLE):
    for _i, _d in enumerate(_defs):
        BY_TYPE.setdefault(_d.type, []).append((_s, _i))


# ==========================================================================================
# 二、参数组：before（改动前，硬编码） vs after（源码现值）
# ==========================================================================================

@dataclass(frozen=True)
class Params:
    """一组可调经济参数。

    默认值 = **当前源码真实值**（2026-10-05 21:12 的状态）。
    要跑「改动前」的历史值请用 {@link before_params}。
    """
    name: str = "after"
    fee_rate: float = FEE_RATE
    purchase_markup: float = PURCHASE_MARKUP
    mean_reversion: float = MEAN_REVERSION
    margin_call_percent: int = MARGIN_CALL_PERCENT
    bull_bias: float = BULL_BIAS
    bear_bias: float = BEAR_BIAS
    bad_news_scale: float = BEARISH_EVENT_SCALE   # 利空事件幅度倍率（利好不变）
    spike_enabled: bool = True                    # 插针（源码已落地）
    spike_chance: float = SPIKE_CHANCE
    spike_min_drop: float = SPIKE_MIN_DROP
    spike_max_drop: float = SPIKE_MAX_DROP
    spike_rebound_min: float = SPIKE_REBOUND_MIN
    spike_rebound_max: float = SPIKE_REBOUND_MAX
    spike_rebound_jumps_min: int = SPIKE_REBOUND_MIN_JUMPS
    spike_rebound_jumps_max: int = SPIKE_REBOUND_MAX_JUMPS

    def regime_bias(self, regime: str) -> float:
        if regime == "bull":
            return self.bull_bias
        if regime == "bear":
            return self.bear_bias
        return REGIMES[regime][0]


def original_params() -> Params:
    """**最初版**（这次经济调整之前，源码里已经查不到了）。

    手续费 0.1% / 购买 ×1.15 / 均值回归 1.5% / 爆仓线 50% /
    牛 +0.12% 熊 −0.10% / 利空 ×1.0 / **没有插针**。
    """
    return Params(
        name="original",
        fee_rate=0.001,
        purchase_markup=1.15,
        mean_reversion=0.015,
        margin_call_percent=50,
        bull_bias=0.0012,
        bear_bias=-0.0010,
        bad_news_scale=1.0,
        spike_enabled=False,
    )


def before_params() -> Params:
    """**上一版（严厉）**：2026-10-06 第一次收紧之后的状态。

    手续费 0.35% / 购买 ×1.25 / 均值回归 3% / 爆仓线 40% /
    牛 +0.06% 熊 −0.16% / 利空 ×1.15 / 插针 0.05%。
    玩家反馈「赚钱太难了」就是对这一版说的。
    """
    return Params(
        name="before",
        fee_rate=0.0035,
        purchase_markup=1.25,
        mean_reversion=0.03,
        margin_call_percent=40,
        bull_bias=0.0006,
        bear_bias=-0.0016,
        bad_news_scale=1.15,
        spike_chance=0.0005,
    )


def after_params() -> Params:
    """**源码现值**（= 回调之后真正生效的数值，全部由文件头的常量解析而来）。

    改源码 → 这里自动跟着变；解析失败会直接报错，不会用过期数值糊弄。
    """
    return Params(name="after")


def all_param_groups() -> List[Params]:
    """两组对照：before（上一版·严厉）/ after（源码现值·回调后）。"""
    return [before_params(), after_params()]


def every_param_group() -> List[Params]:
    """三档全跑：original（最初版）/ before（上一版）/ after（源码现值）。"""
    return [original_params(), before_params(), after_params()]


# 参数组在表格里的显示名
PARAM_LABELS = {
    "original": "original（最初版）",
    "before": "before（上一版·严厉）",
    "after": "after（本版·回调后）",
}


# ==========================================================================================
# 三、仓位数学：**逐条对应 PositionMath.java / TradeService.java**
# ==========================================================================================

def price_floor(stock: int) -> int:
    return max(1, int(BASE_CENTS[stock] * FLOOR_RATIO))


def price_ceil(stock: int) -> int:
    return int(BASE_CENTS[stock] * CEIL_RATIO)


def fee_for(notional_cents: int, p: Params) -> int:
    """手续费 = |notional| × 费率，**向上取整**（Java 用 Math.ceil）。"""
    import math
    return int(math.ceil(abs(notional_cents) * p.fee_rate))


def margin_for(price_cents: int, qty: int, leverage: int) -> int:
    return max(1, price_cents * qty // max(1, leverage))


def max_affordable(cash_cents: int, price_cents: int, leverage: int, p: Params) -> int:
    """PositionMath.maxAffordable：qty × (1/杠杆 + 费率) ≤ 现金。"""
    if price_cents <= 0 or cash_cents <= 0:
        return 0
    per_share = price_cents / max(1, leverage) + price_cents * p.fee_rate
    if per_share <= 0:
        return 0
    return int(max(0, min(QTY_MAX, cash_cents // per_share)))


@dataclass
class Position:
    """Position.java 的镜像（一个仓开出后就不再变）。"""
    pid: int
    stock: int
    shares: int          # 有符号：正 = 多
    entry_cents: int
    margin_cents: int
    leverage: int

    def pnl(self, price_cents: int) -> int:
        return self.shares * (price_cents - self.entry_cents)

    def liquidated(self, price_cents: int, p: Params) -> bool:
        """PositionMath.liquidated：浮亏 × 100 ≥ 保证金 × 爆仓线百分比。"""
        if self.shares == 0 or self.margin_cents <= 0:
            return False
        loss = -self.pnl(price_cents)
        return loss * 100 >= self.margin_cents * p.margin_call_percent

    def close_fee(self, price_cents: int, p: Params) -> int:
        return fee_for(price_cents * abs(self.shares), p)


# ==========================================================================================
# 四、账户：现金 + 仓位列表（TradeService 的三个入口）
# ==========================================================================================

class Account:
    """`cash + Σ(保证金 + 浮盈)` 就是净值；现金**允许为负**（穿仓 → 负债）。"""

    def __init__(self, cash_cents: int):
        self.cash = cash_cents
        self.positions: List[Position] = []
        self.next_id = 1

    # ---- TradeService.open ----
    def open(self, stock: int, price_cents: int, qty: int, leverage: int,
             is_long: bool, p: Params) -> Tuple[bool, int, int]:
        """返回 (是否成交, 实际股数, 保证金)。余额不够付「保证金 + 手续费」就整笔失败。"""
        if qty <= 0:
            return False, 0, 0
        want = max(QTY_MIN, min(QTY_MAX, qty))
        lev = max(LEVERAGE_MIN, min(LEVERAGE_MAX, leverage))
        margin = margin_for(price_cents, want, lev)
        fee = fee_for(price_cents * want, p)
        need = margin + fee
        if self.cash < need:
            return False, 0, margin
        self.cash -= need
        self.positions.append(Position(self.next_id, stock,
                                       want if is_long else -want,
                                       price_cents, margin, lev))
        self.next_id += 1
        return True, want, margin

    # ---- TradeService.close ----
    def close(self, pos: Position, price_cents: int, p: Params) -> int:
        realized = pos.pnl(price_cents)
        fee = pos.close_fee(price_cents, p)
        self.positions.remove(pos)
        self.cash += pos.margin_cents + realized - fee
        return realized - fee

    # ---- TradeService.liquidate（⚠️ **不收平仓费**，且不夹到 0） ----
    def liquidate(self, pos: Position, price_cents: int) -> int:
        delta = pos.margin_cents + pos.pnl(price_cents)
        self.positions.remove(pos)
        self.cash += delta
        return delta

    def settle_liquidations(self, prices: Sequence[int], p: Params) -> int:
        """StockService.settleLiquidations：逐仓检查，按**跳价后的真实价格**结算。"""
        n = 0
        for pos in list(self.positions):
            if pos.liquidated(prices[pos.stock], p):
                self.liquidate(pos, prices[pos.stock])
                n += 1
        return n

    def equity(self, prices: Sequence[int]) -> int:
        total = self.cash
        for pos in self.positions:
            total += pos.margin_cents + pos.pnl(prices[pos.stock])
        return total


# ==========================================================================================
# 五、行情引擎：**逐行对应 MarketEngine.java** 的抽签顺序与护栏顺序
# ==========================================================================================

class MarketEngine:
    """一跳里发生什么（顺序不许改）：

    1. 大盘状态推进（到点换状态 + 播新闻）；
    2. 到期的传闻澄清（注册一条**反向**冲击）；
    3. 到期的成簇事件（同类型再抽一条，最多连 3 条）；
    4. 三只股票各算一跳：随机消耗顺序 = ① 高斯噪声 → ② 事件抽签 → ③（命中时）成簇抽签
       → ④（插针开关，见下）；
    5. 清理衰减完的冲击。

    ⚠️ **唯一一处实现差异**：Java 用 {@code java.util.Random.nextGaussian()}（历史遗留的
    polar 法），Python 标准库没有它，这里用 **Box–Muller** 代替。两者都是标准正态，
    但**不是同一个分布序列** —— 所以本脚本的绝对数值不能和 Java 逐位对拍，
    只能对拍「分布特性」与「公式口径」。所有抽签的**次数与顺序**严格一致。
    """

    def __init__(self, seed: int, p: Params):
        self.p = p
        self.rng = random.Random(seed)
        self._spare: Optional[float] = None     # Box–Muller 的第二个样本
        self.price = list(BASE_CENTS)
        self.regime = "calm"
        self.regime_jumps_left = self._draw_duration(self.regime)
        self.shocks: List[dict] = []
        self.pending_cluster: Optional[dict] = None
        self.pending_clarifies: List[dict] = []
        self.rebounds: List[dict] = []      # 插针回补待办（PendingRebound）
        self.jumps = 0
        self.event_count = 0
        self.up_event_count = 0      # 其中「利好」事件（策略 A 的入场信号）
        self.down_event_count = 0
        self.spike_count = 0

    def _gauss(self) -> float:
        """标准正态（Box–Muller）。每次消耗 2 个 uniform、产出 2 个样本（缓存一个）——
        **注意这是每次调用都抽 2 个**，所以随机消耗节奏与 Java 的 nextGaussian 不同；
        但同一份种子下 before / after 在**绝大多数跳**上消耗一致（before 也照样抽命中判定），
        所以两组基本是逐跳对拍；**唯一例外**是真的抽中插针时 after 会多消耗 4 次，
        此后随机序列错位。这是插针的必然代价，不影响「两边都是负期望」的结论。"""
        if self._spare is not None:
            v = self._spare
            self._spare = None
            return v
        import math
        u1 = 0.0
        while u1 <= 0.0:
            u1 = self.rng.random()
        u2 = self.rng.random()
        r = math.sqrt(-2.0 * math.log(u1))
        self._spare = r * math.sin(2.0 * math.pi * u2)
        return r * math.cos(2.0 * math.pi * u2)

    # ---------- 大盘 ----------
    def _draw_duration(self, regime: str) -> int:
        lo, hi = REGIMES[regime][2], REGIMES[regime][3]
        return lo + self.rng.randrange(hi - lo + 1)

    def _advance_regime(self, jump: int) -> None:
        self.regime_jumps_left -= 1
        if self.regime_jumps_left > 0:
            return
        target = self.rng.random()     # 消耗一次（即使不换状态也要对齐：这里只在到点时才抽）
        row = TRANSITIONS[self.regime]
        acc = target
        nxt = "calm"
        for i, prob in enumerate(row):
            acc -= prob
            if acc < 0.0:
                nxt = REGIME_ORDER[i]
                break
        self.regime = nxt
        self.regime_jumps_left = self._draw_duration(nxt)

    # ---------- 冲击（MarketShock） ----------
    @staticmethod
    def _age(shock: dict, jump: int) -> int:
        return max(0, jump - shock["start_jump"])

    @classmethod
    def _decay(cls, shock: dict, jump: int) -> float:
        return 2.0 ** (-cls._age(shock, jump) / shock["half_life"])

    @classmethod
    def _alive(cls, shock: dict, jump: int) -> bool:
        return cls._age(shock, jump) < shock["half_life"] * 3

    @classmethod
    def _tail_norm(cls, shock: dict) -> float:
        """⚠️ 用**离散**系数 1 − 2^(−1/半衰期)，不是连续积分的 ln2/半衰期。
        这样整条冲击的累计影响恰好等于 magnitude × σ（源码单测会断言这一点）。"""
        return 1.0 - 2.0 ** (-1.0 / shock["half_life"])

    @classmethod
    def _drift_units(cls, shock: dict, jump: int) -> float:
        age = cls._age(shock, jump)
        step = shock["direction"] * shock["magnitude"] * STEP_SHARE if age == 0 else 0.0
        tail = (shock["direction"] * shock["magnitude"] * (1.0 - STEP_SHARE)
                * cls._tail_norm(shock) * cls._decay(shock, jump))
        return step + tail

    @classmethod
    def _sigma_scale(cls, shock: dict, jump: int) -> float:
        return 1.0 + (shock["sigma_multiple"] - 1.0) * cls._decay(shock, jump)

    # ---------- 事件注册 ----------
    def _scale_for(self, direction: int) -> float:
        """after 的「利空事件幅度 ×1.15」（BEARISH_EVENT_SCALE）：只放大 direction < 0 的事件。"""
        return self.p.bad_news_scale if direction < 0 else 1.0

    def _register_event(self, home_stock: int, index: int, jump: int, depth: int,
                        roll_cluster: bool) -> None:
        stamp = self._scale_for(EVENT_TABLE[home_stock][index].direction)
        self._register_event_scaled(home_stock, index, jump, depth, roll_cluster, stamp)

    def _register_event_scaled(self, home_stock: int, index: int, jump: int, depth: int,
                               roll_cluster: bool, stamp: float) -> None:
        d = EVENT_TABLE[home_stock][index]
        for target_stock, weight in d.targets:
            signed = d.magnitude * weight * stamp
            direction = d.direction if signed >= 0.0 else -d.direction
            self.shocks.append({
                "stock": target_stock, "event_id": d.id, "direction": direction,
                "start_jump": jump, "half_life": d.half_life,
                "magnitude": abs(signed), "sigma_multiple": d.sigma_multiple,
                "clarification": False,
            })
        self.event_count += 1
        if d.direction > 0:
            self.up_event_count += 1
        else:
            self.down_event_count += 1
        if d.type == TYPE_RUMOR:
            self.pending_clarifies.append({
                "stock": home_stock, "index": index,
                "at_jump": jump + d.clarify_after, "factor": d.clarify_factor,
            })
        if roll_cluster:
            self._schedule_cluster(d.type, jump, depth)

    def _schedule_cluster(self, etype: str, jump: int, depth: int) -> None:
        if depth >= CLUSTER_MAX_CHAIN or etype == TYPE_MARKET:
            return
        if self.rng.random() >= CLUSTER_CHANCE:
            return
        span = CLUSTER_MAX_DELAY - CLUSTER_MIN_DELAY + 1
        delay = CLUSTER_MIN_DELAY + self.rng.randrange(span)
        self.pending_cluster = {"type": etype, "at_jump": jump + delay, "depth": depth + 1}

    def _fire_pending_cluster(self, jump: int) -> None:
        pc = self.pending_cluster
        if pc is None or jump < pc["at_jump"]:
            return
        self.pending_cluster = None
        flat = BY_TYPE.get(pc["type"], [])
        if not flat:
            return
        s, i = flat[self.rng.randrange(len(flat))]
        self._register_event(s, i, jump, pc["depth"], True)

    def _fire_due_clarifications(self, jump: int) -> None:
        """传闻澄清：方向反过来、幅度打 0.6~0.8 折（factor）。"""
        still: List[dict] = []
        for pc in self.pending_clarifies:
            if jump < pc["at_jump"]:
                still.append(pc)
                continue
            d = EVENT_TABLE[pc["stock"]][pc["index"]]
            stamp = self._scale_for(d.direction)
            for target_stock, weight in d.targets:
                signed = d.magnitude * weight * pc["factor"] * stamp
                direction = -d.direction if signed >= 0.0 else d.direction
                self.shocks.append({
                    "stock": target_stock, "event_id": d.id, "direction": direction,
                    "start_jump": jump, "half_life": d.half_life,
                    "magnitude": abs(signed), "sigma_multiple": d.sigma_multiple,
                    "clarification": True,
                })
        self.pending_clarifies = still

    # ---------- 跳价 ----------
    def advance(self) -> Tuple[List[int], Dict[int, int]]:
        """跳一次价。返回 (新价格列表, {股票: 这一跳触发的利好事件行号})。"""
        jump = self.jumps + 1
        price = self.price
        pos_events: Dict[int, int] = {}

        # ① 大盘 → ② 传闻澄清 → ③ 成簇
        self._advance_regime(jump)
        self._fire_due_clarifications(jump)
        self._fire_pending_cluster(jump)

        bias = self.p.regime_bias(self.regime)
        vol_mult = REGIMES[self.regime][1]
        new_price = [0] * COUNT

        for s in range(COUNT):
            current = price[s]
            base = float(BASE_CENTS[s])
            sigma = VOLATILITY[s]

            # ⚠️⚠️ 随机消耗顺序**就是协议**（源码注释原文）：
            #     ① 高斯噪声 → ② 事件抽签 → ③ 插针抽签 → ④（命中时）成簇抽签
            #     顺序挪一位，读档重放就对不上；这里也一样，别改。
            noise = self._gauss()
            chance = EVENT_CHANCE * (VOLATILE_EVENT_MULTIPLIER if self.regime == "volatile" else 1.0)
            hit = -1
            if self.rng.random() < chance:
                hit = self._pick_event(s, self.rng.random())
                self._register_event(s, hit, jump, 1, True)
            spike = self._draw_spike(s, jump)

            # 均价回归 + 大盘基础漂移
            drift = self.p.mean_reversion * (base - current) / base + bias * BETA[s]
            # 插针：命中那一跳当场砸下去（负 drift），回补在后续 3~5 跳摊平
            drift += spike
            drift += self._rebound_drift(s, jump)
            # 叠加所有活着的冲击
            sigma_scale = 1.0
            for shock in self.shocks:
                if shock["stock"] != s or not self._alive(shock, jump):
                    continue
                drift += self._drift_units(shock, jump) * sigma
                sigma_scale = max(sigma_scale, self._sigma_scale(shock, jump))
            drift += noise * sigma * vol_mult * sigma_scale

            # 护栏顺序：先把**所有**贡献（含插针与回补）并进 drift → 夹 ±MAX_STEP
            # → 再把取整后的价格夹回 [ceil(p×0.75), floor(p×1.25)] → 最后夹价格区间
            drift = max(-MAX_STEP, min(MAX_STEP, drift))
            lo = int(-(-current * (1.0 - MAX_STEP) // 1))    # ceil(current × 0.75)
            hi = int(current * (1.0 + MAX_STEP) // 1)        # floor(current × 1.25)
            nxt = max(lo, min(hi, int(round(current * (1.0 + drift)))))
            nxt = max(price_floor(s), min(price_ceil(s), nxt))
            new_price[s] = nxt

            if hit >= 0 and EVENT_TABLE[s][hit].direction > 0:
                pos_events[s] = hit

        self.price = new_price
        self.jumps = jump
        self.shocks = [sh for sh in self.shocks if self._alive(sh, jump)]
        # 插针回补跑完的也清掉（回补区间是 [startJump, startJump+jumps)）
        self.rebounds = [rb for rb in self.rebounds if jump < rb["start_jump"] + rb["jumps"]]
        return new_price, pos_events

    def _draw_spike(self, stock: int, jump: int) -> float:
        """插针抽签（`MarketEngine#drawSpike`）：返回这一跳要并进 drift 的**负 drift 项**。

        ⚠️ 源码里跌幅是**价格百分比**（`-8%~-15%`）直接当 drift 用，不是 σ 倍数，
        也不是「乘到价格上」——它和均值回归 / 大盘 bias / 冲击 / 噪声一起进同一个
        `clamp(±MAX_STEP)`，所以插针同样受 ±25% 单跳护栏约束。

        ⚠️ 随机消耗：命中判定 1 次；命中后再 4 次（跌幅 / 回补跳数 / 回补比例 / 新闻 id）。
        **before 组关着插针，但照样抽前面那一次命中判定** —— 这样两组参数在时间轴上
        的抽签序列完全一致，才能逐跳对拍（否则差异分不清是参数还是随机性造成的）。
        """
        roll = self.rng.random()
        if not self.p.spike_enabled or roll >= self.p.spike_chance:
            return 0.0
        drop = self.p.spike_min_drop + self.rng.random() * (
            self.p.spike_max_drop - self.p.spike_min_drop)
        span = self.p.spike_rebound_jumps_max - self.p.spike_rebound_jumps_min + 1
        rebound_jumps = self.p.spike_rebound_jumps_min + self.rng.randrange(span)
        factor = self.p.spike_rebound_min + self.rng.random() * (
            self.p.spike_rebound_max - self.p.spike_rebound_min)
        self.rng.randrange(len(SPIKE_NEWS_IDS))     # 挑新闻 id（消耗对齐）
        # 回补**从下一跳**开始，连续 rebound_jumps 跳，每跳补 drop×factor/rebound_jumps
        self.rebounds.append({
            "stock": stock, "start_jump": jump + 1, "jumps": rebound_jumps,
            "per_jump": drop * factor / rebound_jumps,
        })
        self.spike_count += 1
        return -drop

    def _rebound_drift(self, stock: int, jump: int) -> float:
        """这一跳该股票身上「插针回补」的 drift（**正数**，按 3~5 跳线性摊平）。"""
        total = 0.0
        for rb in self.rebounds:
            if rb["stock"] != stock:
                continue
            age = jump - rb["start_jump"]
            if 0 <= age < rb["jumps"]:
                total += rb["per_jump"]
        return total

    @staticmethod
    def _pick_event(stock: int, roll01: float) -> int:
        """StockEventTable.pick：按 weightOf = max(0.5, 9 − 幅度) 加权。"""
        defs = EVENT_TABLE[stock]
        total = sum(d.weight() for d in defs)
        target = roll01 * total
        for i, d in enumerate(defs):
            target -= d.weight()
            if target < 0.0:
                return i
        return len(defs) - 1


# ==========================================================================================
# 六、四条策略
# ==========================================================================================

START_CASH_CENTS = 100_000          # 1000.00 金币
TICKS_PER_JUMP = PRICE_TICK_TICKS


@dataclass
class RunResult:
    """一次模拟（一个种子、一条策略）的结果。全部**分**为单位。"""
    name: str
    equity_series: List[int]              # 每跳采样一次净值
    liquidations: int
    max_drawdown_cents: int               # 正数 = 从峰值回落了多少
    liq_jumps: List[int]                  # 每次爆仓发生在第几跳
    trade_returns: List[float]            # 策略 A 用：每个持仓的净值收益率
    settled_trades: int
    a_liquidated_trades: int = 0          # 策略 A 里被爆掉、但也计入了 trade_returns 的笔数
    ruin_jump: int = 0                    # 净值第一次 ≤ 0 的跳数（0 = 从未亏光）


def run_strategy(strategy: str, p: Params, seed: int, ticks: int,
                 leverage_override: Optional[int] = None) -> RunResult:
    """跑一条策略。ticks = 游戏 tick 数（1800 = 1 小时）。"""
    engine = MarketEngine(seed, p)
    acct = Account(START_CASH_CENTS)

    # 每条策略一本账：# 跳价次数 → 该次跳价后要做的动作
    jumps_total = ticks // TICKS_PER_JUMP

    equity_series: List[int] = []
    liquidations = 0
    liq_jumps: List[int] = []
    trade_returns: List[float] = []
    settled = 0
    peak = START_CASH_CENTS
    max_dd = 0
    # 「多久把 1000 金币亏光」：净值第一次 ≤ 0 的那一跳（0 = 从未亏光）
    ruin_jump = 0

    def note_ruin(jump_no: int, equity_cents: int) -> None:
        """账户净值第一次掉到 ≤ 0 就记下跳数（只记第一次）。"""
        nonlocal ruin_jump
        if ruin_jump == 0 and equity_cents <= 0:
            ruin_jump = jump_no

    # ---------- 策略初始化 ----------
    # A：等第一条利好事件；B：开局 all-in ×400；C：每 10 跳一个往返；D：什么都不做
    lev_b = leverage_override if leverage_override is not None else 400
    if strategy == "B":
        prices = list(BASE_CENTS)
        qty = max_affordable(acct.cash, prices[0], lev_b, p)
        acct.open(0, prices[0], qty, lev_b, True, p)

    a_pos: Optional[Position] = None       # 策略 A 当前持有的仓
    a_entry_jump = 0
    a_value_before = 0                     # 开仓前的账户净值（算「每个持仓的收益率」用）
    a_start_margin = 0                     # 这一笔投入的保证金
    a_liq_count = 0                        # 策略 A 里被爆掉的笔数
    c_pos: Optional[Position] = None       # 策略 C 当前那一股

    def record_a_trade(equity_after_cents: int) -> None:
        """收口一笔策略 A 的交易：收益率 = 这笔的净值变化 ÷ 开仓前账户净值。

        ⚠️ 爆仓与正常平仓**必须走同一个出口**：只记正常平仓的话，样本会被
        「活下来的赢家」系统性污染（生存者偏差），期望收益会算成正的。
        """
        nonlocal a_pos, settled
        if a_pos is None:
            return
        trade_returns.append((equity_after_cents - a_value_before) / max(1, a_value_before))
        a_pos = None
        settled += 1

    mining_per_tick = 0.0
    if strategy == "D":
        # 劳动收入：每分钟 64 个圆石 × 1 金币 → 每 tick 64/1200 金币
        mining_cobble_per_min = CURRENT_MINING_RATE
        mining_per_tick = mining_cobble_per_min * COBBLESTONE_COINS * 100.0 / 1200.0

    for tick in range(1, ticks + 1):
        jumped = (tick % TICKS_PER_JUMP == 0) and (tick // TICKS_PER_JUMP <= jumps_total)
        pos_events: Dict[int, int] = {}

        if jumped:
            jump_no = tick // TICKS_PER_JUMP
            # 跳价**前**净值（上一跳冲击已经落地、这一跳还没发生）
            eq_now = acct.equity(engine.price)
            note_ruin(max(0, jump_no - 1), eq_now)
            equity_series.append(eq_now)
            peak = max(peak, eq_now)
            max_dd = max(max_dd, peak - eq_now)

            prices, pos_events = engine.advance()

            # 服务端顺序：跳价完 → 先爆仓结算 → 再让玩家下单
            n = acct.settle_liquidations(prices, p)
            if n:
                liquidations += n
                liq_jumps.extend([jump_no] * n)

            # ⚠️ 爆仓那一跳，账户净值是在**结算之后**才崩的：必须在这里再采一次样，
            #    否则 ×400 那种「一跳打光」在最大回撤里根本看不见。
            eq_after = acct.equity(prices)
            note_ruin(jump_no, eq_after)
            equity_series.append(eq_after)
            peak = max(peak, eq_after)
            max_dd = max(max_dd, peak - eq_after)

            # ---------- 策略 A ----------
            if strategy == "A":
                if a_pos is not None and a_pos not in acct.positions:
                    # 这一跳被爆掉了 —— 同样计成一笔已结算的交易（不能丢，否则只剩赢家）
                    a_liq_count += 1
                    record_a_trade(acct.equity(prices))
                if a_pos is not None and jump_no - a_entry_jump >= 60:
                    acct.close(a_pos, prices[a_pos.stock], p)
                    record_a_trade(acct.equity(prices))
                if a_pos is None and pos_events:
                    stock = min(pos_events.keys())   # 多只同时利好时取小下标（可复现）
                    price = prices[stock]
                    qty = max_affordable(acct.cash, price, 50, p)
                    a_value_before = max(1, acct.equity(prices))
                    ok, got, margin = acct.open(stock, price, qty, 50, True, p)
                    if ok:
                        a_pos = acct.positions[-1]
                        a_entry_jump = jump_no
                        a_start_margin = margin

            # ---------- 策略 C ----------
            if strategy == "C":
                if c_pos is not None:
                    acct.close(c_pos, prices[c_pos.stock], p)
                    c_pos = None
                    settled += 1
                if jump_no % 10 == 0:
                    price = prices[0]
                    qty = max_affordable(acct.cash, price, 1, p)
                    if qty >= 1:
                        ok, _, _ = acct.open(0, price, 1, 1, True, p)
                        if ok:
                            c_pos = acct.positions[-1]

        # ---------- 策略 D：劳动收入（线性计入，不碰股市） ----------
        if mining_per_tick > 0.0:
            acct.cash += int(round(mining_per_tick))

    # 收尾：跑完时把还挂着的仓按最后价格平掉（统计净值口径统一为「可变现净值」）
    final_prices = engine.price
    if strategy == "A" and a_pos is not None and a_pos in acct.positions:
        acct.close(a_pos, final_prices[a_pos.stock], p)
        record_a_trade(acct.equity(final_prices))
    if c_pos is not None and c_pos in acct.positions:
        acct.close(c_pos, final_prices[c_pos.stock], p)
        settled += 1
    for pos in list(acct.positions):
        acct.close(pos, final_prices[pos.stock], p)
        settled += 1

    final_eq = acct.equity(final_prices)
    equity_series.append(final_eq)
    peak = max(peak, final_eq)
    max_dd = max(max_dd, peak - final_eq)
    note_ruin(jumps_total, final_eq)

    return RunResult(strategy, equity_series, liquidations, max_dd,
                     liq_jumps, trade_returns, settled, a_liq_count, ruin_jump)


# ==========================================================================================
# 七、统计与报表
# ==========================================================================================

def quantile(values: Sequence[float], q: float) -> float:
    """线性插值分位数（numpy 口径的 nearest-rank 折中；样本量 200 时足够）。"""
    if not values:
        return 0.0
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = q * (len(xs) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(xs) - 1)
    frac = pos - lo
    return xs[lo] * (1 - frac) + xs[hi] * frac


def coins(cents: float) -> str:
    return f"{cents / 100.0:,.2f}"


def coins_signed(cents: float) -> str:
    v = cents / 100.0
    return f"{v:+,.2f}"


def summarize(runs: List[RunResult], start_cents: int = START_CASH_CENTS) -> dict:
    finals = [r.equity_series[-1] for r in runs]
    profits = [f - start_cents for f in finals]
    dds = [r.max_drawdown_cents for r in runs]
    liqs = [r.liquidations for r in runs]
    return {
        "finals": finals,
        "profits": profits,
        "dds": dds,
        "liqs": liqs,
        "median_final": statistics.median(finals),
        "median_profit": statistics.median(profits),
        "p10_profit": quantile(profits, 0.10),
        "p90_profit": quantile(profits, 0.90),
        "median_dd": statistics.median(dds),
        "p90_dd": quantile(dds, 0.90),
        "mean_liq": statistics.fmean(liqs),
        "median_liq": statistics.median(liqs),
        "liq_rate": sum(1 for x in liqs if x > 0) / len(liqs),
    }


def strategy_table(title: str, per_params: Dict[str, Dict[str, dict]], ticks: int) -> List[str]:
    """四条策略 × 两组参数 的结果表。"""
    hours = ticks / 1800.0
    out = [f"### {title}", ""]
    out.append(f"| 策略 | 参数 | 期末净值中位数（{hours:.1f} h） | 净收益中位数 | 净收益 10% 分位 "
               "| 净收益 90% 分位 | 爆仓次数中位数 | 爆仓概率 | 最大回撤中位数 | 最大回撤 90% 分位 |")
    out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    labels = {"A": "A 追事件（50×）", "B": "B 死多 ×400", "C": "C 无脑往返", "D": "D 只回收不交易"}
    for strat in ("A", "B", "C", "D"):
        for pname in ("before", "after"):
            st = per_params[pname][strat]
            tag = PARAM_LABELS.get(pname, pname)
            out.append(
                f"| {labels[strat]} | {tag} | {coins(st['median_final'])} | {coins_signed(st['median_profit'])} "
                f"| {coins_signed(st['p10_profit'])} | {coins_signed(st['p90_profit'])} "
                f"| {st['median_liq']:.1f} | {st['liq_rate'] * 100:.1f}% "
                f"| {coins(st['median_dd'])} | {coins(st['p90_dd'])} |")
    out.append("")
    return out


# ==========================================================================================
# 八、主流程
# ==========================================================================================

def run_all(params_list: List[Params], ticks: int, seed: int, runs: int,
            progress: bool = True) -> Dict[str, Dict[str, List[RunResult]]]:
    result: Dict[str, Dict[str, List[RunResult]]] = {}
    for p in params_list:
        result[p.name] = {}
        for strat in ("A", "B", "C", "D"):
            got = []
            for i in range(runs):
                got.append(run_strategy(strat, p, seed + i, ticks))
            result[p.name][strat] = got
            if progress:
                print(f"  · {p.name}/{strat} 跑完 {runs} 次", file=sys.stderr)
    return result


def breakeven_section(params_list: List[Params]) -> Tuple[List[str], List[dict]]:
    """「要多少胜率才能打平」：手续费 + 价差决定的盈亏平衡价格变动。

    @return (Markdown 行, 机器可读数据)
    """
    out = ["### 2. 打平所需胜率：盈亏平衡的价格变动（手续费 + 价差）", ""]
    out.append("| 参数组 | 手续费率 | 单边成本 | 往返成本 | 盈亏平衡价格变动 | 1 跳 σ（稳健/波动/激进） "
               "| 需要跑赢 σ 的倍数 | 一次往返打平概率 [注1] | 从 −20% 回本所需连续正确次数 [注2] |")
    out.append("|---|---:|---:|---:|---:|---|---:|---:|---:|")
    data: List[dict] = []
    for p in params_list:
        one_side = p.fee_rate
        round_trip = 2 * p.fee_rate
        be_move = round_trip          # 价格必须变动 ≥ 往返成本才不亏
        sigmas = "/".join(f"{v * 100:.2f}%" for v in VOLATILITY)
        # 最乐观口径：拿 σ 最大的那只（激进股）当分母
        best_ratio = be_move / VOLATILITY[2]
        win_rate = (1.0 + round_trip) / 2.0     # 两次结果对称（±be_move）时的保本胜率
        n_recoup = -(-0.20 // be_move)          # ceil(0.20 / be_move)
        data.append({"params": p.name, "fee_rate": one_side, "round_trip_cost": round_trip,
                     "breakeven_price_move": be_move, "sigma_ratio_best": best_ratio,
                     "breakeven_win_rate": win_rate, "recoup_20pct_trades": int(n_recoup)})
        out.append(f"| {PARAM_LABELS.get(p.name, p.name)} | {one_side * 100:.2f}% "
                   f"| {one_side * 100:.2f}% | {round_trip * 100:.2f}% | **{be_move * 100:.2f}%** "
                   f"| {sigmas} | {best_ratio:.2f}σ | {win_rate * 100:.1f}% | {int(n_recoup)} |")
    out.append("")
    out.append("[注1] 口径：盈亏对称（每次盈利 +c、亏损 −c，c = 往返成本）时，"
               "胜率 p 打平需 p×c = (1−p)×c ⇒ **p = (1+c)/2**，c 越大要求的胜率越高。")
    out.append("[注2] 从账户 −20% 回到本金，每次净赚一个往返成本 c 所需的**连续**正确次数 "
               "`ceil(0.20/c)`（不含连续失败，只是量级感）。")
    out.append("")
    out += breakeven_winrate_table(params_list)
    return out, data


def breakeven_winrate_table(params_list: List[Params]) -> List[str]:
    """**走势交易的真实打平胜率**：把「一次赚 1σ / 亏 1σ」当成一注来算。

    <p>公式：赢 +kσ、输 −kσ、胜率 p、成本 c ⇒ 打平条件 `p·kσ − (1−p)·kσ − c = 0`
    ⇒ **p = (kσ + c) / (2kσ)**。σ 越小（越稳健的股票）要求的胜率越高 ——
    稳健股的成本占 σ 的比例更大，这就是「高频往返只适合高波动股、而且仍然不划算」的数学来源。
    """
    out = ["#### 2b. 打平胜率表：一注赚 kσ / 亏 kσ（含往返成本）", ""]
    out.append("| 参数组 | 股票 | 1 跳 σ | 往返成本 | **±1σ 打平胜率** | ±2σ 打平胜率 | ±3σ 打平胜率 |")
    out.append("|---|---|---:|---:|---:|---:|---:|")
    names = ("稳健（钻石矿业）", "波动（红石科技）", "激进（下界贸易）")
    for p in params_list:
        round_trip = 2 * p.fee_rate
        for i, sigma in enumerate(VOLATILITY):
            rates = []
            for k in (1, 2, 3):
                move = k * sigma
                rates.append((move + round_trip) / (2.0 * move))
            label = "**∞（打不平）**" if rates[0] >= 1.0 else f"**{rates[0] * 100:.1f}%**"
            out.append(f"| {PARAM_LABELS.get(p.name, p.name)} | {names[i]} | {sigma * 100:.2f}% "
                       f"| {round_trip * 100:.2f}% | {label} | {rates[1] * 100:.1f}% | {rates[2] * 100:.1f}% |")
    out.append("")
    out.append("读法：**稳健股在 ±1σ 上根本打不平**（σ=0.6% 只有往返成本 0.40% 的 1.5 倍，"
               "要求胜率超过 100%）—— 所以「稳健股适合拿着不动、不适合来回做」；"
               "激进股 ±1σ 也要 58% 胜率才保本（回调前是 64%），而随机走势的胜率就是 50%。")
    out.append("")
    return out


def main(argv: Optional[Sequence[str]] = None) -> int:
    global CURRENT_MINING_RATE

    ap = argparse.ArgumentParser(
        description="MC 股市经济模拟：四条策略 × 现值/提议参数（只读源码，不改 Java）")
    ap.add_argument("--ticks", type=int, default=1800, help="游戏 tick 数（1800 = 1 小时，40 tick 跳一次价）")
    ap.add_argument("--seed", type=int, default=20261006, help="基准种子（第 i 次用 seed+i）")
    ap.add_argument("--runs", type=int, default=200, help="每个组合跑多少次（取中位数与分位数）")
    ap.add_argument("--params", default="both",
                    help="before / after / both / all（也可逗号分隔，如 before,after）")
    ap.add_argument("--mining", type=float, default=64.0,
                    help="挖矿效率假设（个圆石/分钟），默认 64")
    ap.add_argument("--json", action="store_true", help="输出机器可读 JSON 而不是 Markdown")
    ap.add_argument("--quiet", action="store_true", help="不打印进度到 stderr")
    args = ap.parse_args(argv)

    CURRENT_MINING_RATE = args.mining

    params_list = []
    for want in args.params.split(","):
        want = want.strip()
        if want == "original":
            params_list.append(original_params())
        elif want == "before":
            params_list.append(before_params())
        elif want == "after":
            params_list.append(after_params())
        elif want == "both":
            # 上一版（严厉） vs 本版（回调后）
            params_list += all_param_groups()
        elif want == "all":
            params_list += every_param_group()
        else:
            ap.error(f"未知的 --params 取值：{want}（可选 original / before / after / both / all）")
    # 去重
    seen = set()
    params_list = [p for p in params_list if not (p.name in seen or seen.add(p.name))]

    hours = args.ticks / 1800.0
    jumps = args.ticks // TICKS_PER_JUMP

    lines: List[str] = []
    lines.append(f"# MC 股市 · 经济模拟结果（{args.ticks} tick = {hours:.1f} 小时 = {jumps} 跳价）")
    lines.append("")
    lines.append(f"起点 **{coins(START_CASH_CENTS)} 金币**，每条策略每个参数组跑 **{args.runs} 次**"
                 f"（种子 {args.seed} … {args.seed + args.runs - 1}），"
                 f"跳价间隔 {PRICE_TICK_TICKS} tick = 2 秒。金额单位：**金币**（内部为分）。")
    lines.append("")
    lines.append("> **与 Java 单测的分工**：`EconomySimulationTest` 已覆盖「三条策略亏、回收赚」的"
                 "**净收益**，本文档**不重复**；重点是 **§2 打平胜率 / §5b 杠杆→爆仓时间 / "
                 "§5a 劳动敏感性 / §6 亏光时间**。")
    lines.append("")
    lines.append("参数组：**before** = 改动前的历史值（手续费 0.1% / 购买 ×1.15 / 均值回归 1.5% / "
                 "爆仓线 50% / 牛 +0.12% 熊 −0.10% / 利空 ×1.0 / 无插针）；"
                 "**after** = 当前源码真实值（0.35% / ×1.25 / 3% / 40% / 牛 +0.06% 熊 −0.16% / "
                 "利空 ×1.15 / 有插针）。")
    lines.append("")

    results = run_all(params_list, args.ticks, args.seed, args.runs, progress=not args.quiet)

    per: Dict[str, Dict[str, dict]] = {
        p.name: {s: summarize(results[p.name][s]) for s in ("A", "B", "C", "D")}
        for p in params_list
    }

    lines += strategy_table(f"1. 四条策略 × 各参数组（{args.ticks} tick = {hours:.1f} 小时）",
                            per, args.ticks)
    be_lines, breakeven_data = breakeven_section(params_list)
    lines += be_lines

    # ---------- 3. 策略 A 的期望收益 ----------
    lines.append("### 3. 策略 A「追事件」的期望收益（扣完成本）")
    lines.append("")
    lines.append("| 参数组 | 已结算笔数（全部局合计） | 平均每局笔数 | 其中爆仓笔数 | 平均每次收益率 "
                 "| 收益率中位数 | 盈利笔数占比 | 每局利好事件数 | 每局全部事件数 | 每局插针数 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    market_stats: Dict[str, dict] = {}
    for p in params_list:
        rets: List[float] = []
        liq_trades = 0
        settled_total = 0
        for r in results[p.name]["A"]:
            rets.extend(r.trade_returns)
            liq_trades += r.a_liquidated_trades
            settled_total += r.settled_trades
        # 事件统计：另跑同一批种子（与策略 A 的引擎配置完全一致）
        n_probe = min(args.runs, 50)
        ev_up = ev_all = spikes = 0
        for i in range(n_probe):
            eng = MarketEngine(args.seed + i, p)
            for _ in range(jumps):
                eng.advance()
            ev_up += eng.up_event_count
            ev_all += eng.event_count
            spikes += eng.spike_count
        market_stats[p.name] = {
            "up_events_per_run": ev_up / n_probe,
            "all_events_per_run": ev_all / n_probe,
            "spikes_per_run": spikes / n_probe,
            "trades_total": settled_total,
            "trades_per_run": settled_total / args.runs,
        }
        tag = PARAM_LABELS.get(p.name, p.name)
        if rets:
            mean_r = statistics.fmean(rets)
            med_r = statistics.median(rets)
            win = sum(1 for x in rets if x > 0) / len(rets)
            lines.append(f"| {tag} | {len(rets)} | {settled_total / args.runs:.2f} | {liq_trades} "
                         f"| {mean_r * 100:+.2f}% | {med_r * 100:+.2f}% | {win * 100:.1f}% "
                         f"| {ev_up / n_probe:.2f} | {ev_all / n_probe:.2f} | {spikes / n_probe:.3f} |")
        else:
            lines.append(f"| {tag} | 0 | 0.00 | 0 | — | — | — | {ev_up / n_probe:.2f} "
                         f"| {ev_all / n_probe:.2f} | {spikes / n_probe:.3f} |")
    lines.append("")
    lines.append("收益率口径：**这笔持仓带来的账户净值变化 ÷ 开仓前账户净值**（50× 杠杆、全仓，"
                 "所以它同时也是「这一笔对账户的冲击」）。已扣开仓费 + 平仓费（爆仓不收平仓费）。"
                 "**爆仓那笔也算进来了** —— 只统计活到 60 跳的赢家会得出「稳赚」的假象。")
    lines.append("")

    # ---------- 4. D 与 A/B/C 的对比 ----------
    lines.append("### 4. 策略 D（劳动）与 A/B/C（投机）对比")
    lines.append("")
    lines.append("| 参数组 | D 净收益（中位数） | A 净收益 | B 净收益 | C 净收益 | 跑赢 D 的策略 |")
    lines.append("|---|---:|---:|---:|---:|---|")
    for p in params_list:
        d = per[p.name]["D"]["median_profit"]
        cells = {}
        for s in ("A", "B", "C"):
            cells[s] = per[p.name][s]["median_profit"]
        winners = [s for s in ("A", "B", "C") if cells[s] > d]
        lines.append(f"| {PARAM_LABELS.get(p.name, p.name)} | {coins_signed(d)} "
                     f"| {coins_signed(cells['A'])} | {coins_signed(cells['B'])} "
                     f"| {coins_signed(cells['C'])} | {'、'.join(winners) if winners else '**没有**'} |")
    lines.append("")

    # ---------- 5. 敏感性 ----------
    lines.append("### 5a. 劳动效率敏感性：挖矿 32 / 64 / 128 个圆石每分钟")
    lines.append("")
    lines.append("| 挖矿效率（圆石/分钟） | 本局累计收入（%s） | 折算每小时 | 对比：A/B/C 里最好的一条（中位数） |"
                 % f"{hours:.1f} h")
    lines.append("|---|---:|---:|---:|")
    best_credit = max((per[p.name][s]["median_profit"] for p in params_list for s in ("A", "B", "C")))
    mining_rows = []
    for rate in (32.0, 64.0, 128.0):
        income = rate * COBBLESTONE_COINS * 100.0 * (args.ticks / 1200.0)
        mining_rows.append({"cobble_per_min": rate, "income_cents": income})
        lines.append(f"| {rate:.0f} | {coins(income)} 金币 | {coins(income / hours)} / 小时 "
                     f"| {coins_signed(best_credit)} |")
    lines.append("")
    lines.append("### 5b. 杠杆 → 爆仓时间中位数（策略 B「死多」，四档杠杆）")
    lines.append("")
    lines.append("爆仓时间只统计**真的爆了**的那些局；爆仓概率 = 该杠杆下全部局里爆掉的比例。"
                 "「10% 分位」= 最快的那 10% 有多快，「90% 分位」= 最慢的那 10% 有多慢。")
    lines.append("")
    lines.append(f"| 参数组 | 杠杆 | 爆仓概率 | 爆仓跳数 10% 分位 | **爆仓跳数中位数** "
                 f"| 爆仓跳数 90% 分位 | 爆仓时间中位数 | 期末净值中位数（{hours:.1f} h） |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
    lever_rows = []
    for p in params_list:
        for lev in (1, 10, 100, 400):
            runs_lev = [run_strategy("B", p, args.seed + i, args.ticks, leverage_override=lev)
                        for i in range(args.runs)]
            liq_rates = [r for r in runs_lev if r.liq_jumps]
            prob = len(liq_rates) / len(runs_lev)
            finals = [r.equity_series[-1] for r in runs_lev]
            med_final = statistics.median(finals)
            if liq_rates:
                firsts = sorted(float(r.liq_jumps[0]) for r in liq_rates)
                p10 = quantile(firsts, 0.10)
                p50 = quantile(firsts, 0.50)
                p90 = quantile(firsts, 0.90)
                secs = p50 * PRICE_TICK_TICKS / 20.0
                cells = (f"{p10:.1f}", f"**{p50:.1f}**", f"{p90:.1f}", f"{secs:.1f} 秒")
            else:
                p10 = p50 = p90 = None
                secs = None
                cells = ("未爆仓", "未爆仓", "未爆仓", "未爆仓")
            lever_rows.append({"params": p.name, "leverage": lev, "liq_prob": prob,
                               "p10_liq_jumps": p10, "median_liq_jumps": p50,
                               "p90_liq_jumps": p90, "median_liq_seconds": secs,
                               "median_final_cents": med_final})
            lines.append(f"| {PARAM_LABELS.get(p.name, p.name)} | ×{lev} | {prob * 100:.1f}% "
                         f"| {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} | {coins(med_final)} |")
    lines.append("")

    lines.append("### 6. 源码已落地的「插针」到底多久来一次")
    lines.append("")
    pin_rows = []
    if any(p.spike_enabled for p in params_list):
        probe_jumps = 500_000          # 约 11.6 天游戏时间，足够量出 0.05% 的频率
        eng = MarketEngine(args.seed, after_params())
        for _ in range(probe_jumps):
            eng.advance()
        per_jump = eng.spike_count / probe_jumps / COUNT
        lines.append(f"长时间探针（{probe_jumps:,} 跳 = {probe_jumps * 2 / 3600:.1f} 小时游戏时间，"
                     f"1 个种子）：共触发 **{eng.spike_count}** 次插针。")
        lines.append("")
        lines.append(f"| 口径 | 每只股票每跳 | 折合每只股票多久一次 | 折合三只股票多久一次 "
                     f"| 本局（{hours:.1f} h = {jumps} 跳）期望次数 |")
        lines.append("|---|---:|---:|---:|---:|")
        theo = after_params().spike_chance
        def fmt_gap(per_stock_per_jump: float) -> str:
            if per_stock_per_jump <= 0:
                return "—"
            jumps_per = 1.0 / per_stock_per_jump
            return f"{jumps_per * 2 / 60:.2f} 分钟"
        lines.append(f"| 设计值 | {theo * 100:.3f}% | {fmt_gap(theo)} | {fmt_gap(theo * COUNT)} "
                     f"| {theo * COUNT * jumps:.3f} |")
        lines.append(f"| 实测值 | {per_jump * 100:.3f}% | {fmt_gap(per_jump)} | {fmt_gap(per_jump * COUNT)} "
                     f"| {per_jump * COUNT * jumps:.3f} |")
        pin_rows = [{"mode": "designed", "per_stock_per_jump": theo},
                    {"mode": "measured", "per_stock_per_jump": per_jump,
                     "spikes_in_probe": eng.spike_count, "probe_jumps": probe_jumps}]
        lines.append("")
        lines.append("**读法**：`SPIKE_CHANCE` 听起来像个高频风险，实际摊到三只股票也只有"
                     f"每 **{1 / (theo * COUNT) * 2 / 60:.1f} 分钟**一次，"
                     f"1 小时里期望只有 {theo * COUNT * 45:.3f} 次、"
                     f"{hours:.0f} 小时里也只有 {theo * COUNT * jumps:.2f} 次 —— **绝大多数对局一次都遇不到**。"
                     "2026-10-06 回调时它被有意从 0.05%/跳降到 0.03%/跳（配合「赚钱太难」的反馈），现在更像偶发的尾部风险而不是持续威胁；想加强就抬这一个数。")
    else:
        lines.append("（本组参数未启用插针）")
    lines.append("")

    lines.append("### 7. 「多久把 1000 金币亏光」—— 中位时间")
    lines.append("")
    lines.append("口径：账户**净值第一次 ≤ 0** 的那一跳（爆仓可以亏穿本金、现金允许为负）。"
                 "只统计真的亏光过的局；「亏光概率」= 全部局里发生过的比例。")
    lines.append("")
    lines.append(f"| 参数组 | 策略 | 亏光概率（{jumps} 跳内） | 亏光跳数中位数 | 亏光时间中位数 |")
    lines.append("|---|---|---:|---:|---:|")
    ruin_rows = []
    for p in params_list:
        for strat in ("A", "B", "C"):
            rs = results[p.name][strat]
            ruined = [r for r in rs if r.ruin_jump > 0]
            prob = len(ruined) / len(rs)
            if ruined:
                med_j = statistics.median([r.ruin_jump for r in ruined])
                secs = med_j * PRICE_TICK_TICKS / 20.0
                jtxt = f"{med_j:.1f}"
                ttxt = f"{secs:.1f} 秒"
            else:
                med_j = None
                jtxt = ttxt = "未亏光"
            ruin_rows.append({"params": p.name, "strategy": strat, "ruin_prob": prob,
                              "median_ruin_jumps": med_j})
            lines.append(f"| {PARAM_LABELS.get(p.name, p.name)} | {strat} | {prob * 100:.1f}% "
                         f"| {jtxt} | {ttxt} |")
    lines.append("")

    text = "\n".join(lines)

    if args.json:
        payload = {
            "ticks": args.ticks, "seed": args.seed, "runs": args.runs,
            "start_cash_cents": START_CASH_CENTS,
            "equity_timeline_ticks": args.ticks,
            "results": {
                pname: {s: {k: v for k, v in st.items()
                            if k not in ("finals", "profits", "dds", "liqs")}
                       for s, st in per[pname].items()}
                for pname in per
            },
            "breakeven": breakeven_data,
            "market_stats": market_stats,
            "mining_sensitivity": mining_rows,
            "leverage_sensitivity": lever_rows,
            "ruin_time": ruin_rows,
            "spike_frequency": pin_rows,
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
