package com.qiushaonan.mcstock.market;

/**
 * 一个**独立仓位**：纯数据 + 纯计算，不依赖 Minecraft（存盘见 {@link PortfolioSnapshot}）。
 *
 * <p>一次「买入」= 新开一个多头仓，一次「卖出」= 新开一个空头仓；**永不自动对冲、永不合并** ——
 * 同一只股票可以同时挂着一堆多仓和空仓，各自有各自的均价、保证金与爆仓价。
 * 这也是玩家明确要的语义：「买入卖出都是新增仓位」。
 *
 * <p>字段单位与符号：
 * <ul>
 *   <li>{@code id} —— 玩家内唯一、只增不减（用来定位「平哪一个仓」，也是网络包里的句柄）；</li>
 *   <li>{@code stock} —— 股票下标 0..{@code StockDefs.COUNT-1}；</li>
 *   <li>{@code shares} —— **有符号**股数：正 = 多、负 = 空（0 股仓不存在）；</li>
 *   <li>{@code entryCents} —— 开仓均价（分），开仓后不变（这一仓只有一次开仓）；</li>
 *   <li>{@code marginCents} —— 这一仓锁定的保证金（分），开仓后不变；</li>
 *   <li>{@code leverage} —— 开仓时的杠杆，**仅用于显示**；从旧存档迁移来的仓位为 0（未知）。</li>
 * </ul>
 *
 * <p>⚠️ 因为一个仓「开出来就不再变」，它是不可变的 record：加仓是**再开一个仓**，
 * 减仓是**平掉一个仓**。于是「同一只股票多个仓互不影响」是数据结构天然保证的，
 * 不靠调用方小心翼翼。
 */
public record Position(int id, int stock, int shares, long entryCents, long marginCents, int leverage) {

    /** 多头（股数为正）。 */
    public boolean isLong() {
        return shares > 0;
    }

    /** 这一仓的股数（绝对值，显示用）。 */
    public int absShares() {
        return Math.abs(shares);
    }

    /** 这一仓按现价算的仓位价值（notional，分，恒为正）。 */
    public long notional(long priceCents) {
        return PositionMath.notional(priceCents, absShares());
    }

    /** 这一仓的浮动盈亏（分）。 */
    public long pnl(long priceCents) {
        return PositionMath.pnl(shares, entryCents, priceCents);
    }

    /** 这一仓对净值的贡献：保证金 + 浮动盈亏（平掉之后就是这么多钱回到现金）。 */
    public long equity(long priceCents) {
        return marginCents + pnl(priceCents);
    }

    /** 这一仓的爆仓价（分）。 */
    public long liquidationPrice() {
        return PositionMath.liquidationPrice(shares, entryCents, marginCents);
    }

    /**
     * 这一仓该不该强平（浮亏吃掉了保证金的 {@code StockDefs.MARGIN_CALL_PERCENT}%，现在是 45%）。
     *
     * <p>逐仓判定：一只股票上别的仓不管亏成什么样，都不影响这一仓的爆仓线。
     */
    public boolean liquidated(long priceCents) {
        return PositionMath.liquidated(shares, entryCents, marginCents, priceCents);
    }

    /** 按现价平掉这一仓要收的手续费（仓位价值 × {@code StockDefs.FEE_RATE}，现在 0.20%）。 */
    public long closeFee(long priceCents) {
        return PositionMath.feeFor(notional(priceCents));
    }

    /**
     * 从「均价 / 保证金」反推杠杆 —— **只用于显示**（协议里不传杠杆字段）。
     *
     * <p>保证金 = 开仓价值 ÷ 杠杆，所以 `开仓价值 ÷ 保证金` 就是开仓时的杠杆；
     * 用 {@code entryCents}（开仓均价）而不是现价，反推出来的才是开仓那一档。
     * 迁移来的旧仓杠杆记 0（未知），这里仍能反推出正确值（旧仓的保证金也是同一个算法）。
     */
    public static int deriveLeverage(int shares, long entryCents, long marginCents) {
        long abs = Math.abs((long) shares);
        if (abs == 0 || entryCents <= 0 || marginCents <= 0) {
            return 0;
        }
        long derived = Math.round((double) PositionMath.notional(entryCents, abs) / marginCents);
        return (int) Math.max(0L, Math.min((long) StockDefs.LEVERAGE_MAX, derived));
    }
}
