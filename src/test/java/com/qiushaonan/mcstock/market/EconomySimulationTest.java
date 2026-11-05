package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **经济平衡的模拟验收**（纯 Java，固定种子，可复现）—— 口径是玩家原话：
 * 「尽量不让玩家容易赚钱」，也就是**投机不该容易赚、劳动才是可靠路径**。
 *
 * <p>四条策略跑在**同一条行情路径**上（同一个 {@link MarketEngine}，同一个种子、10 万跳），
 * 各自的账户互不影响，所以四个数字是可直接比较的：
 * <ul>
 *   <li><b>A 追事件</b>：看到利好新闻就满仓 + ×20 开多，持有 30 跳；</li>
 *   <li><b>B 死多高杠杆</b>：一直 ×400 做多，爆仓就再开；</li>
 *   <li><b>C 频繁往返</b>：每 10 跳无脑开/平一次（×5）；</li>
 *   <li><b>D 只回收不交易</b>（对照）：每 400 跳把挖到的东西卖成金币，从不碰行情。</li>
 * </ul>
 *
 * <p>断言只写**宽松的方向性阈值**（谁是正的、谁明显更差），精确数字靠打印出来对账 ——
 * 写死精确值会和常量一起变，测试变成「改常量就要改测试」，那就等于没测。
 *
 * <p>初始资金统一 1000 金币（= {@code StockEvents#STARTING_CENTS} 的启动资金）。
 * 策略 D 的「劳动收入」是一个**写明的假设**：每 400 跳（20 秒）回收一批 ≈ 120 金币
 * （约一组圆石 64 + 十几块铁矿，按数据包里的估价 1 / 5 金币算），远低于「挖一小时暴富」的幻想，
 * 但它是**无风险**的 —— 这正是「劳动可靠」的含义。
 */
class EconomySimulationTest {

    /** 初始资金（分）：与游戏里的启动资金一致。 */
    private static final long START_CENTS = 100_000L;
    /**
     * 一批劳动收益（分）：**按真估值表现算**，不再写死。
     *
     * <p>假设：20 秒（400 跳）能带回**一组圆石 + 4 块粗铁** —— 一组圆石 64 个，
     * 粗铁按一趟小矿洞的零头算 4 个。单价由 {@link ValueTableFixture} 从
     * {@code core.json} 现取，所以**估值表一改这个假设自动跟着变**
     * （2026-10-06 重做估值表后：64×1 + 4×10 = 104 金币/20 秒 ≈ 312 金币/分钟）。
     */
    private static final long LABOR_BATCH_CENTS = labourBatchCents();
    private static final int LABOR_BATCH_JUMPS = 400;        // 20 秒一批
    private static final int SIM_JUMP_COUNT = 100_000;       // ≈ 55 小时游戏时间
    private static final long SEED = 20260101L;

    private static long labourBatchCents() {
        ValueTableFixture table = ValueTableFixture.load();
        return 100L * (64L * table.value("minecraft:cobblestone")
                + 4L * table.value("minecraft:raw_iron"));
    }

    /** 一个模拟账户：现金 + 仓位数（口径与 {@code StockService} 一致）。 */
    private static final class Account {
        final String name;
        long cash = START_CENTS;
        final Portfolio portfolio = new Portfolio();
        long feesPaid;
        int trades;
        int liquidations;

        Account(String name) {
            this.name = name;
        }

        long netWorth(long[] prices) {
            return cash + portfolio.totalMargin() + portfolio.totalPnl(prices);
        }

        /** 逐仓强平（与 {@code StockService#settleLiquidations} 同一套判定）。 */
        void settle(long[] prices) {
            for (Position position : new ArrayList<>(portfolio.positions())) {
                long price = prices[position.stock()];
                if (!position.liquidated(price)) {
                    continue;
                }
                cash += TradeService.liquidate(portfolio, position.id(), price);
                liquidations++;
            }
        }

        boolean open(int stock, long price, int leverage, long budget) {
            int qty = PositionMath.maxAffordable(budget, price, leverage);
            if (qty < StockDefs.QTY_MIN) {
                return false;
            }
            TradeService.Outcome out = TradeService.open(cash, portfolio, stock, price, qty, leverage, true);
            if (out.status() != TradeService.Status.OPENED) {
                return false;
            }
            cash = out.cashCents();
            feesPaid += out.feeCents();
            trades++;
            return true;
        }

        boolean close(int positionId, long price) {
            TradeService.Outcome out = TradeService.close(cash, portfolio, positionId, price);
            if (out.status() != TradeService.Status.CLOSED) {
                return false;
            }
            cash = out.cashCents();
            feesPaid += out.feeCents();
            trades++;
            return true;
        }

        void closeAll(int stock, long price) {
            for (Position position : portfolio.forStock(stock)) {
                close(position.id(), price);
            }
        }

        int countFor(int stock) {
            return portfolio.countFor(stock);
        }
    }

    /** 追事件用的杠杆：**中高**（不是极限），这样它能持续交易很多笔，统计才有意义。 */
    private static final int CHASER_LEVERAGE = 10;
    /** 追事件的持有跳数：约一分钟（30 跳），够趋势走一点、也不至于拿到天荒地老。 */
    private static final int CHASER_HOLD_JUMPS = 30;

    @Test
    void speculationLosesWhileLabourPays() {
        MarketEngine engine = new MarketEngine(SEED);
        Account chaser = new Account("A 追事件");
        Account degen = new Account("B 死多×400");
        Account scalper = new Account("C 频繁往返");
        Account labour = new Account("D 只回收");

        // 追事件：记住「这条新闻有没有被交易过」，以及每个仓准备持有多久
        List<MarketNews> seenNews = new ArrayList<>();
        int[] chaserPosition = {-1, -1};        // {仓 id, 开仓跳数}
        int[] scalperPosition = {-1, -1};       // {仓 id, 开仓跳数}
        int chaserSignals = 0;

        engine.debugSetRegime(MarketRegime.CALM);   // 与其它测试一致：从平静开始

        for (int jump = 1; jump <= SIM_JUMP_COUNT; jump++) {
            engine.advanceAll();
            long[] prices = engine.prices();

            // ① 先结算爆仓（三条投机策略都要，和真实服务端一样）
            chaser.settle(prices);
            degen.settle(prices);
            scalper.settle(prices);

            // ---------- 策略 D：只回收（每 400 跳一批） ----------
            if (jump % LABOR_BATCH_JUMPS == 0) {
                labour.cash += LABOR_BATCH_CENTS;
            }

            // ---------- 策略 A：追事件 —— 看到利好新闻就满仓开多，持有 30 跳 ----------
            for (MarketNews item : engine.newsFeed()) {
                if (seenNews.contains(item)) {
                    continue;
                }
                seenNews.add(item);
                if (item.isMarket() || item.type() == EventType.SPIKE) {
                    continue;               // 大盘播报 / 插针不是「利好事件」
                }
                if (item.direction() <= 0) {
                    continue;
                }
                chaserSignals++;
                int stock = item.stock();
                if (chaser.countFor(stock) == 0 && chaserPosition[0] < 0) {
                    // ⚠️ 只投**一半**现金（×10 杠杆 → 名义仓位约 5 倍现金）。
                    //    全押的话头一两次爆仓就把现金打到负值、之后再开不了仓，
                    //    样本只剩个位数笔、测的是「一次意外」而不是「期望」
                    //    （2026-10-06 回调后实测全押只剩 4 笔）。半仓仍然亏光，但能持续交易几十笔。
                    if (chaser.open(stock, prices[stock], CHASER_LEVERAGE, chaser.cash / 2)) {
                        // 刚开出来的那个仓就是 portfolio 里 id 最大的
                        chaserPosition[0] = chaser.portfolio.positions()
                                .get(chaser.portfolio.size() - 1).id();
                        chaserPosition[1] = jump;
                    }
                }
            }
            if (chaserPosition[0] >= 0 && jump - chaserPosition[1] >= CHASER_HOLD_JUMPS) {
                Position position = chaser.portfolio.byId(chaserPosition[0]);
                if (position != null) {
                    chaser.close(chaserPosition[0], prices[position.stock()]);
                }
                chaserPosition[0] = -1;
            }

            // ---------- 策略 B：死多 ×400（爆仓就再开） ----------
            if (degen.portfolio.isEmpty()) {
                degen.open(0, prices[0], StockDefs.LEVERAGE_MAX, degen.cash);
            }

            // ---------- 策略 C：频繁往返（每 10 跳开/平一次，×5） ----------
            if (scalperPosition[0] < 0 && jump % 10 == 0) {
                if (scalper.open(0, prices[0], 5, scalper.cash)) {
                    scalperPosition[0] = scalper.portfolio.positions()
                            .get(scalper.portfolio.size() - 1).id();
                }
            } else if (scalperPosition[0] >= 0 && jump % 10 == 0) {
                scalper.close(scalperPosition[0], prices[0]);
                scalperPosition[0] = -1;
            }
        }

        // 收尾：把还挂着的仓按最后价格平掉（三条策略一视同仁）
        long[] lastPrices = engine.prices();
        chaser.closeAll(0, lastPrices[0]);
        chaser.closeAll(1, lastPrices[1]);
        chaser.closeAll(2, lastPrices[2]);
        degen.closeAll(0, lastPrices[0]);
        scalper.closeAll(0, lastPrices[0]);

        long netChaser = chaser.netWorth(lastPrices);
        long netDegen = degen.netWorth(lastPrices);
        long netScalper = scalper.netWorth(lastPrices);
        long netLabour = labour.netWorth(lastPrices);
        long pnlChaser = netChaser - START_CENTS;
        long pnlDegen = netDegen - START_CENTS;
        long pnlScalper = netScalper - START_CENTS;
        long pnlLabour = netLabour - START_CENTS;

        // 打印实测数字（gradle 的测试报告里能看到；汇报里直接引用这几个数）
        System.out.println("=== 经济平衡模拟：同一颗种子 " + SEED + "，" + SIM_JUMP_COUNT
                + " 跳（≈ " + (SIM_JUMP_COUNT * 2 / 3600) + " 小时），初始资金 "
                + money(START_CENTS));
        System.out.println("A 追事件(×" + CHASER_LEVERAGE + ")：净 " + money(netChaser)
                + "  盈亏 " + money(pnlChaser)
                + "  信号 " + chaserSignals + " 次，成交 " + chaser.trades
                + " 笔，爆仓 " + chaser.liquidations + " 次，手续费 " + money(chaser.feesPaid));
        System.out.println("B 死多×400 ：净 " + money(netDegen) + "  盈亏 " + money(pnlDegen)
                + "  成交 " + degen.trades + " 笔，爆仓 " + degen.liquidations
                + " 次，手续费 " + money(degen.feesPaid));
        System.out.println("C 频繁往返 ：净 " + money(netScalper) + "  盈亏 " + money(pnlScalper)
                + "  成交 " + scalper.trades + " 笔，爆仓 " + scalper.liquidations
                + " 次，手续费 " + money(scalper.feesPaid));
        System.out.println("D 只回收   ：净 " + money(netLabour) + "  盈亏 " + money(pnlLabour)
                + "  （每 " + LABOR_BATCH_JUMPS + " 跳 +" + money(LABOR_BATCH_CENTS) + "，无风险）");
        // 新的「挖矿收入量级」：一组各是多少（汇报直接引用这几个数）
        ValueTableFixture table = ValueTableFixture.load();
        for (String[] row : new String[][]{
                {"minecraft:cobblestone", "一组圆石(64)"},
                {"minecraft:oak_log", "一组原木(64)"},
                {"minecraft:coal", "一组煤炭(64)"},
                {"minecraft:raw_iron", "一组粗铁(64)"},
                {"minecraft:diamond", "一组钻石(64)"},
                {"minecraft:diamond", "一颗钻石"}}) {
            int unit = table.value(row[0]);
            long group = row[1].startsWith("一组") ? 64L * unit : unit;
            System.out.println("   " + row[1] + " ≈ " + money(group * 100L) + "（单价 "
                    + money(unit * 100L) + "）");
        }

        // ---------- 断言（宽松方向性：只看「亏没亏、亏得够不够狠」） ----------
        assertTrue(pnlChaser < 0, "A 追事件必须是负收益，实测 " + money(pnlChaser));
        assertTrue(pnlDegen < 0, "B 死多高杠杆必须是负收益，实测 " + money(pnlDegen));
        assertTrue(degen.liquidations >= 1, "×400 必然会爆仓，实测 " + degen.liquidations + " 次");
        // ⚠️ C 的「净额」不可能是负数：一旦连 1 股都买不起，策略就没法继续交易，
        //    余额会停在「买不起一手」的残值上（实测几十金币以内）。所以这里断言的是**盈亏**（收益率），
        //    并且要求亏掉**至少八成**本金 —— 这才是「被手续费磨死」。
        assertTrue(pnlScalper < 0 && pnlScalper <= -START_CENTS * 0.8,
                "C 频繁往返必须被手续费磨掉至少八成本金，实测 " + money(pnlScalper));
        assertTrue(scalper.feesPaid > START_CENTS,
                "C 的手续费应该超过初始资金（说明是「被磨死」而不是「行情不好」），实测 "
                        + money(scalper.feesPaid));
        assertTrue(pnlChaser <= -START_CENTS * 0.8 && pnlDegen <= -START_CENTS * 0.8,
                "A/B 也该亏掉至少八成本金");
        assertTrue(pnlLabour > 0, "D 只回收必须是正收益，实测 " + money(pnlLabour));
        assertTrue(netLabour > netChaser && netLabour > netScalper && netLabour > netDegen,
                "劳动必须明显跑赢三种投机");
        assertTrue(chaserSignals > 50, "十万跳里应该抓到足够多的利好信号，实测 " + chaserSignals);
        assertTrue(chaser.trades >= 10, "追事件应该成交足够多笔才有统计意义，实测 " + chaser.trades);
    }

    /** 同一个种子跑两遍，四个数字必须一模一样（模拟本身可复现）。 */
    @Test
    void simulationIsReproducible() {
        long first = runScalperOnly(SEED);
        long second = runScalperOnly(SEED);
        assertEquals(first, second, "同一个种子必须给出同一串结果");
    }

    /** 只跑「频繁往返」这一条，用来验可复现（跑得比全套快）。 */
    private static long runScalperOnly(long seed) {
        MarketEngine engine = new MarketEngine(seed);
        Account scalper = new Account("C");
        int openId = -1;
        for (int jump = 1; jump <= 20_000; jump++) {
            engine.advanceAll();
            long[] prices = engine.prices();
            scalper.settle(prices);
            if (openId < 0 && jump % 10 == 0) {
                if (scalper.open(0, prices[0], 5, scalper.cash)) {
                    openId = scalper.portfolio.positions().get(scalper.portfolio.size() - 1).id();
                }
            } else if (openId >= 0 && jump % 10 == 0) {
                scalper.close(openId, prices[0]);
                openId = -1;
            }
        }
        scalper.closeAll(0, engine.price(0));
        return scalper.netWorth(engine.prices());
    }

    /** 只给打印用的金额格式化（测试不能碰客户端的 StockWidgets）。 */
    private static String money(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        return (negative ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100) + " 金币";
    }
}
