package com.qiushaonan.mcstock.market;

import java.util.Random;

/**
 * **大盘状态机**（市场环境层）：平静 / 牛市 / 熊市 / 高波动。纯 Java，单测直接算。
 *
 * <p>三只股票等权合成一个大盘（{@link MarketEngine#indexDeci()}）。大盘状态决定两件事：
 * <ol>
 *   <li><b>基础漂移</b>：{@code drift += bias × β[stock]}（{@link StockDefs#BETA}）——
 *       牛市里所有股票都更容易涨，而且越激进（β 越大）的股票涨得越多；</li>
 *   <li><b>波动倍率</b>：{@code σ × volMultiplier} —— 高波动期柱子明显变粗，爆仓更容易
 *       （玩家能直接在图上看出来，这是「真实感」的主要来源）。</li>
 * </ol>
 *
 * <h2>持续时间口径（⚠️ 与需求原文的一处出入，以「分钟」为准）</h2>
 * <p>需求写的是「单次状态持续约 2~10 分钟（600~3000 tick）」。这两组数字互相矛盾：
 * 1 跳 = {@link StockDefs#PRICE_TICK_TICKS} tick = 2 秒，所以 2~10 分钟 = 2400~12000 tick，
 * 而 600~3000 tick 只有 30 秒 ~ 2.5 分钟。**按分钟那一条实现**（它才是设计意图：
 * 玩家一局 30 分钟能碰上 3~8 次状态切换），并把区间写成 {@link #minJumps()} / {@link #maxJumps()}（跳），
 * 单位换算写在注释里，免得以后再对着两套数字猜。
 *
 * <p>状态不是「每跳独立抽签」（那样持续时长会是指数分布，短到几秒、长到十几分钟），
 * 而是**进入状态时抽一个持续跳数**，到点再按 {@link #TRANSITIONS 转移表}抽下一个状态。
 * 这样「持续 2~10 分钟」是一条硬保证，单测可以直接断言。
 */
public enum MarketRegime {

    /** 平静：基准状态，没有额外漂移、波动不放大。持续 2~6 分钟。 */
    CALM("calm", 0.0, 1.0, 60, 180),
    /**
     * 「温和」（内部名 BULL）：基础漂移 **+0.08%/跳**（β=1 时），长期把价格托到基准价上方约 3%。
     *
     * <p>⚠️ 历史：+0.12% → +0.06%（2026-10-06 第一次收紧）→ **+0.08%（同日回调：
     * 玩家反馈「赚钱太难了」）**。回调后仍然是「温和期略强于起伏期」的偏空口径：
     * 一问一答（温和 + 起伏）的净漂移是 +0.08 −0.12 = **−0.04%/跳**，大盘自己仍在慢慢抽水，
     * 但不再是一开局就长熊。别调回 +0.12%（A 策略会转正），也别压到 0。
     */
    BULL("bull", 0.0008, 1.0, 60, 300),
    /**
     * 「起伏」（内部名 BEAR）：基础漂移 **−0.12%/跳**，波动略放大（恐慌盘）。持续 2~8 分钟。
     *
     * <p>⚠️ 历史：−0.10% → −0.16%（2026-10-06 第一次收紧）→ **−0.12%（同日回调）**。
     * 与 +0.08% 配对之后，一个「温和期 + 起伏期」来回的净漂移仍是负的
     * （+0.08 − 0.12 = **−0.04%/跳**）—— 轻微偏空即可，不必把熊市做成常态。
     */
    BEAR("bear", -0.0012, 1.15, 60, 240),
    /** 动荡（内部名 VOLATILE）：波动 ×2.0（柱子变粗、爆仓变密），漂移略偏负。持续 1~4 分钟。 */
    VOLATILE("volatile", -0.0004, 2.0, 30, 120);

