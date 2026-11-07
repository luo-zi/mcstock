package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：这个玩家**已解锁**的物品 id（整份替换，不是增量）。
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
 * @param ids         已解锁（交付过一组）的物品
 * @param playSeconds 这个玩家**累计游戏时间**（秒）。客户端拿它和买价自己算
 *                    「玩到 95% 就自动开放」那条路（{@code UnlockRules#isLocked} 是两端共用的），
 *                    所以不需要再单独发一份「时间解锁表」—— 那张表是**算出来的**，不是存出来的
 */
public record S2CUnlocked(List<ResourceLocation> ids, int playSeconds) implements CustomPacketPayload {

    /** 单包最多带多少个 id。正常玩几百条；4096 是「整合包里全部需要解锁的物品数」的量级上限。 */
    public static final int MAX_IDS = 4096;
    /** 「读完再截断」的硬读上限：声明得再离谱，也最多读这么多条。 */
    private static final int HARD_READ_LIMIT = MAX_IDS * 4;

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
        return new S2CUnlocked(List.copyOf(kept), playSeconds);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CUnlocked payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketSink.onUnlocked(payload));
    }
}
