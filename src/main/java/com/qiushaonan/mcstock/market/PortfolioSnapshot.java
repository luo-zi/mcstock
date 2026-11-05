package com.qiushaonan.mcstock.market;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 持仓的**存盘快照**：仓位列表 + 发号器。纯 Java（只用 DataFixerUpper 的 Codec），
 * 所以能脱离 Minecraft 直接单测 —— 迁移逻辑出错的代价是「读档丢光玩家的仓」，必须测得出来。
 *
 * <p>{@code ModAttachments.PORTFOLIO} 存的就是它（{@code copyOnDeath} 保留）。
 *
 * <h2>两种盘上格式</h2>
 * <pre>
 * 新（本版本起写盘的就是这个）：
 *   { positions: [ {id:1, stock:0, shares:100, entry:10000, margin:200000, lev:5}, ... ],
 *     next_id: 2 }
 * 旧（阶段 3 的「每只股票一个净仓」三元组，只读不写）：
 *   { shares: [100, 0, 0], avg: [10000, 0, 0], margin: [200000, 0, 0] }
 * </pre>
 *
 * <p>⚠️ 旧存档**必须能读进来**：{@link #CODEC} 先按新格式解，解不开（没有 {@code positions} 字段）
 * 再退回旧格式，把每个非空旧仓转成一个新仓（{@link Portfolio#fromLegacy}）。
 * 只按新格式解的话，老玩家一进游戏所有仓位就凭空消失了 —— 这是本次重构最容易出事的地方。
 *
 * <p>写盘只会写新格式（{@code Either.left} 永远是编码路径），不会把旧字段一起写出去：
 * 那些字段是迁移用的输入，不是事实来源，写回去只会让以后的人分不清哪份是新数据。
 */
public record PortfolioSnapshot(List<Position> positions, int nextId) {

    /** 空持仓（附件默认值）。 */
    public static final PortfolioSnapshot EMPTY = new PortfolioSnapshot(List.of(), 1);

    /** 一个仓的盘上形状。 */
    private static final Codec<Position> POSITION_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("id").forGetter(Position::id),
            Codec.INT.fieldOf("stock").forGetter(Position::stock),
            Codec.INT.fieldOf("shares").forGetter(Position::shares),
            Codec.LONG.fieldOf("entry").forGetter(Position::entryCents),
            Codec.LONG.fieldOf("margin").forGetter(Position::marginCents),
            // lev 可缺省：迁移来的仓没有杠杆（0 = 未知），补字段时老存档也不该读挂
            Codec.INT.optionalFieldOf("lev", 0).forGetter(Position::leverage)
    ).apply(inst, Position::new));

    /** 新格式。{@code positions} 是**必填**字段 —— 它缺了就说明这是旧存档，要退回旧格式解。 */
    private static final Codec<PortfolioSnapshot> CURRENT = RecordCodecBuilder.create(inst -> inst.group(
            POSITION_CODEC.listOf().fieldOf("positions").forGetter(PortfolioSnapshot::positions),
            Codec.INT.optionalFieldOf("next_id", 0).forGetter(PortfolioSnapshot::nextId)
    ).apply(inst, PortfolioSnapshot::new));

    /** 旧格式的盘上形状（三张等长表，按股票下标）。 */
    private record Legacy(List<Integer> shares, List<Long> avg, List<Long> margin) {
        static final Codec<Legacy> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.listOf().fieldOf("shares").forGetter(Legacy::shares),
                Codec.LONG.listOf().fieldOf("avg").forGetter(Legacy::avg),
                Codec.LONG.listOf().fieldOf("margin").forGetter(Legacy::margin)
        ).apply(inst, Legacy::new));
    }

    /**
     * 旧格式 → 快照。编码方向只为满足 {@code xmap} 的签名，实际上永远不会被调用
     * （写盘恒走 {@code Either.left} = 新格式）。
     */
    private static final Codec<PortfolioSnapshot> LEGACY = Legacy.CODEC.xmap(
            PortfolioSnapshot::migrate,
            snapshot -> new Legacy(List.of(), List.of(), List.of()));

    /** 对外唯一入口：先新后旧。 */
    public static final Codec<PortfolioSnapshot> CODEC = Codec.either(CURRENT, LEGACY)
            .xmap(either -> either.map(current -> current, migrated -> migrated), Either::left);

    /** 从一份持仓生成快照。 */
    public static PortfolioSnapshot of(Portfolio portfolio) {
        return new PortfolioSnapshot(List.copyOf(portfolio.positions()), portfolio.nextId());
    }

    /** 还原成可操作的持仓（{@link Portfolio#restore} 会做畸形数据收口）。 */
    public Portfolio toPortfolio() {
        Portfolio portfolio = new Portfolio();
        portfolio.restore(positions, nextId);
        return portfolio;
    }

    /** 旧三元组 → 仓位列表。见类注释：读旧存档**不能丢数据**。 */
    private static PortfolioSnapshot migrate(Legacy legacy) {
        int n = Math.max(legacy.shares().size(), Math.max(legacy.avg().size(), legacy.margin().size()));
        int[] shares = new int[n];
        long[] avg = new long[n];
        long[] margin = new long[n];
        for (int i = 0; i < n; i++) {
            shares[i] = i < legacy.shares().size() ? legacy.shares().get(i) : 0;
            avg[i] = i < legacy.avg().size() ? legacy.avg().get(i) : 0L;
            margin[i] = i < legacy.margin().size() ? legacy.margin().get(i) : 0L;
        }
        return of(Portfolio.fromLegacy(shares, avg, margin));
    }
}
