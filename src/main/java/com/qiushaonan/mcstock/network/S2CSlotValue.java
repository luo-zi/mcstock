package com.qiushaonan.mcstock.network;

import com.qiushaonan.mcstock.McStock;
import com.qiushaonan.mcstock.client.ClientPacketSink;
import com.qiushaonan.mcstock.menu.StockMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：回收栏现在值多少钱。
 *
 * @param total      回收栏 18 格合计（**分**）
 * @param kinds      其中有几种物品
 * @param unitValues **逐格**的单件回收价（**分**，索引 = 回收栏槽位 0..17，空格为 0）
 *
 * <p>为什么不让客户端自己算：**估值表在服务端**（{@code data/mcstock/} 里的那张表），
 * 客户端手里没有它；而且就算有，客户端算出来的也只是「它自己那份物品栏的镜像」，
 * 槽位同步稍有延迟就会闪。所以每次回收栏变化都由服务端推一份过来，
 * 客户端只负责把字画出来。
 *
 * <p>⚠️⚠️ {@code unitValues} 是**后加的**，起因是一个真机 bug：回收明细原来只拿得到
 * 「合计」和「总件数」，于是客户端用 {@code 合计 / 总件数} 反推了一个**均价**当每一行的单价 ——
 * 三种不同物品只要件数相同就显示出**完全相同**的单价与小计（截图：32 下界合金锭 / 32 金锭 /
 * 32 下界合金碎片 三行都是 {@code 776.66 / 24053.12}）。
 * **均价不是单价**：一行明细必须是「这一行自己的单件价」。所以服务端把逐格的单价也带上，
 * 客户端按「该格单价 × 该格件数」逐项累加，行与行之间才独立。
 */
public record S2CSlotValue(long total, int kinds, List<Integer> unitValues) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CSlotValue> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(McStock.MODID, "slot_value"));

    /**
     * 手写 codec（2026-10-06 极端输入审计之后改的）。
     *
     * <p>原来是 {@code StreamCodec.composite} + {@code ByteBufCodecs.list(BAR_SLOTS)}：
     * 那个 list 解码器在**声明条数超过上限时会抛解码异常** —— 和目录 4096 条、快照 103 字符
     * 是同一条纪律（解码器不许抛），所以这里改成手写：
     * 声明条数走 {@link PacketGuards#clampLength}（超了截断 + 去重 WARN），逐项自己读。
     *
     * <p>写端恒定写 {@link StockMenu#BAR_SLOTS} 项（回收栏格数），所以正常包永远不会触发截断。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSlotValue> STREAM_CODEC =
            StreamCodec.of(S2CSlotValue::encode, S2CSlotValue::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CSlotValue p) {
        buf.writeVarLong(p.total);
        buf.writeVarInt(p.kinds);
        List<Integer> values = p.unitValues == null ? List.of() : p.unitValues;
        int count = Math.min(values.size(), StockMenu.BAR_SLOTS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Integer value = values.get(i);
            buf.writeVarInt(value == null ? 0 : value);
        }
    }

    private static S2CSlotValue decode(RegistryFriendlyByteBuf buf) {
        long total = buf.readVarLong();
        int kinds = buf.readVarInt();
        // 上限给 2 倍回收栏格数：留一点以后加格子的余地，同时挡住畸形包
        int count = PacketGuards.clampLength(buf.readVarInt(), StockMenu.BAR_SLOTS * 2, "回收栏单价数组");
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(buf.readVarInt());
        }
        return new S2CSlotValue(total, kinds, values);
    }

    /** 便捷构造：按回收栏槽位给单价（长度不足 18 时补 0）。 */
    public static S2CSlotValue of(long total, int kinds, int[] perSlot) {
        List<Integer> list = new ArrayList<>(StockMenu.BAR_SLOTS);
        for (int i = 0; i < StockMenu.BAR_SLOTS; i++) {
            list.add(i < perSlot.length ? perSlot[i] : 0);
        }
        return new S2CSlotValue(total, kinds, list);
    }

    /** 第 {@code slot} 格的单件价（分）；越界或旧包（空列表）返回 0。 */
    public int unitAt(int slot) {
        return slot >= 0 && slot < this.unitValues.size() ? this.unitValues.get(slot) : 0;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CSlotValue payload, IPayloadContext context) {
        // 只在客户端执行；服务端不会收到 clientbound 包，ClientPacketSink 也就不会被它加载
        context.enqueueWork(() -> ClientPacketSink.onSlotValue(payload));
    }
}
