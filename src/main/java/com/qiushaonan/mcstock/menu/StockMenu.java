package com.qiushaonan.mcstock.menu;

import com.qiushaonan.mcstock.client.StockGuiLayout;
import com.qiushaonan.mcstock.items.ModItems;
import com.qiushaonan.mcstock.market.StockService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 股市界面的容器菜单 —— 三个页面**共用**这一个菜单。
 *
 * <p>槽位一共 54 个，下标固定：
 * <pre>
 *   0 .. 17  回收栏 18 格（2×9，存在 {@link #bar()} 那个 SimpleContainer 里）
 *   18 .. 44 玩家主背包 27 格（Inventory 索引 9..35）
 *   45 .. 53 玩家快捷栏 9 格（Inventory 索引 0..8）
 * </pre>
 * 「主背包在前、快捷栏在后」是照原版 {@code InventoryMenu} 的顺序抄的 ——
 * 服务端和客户端各自用同一个构造器建菜单，**下标顺序必须逐位一致**，
 * 否则原版的槽位同步包（按 slot 下标寻址）会把物品放到错误的格子里。
 *
 * <h2>三个页面怎么共用（⚠️ 这里和最初的设想不一样，看仔细）</h2>
 * <p>需求是「切到不显示某个区域的页面时，把那些 {@code Slot#x/y} 挪到屏幕外」。
 * 但 **1.21.1 里 {@code Slot#x} / {@code Slot#y} 是 {@code public final int}**
 * （已用 {@code javap} 在 {@code neoforge-21.1.234-merged.jar} 上核实：
 * {@code public final int x; public final int y;}），字段根本赋不了值，
 * 也没有 {@code setX/setY} 之类的补丁方法。原版读坐标的地方（
 * {@code AbstractContainerScreen#renderSlot}、{@code #isHovering(Slot,...)}、
 * {@code #findSlot}）读的都是这两个字段本身，所以也没法靠「重写方法」绕过去。
 *
 * <p>因此 {@link #placeSlots(int, StockGuiLayout.Layout)} 的做法是：**把槽对象换成新坐标的新对象**，
 * 原地替换 {@code menu.slots} 里的那一项（容器与容器内下标原样保留，因此物品、同步下标、
 * 点击逻辑全都不变）。列表长度不变，所以原版那三个按长度对齐的内部数组
 * （{@code lastSlots} / {@code remoteSlots}）不会错位。
 *
 * <p>其它两条路都试过、都更糟：
 * <ul>
 *   <li>反射改 final 字段：能跑但依赖 JVM 行为，还要 {@code setAccessible}，不值当。</li>
 *   <li>切页时增删槽位（{@code addSlot} / 清空列表）：原版没有 {@code removeSlot}，
 *       自己动 {@code slots} 会让 {@code lastSlots}/{@code remoteSlots} 长度对不上，
 *       进而让槽位同步错位。</li>
 * </ul>
 * 替换槽对象只需要「下标顺序不变」这一条，正好是本类一开始就保证的。
 *
 * <p>构造函数里所有槽位的初始坐标都是 {@link #OFFSCREEN}：**只有客户端**在
 * {@code Screen#init()} 与切页时调 {@link #placeSlots} 摆正，服务端的坐标没人看。
 */
public class StockMenu extends AbstractContainerMenu {

    // ==================== 槽位数量与下标 ====================

    /** 回收栏格数（2×9）。 */
    public static final int BAR_SLOTS = 18;
    /** 玩家主背包格数（3×9），对应 Inventory 索引 9..35。 */
    public static final int INV_SLOTS = 27;
    /** 快捷栏格数，对应 Inventory 索引 0..8。 */
    public static final int HOTBAR_SLOTS = 9;
    /** 玩家背包总格数（36）。 */
    public static final int PLAYER_SLOTS = INV_SLOTS + HOTBAR_SLOTS;
    /** 本菜单槽位总数（54）。 */
    public static final int TOTAL_SLOTS = BAR_SLOTS + PLAYER_SLOTS;

    // ==================== 页号（客户端摆槽位时用） ====================

    /** 回收页：回收栏 + 玩家背包。 */
    public static final int PAGE_RECYCLE = 0;
    /** 行情页：没有任何容器槽，全部挪到屏幕外（否则会点到看不见的格子）。 */
    public static final int PAGE_MARKET = 1;
    /** 购买页：只摆玩家背包（网格不是容器槽，是点一下直接下单的区域）。 */
    public static final int PAGE_SHOP = 2;
    /**
     * 解锁页：投放区（回收栏那 18 格）+ 玩家背包。
     *
     * <p>⚠️ 这一页**复用回收栏容器**（{@link #bar()}）而不是新开一个 18 格容器：
     * 两个动作的本质都是「把东西交出去」，区别只在结算方式（换钱 / 换购买资格）。
     * 新开容器要动 {@code addSlot} 的下标对齐（见类注释里「不要增删槽位」那条），
     * 而共用容器只需要在 {@link #slotRect} 里多认一个页号 —— 风险差一个量级。
     */
    public static final int PAGE_UNLOCK = 3;

    /**
     * 「屏幕外」坐标。取 -10000 而不是 -1000：原版命中判定是
     * {@code mouseX >= slot.x - 1 && mouseX < slot.x + 17}，只要比任何可能的光标坐标都小就行，
     * 留足余量免得以后有人用「负坐标画在左边缘外」的花招时撞上。
     */
    public static final int OFFSCREEN = -10000;

    private static final StockGuiLayout.Rect OFFSCREEN_RECT = StockGuiLayout.OFFSCREEN_RECT;

    /** 不拿终端时允许离开「打开界面时的位置」多远（格）。 */
    public static final double MAX_VALID_DISTANCE = 8.0;

    // ==================== 状态 ====================

    /** 回收栏本体。主控读它、清它（{@code StockService#recycle}），所以留了 {@link #bar()}。 */
    private final SimpleContainer bar = new SimpleContainer(BAR_SLOTS);

    /** 玩家背包（重建背包槽时要用它的实例与下标）。 */
    private final Inventory playerInventory;

    /** 菜单创建者：打开界面那一刻的玩家，用来记「锚点」。 */
    private final Player owner;
    /** 打开界面时创建者所在的位置：没拿终端时，玩家离这里超过 {@link #MAX_VALID_DISTANCE} 格就关界面。 */
    private final Vec3 anchor;
    /** 打开界面时创建者所在的维度：跨维度传送了就直接算无效。 */
    private final ResourceKey<Level> anchorDimension;

    /** 客户端当前摆的是哪一页（仅供屏幕代码查询，服务端不关心）。 */
    private int page = PAGE_RECYCLE;

    /**
     * 构造菜单。
     *
     * <p>签名严格是 {@code (int, Inventory)}：{@link net.minecraft.world.inventory.MenuType.MenuSupplier}
     * 只认这一种，客户端收到「打开菜单」包时也会用它建一份本地镜像。
     */
    public StockMenu(int id, Inventory playerInv) {
        super(ModMenus.stock.get(), id);
        this.playerInventory = playerInv;
        this.owner = playerInv.player;
        this.anchor = this.owner.position();
        this.anchorDimension = this.owner.level().dimension();

        // 54 个槽位一次性建好，全部先丢到屏幕外；客户端 init()/切页时用 placeSlots 摆正。
        // 顺序 = 回收栏 → 主背包 → 快捷栏，两端各建一次就必须完全一样（见类注释）。
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            this.addSlot(this.createSlot(i, OFFSCREEN, OFFSCREEN));
        }
    }

    // ==================== 槽位摆放（只在客户端调用） ====================

    /**
     * 把 54 个槽位摆到当前页面对应的位置。
     *
     * <p><b>只在客户端调用</b>（{@code Screen#init()} 与切页时各一次）：服务端不读坐标，
     * 摆了也没用；而且只有客户端才有「当前是哪一页」这个概念 —— 页签不是容器状态，
     * 不会同步给服务端。在服务端调也不会坏，只是白忙。
     *
     * <p>⚠️ 写进去的是 {@link StockGuiLayout.Layout} 里的**屏幕绝对坐标**，
     * 而原版是按 {@code Slot#x/y + leftPos/topPos} 画的 —— 所以装着本菜单的
     * {@code AbstractContainerScreen} 必须用 {@code leftPos = topPos = 0}（整屏界面，
     * 面板坐标自己算）。以后要是换成有偏移的屏幕，得在这里把偏移减掉。
     *
     * @param page   页号：{@link #PAGE_RECYCLE} / {@link #PAGE_MARKET} / {@link #PAGE_SHOP}
     * @param layout 当前屏幕尺寸算出来的布局（{@link StockGuiLayout#compute(int, int)} 的结果）
     */
    public void placeSlots(int page, StockGuiLayout.Layout layout) {
        this.page = page;
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            StockGuiLayout.Rect rect = slotRect(page, i, layout);
            Slot current = this.slots.get(i);
            if (current.x == rect.x() && current.y == rect.y()) {
                continue;   // 位置没变就连对象都不换：窗口缩放时大部分槽位都落在这一支
            }
            // Slot#x/y 是 final，改不了 —— 只能换一个新坐标的对象顶上（见类注释）
            Slot moved = this.createSlot(i, rect.x(), rect.y());
            moved.index = i;    // addSlot 只在添加时写过一次 index，替换后必须自己补
            this.slots.set(i, moved);
        }
    }

    /** 客户端当前摆的是哪一页。 */
    public int page() {
        return this.page;
    }

    /**
     * 第 {@code index} 个槽位在 {@code page} 页上的坐标。
     *
     * <p>布局取值全部来自 {@link StockGuiLayout}（AGENTS.md 5.3：坐标只有那一份来源），
     * 这里只负责「哪一页用哪一组矩形」：
     * <ul>
     *   <li>回收页：{@code barSlot} / {@code invSlot} / {@code hotbarSlot}</li>
     *   <li>购买页：{@code shopInvSlot} / {@code shopHotbarSlot}（背包整体下移到底部）</li>
     *   <li>行情页 / 未知页：全部 {@link #OFFSCREEN_RECT}</li>
     * </ul>
     */
    private static StockGuiLayout.Rect slotRect(int page, int index, StockGuiLayout.Layout layout) {
        if (index < BAR_SLOTS) {
            // 投放区在回收页与解锁页都出现（同一个容器、同一套矩形）；摆到屏幕外时物品照样留在容器里
            return page == PAGE_RECYCLE || page == PAGE_UNLOCK
                    ? StockGuiLayout.barSlot(layout, index) : OFFSCREEN_RECT;
        }
        int playerIndex = index - BAR_SLOTS;    // 0..26 = 主背包，27..35 = 快捷栏
        if (page == PAGE_RECYCLE || page == PAGE_UNLOCK) {
            return playerIndex < INV_SLOTS
                    ? StockGuiLayout.invSlot(layout, playerIndex)
                    : StockGuiLayout.hotbarSlot(layout, playerIndex - INV_SLOTS);
        }
        if (page == PAGE_SHOP) {
            return playerIndex < INV_SLOTS
                    ? StockGuiLayout.shopInvSlot(layout, playerIndex)
                    : StockGuiLayout.shopHotbarSlot(layout, playerIndex - INV_SLOTS);
        }
        return OFFSCREEN_RECT;
    }

    /**
     * 按下标造槽位：回收栏用 {@link BarSlot}（多一条「不收终端」的规则），其余是原版 {@link Slot}。
     *
     * <p>构造时和 {@link #placeSlots} 换坐标时都走这一个入口 ——
     * 两处要是各写一份，早晚会出现「切页以后回收栏少了那条规则」这种 bug。
     */
    private Slot createSlot(int index, int x, int y) {
        if (index < BAR_SLOTS) {
            return new BarSlot(this.bar, index, x, y);
        }
        int playerIndex = index - BAR_SLOTS;
        int inventoryIndex = playerIndex < INV_SLOTS ? playerIndex + 9 : playerIndex - INV_SLOTS;
        return new Slot(this.playerInventory, inventoryIndex, x, y);
    }

    // ==================== 对外读写 ====================

    /** 回收栏容器：主控靠它读内容 / 清空（回收成功后应该清掉已经换成金币的那些格子）。 */
    public SimpleContainer bar() {
        return this.bar;
    }

    // ==================== AbstractContainerMenu 要求实现的部分 ====================

    /**
     * 背包 ↔ 回收栏之间搬东西（shift + 左键）。下标约定：
     * 回收栏 0..17、玩家背包 18..53。
     *
     * <p>结构照抄原版 {@code ChestMenu#quickMoveStack}（含最后那句
     * {@code setByPlayer(EMPTY)} / {@code setChanged()}）——不自己发明搬运规则，
     * 原版这套已经处理了合并同类、反向优先填快捷栏等细节。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;     // 理论上不会发生，防一手越界
        }
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack inSlot = slot.getItem();
            moved = inSlot.copy();
            if (index < BAR_SLOTS) {
                // 回收栏 → 背包：reverseDirection = true，原版顺序是先往快捷栏塞
                if (!this.moveItemStackTo(inSlot, BAR_SLOTS, TOTAL_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(inSlot, 0, BAR_SLOTS, false)) {
                // 背包 → 回收栏：塞不进去就原样不动。两种原因：
                //   ① 栏满了（原版行为，物品留在背包里是对的）；
                //   ② **被 BarSlot 的规则挡下**（终端 / 有耐久的物品）。
                // ② 必须给玩家一句话 —— 否则「shift 点了没反应」看着像 bug。
                // 这里按「栏里还有空格」区分：还有空格却进不去，就只能是规则挡的
                // （原版格子不可能拒收一件无耐久的普通物品）。
                // ⚠️ 别在这里重写判据，只做「是不是规则挡的」这个判断；
                //    真正的判据始终只有 RecycleRules 那一份。
                this.feedbackRejected(player, inSlot);
                return ItemStack.EMPTY;
            }

            if (inSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return moved;
    }

    /**
     * shift 搬运被回收栏规则挡下时，给玩家一句话（动作栏，不进聊天）。
     *
     * <p>只在**确实还有空格**的情况下才提示：没空格说明只是栏满了，
     * 那时原版什么都不说才是对的（玩家自己也看得见栏是满的）。
     *
     * <p>⚠️ 提示只覆盖 **shift 路径**（最常见、也最容易误伤的一条）。
     * 鼠标拖动 / 手动点击被拒时原版会自己播一声「不行」的音效、物品也不动，
     * 这里不重复造一套（那两条路径在原版里没有统一的钩子，硬加会在 {@code mayPlace}
     * 里做副作用，那是被原版反复警告过的写法）。
     */
    private void feedbackRejected(Player player, ItemStack rejected) {
        if (player.level().isClientSide() || !(player instanceof ServerPlayer server)) {
            return;
        }
        if (!hasEmptyBarSlot()) {
            return;     // 栏真的满了：原版静默是对的
        }
        String key = rejected.getMaxDamage() > 0
                ? "message.mcstock.recycle_reject_durable"
                : "message.mcstock.recycle_reject_terminal";
        server.displayClientMessage(Component.translatable(key), true);
    }

    /** 回收栏里还有没有空格（提示要不要发出去靠它区分「栏满」和「被规则挡下」）。 */
    private boolean hasEmptyBarSlot() {
        for (int i = 0; i < BAR_SLOTS; i++) {
            if (this.slots.get(i).getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 界面关闭：把回收栏里剩下的东西还给玩家。
     *
     * <p>用原版 {@link AbstractContainerMenu#clearContainer(Player, Container)}：
     * 活着且在线时塞回背包（{@code placeItemBackInInventory}），
     * 死了 / 掉线时原地掉出来。它内部对「客户端」有守卫（只清服务端那份），
     * 所以两端都会走这个方法、但只有服务端真的动东西 —— 和原版 {@code CraftingMenu} 一样。
     *
     * <p>⚠️ 别把这段挪进 {@code StockService}：玩家直接关窗口（没点回收按钮）时，
     * 服务端只有这里有机会把东西还给他。
     */
    @Override
    public void removed(Player player) {
        super.removed(player);      // 先把光标上拿着的那份还给玩家
        this.clearContainer(player, this.bar);
        // 收尾交给服务端（StockService#onMenuClosed）：**必须放在还完东西之后**，
        // 这样它顺手推的那份快照才是「回收栏已清空」的状态。只在服务端调一次。
        if (player instanceof ServerPlayer serverPlayer) {
            StockService.onMenuClosed(serverPlayer);
        }
    }

    /**
     * 界面还该不该开着。
     *
     * <p>前提是「问的人就是创建者本人」，在此之上满足任一条就算有效：
     * <ol>
     *   <li><b>主手或副手拿着交易所终端</b> —— 手里这个终端就是「界面还有意义」的直接证据；
     *       拿着它走多远都行（自己家里的行情屏，人走开也合理）。</li>
     *   <li><b>没拿终端，但人还在打开界面时那一带</b> —— 同一个维度、离
     *       {@link #anchor}（创建者打开界面时的位置）不到 {@link #MAX_VALID_DISTANCE} 格。
     *       终端收进背包 / 放进回收栏时还能再看两眼，但传送走了、跨维度了就把界面关掉。</li>
     * </ol>
     *
     * <p>为什么记「打开时的位置」而不是「创建者当前位置」：{@code stillValid} 的
     * {@code player} 就是菜单所有者本人，拿他的当前位置和创建者比距离恒等于 0，
     * 那条规则就等于永远成立、白写。真正想表达的约束是「别把这个界面随身带走」。
     */
    @Override
    public boolean stillValid(Player player) {
        // 本菜单只属于打开它的那个人（两端传进来的都是本人，这里只是把语义写死：
        // 以后真要做「帮别人开界面」时，这条会先拦住，而不是悄悄共享一个回收栏）
        if (player != this.owner && !player.getUUID().equals(this.owner.getUUID())) {
            return false;
        }
        Item terminal = ModItems.stock_terminal.get();
        if (player.getMainHandItem().is(terminal) || player.getOffhandItem().is(terminal)) {
            return true;
        }
        return player.level().dimension().equals(this.anchorDimension)
                && player.distanceToSqr(this.anchor) < MAX_VALID_DISTANCE * MAX_VALID_DISTANCE;
    }

    // ==================== 内部槽位类型 ====================

    /**
     * 回收栏的格子：拒收**交易所终端本身**与**任何有耐久的物品**。
     *
     * <p>为什么拒收终端：回收栏是「把东西换成金币」的入口，而终端是打开这个界面的钥匙
     * （{@link #stillValid} 第 1 条）。把钥匙本身丢进去换几个金币，玩家手里就没界面了；
     * shift + 左键整理背包时尤其容易误伤（一整排物品连点，终端正好在快捷栏第一个）。
     *
     * <p>为什么拒收**有耐久**的：玩家实测「一把已经掉耐久的下界合金剑 + 镐子放进回收栏，
     * 被按**满价 2964.00** 收购」—— 规则不一致（负债自动还债那条早就拒收有耐久的），
     * 而且等于白用工具（用旧了再按新价卖回去）。判据只看「这个物品类型有没有耐久条」，
     * **全新的和用掉一半的一律不收**。
     *
     * <p>⚠️ 两条判据都在 {@link RecycleRules#accepts} 里，**与
     * {@code StockService#sellableWhileInDebt} 共用同一个口径** —— 别再各写一份。
     *
     * <p>{@code mayPlace} 是原版的放置钩子，所有放置路径都会问它，所以在这里挡一次就够：
     * <ul>
     *   <li><b>手动点击 / 拖拽</b>：{@code AbstractContainerMenu#doClick} 对
     *       {@code PICKUP} / {@code QUICK_CRAFT}（拖拽批量放置）走
     *       {@code Slot#safeInsert}，而 {@code safeInsert} 第一件事就是问 {@code mayPlace}
     *       （字节码里 {@code safeInsert(ItemStack,int)} 偏移 9 处就是
     *       {@code invokevirtual Slot.mayPlace}）；</li>
     *   <li><b>shift 搬运</b>：{@code quickMoveStack} → {@code moveItemStackTo} →
     *       {@code safeInsert}，同一道闸（{@code moveItemStackTo} 自己的字节码里也直接调了
     *       {@code mayPlace}，偏移 268）。</li>
     * </ul>
     * ⚠️ 它**只**挡这两类，其余（材料 / 方块 / 不值钱的杂物）照收 ——
     * 「值不值钱」由主控那边的估价表决定，这里不做价值筛选。
     */
    private static final class BarSlot extends Slot {
        BarSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // ⚠️ 手动回收那条判据（**未使用过就能卖**，用过的才不收）——
            //    和自动还债那条**故意不同**，理由见 RecycleRules 的类注释。
            // ⚠️ 两个取值都只能问 ItemStack：1.21.1 里耐久与损伤都是数据组件，
            //    Item 上没有 getMaxDamage() / getDamageValue()。
            boolean isTerminal = stack.is(ModItems.stock_terminal.get());
            int maxDamage = stack.getMaxDamage();
            int damage = stack.getDamageValue();
            return RecycleRules.acceptsManual(maxDamage, damage, isTerminal);
        }
    }
}
