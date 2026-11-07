package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 模组自己的网络通道。
 *
 * <p>1.21.1 的 NeoForge 已经不用 {@code SimpleChannel} 了：在 {@link RegisterPayloadHandlersEvent}
 * 里拿一个 {@link PayloadRegistrar}，把 {@code CustomPacketPayload} 连同它的 {@code StreamCodec}
 * 一起登记即可。协议号（{@value #PROTOCOL_VERSION}）对不上会直接拒绝连接，
 * **改包结构（加字段、改顺序）时必须一起改**，否则新旧客户端会互相发看不懂的包。
 *
 * <p>{@code @EventBusSubscriber} 不用写 bus：{@link RegisterPayloadHandlersEvent} 是
 * {@code IModBusEvent}，FML 会按事件类型自己把它挂到模组总线上（21.1 起 {@code bus} 字段已被忽略）。
 *
 * <p>方向上的约定（对应 AGENTS.md 的「服务端权威」）：
 * <ul>
 *   <li><b>C2S</b> 只表达「我点了哪个按钮 / 填了什么数」——手上有没有终端、余额够不够、
 *       数量与杠杆合不合法，全部由服务端在 {@code StockService} 里**重算一遍**。
 *       客户端发来的数值一律当成「提示」，不是事实。</li>
 *   <li><b>S2C</b> 只表达「服务端算出来的结果」——客户端一个数都不自己算。</li>
 * </ul>
 */
@EventBusSubscriber(modid = McStock.MODID)
public final class ModNetwork {
    /**
     * 协议版本：改包结构（加字段、改顺序）时必须一起改。
     *
     * <p>「1 → 2」的原因：{@code S2CStockSnapshot} 里那五个「每股票一个数」的数组
     * （shares/avgEntry/margin/liq/pnl）换成了「每仓一个数」的五个数组，并新增
     * {@code C2SClosePosition} —— 旧客户端解新包会字段错位，必须靠版本号挡住。
     *
     * <p>「2 → 3」的原因：{@code S2CStockSnapshot} 末尾追加了四组字段 ——
     * 大盘指数（{@code indexDeci} / {@code indexChangePermille}）、大盘状态序号
     * （{@code regime}）与**新闻流**（{@code news[]}：每条的股票 / 语言键 / 类型 / 影响 /
     * 年龄 / 是否已澄清）。旧客户端解到一半就会读到错位的字节，必须靠版本号挡住。
     *
     * <p>「3 → 4」的原因（2026-10-06 线上掉线事故）：{@code S2CShopCatalog} 从
     * 「一个包塞整份目录」改成**分片**（片头多了 {@code part} / {@code totalParts} 两个字段）。
     * 旧客户端会把片头当成「条目数」读，字段全错位 —— 也就是必须换版本号。
     *
     * <p>「4 → 5」的原因（条件单）：新增 {@link C2SPlaceConditionalOrder} /
     * {@link C2SCancelConditionalOrder} / {@link S2CConditionalOrders} 三个载荷类型。
     * 载荷类型表是连接协商的一部分，旧客户端不认识新通道，必须靠版本号挡住；
     * {@link S2CStockSnapshot} 本身的字段一个没动，所以不需要动那个大 record。
     *
     * <p>「5 → 6」的原因（解锁页）：新增 {@link C2SUnlock} / {@link S2CUnlocked} 两个载荷类型。
     * 同样是「载荷类型表变了」，旧客户端不认识 {@code mcstock:unlocked} 通道；
     * 已有的 {@link S2CShopCatalog} / {@link S2CStockSnapshot} 字段一个没动。
     *
     * <p>「6 → 7」的原因（时间解锁 + 采购冷却）：{@link S2CUnlocked} 头部追加了
     * {@code playSeconds}（客户端要用它自己算「玩到买价 95% 就自动开放」）。
     * 旧客户端会把那个 varint 当成「id 条数」读，整包错位 —— 必须换版本号。
     */
    private static final String PROTOCOL_VERSION = "7";

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // ---------- 客户端 → 服务端 ----------
        // 打开界面（空包：只说「我按了右键」，服务端自己查手上拿的是不是终端）
        registrar.playToServer(
                C2SOpenStock.TYPE,
                C2SOpenStock.STREAM_CODEC,
                C2SOpenStock::handle);
        // 买入 / 卖出（数量、杠杆都是「提示值」，服务端会夹到 QTY_MIN..MAX / LEVERAGE_MIN..MAX）
        // ⚠️ 语义：买入 = 新开一个多仓、卖出 = 新开一个空仓，**永不自动平掉反向仓**（协议 v2）
        registrar.playToServer(
                C2STradeStock.TYPE,
                C2STradeStock.STREAM_CODEC,
                C2STradeStock::handle);
        // 平掉某一个仓（持仓列表每行那个「平」按钮；id 来自快照包的 posId）
        registrar.playToServer(
                C2SClosePosition.TYPE,
                C2SClosePosition.STREAM_CODEC,
                C2SClosePosition::handle);
        // 回收（空包：回收栏里的东西是容器槽同步上去的，服务端读自己的容器，不看客户端）
        registrar.playToServer(
                C2SRecycleBar.TYPE,
                C2SRecycleBar.STREAM_CODEC,
                C2SRecycleBar::handle);
        // 采购（买哪个物品、买几个；count <= 0 = 「买 1 组」）
        registrar.playToServer(
                C2SPurchaseItem.TYPE,
                C2SPurchaseItem.STREAM_CODEC,
                C2SPurchaseItem::handle);
        // 立刻要一份带 K 线的完整快照（切页签 / 换企业时用，见包注释）
        registrar.playToServer(
                C2SRequestSnapshot.TYPE,
                C2SRequestSnapshot.STREAM_CODEC,
                C2SRequestSnapshot::handle);
        // 条件单：挂单 / 撤单（字段全是提示值，服务端重算；协议 v5）
        registrar.playToServer(
                C2SPlaceConditionalOrder.TYPE,
                C2SPlaceConditionalOrder.STREAM_CODEC,
                C2SPlaceConditionalOrder::handle);
        registrar.playToServer(
                C2SCancelConditionalOrder.TYPE,
                C2SCancelConditionalOrder.STREAM_CODEC,
                C2SCancelConditionalOrder::handle);
        // 解锁（空包：投放区是服务端容器，服务端自己扫；协议 v6）
        registrar.playToServer(
                C2SUnlock.TYPE,
                C2SUnlock.STREAM_CODEC,
                C2SUnlock::handle);

        // ---------- 服务端 → 客户端 ----------
        // 行情快照：所有数字一次性推过去（价格 / 涨跌 / 持仓 / 阴阳柱 / 资金）
        registrar.playToClient(
                S2CStockSnapshot.TYPE,
                S2CStockSnapshot.STREAM_CODEC,
                S2CStockSnapshot::handle);
        // 回收栏合计（每次槽位变化都推一份，避免客户端自己估价——估价表在服务端）
        registrar.playToClient(
                S2CSlotValue.TYPE,
                S2CSlotValue.STREAM_CODEC,
                S2CSlotValue::handle);
        // 采购目录（进界面 / 翻页 / 改分类时推一份）
        registrar.playToClient(
                S2CShopCatalog.TYPE,
                S2CShopCatalog.STREAM_CODEC,
                S2CShopCatalog::handle);
        // 条件单簿（每次推快照时顺手带一份，整份替换；协议 v5）
        registrar.playToClient(
                S2CConditionalOrders.TYPE,
                S2CConditionalOrders.STREAM_CODEC,
                S2CConditionalOrders::handle);
        // 已解锁物品表（进界面 / 解锁成功后推一份，整份替换；协议 v6）
        registrar.playToClient(
                S2CUnlocked.TYPE,
                S2CUnlocked.STREAM_CODEC,
                S2CUnlocked::handle);
    }
}
