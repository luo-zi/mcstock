package com.qiushaonan.mcstock.client;

import com.qiushaonan.mcstock.market.ConditionalOrder;
import com.qiushaonan.mcstock.market.MarketRegime;
import com.qiushaonan.mcstock.market.Position;
import com.qiushaonan.mcstock.market.StockDefs;
import com.qiushaonan.mcstock.network.S2CStockSnapshot;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 客户端的行情镜像：服务端每跳价推一次快照，这里存下来给 GUI 画。
 *
 * <p>纯数据 + 基本换算，**不 import 任何渲染类**：这样 {@code StockScreen} 只负责画，
 * 拆包（{@code ClientPacketSink}）只负责写，互不干扰。
 *
 * <p>金额单位一律**分**；要显示成金币时用 {@link #coins(long)}。
 *
 * <p>持仓是**一串互相独立的仓**（{@link Position}）：服务端推的是「每仓一个数」的五个数组，
 * 这里存成对象数组，K 线 / 资金情况 / 持仓列表都从它读。浮盈与爆仓价**客户端自己算**
 * （{@link Position#pnl(long)} / {@link Position#liquidationPrice()}，两边共用同一份
 * {@code PositionMath} 纯函数）。
 */
public final class StockClientState {

    private StockClientState() {
    }

    /** 快照包里的阴阳柱根数上限（与服务端 StockDefs.CANDLE_MAX 对齐）。 */
    public static final int CANDLE_MAX = StockDefs.CANDLE_MAX;

    public static long cash = 0L;
    public static long netWorth = 0L;
    public static int secondsToNext = 0;
    /** 现价（分）。 */
    public static final long[] prices = new long[StockDefs.COUNT];
    /** 相对上一次跳价的涨跌（千分比，用于显示百分比）。 */
    public static final int[] changesPermille = new int[StockDefs.COUNT];
    /**
     * 玩家当前的全部仓位（每帧一份新的，服务端推什么就是什么）。
     *
     * <p>⚠️ **整份替换，不要就地改**：{@code StockScreen} 靠「数组引用变了没有」判断
     * 缓存要不要重算（一帧要读好几次，不该每次都重新排序一遍）。
     */
    public static Position[] positions = new Position[0];
    /** 阴阳柱：[stock][candle * 4 + (开/高/低/收)]，价格单位分。 */
    public static int[][] candles = new int[StockDefs.COUNT][0];

    /** 当前选中的股票（点击左列卡片切换）。 */
    public static int selected = 0;
    /** 下单数量（股）。 */
    public static int qty = 1;
    /** 杠杆倍数。 */
    public static int leverage = 1;
    /** 回收栏合计（分）与种类数，由 S2C 槽位包推送。 */
    public static long recycleTotal = 0L;
    public static int recycleKinds = 0;

    /**
     * 已解锁的物品 id（服务端推什么就是什么，**整份替换**）。
     *
     * <p>客户端只用它做一件事：在购买页把「还没解锁」的贵物品置灰。
     * 判定口径必须和服务端一致（{@code UnlockRules#requiresUnlock}）：
     * <b>只有「推导价 ≥ 500 金币」的物品才上锁</b>，原版种子物品永远不上锁 ——
     * 所以这里**不需要**知道哪个物品有种子价：客户端只要判断集合里有没有它即可，
     * 「有没有种子价」这个判断只在服务端做（客户端拿不到 {@code ItemValuator} 的来源表）。
     */
    public static java.util.Set<ResourceLocation> unlocked = java.util.Set.of();

    /** 这件物品解锁了吗（只对「需要上锁」的物品有意义，见 {@link #unlocked} 的注释）。 */
    public static boolean isUnlocked(ResourceLocation id) {
        return id != null && unlocked.contains(id);
    }
    /**
     * 回收栏**逐格**的单件回收价（**分**，索引 = 回收栏槽位 0..17，空格为 0）。
     *
     * <p>⚠️ 这是明细「单价 / 小计」两列的**唯一正确来源**。
     * 曾经用 {@code recycleTotal / 总件数} 反推均价当单价 —— 那是**均价不是单价**：
     * 三种不同物品只要件数相同就会显示出完全一样的单价与小计（真机 bug）。
     * 现在按格取价、按物品把同种的格累加，每一行才是它自己的数。
     */
    public static int[] recycleUnitValues = new int[0];
    /** 服务端推来的一行提示（action bar 用），空字符串表示没有新提示。 */
    public static String flash = "";

    // ==================== 大盘与新闻（协议 v3） ====================

    /** 大盘指数 × 10（{@code 10000} = 1000.0）。 */
    public static int indexDeci = 10_000;
    /** 大盘涨跌（千分比，相对上一次跳价）。 */
    public static int indexChangePermille = 0;
    /** 大盘状态（{@code MarketRegime} 序号；客户端用同一个枚举取语言键与颜色）。 */
    public static MarketRegime regime = MarketRegime.CALM;
    /** 新闻流：**最新在最前**（最多 {@code StockDefs.NEWS_KEEP} 条）。整份替换。 */
    public static S2CStockSnapshot.NewsItem[] news = new S2CStockSnapshot.NewsItem[0];

    /**
     * 玩家挂着的全部条件单（协议 v5，由 {@code S2CConditionalOrders} **整份替换**）。
     *
     * <p>存的是 {@link ConditionalOrder} 而非网络包里的 View：那个 record 是两端共用的纯 Java 类，
     * 触发判定、语言键、夹取都在它身上，GUI 直接拿来画，不必再维护第二份「客户端订单」形状。
     * 客户端**只显示、只发撤单 id**，不在这里做任何触发判定（服务端权威）。
     */
    public static ConditionalOrder[] conditionalOrders = new ConditionalOrder[0];
    /**
     * 收到**这一份**快照的时刻（{@code System.currentTimeMillis()}）。
     *
     * <p>新闻的年龄 = 服务端给的 {@code ageTicks} + 「从收到这份快照到现在过了多少毫秒 ÷ 50」。
     * 快照每秒来一份，所以两次快照之间的年龄是平滑累加的，不会一跳一跳。
     * （只有「读时钟」用挂钟；滚动相位用 tick 累加，见 {@code StockScreen#tickerPhase}。）
     */
    public static long snapshotAtMs = 0L;

    // ==================== 持仓查询（GUI 用） ====================

    /** 玩家一共有几个仓。 */
    public static int positionCount() {
        return positions.length;
    }

    /** 这只股票上有几个仓。 */
    public static int positionCount(int stock) {
        int n = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                n++;
            }
        }
        return n;
    }

    /** 这只股票上所有仓的股数合计（有符号：多空互相抵消，只用于显示）。 */
    public static int netShares(int stock) {
        int sum = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                sum += p.shares();
            }
        }
        return sum;
    }

    /** 这只股票上所有仓的保证金合计（分）。 */
    public static long stockMargin(int stock) {
        long sum = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                sum += p.marginCents();
            }
        }
        return sum;
    }

    /** 这只股票上所有仓的浮动盈亏合计（分）。 */
    public static long stockPnl(int stock) {
        long price = priceOf(stock);
        long sum = 0;
        for (Position p : positions) {
            if (p.stock() == stock) {
                sum += p.pnl(price);
            }
        }
        return sum;
    }

    /**
     * 这只股票上**离爆仓最近**（现价与爆仓价距离最小）的那个仓 —— 走势图上那条爆仓虚线与
     * 「爆仓」那一行都画它。没有持仓返回 {@code null}。
     *
     * <p>为什么挑「最近」的：同一只股票可以挂好几个仓（杠杆不同、爆仓价差很远），
     * 玩家最需要知道的是**哪一个先爆**。画平均或随便挑一个都会误导人。
     */
    public static Position nearestLiquidation(int stock) {
        long price = priceOf(stock);
        Position best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Position p : positions) {
            if (p.stock() != stock || p.marginCents() <= 0) {
                continue;
            }
            long distance = Math.abs(p.liquidationPrice() - price);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p;
            }
        }
        return best;
    }

    /** 按「股票下标 → 仓 id」排好序的一份拷贝（持仓列表的显示顺序，稳定不跳）。 */
    public static List<Position> sortedPositions() {
        List<Position> out = new ArrayList<>(positions.length);
        for (Position p : positions) {
            out.add(p);
        }
        out.sort(Comparator.comparingInt(Position::stock).thenComparingInt(Position::id));
        return out;
    }

    /** 某只股票的现价（分）。 */
    public static long priceOf(int stock) {
        return stock >= 0 && stock < prices.length ? prices[stock] : 0L;
    }

    // ==================== 采购目录（购买页用） ====================

    /**
     * 可购买物品的注册名，**顺序就是显示顺序**（服务端已经按价格降序排好）。
     *
     * <p>由 {@code S2CShopCatalog} 的解包入口写入 —— {@code ClientPacketSink.onCatalog(payload)}
     * 里就是这两行：
     * <pre>{@code
     * StockClientState.shopItems  = payload.items();
     * StockClientState.shopPrices = payload.prices();   // 单位：金币（整数）
     * }</pre>
     *
     * <p>⚠️ 这两个字段**故意是 public 的数组**（和上面那堆快照字段一个风格）：目录是一整帧推过来的，
     * 拷贝一份只是白白多一次分配。购买页的过滤/翻页都在客户端做，所以这里必须留着**整份**目录，
     * 不能只留当前页。
     *
     * <p>单位说明：{@code shopPrices} 是**金币**（{@code S2CSlotValue} / {@code StockService} 的口径），
     * 而行情那边一律是**分**；{@link StockScreen} 在用它之前会 ×100 转成分，
     * 免得两套单位混着算出「便宜一百倍」的显示。
     */
    public static ResourceLocation[] shopItems = new ResourceLocation[0];
    /** 与 {@link #shopItems} 一一对应的**单价（金币）**。 */
    public static int[] shopPrices = new int[0];

    /** 目录里有几条（两个数组取短的，避免畸形包把下标读飞）。 */
    public static int shopCount() {
        return Math.min(shopItems.length, shopPrices.length);
    }

    /** 分 → 金币（四舍五入到整数，显示用）。 */
    public static long coins(long cents) {
        return Math.round(cents / 100.0);
    }

    /** 分 → 带两位小数的价格字符串，例如 {@code 100.00}。 */
    public static String price(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        return (negative ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    /**
     * 同步一份快照（由 {@code ClientPacketSink} 调用）。
     *
     * <p>持仓是**整份替换**（不是按股票下标覆盖）：仓位数每一帧都可能变
     * （开仓 / 平仓 / 爆仓），按下标覆盖会留下已经平掉的鬼仓。
     */
    public static void applySnapshot(long cashCents, long netWorthCents, int seconds,
                                     long[] priceArr, int[] changeArr, Position[] posArr,
                                     int[][] candleArr, int indexDeciValue, int indexChange,
                                     MarketRegime marketRegime, S2CStockSnapshot.NewsItem[] newsArr,
                                     String flashMessage) {
        cash = cashCents;
        netWorth = netWorthCents;
        secondsToNext = seconds;
        copy(priceArr, prices);
        copyInt(changeArr, changesPermille);
        positions = posArr == null ? new Position[0] : posArr;
        // ⚠️ 阴阳柱是低频数据（只在跳价时重发）；空数组表示「沿用上一份」。
        // 否则每秒一次的状态推送会把 K 线清空（历史价不在每秒包里）。
        if (candleArr != null && candleArr.length > 0 && candleArr[0] != null && candleArr[0].length > 0) {
            candles = candleArr;
        }
        indexDeci = indexDeciValue;
        indexChangePermille = indexChange;
        regime = marketRegime == null ? MarketRegime.CALM : marketRegime;
        news = newsArr == null ? new S2CStockSnapshot.NewsItem[0] : newsArr;
        snapshotAtMs = System.currentTimeMillis();
        if (flashMessage != null && !flashMessage.isEmpty()) {
            flash = flashMessage;
        }
    }

    private static void copy(long[] from, long[] to) {
        if (from == null) {
            return;
        }
        System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
    }

    private static void copyInt(int[] from, int[] to) {
        if (from == null) {
            return;
        }
        System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
    }
}
