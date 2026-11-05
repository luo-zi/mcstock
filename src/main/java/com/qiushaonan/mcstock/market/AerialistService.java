package com.qiushaonan.mcstock.market;

import com.mojang.logging.LogUtils;
import com.qiushaonan.mcstock.McStock;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **「空中飞人」成就的落地**（服务端）：摔落高度缓存 + 死亡判定 + 授成就 + 清债。
 *
 * <h2>为什么需要一个「高度缓存」</h2>
 * 死亡事件（{@code LivingDeathEvent}）里拿不到真实摔落高度 —— 玩家落地那一瞬间
 * {@code fallDistance} 已经被重置成 0 了。所以要在**伤害结算之前**的
 * {@code LivingFallEvent} 里把 {@code getDistance()} 记下来，死亡事件里再取。
 *
 * <h2>缓存的「短生命周期」怎么做</h2>
 * <ul>
 *   <li>**覆盖**：同一个人下一次摔落直接覆盖旧值；</li>
 *   <li>**不够高就删**：{@code LivingFallEvent} 里高度 &lt; 门槛时把旧条目删掉
 *       （避免「上一次百格摔落没死」留下的值被后来的普通摔死误用）；</li>
 *   <li>**死亡即取即删**：死亡事件里 {@code remove} 取走，无论触没触发都清空；
 *       （这就是「没死的话清掉」的等价保证：值最多活到下一次摔落 / 下一次死亡 / 超时）</li>
 *   <li>**超时**：超过 {@link #MAX_AGE_TICKS}（2 秒）的记录视为过期，不参与判定。</li>
 * </ul>
 *
 * <h2>触发后做什么</h2>
 * <ol>
 *   <li>授予成就 {@code mcstock:aerialist}（advancement JSON 里的 criteria 是
 *       {@code minecraft:impossible}，只能由代码授予）；</li>
 *   <li>**清除债务**：现金直接置 0（不是加钱、不是按净值算 —— 照玩家原话「清除债务」）；</li>
 *   <li>推一条聊天提示（死亡界面上动作栏看不清，所以走聊天栏）。</li>
 * </ol>
 *
 * <p>⚠️ 所有入口都由 {@code StockEvents} 包在 {@code Errors.guard} 里：
 * 死亡 / 摔落路径上抛异常会让整个死亡结算断掉，比「成就没发出来」严重得多。
 */
public final class AerialistService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 成就的注册名（不带命名空间）。 */
    public static final String ADVANCEMENT_ID = "aerialist";
    /** criteria 名，与 {@code data/mcstock/advancement/aerialist.json} 里的键一致。 */
    public static final String CRITERION = "impossible";

    /** 记录的有效期（tick）：2 秒。摔死一定是落地那一 tick 的事，用不着更长。 */
    private static final int MAX_AGE_TICKS = 40;

    /** 玩家 UUID → 摔落记录。只在服务端主线程读写，但用并发容器更省心（也防一手死灵线程）。 */
    private static final Map<UUID, Fall> FALLS = new ConcurrentHashMap<>();

    /** 一次摔落记录：高度（格）+ 记下的那一刻（服务端 tick）。 */
    private record Fall(double distance, int tick) {
    }

    private AerialistService() {
    }

    /**
     * {@code LivingFallEvent}：**伤害结算前**记下高度。
     *
     * @param distance {@code event.getDistance()}（格）
     */
    public static void recordFall(ServerPlayer player, double distance) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (distance < AerialistJudge.MIN_FALL_DISTANCE) {
            // 不够高：顺手清掉旧记录，别让「上一次百格没死」的残留影响后面的判定
            FALLS.remove(id);
            return;
        }
        FALLS.put(id, new Fall(distance, player.server.getTickCount()));
    }

    /**
     * {@code LivingDeathEvent}：判三条件 → 授成就 + 清债 + 提示。
     *
     * @return 这次死法触发了成就吗（调试 / 单测观察用）
     */
    public static boolean onDeath(ServerPlayer player, DamageSource source) {
        if (player == null) {
            return false;
        }
        // 即取即删：不管触发不触发，记录都不留到下一条命
        Fall fall = FALLS.remove(player.getUUID());
        if (fall == null) {
            return false;
        }
        int now = player.server.getTickCount();
        if (now - fall.tick() > MAX_AGE_TICKS) {
            return false;       // 过期记录（理论上走不到，保险）
        }
        boolean fellToDeath = source != null && source.is(DamageTypes.FALL);
        long cash = StockService.cash(player);
        if (!AerialistJudge.isAerialist(cash, fall.distance(), fellToDeath)) {
            return false;
        }

        // ① 清债：现金直接置 0（照玩家原话「清除债务」，不是加钱、也不按净值算）
        StockService.setCash(player, 0L);
        // ② 授成就
        award(player);
        // ③ 提示（走聊天栏：死亡界面上动作栏一闪就没了）
        player.displayClientMessage(Component.translatable(
                "message.mcstock.aerialist", StockService.formatPrice(-cash)), false);
        LOGGER.debug("[mcstock] 「空中飞人」触发：摔落 {} 格、原现金 {} 分（已清零）",
                fall.distance(), cash);
        return true;
    }

    /**
     * 授予 {@code mcstock:aerialist}。
     *
     * <p>advancement 查不到（数据包没加载出来 / 玩家把数据包删了）时**静默跳过**：
     * 清债与提示照常发生 —— 成就只是纪念品，不该因为它缺失就吞掉玩家的实际好处。
     */
    private static void award(ServerPlayer player) {
        try {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(McStock.MODID, ADVANCEMENT_ID);
            AdvancementHolder holder = player.server.getAdvancements().get(id);
            if (holder == null) {
                LOGGER.warn("[mcstock] 找不到成就 {}（数据包没加载？），本次只清债不授勋", id);
                return;
            }
            player.getAdvancements().award(holder, CRITERION);
        } catch (RuntimeException e) {
            // 授勋失败不许影响清债与提示：这里吞掉并记一条，外层 Errors.guard 也会兜
            LOGGER.warn("[mcstock] 授予「空中飞人」失败（清债与提示已照常执行）", e);
        }
    }

    /** 调试 / 单测观察用：当前有多少条摔落记录（正常打怪时它应该一直是 0 或极少数）。 */
    public static int pendingRecords() {
        return FALLS.size();
    }

    /** 测试用：清空缓存（生产代码不要调）。 */
    public static void clearForTests() {
        FALLS.clear();
    }
}
