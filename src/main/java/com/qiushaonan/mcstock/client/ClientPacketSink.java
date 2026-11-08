package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.market.ConditionalBook;
import com.qiushaonan.mcstock.market.ConditionalOrder;
import com.qiushaonan.mcstock.market.MarketRegime;
import com.qiushaonan.mcstock.market.Position;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.network.S2CConditionalOrders;
import com.qiushaonan.mcstock.network.S2CShopCatalog;
import net.minecraft.resources.ResourceLocation;
import com.qiushaonan.mcstock.network.S2CSlotValue;
import com.qiushaonan.mcstock.network.S2CStockSnapshot;
import com.qiushaonan.mcstock.network.S2CUnlocked;

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

    /**
     * 条件单簿：**整份替换**（协议 v5）。
     *
     * <p>⚠️ 收包侧一律不抛：解码异常 / 畸形条目在客户端 = 直接掉线。这里把序号交给
     * {@link ConditionalOrder#direction(int)} / {@link ConditionalOrder#action(int)} 兜底，越界股票与超过上限
     * 的条目直接跳过，界面最多少显示几行。
     */
    public static void onConditionalOrders(S2CConditionalOrders payload) {
        S2CConditionalOrders.View[] src = payload.orders() == null
                ? new S2CConditionalOrders.View[0] : payload.orders();
        java.util.List<ConditionalOrder> out = new java.util.ArrayList<>(src.length);
        for (S2CConditionalOrders.View v : src) {
            if (v == null || v.stock() < 0 || v.stock() >= StockDefs.COUNT
                    || out.size() >= ConditionalBook.MAX_ORDERS) {
                continue;
            }
            out.add(new ConditionalOrder(v.id(), v.stock(),
                    ConditionalOrder.direction(v.direction()), v.triggerCents(),
                    ConditionalOrder.action(v.action()), v.qty(), v.leverage()));
        }
        StockClientState.conditionalOrders = out.toArray(new ConditionalOrder[0]);
    }

    /**
     * 已解锁物品表 + 游戏时间 + 进度档：**整份替换**（协议 v6 / v7 / v8）。
     *
     * <p>只做数据搬运：畸形条目（null / id 解析失败已经在解码侧丢掉）不会再进到这里。
     * 客户端不自己判断「这物品值不值得上锁」—— 那是服务端 {@code UnlockRules} 的事，
     * 这里只保留「解锁过哪些」「玩了多久」「每一档第几小时开放」。
     *
     * <p>⚠️ 三样必须一起换（{@code unlockVersion} 自增）：解锁页的预演是**缓存**的
     * （目录 7 千条，不能每 tick 重算），要有一个东西告诉它「输入变了，重算」。
     */
    public static void onUnlocked(S2CUnlocked payload) {
        java.util.Set<ResourceLocation> set = new java.util.HashSet<>();
        if (payload.ids() != null) {
            for (ResourceLocation id : payload.ids()) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        java.util.Map<ResourceLocation, Integer> stages = new java.util.HashMap<>();
        if (payload.stages() != null) {
            for (java.util.Map.Entry<ResourceLocation, Integer> entry : payload.stages().entrySet()) {
                // 只收 > 0 的条目：0 的语义是「没写阶段」（回落到按价格算），存进去反而要处处判空
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    stages.put(entry.getKey(), entry.getValue());
                }
            }
        }
        StockClientState.unlocked = java.util.Set.copyOf(set);
        // 负数是畸形包：夹到 0（= 只走「交一组」那条路），别让界面算出「已经等够了」
        StockClientState.playSeconds = Math.max(0, payload.playSeconds());
        StockClientState.unlockStages = java.util.Map.copyOf(stages);
        StockClientState.unlockVersion++;
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