    /**
     * 状态转移表：{@code TRANSITIONS[from.ordinal()][to.ordinal()]}，**每行和 = 1**。
     *
     * <p>数值上的取舍：对角线都在 0.30~0.45，也就是「状态有惯性但不粘住」——
     * 平静之后最可能还是平静（0.45），但出现趋势（牛/熊）的概率合计 0.45；
     * 高波动之后回到平静的概率最高（0.40）。牛市里出现熊市的概率压到 0.10
     * （趋势反转是少数事件，符合「牛市里回调但不掉头」的观感）。
     */
    private static final double[][] TRANSITIONS = {
            //          CALM  BULL  BEAR  VOLATILE
            /* CALM */ {0.45, 0.25, 0.20, 0.10},
            /* BULL */ {0.40, 0.30, 0.10, 0.20},
            /* BEAR */ {0.40, 0.10, 0.30, 0.20},
            /* VOLA */ {0.40, 0.20, 0.20, 0.20},
    };

    static {
        // ⚠️ 转移表自检：行和必须是 1（差一点点就会让「抽不到状态」变成索引越界）。
        //    单测也会查一遍，这里再查一次是为了让「手抖改错一位小数」在类加载时就炸。
        for (double[] row : TRANSITIONS) {
            double sum = 0.0;
            for (double p : row) {
                sum += p;
            }
            if (Math.abs(sum - 1.0) > 1e-9) {
                throw new IllegalStateException("大盘状态转移表的行和必须是 1，实际 " + sum);
            }
        }
    }

    private final String id;
    private final double bias;
    private final double volMultiplier;
    private final int minJumps;
    private final int maxJumps;

    MarketRegime(String id, double bias, double volMultiplier, int minJumps, int maxJumps) {
        this.id = id;
        this.bias = bias;
        this.volMultiplier = volMultiplier;
        this.minJumps = minJumps;
        this.maxJumps = maxJumps;
    }

    public String id() {
        return id;
    }

    /**
     * 语言键：{@code gui.mcstock.regime.<id>}。
     *
     * <p>⚠️ 界面上出现的词**刻意是中性氛围词**（平静 / 温和 / 起伏 / 动荡），不是「牛市 / 熊市」：
     * 玩家原话「不要直接写涨幅和熊市之类的，玩家看到会直接买或卖。我的初衷是让人远离赌博」——
     * 机制照旧（bias 该正就正、该负就负），但**不许把方向印在界面上**。
     */
    public String nameKey() {
        return "gui.mcstock.regime." + id;
    }

    /**
     * 状态播报用的**中性叙述句**语言键：{@code gui.mcstock.regime.<id>.news}，
     * 例如「市场交投转趋温和」。它描述的是**交投气氛**（活跃度 / 波动），不含涨跌方向，
     * 也不含「该买该卖」的暗示 —— 同一条口径见 {@link #nameKey()}。
     */
    public String newsKey() {
        return "gui.mcstock.regime." + id + ".news";
    }

    /** 每跳的基础漂移（还要乘各股票的 β）。 */
    public double bias() {
        return bias;
    }

    /** σ 的倍率（1.0 = 不放大）。 */
    public double volMultiplier() {
        return volMultiplier;
    }

    /** 最短 / 最长持续跳数（1 跳 = 2 秒）。 */
    public int minJumps() {
        return minJumps;
    }

    public int maxJumps() {
        return maxJumps;
    }

    /** 抽一个持续跳数（含两端）。**唯一实现**：自然切换与读档重放都走它。 */
    public int drawDuration(Random random) {
        return minJumps + random.nextInt(maxJumps - minJumps + 1);
    }

    /** 按转移表抽下一个状态（纯函数：同一个 {@code roll01} 永远给同一个结果）。 */
    public MarketRegime next(double roll01) {
        double target = roll01;
        double[] row = TRANSITIONS[ordinal()];
        for (int i = 0; i < row.length; i++) {
            target -= row[i];
            if (target < 0.0) {
                return values()[i];
            }
        }
        return CALM;    // 浮点边界兜底
    }

    /** 序号 → 枚举；越界退回 {@link #CALM}（脏存档/畸形包不抛异常）。 */
    public static MarketRegime byOrdinal(int ordinal) {
        MarketRegime[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CALM;
    }

    /** 转移表的一行（只读，单测用来验证「行和 = 1」）。 */
    public static double[] transitionRow(MarketRegime from) {
        return TRANSITIONS[from.ordinal()].clone();
    }
}
