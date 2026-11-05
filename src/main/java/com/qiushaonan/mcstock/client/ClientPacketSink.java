package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.market.MarketRegime;
import com.qiushaonan.mcstock.market.Position;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.network.S2CShopCatalog;
import net.minecraft.resources.ResourceLocation;
import com.qiushaonan.mcstock.network.S2CSlotValue;
import com.qiushaonan.mcstock.network.S2CStockSnapshot;

/**
 * 客户端收包入口：把 S2C 包拆进 {@link StockClientState}。
 *
 * <p>只做数据搬运，不碰渲染 —— 屏幕每一帧自己从 state 里读。
 * 这些方法只会在客户端执行（服务端不会收到 clientbound 包），所以可以安全引用客户端类。
 */
public final class ClientPacketSink {

    private ClientPacketSink() {
    }

    public static void onSnapshot(S2CStockSnapshot payload) {
        int[][] candles = unpackCandles(payload.candles());
        StockClientState.applySnapshot(
                payload.cash(), payload.netWorth(), payload.secondsToNext(),
                toLong(payload.prices()), payload.changesPermille(), unpackPositions(payload),
                candles,
                payload.indexDeci(), payload.indexChangePermille(),
                MarketRegime.byOrdinal(payload.regime()),
                payload.news() == null ? new S2CStockSnapshot.NewsItem[0] : payload.news(),
                payload.flash());
    }

    public static void onSlotValue(S2CSlotValue payload) {
        StockClientState.recycleTotal = payload.total();
        StockClientState.recycleKinds = payload.kinds();
        // ⚠️ 逐格单价必须一起收：明细的「单价 / 小计」两列靠它。
        //    只用 total/kinds 是算不出单价的（那反推出来的是均价，会把不同物品显示成同一个数）。
        StockClientState.recycleUnitValues = new int[payload.unitValues().size()];
        for (int i = 0; i < StockClientState.recycleUnitValues.length; i++) {
            StockClientState.recycleUnitValues[i] = payload.unitValues().get(i);
        }
    }

    /**
     * 采购目录：**累积分片，收齐才整体替换**（线上事故之后改的，2026-10-06）。
     *
     * <p>7799 件物品要分 8 片；没收齐之前保持旧目录（玩家不会搜到一半看到残缺列表）。
     * 畸形的片由 {@link S2CShopCatalog.Assembler#accept} 直接忽略，绝不抛异常 ——
     * 解码/收包环节抛异常会让玩家**掉线**。
     */
    public static void onCatalog(S2CShopCatalog payload) {
        if (!CATALOG.accept(payload)) {
            return;
        }
        ResourceLocation[] items = CATALOG.takeItems();
        int[] prices = CATALOG.takePrices();
        if (items != null && prices != null) {
            // 两个字段分开赋值：渲染线程最多看到一帧「长度不一致」，而 shopCount() 取两者较短值，
            // 所以不会越界；下一次快照/切片就会自愈。
            StockClientState.shopItems = items;
            StockClientState.shopPrices = prices;
        }
    }

    /** 目录分片累积器（进程级单例：分片会跨多个包陆续到达）。 */
    private static final S2CShopCatalog.Assembler CATALOG = new S2CShopCatalog.Assembler();

    /**
     * 把快照里那五个等长数组拼回仓位对象。
     *
     * <p>长度按 {@link S2CStockSnapshot#posCount()}（五个数组取最短，并夹到上限），
     * 畸形包只会让列表少显示几行，不会抛异常踢连接。
     *
     * <p>杠杆协议里不传（它是开仓那一刻的显示属性），这里从「均价 × 股数 ÷ 保证金」反推 ——
     * 和旧版 K 线下面那行「×杠杆」是同一个口径，见 {@link Position#deriveLeverage}。
     */
    private static Position[] unpackPositions(S2CStockSnapshot payload) {
        int n = payload.posCount();
        Position[] out = new Position[n];
        for (int i = 0; i < n; i++) {
            int shares = payload.posShares()[i];
            long entry = payload.posEntry()[i];
            long margin = payload.posMargin()[i];
            int stock = payload.posStock()[i];
            if (stock < 0 || stock >= StockDefs.COUNT || shares == 0) {
                continue;       // 畸形条目：跳过（位置留 null，下面收口）
            }
            out[i] = new Position(payload.posId()[i], stock, shares, entry, margin,
                    Position.deriveLeverage(shares, entry, margin));
        }
        return compact(out);
    }

    /** 丢掉上面跳过的 {@code null} 条目（正常包一个都不会有）。 */
    private static Position[] compact(Position[] in) {
        int n = 0;
        for (Position p : in) {
            if (p != null) {
                n++;
            }
        }
        if (n == in.length) {
            return in;
        }
        Position[] out = new Position[n];
        int i = 0;
        for (Position p : in) {
            if (p != null) {
                out[i++] = p;
            }
        }
        return out;
    }

    /**
     * 把平铺的阴阳柱拆回 {@code [stock][candle*4+字段]}。
     *
     * <p>长度不齐时按 0 补齐、多出来的截断，**不抛异常** —— 收包时抛异常会直接踢掉连接。
     *
     * <p>⚠️ 返回**空数组**表示「这一包没带 K 线」，屏幕会沿用上一份。判据有两个：
     * <ul>
     *   <li>长度 0：服务端每秒推的轻量快照就是这么发的；</li>
     *   <li>全是 0：解码端会把长度 0 的数组补成 {@code CANDLES_LENGTH} 个 0，
     *       只判 {@code length > 0} 会把这种包当成有效数据、每秒把 K 线冲掉一次
     *       （实机反馈「K 线一直显示正在收集行情」就是这个）。</li>
     * </ul>
     */
    private static int[][] unpackCandles(int[] flat) {
        if (flat == null || flat.length == 0 || isAllZero(flat)) {
            return new int[StockDefs.COUNT][0];
        }
        int perStock = S2CStockSnapshot.CANDLE_MAX * S2CStockSnapshot.CANDLE_FIELDS;
        int[][] out = new int[StockDefs.COUNT][perStock];
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            int from = stock * perStock;
            if (from >= flat.length) {
                continue;
            }
            System.arraycopy(flat, from, out[stock], 0, Math.min(perStock, flat.length - from));
        }
        return out;
    }

    /** 全 0 = 没有有效价格（价格恒 > 0，所以 0 一定是「无数据」的占位）。 */
    private static boolean isAllZero(int[] values) {
        for (int value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static long[] toLong(int[] values) {
        if (values == null) {
            return null;
        }
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }
}
