package com.qiushaonan.mcstock.market;

/**
 * **一条正在生效的冲击**（事件对**某一只股票**的影响）。纯 Java，衰减曲线可以直接单测。
 *
 * <h2>为什么不是「一次性跳变」，也不是「纯衰减拖尾」</h2>
 * <p>第一版的事件是「命中就把 drift 加一大坨」，价格当场跳一下、下一跳就当没发生过 ——
 * 看起来像闪断，不像新闻。真实市场里消息是**先急动、再被慢慢消化**的，所以这一版给每条冲击
 * 一个生命周期：{@link #STEP_SHARE} 的部分在触发那一跳**当场跳变**（急动），
 * 剩下的部分按半衰期**指数衰减地摊到后面若干跳**（消化）：
 *
 * <pre>
 *   age = 0 ：drift += direction × magnitude × σ × STEP_SHARE                  ← 急动（只这一跳）
 *   每一跳  ：drift += direction × magnitude × σ × (1 − STEP_SHARE)
 *                       × (ln2 / halfLife) × 2^(−age / halfLife)               ← 拖尾（积分 = 剩下的部分）
 *   σ       ：×= 1 + (sigmaMultiple − 1) × 2^(−age / halfLife)                 ← 波动放大，平滑回落
 * </pre>
 *
 * <p>于是**整条冲击的累计影响恰好等于 {@code magnitude × σ}**（也就是事件表里写的 3~8σ），
 * 而 {@code halfLife} 跳之后拖尾只剩一半 —— 半衰期的定义就是「过一半」，
 * 所以用 2 的幂而不是 e 的幂（{@code e^(−age/halfLife)} 在 age = halfLife 时是 36.8%，
 * 那是时间常数，不是半衰期）。单测直接断言「halfLife 跳后正好是 50%」。
 *
 * <p>⚠️⚠️ **不能把 3~8σ 直接当成「每跳的 drift」**（照字面写就是这样）：衰减拖尾的积分是
 * {@code magnitude × σ × halfLife / ln2} —— halfLife = 20~60 时是 **29~87 倍**，
 * 一条 5σ 的新闻就会累计推高 145~433 个 σ，一分钟内顶到价格上限（基准价 500%）并钉住。
 * 所以「3~8σ」只能是**整条事件的累计影响**；要同时做到「看得见的急动」，
 * 就得把它拆成「当场跳变 + 衰减拖尾」两部分（70% / 30%）。
 *
 * <p>存活 {@link #lifeJumps()} = 3 × 半衰期（拖尾衰减到 12.5%），之后引擎会把它清掉。
 *
 * @param stock        受影响的股票下标
 * @param eventId      来源事件 id（调试 / 去重用）
 * @param direction    {@code +1} 利好 / {@code -1} 利空（外溢权重为负时这里已经翻过号）
 * @param startJump    从第几跳开始生效（触发那一跳就算 age = 0，当场跳变）
 * @param halfLife     半衰期（跳）：多少跳之后拖尾剩一半
 * @param magnitude    这条冲击的**累计**幅度（**该股票自己的 σ 倍数**，已乘上外溢权重；恒 ≥ 0）
 * @param sigmaMultiple 存续期内 σ 的放大倍率（起手值，之后指数回落到 1.0）
 * @param clarification 这是不是「传闻的澄清」那一发（反向冲击）
 */
public record MarketShock(int stock, String eventId, int direction, long startJump, int halfLife,
                          double magnitude, double sigmaMultiple, boolean clarification) {

    /**
     * 一条冲击里「当场跳变」占的比例（其余按半衰期慢慢消化）。
     *
     * <p>取 0.7：跳变部分要够大才看得见（3~8σ × 0.7 = 2.1~5.6σ 的单跳位移），
     * 拖尾部分要够长才有「消化」的观感（剩下 30% 摊在 3 个半衰期上）。
     * 调它只改「急动 / 消化」的比例，**不改整条事件的累计影响**（那由 magnitude 决定）。
     */
    public static final double STEP_SHARE = 0.70;

    /** 拖尾衰减到「可以忽略」所需的跳数：3 个半衰期（2⁻³ = 12.5%）。 */
    public int lifeJumps() {
        return halfLife * 3;
    }

    /** 已经过去多少跳（触发那一跳是 0）。 */
    public int ageJumps(long jump) {
        return (int) Math.max(0L, jump - startJump);
    }

    /**
     * 衰减系数 = {@code 2^(−age/halfLife)}。
     *
     * <p>⚠️ 用 2 的幂而不是 {@code e} 的幂：**半衰期**的定义就是「过一半」。
     */
    public double decay(long jump) {
        return Math.pow(2.0, -(double) ageJumps(jump) / halfLife);
    }

    /** 这条冲击还在生效吗（没过完 3 个半衰期）。 */
    public boolean alive(long jump) {
        return ageJumps(jump) < lifeJumps();
    }

    /**
     * 拖尾的**归一化系数**：{@code 1 − 2^(−1/halfLife)}。
     *
     * <p>⚠️ 用离散求和的系数，而不是连续积分的 {@code ln2 / halfLife}：
     * 拖尾是**一跳一次**的离散贡献，等比级数 {@code Σ r^k = 1/(1−r)}（{@code r = 2^(−1/T)}），
     * 所以系数必须是 {@code 1 − r}。用 {@code ln2/T} 会让累计影响比表里写的大约 0.9%
     * （半衰期越长差得越多），单测 {@code totalImpactEqualsTheDeclaredMagnitude} 会当场抓到。
     */
    public double tailNorm() {
        return 1.0 - Math.pow(2.0, -1.0 / halfLife);
    }

    /** 触发那一跳的**跳变**贡献（σ 单位，含符号）：只有 age = 0 那一跳非 0。 */
    public double stepUnits(long jump) {
        return ageJumps(jump) == 0 ? direction * magnitude * STEP_SHARE : 0.0;
    }

    /**
     * **拖尾**的每跳贡献（σ 单位，含符号）。
     *
     * <p>系数见 {@link #tailNorm()}：拖尾对 age 的**求和**恰好等于
     * {@code direction × magnitude × (1 − STEP_SHARE)}，与 {@link #stepUnits} 相加
     * 正好是整条事件的累计幅度（3~8σ）。
     */
    public double tailUnits(long jump) {
        return direction * magnitude * (1.0 - STEP_SHARE) * tailNorm() * decay(jump);
    }

    /** 这一跳对 drift 的总贡献（σ 单位，含符号）= 跳变 + 拖尾。 */
    public double driftUnits(long jump) {
        return stepUnits(jump) + tailUnits(jump);
    }

    /** 这一跳 σ 的放大倍率：从 {@code sigmaMultiple} 指数回落到 1.0。 */
    public double sigmaScale(long jump) {
        return 1.0 + (sigmaMultiple - 1.0) * decay(jump);
    }
}
