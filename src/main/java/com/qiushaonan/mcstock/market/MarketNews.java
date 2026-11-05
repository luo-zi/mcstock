package com.qiushaonan.mcstock.market;

/**
 * **一条新闻**（服务端保留最近 {@link StockDefs#NEWS_KEEP} 条，最新的在最前）。纯 Java。
 *
 * <p>字段与需求一一对应：{@code 股票 / 事件名键 / 类型 / 影响百分比 / 触发时的跳数 / 是否已澄清}。
 * 大盘状态播报用 {@link #MARKET_STOCK}（{@code -1}）当股票下标 —— 它不属于任何一只股票。
 *
 * <p>⚠️ {@code nameKey} 是**语言键**而不是翻译好的文本：新闻要在**客户端**按玩家自己的语言渲染
 * （多人服里服务端语言常常是 en、客户端是 zh），而且客户端还要按方向配色、按年龄淡出。
 * 这正是本项目踩过的那个坑（服务端 {@code Component.getString()} 之后键名就没了，
 * 客户端只能拿模板硬猜属于哪一页）—— 新闻这条链路从一开始就发键名，不走那条老路。
 *
 * @param stock     股票下标；{@link #MARKET_STOCK} = 大盘状态播报
 * @param id        事件 id（{@code mine_collapse} / 状态 id {@code bull}）；调试与去重用
 * @param nameKey   展示名的语言键
 * @param type      类型（公司 / 行业 / 宏观 / 传闻 / 大盘）
 * @param direction {@code +1} 利好 / {@code -1} 利空（澄清**不会**改这个：它标记的是「这条消息本身」）
 * @param permille  名义影响（千分比，带符号）——对**这家公司**的冲击幅度（见 {@link StockEventTable}）
 * @param jump      触发时的跳数
 * @param clarifyAt 计划澄清的跳数（0 = 不澄清）；只有传闻会填
 * @param clarified 是否已经澄清过
 * @param chainDepth 这是「新闻扎堆」链上的第几条：{@code 1} = 自然触发、{@code 2~3} = 跟风的同类型新闻、
 *                   {@code 0} = 大盘状态播报。**不进协议**，只给调试命令与单测看
 *                   （成簇上限 {@link StockDefs#CLUSTER_MAX_CHAIN} 靠它才验得准：
 *                   光看「同类型新闻挨得近」会把两条互不相干的链误判成一条）
 */
public record MarketNews(int stock, String id, String nameKey, EventType type, int direction,
                         int permille, long jump, long clarifyAt, boolean clarified, int chainDepth) {

    /** 「大盘」这条新闻的股票下标：它不属于任何一只股票。 */
    public static final int MARKET_STOCK = -1;

    /** 这条新闻是不是大盘状态播报。 */
    public boolean isMarket() {
        return stock == MARKET_STOCK;
    }

    /** 澄清标记的副本（记录不可变，澄清时整条替换回列表）。 */
    public MarketNews asClarified() {
        return new MarketNews(stock, id, nameKey, type, direction, permille, jump, clarifyAt,
                true, chainDepth);
    }

    /** 距今多少 tick（客户端淡出用；跳 → tick 按 {@link StockDefs#PRICE_TICK_TICKS} 折算）。 */
    public int ageTicks(long currentJump) {
        long jumps = Math.max(0L, currentJump - jump);
        return (int) Math.min(Integer.MAX_VALUE, jumps * StockDefs.PRICE_TICK_TICKS);
    }
}
