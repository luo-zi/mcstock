package com.qiushaonan.mcstock.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **股票事件表**（有名字、有剧情、有生命周期的市场冲击）—— 纯 Java，不 import 任何 Minecraft 类。
 *
 * <p>每个事件定义：{@code id / 名称键 / 类型 / 方向 / 初始幅度(σ 倍数) / 半衰期(跳) / σ 倍率 /
 * 影响映射(哪几只股票、权重多少) / 传闻澄清参数}。触发之后由 {@link MarketShock} 负责「先急动、
 * 再按半衰期慢慢消化」，本类只管「是什么、砸谁、砸多狠」。
 *
 * <h2>影响映射（外溢）的写法与理由</h2>
 * <ul>
 *   <li>每条事件的影响映射**必须包含自己这家公司、权重 1.0**（静态自检）：公司自己的消息
 *       对它是满额冲击，这既符合直觉，也让「新闻上的百分比」有一个确定的含义
 *       （就是对本公司的影响，见 {@link EventDef#permille(int)}）；</li>
 *   <li>权重可以是**负数**＝反向外溢。例：{@code portal_congestion}（传送门拥堵）
 *       对下界贸易是利空，但资金会往稳健的钻石矿业避险 → 钻石矿业那一项权重写 −0.25
 *       （等价于「同一条新闻，对它是利好」）；</li>
 *   <li>幅度按**各股票自己的 σ** 缩放（{@code |权重| × 幅度 × σ[目标]}）：所以同一条宏观新闻
 *       落在激进股上是 ±15%、落在稳健股上只有 ±2% —— 这才是「同一件事，不同公司反应不同」。</li>
 * </ul>
 *
 * <h2>触发概率</h2>
 * <p>每只股票每跳 {@link StockDefs#EVENT_CHANCE}（0.25% ≈ 每 13 分钟一次）；
 * 高波动状态下**翻倍**（{@link StockDefs#VOLATILE_EVENT_MULTIPLIER}）——
 * 「市场一乱，消息也变多」。
 */
public final class StockEventTable {

    private StockEventTable() {
    }

    /**
     * 影响映射的一项。
     *
     * @param stock  受影响的股票下标
     * @param weight 相对本事件方向的权重：{@code 1.0} = 满额、{@code 0.3} = 三成外溢、
     *               **负数 = 反向**（资金避险这类）；幅度的绝对值不超过 1
     */
    public record Target(int stock, double weight) {
    }

    /**
     * 一条事件定义。
     *
     * @param id            事件 id（存盘 / 调试 / 新闻去重用；不是语言键）
     * @param nameKey       展示名的语言键，固定 {@code gui.mcstock.event.<id>}
     * @param type          类型（公司 / 行业 / 宏观 / 传闻）
     * @param direction     {@code +1} 利好 / {@code -1} 利空
     * @param magnitude     初始幅度（σ 的倍数，3~8）
     * @param halfLife      半衰期（跳，20~60）：多少跳后贡献剩一半
     * @param sigmaMultiple 存续期内 σ 的放大倍率（1.5~3）
     * @param targets       影响映射（含本公司、权重 1.0）
     * @param clarifyAfter  传闻专用：多少跳后自动澄清（跳；非传闻为 0）
     * @param clarifyFactor 传闻专用：澄清冲击的幅度倍率（0.6~0.8，即回吐六到八成）
     */
    public record EventDef(String id, String nameKey, EventType type, int direction, double magnitude,
                           int halfLife, double sigmaMultiple, List<Target> targets,
                           int clarifyAfter, double clarifyFactor) {

        /** 这条事件是不是「市场传闻」（先涨后跌，见 {@link EventType#RUMOR}）。 */
        public boolean isRumor() {
            return type == EventType.RUMOR;
        }

        /**
         * 名义影响（千分比）：对**本公司**的冲击幅度 = 方向 × 幅度 × σ[home]。
         *
         * <p>{@code homeStock} 由表的位置决定（表里第 stock 行的公司就是 stock）；
         * 本公司权重恒为 1.0（静态自检保证），所以这里不需要查影响映射。
         */
        public int permille(int homeStock) {
            return (int) Math.round(direction * magnitude
                    * StockDefs.VOLATILITY[StockDefs.clamp(homeStock, 0, StockDefs.COUNT - 1)] * 1000.0);
        }
    }

    /** 语言键前缀：{@code gui.mcstock.event.<id>}。 */
    public static final String NAME_KEY_PREFIX = "gui.mcstock.event.";

    /** 幅度 / 半衰期 / σ 倍率 / 传闻回吐的**设计区间**（静态自检 + 单测都按它们查）。 */
    public static final double MIN_MAGNITUDE = 3.0;
    public static final double MAX_MAGNITUDE = 8.0;
    public static final int MIN_HALF_LIFE = 20;
    public static final int MAX_HALF_LIFE = 60;
    public static final double MIN_SIGMA_MULTIPLE = 1.5;
    public static final double MAX_SIGMA_MULTIPLE = 3.0;
    public static final double MIN_CLARIFY_FACTOR = 0.6;
    public static final double MAX_CLARIFY_FACTOR = 0.8;

    /**
     * 事件表：外层下标 = 股票下标（与 {@link StockDefs#IDS} 同序），每只股票 6 条
     * （5 条常规 + 1 条传闻）。
     *
     * <h3>钻石矿业（稳健，σ = 0.6%）—— 实体产业，事故与利好都偏经营面</h3>
     * <ul>
     *   <li>{@code mine_collapse} 矿洞塌方（公司，−6σ，半衰期 30）：单一矿区停产，只砸自己；</li>
     *   <li>{@code giant_vein} 发现巨型矿脉（公司，+6σ，半衰期 45）：矿脉能挖很久 → 半衰期长；</li>
     *   <li>{@code deep_core_tech} 深层开采技术突破（行业，+5σ，半衰期 60）：全行业利好，
     *       红石科技作为设备供应商外溢 0.3；</li>
     *   <li>{@code smuggling_raid} 钻石走私案被查（宏观，−4.5σ，半衰期 25）：黑市出货受阻，
     *       顺带砸到做跨境物流的下界贸易（外溢 0.4）；</li>
     *   <li>{@code miners_strike} 矿工罢工（公司，−3.5σ，半衰期 20）：影响面最小、恢复最快；</li>
     *   <li>{@code vein_rumor} 传闻发现新矿脉（传闻，+5σ，18 跳后澄清 0.7）。</li>
     * </ul>
     *
     * <h3>红石科技（波动，σ = 1.2%）—— 成长股，订单与事故都来得猛</h3>
     * <ul>
     *   <li>{@code patent_granted} 红石专利获批（公司，+5σ，半衰期 40）；</li>
     *   <li>{@code circuit_short} 电路短路事故（公司，−5σ，半衰期 25）；</li>
     *   <li>{@code military_order} 军方大额订单（宏观，+7σ，半衰期 50）：要大量原料，
     *       钻石矿业外溢 0.35；</li>
     *   <li>{@code engineer_poached} 核心工程师跳槽（公司，−4σ，半衰期 30）；</li>
     *   <li>{@code automation_line} 自动化产线投产（行业，+3.5σ，半衰期 60）：产能扩张对物流是增量，
     *       下界贸易外溢 0.25；</li>
     *   <li>{@code order_rumor} 传闻接到军方大单（传闻，+6σ，15 跳后澄清 0.75）：传闻比真订单更猛
     *       （消息面炒预期），澄清回吐也更多。</li>
     * </ul>
     *
     * <h3>下界贸易（激进，σ = 2.5%）—— 跨界生意，政策与航线一句话就是 ±15%~20%</h3>
     * <ul>
     *   <li>{@code portal_congestion} 传送门拥堵（行业，−4σ，半衰期 20）：物流堵了对它是利空，
     *       但资金往稳健股避险 → 钻石矿业权重 **−0.25**（反向外溢，利好）；</li>
     *   <li>{@code piglin_caravan} 猪灵商队抵达（公司，+5σ，半衰期 30）；</li>
     *   <li>{@code blaze_shortage} 烈焰棒短缺（行业，+4.5σ，半衰期 35）：它手里的库存涨价是利好，
     *       而红石科技要拿烈焰粉当原料，成本上升 → 红石科技权重 **−0.3**（反向外溢）；</li>
     *   <li>{@code tariff_hike} 跨界关税上调（宏观，−6σ，半衰期 45）：跨境物流成本直接上升，
     *       另外两家的货也要过境，同向承压（外溢 0.3 / 0.3）；</li>
     *   <li>{@code fortress_route} 要塞航线开通（宏观，+8σ，半衰期 60）：长期基础设施，
     *       全市场受益，但贸易最直接；</li>
     *   <li>{@code route_rumor} 传闻要塞航线即将开通（传闻，+6.5σ，20 跳后澄清 0.7）。</li>
     * </ul>
     */
    private static final EventDef[][] TABLE = {
            // ---------- 0 钻石矿业（稳健） ----------
            {
                    new EventDef("mine_collapse", NAME_KEY_PREFIX + "mine_collapse", EventType.COMPANY,
                            -1, 6.0, 30, 2.0, List.of(new Target(0, 1.0)), 0, 0.0),
                    new EventDef("giant_vein", NAME_KEY_PREFIX + "giant_vein", EventType.COMPANY,
                            +1, 6.0, 45, 1.8, List.of(new Target(0, 1.0)), 0, 0.0),
                    new EventDef("deep_core_tech", NAME_KEY_PREFIX + "deep_core_tech", EventType.INDUSTRY,
                            +1, 5.0, 60, 1.5, List.of(new Target(0, 1.0), new Target(1, 0.3)), 0, 0.0),
                    new EventDef("smuggling_raid", NAME_KEY_PREFIX + "smuggling_raid", EventType.MACRO,
                            -1, 4.5, 25, 2.2, List.of(new Target(0, 1.0), new Target(2, 0.4)), 0, 0.0),
                    new EventDef("miners_strike", NAME_KEY_PREFIX + "miners_strike", EventType.COMPANY,
                            -1, 3.5, 20, 1.6, List.of(new Target(0, 1.0)), 0, 0.0),
                    new EventDef("vein_rumor", NAME_KEY_PREFIX + "vein_rumor", EventType.RUMOR,
                            +1, 5.0, 25, 2.0, List.of(new Target(0, 1.0)), 18, 0.70),
            },
            // ---------- 1 红石科技（波动） ----------
            {
                    new EventDef("patent_granted", NAME_KEY_PREFIX + "patent_granted", EventType.COMPANY,
                            +1, 5.0, 40, 1.8, List.of(new Target(1, 1.0)), 0, 0.0),
                    new EventDef("circuit_short", NAME_KEY_PREFIX + "circuit_short", EventType.COMPANY,
                            -1, 5.0, 25, 2.0, List.of(new Target(1, 1.0)), 0, 0.0),
                    new EventDef("military_order", NAME_KEY_PREFIX + "military_order", EventType.MACRO,
                            +1, 7.0, 50, 2.4, List.of(new Target(1, 1.0), new Target(0, 0.35)), 0, 0.0),
                    new EventDef("engineer_poached", NAME_KEY_PREFIX + "engineer_poached", EventType.COMPANY,
                            -1, 4.0, 30, 1.6, List.of(new Target(1, 1.0)), 0, 0.0),
                    new EventDef("automation_line", NAME_KEY_PREFIX + "automation_line", EventType.INDUSTRY,
                            +1, 3.5, 60, 1.5, List.of(new Target(1, 1.0), new Target(2, 0.25)), 0, 0.0),
                    new EventDef("order_rumor", NAME_KEY_PREFIX + "order_rumor", EventType.RUMOR,
                            +1, 6.0, 20, 2.6, List.of(new Target(1, 1.0), new Target(0, 0.2)), 15, 0.75),
            },
            // ---------- 2 下界贸易（激进） ----------
            {
                    new EventDef("portal_congestion", NAME_KEY_PREFIX + "portal_congestion", EventType.INDUSTRY,
                            -1, 4.0, 20, 2.0, List.of(new Target(2, 1.0), new Target(0, -0.25)), 0, 0.0),
                    new EventDef("piglin_caravan", NAME_KEY_PREFIX + "piglin_caravan", EventType.COMPANY,
                            +1, 5.0, 30, 2.0, List.of(new Target(2, 1.0)), 0, 0.0),
                    new EventDef("blaze_shortage", NAME_KEY_PREFIX + "blaze_shortage", EventType.INDUSTRY,
                            +1, 4.5, 35, 2.2, List.of(new Target(2, 1.0), new Target(1, -0.3)), 0, 0.0),
                    new EventDef("tariff_hike", NAME_KEY_PREFIX + "tariff_hike", EventType.MACRO,
                            -1, 6.0, 45, 2.4,
                            List.of(new Target(2, 1.0), new Target(0, 0.3), new Target(1, 0.3)), 0, 0.0),
                    new EventDef("fortress_route", NAME_KEY_PREFIX + "fortress_route", EventType.MACRO,
                            +1, 8.0, 60, 2.6,
                            List.of(new Target(2, 1.0), new Target(0, 0.2), new Target(1, 0.2)), 0, 0.0),
                    new EventDef("route_rumor", NAME_KEY_PREFIX + "route_rumor", EventType.RUMOR,
                            +1, 6.5, 25, 2.8, List.of(new Target(2, 1.0), new Target(0, 0.15)), 20, 0.70),
            },
    };

    /** {@code (股票, 行号)} 的扁平表，按类型分组 —— 成簇时「再抽一条同类型事件」用。 */
    private static final Map<EventType, int[][]> BY_TYPE = new HashMap<>();

    static {
        if (TABLE.length != StockDefs.COUNT) {
            throw new IllegalStateException("事件表必须每只股票一张：" + TABLE.length + " != " + StockDefs.COUNT);
        }
        // ⚠️ 先把**利空幅度整体放大**（口径：「不要让玩家容易赚」—— 同样多的一堆消息里，
        //    亏的那几发比赚的那几发更狠）。表里写的仍是设计值（3~8σ），
        //    放大之后最大 6.9σ，仍在区间内；下面的自检直接校验**生效值**。
        double scale = StockDefs.BEARISH_EVENT_SCALE;
        for (int stock = 0; stock < TABLE.length; stock++) {
            for (int i = 0; i < TABLE[stock].length; i++) {
                EventDef def = TABLE[stock][i];
                if (def.direction() < 0) {
                    TABLE[stock][i] = new EventDef(def.id(), def.nameKey(), def.type(), def.direction(),
                            def.magnitude() * scale, def.halfLife(), def.sigmaMultiple(),
                            def.targets(), def.clarifyAfter(), def.clarifyFactor());
                }
            }
        }
        Set<String> ids = new HashSet<>();
        Map<EventType, List<int[]>> byType = new HashMap<>();
        for (int stock = 0; stock < TABLE.length; stock++) {
            if (TABLE[stock].length < 4) {
                throw new IllegalStateException(StockDefs.IDS[stock] + " 的事件少于 4 个");
            }
            boolean hasUp = false;
            boolean hasDown = false;
            for (int index = 0; index < TABLE[stock].length; index++) {
                EventDef def = TABLE[stock][index];
                if (def.direction() != 1 && def.direction() != -1) {
                    throw new IllegalStateException(def.id() + " 的方向必须是 ±1");
                }
                if (def.magnitude() < MIN_MAGNITUDE || def.magnitude() > MAX_MAGNITUDE) {
                    throw new IllegalStateException(def.id() + " 的幅度超出 " + MIN_MAGNITUDE + "~" + MAX_MAGNITUDE + "σ");
                }
                if (def.halfLife() < MIN_HALF_LIFE || def.halfLife() > MAX_HALF_LIFE) {
                    throw new IllegalStateException(def.id() + " 的半衰期超出 " + MIN_HALF_LIFE + "~" + MAX_HALF_LIFE + " 跳");
                }
                if (def.sigmaMultiple() < MIN_SIGMA_MULTIPLE || def.sigmaMultiple() > MAX_SIGMA_MULTIPLE) {
                    throw new IllegalStateException(def.id() + " 的 σ 倍率超出 "
                            + MIN_SIGMA_MULTIPLE + "~" + MAX_SIGMA_MULTIPLE);
                }
                if (def.type() == EventType.MARKET) {
                    throw new IllegalStateException(def.id() + " 不能是 MARKET 类型（那只归大盘状态机）");
                }
                if (def.isRumor()) {
                    if (def.direction() != 1) {
                        throw new IllegalStateException(def.id() + " 是传闻：传闻一律先给正向冲击（先涨后跌）");
                    }
                    if (def.clarifyAfter() <= 0) {
                        throw new IllegalStateException(def.id() + " 是传闻但没写澄清延迟");
                    }
                    if (def.clarifyFactor() < MIN_CLARIFY_FACTOR || def.clarifyFactor() > MAX_CLARIFY_FACTOR) {
                        throw new IllegalStateException(def.id() + " 的澄清回吐倍率超出 "
                                + MIN_CLARIFY_FACTOR + "~" + MAX_CLARIFY_FACTOR);
                    }
                } else if (def.clarifyAfter() != 0 || def.clarifyFactor() != 0.0) {
                    throw new IllegalStateException(def.id() + " 不是传闻，不该有澄清参数");
                }
                if (!def.nameKey().equals(NAME_KEY_PREFIX + def.id())) {
                    throw new IllegalStateException(def.id() + " 的语言键必须是 " + NAME_KEY_PREFIX + def.id());
                }
                if (!ids.add(def.id())) {
                    throw new IllegalStateException("事件 id 重复：" + def.id());
                }
                // 影响映射：必须有本公司且权重 1.0；股票下标合法；权重非 0 且 |权重| <= 1
                boolean home = false;
                for (Target target : def.targets()) {
                    if (target.stock() < 0 || target.stock() >= StockDefs.COUNT) {
                        throw new IllegalStateException(def.id() + " 的影响映射里有非法股票下标 " + target.stock());
                    }
                    if (target.weight() == 0.0 || Math.abs(target.weight()) > 1.0) {
                        throw new IllegalStateException(def.id() + " 的权重必须非 0 且 |权重| <= 1");
                    }
                    if (target.stock() == stock) {
                        if (target.weight() != 1.0) {
                            throw new IllegalStateException(def.id() + " 对**本公司**的权重必须是 1.0");
                        }
                        home = true;
                    }
                }
                if (!home) {
                    throw new IllegalStateException(def.id() + " 的影响映射里必须包含本公司（权重 1.0）");
                }
                hasUp |= def.direction() > 0;
                hasDown |= def.direction() < 0;
                byType.computeIfAbsent(def.type(), k -> new ArrayList<>()).add(new int[]{stock, index});
            }
            if (!hasUp || !hasDown) {
                throw new IllegalStateException(StockDefs.IDS[stock] + " 的事件必须正负都有");
            }
        }
        for (Map.Entry<EventType, List<int[]>> entry : byType.entrySet()) {
            BY_TYPE.put(entry.getKey(), entry.getValue().toArray(new int[0][]));
        }
    }

    /** 这只股票有几个事件。 */
    public static int size(int stock) {
        return table(stock).length;
    }

    /** 取一条定义；下标越界返回 {@code null}（脏存档 / 畸形包不炸）。 */
    public static EventDef get(int stock, int index) {
        if (stock < 0 || stock >= TABLE.length || index < 0 || index >= TABLE[stock].length) {
            return null;
        }
        return TABLE[stock][index];
    }

    /**
     * 按权重抽一条事件（**纯函数**：同一只股票 + 同一个 {@code [0,1)} 随机数永远给同一行）。
     *
     * @return 表内行号（{@code 0 .. size-1}）
     */
    public static int pick(int stock, double roll01) {
        EventDef[] defs = table(stock);
        int total = 0;
        for (EventDef def : defs) {
            total += weightOf(def);
        }
        double target = roll01 * total;
        for (int i = 0; i < defs.length; i++) {
            target -= weightOf(defs[i]);
            if (target < 0.0) {
                return i;
            }
        }
        return defs.length - 1;     // 浮点边界兜底
    }

    /** 某一类型下有几条事件（成簇抽签用）。 */
    public static int countOfType(EventType type) {
        int[][] flat = BY_TYPE.get(type);
        return flat == null ? 0 : flat.length;
    }

    /**
     * 某一类型里的第 {@code index} 条（成簇：再抽一条**同类型**事件）。
     *
     * @return {@code {股票, 行号}}；越界返回 {@code null}
     */
    public static int[] ofType(EventType type, int index) {
        int[][] flat = BY_TYPE.get(type);
        if (flat == null || index < 0 || index >= flat.length) {
            return null;
        }
        return flat[index];
    }

    /**
     * 权重：**由幅度推出**（{@code 9 − 幅度}，幅度 3.5~8 → 权重 1~5.5）。
     *
     * <p>规则一句话说得清：**小事件更常见、大事件更罕见** —— 玩家不会每十几分钟就被 8σ 砸一次，
     * 但「矿工罢工」(3.5σ) 这类小消息会常出现。用公式而不是手写常数，是为了以后加事件时
     * 不用重新配平一整只股票的权重总和。
     */
    public static double weightOf(EventDef def) {
        return Math.max(0.5, 9.0 - def.magnitude());
    }

    /** 这只股票的事件表；下标越界夹到合法范围（脏存档不炸）。 */
    private static EventDef[] table(int stock) {
        return TABLE[StockDefs.clamp(stock, 0, TABLE.length - 1)];
    }

    // ---------- 越界安全的取值（调用点大多是「按行号读一个字段」，不必自己判 null） ----------

    /** 事件 id；越界返回空串。 */
    public static String id(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? "" : def.id();
    }

    /** 展示名的语言键；越界返回空串。 */
    public static String nameKey(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? "" : def.nameKey();
    }

    /** 方向（{@code +1} / {@code -1}）；越界返回 0。 */
    public static int direction(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? 0 : def.direction();
    }

    /** σ 倍率；越界返回 0。 */
    public static double sigmaMultiple(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? 0.0 : def.sigmaMultiple();
    }

    /** 半衰期（跳）；越界返回 0。 */
    public static int halfLife(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? 0 : def.halfLife();
    }

    /** 名义影响（千分比，对**本公司**）；越界返回 0。 */
    public static int permille(int stock, int index) {
        EventDef def = get(stock, index);
        return def == null ? 0 : def.permille(stock);
    }
}
