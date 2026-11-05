package com.qiushaonan.mcstock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个玩家的持仓：**一串互相独立的仓位**（纯数据 + 纯计算，不依赖 Minecraft）。
 *
 * <p>这里是本次重构的核心改动：以前是「每只股票一个净仓位」（三个定长数组），
 * 现在是一个 {@link Position} 列表 —— 玩家可以持有 0..{@link #MAX_POSITIONS} 个仓，
 * 同一只股票上多仓、空仓可以同时存在，**任何一仓的均价、保证金、爆仓价都只由它自己决定**。
 *
 * <p>净值口径不变：{@code 现金 + Σ(各仓保证金 + 各仓浮盈)}，见 {@link #totalMargin()} /
 * {@link #totalPnl(long[])}。
 *
 * <p>⚠️ 唯一性：{@code id} 在**玩家内**唯一，由 {@link #nextId} 单调递增发号；读档时把发号器
 * 一起恢复（{@link #restore}），否则重进游戏后新开的仓会和老仓撞 id、平仓平错人。
 */
public final class Portfolio {

    /**
     * 一个玩家最多同时持有多少个仓。
     *
     * <p>它同时是三处的硬上限：开仓被拒（{@code TradeService.Status.FULL}）、
     * 快照包最多带这么多条（{@link com.qiushaonan.mcstock.network.S2CStockSnapshot}）、
     * 读档时多余的仓被截断。取这个数是因为**客户端持仓列表要能平掉每一个仓** ——
     * 服务端悄悄多留几个「看不见也平不掉」的仓比直接拒绝开仓更糟。
     */
    public static final int MAX_POSITIONS = 32;

    private final List<Position> positions = new ArrayList<>();
    private int nextId = 1;

    /** 全部仓位（只读视图，顺序 = 建仓顺序）。 */
    public List<Position> positions() {
        return Collections.unmodifiableList(positions);
    }

    public int size() {
        return positions.size();
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    /** 下一个要发的 id（存盘用）。 */
    public int nextId() {
        return nextId;
    }

    /** 按 id 找仓位，找不到返回 {@code null}。 */
    public Position byId(int id) {
        for (Position p : positions) {
            if (p.id() == id) {
                return p;
            }
        }
        return null;
    }

    /** 这只股票上有几个仓。 */
    public int countFor(int stock) {
        int n = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                n++;
            }
        }
        return n;
    }

    /** 这只股票上所有仓的股数合计（有符号：多空会互相抵消，仅用于显示）。 */
    public int sharesFor(int stock) {
        int sum = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                sum += p.shares();
            }
        }
        return sum;
    }

    /** 这只股票上的全部仓位（**拷贝**：调用方要边遍历边平仓，不能拿活视图去改）。 */
    public List<Position> forStock(int stock) {
        List<Position> out = new ArrayList<>();
        for (Position p : positions) {
            if (p.stock() == stock) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 开一个新仓（调用方负责先扣掉现金，见 {@code TradeService.open}）。
     *
     * @param shares 有符号股数：正 = 多、负 = 空
     * @return 新仓的 id
     */
    public int addPosition(int stock, int shares, long entryCents, long marginCents, int leverage) {
        int id = nextId++;
        positions.add(new Position(id, stock, shares, entryCents, marginCents, leverage));
        return id;
    }

    /** 移除一个仓（平仓 / 爆仓），返回被移除的那个仓；id 不存在返回 {@code null}。 */
    public Position removePosition(int id) {
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).id() == id) {
                return positions.remove(i);
            }
        }
        return null;
    }

    /** 全部仓位占用的保证金合计（分）。 */
    public long totalMargin() {
        long sum = 0;
        for (Position p : positions) {
            sum += p.marginCents();
        }
        return sum;
    }

    /** 全部仓位的浮动盈亏合计（分）。{@code prices} 按股票下标取现价。 */
    public long totalPnl(long[] prices) {
        long sum = 0;
        for (Position p : positions) {
            long price = p.stock() >= 0 && p.stock() < prices.length ? prices[p.stock()] : 0L;
            sum += p.pnl(price);
        }
        return sum;
    }

    /** 这只股票上所有仓的浮盈合计（分）。 */
    public long pnlFor(int stock, long priceCents) {
        long sum = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                sum += p.pnl(priceCents);
            }
        }
        return sum;
    }

    /**
     * 从存档恢复：整份替换。
     *
     * <p>**防御性收口**（存档是外部输入，畸形数据不该把玩法搞崩）：
     * <ol>
     *   <li>丢弃股数为 0（空仓不该存在）、股票下标越界的条目；</li>
     *   <li>id 重复的只留第一个（否则「平这个仓」会平错）；</li>
     *   <li>最多留 {@link #MAX_POSITIONS} 个；</li>
     *   <li>发号器至少是「最大 id + 1」，免得新仓和老仓撞 id。</li>
     * </ol>
     */
    public void restore(List<Position> loaded, int nextIdValue) {
        positions.clear();
        nextId = Math.max(1, nextIdValue);
        if (loaded == null) {
            return;
        }
        for (Position p : loaded) {
            if (p == null || p.shares() == 0 || p.stock() < 0 || p.stock() >= StockDefs.COUNT) {
                continue;
            }
            if (positions.size() >= MAX_POSITIONS) {
                break;
            }
            if (byId(p.id()) != null) {
                continue;
            }
            positions.add(p);
            nextId = Math.max(nextId, p.id() + 1);
        }
    }

    /**
     * 旧存档迁移：把「每只股票一个净仓」的三元组转成**仓位列表**。
     *
     * <p>旧格式里一只股票只有一个净仓（加仓是合并均价、反向下单先平仓），所以每个非空旧仓
     * 正好对应一个新仓；{@code id} 按股票下标顺序从 1 递增，{@code leverage} 记 0（未知，
     * 界面上由 {@link Position#deriveLeverage} 从「均价 / 保证金」反推）。
     *
     * <p>纯函数、不碰 Minecraft：读了旧存档不能把玩家的钱和仓位丢了（见 {@link PortfolioSnapshot}）。
     */
    public static Portfolio fromLegacy(int[] shares, long[] avgEntry, long[] margin) {
        Portfolio p = new Portfolio();
        int n = Math.max(shares == null ? 0 : shares.length,
                Math.max(avgEntry == null ? 0 : avgEntry.length,
                        margin == null ? 0 : margin.length));
        for (int stock = 0; stock < n && stock < StockDefs.COUNT; stock++) {
            int s = shares != null && stock < shares.length ? shares[stock] : 0;
            if (s == 0) {
                continue;
            }
            long avg = avgEntry != null && stock < avgEntry.length ? avgEntry[stock] : 0L;
            long m = margin != null && stock < margin.length ? margin[stock] : 0L;
            p.addPosition(stock, s, avg, m, 0);
        }
        return p;
    }
}
