package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import com.qiushaonan.mcstock.client.StockGuiLayout;
import com.qiushaonan.mcstock.market.Portfolio;
import com.qiushaonan.mcstock.market.StockDefs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;

/**
 * 服务端 → 客户端：一帧完整的行情快照（行情页 + 资金情况块 + 持仓列表要的全部数字）。
 *
 * <p>为什么是「一整帧」而不是「字段级增量」：盘面本来就是**每 2 秒整体重算一次**
 * （一跳 = 2 秒），把这一跳的全部数字打包成一条包，客户端只管照着画，
 * 不会出现「价格更新了、持仓还没更新」这种半帧状态，也不用在客户端维护增量缓存。
 *
 * <h2>字段与单位</h2>
 * <p>本类**不解释任何数值的单位**，只保证「发出去什么、收到什么」；
 * 单位（金币 / 每股 / 千分比）由服务端的市场引擎定义，客户端的绘制代码按同一套约定解释。
 *
 * @param cash           余额（金币）
 * @param netWorth       净值（金币）
 * @param secondsToNext  距离下一跳还有几秒（0..2，行情页倒计时用）
 * @param prices         每只股票的现价，按股票下标顺序
 * @param changesPermille 每只股票的涨跌，**千分比**（±10 = ±1%）；用整数千分比是为了
 *                        网络与显示都不碰浮点，红涨绿跌只按符号判断
 * @param posId          每个仓的 id（平仓包 {@link C2SClosePosition} 用的句柄）
 * @param posStock       每个仓的股票下标
 * @param posShares      每个仓的**有符号**股数（正 = 多、负 = 空）
 * @param posEntry       每个仓的开仓均价（分）
 * @param posMargin      每个仓锁定的保证金（分）
 * @param candles        阴阳柱原始数据，见下面的平铺约定
 * @param indexDeci      **大盘指数 × 10**（10000 = 1000.0）：三只股票等权，
 *                       {@code 1000 × mean(price / base)}。用整数传输，客户端只负责画
 * @param indexChangePermille 大盘涨跌（**千分比**，相对上一次跳价，与个股涨跌同口径）
 * @param regime         大盘状态（{@link com.qiushaonan.mcstock.market.MarketRegime} 的序号）。
 *                       ⚠️ 发**序号**而不是语言键：枚举是两端共用的纯 Java 类
 *                       （{@code market/} 包），序号 → 枚举只有 {@code byOrdinal} 一份实现，
 *                       不存在「两份下标表」这种对不上的可能
 * @param news           新闻流（**最新在最前**，服务端最多保留 {@link #NEWS_KEEP} 条）。
 *                       界面只滚动显示其中最新的一条，但整份推过来，翻看/淡出都在客户端做
 * @param flash          提示行文字（{@code ""} = **不更新**提示行，客户端保留上一次的内容）
 *
 * <h2>新闻为什么发「语言键」而不是翻译好的文本</h2>
 * <p>⚠️ 本项目在 {@code flash} 那条链路上吃过亏：服务端 {@code Component.getString()} 之后
 * 只剩成品文本，客户端既不知道它是什么、也没法按语言重渲染，只能拿 lang 的「值」做模板匹配
 * 去猜它属于哪一页（AGENTS.md 6.18）。新闻从一开始就发 {@code nameKey}：
 * 多人服里服务端语言常常是 en、客户端是 zh，键名才能让两边各按自己的语言渲染；
 * 而且客户端还要用类型标签、方向配色、年龄淡出，这些都需要结构化字段，不是一段文本能带的。
 * 代价是每条新闻多几十字节（8 条 ≈ 200 字节），相对每秒几百字节的状态推送可以忽略。
 *
 * <h2>持仓为什么是「五个等长数组」而不是每股票一个数</h2>
 * <p>玩家的持仓是**一串互相独立的仓**（同一只股票可以有好几个），所以再没有「每股票一个净仓」
 * 这种形状了。五个数组长度都等于 {@link #posCount()}（上限 {@link #MAX_POSITIONS}），
 * 客户端按长度读，**浮盈与爆仓价由客户端用现价自己算** ——
 * {@code PositionMath} 是纯 Java 的，客户端直接调同一个函数，不必在协议里重复搬一遍
 * （搬了反而多一处会算错的地方）。
 *
 * <p>⚠️ 杠杆**不在协议里**：它是开仓那一刻的显示属性，客户端从「均价 × 股数 ÷ 保证金」反推
 * （{@code Position#deriveLeverage}），和旧版 K 线下面那行「×杠杆」是同一个口径。
 *
 * <h2>candles 的平铺约定（客户端照这个下标取数）</h2>
 * <pre>
 *   index = (stock * {@link #CANDLE_MAX} + candle) * {@link #CANDLE_FIELDS} + field
 *   field: 0 = open, 1 = high, 2 = low, 3 = close
 *   stock: 0..2      candle: 0 = 最旧 … {@link #CANDLE_MAX}-1 = 最新
 * </pre>
 * 长度固定 {@link #CANDLES_LENGTH}（= 3 × 60 × 4 = 720）：某只股票历史还不够 60 根时，
 * **空出来的那几根全部补 0**（不是补现价），客户端靠「四价全是 0」跳过不画 ——
 * 补现价的话，开盘头几帧会平白多出一堆「横盘」的柱子。
 *
 * <p>数组直接作为 record 分量持有（不复制）：一条包只被一个线程读一次，
 * 收包侧把它当只读数据用就行；要长期留着就自己 copy 一份。
 */
