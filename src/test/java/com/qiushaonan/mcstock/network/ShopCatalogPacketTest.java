package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.Errors;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **目录分片 + 解码端「绝不抛异常」的回归锚**（纯 Java，走真的 {@link S2CShopCatalog#STREAM_CODEC}）。
 *
 * <h2>这个用例是从哪来的</h2>
 * 线上第三方整合包（7799 件可购买物品）真实掉线：
 * <pre>
 * io.netty.handler.codec.DecoderException: Failed to decode packet 'clientbound/minecraft:custom_payload'
 * Caused by: DecoderException: ShopCatalog with 7799 entries exceeds 4096
 * </pre>
 * 解码器抛异常 = 客户端直接断开。所以这里把**当天那份目录**当成固定输入：
 * 7799 条 → 8 片 → 客户端累积 → 必须与原始目录**逐条一致**（条目数、顺序、价格）。
 *
 * <p>另外把「畸形包一律不抛」也钉住：单包 4096+ 条、声称 1_000_000 条 / 负数条数、
 * 越界片号、重复片、缺片 —— 全部只允许降级，不许抛。
 */
class ShopCatalogPacketTest {

    private static final int REAL_WORLD_ENTRIES = 7799;   // 线上那份整合包的条目数

    /** 造一份 n 条的目录（价格刻意做成「随下标递增」，这样顺序错了会被立刻发现）。 */
    private static ResourceLocation[] items(int n) {
        ResourceLocation[] out = new ResourceLocation[n];
        for (int i = 0; i < n; i++) {
            out[i] = ResourceLocation.fromNamespaceAndPath("test", "item_" + i);
        }
        return out;
    }

    private static int[] prices(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i + 1;
        }
        return out;
    }

    /** 走真 codec 往返一次（编码 → 解码），返回解码结果。 */
    private static S2CShopCatalog roundTrip(S2CShopCatalog payload) {
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        S2CShopCatalog.STREAM_CODEC.encode(buf, payload);
        return S2CShopCatalog.STREAM_CODEC.decode(buf);
    }

    // ==================== ① 线上场景：7799 条 → 8 片 → 逐条一致 ====================

    @Test
    void realWorldCatalogIsSplitAndReassembledLosslessly() {
        ResourceLocation[] items = items(REAL_WORLD_ENTRIES);
        int[] prices = prices(REAL_WORLD_ENTRIES);
        List<S2CShopCatalog> parts = S2CShopCatalog.split(items, prices);

        assertEquals(8, parts.size(), "7799 条按每片 1024 条 = 8 片");
        for (int i = 0; i < parts.size(); i++) {
            S2CShopCatalog part = parts.get(i);
            assertEquals(i, part.part(), "片号必须从 0 递增（NeoForge 保证同连接有序）");
            assertEquals(8, part.totalParts());
            assertTrue(part.count() <= S2CShopCatalog.MAX_ENTRIES_PER_PART, "每片不许超过 1024 条");
        }
        assertEquals(1024, parts.get(0).count());
        assertEquals(REAL_WORLD_ENTRIES - 7 * 1024, parts.get(7).count(), "最后一片是余数");

        // 客户端逐片收（**每片都走真 codec**），收齐之前不许替换
        S2CShopCatalog.Assembler assembler = new S2CShopCatalog.Assembler();
        for (int i = 0; i < parts.size() - 1; i++) {
            assertTrue(assembler.accept(roundTrip(parts.get(i))));
            assertNull(assembler.takeItems(), "没收齐时必须返回 null（调用方保持旧目录）");
            assertFalse(assembler.isComplete());
        }
        assertTrue(assembler.accept(roundTrip(parts.get(7))));
        assertTrue(assembler.isComplete());

        ResourceLocation[] got = assembler.takeItems();
        int[] gotPrices = assembler.takePrices();
        assertNotNull(got);
        assertNotNull(gotPrices);
        assertEquals(REAL_WORLD_ENTRIES, got.length, "拼回来的条目数必须与原表一致");
        assertArrayEquals(items, got, "顺序必须与原表逐条一致");
        assertArrayEquals(prices, gotPrices, "价格必须与原表逐条一致");
    }

    // ==================== ② 分片边界 ====================

    @Test
    void splitBoundariesAreExact() {
        // 空目录：也要给一片空的，否则客户端永远等不到「收齐」、会一直挂着旧目录
        List<S2CShopCatalog> empty = S2CShopCatalog.split(new ResourceLocation[0], new int[0]);
        assertEquals(1, empty.size());
        assertEquals(0, empty.get(0).count());
        S2CShopCatalog.Assembler assembler = new S2CShopCatalog.Assembler();
        assertTrue(assembler.accept(roundTrip(empty.get(0))));
        assertEquals(0, assembler.takeItems().length);

        // 1 条 / 恰好 1024 / 1025
        for (int n : new int[]{1, 1024, 1025}) {
            List<S2CShopCatalog> parts = S2CShopCatalog.split(items(n), prices(n));
            assertEquals(n <= 1024 ? 1 : 2, parts.size(), n + " 条的分片数不对");
            S2CShopCatalog.Assembler acc = new S2CShopCatalog.Assembler();
            for (S2CShopCatalog part : parts) {
                acc.accept(roundTrip(part));
            }
            assertArrayEquals(items(n), acc.takeItems(), n + " 条拼回来必须一致");
            assertArrayEquals(prices(n), acc.takePrices());
        }
    }

    // ==================== ③ 畸形包：一律不抛 ====================

    @Test
    void oversizedSinglePacketIsTruncatedNotThrown() {
        // 模拟旧版客户端 / 手搓包：一个包里声称 5000 条（远超单片上限）
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        buf.writeVarInt(0);                                  // part
        buf.writeVarInt(1);                                  // totalParts
        buf.writeVarInt(5000);                               // 声明 5000 条
        for (int i = 0; i < 5000; i++) {
            buf.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("test", "item_" + i));
        }
        buf.writeVarIntArray(prices(5000));
        Errors.resetForTests();
        S2CShopCatalog decoded = assertDoesNotThrow(() -> S2CShopCatalog.STREAM_CODEC.decode(buf),
                "条目数超上限**绝不允许抛解码异常**（抛了玩家就掉线）");
        assertEquals(S2CShopCatalog.MAX_ENTRIES_PER_PART, decoded.count(), "超上限要截断到上限");
        assertTrue(Errors.hasLogged("S2CShopCatalog#oversized"), "截断要留下一条（去重的）WARN");
    }

    @Test
    void absurdHeadersNeverThrow() {
        // ① 语义荒谬但**字节完整**：负数条数 → 归零，不抛
        Errors.resetForTests();
        for (int declared : new int[]{-1, Integer.MIN_VALUE}) {
            FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            buf.writeVarInt(0);                       // part
            buf.writeVarInt(1);                       // totalParts
            buf.writeVarInt(declared);                // 负的条目数
            buf.writeVarInt(0);                       // 价格数组长度（让流是完整的）
            S2CShopCatalog decoded = assertDoesNotThrow(() -> S2CShopCatalog.STREAM_CODEC.decode(buf),
                    "声称 " + declared + " 条时不许抛解码异常（抛了玩家就掉线）");
            assertEquals(0, decoded.count(), "负的条目数要归零");
        }

        // ② 单包带了 2000 条（超过单片上限，但**头的声明与字节一致** —— 模拟旧服务端 / 手搓包）：
        //    照样读完再截断，不抛
        FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(2000);                       // 声明 2000 条
        for (int i = 0; i < 2000; i++) {             // 字节也是 2000 条（自洽）
            buf.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("test", "x" + i));
        }
        buf.writeVarIntArray(prices(2000));
        S2CShopCatalog decoded = assertDoesNotThrow(() -> S2CShopCatalog.STREAM_CODEC.decode(buf),
                "声明的条数与字节一致时不许抛（读完再截断）");
        assertEquals(S2CShopCatalog.MAX_ENTRIES_PER_PART, decoded.count(), "一律截断到单片上限");

        // ③ 头里声明的字节**根本不在**（流已经错位、无法挽救）：只允许缓冲区下溢那种错误，
        //    **不允许**是我们的语义检查抛出来的 DecoderException
        FriendlyByteBuf shortRaw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf shortBuf = new RegistryFriendlyByteBuf(shortRaw, RegistryAccess.EMPTY);
        shortBuf.writeVarInt(0);
        shortBuf.writeVarInt(1);
        shortBuf.writeVarInt(4096);                  // 声称 4096 条，一个字节都没写
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> S2CShopCatalog.STREAM_CODEC.decode(shortBuf),
                "字节不够属于「流读不动」，原版会自己抛并踢人 —— 这是唯一允许的情况");
        assertFalse(thrown instanceof io.netty.handler.codec.DecoderException,
                "我们自己的语义检查绝不允许抛 DecoderException（那会以「坏包」的名义踢人）：" + thrown);

        // ④ 片头本身畸形：part = -5、totalParts = 1_000_000（字节完整）
        FriendlyByteBuf headRaw = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf headBuf = new RegistryFriendlyByteBuf(headRaw, RegistryAccess.EMPTY);
        headBuf.writeVarInt(-5);
        headBuf.writeVarInt(1_000_000);
        headBuf.writeVarInt(0);
        headBuf.writeVarIntArray(new int[0]);
        S2CShopCatalog header = assertDoesNotThrow(() -> S2CShopCatalog.STREAM_CODEC.decode(headBuf));
        assertTrue(header.part() >= 0, "片号要夹回合法值");
        assertTrue(header.totalParts() <= S2CShopCatalog.MAX_PARTS, "总片数要夹回合法值");
    }

    @Test
    void assemberIgnoresMalformedAndOutOfOrderParts() {
        S2CShopCatalog.Assembler assembler = new S2CShopCatalog.Assembler();
        // 越界片号 / 离谱总片数：直接忽略，不抛
        assertFalse(assembler.accept(new S2CShopCatalog(items(1), prices(1), 5, 2)));
        assertFalse(assembler.accept(new S2CShopCatalog(items(1), prices(1), 0, 999_999)));
        assertFalse(assembler.accept(new S2CShopCatalog(items(1), prices(1), -1, 2)));
        assertNull(assembler.takeItems(), "一片都没收下时不许返回半份目录");

        // 缺片：永远凑不齐 → 一直给 null（客户端保持旧目录）
        List<S2CShopCatalog> parts = S2CShopCatalog.split(items(3000), prices(3000));
        assertEquals(3, parts.size());
        assembler.accept(parts.get(0));
        assembler.accept(parts.get(2));
        assertNull(assembler.takeItems(), "缺中间那片时不许拼出残缺目录");
        assertEquals(2, assembler.receivedParts());
        assertEquals(3, assembler.expectedParts());

        // 重发 part 0 → 重新开始累积（服务端重发/换页时会发生）
        assembler.accept(parts.get(0));
        assertEquals(1, assembler.receivedParts(), "part 0 到达时要重开这一轮");
        // 重复片（非 0 号）：幂等忽略
        assertTrue(assembler.accept(parts.get(1)));
        assertFalse(assembler.accept(parts.get(1)), "同一片重复到达时忽略，不重复拼接");
        assertEquals(2, assembler.receivedParts());

        // 补上剩下的两片 → 收齐
        assembler.accept(parts.get(1));
        assembler.accept(parts.get(2));
        assertArrayEquals(items(3000), assembler.takeItems());
    }

    // ==================== ④ 硬上限截断 ====================

    @Test
    void hardLimitTruncatesKeepingTheMostExpensive() {
        int over = S2CShopCatalog.MAX_TOTAL_ENTRIES + 1000;
        int[] ascending = prices(over);      // 发送端按价格升序排
        int start = S2CShopCatalog.truncationStart(over, S2CShopCatalog.MAX_TOTAL_ENTRIES);
        assertEquals(1000, start, "保留末尾（最贵的）那一万条中的最高价段");
        int[] kept = java.util.Arrays.copyOfRange(ascending, start, ascending.length);
        assertEquals(S2CShopCatalog.MAX_TOTAL_ENTRIES, kept.length, "截断后长度必须正好是硬上限");
        assertEquals(over, kept[kept.length - 1], "最贵的必须留下");

        // 不超过上限时不动
        assertEquals(0, S2CShopCatalog.truncationStart(100, S2CShopCatalog.MAX_TOTAL_ENTRIES));
        assertEquals(0, S2CShopCatalog.truncationStart(S2CShopCatalog.MAX_TOTAL_ENTRIES,
                S2CShopCatalog.MAX_TOTAL_ENTRIES));

        // 32768 条正好是 32 片，且每片都在上限内（说明硬上限与分片参数自洽）
        assertEquals(32, S2CShopCatalog.split(items(S2CShopCatalog.MAX_TOTAL_ENTRIES),
                prices(S2CShopCatalog.MAX_TOTAL_ENTRIES)).size());
    }
}
