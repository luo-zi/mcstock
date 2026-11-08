package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 服务端 → 客户端：这个玩家的**解锁状态**（整份替换，不是增量）。
 *
 * <p>为什么单独一个包、而不是塞进 {@link S2CStockSnapshot} 或目录包：
 * <ul>
 *   <li>快照每 2 秒推一次，光 K 线就几百个整数；解锁表是「交付一组才变一次」的低频数据。</li>
 *   <li>目录包（{@link S2CShopCatalog}）在整合包里是 7 千多条、分 8 片发的 ——
 *       <b>绝不能因为解锁一次就重发整份目录</b>，那会变成「点一下卡一下」。
 *       目录里的条目本来就带价格，客户端拿这份集合自己决定哪些置灰。</li>
 * </ul>
 *
 * <p>⚠️ 解码侧一律不抛（AGENTS.md 第 38 条）：声明条数超上限时**按声明读完再截断**
 * （只读前面一段会让后面的流整体错位），单条 id 解析失败就跳过那一条，
 * 最坏结果是「购买页少灰几个」，绝不能是「一进服就掉线」。
 *
 * <h2>三样东西为什么在同一个包里</h2>
 * 客户端要判定「这一项现在能不能买」需要三个输入：<b>交付过没有</b>（{@code ids}）、
 * <b>玩了多久</b>（{@code playSeconds}）、<b>作者写的进度档</b>（{@code stages}）。
 * 三者必须**同一时刻、同一份**地到达 —— 分开发会让客户端在中间那一瞬算出
 * 「服务端说锁着、界面说能买」的错位（点了被回绝，玩家只会以为按钮坏了）。
 *
 * @param ids         已解锁（交付过一组）的物品
 * @param playSeconds 这个玩家**累计游戏时间**（秒）。客户端拿它和买价自己算
 *                    「玩到 95% 就自动开放」那条路（{@code UnlockRules#isLocked} 是两端共用的），
 *                    所以不需要再单独发一份「时间解锁表」—— 那张表是**算出来的**，不是存出来的
 * @param stages      进度档（物品 → 第几小时游戏时间开放，{@code UnlockStages#snapshot}）。
 *                    客户端不加载估值数据包，**没有**这张表，所以必须推给它；
 *                    否则界面会把 Allthemodium 锭显示成「已经开放」
 */
public record S2CUnlocked(List<ResourceLocation> ids, int playSeconds,
                          Map<ResourceLocation, Integer> stages) implements CustomPacketPayload {

    /** 单包最多带多少个 id。正常玩几百条；4096 是「整合包里全部需要解锁的物品数」的量级上限。 */
    public static final int MAX_IDS = 4096;
    /** 进度档最多带多少条。它只会比 id 少（只有作者手写的那几档），共用同一个量级上限。 */
    public static final int MAX_STAGES = 4096;
    /** 「读完再截断」的硬读上限：声明得再离谱，也最多读这么多条。 */
    private static final int HARD_READ_LIMIT = MAX_IDS * 4;

    /** 进度档里「id 与小时」的分隔符。资源路径里不可能出现 {@code =}，所以按第一个切是安全的。 */
    private static final char STAGE_SEPARATOR = '=';

    public static final CustomPacketPayload.Type<S2CUnlocked> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "unlocked"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CUnlocked> STREAM_CODEC =
            StreamCodec.of(S2CUnlocked::encode, S2CUnlocked::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CUnlocked p) {
        // 游戏时间写在最前面：它是定长字段，坏包截断时先拿到它，
        // 「时间解锁」那条路的显示不会因为后面的 id 列表出问题而整片失效
        buf.writeVarInt(Math.max(0, p.playSeconds));
        List<ResourceLocation> ids = p.ids == null ? List.of() : p.ids;
        int count = Math.min(ids.size(), MAX_IDS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = ids.get(i);
            // id 为 null 也要写一个空串占位：少写一条会让后面的条目整体错位
            PacketGuards.writeText(buf, id == null ? "" : id.toString());
        }
        // 进度档（协议 v8）：按 id 排序再发，同一份表在任何机器上包内容一致（好抓包比对）
        Map<ResourceLocation, Integer> stages = p.stages == null ? Map.of() : p.stages;
        Map<ResourceLocation, Integer> sorted = new TreeMap<>(stages);
        int stageCount = Math.min(sorted.size(), MAX_STAGES);
        buf.writeVarInt(stageCount);
        int written = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : sorted.entrySet()) {
            if (written++ >= stageCount) {
                break;
            }
            ResourceLocation id = entry.getKey();
            Integer hours = entry.getValue();
            if (id == null || hours == null) {
                // 与 id 列表同样的纪律：写一个占位，不让后面的条目错位
                PacketGuards.writeText(buf, "");
                continue;
            }
            PacketGuards.writeText(buf, id + String.valueOf(STAGE_SEPARATOR) + hours);
        }
    }

    private static S2CUnlocked decode(RegistryFriendlyByteBuf buf) {
        int playSeconds = Math.max(0, buf.readVarInt());
        int declared = buf.readVarInt();
        int toRead = Math.max(0, Math.min(declared, HARD_READ_LIMIT));
        List<ResourceLocation> kept = new ArrayList<>();
        for (int i = 0; i < toRead; i++) {
            String raw = PacketGuards.readText(buf, "unlocked");
            if (kept.size() >= MAX_IDS) {
                continue;   // 读还是要读完（保持流对齐），只是不再收
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id != null) {
                kept.add(id);
            }
        }

        // 进度档：一条坏条目只丢那一条，绝不影响前面已经读完的字段
        int stageDeclared = buf.readVarInt();
        int stageToRead = Math.max(0, Math.min(stageDeclared, HARD_READ_LIMIT));
        Map<ResourceLocation, Integer> stages = new LinkedHashMap<>();
        for (int i = 0; i < stageToRead; i++) {
            String raw = PacketGuards.readText(buf, "unlock_stage");
            if (stages.size() >= MAX_STAGES) {
                continue;
            }
            int sep = raw.indexOf(STAGE_SEPARATOR);
            if (sep <= 0 || sep >= raw.length() - 1) {
                continue;   // 没有分隔符 / 空 id / 空小时数：丢掉这条
            }
            ResourceLocation id = ResourceLocation.tryParse(raw.substring(0, sep));
            if (id == null) {
                continue;
            }
            try {
                int hours = Integer.parseInt(raw.substring(sep + 1));
                if (hours > 0) {
                    stages.put(id, hours);
                }
            } catch (NumberFormatException e) {
                // 小时数不是数字：丢这一条，别让整个包解不开
            }
        }
        return new S2CUnlocked(List.copyOf(kept), playSeconds, Map.copyOf(stages));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CUnlocked payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketSink.onUnlocked(payload));
    }
}
