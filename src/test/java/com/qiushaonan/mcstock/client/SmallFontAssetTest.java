package com.qiushaonan.mcstock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小号数字字体的**资源自检**（纯 Java：JSON + PNG + 一点算术，不碰 Minecraft）。
 *
 * <p>为什么值得一条单测：{@code assets/mcstock/font/small.json} 里只要有一个数写歪，
 * 表现是「持仓列表里全是缺字方块」而不是报错 —— MC 在指定字体里找不到字形时画的是
 * {@code SpecialGlyphs.MISSING}，**不会**回退到默认字体（FontSet#getGlyphInfo）。
 * 这条测试把 MC {@code BitmapProvider.load()} 的算法照抄一遍，验「json 与图集对得上」：
 *
 * <pre>
 *   cellW = 图宽 / chars[0].length      cellH = 图高 / chars.length
 *   scale = json.height / cellH         （必须是 1，否则字形会被拉伸/缩小 = 糊）
 *   advance = round(墨迹宽 × scale) + 1  （墨迹宽 = 格子里最右一个不透明列 + 1）
 *   墨迹底边 = (7 − ascent) + 格内行号 + 1 —— 取 ascent = 格高时正好落在 y+7，
 *              与原版数字（ascii.png 的 '0' 墨迹占 0..6 行、ascent 7）**同一条基线**
 * </pre>
 *
 * <p>图集与 json 由 {@code tools/gen_small_font.py} 生成，两边一起改；这条测试就是它们的锚。
 */
class SmallFontAssetTest {

    private static final String JSON_PATH = "/assets/mcstock/font/small.json";
    private static final String PNG_PATH = "/assets/mcstock/textures/font/small.png";
    /** 原版数字的基线：绘制 y + 7（见类注释）。 */
    private static final int BASELINE = 7;

    @Test
    void definitionAndAtlasAgree() throws Exception {
        JsonObject root = readJson();
        JsonArray providers = root.getAsJsonArray("providers");
        assertEquals(1, providers.size(), "只放一个 bitmap provider");

        JsonObject provider = providers.get(0).getAsJsonObject();
        assertEquals("bitmap", provider.get("type").getAsString());
        assertEquals("mcstock:font/small.png", provider.get("file").getAsString(),
                "file 是纹理 id：assets/mcstock/textures/font/small.png");

        int height = provider.get("height").getAsInt();
        int ascent = provider.get("ascent").getAsInt();
        assertTrue(ascent <= height, "MC 要求 ascent ≤ height（BitmapProvider.Definition#validate）");

        JsonArray rows = provider.getAsJsonArray("chars");
        assertEquals(1, rows.size(), "一行就够：数字与符号");
        String chars = rows.get(0).getAsString();
        assertTrue(chars.length() >= 15, "至少要有 0-9 . , + - %");
        for (char c : "0123456789.,%+-/".toCharArray()) {
            assertTrue(chars.indexOf(c) >= 0, "缺字形：" + c);
        }

        BufferedImage image = readImage();
        int cellW = image.getWidth() / chars.length();
        int cellH = image.getHeight() / rows.size();
        assertEquals(chars.length() * cellW, image.getWidth(), "图宽 = 字形数 × 格宽（不能有余数）");
        assertEquals(rows.size() * cellH, image.getHeight(), "图高 = 行数 × 格高");
        assertEquals(cellH, height, "height 必须等于格高 → scale = 1 → 1:1 绘制（不缩放才不糊）");
        assertEquals(cellH, ascent, "ascent = 格高 → 墨迹底边落在绘制 y+7，与原版数字同基线");

        List<Integer> advances = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) {
            int inkWidth = inkWidth(image, i, cellW, cellH);
            assertTrue(inkWidth > 0, "字形 '" + chars.charAt(i) + "' 在图集里是空的（会显示成缺字方块）");
            int advance = inkWidth + 1;             // scale = 1
            advances.add(advance);
            // 硬边点阵：不许有半透明抗锯齿像素，否则小字号会糊
            assertHardEdges(image, i, cellW, cellH, chars.charAt(i));
        }
        // 每位数字 4px（原版 6px）：这就是「字号调小」的真正来源
        for (int d = 0; d <= 9; d++) {
            int index = chars.indexOf((char) ('0' + d));
            assertEquals(4, advances.get(index), "数字 " + d + " 的 advance 必须是 4px");
        }
        assertEquals(2, advances.get(chars.indexOf('.')), "小数点窄一档（2px）");

        // 基线：数字的墨迹要压到格子最后一行（这样它们的底边才和原版数字对齐）
        for (int d = 0; d <= 9; d++) {
            int index = chars.indexOf((char) ('0' + d));
            assertEquals(cellH - 1, inkBottomRow(image, index, cellW, cellH),
                    "数字 " + d + " 的墨迹必须占满格子高度（底边对齐基线）");
        }
        // 运算符（+ - ×）居中，不该顶到基线以下
        for (char c : "+-×".toCharArray()) {
            int index = chars.indexOf(c);
            assertTrue(index >= 0, "缺运算符：" + c);
            assertTrue(inkBottomRow(image, index, cellW, cellH) < cellH - 1,
                    "运算符 " + c + " 应该居中（不压基线）");
        }
        // 反推一遍「绘制 y」：底边 = (7 − ascent) + 墨迹底行 + 1
        assertEquals(BASELINE, (BASELINE - ascent) + (cellH - 1) + 1,
                "数字底边必须正好落在基线上");
    }

    private JsonObject readJson() {
        try (InputStream in = open(JSON_PATH)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("读不到字体定义 " + JSON_PATH, e);
        }
    }

    private BufferedImage readImage() {
        try (InputStream in = open(PNG_PATH)) {
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "PNG 读不出来");
            return image;
        } catch (Exception e) {
            throw new AssertionError("读不到字体图集 " + PNG_PATH, e);
        }
    }

    private InputStream open(String path) {
        InputStream in = SmallFontAssetTest.class.getResourceAsStream(path);
        assertNotNull(in, "资源不在 classpath 上：" + path);
        return in;
    }

    /** 复刻 MC 的 getActualGlyphWidth：格子里最右一个不透明列 + 1。 */
    private static int inkWidth(BufferedImage image, int index, int cellW, int cellH) {
        for (int x = cellW - 1; x >= 0; x--) {
            for (int y = 0; y < cellH; y++) {
                if (alpha(image, index * cellW + x, y) != 0) {
                    return x + 1;
                }
            }
        }
        return 0;
    }

    /** 墨迹最低那一行的行号（格子内，0 起）。 */
    private static int inkBottomRow(BufferedImage image, int index, int cellW, int cellH) {
        for (int y = cellH - 1; y >= 0; y--) {
            for (int x = 0; x < cellW; x++) {
                if (alpha(image, index * cellW + x, y) != 0) {
                    return y;
                }
            }
        }
        return -1;
    }

    private void assertHardEdges(BufferedImage image, int index, int cellW, int cellH, char glyph) {
        for (int x = 0; x < cellW; x++) {
            for (int y = 0; y < cellH; y++) {
                int a = alpha(image, index * cellW + x, y);
                assertTrue(a == 0 || a == 255, "字形 '" + glyph + "' 有半透明像素（alpha=" + a + "）");
            }
        }
    }

    /** 透明度（图集是 RGBA；MC 的 BitmapProvider 也是按 alpha 判「有没有墨」）。 */
    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }
}
