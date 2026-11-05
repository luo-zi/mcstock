# MC Stock Market

**Recycle what you mine into coins, trade a market that ticks every 2 seconds, and spend the profits on supplies — a stock-market minigame that lives inside Minecraft.**

- **Minecraft 1.21.1 · NeoForge 21.1.234+ · no dependencies** (install on both client and server)
- Adds 1 item, 4 GUI pages, 3 stocks and 12 named market events
- Making the terminal is cheap; **your first login grants 1,000 coins**

---

## What it is

Three pages, one loop: **turn what you dig up into capital, then decide whether to risk it.**

| Page | What it does |
|---|---|
| **Recycle** | Shift items into the 18-slot recycle bar, hit **Recycle** for coins. The list shows **unit price, line total, item count and grand total** — only what's in the bar is sold, your inventory is untouched |
| **Market** | Candlestick chart for three stocks, quantity/leverage fields, Buy/Sell, and a **scrollable position table** underneath — every row has its own close button |
| **Shop** | Spend coins on supplies. Unit price = value × 1.18; **left-click buys 1, Shift + left-click buys a stack** |
| **Help** | The **?** button in the title bar — every rule and every number lives in there, so the main screen stays clean |

---

## How the market behaves

### Three stocks, three temperaments

| Stock | Character | σ per tick |
|---|---|---|
| Diamond Mining | Steady | 0.6% |
| Redstone Tech | Swingy | 1.2% |
| Nether Trading | Wild | 2.5% |

### The tick

- Prices move **every 2 seconds**; one candle = 5 ticks = **10 seconds**
- Price = mean reversion toward a base (2%/tick) + random walk + market events
- A single tick never moves more than **±25%**, and prices stay inside 2%–500% of their base (they can nearly bottom out, never hit zero)
- The **market regime** shifts between *calm / mild / choppy / turbulent* every few minutes, changing volatility and drift

### Named events with a life of their own

Each stock has **6 named events** — "a mine shaft collapsed", "a redstone patent was approved", "a queue formed at the portal".
An event is not a one-tick jump: it carries **magnitude, a half-life and a volatility boost** — the price lurches, then digests it over a minute or two.
Industry and macro news **spills over** to other stocks (sometimes in the opposite direction).
Anything tagged **Rumour** may not be true — it can be **clarified later** (up first, down after).
News also **arrives in clusters**.

There is also a rare **flash crash**: a single tick drops 8%–15% and only retraces 60–80% afterwards. High leverage's worst enemy.

### Trading and risk

| | |
|---|---|
| Direction | **Buy = long, Sell = short**; the two **never net off** — every order is its own position |
| Quantity | 1 – 100,000 (16 steps + a MAX button) |
| Leverage | 1 – 400 (18 steps, denser at the low end) |
| Margin | position value ÷ leverage |
| Fee | position value × **0.20%**, charged on open *and* close (0.40% round trip) |
| Liquidation | at **45% margin loss**, settled at the **real post-tick price** |
| Debt | **Going negative is allowed.** While in debt, recyclable **non-durable** items in your inventory are auto-sold every second to pay it down — **tools, weapons, armour and the terminal are never touched** |

The **Risk** line in the account panel tells you, live, how much adverse movement the current leverage can survive (40% at ×1 … 0.11% at ×400).

### What things are worth: six rarity tiers

Every item is priced by **rarity and effort**, from "free for the taking" (dirt, cobblestone: 1 coin) to "legendary" (elytra 3,000 · nether star 3,200 · dragon egg 5,000).
**Anything renewable is priced as a material** (obsidian, emeralds, sponges ≤ 30 coins); only **non-renewable** items get to be expensive — diamond 300, netherite scrap 450, shulker shell 400.

> A useful yardstick: **one diamond ≈ 375 dirt.** Fifty logs will not buy you one.

---

## Why this is not a gambling sim

Three deliberate design decisions keep **speculation mathematically unprofitable and labour reliable**:

1. **The interface never tells you what to do.** News says *what happened* ("a mine shaft collapsed") — **no percentages, no bull/bear labels, no red/green tinting**. Regimes use direction-free words (calm / mild / choppy / turbulent). If you want the numbers, read the chart yourself.
2. **Speculation is negative expectancy.** Round-trip fees are 0.40% and the buy/sell spread is 18%, while a single tick's σ is an order of magnitude larger. Simulated over 55 in-game hours: **chasing news, holding ×400, and churning all lose 80%+ of the bankroll** (churning alone pays 1.5× the starting capital in fees).
3. **Labour is stable.** The control strategy — recycle only, never trade — is reliably positive. Mining always pays.

> In other words: it simulates a market, it does not teach you to gamble. Play it safe at low leverage, or gamble if you like — but the price of gambling is printed right next to the leverage box.

---

## Getting started

1. Install **Minecraft 1.21.1 + NeoForge 21.1.234** (or a newer 21.1.x) and drop `mcstock-0.1.0.jar` into `mods/` (client **and** server).
2. Craft the **Exchange Terminal**:

   ```
   Iron Ingot   Redstone   Iron Ingot
   Redstone     Book       Redstone
   Iron Ingot   Redstone   Iron Ingot
   ```

   (4 iron ingots + 4 redstone + 1 book — or `/give @s mcstock:stock_terminal`)
3. **Right-click** it (works on air, on the ground, even on a chest).
4. Your first login grants **1,000 coins**.
5. Press the **?** in the title bar — everything is explained there.

### Commands (permission level 2)

```
/mcstock money              show balance and net worth
/mcstock money add 100      add 100 coins
/mcstock money set -50      set the balance to -50 (try going into debt)
/mcstock price              current price and change for all three stocks
/mcstock price set diamond_mine 100
/mcstock advance            force one price tick
/mcstock value              value and source of the held item (seed / tag / recipe-derived)
/mcstock positions          list your positions
```

---

## For modpack authors & tinkerers

- **Every item value is data-driven**: edit `data/mcstock/mcstock/item_values/core.json` (a tiny JSON with `values` for items and `#tags`) and `/reload`. Anything a recipe can produce is **derived from its ingredients**, and the derivation is checked against an anti-arbitrage invariant — a recipe can never be a money printer.
- **No config sprawl**: the economy constants (tick rate, fees, leverage bounds, liquidation line, event magnitudes) are plain constants in the source with the reasoning written next to them.
- **Everything is verifiable**: 124 unit tests, including an in-CI **economy simulation** (three speculation strategies must lose, recycling must win), engine invariants (±25% cap, liquidation distance = 45% ÷ leverage), valuation invariants, and a full GUI overlap matrix across nine resolutions.

---

## Technical notes

- **Single source of truth for layout**: `StockGuiLayout` is plain Java with no Minecraft imports, so the docs, mockups and texture coordinates are all generated from it — change one number and everything stays in sync.
- **Server-authoritative**: prices, balances, positions and valuations are computed server-side; the client only sends "which button I pressed".
- **Reproducible markets**: the engine runs on a seeded RNG and replays it from the save (seed + tick count), and candlesticks use **absolute binning** so completed candles are never recomputed.
- Docs in the repository: `docs/UI_GUIDE.md` (plain-language guide to reshaping the GUI), `docs/ECONOMY_SIM.md` (the simulation behind the balance), `AGENTS.md` (every pitfall we hit and how we debug them).
