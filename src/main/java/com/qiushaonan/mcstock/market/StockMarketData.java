package com.qiushaonan.mcstock.market;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * 全服共享的行情存档（挂在主世界）。
 *
 * <p>价格、历史价、跳价倒计时、随机源种子与「已跳次数」都在这里 ——
 * 服务器关掉时行情不动（不做事后补算），读档后按种子 + 次数复原随机序列，保证同存档可复现。
 *
 * <p>用 1.21.1 的 {@code SavedData.Factory}（两参重载：构造器 + 反序列化器，datafix 传 null）。
 *
 * <p>⚠️ 加固（2026-10-06）：**脏存档不许抛异常**。玩家改过的 NBT、半途写坏的存档、
 * 别的模组往同一个文件里塞了怪东西 —— 这些都会让 {@code getLongArray} 直接抛
 * （类型对不上时原版是抛 {@code IllegalStateException}）。读档失败就**按新市场初始化**
 * 并记一条 WARN（行情重来一遍，总好过服务器起不来）。
 */
public class StockMarketData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE_ID = "mcstock_market";

    private final MarketEngine engine;

    public StockMarketData() {
        this(new MarketEngine(System.nanoTime()));
    }

    public StockMarketData(MarketEngine engine) {
        this.engine = engine;
    }

    public MarketEngine engine() {
        return engine;
    }

    /** 取主世界的行情存档（三只股票全服共享，跨维度一致）。 */
    public static StockMarketData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StockMarketData::new, StockMarketData::load),
                FILE_ID);
    }

    /**
     * 读档：**任何一步出错都退回「新市场」**（见类注释）。
     *
     * <p>包级可见是为了让单测能直接喂一份坏 NBT 进来（正常路径只有 {@link #get} 会调它）。
     */
    static StockMarketData load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            long seed = tag.getLong("seed");
            long advances = tag.getLong("advances");
            int ticks = tag.contains("ticks") ? tag.getInt("ticks") : StockDefs.PRICE_TICK_TICKS;
            long[] prices = tag.getLongArray("prices");
            long[] history = tag.getLongArray("history");
            return new StockMarketData(MarketEngine.restore(seed, advances, ticks, prices, history));
        } catch (Throwable t) {
            LOGGER.warn("[mcstock] 行情存档读取失败，按**新市场**初始化（旧行情作废，玩家仓位不受影响）", t);
            return new StockMarketData();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // ⚠️ 写盘也不许抛：save 抛异常会打断整个世界存档流程（比行情丢掉严重得多）。
        //    真出问题就写一份空 tag（下次读档会走「新市场」分支）并记一条 WARN。
        try {
            tag.putLong("seed", engine.seed());
            tag.putLong("advances", engine.advances());
            tag.putInt("ticks", engine.ticksToNextRaw());
            tag.putLongArray("prices", engine.snapshotPrices());
            tag.putLongArray("history", engine.snapshotHistory());
            // ⚠️ 大盘状态 / 活着的冲击 / 新闻流**故意不写盘**：它们都是 (seed, advances) 的确定函数 ——
            //    MarketEngine.restore 会把整段跳价重放一遍，这些派生状态原样重建出来。
            //    存两份真相迟早会对不上（改了重放路径却忘了同步存盘字段），而重放本身是纯内存计算。
        } catch (Throwable t) {
            LOGGER.warn("[mcstock] 行情存档写入失败，本次不保存行情（下次启动按新市场初始化）", t);
            return new CompoundTag();
        }
        return tag;
    }

    /** 各只股票现价（分）的拷贝，拼快照包时用。 */
    public long[] prices() {
        return Arrays.copyOf(engine.snapshotPrices(), StockDefs.COUNT);
    }
}
