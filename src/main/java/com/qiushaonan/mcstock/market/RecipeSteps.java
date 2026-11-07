package com.qiushaonan.mcstock.market;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 「这一步工序值多少劳动时间」：配方类型 → 分钟。
 *
 * <h2>为什么需要它</h2>
 * 回收价（{@link ItemValuator#value}）只看**材料成本**：{@code floor(Σ 原料价 × 数量 / 产出)}。
 * 这个公式里没有任何一项表示「合成这一步本身要花多少功夫」，后果是
 * <b>1:1 的配方链一点成本都不涨</b> —— 十层「1 个 A → 1 个 B」的机器链，
 * 末端物品的回收价和第一层原料一模一样。整合包（ATM10 那种）里后期物品被廉价卖掉，
 * 根子就在这里。
 *
 * <p>本类给每一步工序一个**时间代价**，喂给 {@link LaborTime} 去累加：
 * 十层链 = 十份工序代价 + 材料代价。于是链越长越贵，这正是「后期物品该贵」的来源。
 *
 * <h2>为什么按「配方类型」而不是按「物品」</h2>
 * 按物品要写几千条（整合包里有 7799 件可估价物品）；按类型只有十几个
 * （原版十来种 + 模组几种）。**这是整套定价里唯一需要人工标定的数字**，
 * 所以刻意压到最小的一处。
 *
 * <h2>三条口径</h2>
 * <ul>
 *   <li><b>不认识的 {@code minecraft:*} → {@link #DEFAULT_MINECRAFT}</b>（原版配方都很轻）；</li>
 *   <li><b>不认识的模组类型 → {@link #DEFAULT_MODDED}</b>：这一条是整套机制的支点。
 *       模组机器配方本身几乎不要材料，贵的是**机器与产线**（配方图里看不到机器被消耗），
 *       所以用一个明显偏高的默认步长当「产线成本」的代理指标；</li>
 *   <li><b>数据包可以覆盖任意一条</b>（{@code data/<ns>/mcstock/recipe_steps/*.json}），
 *       整合包作者按自己包的难度调，不用改代码。</li>
 * </ul>
 *
 * <p>⚠️ 本类**不参与回收价**：{@link ItemValuator#value} / 卖出侧一个字都不用它。
 * 只影响采购价里那条「劳动时间」轨道（{@link ItemValuator#laborMinutes}），
 * 以及由采购价推出来的冷却与时间锁。
 */
public final class RecipeSteps {

    /**
     * 不认识的 {@code minecraft:*} 配方的步长（分钟）。
     *
     * <p>0.25 分钟 ≈ 15 金币。取这么小是因为原版合成一次只要几秒，
     * 而 1 金币 = 1 秒劳动（见 {@link LaborTime#COINS_PER_MINUTE}）——
     * 原版那点工序不该把一根木棍抬到几十金币。
     */
    public static final double DEFAULT_MINECRAFT = 0.25;

    /**
     * 不认识的**模组**配方的步长（分钟）。
     *
     * <p>4 分钟 = 240 金币。这是整套定价里最重要的一个数字，理由见类注释：
     * 模组机器配方在材料表上几乎是零成本，真正的成本是「你得先有那台机器」。
     * 取 4 分钟意味着**每多一层机器工序，末端物品就贵 240 金币**，
     * 一条十层的加工链自然落在 2400 金币以上 —— 那个量级才挡得住「开局捡点东西换后期货」。
     */
    public static final double DEFAULT_MODDED = 4.0;

    /** 原版命名空间：模组类型与它区分开，只为了给两档不同的默认值。 */
    private static final String VANILLA_NAMESPACE = "minecraft";

    /**
     * 内置步长表（分钟）。
     *
     * <p>只列**真的会被区分**的那几种；其余走默认值。数值口径：
     * 工作台一次 0.1 分钟（6 金币，合成动作本身很快），烧炼 0.2（要烧燃料），
     * 切石 0.05（一次切一堆），锻造 0.5（下界合金那一档，本来就贵）。
     */
    private static final Map<String, Double> BUILTIN = Map.of(
            "minecraft:crafting_shaped", 0.1,
            "minecraft:crafting_shapeless", 0.1,
            "minecraft:smelting", 0.2,
            "minecraft:blasting", 0.15,
            "minecraft:smoking", 0.15,
            "minecraft:campfire_cooking", 0.4,
            "minecraft:stonecutting", 0.05,
            "minecraft:smithing_transform", 0.5
    );

    /** 数据包覆盖表（配方类型 id → 分钟）。整份替换，读方拿到的是不可变快照。 */
    private static volatile Map<ResourceLocation, Double> overrides = Map.of();

    /** 数据包覆盖的两档默认值（模组 / 原版）；没写就是类常量。 */
    private static volatile double defaultModded = DEFAULT_MODDED;
    private static volatile double defaultVanilla = DEFAULT_MINECRAFT;

    private RecipeSteps() {
    }

    /**
     * 这一步工序值多少分钟。
     *
     * @param type 配方类型 id；{@code null} / 未注册 / 查不到时按原版默认值处理
     *             （拿不到类型的基本都是原版工作台那条路，按模组算会把它们全部抬高十倍）
     */
    public static double minutes(ResourceLocation type) {
        if (type == null) {
            return defaultVanilla;
        }
        Double fromPack = overrides.get(type);
        if (fromPack != null) {
            return fromPack;
        }
        Double builtin = BUILTIN.get(type.toString());
        if (builtin != null) {
            return builtin;
        }
        return VANILLA_NAMESPACE.equals(type.getNamespace()) ? defaultVanilla : defaultModded;
    }

    /**
     * 换一份数据包覆盖（由 {@link RecipeStepRules} 在 reload 时调）。
     *
     * <p>{@code null} 当作「没有任何覆盖」，而不是保留旧的 —— 数据包被移除时价格要跟着回去。
     *
     * @param table   配方类型 id → 分钟
     * @param modded  未列出的**模组**类型用多少分钟（{@code NaN} / 负数 → 用类常量）
     * @param vanilla 未列出的**原版**类型用多少分钟（同上）
     */
    public static void override(Map<ResourceLocation, Double> table, double modded, double vanilla) {
        overrides = table == null ? Map.of() : Map.copyOf(table);
        defaultModded = Double.isFinite(modded) && modded >= 0 ? modded : DEFAULT_MODDED;
        defaultVanilla = Double.isFinite(vanilla) && vanilla >= 0 ? vanilla : DEFAULT_MINECRAFT;
    }

    /** 当前生效的覆盖表（只读快照，给 {@code /mcstock audit} 之类的诊断用）。 */
    public static Map<ResourceLocation, Double> overrides() {
        return overrides;
    }

    /** 当前生效的模组默认步长（分钟）。 */
    public static double defaultModded() {
        return defaultModded;
    }

    /** 当前生效的原版默认步长（分钟）。 */
    public static double defaultVanilla() {
        return defaultVanilla;
    }

    /** 内置表（只读，给单测与文档用）。 */
    public static Map<String, Double> builtin() {
        return BUILTIN;
    }
}
