package com.qiushaonan.mcstock.market;

/**
 * 一张**条件单**：价格到达某个条件时，服务端替玩家执行一次交易（纯数据 + 纯判定，不依赖 Minecraft）。
 *
 * <p>设计口径（改这里之前先读完，免得把「服务端权威」改坏）：
 * <ul>
 *   <li><b>触发方向</b>：{@link Direction#ABOVE} = 现价 ≥ 触发价（突破单 / 追涨）；
 *       {@link Direction#BELOW} = 现价 ≤ 触发价（止损 / 跌破单）。边界值算触发（含等号）。</li>
 *   <li><b>触发动作</b>：{@link Action#OPEN_LONG} 买入开多、{@link Action#OPEN_SHORT} 卖出开空、
 *       {@link Action#CLOSE_ALL} 平掉这一只股票的**全部**仓（逐仓结算，见 {@code StockService#closeAll}）。
 *       后两种动作里 {@code qty} / {@code leverage} 不参与计算 —— 平仓不需要它们。</li>
 *   <li><b>只在跳价那一刻判定</b>：挂单当下即使已经满足条件也**不会**立刻成交，要等下一次价格推进
 *       （正常玩法是每 2 秒一跳；调试命令 {@code /mcstock advance} / {@code /mcstock price set}
 *       也会走一次判定）。这样「挂单」和「市价单」不会混淆。</li>
 *   <li><b>按现价成交</b>：触发后用的是**跳价后的真实现价**，不是触发价 —— 与爆仓结算同一口径，
 *       一跳跳空穿过触发价时不会给玩家一个不存在的价格。手续费、保证金、爆仓线全部照常算。</li>
 *   <li><b>一次性</b>：触发（或触发后成交失败）就移除，不会反复挂着重试。失败会给一条提示，
 *       玩家要再挂就再挂一张。</li>
 *   <li><b>服务端权威</b>：客户端发来的股票下标 / 方向序号 / 触发价 / 动作序号 / 数量 / 杠杆
 *       一律只当提示，服务端用 {@link #direction(int)} / {@link #action(int)} /
 *       {@link #clampTrigger(int, long)} / {@link StockDefs#clamp(int, int, int)} 重算一遍。</li>
 * </ul>
 *
 * <p>金额/价格单位一律是**分**（{@code long}），与 {@link Position}、{@link TradeService} 同口径。
 */
public record ConditionalOrder(int id, int stock, Direction direction, long triggerCents,
                              Action action, int qty, int leverage) {

    /** 价格从哪一侧碰到触发价。序号会进网络包与存档，所以**不要调整顺序**。 */
    public enum Direction {
        /** 现价 ≥ 触发价。 */
        ABOVE,
        /** 现价 ≤ 触发价。 */
        BELOW
    }

    /** 触发后执行什么。序号会进网络包与存档，所以**不要调整顺序**。 */
    public enum Action {
        /** 新开一个多头仓。 */
        OPEN_LONG,
        /** 新开一个空头仓。 */
        OPEN_SHORT,
        /** 市价平掉这只股票的全部仓（忽略 qty / leverage）。 */
        CLOSE_ALL
    }

    /**
     * 这一跳的现价是否触发本单。纯函数，单测直接调。
     *
     * <p>{@code priceCents <= 0} 视为「没有有效行情」，一律不触发 —— 畸形存档 / 引擎还没起时
     * 不该因为一个 0 价把一堆 BELOW 单全部打掉。
     */
    public boolean triggeredBy(long priceCents) {
        if (priceCents <= 0L || triggerCents <= 0L) {
            return false;
        }
        return direction == Direction.ABOVE
                ? priceCents >= triggerCents
                : priceCents <= triggerCents;
    }

    /**
     * 方向 → 语言键（服务端提示 / GUI 列表 / 命令三处共用这一张表）。
     *
     * <p>⚠️ 键是拼在纯 Java 类里的字符串常量，{@code check_lang_keys.py} 静态抓不到，
     * 所以这几个键登记在它的 {@code DYNAMIC_REQUIRED} 里 —— 改这里记得同步那张表。
     */
    public static String directionKey(Direction direction) {
        return direction == Direction.ABOVE
                ? "gui.mcstock.cond.dir.above" : "gui.mcstock.cond.dir.below";
    }

    /** 动作 → 语言键（同 {@link #directionKey}）。 */
    public static String actionKey(Action action) {
        return switch (action) {
            case OPEN_LONG -> "gui.mcstock.cond.action.buy";
            case OPEN_SHORT -> "gui.mcstock.cond.action.sell";
            case CLOSE_ALL -> "gui.mcstock.cond.action.close";
        };
    }

    /** 序号 → 方向，越界兜底成 {@link Direction#ABOVE}（畸形包不该让服务端抛异常）。 */
    public static Direction direction(int ordinal) {
        Direction[] values = Direction.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Direction.ABOVE;
    }

    /** 序号 → 动作，越界兜底成 {@link Action#OPEN_LONG}。 */
    public static Action action(int ordinal) {
        Action[] values = Action.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Action.OPEN_LONG;
    }

    /**
     * 触发价夹进**这只股票**的价格区间（与 {@code MarketEngine} 用的是同一组常量）。
     *
     * <p>为什么必须夹：不夹的话客户端可以挂一个 {@code 1 分} 的 BELOW 单（永远不触发）或
     * {@code Long.MAX_VALUE} 的 ABOVE 单，把 8 个格子全占住；夹了之后每单都落在行情可能到达的区间里。
     * 股票下标非法时返回 {@code 0}（调用方应以 {@link #valid()} 判掉，别拿它去建单）。
     */
    public static long clampTrigger(int stock, long cents) {
        if (stock < 0 || stock >= StockDefs.COUNT) {
            return 0L;
        }
        long low = Math.max(1L, StockDefs.priceFloor(stock));
        long high = StockDefs.priceCeil(stock);
        return Math.max(low, Math.min(high, cents));
    }

    /** 数量按现有下单口径夹一次（服务端重算，客户端夹的那次不算数）。 */
    public static int clampQty(int qty) {
        return StockDefs.clamp(qty, StockDefs.QTY_MIN, StockDefs.QTY_MAX);
    }

    /** 杠杆按现有下单口径夹一次。 */
    public static int clampLeverage(int leverage) {
        return StockDefs.clamp(leverage, StockDefs.LEVERAGE_MIN, StockDefs.LEVERAGE_MAX);
    }

    /**
     * 从存档 / 网络包恢复时的收口判据：股票下标、方向、触发价、动作、数量、杠杆都得是合法值。
     *
     * <p>⚠️ 与 {@link Portfolio#restore} 同一个思路：存档是外部输入，畸形条目直接丢掉，
     * 不能让它在触发那一刻把玩法搞崩。
     */
    public boolean valid() {
        return id > 0
                && stock >= 0 && stock < StockDefs.COUNT
                && direction != null && action != null
                && triggerCents > 0L
                && qty >= StockDefs.QTY_MIN && qty <= StockDefs.QTY_MAX
                && leverage >= StockDefs.LEVERAGE_MIN && leverage <= StockDefs.LEVERAGE_MAX;
    }
}
