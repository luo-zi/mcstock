package com.qiushaonan.mcstock.command;

import com.qiushaonan.mcstock.market.StockDefs;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mcstock} 的**参数解析**单测：只测纯函数那一段
 * （{@link McStockCommand#parseCoins} / {@link McStockCommand#parseStockId}）。
 *
 * <p>⚠️ 为什么不去测「{@code /mcstock price set} 整条链」：id → 下标那一步走
 * {@code StockCatalog#indexOf}，而 {@code StockCatalog} 的**静态初始化**握着
 * {@code Items.DIAMOND} 那三个图标 —— 在没有 Bootstrap 的纯 JUnit JVM 里当场就炸。
 * 所以解析被刻意切成两段：**规范化**（纯函数，这里测）+ **查表**（{@code StockCatalog.indexOf}，
 * 一份实现、只在游戏里跑）。这也是 {@link McStockCommand} 里不许有 Minecraft 静态字段的原因：
 * 一旦有，这个测试类连加载 {@code McStockCommand} 都会失败。
 */
class McStockCommandTest {

    // ==================== parseCoins：金额（金币）→ 分 ====================

    @Test
    void parsesWholeCoins() {
        assertCoins(100_000L, "1000");
        assertCoins(0L, "0");
        assertCoins(100L, "1");
        assertCoins(123_456L, "1234.56");
    }

    @Test
    void parsesDecimals() {
        assertCoins(1_234L, "12.34");
        assertCoins(50L, ".5");         // 省略整数部分
        assertCoins(500L, "5.");        // 省略小数部分
        assertCoins(10L, "0.1");        // 0.1 × 100 用浮点算会得到 10.000000000000002
        assertCoins(115L, "1.15");      // 1.15 × 100 用浮点算会得到 114.999…（截断成 114）
    }

    @Test
    void truncatesBeyondTwoDecimals() {
        // 刻意**不四舍五入**：和 StockService.formatPrice 的两位小数口径一致，
        // 否则会出现「输入 1.005、余额显示 1.00」这种对不上的账
        assertCoins(1_234L, "12.345");
        assertCoins(1_234L, "12.349");
        assertCoins(100L, "1.009");
    }

    @Test
    void toleratesWhitespaceAndLeadingPlus() {
        assertCoins(800L, "  8  ");
        assertCoins(750L, "+7.5");
        assertBad("+ 7.5");         // 加号与数字之间不能有空格（要错就错，不猜）
    }

    @Test
    void acceptsNegativeForDebt() {
        // 负号 = 负债：这是**权限 2 的调试命令**，用来直接造负债看界面 / 触发「空中飞人」；
        // 普通玩家没有这条命令，所以「负债只能由穿仓产生」的玩法约束不受影响。
        assertCoins(-500L, "-5");
        assertCoins(-12_345L, "-123.45");
        assertCoins(-50L, "-.5");
        assertBad("-");             // 只有负号 = 非法
        assertBad("--5");           // 两个负号 = 非法
        assertBad("- 5");           // 负号与数字之间有空格 = 非法
    }

    @Test
    void rejectsGarbage() {
        assertBad(null);
        assertBad("");
        assertBad("   ");
        assertBad("+");
        assertBad(".");
        assertBad("abc");
        assertBad("12a");
        assertBad("1.2.3");
        assertBad("1e3");           // 不猜科学计数法
        assertBad("1,000");         // 不猜千分位
        assertBad("0x10");
        assertBad("１２３");          // 全角数字不是 ASCII 数字
        assertBad(" 12 34 ");
    }

    @Test
    void rejectsAmountsBeyondTheCapOrLong() {
        assertTrue(McStockCommand.parseCoins("1000000000").ok(), "上限本身要能用");
        assertCoins(100_000_000_000L, "1000000000");
        assertBad("1000000001");                                    // 超过 10 亿金币
        assertBad("99999999999999999999999999");                    // long 装不下
        assertBad("99999999999999999999999.99");                    // 溢出必须报错，不能变成别的数
    }

    // ==================== parseStockId：输入 → 裸 id ====================

    @Test
    void acceptsBareIds() {
        for (String id : StockDefs.IDS) {
            assertEquals(id, McStockCommand.parseStockId(id));
        }
        assertEquals("diamond_mine", McStockCommand.parseStockId("diamond_mine"));
        assertEquals("nether_trade", McStockCommand.parseStockId("nether_trade"));
    }

    @Test
    void normalizesNamespaceCaseAndPadding() {
        assertEquals("diamond_mine", McStockCommand.parseStockId("mcstock:diamond_mine"));
        assertEquals("redstone_tech", McStockCommand.parseStockId("  REDSTONE_TECH  "));
        assertEquals("nether_trade", McStockCommand.parseStockId("MCStock:Nether_Trade"));
    }

    @Test
    void rejectsUnknownStocks() {
        assertNull(McStockCommand.parseStockId(null));
        assertNull(McStockCommand.parseStockId(""));
        assertNull(McStockCommand.parseStockId("diamond"));
        assertNull(McStockCommand.parseStockId("gold_mine"));
        assertNull(McStockCommand.parseStockId("minecraft:diamond_mine"), "别的命名空间是打错了，不是通配");
        assertNull(McStockCommand.parseStockId("mcstock:"));
    }

    /**
     * 返回的 id 必须**正好**是 {@link StockDefs#IDS} 里的一项 ——
     * 这是「id → 下标」能交给 {@code StockCatalog.indexOf} 的前提
     * （那个函数是逐个 {@code equals} 比，不做任何模糊匹配）。
     */
    @Test
    void everyAcceptedIdIsExactlyOneOfTheCatalogIds() {
        for (String raw : new String[]{"diamond_mine", "mcstock:diamond_mine", " Diamond_Mine ",
                "redstone_tech", "nether_trade"}) {
            String id = McStockCommand.parseStockId(raw);
            assertTrue(Arrays.asList(StockDefs.IDS).contains(id),
                    raw + " 解析出的 " + id + " 必须是 StockDefs.IDS 里的一项");
        }
    }

    // ==================== 小工具 ====================

    private static void assertCoins(long expectedCents, String text) {
        McStockCommand.Coins parsed = McStockCommand.parseCoins(text);
        assertTrue(parsed.ok(), "「" + text + "」应该能解析成金额");
        assertEquals(expectedCents, parsed.cents(), "「" + text + "」应该等于 " + expectedCents + " 分");
    }

    private static void assertBad(String text) {
        McStockCommand.Coins parsed = McStockCommand.parseCoins(text);
        assertFalse(parsed.ok(), "「" + text + "」不该被当成合法金额");
        assertEquals(0L, parsed.cents(), "非法金额必须回 0 分，调用方靠 ok 判断，不要静默当 0 用");
    }
}
