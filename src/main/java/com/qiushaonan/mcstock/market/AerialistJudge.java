package com.qiushaonan.mcstock.market;

/**
 * **「空中飞人」成就的判定**（纯函数，可单测，不碰 Minecraft）。
 *
 * <p>玩家口径原话：
 * <blockquote>添加在负债情况下，一百格摔落下来致死后触发成就（空中飞人），并且清除债务</blockquote>
 *
 * <p>三个条件**同时**成立才算：
 * <ol>
 *   <li>负债：{@code cash < 0}（0 分不算 —— 那是「刚好没钱」，不是欠钱）；</li>
 *   <li>摔死：由 {@link #isAerialist} 的 {@code fellToDeath} 参数表达（事件侧还会再核一次
 *       死因是不是 {@code minecraft:fall}）；</li>
 *   <li>摔落高度 ≥ {@link #MIN_FALL_DISTANCE} 格（**刚好 100.0 算达标**，所以用 {@code >=}）。</li>
 * </ol>
 *
 * <p>⚠️ 高度必须是**伤害结算前**记下来的那份（{@code LivingFallEvent#getDistance()}）：
 * 死亡事件里玩家的 {@code fallDistance} 往往已经被重置成 0，直接读会永远不达标。
 * 记录与取用见 {@link AerialistService}。
 */
public final class AerialistJudge {

    /** 摔落高度门槛（格）：≥ 它才算「百格」。边界值 100.0 算达标。 */
    public static final double MIN_FALL_DISTANCE = 100.0;

    private AerialistJudge() {
    }

    /**
     * 够不够「空中飞人」。
     *
     * @param cashCents    玩家当前现金（分）：**负数是负债**，0 与正数都不算
     * @param fallDistance 摔落高度（格），必须 ≥ {@link #MIN_FALL_DISTANCE}
     * @param fellToDeath  这一次确实摔死了吗（死因是摔落伤害）
     * @return 三个条件同时成立才返回 {@code true}
     */
    public static boolean isAerialist(long cashCents, double fallDistance, boolean fellToDeath) {
        if (!fellToDeath) {
            return false;
        }
        if (cashCents >= 0L) {
            return false;       // 不欠钱就不算：0 分也不行
        }
        // ⚠️ NaN 走这里会得到 false（NaN 的任何比较都是 false），这正是我们要的：脏数据不触发
        return fallDistance >= MIN_FALL_DISTANCE;
    }
}
