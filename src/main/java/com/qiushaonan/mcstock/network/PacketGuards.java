package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.Errors;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * **解码端的「绝不抛异常」工具箱**（2026-10-06 两次线上掉线事故之后加的）。
 *
 * <h2>事故一：数组声明长度超上限</h2>
 * 整合包有 7799 件可购买物品，`S2CShopCatalog` 原来整包发送 + 解码端 `count &gt; 4096` 就抛
 * → 客户端 `Failed to decode packet` → **掉线**。原版的 {@code readVarIntArray(max)} /
 * {@code readLongArray(null, max)} 在**声明长度超上限**时同样会抛 —— 一并在这里兜住。
 *
 * <h2>事故二：字符串的写上限 ≠ 读上限</h2>
 * <pre>
 * DecoderException: The received encoded string buffer length is longer than maximum allowed (103 &gt; 102)
 * </pre>
 * 写端 {@code writeUtf(s)} 是不带上限的（最多 32767 字符），读端只要用了更小的上限就会抛。
 * 教训两条，都落在这个类里：
 * <ol>
 *   <li><b>读端</b>：{@link #readText} 自己读长度、自己切字节，长度荒谬就夹 + 去重 WARN，**不抛**；</li>
 *   <li><b>写端</b>：{@link #writeText} 先按**码点**截断到 {@link #MAX_TEXT} 再写 ——
 *       保证「写出去的内容永远不超过读端允许的上限」，超长消息最坏只是显示不全。</li>
 * </ol>
 *
 * <p>⚠️ 写/读**必须共用 {@link #MAX_TEXT} 这一个常量**（别在别的类里另写一个数字）：
 * 两边各写一个上限正是这次事故的根因。
 *
 * <p>⚠️ 这里刻意**不用** {@code buf.readUtf(maxChars)} —— 那个方法会**自己再读一次长度前缀**，
 * 而我们已经读过长度了。上一版就错在这里：传进去的字节数被它当成字符上限，第二次读到的是
 * 字符串正文的第一个字节，于是报出「103 &gt; 102」这种完全看不懂的错。
 */
public final class PacketGuards {

    /**
     * 玩家可见文本的长度上限（**字符**数，UTF-16 code unit）：**1024**。
     *
     * <p>为什么是 1024（2026-10-06 由 512 提到 1024，玩家裁决）：
     * <ul>
     *   <li>提示行 / 新闻里会拼**整合包的物品名**，而有些模组会往名字里塞
     *       NBT 后缀 / 属性文本（「钻石剑 +7 锋利 [绑定] …」这类），比原版名字长好几倍；</li>
     *   <li>代价只是一条极端包大几百字节，而**截断的代价是玩家看不到完整信息** ——
     *       两边不对称，所以往宽的一侧取；</li>
     *   <li>中英混排 + emoji 都要放得下（emoji 是代理对：一个字符占 2 个 code unit，
     *       而 UTF-8 里每个 code unit 最多 3 字节 —— 原版 {@code readUtf} 的
     *       {@code maxLength × 3} 字节上限正是按这个算的，1024 字符 ≈ 最坏 3072 字节）；</li>
     *   <li>它仍然是一个**有界**的值：这才是重点。上限本身多大不关键，
     *       「写端与读端用同一个数、且写端先截断」才是防止掉线的那条纪律。</li>
     * </ul>
     */
    public static final int MAX_TEXT = 1024;

    private PacketGuards() {
    }

    // ==================== 字符串：写端截断 + 读端夹长度，共用 MAX_TEXT ====================

    /**
     * 写一段玩家可见文本：**超过 {@link #MAX_TEXT} 就按码点截断**，绝不写出超长内容。
     *
     * <p>按码点截断而不是直接 {@code substring}：那样可能把 emoji / 生僻字的代理对切成两半，
     * 写出去就是一个孤立代理项（界面上显示成方块）。
     */
    public static void writeText(FriendlyByteBuf buf, String text) {
        String safe = text == null ? "" : text;
        if (safe.length() > MAX_TEXT) {
            safe = truncateByCodePoint(safe, MAX_TEXT);
        }
        buf.writeUtf(safe, MAX_TEXT);   // 再让原版按同一个上限兜一次，双保险
    }

    /** 读一段玩家可见文本：长度荒谬就夹 + 去重 WARN，**绝不抛**。 */
    public static String readText(FriendlyByteBuf buf, String what) {
        return readUtf(buf, MAX_TEXT, what);
    }

    /** 按**码点**截断到 {@code maxChars} 个 code unit，且不切断代理对。 */
    public static String truncateByCodePoint(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int end = maxChars;
        // 截断点正好落在代理对中间（前一个是高位代理）就退一格，别切出半个字符
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, Math.max(0, end));
    }

    // ==================== 数组：声明长度先夹住 ====================

    /** 读一个 VarInt 数组，声明长度先夹到 {@code [0, max]}（超了记一条去重 WARN，不抛）。 */
    public static int[] readVarIntArray(FriendlyByteBuf buf, int max, String what) {
        int length = clampLength(buf.readVarInt(), max, what);
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            out[i] = buf.readVarInt();
        }
        return out;
    }

    /** 读一个 Long 数组，声明长度先夹到 {@code [0, max]}（超了记一条去重 WARN，不抛）。 */
    public static long[] readLongArray(FriendlyByteBuf buf, int max, String what) {
        int length = clampLength(buf.readVarInt(), max, what);
        long[] out = new long[length];
        for (int i = 0; i < length; i++) {
            out[i] = buf.readLong();
        }
        return out;
    }

    /**
     * 读一个字符串，声明长度先夹到 {@code maxChars} 个字符（超了记一条去重 WARN，不抛）。
     *
     * <p>实现要点：**长度前缀只读一次**，然后自己 {@code readBytes} 解码 ——
     * 不能调 {@code buf.readUtf(n)}（它会再读一次长度前缀，见类注释里的事故二）。
     */
    public static String readUtf(FriendlyByteBuf buf, int maxChars, String what) {
        int byteLength = buf.readVarInt();
        if (byteLength < 0) {
            warn(what, "声明了负的字符串长度 " + byteLength);
            return "";
        }
        int limit = Math.max(0, maxChars) * 3;      // UTF-16 的一个 code unit 在 UTF-8 里最多 3 字节
        if (byteLength > limit) {
            warn(what, "字符串声明长度 " + byteLength + " 字节超过上限 " + limit
                    + " 字节，已截断（后面的字段可能因此错位，但连接不会断）");
            byteLength = limit;
        }
        if (byteLength == 0) {
            return "";
        }
        byte[] bytes = new byte[byteLength];
        buf.readBytes(bytes);
        // 截断点可能落在多字节字符中间：String 解码会用 U+FFFD 顶替，不会抛
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 把「声明出来的长度」夹进合法区间；越界只记 WARN，不抛。 */
    public static int clampLength(int declared, int max, String what) {
        if (declared < 0) {
            warn(what, "声明了负的长度 " + declared + "，按 0 处理");
            return 0;
        }
        if (declared > max) {
            warn(what, "声明长度 " + declared + " 超过上限 " + max + "，已截断（其余字段可能因此错位，"
                    + "但连接不会断）");
            return max;
        }
        return declared;
    }

    /** 去重 WARN：同一个 {@code what} 只打一次，避免每帧一个包把日志刷爆。 */
    private static void warn(String what, String detail) {
        Errors.logOnce("PacketGuards#" + what, "收到畸形的 " + what + " 包：" + detail);
    }
}
