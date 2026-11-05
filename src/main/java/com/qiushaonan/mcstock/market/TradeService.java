package com.qiushaonan.mcstock.market;

/**
 * 开仓 / 平仓 / 逐仓强平结算：纯函数，不依赖 Minecraft。
 *
 * <p>语义（本次重构后）只有两件事，**没有任何「反向下单先平仓」的隐藏动作**：
 * <ol>
 *   <li>{@link #open} —— 买入 = 新开一个多头仓，卖出 = 新开一个空头仓。
 *       不管玩家手里已经有什么仓，都是**再挂一个仓上去**（这正是玩家要的：买卖都是新增仓位）；</li>
 *   <li>{@link #close} —— 按仓位 id 平掉**那一个**仓：按它自己的均价结算浮盈、退还它自己的保证金、
 *       收它自己那份平仓费。同一只股票上其它仓一个数都不动。</li>
 * </ol>
 *
 * <p>⚠️ 手续费按**仓位价值**（notional）收 {@code StockDefs.FEE_RATE}（现在是 **0.20%**，
 * 开平各一次 = 往返 0.40%），不是按保证金 —— 这是让高杠杆不能变成印钞机的关键：
 * 盈亏平衡所需的价格变动恒为 **0.40%**（与杠杆无关），而爆仓距离只有
 * {@code MARGIN_CALL_PERCENT ÷ 杠杆}（现在是 **45%** ÷ 杠杆：×2 是 22.5%、×400 是 0.11%）。
 * 口径与重构前一致（都是「按仓位算」），只是 2026-10-06 把这两个数一起调紧了 ——
 * 目的是「不让投机容易赚」，见 {@code EconomySimulationTest} 的三种策略模拟。
 */
public final class TradeService {

    private TradeService() {
    }

    public enum Status {
        /** 开了新仓。 */
        OPENED,
        /** 平掉了一个仓。 */
        CLOSED,
        /** 余额不够付「保证金 + 手续费」，什么都没发生。 */
        NO_CASH,
        /** 这个 id 没有对应的仓（已经被平掉 / 爆掉，或者根本不是自己的仓）。 */
        NO_POSITION,
        /** 数量非法（<= 0，或者大到夹不进来）。 */
        BAD_QTY,
        /** 仓位数已经到 {@link Portfolio#MAX_POSITIONS}，开不下新仓了。 */
        FULL
    }

    /**
     * 一次结算的结果。
     *
     * @param cashCents        结算后的现金（分）
     * @param openedQty         这次新开的股数（0 = 没开）
     * @param closedQty         这次平掉的股数（绝对值，0 = 没平）
     * @param priceCents        这次用的价格（分）
     * @param marginCents       这次开仓锁定的保证金（分）；{@code NO_CASH} 时是「本来需要多少保证金」
     * @param feeCents          这次收的手续费合计（分）
     * @param realizedPnlCents  这次平仓结算的浮动盈亏（分，可负）
     * @param positionId        这次开的/平的仓位 id（0 = 没有）
     */
    public record Outcome(Status status, long cashCents, int openedQty, int closedQty,
                          long priceCents, long marginCents, long feeCents, long realizedPnlCents,
                          int positionId) {
    }

    /**
     * 开仓：买入（{@code isLong}）或卖出（做空）**新开一个仓**。
     *
     * <p>数量会夹到 {@code [QTY_MIN, QTY_MAX]}（客户端发来的数字只当提示）；杠杆夹到
     * {@code [1, LEVERAGE_MAX]}。余额不够付「保证金 + 手续费」就整笔失败 ——
     * 这里**不会再顺手平掉任何反向仓**：那是旧语义，也是本次要拆掉的东西。
     */
    public static Outcome open(long cashCents, Portfolio portfolio, int stock, long priceCents,
                               int qty, int leverage, boolean isLong) {
        if (qty <= 0) {
            return fail(Status.BAD_QTY, cashCents, priceCents);
        }
        if (portfolio.size() >= Portfolio.MAX_POSITIONS) {
            return fail(Status.FULL, cashCents, priceCents);
        }
        int want = StockDefs.clamp(qty, StockDefs.QTY_MIN, StockDefs.QTY_MAX);
        int lev = StockDefs.clamp(leverage, StockDefs.LEVERAGE_MIN, StockDefs.LEVERAGE_MAX);
        long margin = PositionMath.marginFor(priceCents, want, lev);
        long fee = PositionMath.feeFor(PositionMath.notional(priceCents, want));
        long need = margin + fee;
        if (cashCents < need) {
            // 把「需要多少」带回给调用方，提示行才说得清差多少（以前这里回 0，提示永远写「需要 0.00」）
            return new Outcome(Status.NO_CASH, cashCents, 0, 0, priceCents, margin, fee, 0, 0);
        }
        int id = portfolio.addPosition(stock, isLong ? want : -want, priceCents, margin, lev);
        return new Outcome(Status.OPENED, cashCents - need, want, 0, priceCents, margin, fee, 0, id);
    }

    /**
     * 平掉一个仓：按现价结算这一仓的浮盈、退还这一仓的保证金、收这一仓的平仓费。
     *
     * <p>⚠️ 现金**允许为负**（2026-10-06 起）：穿仓时亏掉的钱可以超过本金，
     * 玩家会带着负债继续玩（负债时开不了新仓，并且背包里的可回收物会被自动卖掉还债，
     * 见 {@link DebtService}）。这里**不做任何下限夹取** —— 夹了就等于凭空免掉债务。
     * **只影响这一个仓**。
     */
    public static Outcome close(long cashCents, Portfolio portfolio, int positionId, long priceCents) {
        Position position = portfolio.byId(positionId);
        if (position == null) {
            return fail(Status.NO_POSITION, cashCents, priceCents);
        }
        long realized = position.pnl(priceCents);
        long fee = position.closeFee(priceCents);
        long released = position.marginCents();
        portfolio.removePosition(positionId);
        long cash = cashCents + released + realized - fee;
        return new Outcome(Status.CLOSED, cash, 0, position.absShares(), priceCents, 0, fee, realized,
                positionId);
    }

    /** 这个仓这一跳之后是否触发强平（逐仓判定，别的仓与它无关）。 */
    public static boolean shouldLiquidate(Portfolio portfolio, int positionId, long priceCents) {
        Position position = portfolio.byId(positionId);
        return position != null && position.liquidated(priceCents);
    }

    /**
     * 强平**一个仓**：按真实价格结算（可以亏穿它自己的保证金），**现金允许变负**。
     *
     * <p>⚠️ 结算按**跳价后的真实价格**：急跌可以一跳穿过爆仓线，把整份保证金（甚至更多现金）
     * 一次打光。这是「倾家荡产」的来源，别改成按爆仓价保本截断。
     *
     * <p>⚠️ 返回值可能是「比本金还大的亏损」，调用方**不要**再夹到 0（2026-10-06 起允许负债）：
     * 夹掉就等于替玩家免债，负债玩法（自动卖回收物还债）也就永远不会触发。
     *
     * @return 这次结算带来的现金变化（可能是负数）
     */
    public static long liquidate(Portfolio portfolio, int positionId, long priceCents) {
        Position position = portfolio.byId(positionId);
        if (position == null) {
            return 0L;
        }
        long realized = position.pnl(priceCents);
        long released = position.marginCents();
        portfolio.removePosition(positionId);
        return released + realized;
    }

    private static Outcome fail(Status status, long cashCents, long priceCents) {
        return new Outcome(status, cashCents, 0, 0, priceCents, 0, 0, 0, 0);
    }
}