public record S2CStockSnapshot(
        long cash,
        long netWorth,
        int secondsToNext,
        int[] prices,
        int[] changesPermille,
        int[] posId,
        int[] posStock,
        int[] posShares,
        long[] posEntry,
        long[] posMargin,
        int[] candles,
        int indexDeci,
        int indexChangePermille,
        int regime,
        NewsItem[] news,
        String flash) implements CustomPacketPayload {

    /**
     * 一条新闻（协议里的形状）。
     *
     * @param stock     股票下标；{@code -1} = 「大盘」状态播报（不属于任何一只股票）
     * @param nameKey   展示名的**语言键**（客户端翻译）
     * @param type      {@link com.qiushaonan.mcstock.market.EventType} 的序号（安全越界兜底）
     * @param permille  名义影响（千分比，带符号；大盘那条是每跳的基础漂移）
     * @param ageTicks  距触发已经过去多少 tick（客户端拿它 + 本地经过时间做淡出/接替）
     * @param clarified 这条传闻是不是已经被澄清过
     */
    public record NewsItem(int stock, String nameKey, int type, int permille, int ageTicks,
                           boolean clarified) {
    }

    /** 服务端最多保留几条新闻（与 {@code StockDefs.NEWS_KEEP} 同一个数）。 */
    public static final int NEWS_KEEP = StockDefs.NEWS_KEEP;

    /** 一根阴阳柱 4 个价（开 / 高 / 低 / 收）。 */
    public static final int CANDLE_FIELDS = 4;
    /**
     * 图上最多放多少根柱子。**必须和客户端布局用的上限一致**：
     * 直接从 {@link StockGuiLayout#CANDLE_MAX} 取，免得以后调布局时两边对不上。
     */
    public static final int CANDLE_MAX = StockGuiLayout.CANDLE_MAX;
    /** 股票数量（三家）。 */
    public static final int CANDLE_STOCKS = 3;
    /** candles 数组的固定长度 = 3 × 60 × 4 = 720。 */
    public static final int CANDLES_LENGTH = CANDLE_STOCKS * CANDLE_MAX * CANDLE_FIELDS;

    /**
     * 一条快照最多带几个仓。**与服务端的开仓上限是同一个数**
     * （{@link Portfolio#MAX_POSITIONS}）：服务端不会让玩家持有比这更多的仓，
     * 否则多出来的仓在持仓列表里既看不见也平不掉。
     */
    public static final int MAX_POSITIONS = Portfolio.MAX_POSITIONS;

    /**
     * 除 candles 外那些数组的长度上限。真实长度只有个位数（每只股票一项 / 每个仓一项），
     * 这里给一个宽松的上限只是为了让**解码端**在收到畸形包时抛异常而不是先分配一大块内存。
     */
    private static final int MAX_ARRAY_LENGTH = 1024;

    /**
     * 持仓数组在**线路上**允许的最大长度（比 {@link #MAX_POSITIONS} 宽松）。
     *
     * <p>和 candles 同一个理由：解码失败会直接踢连接（NeoForge 对包解码异常的处理是断开玩家），
     * 而「以后把上限从 32 调大」这种改动应该表现为「列表少显示几个」，不该表现为「上线就被踢」。
     * 多出来的部分由 {@link #posCount()} 统一截断，流的位置仍由长度前缀保证不错位。
     */
    private static final int MAX_POSITIONS_WIRE = MAX_POSITIONS * 4;

    /**
     * candles 在**线路上**允许的最大长度（比 {@link #CANDLES_LENGTH} 宽松）。
     *
     * <p>发送方目前发的就是 {@code StockDefs.COUNT(3) × StockDefs.CANDLE_MAX(60) × 4 = 720} 个，
     * 正好等于 {@link #CANDLES_LENGTH}。这里不把线路上限卡死在 720，是因为**解码失败会直接踢连接**
     * （NeoForge 对包解码异常的处理是断开玩家），而「以后有人把 60 改成 80」这种改动
     * 应该表现为「图上少画几根」，不该表现为「进服务器就被踢」。多出来的部分在
     * {@link #normalizeCandles(int[])} 里截断，流的位置仍然由长度前缀保证不错位。
     */
    private static final int MAX_CANDLES_WIRE = CANDLES_LENGTH * 4;

    /**
     * 新闻条数在**线路上**允许的最大值（比 {@link #NEWS_KEEP} 宽松）。
     *
     * <p>同样是为了「解码失败 = 踢连接」这条：以后把保留条数调大时，
     * 应该表现为「界面少显示几条」，不该表现为「一上线就被踢」。
     */
    private static final int MAX_NEWS_WIRE = NEWS_KEEP * 4;

    // ⚠️ 字符串上限**不在本类定义**：写端与读端共用 {@link PacketGuards#MAX_TEXT} 一份常量。
    //    2026-10-06 的事故二就是「两边各写一个上限」——写 32767 字符、读端只有 34 字符，
    //    一条长消息就让客户端掉了线。别在这里加回任何本地上限。

    /** 空新闻数组（省掉每次都 new 一个）。 */
    private static final NewsItem[] NO_NEWS = new NewsItem[0];

    public static final CustomPacketPayload.Type<S2CStockSnapshot> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "stock_snapshot"));

    /**
     * 手写的 {@link StreamCodec#of}，没有用 {@code StreamCodec.composite}：
     * ① composite 最多支持 6 个分量，这里有 16 个；
     * ② 数组用 {@code FriendlyByteBuf} 自带的 {@code writeVarIntArray} / {@code writeLongArray}
     *    最省事也最省字节（自带长度前缀，VAR_INT 写小整数很划算），
     *    套 {@code ByteBufCodecs.list()} 会先装箱成 {@code List<Integer>} 再逐个数，白绕一圈。
     * ③ 新闻是一串小记录，手写循环比套 codec 更直白（长度前缀 + 逐条字段）。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CStockSnapshot> STREAM_CODEC =
            StreamCodec.of(S2CStockSnapshot::encode, S2CStockSnapshot::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CStockSnapshot p) {
        buf.writeLong(p.cash);
        buf.writeLong(p.netWorth);
        buf.writeVarInt(p.secondsToNext);
        buf.writeVarIntArray(p.prices);
        buf.writeVarIntArray(p.changesPermille);
        // 持仓：五个等长数组（长度 = 仓位数，发包侧已经截到 MAX_POSITIONS）
        buf.writeVarIntArray(p.posId);
        buf.writeVarIntArray(p.posStock);
        buf.writeVarIntArray(p.posShares);
        buf.writeLongArray(p.posEntry);
        buf.writeLongArray(p.posMargin);
        buf.writeVarIntArray(p.candles);
        // 大盘：指数（×10）、涨跌（千分比）、状态序号
        buf.writeVarInt(p.indexDeci);
        buf.writeVarInt(p.indexChangePermille);
        buf.writeVarInt(p.regime);
        // 新闻流：长度前缀 + 逐条字段。⚠️ 空条目也要写满字段（占位），否则流会错位
        NewsItem[] news = p.news == null ? NO_NEWS : p.news;
        int newsCount = Math.min(news.length, MAX_NEWS_WIRE);
        buf.writeVarInt(newsCount);
        for (int i = 0; i < newsCount; i++) {
            NewsItem item = news[i];
            if (item == null) {
                buf.writeVarInt(0);
                PacketGuards.writeText(buf, "");      // 占位也要写满字段，否则流会错位
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeBoolean(false);
                continue;
            }
            buf.writeVarInt(item.stock());
            PacketGuards.writeText(buf, item.nameKey());
            buf.writeVarInt(item.type());
            buf.writeVarInt(item.permille());
            buf.writeVarInt(item.ageTicks());
            buf.writeBoolean(item.clarified());
        }
        // ⚠️ 提示行**必须走 PacketGuards.writeText**（2026-10-06 事故二）：
        //    写端原来是不带上限的 writeUtf（最多 32767 字符），读端上限更小就抛 → 客户端掉线。
        //    现在写端先按码点截到 PacketGuards.MAX_TEXT，读端用同一个常量，两边永远一致。
        //    空串 = 不更新提示行；null 也在这里兜住（发包侧少写一个三元就会让编码器 NPE）
        PacketGuards.writeText(buf, p.flash);
    }

    private static S2CStockSnapshot decode(RegistryFriendlyByteBuf buf) {
        long cash = buf.readLong();
        long netWorth = buf.readLong();
        int secondsToNext = buf.readVarInt();
        // ⚠️ 全部走 PacketGuards（「声明长度超上限就截断 + WARN，绝不抛」）：
        //    原版的 readVarIntArray(max) / readLongArray(null, max) 在声明长度超上限时会**抛解码异常**，
        //    而解码异常在客户端 = 直接掉线（2026-10-06 的目录事故就是这一类）。见 PacketGuards 类注释。
        int[] prices = PacketGuards.readVarIntArray(buf, MAX_ARRAY_LENGTH, "行情价格数组");
        int[] changesPermille = PacketGuards.readVarIntArray(buf, MAX_ARRAY_LENGTH, "涨跌数组");
        int[] posId = PacketGuards.readVarIntArray(buf, MAX_POSITIONS_WIRE, "持仓 id 数组");
        int[] posStock = PacketGuards.readVarIntArray(buf, MAX_POSITIONS_WIRE, "持仓股票数组");
        int[] posShares = PacketGuards.readVarIntArray(buf, MAX_POSITIONS_WIRE, "持仓股数数组");
        long[] posEntry = PacketGuards.readLongArray(buf, MAX_POSITIONS_WIRE, "持仓均价数组");
        long[] posMargin = PacketGuards.readLongArray(buf, MAX_POSITIONS_WIRE, "持仓保证金数组");
        // 硬上限只用来挡畸形包；长度对不上（以后改了 CANDLE_MAX / 股票数）由 normalizeCandles 抹平
        int[] candles = PacketGuards.readVarIntArray(buf, MAX_CANDLES_WIRE, "K 线数组");
        int indexDeci = buf.readVarInt();
        int indexChangePermille = buf.readVarInt();
        int regime = buf.readVarInt();
        int newsCount = Math.max(0, Math.min(MAX_NEWS_WIRE, buf.readVarInt()));
        NewsItem[] news = newsCount == 0 ? NO_NEWS : new NewsItem[newsCount];
        for (int i = 0; i < newsCount; i++) {
            // 新闻的语言键：上限 128 字符（真实键都在 64 以内）；超了截断，不抛
            news[i] = new NewsItem(buf.readVarInt(),
                    PacketGuards.readText(buf, "新闻语言键"),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }
        String flash = PacketGuards.readText(buf, "提示行文本");
        // 长度对不上（版本混用 / 手搓包）时补齐或截断，保证客户端按下标取数不会越界
        return new S2CStockSnapshot(cash, netWorth, secondsToNext,
                prices, changesPermille,
                posId, posStock, posShares, posEntry, posMargin,
                normalizeCandles(candles),
                indexDeci, indexChangePermille, regime, news, flash);
    }

    /** 把任意长度的 candles 规整成 {@link #CANDLES_LENGTH}：短了补 0，长了截断。 */
    private static int[] normalizeCandles(int[] candles) {
        if (candles.length == CANDLES_LENGTH) {
            return candles;
        }
        return Arrays.copyOf(candles, CANDLES_LENGTH);
    }

    /**
     * 这一帧实际能读几个仓 = 五个数组里**最短的那个**，再夹到 {@link #MAX_POSITIONS}。
     *
     * <p>客户端一律走这个方法取长度，不要直接读某个数组的 {@code length} ——
     * 畸形包 / 以后加字段时，五个数组的长度可能不一致，按最短的读才不会越界。
     */
    public int posCount() {
        int n = Math.min(Math.min(posId.length, posStock.length), Math.min(posShares.length,
                Math.min(posEntry.length, posMargin.length)));
        return Math.max(0, Math.min(MAX_POSITIONS, n));
    }

    /**
     * 取某只股票、某根柱子的某个价（0=开 1=高 2=低 3=收）。
     *
     * <p>给客户端绘制用的小工具：平铺下标只在这一个地方算，省得画 K 线时到处手算
     * {@code (stock * 60 + candle) * 4 + field} 写错一位。
     */
    public int candle(int stock, int candle, int field) {
        int index = (stock * CANDLE_MAX + candle) * CANDLE_FIELDS + field;
        return index >= 0 && index < this.candles.length ? this.candles[index] : 0;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CStockSnapshot payload, IPayloadContext context) {
        // 只会在客户端收到（注册时限定 CLIENTBOUND），因此这里可以直接碰客户端类：
        // 服务端永远不会执行到这个方法体，ClientPacketSink 也就不会被服务端加载
        context.enqueueWork(() -> ClientPacketSink.onSnapshot(payload));
    }
}
