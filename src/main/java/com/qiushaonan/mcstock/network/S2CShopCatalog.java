package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.Errors;
import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 服务端 → 客户端：可购买目录的**一片**。
 *
 * @param items      这一片的物品注册名，数组顺序就是显示顺序
 * @param prices     与 {@code items} 一一对应的**单价**（金币）
 * @param part       这一片是第几片（从 0 开始）
 * @param totalParts 一共有几片
 *
 * <h2>为什么要分片（线上事故，2026-10-06）</h2>
 * 第三方整合包里有 **7799 件**能推得出估价的物品，原来「整个目录塞一个包」直接撞上解码端
 * 4096 条的上限，客户端 {@code Failed to decode packet} → **掉线**。现在：
 * <ul>
 *   <li>每片 ≤ {@link #MAX_ENTRIES_PER_PART}（1024）条，7799 条 → 8 片，每片约 2KB；</li>
 *   <li>发送端按顺序发出所有片（NeoForge 保证同一连接内有序），**不等回执**；</li>
 *   <li>客户端用 {@link Assembler} 累积，**收齐之后才整体替换**目录 ——
 *       没齐之前保持旧目录，玩家不会搜到一半看到残缺列表。</li>
 * </ul>
 *
 * <h2>为什么用 {@code part + totalParts} 而不是 {@code offset + total}</h2>
 * <ul>
 *   <li>收端只需要「0..totalParts-1 是不是全到齐」，不需要预先知道任何一片的大小 ——
 *       以后想调分片大小（比如压到 512）不用改协议；</li>
 *   <li>{@code offset} 方案要求收端做区间拼接与重叠检测，平地多一类 bug；</li>
 *   <li>缺片时的行为天然明确：永远凑不齐 → 一直用旧目录（{@code offset + total} 还得自己定义
 *       「中间缺了一段算不算完整」）。</li>
 * </ul>
 *
 * <p>⚠️ 「按价格升序」是**发送方**的责任（{@code StockService} 排好再切片），
 * 这个类刻意不排序：payload 只负责搬运，隐藏的排序会让「客户端看到的顺序」
 * 和「服务端算分页时用的顺序」悄悄不一致。
 *
 * <p>⚠️ **解码端一个异常都不许抛**（见 {@link PacketGuards}）：条目数超上限就截断 + WARN，
 * 片号越界就夹回合法值。宁可让玩家看到「目录不全」，也绝不能让他掉线。
 */
public record S2CShopCatalog(ResourceLocation[] items, int[] prices, int part, int totalParts)
        implements CustomPacketPayload {

    /** 单片最多几条。1024 条 ≈ 2KB，远低于任何包大小上限。 */
    public static final int MAX_ENTRIES_PER_PART = 1024;

    /**
     * 目录的**硬上限**：超过就按价格从高到低截断。
     *
     * <p>32768 条 = 32 片；比任何真实整合包（目前见过 7799）都宽，
     * 同时守住「包体不许无上限膨胀」这条纪律。
     */
    public static final int MAX_TOTAL_ENTRIES = 32768;

    /** 片数的合法上限（由硬上限推出）；畸形包声称更多片时忽略它。 */
    public static final int MAX_PARTS = MAX_TOTAL_ENTRIES / MAX_ENTRIES_PER_PART;

    /**
     * 「还愿意照着读」的条数上限：声明超过它就当成荒谬头（连字节都不信），按 0 条处理。
     *
     * <p>比单片上限宽 64 倍，是为了「**超一点也照样读完再截断**」——
     * 只读前面一段会让后面的价格数组读到物品数据中间、整条流全错位，那比截断更糟。
     */
    private static final int HARD_READ_LIMIT = MAX_ENTRIES_PER_PART * 64;

    /**
     * 物品 id 的字符上限（真实 id 都在 64 以内）。读端限住它，是为了不落进
     * {@code readResourceLocation()} 内部那个 {@code readUtf(32767)} 的抛出路径。
     */
    private static final int MAX_ID_CHARS = 128;

    /** id 解析失败时的占位（air 买不到、价格 0，界面上就是个买不了的格子，但不会掉线）。 */
    private static final ResourceLocation PLACEHOLDER_ID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    public static final CustomPacketPayload.Type<S2CShopCatalog> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "shop_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CShopCatalog> STREAM_CODEC =
            StreamCodec.of(S2CShopCatalog::encode, S2CShopCatalog::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CShopCatalog p) {
        // 片头写在最前面：收端可以先校验片号，再决定怎么读后面的数组
        buf.writeVarInt(p.part);
        buf.writeVarInt(p.totalParts);
        int count = Math.min(p.items.length, MAX_ENTRIES_PER_PART);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeResourceLocation(p.items[i]);
        }
        buf.writeVarIntArray(Arrays.copyOf(p.prices, Math.min(p.prices.length, count)));
    }

    /**
     * 解码：**不抛语义异常**（唯一可能抛的还是「流本身读不动」—— 头里声明的字节根本不在，
     * 那种流已经错位、无法挽救，原版自己也会抛并踢人）。
     *
     * <p>口径（2026-10-06 事故之后定的）：
     * <ul>
     *   <li>声明条数超过 {@link #MAX_ENTRIES_PER_PART} → **照样读完**（否则后面的价格数组会读到
     *       物品数据中间、整条流全错位），但**返回时截断到上限** + 一条去重 WARN；</li>
     *   <li>声明条数荒谬到 {@link #HARD_READ_LIMIT} 以上 → 连字节都不信了：按 0 条处理
     *       （这种包只可能来自坏掉的发送端或已错位的流）；</li>
     *   <li>负数 → 0；价格数组长度对不上 → 补齐或截断。</li>
     * </ul>
     */
    private static S2CShopCatalog decode(RegistryFriendlyByteBuf buf) {
        int part = buf.readVarInt();
        int totalParts = buf.readVarInt();
        if (totalParts < 1) {
            totalParts = 1;
        } else if (totalParts > MAX_PARTS) {
            Errors.logOnce("S2CShopCatalog#parts",
                    "收到畸形的目录包：总片数 " + totalParts + " 超过上限 " + MAX_PARTS + "，已按上限处理");
            totalParts = MAX_PARTS;
        }
        if (part < 0) {
            part = 0;
        } else if (part >= totalParts) {
            // 夹回最后一片：内容可能不对，但至少不越界、也不会把累积器搞乱
            part = totalParts - 1;
        }

        int declaredItems = buf.readVarInt();
        if (declaredItems > MAX_ENTRIES_PER_PART) {
            Errors.logOnce("S2CShopCatalog#oversized",
                    "目录单包声明了 " + declaredItems + " 条（单片上限 " + MAX_ENTRIES_PER_PART
                            + "）：已按上限截断显示，连接不受影响");
        }
        int readItems = saneReadCount(declaredItems);
        ResourceLocation[] readItemArray = new ResourceLocation[readItems];
        for (int i = 0; i < readItems; i++) {
            // ⚠️ 不用 buf.readResourceLocation()：它内部是 readUtf(32767)，声明超长会**抛**
            //    （同一条纪律：解码端只降级）。这里按文本上限读，解析失败就用占位物品 ——
            //    保证下标与价格数组仍然一一对应（宁可显示一个买不了的格子，也绝不掉线）。
            String raw = PacketGuards.readUtf(buf, MAX_ID_CHARS, "目录物品 id");
            ResourceLocation id = ResourceLocation.tryParse(raw);
            readItemArray[i] = id == null ? PLACEHOLDER_ID : id;
        }

        // 价格数组自带长度前缀：**必须按发送端声明的条数读完**，否则后面的字段全错位
        int declaredPrices = buf.readVarInt();
        int readPrices = saneReadCount(declaredPrices);
        int[] readPriceArray = new int[readPrices];
        for (int i = 0; i < readPrices; i++) {
            readPriceArray[i] = buf.readVarInt();
        }

        int keep = Math.min(readItems, MAX_ENTRIES_PER_PART);
        ResourceLocation[] items = Arrays.copyOf(readItemArray, keep);
        int[] prices = Arrays.copyOf(readPriceArray, Math.min(keep, readPriceArray.length));
        if (prices.length != items.length) {
            // 长度对不上就当成缺价补 0：界面上顶多显示成 0 金币，不会越界崩溃
            prices = Arrays.copyOf(prices, items.length);
        }
        return new S2CShopCatalog(items, prices, part, totalParts);
    }

    /** 「声明条数」→「实际读几条」：负数 0、荒谬值 0（流已不可信）、其余原样（返回时再截断）。 */
    private static int saneReadCount(int declared) {
        if (declared < 0) {
            return 0;
        }
        return Math.min(declared, HARD_READ_LIMIT);
    }

    /**
     * 把整份目录切成若干片（**发送端唯一入口**）。
     *
     * <p>顺序即显示顺序；{@code part} 从 0 递增。空目录也会返回**一片空的**
     * （而不是零片）—— 否则客户端永远等不到「收齐」，会一直挂着上一次的旧目录。
     */
    public static List<S2CShopCatalog> split(ResourceLocation[] items, int[] prices) {
        int total = Math.min(items.length, prices.length);
        int totalParts = Math.max(1, (total + MAX_ENTRIES_PER_PART - 1) / MAX_ENTRIES_PER_PART);
        List<S2CShopCatalog> parts = new ArrayList<>(totalParts);
        for (int part = 0; part < totalParts; part++) {
            int from = part * MAX_ENTRIES_PER_PART;
            int to = Math.min(total, from + MAX_ENTRIES_PER_PART);
            parts.add(new S2CShopCatalog(
                    Arrays.copyOfRange(items, from, to),
                    Arrays.copyOfRange(prices, from, to),
                    part, totalParts));
        }
        return parts;
    }

    /**
     * 超硬上限时**按价格从高到低截断**（保留贵的），返回保留区间的起始下标。
     *
     * <p>发送端把目录按价格**升序**排过（第一页必须是买得起的东西），所以「最贵的 N 条」
     * 就是数组末尾的 N 条 —— 返回起点即可，不用重排。
     *
     * <p>为什么截断而不是「有多少发多少」：无上限的包迟早撞上某个上限（这次就是），
     * 而截断至少是**可预期**的降级。
     */
    public static int truncationStart(int total, int limit) {
        return total <= limit ? 0 : total - limit;
    }

    /** 第 i 件的单价；下标越界返回 0（界面照常画，不会抛异常）。 */
    public int price(int index) {
        return index >= 0 && index < this.prices.length ? this.prices[index] : 0;
    }

    /** 这一片有几条。 */
    public int count() {
        return Math.min(this.items.length, this.prices.length);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CShopCatalog payload, IPayloadContext context) {
        // 只在客户端执行（clientbound）
        context.enqueueWork(() -> ClientPacketSink.onCatalog(payload));
    }

    /**
     * **客户端分片累积器**（纯逻辑，不碰渲染、不碰 MC 客户端状态，便于单测）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code part == 0} 开始一次新的累积（顺手处理「服务端重发」）；</li>
     *   <li>片号越界 / 总片数离谱 → 忽略这一片（{@link #accept} 返回 false，不抛）；</li>
     *   <li>收齐之前 {@link #takeItems()} 返回 {@code null}，调用方**保持旧目录**；</li>
     *   <li>收齐之后各返回一份拼好的数组，调用方整体替换。</li>
     * </ul>
     */
    public static final class Assembler {

        private ResourceLocation[][] items;
        private int[][] prices;
        private boolean[] present;
        private int totalParts;
        private int received;

        /**
         * 收下一片。
         *
         * @return 这一片被接受了吗（false = 畸形 / 重复 / 过期片，已忽略）
         */
        public boolean accept(S2CShopCatalog payload) {
            if (payload == null) {
                return false;
            }
            int part = payload.part();
            int total = payload.totalParts();
            if (total < 1 || total > MAX_PARTS || part < 0 || part >= total) {
                return false;   // 畸形片：丢掉，等下一次 part==0 重开（绝不抛）
            }
            if (part == 0 || this.items == null || this.totalParts != total) {
                reset(total);
            }
            if (this.present[part]) {
                return false;   // 重复片：幂等忽略
            }
            this.items[part] = payload.items();
            this.prices[part] = payload.prices();
            this.present[part] = true;
            this.received++;
            return true;
        }

        /** 收齐了吗。 */
        public boolean isComplete() {
            return this.items != null && this.received >= this.totalParts;
        }

        /** 收齐了返回拼好的物品数组，否则返回 {@code null}（调用方保持旧目录）。 */
        public ResourceLocation[] takeItems() {
            if (!isComplete()) {
                return null;
            }
            ResourceLocation[] out = new ResourceLocation[countAll()];
            int at = 0;
            for (ResourceLocation[] chunk : this.items) {
                System.arraycopy(chunk, 0, out, at, chunk.length);
                at += chunk.length;
            }
            return out;
        }

        /** 收齐了返回拼好的价格数组，否则返回 {@code null}。 */
        public int[] takePrices() {
            if (!isComplete()) {
                return null;
            }
            int[] out = new int[countAll()];
            int at = 0;
            for (int[] chunk : this.prices) {
                System.arraycopy(chunk, 0, out, at, chunk.length);
                at += chunk.length;
            }
            return out;
        }

        /** 收到几片了（诊断用）。 */
        public int receivedParts() {
            return this.received;
        }

        /** 这一轮一共几片（还没收过任何片时是 0）。 */
        public int expectedParts() {
            return this.totalParts;
        }

        /** 丢掉当前累积（下一次 {@code part==0} 会重开）。 */
        public void reset() {
            this.items = null;
            this.prices = null;
            this.present = null;
            this.totalParts = 0;
            this.received = 0;
        }

        private int countAll() {
            int total = 0;
            for (ResourceLocation[] chunk : this.items) {
                total += chunk.length;
            }
            return total;
        }

        private void reset(int total) {
            this.items = new ResourceLocation[total][];
            this.prices = new int[total][];
            this.present = new boolean[total];
            this.totalParts = total;
            this.received = 0;
        }
    }
}
