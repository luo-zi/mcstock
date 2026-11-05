package com.qiushaonan.mcstock.market;

/**
 * 仓位数学：纯函数，不碰任何 Minecraft 类，单测直接算。
 *
 * <p>约定：
 * <ul>
 *   <li>金额单位**分**，股数**有符号**（正 = 多头，负 = 空头）。</li>
 *   <li>加权均价 {@code avgEntry} 是开仓均价；{@code margin} 是这一仓**锁定的保证金总额**
 *       （加仓会累加、减仓按比例退还），开仓后不随价格变化。</li>
 *   <li>浮动盈亏 = 股数 × (现价 − 均价)：空头股数为负，价格下跌自动为正，不用分支。</li>
 * </ul>
 */
public final class PositionMath {

    private PositionMath() {
    }

    /** 仓位价值（notional）。股数最多 100000、价格最多基准价 5 倍，long 完全够。 */
    public static long notional(long priceCents, long shares) {
        return priceCents * shares;
    }

    /** 开仓需要的保证金 = 仓位价值 ÷ 杠杆。 */
    public static long marginFor(long priceCents, int qty, int leverage) {
        return Math.max(1L, notional(priceCents, qty) / Math.max(1, leverage));
    }

    /** 手续费 = 仓位价值 × {@code StockDefs.FEE_RATE}（现在 0.20%），向上取整（宁可多收 1 分，也不要有免费的一分钱仓位）。 */
    public static long feeFor(long notional) {
        long abs = Math.abs(notional);
        return (long) Math.ceil(abs * StockDefs.FEE_RATE);
    }

    /** 浮动盈亏（分）。 */
    public static long pnl(int shares, long avgEntryCents, long priceCents) {
        return shares * (priceCents - avgEntryCents);
    }

    /**
     * 爆仓价（分）：浮亏达到保证金的 {@link StockDefs#MARGIN_CALL_PERCENT}%（现在是 **45%**）就强平。
     * <p>多仓：均价 − 保证金×45% ÷ 股数；空仓：均价 + 保证金×45% ÷ |股数|。
     * 没有仓位时返回 0。
     *
     * <p>⚠️ 分子取 {@code margin × MARGIN_CALL_PERCENT / 100} 而不是写死的 {@code margin / 2}：
     * 2026-10-06 把爆仓线从 50 收到 45（先收到 40，玩家反馈太难之后回调），
 * 这里与 {@link #liquidated} 必须共用同一个常量，
     * 否则「画出来的爆仓价」会和「真正强平的价格」对不上（本项目在「一份口径两处实现」上吃过大亏）。
     */
    public static long liquidationPrice(int shares, long avgEntryCents, long marginCents) {
        if (shares == 0 || marginCents <= 0) {
            return 0L;
        }
        long tolerance = marginCents * StockDefs.MARGIN_CALL_PERCENT / 100L;
        long distance = Math.max(1L, tolerance / Math.abs(shares));
        return shares > 0 ? avgEntryCents - distance : avgEntryCents + distance;
    }

    /**
     * 这一跳之后该不该强平。
     * <p>⚠️ 结算按**跳价后的真实价格**，不是按爆仓价结算 —— 急跌可以一跳穿过爆仓线，
     * 把整份保证金（甚至更多现金）一次打光。这是「倾家荡产」的来源，别改成保本截断。
     */
    public static boolean liquidated(int shares, long avgEntryCents, long marginCents, long priceCents) {
        if (shares == 0 || marginCents <= 0) {
            return false;
        }
        long loss = -pnl(shares, avgEntryCents, priceCents);
        return loss * 100L >= marginCents * StockDefs.MARGIN_CALL_PERCENT;
    }

    /**
     * 主动平掉部分或全部仓位时退还的保证金：按股数比例退。
     * <p>先把「减仓股数」吸成非负数，避免空头（股数为负）时算反。
     */
    public static long marginReleased(int shares, long marginCents, int closed) {
        long abs = Math.abs(shares);
        if (abs == 0 || marginCents <= 0) {
            return 0L;
        }
        long take = Math.min(abs, Math.max(0, closed));
        return marginCents * take / abs;
    }

    /** 加仓后的加权均价（分）：旧均价 × 旧股数 + 新价 × 新股数。 */
    public static long weightedEntry(int oldShares, long oldAvg, int addedShares, long priceCents) {
        long total = Math.abs(oldShares) + Math.abs(addedShares);
        if (total == 0) {
            return priceCents;
        }
        return (oldAvg * Math.abs(oldShares) + priceCents * Math.abs(addedShares)) / total;
    }

    /**
     * 用现金 + 杠杆能开的最大股数（用于「数量 MAX」按钮）。
     * 需要 `股数 × (保证金系数 + 手续费系数) ≤ 现金`，其中保证金系数 = 1/杠杆、手续费系数 = 0.001。
     */
    public static int maxAffordable(long cashCents, long priceCents, int leverage) {
        if (priceCents <= 0 || cashCents <= 0) {
            return 0;
        }
        double perShare = (double) priceCents / Math.max(1, leverage) + priceCents * StockDefs.FEE_RATE;
        if (perShare <= 0) {
            return 0;
        }
        long qty = (long) Math.floor(cashCents / perShare);
        return (int) Math.max(0, Math.min(StockDefs.QTY_MAX, qty));
    }
}
