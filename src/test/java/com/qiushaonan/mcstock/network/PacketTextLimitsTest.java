package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.Errors;
import com.qiushaonan.mcstock.menu.StockMenu;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **字符串上限一致性 + 全包极端输入**的回归锚（纯 Java，走真 codec）。
 *
 * <h2>事故二（2026-10-06，1.0.1 实测掉线）</h2>
 * <pre>
 * Failed decoding custom payload mcstock:stock_snapshot:
 *   DecoderException: The received encoded string buffer length is longer than maximum allowed (103 &gt; 102)
 * </pre>
 * 根因是**写端与读端的字符串上限不是同一个数**（写端不带上限，读端更小就抛）。
 * 所以这里的核心断言是：**对每一个 S2C 包，塞入远超上限的字符串后往返，必须不抛、
 * 且解码出来的文本是安全截断的前缀**。
 *
 * <p>这条用例的价值在于「**新增包 / 改字段时会当场炸**」：只要有人给某个字段写了
 * 比 {@link PacketGuards#MAX_TEXT} 更小的读上限，或者绕开 {@code writeText} 用不带上限的
 * {@code writeUtf}，往返就会抛异常 —— 不用等玩家掉线。
 */
class PacketTextLimitsTest {

    /** 远超上限的长文本（2000 字符；上限 1024，所以一定会被截断）。 */
    private static final String LONG_ASCII = "A".repeat(2000);
    /** 中文长文本（每个字符 3 字节，最坏情况）：12 字 ×120 = 1440 字符。 */
    private static final String LONG_CJK = "钻石矿业红石科技下界贸易".repeat(120);
    /** emoji 混排（代理对 + 中文）：每轮 16 个 code unit ×100 = 1600，截断点很容易落进代理对里。 */
    private static final String EMOJI_MIX = "💎钻石📈红石🚀下界⭐贸易".repeat(100);

    // ==================== 快照包：所有字符串字段 ====================

    @Test
    void snapshotSurvivesOverlongStrings() {
        for (String text : List.of(LONG_ASCII, LONG_CJK, EMOJI_MIX)) {
            S2CStockSnapshot out = snapshotWith(text);
            S2CStockSnapshot in = assertDoesNotThrow(() -> roundTrip(S2CStockSnapshot.STREAM_CODEC, out),
                    "超长字符串绝不允许让 stock_snapshot 解码抛异常（抛了玩家就掉线）");

            // 每段文本都必须被安全截断到上限以内，且是原文的前缀
            int expected = Math.min(text.length(), PacketGuards.MAX_TEXT);
            assertEquals(expected, in.flash().length(),
                    "长文本应被截断到 MAX_TEXT，实际 " + in.flash().length());
            assertTrue(text.startsWith(in.flash()), "截断结果必须是原文的前缀");
            S2CStockSnapshot.NewsItem news = in.news()[0];
            assertEquals(expected, news.nameKey().length());
            assertTrue(text.startsWith(news.nameKey()));
            // 截断不能留下孤立代理项（界面上会显示成方块）
            assertTrue(in.flash().isEmpty()
                            || !Character.isHighSurrogate(in.flash().charAt(in.flash().length() - 1)),
                    "截断点不许切出半个代理对");
            // 其它字段不受影响（说明流没有错位）
            assertEquals(12_345L, in.cash());
            assertArrayEquals(new int[]{100, 200, 300}, in.prices());
        }
    }

    /** 恰好等于上限、以及上限 +1：边界两侧都要正确（截断规则是「超过才截」）。 */
    @Test
    void snapshotTextBoundaryIsExact() {
        String exact = "x".repeat(PacketGuards.MAX_TEXT);
        assertEquals(exact, roundTrip(S2CStockSnapshot.STREAM_CODEC, snapshotWith(exact)).flash(),
                "正好 MAX_TEXT 个字符必须原样保留");

        String oneMore = "x".repeat(PacketGuards.MAX_TEXT + 1);
        assertEquals(PacketGuards.MAX_TEXT,
                roundTrip(S2CStockSnapshot.STREAM_CODEC, snapshotWith(oneMore)).flash().length(),
                "MAX_TEXT + 1 个字符要被截断到上限");
    }

    /** null / 空串：不能 NPE（编码器里的异常同样会踢人）。 */
    @Test
    void nullAndEmptyTextAreSafe() {
        S2CStockSnapshot out = new S2CStockSnapshot(0, 0, 0,
                new int[]{1}, new int[]{0},
                new int[0], new int[0], new int[0], new long[0], new long[0],
                new int[S2CStockSnapshot.CANDLES_LENGTH], 0, 0, 0,
                new S2CStockSnapshot.NewsItem[]{new S2CStockSnapshot.NewsItem(0, null, 0, 0, 0, false)},
                null);
        S2CStockSnapshot in = assertDoesNotThrow(() -> roundTrip(S2CStockSnapshot.STREAM_CODEC, out));
        assertEquals("", in.flash(), "null 提示行要变成空串");
        assertEquals("", in.news()[0].nameKey(), "null 语言键要变成空串");
    }

    // ==================== 畸形字符串头：读端只降级 ====================

    @Test
    void oversizedStringHeaderIsTruncatedNotThrown() {
        Errors.resetForTests();
        // 手搓一个「声明 100 万字节的字符串」的头（源数据只有一小段），模拟恶意/错位包
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        buf.writeVarInt(1_000_000);                          // 声明的字节数远超上限
        for (int i = 0; i < PacketGuards.MAX_TEXT * 3; i++) {
            buf.writeByte('x');                              // 字节是够的（只是声明得离谱）
        }
        String got = assertDoesNotThrow(() -> PacketGuards.readText(buf, "测试文本"),
                "畸形的字符串长度绝不允许抛");
        assertEquals(PacketGuards.MAX_TEXT * 3, got.length(), "按上限读满，不抛");
        assertTrue(Errors.hasLogged("PacketGuards#测试文本"), "要留下一条去重的 WARN");

        // 负数长度：按空串处理
        FriendlyByteBuf negRaw = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf negBuf = new FriendlyByteBuf(negRaw);
        negBuf.writeVarInt(-5);
        assertEquals("", assertDoesNotThrow(() -> PacketGuards.readText(negBuf, "负数长度")));
    }

    // ==================== 目录包 / 回收包也走一遍 ====================

    @Test
    void catalogAndSlotValueSurviveExtremes() {
        // 目录包：物品 id 是 ResourceLocation（内部也是字符串）。合法 id 不会超长，
        // 但这里确认「一大把片 + 大数组」往返后仍然完整
        ResourceLocation[] items = new ResourceLocation[3000];
        int[] prices = new int[3000];
        for (int i = 0; i < items.length; i++) {
            items[i] = ResourceLocation.fromNamespaceAndPath("test", "very_long_item_name_" + i);
            prices[i] = i;
        }
        S2CShopCatalog.Assembler assembler = new S2CShopCatalog.Assembler();
        for (S2CShopCatalog part : S2CShopCatalog.split(items, prices)) {
            assembler.accept(roundTrip(S2CShopCatalog.STREAM_CODEC, part));
        }
        assertArrayEquals(items, assembler.takeItems());
        assertArrayEquals(prices, assembler.takePrices());

        // 回收包：unitValues 是「定长列表」（长度 = 回收栏格数），往返一次
        S2CSlotValue out = new S2CSlotValue(123_456L, 7, intList(StockMenu.BAR_SLOTS));
        S2CSlotValue in = assertDoesNotThrow(() -> roundTrip(S2CSlotValue.STREAM_CODEC, out));
        assertEquals(out.total(), in.total());
        assertEquals(out.kinds(), in.kinds());
        assertEquals(out.unitValues().size(), in.unitValues().size());
        assertEquals(out.unitValues(), in.unitValues());

        // 声明条数超上限（手搓包，但**字节与声明自洽**）：截断 + WARN，不抛
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf bad = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        bad.writeVarLong(1L);
        bad.writeVarInt(1);
        bad.writeVarInt(100);                      // 声明 100 条（超过 2×回收栏格数）
        for (int i = 0; i < 100; i++) {
            bad.writeVarInt(i);                    // 字节也是 100 条
        }
        S2CSlotValue decoded = assertDoesNotThrow(() -> S2CSlotValue.STREAM_CODEC.decode(bad),
                "回收包的声明条数超上限时不许抛");
        assertEquals(StockMenu.BAR_SLOTS * 2, decoded.unitValues().size(), "超上限要截断到上限");
    }

    /**
     * **上限只有一份**：本类断言所有玩家可见文本都截到同一个 {@link PacketGuards#MAX_TEXT}。
     *
     * <p>这是「写上限 == 读上限」的可自动化版本：只要某个包偷用了更小的上限，
     * {@link #snapshotSurvivesOverlongStrings} 之类就会抛异常；
     * 这里再把「上限值本身」钉住，改动它必须同时改测试（避免有人悄悄调小）。
     */
    @Test
    void textLimitIsASingleSharedConstant() {
        assertEquals(1024, PacketGuards.MAX_TEXT,
                "上限只能有一份（PacketGuards.MAX_TEXT，2026-10-06 玩家裁决 512 → 1024）；"
                        + "改它必须同时考虑写端与读端");
        assertEquals("abc", PacketGuards.truncateByCodePoint("abc", 10));
        assertEquals("ab", PacketGuards.truncateByCodePoint("abcd", 2));
        // 代理对：不能切出半个字符
        String emoji = "💎";                       // 2 个 code unit
        assertEquals("", PacketGuards.truncateByCodePoint(emoji, 1),
                "只剩一半代理对时应当整体丢弃");
        assertEquals(emoji, PacketGuards.truncateByCodePoint(emoji, 2));
        assertEquals("", PacketGuards.truncateByCodePoint(null, 5), "null 要安全地变成空串");
    }

    // ==================== 工具 ====================

    private static <T extends CustomPacketPayload> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                              T payload) {
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        codec.encode(buf, payload);
        return codec.decode(buf);
    }

    private static S2CStockSnapshot snapshotWith(String text) {
        return new S2CStockSnapshot(12_345L, 67_890L, 7,
                new int[]{100, 200, 300}, new int[]{10, 20, 30},
                new int[]{1}, new int[]{0}, new int[]{5}, new long[]{1000L}, new long[]{200L},
                new int[S2CStockSnapshot.CANDLES_LENGTH], 5, 10, 1,
                new S2CStockSnapshot.NewsItem[]{
                        new S2CStockSnapshot.NewsItem(0, text, 1, -5, 3, false)},
                text);
    }

    private static List<Integer> intList(int size) {
        Integer[] values = new Integer[size];
        for (int i = 0; i < size; i++) {
            values[i] = i;
        }
        return List.of(values);
    }
}
