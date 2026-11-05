package com.qiushaonan.mcstock.market;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件表（{@link StockEventTable}）单测：结构与数值口径。
 *
 * <p>这些断言和 {@code StockEventTable} 静态块里的自检是**两件事**：静态块防的是「类加载时就已经写错」，
 * 这里防的是「以后有人放宽常量」。区间用字面量写死（3~8σ / 20~60 跳 / 1.5~3 倍），
 * 而不是引用表里的常量 —— 引用常量的话，改常量就等于改测试，等于没测。
 */
class StockEventTableTest {

    @Test
    void everyStockHasEnoughEvents() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            assertTrue(StockEventTable.size(stock) >= 4,
                    StockDefs.IDS[stock] + " 的事件少于 4 个");
        }
    }

    @Test
    void directionsAreMixedWithinEveryStock() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            int up = 0;
            int down = 0;
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                int direction = StockEventTable.direction(stock, i);
                assertTrue(direction == 1 || direction == -1, "方向必须是 ±1");
                if (direction > 0) {
                    up++;
                } else {
                    down++;
                }
            }
            assertTrue(up > 0 && down > 0, StockDefs.IDS[stock] + " 的事件必须利好利空都有");
        }
    }

    @Test
    void magnitudesHalfLivesAndSigmaMultiplesAreInsideTheDesignRange() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                StockEventTable.EventDef def = StockEventTable.get(stock, i);
                assertNotNull(def);
                assertTrue(def.magnitude() >= 3.0 && def.magnitude() <= 8.0,
                        def.id() + " 的幅度必须落在 3~8σ：" + def.magnitude());
                assertTrue(def.halfLife() >= 20 && def.halfLife() <= 60,
                        def.id() + " 的半衰期必须落在 20~60 跳：" + def.halfLife());
                assertTrue(def.sigmaMultiple() >= 1.5 && def.sigmaMultiple() <= 3.0,
                        def.id() + " 的 σ 倍率必须落在 1.5~3：" + def.sigmaMultiple());
                assertTrue(StockEventTable.weightOf(def) > 0, def.id() + " 的权重必须 > 0");
            }
        }
    }

    @Test
    void idsAndLanguageKeysAreUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                StockEventTable.EventDef def = StockEventTable.get(stock, i);
                assertTrue(ids.add(def.id()), "事件 id 重复：" + def.id());
                assertTrue(keys.add(def.nameKey()), "语言键重复：" + def.nameKey());
                assertEquals("gui.mcstock.event." + def.id(), def.nameKey(),
                        "语言键必须由 id 拼出来（check_lang_keys.py 按这个前缀查）");
            }
        }
    }

    @Test
    void targetsAlwaysIncludeHomeStockAtFullWeightAndStayInRange() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                StockEventTable.EventDef def = StockEventTable.get(stock, i);
                boolean home = false;
                for (StockEventTable.Target target : def.targets()) {
                    assertTrue(target.stock() >= 0 && target.stock() < StockDefs.COUNT,
                            def.id() + " 的影响映射有非法股票下标");
                    assertTrue(target.weight() != 0.0 && Math.abs(target.weight()) <= 1.0,
                            def.id() + " 的权重必须非 0 且不超过 1");
                    if (target.stock() == stock) {
                        assertEquals(1.0, target.weight(), def.id() + " 对本公司必须是满额");
                        home = true;
                    }
                }
                assertTrue(home, def.id() + " 的影响映射必须包含本公司（否则新闻上的百分比没意义）");
            }
        }
    }

    /**
     * 外溢的**双向性**：至少要有「反向外溢」（权重为负）的事件 ——
     * 现实里「下界传送门拥堵 → 资金往钻石矿业避险」就是这种。
     */
    @Test
    void someEventsSpillOverInTheOppositeDirection() {
        int negativeWeights = 0;
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                for (StockEventTable.Target target : StockEventTable.get(stock, i).targets()) {
                    if (target.weight() < 0) {
                        negativeWeights++;
                    }
                }
            }
        }
        assertTrue(negativeWeights >= 2, "至少要有两条反向外溢（资金避险 / 成本传导），实际 " + negativeWeights);
    }

    /** 每只股票至少一条传闻，且传闻的口径是「先涨后跌」。 */
    @Test
    void everyStockHasARumorWithAReasonableClarification() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            int rumors = 0;
            for (int i = 0; i < StockEventTable.size(stock); i++) {
                StockEventTable.EventDef def = StockEventTable.get(stock, i);
                if (!def.isRumor()) {
                    assertEquals(0, def.clarifyAfter(), def.id() + " 不是传闻，不该有澄清延迟");
                    continue;
                }
                rumors++;
                assertEquals(1, def.direction(), def.id() + "：传闻先给正向冲击（先涨后跌）");
                assertTrue(def.clarifyAfter() >= 5 && def.clarifyAfter() <= 60,
                        def.id() + " 的澄清延迟要在 5~60 跳（看得见「先涨」再回调）：" + def.clarifyAfter());
                assertTrue(def.clarifyFactor() >= 0.6 && def.clarifyFactor() <= 0.8,
                        def.id() + " 的澄清回吐倍率必须是 60%~80%：" + def.clarifyFactor());
            }
            assertEquals(1, rumors, StockDefs.IDS[stock] + " 应该正好有一条传闻");
        }
    }

    /** 权重抽签：纯函数、覆盖所有行、边界不越界。 */
    @Test
    void pickCoversEveryRowAndIsPure() {
        for (int stock = 0; stock < StockDefs.COUNT; stock++) {
            Map<Integer, Integer> hits = new HashMap<>();
            for (int i = 0; i < 4_000; i++) {
                int index = StockEventTable.pick(stock, i / 4_000.0);
                assertTrue(index >= 0 && index < StockEventTable.size(stock), "行号越界：" + index);
                hits.merge(index, 1, Integer::sum);
            }
            assertEquals(StockEventTable.size(stock), hits.size(), "每一行都该被抽到");
            assertEquals(StockEventTable.pick(stock, 0.42), StockEventTable.pick(stock, 0.42),
                    "同一个随机数必须给同一行");
        }
    }

    /** 越界访问一律安全返回，不抛异常（脏存档 / 畸形包）。 */
    @Test
    void outOfRangeLookupsAreSafe() {
        assertNull(StockEventTable.get(-1, 0));
        assertNull(StockEventTable.get(0, -1));
        assertNull(StockEventTable.get(0, 999));
        assertNull(StockEventTable.get(99, 0));
        assertEquals("", StockEventTable.id(0, 999));
        assertEquals(0, StockEventTable.direction(0, 999));
        assertEquals(0.0, StockEventTable.sigmaMultiple(0, 999));
        assertNull(StockEventTable.ofType(EventType.COMPANY, 999));
    }

    /** 成簇只能在有事件的类型里抽（MARKET 只由大盘状态机产生）。 */
    @Test
    void clusterableTypesHaveEvents() {
        for (EventType type : new EventType[]{EventType.COMPANY, EventType.INDUSTRY,
                EventType.MACRO, EventType.RUMOR}) {
            assertTrue(StockEventTable.countOfType(type) > 0, type + " 没有任何事件可抽");
            for (int i = 0; i < StockEventTable.countOfType(type); i++) {
                int[] flat = StockEventTable.ofType(type, i);
                assertNotNull(flat);
                StockEventTable.EventDef def = StockEventTable.get(flat[0], flat[1]);
                assertNotNull(def);
                assertEquals(type, def.type(), "按类型查出来的事件类型必须一致");
            }
        }
        assertEquals(0, StockEventTable.countOfType(EventType.MARKET),
                "MARKET 不在事件表里（它属于大盘状态机）");
    }
}
