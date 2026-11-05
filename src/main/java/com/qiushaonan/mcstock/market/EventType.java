package com.qiushaonan.mcstock.market;

/**
 * 新闻 / 事件的**类型**（决定界面上的类型标签、成簇规则，以及玩家怎么读这条消息）。
 *
 * <p>纯 Java 枚举：服务端按序号推进协议，客户端用**同一个枚举**取语言键
 * （不各写一份下标表 —— 本项目在「购买页格子下标」上吃过三份实现的亏，见 AGENTS.md 7.3）。
 */
public enum EventType {

    /** 公司公告：只影响这一家公司（权重 1.0），偶尔带一点上游/下游外溢。 */
    COMPANY("company"),
    /** 行业快讯：一条产业链上的几家公司一起动。 */
    INDUSTRY("industry"),
    /** 宏观政策：关税、军方订单这种，通常同时砸/抬两只以上。 */
    MACRO("macro"),
    /** 市场传闻：**先涨后跌** —— 触发时给正向冲击，N 跳后自动来一条反向澄清（买预期卖事实）。 */
    RUMOR("rumor"),
    /**
     * 大盘状态播报（「市场交投转趋温和」这类气氛描述）。
     *
     * <p>⚠️ 它**不在** {@link StockEventTable} 里 —— 只有大盘状态机切换时才会产生，
     * 而且它的「影响」是每跳的基础漂移（bias），不是一次性冲击。
     */
    MARKET("market"),
    /**
     * **盘中异动**（插针）：{@link StockDefs#SPIKE_CHANCE} 概率出现的单跳急跌 + 部分回补。
     *
     * <p>同样**不在** {@link StockEventTable} 里（它没有「公司」归属，是按股票逐跳抽签的），
     * 但会往新闻流写一条中性叙述（「交易终端短暂故障」），让玩家知道**刚才发生了什么**，
     * 而不是看到一根莫名其妙的针。
     *
     * <p>⚠️ 新增枚举常量必须**加在最后**：网络包里传的是序号，
     * 插在中间会把老客户端已有的类型全部错位（协议 v3 的 {@code news[].type} 就是这么定义的）。
     */
    SPIKE("spike");

    private final String id;
    private final String nameKey;

    EventType(String id) {
        this.id = id;
        this.nameKey = "gui.mcstock.newstype." + id;
    }

    public String id() {
        return id;
    }

    /** 类型标签的语言键（界面上的「公司公告 / 行业快讯 / …」）。 */
    public String nameKey() {
        return nameKey;
    }

    /**
     * 网络包里的序号 → 枚举。**越界一律退回 {@link #COMPANY}，绝不抛异常** ——
     * 解码端抛异常会直接踢掉连接（NeoForge 对包解码异常的处理就是断开玩家）。
     */
    public static EventType byOrdinal(int ordinal) {
        EventType[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : COMPANY;
    }
}
