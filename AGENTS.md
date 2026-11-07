# AGENTS.md

本文件供在此仓库中工作的 AI 编码代理阅读。这是个 **NeoForge 1.21.1 模组**（MC 股市 / mcstock）：
先理解「一套随窗口变化的 GUI + 一套服务端权威的交易结算」，再按下面的注册方式、命名方式和资源布局动手。

## 1. 项目概览

- 模组 ID：`mcstock`，显示名「MC 股市」，包名 `com.qiushaonan.mcstock`
- Minecraft `1.21.1` / NeoForge `21.1.234` / Java `21` / Parchment `2024.11.17`
- 构建：Gradle（wrapper 9.2.1）+ ModDevGradle `2.0.141`
- **不依赖任何第三方模组**；单测用 JUnit 5（Maven Central）

版本号集中在 `gradle.properties`，`build.gradle` 把它们展开进
`src/main/templates/META-INF/neoforge.mods.toml`。改版本改 `gradle.properties`。

## 2. 常用命令

```powershell
.\gradlew.bat build         # 必须通过（编译 + 单测）
.\gradlew.bat clean build   # 发版前的完整校验（确保没有增量残留）
.\gradlew.bat runClient     # 本地验证玩法
.\gradlew.bat runServer

# GUI 相关（改布局/换贴图后必跑，见第 6 节第 22 条）
python tools\gen_gui_spec.py        # docs/gui_spec.md（给美术的规格书，跑真实布局代码生成）
python tools\gen_gui_textures.py    # 占位图集 / 物品图标 / logo / docs/gui_atlas_map.png
python tools\gen_gui_mockup.py      # 三张效果图（可传屏幕尺寸与评审标签）
                                    #   gen_gui_mockup.py 640 360 v4 → 另存 docs/review/v4_*.png
python tools\layout_dump.py 640 360 # 打印某个屏幕尺寸下的布局矩形（排查用）
python tools\check_lang_keys.py     # lang 键差集必须为空（退出码 0）
python tools\check_item_values.py   # 估值表每个键都存在（物品 / **物品**标签；退出码 0）
```

游戏内调试命令（权限 2，`command/McStockCommand.java`）：

```text
/mcstock money [set|add <金额>]    余额 / 净值；set 直接设、add 加钱（金币，允许两位小数）
/mcstock price [set <股票> <价格>] 三只股票现价与涨跌；set 直接设价
/mcstock advance                   立刻跳一次价（不用等 40 tick）
/mcstock value [hand|held]         手持物品的估值 / 采购价 / 来源（种子值？标签值？推导值？）
/mcstock positions                 自己的全部仓位（id / 股票 / 多空 / 股数 / 均价 / 保证金 / 浮盈）
```

## 3. 目录结构

```text
src/main/java/com/qiushaonan/mcstock/
  McStock.java               # @Mod 入口：只负责串起各 holder 的 reg()
  ModCreativeTabs.java
  ModAttachments.java        # 玩家附件：现金（分）+ 持仓快照
  StockEvents.java           # ★ 事件装配：行情 tick / 登录推目录 / 重建估值表 / 注册 /mcstock
  command/McStockCommand.java # ★ 调试命令（权限 2）：money / price / advance / value / positions
  items/ModItems.java
  items/StockTerminalItem.java
  menu/ModMenus.java
  menu/StockMenu.java        # ★ 三页共用的容器菜单（54 槽 + 切页替换 Slot 对象）
  network/ModNetwork.java    # ★ 协议注册 + 版本号（PROTOCOL_VERSION）
  network/C2S*.java / S2C*.java
  market/StockDefs.java      # ★ 全部可调数值（纯 Java，单测直接用）
  market/MarketEngine.java   # ★ 行情引擎：跳价 / 大盘状态 / 冲击 / 新闻流 / 阴阳柱绝对分箱 / 读档重放
  market/MarketRegime.java   # ★ 大盘状态机（平静 / 牛市 / 熊市 / 高波动 + 转移表 + 持续跳数）
  market/StockEventTable.java# ★ 事件表（id / 类型 / 方向 / 幅度 / 半衰期 / σ倍率 / 影响映射 / 传闻澄清）
  market/MarketShock.java    # ★ 冲击的生命周期数学（急动 + 衰减拖尾，纯函数、可直接单测）
  market/MarketNews.java     # ★ 一条新闻（语言键 + 类型 + 影响 + 触发跳数 + 是否已澄清）
  market/EventType.java      # 新闻类型枚举（公司 / 行业 / 宏观 / 传闻 / 大盘）
  market/DebtService.java    # ★ 负债时自动卖回收物还债（纯逻辑，谓词由调用方注入）
  market/PositionMath.java   # ★ 仓位数学（纯函数，两端共用）
  market/Portfolio.java / Position.java / PortfolioSnapshot.java
  market/TradeService.java   # ★ 开仓 / 平仓 / 逐仓强平（纯函数；现金允许为负）
  market/ItemValuator.java   # ★ 估值表（种子 + 标签 + 配方推导）与来源查询
  market/ItemValueRules.java / MinecraftRecipeGraph.java / RecipeGraph.java
  market/StockCatalog.java   # 展示层：语言键 / 图标 / 风险档 / indexOf
  market/StockService.java   # ★ 服务端业务总入口（开界面 / 下单 / 回收 / 购买 / 自动还债 / 推快照）
  market/StockMarketData.java# 全服行情存档（主世界 SavedData）
  client/StockGuiLayout.java # ★ 布局引擎（比例 + 锚点 + compute()）与图集分区定义
  client/StockTheme.java     # ★ 颜色常量
  client/StockScreen.java    # ★ 三个页面 + 帮助覆盖层 + 持仓表格（唯一那个大文件）
  client/StockWidgets.java   # 控件与贴图拼装原语（9 段拉伸、小号数字字体、StockButton）
  client/StockClientState.java / ClientPacketSink.java / McStockClient.java / StockTerminalInput.java
src/main/templates/META-INF/neoforge.mods.toml   # 元数据模板（中文显示名写在这里）
src/main/resources/assets/mcstock/               # 语言 / 模型 / 贴图 / font/small.json + small.png
src/main/resources/data/mcstock/                 # 配方、物品估值表（mcstock/item_values/core.json）
src/test/java/com/qiushaonan/mcstock/            # ★ JUnit5 单测（纯 Java，不启动游戏）
tools/                       # ★ 从 Java 布局代码生成文档 / 贴图 / 效果图的脚本
tools/java/LayoutDump.java   # ★ 把 compute() 结果 dump 成 JSON（用 javac 单独编译，不参与构建）
docs/                        # 生成的规格书与效果图；docs/INSTALL.md 是玩家/服主安装说明
```

## 4. 界面结构（四个页面，页签切换）

| 页面 | 内容 | 有容器槽吗 |
|---|---|---|
| **回收** | 18 格回收栏（2×9）+ 合计块 + 回收按钮 + 回收明细 + 玩家背包 27 格 + 快捷栏 9 格 | **有**（唯一有槽的页面） |
| **行情** | 左列企业卡片（**只有图标 + 名称**）+ 卡片下的大盘小结 / 中间阴阳柱大屏（表头行右边是**新闻条**）/ 右列「买入 · 卖出 · 数量栏 · 杠杆栏 · 资金情况」 | 无（纯展示 + 按钮） |
| **购买** | 分类 + 搜索 + 翻页 + 自适应网格（**点格子直接买**：左键 1 组、右键 1 个）+ 底部玩家背包 | **有**（背包 27 格 + 快捷栏 9 格） |

### 4.1 窗口是「占屏幕 88%」而不是固定像素

- 面板 = `屏幕逻辑尺寸 × PANEL_W_RATIO/PANEL_H_RATIO`（各 0.88），夹在 `320×200 ~ 720×440`，
  并至少离屏幕边缘 6px；面板居中。上下限是必要的：GUI 缩放 1 时逻辑分辨率有 1920×1080，
  按比例算出来会是一块巨大的空板子，而字号与槽位不会跟着变大。
- 所有矩形由 `StockGuiLayout.compute(screenW, screenH)` 生成，返回不可变的 `Layout`。
  客户端在 `Screen#init()` 里调用（开界面 + 窗口缩放都会走那里），
  再按 `Layout.barSlots()` / `invSlots()` / `gridCell()` 摆容器槽的 `Slot#x/y`。
- **因为尺寸可变，贴图纸里没有「整窗底图」**：面板是 9 张 8×8 边框切片拼的，
  槽位/卡片/按钮/页签都是可拼可拉的小块（见 `docs/gui_spec.md` 第 4 节）。

### 4.2 「数量」「杠杆」是什么意思（UI 上必须讲清楚）

- **数量** = **每次点击「买入 / 卖出」成交多少股**，不是手数、也不是金额。
  右列是一个**填写栏（EditBox）+ ▲▼ + MAX**：手动输入 1~100000，▲▼ 按 ×10 档进（1/10/100/…），
  MAX = 按当前余额与杠杆能吃下的最大整数股数。
- **杠杆** = 填写栏 + ▲▼（按 1→2→5→10→20→50→100→200→400 的档位跳）+ MAX（= 400），范围 1~400。
- 仓位价值 = 股数 × 现价；**保证金 = 仓位价值 ÷ 杠杆**（开仓锁定、不随价格变化）；
  **手续费 = 仓位价值 × 0.20%，开仓平仓各一次（往返 0.40%）**。
- 所以「数量 1 + 杠杆 400」= 仓位 100.00 → 押金 0.25 → 手续费 0.20（手续费是押金的 80% —— 高杠杆来回做仍然必亏）。行情页右列的**资金情况**块
  （余额 / 净值 / 本单 / 需付 / 可开 / 持仓 / 爆仓）就是为这件事存在的，**别把这块挪走**。
- 填写栏的校验在服务端再做一遍（`QTY_MIN/QTY_MAX`、`LEVERAGE_MIN/LEVERAGE_MAX`），客户端夹一次只是省流量。

### 4.3 阴阳柱（K 线）

- 每根 = `CANDLE_TICKS = 5` 跳（2 秒一跳 → **每根 10 秒**）；图上放几根由宽度算：
  `chartPlot.w / CANDLE_PX`，夹在 20~60 之间（所以引擎要留 `PRICE_HISTORY_TICKS = 300` 跳的原始价）。
- 颜色按 **A 股习惯：红涨绿跌**（收盘 ≥ 开盘 = 阳线红）。
- 图上叠两条虚线：**现价**（最后一根收盘价，颜色跟这根柱）与**爆仓价**（红色，取**离爆仓最近的那个仓**，
  见 `StockClientState.nearestLiquidation`：同一只股票可能挂着好几个仓，爆仓价各不相同）；
  两个价格标签离得太近（< 14px）时只画现价标签，另一个改用图下文字表达。

### 4.5 柔和观感：圆角 + 低对比配色

- 圆角半径在 `StockGuiLayout.RADIUS_PANEL/RADIUS_BOX/RADIUS_SLOT`（6/4/3），
  **圆角本身是贴图的透明像素**，代码只负责把切片摆对位置 —— 改半径必须同时重跑 `gen_gui_textures.py`。
- 面板四角切片**只圆外侧那个角**：把圆角矩形画出边界再裁掉，另外三个角保持直角，
  所以任意宽高拼出来都是圆角窗口（实现见 `gen_gui_textures.rounded_tile`）。
- 按钮/页签/填写栏的圆角半径 = 左右不拉伸的 4px，拉伸中间段不会破坏圆角。
- 配色走低对比：`COLOR_PANEL_FILL` 比纯黑浅一档、`COLOR_BORDER` 只比底色深一点、
  `COLOR_UP/COLOR_DOWN` 降饱和、`COLOR_TEXT_SHADOW` 用深灰而不是纯黑。
- **写死颜色的地方只有 `StockTheme`**：屏幕绘制代码一律用 `StockTheme` 常量，
  不要在绘制里写字面量颜色。

### 4.6 字体：**已决定保持原版**（别自作主张换）

MC 的字体是**全局**的：`GuiGraphics#drawString` 用的 `Minecraft#font` 就是 `minecraft:default`，
中文来自 Unifont（点阵）。模组没法只给「自己这个界面」换字体。
评审时已经确认走「**保持原版字体，柔和感只靠圆角 + 配色 + 留白**」，所以：

- 不要在模组里塞字体 TTF，也不要覆盖 `assets/minecraft/font/default.json`。
- **效果图里的字必须是接近点阵的观感**（`tools/pixel_font.py` 用阈值 115 渲染），
  别改成抗锯齿的平滑字 —— 那会让人误以为游戏里是圆体。
- 如果以后要变：三条路分别是「自绘 ASCII 位图只覆盖数字（全游戏数字变圆，影响所有界面）」、
  「覆盖 `minecraft:default` 并 reference 回原版（中文不变）」、
  「打包中文 TTF（模组胖 10~20 MB，要确认授权）」。

占位/效果图里用的是系统字体（微软雅黑 + 阈值），**只影响 `docs/` 里的图**，和游戏里无关。

### 4.7 两个带容器槽的页面对应同一个容器菜单（阶段 3 注意）

回收页有 18 个回收栏槽 + 36 个背包槽，购买页有 36 个背包槽（网格不是容器槽，是点一下直接下单的区域），
行情页没有槽。实现时用**同一个 `AbstractContainerMenu`**，切到不显示某个区域的页面时，
把那些 `Slot` 的 x/y 挪到屏幕外（例如 `-10000`），切回来再摆正——
否则玩家会在行情页点到**看不见的槽位**（原版按 `Slot#x/y` 判定点击命中）。
窗口缩放时也要在 `init()` 里重新摆一遍槽位坐标。

## 5. 硬性约定

### 5.1 注册
所有注册走 `DeferredRegister`，每个类型一个 holder 类并提供 `reg(IEventBus)`，在主类构造里统一调用。
字段名与资源 ID 一致，用 `snake_case`。

### 5.2 服务端权威
价格、余额、持仓、保证金、估值**全部在服务端算**，客户端只能发「我点了哪个按钮」。
任何新玩法逻辑都要先问：「客户端能不能伪造这个？」

### 5.3 布局不许写死坐标
`StockGuiLayout.java` 是**唯一**的布局来源。绘制代码只能：
1. 调 `compute(screenW, screenH)` 拿 `Layout`；
2. 用 `Layout` 里的矩形和 `StockGuiLayout.card(l, i)` / `barSlot(l, i)` / `invSlot(l, i)` /
   `hotbarSlot(l, i)` / `gridCell(l, col, row)` 这些取值方法；
3. 需要新元素就**在 `compute()` 里加一个矩形并加进 `Layout` record**，同时到
   `tools/java/LayoutDump.java` 里加一行 dump —— 否则文档和效果图看不到它。

### 5.4 资源与语言
资源命名空间固定 `mcstock`；新增物品 / GUI 文案必须同时补
`assets/mcstock/lang/zh_cn.json` 与 `en_us.json`，键格式：

```text
item.mcstock.<id>        gui.mcstock.<...>        message.mcstock.<...>
```

### 5.5 数据模型：**独立仓位列表**（2026-10-05 重构，别改回「每股票一个净仓」）

玩家原话：「买入卖出都是新增仓位」。所以：

- `Portfolio` 不再是三个定长数组，而是 `List<Position>`（`market/Position.java`，纯 Java record）：
  `id`（玩家内自增、只增不减）/ `stock` / `shares`（**有符号**，正多负空）/ `entryCents` /
  `marginCents` / `leverage`（仅显示，旧存档迁移来的记 0）。
- **买入 = 新开多仓、卖出 = 新开空仓，永不自动对冲、永不合并**；同一只股票可以同时挂多仓和空仓，
  每个仓有自己的均价、保证金、爆仓价。一个仓开出来就不再变（加仓是再开一个仓，减仓是平掉一个仓）。
- 结算入口只有两个（`TradeService`）：`open(cash, portfolio, stock, price, qty, leverage, isLong)` 与
  `close(cash, portfolio, positionId, price)`。**没有** `trade(...)` 那种「反向下单先平仓」的入口。
  口径没变：手续费 = 仓位价值 × 0.20%（2026-10-06 由 0.1% 调紧到 0.35%、同日回调到 0.20%）、保证金 = 仓位价值 ÷ 杠杆，只是**按仓位算**。
- **爆仓逐仓判定**：`PositionMath.liquidated(shares, entry, margin, price)` 逐仓调用，
  到线就把那**一个**仓按真实价格结算掉（可以亏穿它自己的保证金，现金下限 0）。
- 净值口径不变：`现金 + Σ(各仓保证金 + 各仓浮盈)`。
- 上限 `Portfolio.MAX_POSITIONS = 32`：开满再开会被拒（`Status.FULL`）。
  **别偷偷放宽**——客户端列表要能平掉每一个仓，服务端多留几个「看不见也平不掉」的仓比直接拒绝更糟。
- 存盘：`market/PortfolioSnapshot.java`（纯 Java + Codec）。`Codec.either(新格式, 旧格式)`：
  `positions` 字段是必填，缺了就说明是旧存档，退回三元组形状并用 `Portfolio.fromLegacy` 迁移
  （每个非空旧仓 → 一个新仓，id 从 1 递增）。**这条必须有**，否则老玩家一进游戏仓位全没。
  单测见 `PortfolioSnapshotTest`。
- 协议 **v2**（`ModNetwork.PROTOCOL_VERSION = "2"`）：`S2CStockSnapshot` 里那五个「每股票一个数」的
  数组换成五个「每仓一个数」的等长数组（`posId/posStock/posShares/posEntry/posMargin`），
  **浮盈与爆仓价不发**——客户端用现价 + `PositionMath` 自己算（两边同一份纯函数）。
  杠杆也不发，客户端用 `Position.deriveLeverage`（均价 × 股数 ÷ 保证金）反推。
  新增 `C2SClosePosition(positionId)` 平**某一个**仓；`C2STradeStock` 的 `qty <= 0` 仍是
  「平掉这只股票的全部仓」。

### 5.6 持仓表格与小号数字字体（**走势图下方**，横跨图宽）

- **位置**：持仓列表在**走势图正下方**（不是右列底部），宽度 = 走势图宽度；
  右列只剩「买入 / 卖出 / 数量 / 杠杆 / 信息块（占满剩余整条高度）」。
  图下原来那两行持仓摘要（`chartFooter1/2`）**已删除**（信息并入表格）。
- 布局全在 `StockGuiLayout`：`posList` / `posHeader` / `posPage` / `posRow(i)` /
  `posCol(col, row)` / `posCloseBtn(i)` / `posScrollTrack()` / `posScrollThumb(first, total)` /
  `posVisibleRows()`；中间列按「表格高度 = clamp(内容高/3, 表头+1 行, 表头+6 行) →
  走势图至少 `CHART_MIN_H`」切分，表格**钉在内容区底部**。
- **一行一条横向表格**（`POS_ROW_H = 16`，表头 `POS_TITLE_H = 11`），列为
  `方向 | 数量 | 倍数 | 盈亏 | 距爆仓 | [平仓]`：中文列名走常规字号、数字列走小号点阵字体且
  **右对齐**；行尾是 `22×12` 的「平仓」按钮（**一个仓一个按钮**，点谁平谁）。
  表头**没有**「持仓」标题（它和第一列「方向」同格，会叠在一起）。
- 右缘一条竖向滚动条（轨道 `POS_SCROLLBAR_W = 3`，滑块可拖，见 `mouseDragged`）。
- **行控件是动态的**：一屏几个仓就建几个按钮，增删走 `containerTick()` 里的
  **`posRebuildPending` 标志位**（⚠️ **不要**用 `defer(this::rebuildPositionWidgets)`：
  `defer` 是单槽的 `pendingAction`，同一 tick 里后续任何一次 defer 都会把这次重建顶掉，
  于是删掉的按钮永远回收不了 —— 实机表现是「表格已空、右上角还留着一个『平』按钮」）。
  滚轮一格 = 一屏，只吃 `posList` 区域里的事件。
- **数字走小号点阵字体**（`assets/mcstock/font/small.json` + `textures/font/small.png`，
  由 `tools/gen_small_font.py` 生成；`ascent = height = 格高` → 墨迹底边正好落在 `y+7`，
  与原版数字同基线；`height = 格高` → scale 1 → **1:1 不缩放**）。用法：
  `StockWidgets.small(String)` / `smallText(...)` / `Piece`（混排：中文常规字号 + 数字小号）。
- ⚠️ **中文绝不能套 `mcstock:small`**：MC 在指定字体里找不到字形时画的是缺字方块
  （`FontSet#getGlyphInfo → SpecialGlyphs.MISSING`），**不会**回退默认字体。
- ⚠️ **别用 `pose().scale(0.75f)` 缩中文**：MC 文字是图集 + UV，缩小 = UV 采样，点阵必然掉笔画。
  中文保持原尺寸，密度只靠「数字换小号字体 + 两行排布」压。
- `Style#withFont` 在 1.21.1 的 `drawString(Font, Component, ...)` 路径上**是生效的**
  （渲染见 `Font.StringRenderOutput#accept → getFontSet(style.getFont())`；
  `Font#width(FormattedText)` 也按 Style 里的字体量宽 —— 混排的宽度不用自己维护表）。
  字体没加载出来时 `FontManager#getFontSetRaw` 会退到 `missing` 字体（满屏方块），
  所以 `SmallFontAssetTest` 拿同一套算法把 json 与图集对上。

### 5.7 负债：现金允许为负 + 负债时自动卖回收物还债（2026-10-06）

- **现金可以是负数**，而且**没有负债上限**。全项目**三处** clamp 必须一起去掉
  （只去掉一处的话负现金会被下一次结算悄悄抹平，自动还债永远等不到 `cash < 0`）：
  `StockService#setCash` 的 `Math.max(0L, cents)`、`settleLiquidations` 里的
  `cash = Math.max(0L, cash + delta)`、`TradeService#close` 里的
  `Math.max(0L, cashCents + released + realized - fee)`。
- **负债时仍然开不了新仓**：`TradeService#open` 的 `cash >= need` 判定一个字没动
  （「余额不够 → 拒绝下单」是既有口径，不要顺手放宽）。
- 玩法：`StockEvents#onServerTick` 里每 `StockDefs.DEBT_CHECK_TICKS`（20 tick = 1 秒）
  调一次 `StockService#autoSellToRepayAll`；**只在 `cash < 0` 时工作**，还清立刻停。
- 卖什么（红线）：遍历**背包 36 格 + 副手**（`StockService#BACKPACK_SLOTS`，唯一的槽位口径），
  `ItemValuator.value > 0`（估价即回收价）、
  **`getMaxDamage() == 0`（有耐久的一律不卖）**、排除交易所终端；回收栏里的东西不碰。
  - ⚠️⚠️ **两条判据故意不同，别再想着"统一成一个"**（`menu/RecycleRules`，三次调整的最终形态）：
    | 场景 | 判据 | 规则 |
    |---|---|---|
    | 手动回收（`StockMenu.BarSlot#mayPlace`） | `acceptsManual(maxDamage, damage, isTerminal)` | **未使用过**（`getDamageValue() == 0`）的装备**可以卖** —— 一把新剑本身就等于一堆材料；**用过的**（`> 0`）不收 |
    | 自动还债（`StockService#sellableWhileInDebt`） | `acceptsAutoSell(maxDamage, isTerminal)` | **任何有耐久的都不卖**（含全新）—— 未经玩家同意的变卖，宁可少还债 |
    共同底线是「**用过的不能按新价卖**」。历史：① 最早自动拒收、回收栏照收 → 玩家把掉耐久的
    下界合金剑按**满价 2964.00** 卖了；② 统一成「有耐久的都不收」→ **全新的**剑/镐也进不去
    （玩家：「未使用过的武器装备无法回收」）；③ 现在按「谁在卖」分开。
  - ⚠️ 1.21.1 的最大耐久与损伤都是**数据组件**：`Item` 上**没有**
    `getMaxDamage()` / `getDamageValue()`，只能问 `ItemStack`
    （回收栏那边 `stack.getMaxDamage()` / `stack.getDamageValue()`，
    这边判「类型」用 `item.getDefaultInstance().getMaxDamage()`）。
    实测实现（`javap -c`）：`getMaxDamage() → getOrDefault(MAX_DAMAGE, 0)`、
    `getDamageValue() → clamp(getOrDefault(DAMAGE, 0), 0, getMaxDamage())` ——
    **缺组件时默认 0**，所以「全新的东西 damage == 0」是可靠判据。
  - ⚠️ 回收栏的放置钩子是 `Slot#mayPlace`，**所有放置路径都会问它**：
    手动点击/拖拽走 `AbstractContainerMenu#doClick → Slot#safeInsert`（`safeInsert` 头一件事就是
    调 `mayPlace`），shift 搬运走 `quickMoveStack → moveItemStackTo → safeInsert`，
    所以在那一个方法里挡一次就够，**不要去改 `quickMoveStack` 做筛选**
    （那边只加了一句「被规则挡下时给玩家一句话」的反馈，判据不重复）。
  - ⚠️ 不能用 `Inventory#getContainerSize()`（= 41）：后 5 格是**盔甲**，
    那样会把玩家身上穿的胸甲当库存卖掉。用 `Inventory.INVENTORY_SIZE`（36）+
    `Inventory.SLOT_OFFHAND`。**以前的「回收时同类一起卖」也踩过同一个坑**（见第 31 条）；
    那个功能 2026-10-06 已按玩家要求**取消**，所以现在只剩这条负债路径在用
    `StockService#BACKPACK_SLOTS`（回收**只看回收栏那 18 格**，不再看背包）。
- 纯逻辑在 `market/DebtService#sellToRepay(List<Entry<K>>, long, ToIntFunction<K>, Predicate<K>)`：
  泛型 `K` 是「物品标识」（服务端传 `Item`、单测传 `String`），
  **不 import 任何 Minecraft 类**；只卖「刚好够还债」的件数（欠 12.00、单价 5.00 卖 3 件），
  估价是金币、现金是分（换算只在它里面做一次）。
- 提示两条：`message.mcstock.debt.sold`（每轮卖完推一条，含件数 / 金额 / 剩余债务）与
  `message.mcstock.debt.cleared`（债务刚还清那一下，**手动回收还清也会推**——
  靠 `StockService` 里一个「上一秒是否在负债」的 UUID 集合判断）。
- UI：标题栏「余额 / 净值」为负时用 `StockTheme.COLOR_LIQUIDATION`（红）并带负号
  （两段各自判色：可能现金为负、净值被浮盈顶成正数），资金块里的「净值」那一行同款。

### 5.8 行情事件：大盘状态 + 生命周期冲击 + 新闻条（2026-10-06）

- **三层结构**（全部纯 Java，单测直接跑）：
  1. `MarketRegime`：平静 / 牛市 / 熊市 / 高波动，各带 `bias`（每跳基础漂移）、
     `volMultiplier`（σ 倍率）、持续跳数区间（`minJumps~maxJumps`，1 跳 = 2 秒）。
     进入状态时**抽一个持续跳数**，到点再按 `TRANSITIONS` 转移表抽下一个（行和必须 = 1）。
     对个股的影响是 `drift += bias × BETA[stock]`（`StockDefs.BETA = {0.7, 1.0, 1.5}`）。
  2. `StockEventTable`：每只股票 6 条具名事件（5 常规 + 1 传闻），
     字段 = `id / 语言键 / 类型 / 方向 / 幅度(σ) / 半衰期(跳) / σ倍率 / 影响映射 / 传闻澄清参数`。
     **影响映射的权重可以是负数**（反向外溢：传送门拥堵利空下界贸易、利好钻石矿业；
     烈焰棒短缺利好贸易商、利空要用烈焰粉的红石科技），
     幅度按**各目标自己的 σ** 缩放；本公司那条恒为权重 1.0（静态自检）。
  3. `MarketShock`：一条冲击的生命周期 —— 触发那一跳按 `STEP_SHARE`（0.7）**跳变**，
     其余按 `2^(−age/半衰期)` 衰减地摊到后面若干跳，σ 也按同一条曲线放大后回落。
- ⚠️⚠️ **「3~8σ」是整条冲击的累计影响，不是每跳的 drift**：纯拖尾写法的累计是
  `幅度 × 半衰期/ln2`（20~60 跳时是 29~87 倍），一条 5σ 新闻就能把价格顶到 500% 上限。
  归一化系数用**离散求和**的 `1 − 2^(−1/半衰期)`（不是连续积分 `ln2/半衰期`，后者会多 0.9%）。
- 触发：每只股票每跳 `StockDefs.EVENT_CHANCE`（0.25% ≈ 13 分钟一次），
  高波动期 ×`VOLATILE_EVENT_MULTIPLIER`（2）。
- **新闻成簇**：一次事件后 30% 概率在 1~3 跳内再抽一条**同类型**事件，
  连簇最多 `CLUSTER_MAX_CHAIN`（3）条；深度记在 `MarketNews#chainDepth`（**不进协议**，
  只给单测/调试看 —— 用「同类型新闻挨得近」当判据会把两条无关的链误判成一条）。
- **传闻 → 澄清**：传闻一律先给正向冲击，`clarifyAfter` 跳后自动注册一条反向冲击
  （幅度 × `clarifyFactor`，0.6~0.8），并把原新闻标成「已澄清」。
- **新闻流**：服务端保留最近 `StockDefs.NEWS_KEEP`（8）条，**最新在最前**；
  快照整份推给客户端。⚠️ 发的是**语言键 + 结构化字段**（股票 / 类型序号 / 影响千分比 /
  年龄 / 是否已澄清），**不是**服务端翻译好的文本 —— 多人服里两端语言常常不同，
  而且客户端还要按类型贴标签、按年龄接替。这正是不走 `flash` 那条老路的原因（见第 18 条）。
- **大盘指数**：`MarketEngine#indexDeci()` = `1000 × mean(price / base) × 10`（整数，
  一位小数），涨跌与个股同口径（千分比、相对上一次跳价）。
- **界面**：指数放在**左列卡片下方**（`Layout#indexBlock()` + `indexLine(i)`，
  640×360 下 `[46,206,64,44]`，四点：大盘 / 指数 / 涨跌 / 状态）；
  新闻条放在**走势图表头行的右段**（`Layout#chartTicker()`，640×360 下 `[252,59,231,11]`），
  是一条横向滚动 marquee：`NEWS_SCROLL_SPEED`（1 像素/tick = 20 像素/秒）、
  **scissor 裁在这条矩形内**、颜色恒为 `COLOR_ACCENT`（黄）、相位按 **tick 累加**
  （不用挂钟，否则暂停菜单回来会跳一大段）、每条滚 `NEWS_HOLD_TICKS`（25 秒）后接下一条、
  5 分钟前的旧新闻不再滚（留空）。表头行按 `CHART_NAME_MAX_W`(132) / `CHART_NAME_PCT`(45%)
  切成「企业名 + 新闻条」两块，**其余坐标一个都没动**。

### 5.9 经济平衡：投机难赚、劳动可靠（2026-10-06）

玩家口径原话：「**尽量不让玩家容易赚钱**」+「**不要直接写涨幅和熊市之类的，玩家看到会直接买或卖。
我的初衷是让人远离赌博**」。两条要一起看：**机制照旧影响价格，但界面不替玩家下判断**。

- **交易成本（压投机的主力，回收价没动）**：
  - 手续费 `FEE_RATE`：0.1%（原版）→ **0.35%**（第一次收紧）→ **0.20%**（同日回调，
    玩家反馈「赚钱太难」）。往返 **0.40%**：盈亏平衡所需的价格变动是 0.4%，
    而单跳 σ 只有 0.6%~2.5% —— **±1σ 的一注在稳健股上要 83% 胜率才保本**，
    激进股也要 58%（随机走势是 50%），所以频繁往返在数学上仍然是负期望；
  - 购买价 `ItemValuator.PURCHASE_MARKUP`：1.15 → 1.25 → **1.18**（买卖价差 18%）；
    **回收价 = 估价 × 1.0 一个字没动**（劳动要保持公平）。
  - ⚠️ 购买点差**只有** `ItemValuator.PURCHASE_MARKUP` 一份实现：
    `StockService` 里那个从没被用过的同名常量已经删了（一份口径两处实现迟早对不上）。
- **趋势更难吃（第一轮收紧 → 第二轮回调）**：`MEAN_REVERSION` 1.5% → 3% → **2%/跳**；
  爆仓线 `MARGIN_CALL_PERCENT` 50 → 40 → **45**（`PositionMath.liquidationPrice` 与 `liquidated`
  必须共用这个常量，界面「扛风险」也直接取它，别写死 45）；
  大盘 bias：BULL +0.12% → +0.06% → **+0.08%**、BEAR −0.10% → −0.16% → **−0.12%**
  （一问一答净 −0.04%/跳，仍然轻微偏空）；利空事件幅度
  ×`BEARISH_EVENT_SCALE`：1.0 → 1.15 → **1.08**（在 `StockEventTable` 静态块里对
  `direction < 0` 生效，表里写的仍是设计值 3~8σ）。
  ⚠️ **回调的边界**：三条投机策略必须仍然是负的（`EconomySimulationTest` 的四条断言），
  想再放宽就先把那张模拟表重跑一遍 —— 「好受一点」和「能赚」只隔一两个百分点。
- **插针**（`StockDefs.SPIKE_*`，专打高杠杆）：每只股票每跳 **0.03%** 概率（≈ 37 分钟一次）单跳 **−8%~−15%**
  （取**价格百分比**而不是 σ 倍数 —— 按 σ 缩放时稳健股的 8% 相当于十几 σ，那就不是「插针」而是「归零」），
  随后 3~5 跳**只回补 60%~80%**（净跌幅仍剩 20%~40%，「等反弹就行」不成立）。
  实现见 `MarketEngine#drawSpike`（新的随机消耗点，位置固定在「事件抽签之后」）
  与 `PendingRebound`（线性回补、不消耗随机数）；命中会往新闻流写一条 `EventType.SPIKE`
  的中性叙述。**护栏一条没放宽**：照旧过 `clamp(±MAX_STEP)` 与价格区间，价格永远 > 0。
  实测（10 万跳）：触发 155 次（预期 150）、平均单跳 −11.70%、回补后净 −3.75%（= 原始的 32%）、
  最大单跳 24.99%、最低价 488 分。
- **界面呈现（与上一条同源，别再改回去）**：
  - 新闻条**只有叙述、没有数字**：「【公司公告】钻石矿业：矿区发生塌方」——
    没有百分比、没有正负号、不按方向染色（恒 `COLOR_ACCENT` 黄）；
  - 左列那块只有三行：标题 / 指数值 / **中性氛围词**（平静 / 温和 / 起伏 / 动荡），
    **没有涨跌行**、也没有「牛市 / 熊市」（`indexChangePermille` 仍在协议里传，只是不画）；
  - 事件文案、帮助面板全文都**不许出现**「利好 / 利空 / 涨 / 跌 / 牛 / 熊 / 机会 / 建议 / 稳赚」
    这类替玩家把判断做完的词；帮助面板第 8 条是一条平实的风险提示。
- **验收靠模拟**：`EconomySimulationTest` 用同一颗种子跑 10 万跳，
  四条策略（追事件 ×10 / 死多 ×400 / 每 10 跳往返 / 只回收）各自记账，断言只看方向与量级
  （实测数字直接打印在测试报告里）。**改任何经济常量都要重跑它。**

### 5.10 条件单（2026-11-06 新增，协议 v5）

- **一句话**：价格「涨到 / 跌到」某个价时，服务端替玩家执行一次**买入开多 / 卖出开空 / 平掉该股全部仓**。
  纯逻辑在 `market/ConditionalOrder`（订单形状、触发判定、夹取）、`ConditionalBook`（增删改、上限、读档收口）、
  `ConditionalOrderSnapshot`（Codec）里，三者都不依赖 Minecraft，单测直接跑真实实现（见 `ConditionalOrderTest`）。
- **服务端权威**：`C2SPlaceConditionalOrder` 的六个字段全是提示值，`StockService#placeConditionalOrder`
  重算股票下标 / 方向序号 / 触发价区间 / 数量 / 杠杆 / 挂单上限（每人 8 张）。撤单只发一个 id，
  服务端重新查一遍自己的簿子。`S2CConditionalOrders` 跟着每次快照整份推给客户端（**单独一个包**，
  不动 `S2CStockSnapshot` 那 16 个分量）；因为新增了三个载荷类型，`ModNetwork.PROTOCOL_VERSION` 从 4 升到 **5**。
- **触发语义**（`ConditionalOrder` 类注释是唯一口径，别在别处另写一份）：
  只在**价格推进**那一刻判定（正常每 2 秒一跳；`/mcstock advance`、`/mcstock price set` 也会判定），
  含等号、按**跳价后的真实现价**成交、无论成败都**一次性移除**；挂单当下不判定、也不冻结资金。
  每次跳价里先跑爆仓（`settleLiquidations`）再跑条件单（`settleConditionalOrders`），
  保证触发时看到的是爆完之后的真实账户。开仓走 `TradeService.open`、全平走 `closeAllAt`
  （与「平 仓」按钮共用的纯结算），手续费/保证金口径一处都不会漏改。
- **存档**：玩家附件 `ModAttachments.CONDITIONAL_ORDERS`（`copyOnDeath`），发号器 `next_id` 跟着存，
  `restore` 会丢畸形条目、去重、截断到 8 张；`clear` **不复位发号器**（避免延迟到达的撤单包打到新单上）。
- **界面**：标题栏「条件单」按钮打开覆盖层（与帮助覆盖层互斥，浮在内容区之上）。矩形全部由
  `StockGuiLayout.compute()` 生成并登记进 `Layout`/`LayoutDump.java`（规格书 3.4 节）。
  行尾「撤」是纯绘制 + `mouseClicked` 命中判定，不是控件 —— 行数每帧都可能变。
  数量 / 杠杆沿用行情页那两个填写栏，覆盖层里只多一个触发价填写栏。
- **调试命令**：`/mcstock cond [list]`、`cond add <股票> <above|below> <价格> <buy|sell|close> [数量] [杠杆]`、
  `cond cancel <id>`、`cond clear`。整条 spec 的解析是纯函数 `McStockCommand.parseCondSpec`（有单测）。

## 6. 已经踩过的坑与实现细节（别改回去）

1. **`gradle.properties` 必须是纯 ASCII**：它由 `java.util.Properties` 以 ISO-8859-1 读取，
   写中文会变成乱码并带进产物。中文显示名写在 `neoforge.mods.toml` 模板里，
   `build.gradle` 已给 `ProcessResources` 指定 `filteringCharset = 'UTF-8'`。
2. **`StockGuiLayout.java` 不能 import 任何 Minecraft 类**：`tools/layout_dump.py` 要用 javac
   单独编译它 + `LayoutDump.java`。一旦引入 MC 类型，整套文档/贴图/效果图工具链就断了。
3. **`Slot` 坐标 ≠ 格子边框坐标**：原版 `Slot#x/y` 是**物品**的绘制点，
   18×18 的格子边框画在 `(x−1, y−1)`。`Layout.barSlots()` 给的是 Slot 坐标，
   `barCell()` 给的是边框坐标，别混。购买页网格不是容器槽，用的是边框坐标。
4. **涨跌配色是 A 股习惯：红涨绿跌**（`COLOR_UP` 是红色）。别照抄「绿涨红跌」的例子。
5. **K 线要自适应量程**：固定量程下高杠杆（爆仓距离 `MARGIN_CALL_PERCENT(45%) ÷ 杠杆`，×400 时只有 0.11%）
   的波动在图上是一条直线。爆仓价必须画成红线。
6. **价格标签要留暗色内边距**：小字号下数字是「空心」的，标签框太紧会连成一条色带
   （`tools/gen_gui_mockup.py` 的 `price_tag` 留了 3px）。两个标签太近时只画现价那个。
7. **占位图集的分区标注不要「把名字画在分区旁边」**：8×8 的边框切片挤在一起必然互相压，
   现在改成「图集上标号 + 右侧图例」（`gen_gui_textures.build_atlas_map`）。
8. **评审要给效果图加版本标签**：`docs/gui_mockup_*.png` 是固定路径，
   聊天窗口/图床会按路径缓存老图，评审时看到的可能是上一版。带标签另存一份
   （`gen_gui_mockup.py 640 360 v4` → `docs/review/v4_*.png`），贴新路径就不会看到老图。
9. **评审要给效果图加版本标签**：`docs/gui_mockup_*.png` 是固定路径，
   聊天窗口/图床会按路径缓存老图，评审时看到的可能是上一版。带标签另存一份
   （`gen_gui_mockup.py 640 360 v4` → `docs/review/v4_*.png`），贴新路径就不会看到老图。
10. **别想着「只给我们这个界面换字体」**：MC 的字体是全局的，中文来自 Unifont 点阵。
    三条路（保持原版 / 自绘 ASCII 位图覆盖 `minecraft:default` / 打包中文 TTF）见 4.6 节。
11. **单测不要依赖 Minecraft 类**：市场引擎、估值、仓位结算、采购定价都要写成纯 Java。
12. **重构后别再写「反向下单先平仓」**：买卖都是新增仓位（见 5.5），逐仓平仓只有
    `TradeService.close(cash, portfolio, positionId, price)` 一条路。
13. **改了持仓列表布局要重跑文档工具**：`python tools\layout_dump.py 640 360`（布局冒烟）、
    `python tools\gen_gui_spec.py`、`python tools\gen_gui_mockup.py 640 360 <标签>`。
    ⚠️ 后两个脚本**现在直接跑不起来**：它们还在引用已经从布局里删掉的旧「图下持仓条」
    （`chartFooter1` / `chartFooter2`），一跑就 `KeyError`（实测见第 22 条）。
    所以 `docs/gui_spec.md` 与三张效果图里那一块**还是改版前的样子**，
    持仓表格的实际矩形以 `layout_dump.py` 输出的 `posList` / `posHeader` / `posRow` / `posCol`
    或真机截图（`run/screenshots/`）为准 —— 补完脚本才是完整的规格书。
14. **小号数字字体是资源，不是代码**：改字形要重跑 `python tools\gen_small_font.py`
    （图集与 json 一起生成），跑完 `docs/review/small_font_v1.png` 有 8 倍放大自检图；
    `SmallFontAssetTest` 会把 json 与图集对不上立刻照出来（例如满屏缺字方块那种事故）。

15. **`candles()` 必须绝对分箱**（`bin = advances / CANDLE_TICKS`，见 `MarketEngine#candles`）。
    按「历史末尾」相对分箱（曾经的写法 `end = hist.length - c * CANDLE_TICKS`）会让每跳一次价、
    所有柱子的 5 跳窗口整体后移 1 跳 → **每份快照里所有已完成柱子的 OHLC 都变**，客户端忠实照画就是
    「每次更新整图乱跳」（玩家原话：「不是抖动，是每次更新的乱跳」；实测每份快照约 **39 根**柱子发生像素变化）。
    绝对分箱之后，一根柱子的 tick 区间一旦写满就再也不会被重算。
    回归测试：`MarketEngineTest#completedCandlesNeverChangeWhenANewTickArrives`。
    配套的一条：分块还要**跳过每块开头的 0**，否则跨在「0 区 / 真实区」的那根会取到 `open = low = 0`、
    不算「四价全 0」所以客户端不跳过，却把自适应量程一路拉到 0 ——
    实机是一根顶天立地的巨柱（测试：`boundaryCandleNeverMixesInUnfilledSlots`）。
16. **自绘小号字体 `mcstock:small`：图集 png 与 `small.json` 的 `chars` 必须同步**。
    唯一真相源是 `tools/gen_small_font.py`（一次生成 json + png，跑完 `docs/review/small_font_v1.png` 有 8 倍放大自检图）。
    两边不同步时 provider 会按**错误的宽度**切片，整行字形错位。
    ⚠️ 缺字形时 MC 画的是 `SpecialGlyphs.MISSING` 方块（`FontSet#getGlyphInfo`），**不会回退默认字体** ——
    所以中文绝不能套小字体（这套字体只给数字与少量符号用）。
17. **`Style#withFont` 在 1.21.1 的 `drawString(Font, Component, …)` 上是生效的**：
    渲染走 `Font.StringRenderOutput#accept → getFontSet(style.getFont())`，
    `Font#width(FormattedText)` 也按 Style 里的字体量宽 —— 所以混排（中文常规字号 + 数字小号）
    **不用自己维护宽度表**，`StockWidgets.small(...)` / `Piece` 就是这么用的。
    反过来，字体没加载出来时 `FontManager#getFontSetRaw` 会退到 `missing` 字体（满屏方块），
    所以 `SmallFontAssetTest` 拿同一套算法把 json 与图集对起来。
18. **`flash` 提示是服务端翻译好的成品文本，客户端拿不到键名**：
    `StockService.flash` 里是 `Component.translatable(key, args).getString()` 之后塞进快照，
    所以 `StockClientState.flash` 里**没有** `message.mcstock.` 前缀。
    「这条提示属于哪一页」只能靠**模板匹配 lang 的「值」**（`StockScreen.FLASH_SHAPES` + `flashShapes(...)`，
    把 `%s` 换成不可打印的 `PARAM` 哨兵做贪心回溯）。按键名/前缀判断必然是「认不出来 → 三页都显示」
    （购买页冒出平仓提示就是这么来的）；EN 的 `traded.open` 与 `purchased` 前缀都是 `Bought`，
    只有把 `x ` / `x` 一起比才分得开。新加会走 flash 的键记得进那张表（漏了只退化成三页都显示，不会崩）。
19. **帮助覆盖层必须在 `render(...)` 整体早退，只在 `renderBg` 早退挡不住控件层**：
    `AbstractContainerScreen#render` 的层次是 `renderBg（手绘）→ renderSlots（容器槽与物品）
    → renderLabels（文字）→ super.render（所有控件）→ tooltip`。
    实机截图抓到的就是「卡片/图表被盖住了，但买卖按钮、数量/杠杆输入框清晰可见」。
    现在 `helpOpen` 时只画覆盖层，其余一个都不调；再加上 `applyHelpVisibility()` 把所有 `AbstractWidget`
    隐藏（控件是独立的事件接收者，光盖住不隐藏会点到看不见的东西；帮助按钮自己不隐藏，它是「再点一下关掉」的入口）。
20. **关闭按钮的叉必须由按钮自己在皮肤之后画**：`drawChrome` 跑在 `renderBg` 里，
    而控件层在它之后 → 在那里画的叉会被 `StockButton` 的 `BTN_*` 皮肤整个盖住（实机只剩一个深色圆环）。
    现在叉走 `StockGuiLayout.CLOSE_NORMAL/CLOSE_HOVER` 两个 sprite，由 `StockWidgets.closeButton(...)`
    交给 `StockButton` 的 `icon` 通路，在 `renderWidget` 里**皮肤之后**画。
21. **`Slot#x/y` 是 `public final int`，切页只能替换 Slot 对象**（用 `javap` 在
    `neoforge-21.1.234-merged.jar` 上核实过：`public final int x; public final int y;`，没有 setter，
    原版读坐标的地方读的就是这两个字段）。`StockMenu#placeSlots` 用 `createSlot(...)` 造一个新坐标的槽
    顶掉 `slots` 里的那一项（**列表长度不变** → `lastSlots`/`remoteSlots` 不会错位），并自己补
    `moved.index = i`；坐标是 `StockGuiLayout` 给的**屏幕绝对坐标**，所以 `StockScreen` 必须把
    `leftPos/topPos` 按到 (0,0)。
22. **每轮改完布局/贴图要跑的脚本**（一个都别落）：
    ```powershell
    python tools\gen_gui_textures.py    # 图集 stock_market.png / 物品图标 / logo / docs/gui_atlas_map.png
    python tools\gen_small_font.py      # small.json + textures/font/small.png（+ docs/review/small_font_v1.png）
    python tools\gen_gui_spec.py        # docs/gui_spec.md
    python tools\gen_gui_mockup.py 640 360 <标签>   # docs/review/<标签>_*.png
    python tools\check_lang_keys.py     # 键差集必须为空（退出码 0）
    python tools\layout_dump.py 640 360 # 布局矩形自检（只打印，不写文件）
    ```
    `check_lang_keys.py` 把 Java 里所有 `Component.translatable("gui|message|item|block.mcstock.*")`
    字面量抓出来和两份 lang 求差集；**动态拼出来的键**登记在脚本的 `DYNAMIC_REQUIRED` 里
    （加列名表 / 分类表时要同步），两份 lang 的键集合也必须一致。
    ⚠️ **订正（2026-10-05）**：`gen_gui_spec.py` 与 `gen_gui_mockup.py` **现在都能正常跑**
    （实测两者退出码 0、正常写出文件）—— 它们对 `chartFooter1/2` 的引用已经清掉，
    「已知损坏」那条是过时信息，不要再照它去"修"。
23. **允许负债之后，三处「现金下限 0」必须一起去掉**（`StockService#setCash`、
    `settleLiquidations`、`TradeService#close`）。只去掉一处的话，负现金会在下一次结算时
    被悄悄夹回 0，负债玩法（自动还债）就永远不触发 —— 而且**不会报错**，只能靠实机对账发现。
    另外 `message.mcstock.liquidated_wiped` 的文案不能再写「账户归零」：现在打到负数就是**欠债**，
    所以它报的是结算后的真实余额（可能是 −45.00）。
24. **⚠️ 单跳 ±25% 的护栏要夹在「取整之后的价格」上**（`MarketEngine#nextPrice`）：
    `drift` 夹过之后还要再把成交价夹回 `[ceil(p × 0.75), floor(p × 1.25)]`。
    光夹 drift 是不够的：`Math.round(851 × 0.75) = 638`，比值 **0.74971 < 0.75** ——
    整分取整能把 ±25% 吃掉半分。这个 bug 潜伏了很久（旧事件的幅度根本顶不到护栏，
    那一行从来没生效过），事件系统的冲击一上来就把它顶出来了，
    基线用例 `MarketEngineTest#singleStepIsCapped` 当场抓获。**不要靠放宽那条用例的容差解决。**
25. **事件幅度 3~8σ 是「整条冲击的累计影响」，不是「每跳的 drift」**（见 5.8）。
    照字面把 3~8σ 当每跳 drift 写，累计影响会是 `幅度 × 半衰期/ln2` ≈ 29~87 倍 ——
    一条新闻就把价格顶到基准价 500% 的上限并钉住。归一化用离散系数
    `1 − 2^(−1/半衰期)`；`MarketShockTest#totalImpactEqualsTheDeclaredMagnitude` 守着这条。
26. **读档是「从种子完整重放」，不是「照着写一份随机消耗的镜像」**（`MarketEngine#restore`）：
    旧的 `burnOneRound()` 手写「这一跳要消耗几个随机数」，一旦和真实的抽签顺序写歪一点
    （比如新加了成簇抽签忘了同步），症状是「同一个存档读两次跑出不同行情」而且不报错。
    现在 `restore` 直接把 `advanceAll()` 重放 `advances` 跳（纯内存计算，4 万跳约 10 毫秒），
    于是**大盘状态 / 还活着的冲击 / 新闻流**全都原样重建 —— 它们**故意不写进 NBT**，
    只存 `seed + advances`（两份真相迟早会对不上）。重放跳数夹在 `MAX_REPLAY_JUMPS` 防脏存档卡加载。
27. **行情新闻发「语言键」，不发服务端翻译好的文本**（与第 18 条的 `flash` 相反）：
    `flash` 必须发成品文本是因为它同时要进动作栏；新闻没有这个需求，
    而键名才能让客户端按**自己的语言**渲染、按类型贴标签、按年龄接替。
    `S2CStockSnapshot.NewsItem` 里 6 个字段全都是结构化数据。
28. **测试事件系统要用 `debugTriggerEvent` / `debugSetRegime`**：这两个钩子
    **不消耗随机数、也不参与成簇抽签**，所以可以拿「强制触发过的引擎」和
    「同种子的无事件基线」逐跳对拍（两者的噪声序列完全一样）。
    ⚠️ 冲击会把 σ 放大 1.5~3 倍，于是**噪声差**也是一条随机游走 ——
    「利好之后价格更高」这类断言必须**多组试验求和**（`MarketEventsTest` 用 20~200 组），
    单看一次实现会被噪声盖住。
29. **新闻条（marquee）的三条硬约定**：scissor 必须裁在 `chartTicker` 矩形内；
    相位按 **tick 累加**（`NEWS_SCROLL_SPEED` 像素/tick），**不要**用
    `System.currentTimeMillis()`（暂停菜单里 world tick 停了、挂钟还在走，回来字幕会跳一大段）；
    颜色恒为 `COLOR_ACCENT`（玩家明确要黄色，不按利好利空染色）。
    ⚠️ **flash 优先、新闻条让位**：`chartTicker` 与 flash 胶囊所在的 `flashRow()` 天然重叠 7px
    （第 3 层的既有设计），所以行情页**只要正在显示 flash，`drawChartTicker` 就整帧不画** ——
    判据用 `StockScreen#flashVisible()`（与「flash 画不画」共用同一份实现，别再各写一套）。
    让位只是 `return`：**相位与选片照常推进**（它们在 `tickNewsTicker()` 里），
    4 秒后 flash 消失，新闻条从原相位接着滚。玩家对「字压在一起」最敏感，不要去试着错开画。
30. **新增「拼出来的」语言键要登记到 `tools/check_lang_keys.py` 的 `DYNAMIC_REQUIRED`**：
    事件名（`gui.mcstock.event.<id>`）、新闻类型（`gui.mcstock.newstype.<id>`）、
    大盘状态（`gui.mcstock.regime.<id>`）都是「前缀 + 表里的 id」，静态抓不到字面量。
    漏登记的后果是**差集校验查不出这两个键缺了**（脚本会通过，游戏里显示键名）。
31. **「玩家背包」= 哪些槽位只允许有一份实现**（`StockService#BACKPACK_SLOTS`）：
    主背包 + 快捷栏（`0..35`，用 `Inventory.INVENTORY_SIZE`）与 `Inventory.SLOT_OFFHAND`。
    ⚠️ **绝对不要用 `Inventory#getContainerSize()`**：它返回 **41**，后 4 格是**盔甲** ——
    以前的「回收时同类一起卖」按 41 格扫，玩家在回收栏里放一块铁、身上正好穿着铁胸甲时，
    **胸甲会被一起卖掉**（2026-10-06 修）。
    ⚠️ **同日再改**：玩家明确「**放进回收栏的才能卖啊**」，所以「同类一起卖」**整个取消** ——
    `StockService#recycle` 现在**只看回收栏那 18 格**，背包一件都不动；
    这个数组只剩**计数**与**负债自动还债**（`#debtCandidates`）在用。
    两条口径的区别就一句：**回收 = 只看回收栏；自动还债 = 看背包**（欠着钱不能靠把东西留在包里躲）。
    判「有没有耐久」同理：1.21.1 的耐久是数据组件，只能问 `ItemStack`（`Item` 上没有 `getMaxDamage()`）。
32. **经济常量散落在四个文件里，改一个要连带改一串**（2026-10-06 定，见 5.9）：
    `StockDefs.FEE_RATE` / `MARGIN_CALL_PERCENT` / `MEAN_REVERSION` / `BEARISH_EVENT_SCALE` / `SPIKE_*`、
    `ItemValuator.PURCHASE_MARKUP`、`MarketRegime` 的 bias、`PositionMath` 的两个公式。
    连带清单：帮助面板 `help.6/7/9/14`、`docs/INSTALL.md` 的「关键数值一览」、
    `StockWidgets#relativePermille` 的算例注释、`StockScreen#riskText`（它直接取常量，不用改数字但
    注释里的算例要改）、以及**测试里的期望值**：`PositionMathTest` / `TradeServiceTest` /
    `ItemValuatorTest` 都钉了具体数字（这是刻意的：它们守的就是这些口径）。
    这个项目**已经因为「注释里的数值和常量对不上」骗过两轮评审**（第 7.4 节），所以改常量时必须一次搜完。
33. **「界面不替玩家下判断」是一条硬口径**（见 5.9）：任何新加的文案/数字，先问一句
    「玩家看到这个会不会直接去点买入/卖出」。已经删掉的：新闻条里的 `-3.6%`、
    大盘块的涨跌行、`牛市/熊市` 字样（换成平静/温和/起伏/动荡）、帮助里的「看涨/看跌」「先涨后跌」。
    保留的是**客观事实**（价格、K 线、自己的持仓盈亏、爆仓价、风险提示）。
34. **插针是新的随机消耗点**（`MarketEngine#drawSpike`）：位置固定在 `nextPrice` 里
    「事件抽签之后、算 drift 之前」，**不要挪**。它命中时一次消耗 5 个随机数
    （概率抽签 + 跌幅 + 回补跳数 + 回补比例 + 文案下标），因为读档走的是「整段重放」
    （第 26 条），所以不需要在别处同步一份 —— 但位置一挪，旧存档的派生状态就会重新洗牌。
35. **⚠️ 估值表必须锚定「稀有度 / 获取难度」，否则常见资源能换稀有资源**（2026-10-06 玩家实测漏洞）：
    旧表木头 4 / 钻石 40 → 采购价 ×1.25（当时）之后 **50 根木头 = 4 颗钻石**。重做后的表分 6 档
    （随手可得 1 / 常见劳动 1~4 / **常见矿物与可再生 5~30** / 稀有 150~800 / 极稀有 800~3000 / 传奇 3000~10000），
    锚点理由写在 `core.json` 的 `tiers` 文档块里。**验收指标是「换 1 个 X 需要多少个泥土」**，
    由 `ExchangeRateTest`（纯 Java、读真数据包、进 CI）守着：钻石 ≥ 300、下界合金碎片 ≥ 500、
    鞘翅 / 下界之星 ≥ 3000，常见资源之间 ≤ 4 倍价差，档位不许重叠。
    **通用规则（2026-10-06 玩家裁决，优先级最高）**：
    > **凡是可以无限复制的物品 —— 水+岩浆刷黑曜石、村民交易换绿宝石、刷怪塔、骨粉/农场、
    > 可刷的怪物掉落（溺尸的鹦鹉螺壳与鳞甲、兔脚、幻影膜…）—— 一律按材料价定价，
    > 落在 T1~T3，不得超过 30 金币，不许进 T4「稀有」以上。**

    理由：价格一高过「刷它的成本」，基建就变成印钞机 —— 黑曜石（水+岩浆，零门槛）曾经 150、
    绿宝石（村民交易）曾经 200（24 张纸的机会成本才 24 金币，一趟净赚近百）。
    **边界：门槛比「理论可再生」重要** —— 需要先拿下稀有结构（要塞刷怪笼、末地城、远古城市、
    林地府邸）或要打 BOSS 才谈得上刷的（烈焰棒、末影珍珠、恶魂之泪、潜影壳、凋灵骷髅头、
    三叉戟、不死图腾、龙息、下界之星、鞘翅…）按**门槛**定价、不受这条约束，
    否则宝石 / 合金 / 鞘翅会被一路压到材料价，兑换比下限就没了。
    两个清单（`ExchangeRateTest#renewableItemsStayCheap` 断言 ≤30、
    `#gatedItemsStayValuable` 断言 ≥150）都写在测试里、逐条带「凭什么」，加新种子时照着补。
    三条同源的硬约束（改表前先看）：
    - **1 原木 → 4 木板**：木板最多值 `floor(原木/4)`，所以这一对只能是 **4 / 1**
      （原木压到 3 → 木板全变废品；木板抬到 2 → 「买原木拆木板再卖」套利）；
    - **1 羊毛 → 4 线**：同理，羊毛必须 **4**、线 **1**（旧表羊毛 2 就是这个漏洞）；
    - **熔炼 1:1**（粗矿 ↔ 锭、远古残骸 ↔ 下界合金碎片）：两边必须**同价**，
      否则「买粗矿 → 熔炼 → 回收」白赚（旧表 raw_iron 2 / iron_ingot 6 就是这个漏洞）。
    `ExchangeRateTest#seededRecipesCannotBeArbitraged` 把这三类（加上烈焰棒→粉、岩浆膏、
    矿物块）都列成了断言，加新种子时照着补一条。
36. **⚠️ `item_values` 里的 `#标签` 必须是<u>物品</u>标签，方块标签会静默失效**：
    `#minecraft:base_stone_overworld`、`#minecraft:base_stone_nether`、`#minecraft:crops`
    都是**方块**标签（`data/minecraft/tags/block/`），写在估值表里不会有任何报错，
    只是那几个物品拿不到价（`StockEvents#tagMembers` 只遍历 `BuiltInRegistries.ITEM` 的标签）。
    可用的物品标签见 `data/minecraft/tags/item/`（例如 `dirt` / `sand` / `logs` / `planks` /
    `coals` / `wool` / `meat` / `fishes` / `stone_crafting_materials` / `smelts_to_glass`）。
    加键之后自查一遍：`python tools/check_item_values.py`（对着 client-extra 里的
    物品标签表与语言文件校验每个键存不存在）。

37. **⚠️ 加载 / 重载 / 启动阶段一律不许抛异常（线上加固，2026-10-06）**：
    整合包里 `AddReloadListenerEvent` / `ServerStartedEvent` 是**上百个模组共用**的生命周期节点 ——
    我们在这里抛一个异常，服务端初始化就当场断掉、**别人的模组跟着一起崩**
    （线上那份崩溃报告就是这个形态：某家的 reload 监听器失败，另一家的 `INSTANCE` 还是 null）。
    规矩：
    - 所有入口（`StockEvents#onAddReloadListener / onServerStarted / onLogin / onServerTick /
      onChangedDimension / onRegisterCommands`、`StockService#autoSellToRepayAll`、
      `MinecraftRecipeGraph#of`、`ItemValueRules#apply`、`ItemValuator#rebuild`、
      `StockMarketData#load/save`、`MarketEngine#restore`）都走 `Errors.guard(...)` 或等价的 try/catch；
    - **异常只记一次**：`Errors` 用 key 去重（第一次打 ERROR + 堆栈，之后只计数，
      `Errors.suppressedCount(key)` 能查到被压掉多少次）—— 行情每 2 秒一跳，
      持续错误如果每次都打日志，几分钟就把日志刷爆、把真因埋掉；
    - **只放行 `VirtualMachineError`**（OOM / StackOverflow 照抛）：其余 `Throwable` 全接，
      包括 `NoClassDefFoundError`（缺前置模组的配方抛的正是它，只 catch `Exception` 根本接不住）；
    - **降级而不是崩**：估值表建不起来 → `StockEvents.valuesUnavailable()` → 买卖提示
      `message.mcstock.values_unavailable`；行情 tick 连续失败 3 次 → **停掉行情**（价格不再跳，
      服务端照常），`marketHalted()` 可查；
    - 坏数据**逐条目跳过 + 计数**：配方逐条（`MinecraftRecipeGraph#index`）、估值条目逐条
      （`ItemValuator#rebuild`）、种子文件逐个（`ItemValueRules#apply`）—— 都是「跳过 N 条」一条汇总日志，
      前 5 条才逐条打；脏存档（`prices` 类型不符 / 长度为 0 / 负数）→ 退回新市场 + WARN。
    - 锚是 `HardeningTest`（6 条，纯 Java）：畸形 JSON、会抛异常的 `RecipeGraph`、坏 NBT、
      去重计数、`LinkageError` 必须被接住 / `OutOfMemoryError` 必须照抛。
      **新增任何「服务端启动 / reload 时跑的代码」都要照着补一条。**
38. **⚠️ 网络编解码一律不许抛异常，只能降级（2026-10-06 线上掉线事故）**：
    第三方整合包有 **7799 件**可购买物品，`S2CShopCatalog` 原来把整份目录塞一个包、
    解码端超过 4096 条就 `throw DecoderException` —— 结果是客户端
    `Failed to decode packet` → **当场掉线**。和坑 37（加载/重载阶段）并列，这是一条通用原则：
    > **解码器里任何一个「语义上的不满意」都不许抛** —— 越界就截断、负数就当 0、长度对不上就补齐；
    > 唯一允许抛的是**流本身读不动**（{@code buf} 里字节不够，原版自己会抛并踢人，无法挽救）。
    > 宁可让玩家看到「目录不全」，也绝不能让他掉线。
    落地：
    - **分包**：`S2CShopCatalog` 带 `part` / `totalParts`（选它而不是 `offset`：收端只要判断
      「0..N-1 到齐没有」，不依赖任何一片的大小，缺片行为天然明确）。每片 ≤ 1024 条
      → 7799 条 = 8 片；空目录也要发**一片空的**（否则客户端永远等不到「收齐」）。
      客户端 `S2CShopCatalog.Assembler` 累积，**收齐才整体替换**（没收齐保持旧目录）；
      `part == 0` 表示新一轮（也用来处理重发），重复/越界片直接忽略。
      ⚠️ 收端拼装顺序无所谓（按 `part` 下标放），**发端顺序**由 NeoForge 保证。
    - **硬上限**：`MAX_TOTAL_ENTRIES = 32768`（= 32 片）。超了按价格**从高到低**截断
      （目录已按价格升序，所以保留末尾那段）并记一条去重 WARN：「共 N 条只发 M 条」。
      **绝不允许再出现「无上限的包」**。
    - **解码安全网**：`network/PacketGuards`（`readVarIntArray` / `readLongArray` / `readUtf` 的
      「夹长度 + 去重 WARN」版本）。原版的 `readVarIntArray(max)` / `readLongArray(null, max)`
      / `readUtf(max)` 在**声明长度超上限**时都会抛 —— 那是同一个坑，所以带上限的读取一律走它。
      `S2CStockSnapshot#decode` 的全部数组与两个字符串已经改用它。
    - **超一点也要读完再截断**：只读前面一段会让后面的字段读到数组中间、整条流全错位
      （`HARD_READ_LIMIT = 单片上限 × 64`，超过才当成荒谬头按 0 条处理）。
    - **服务端侧的输入夹取**（C2S 包一律当「提示」）：数量 `clamp(QTY_MIN..MAX)`、
      杠杆 `clamp(LEVERAGE_MIN..MAX)`、采购数量 `clamp(1..4096)`（`StockService`）。
    - **改包结构必须改 `PROTOCOL_VERSION`**：`"3" → "4"` 就是这次分片带来的
      （旧客户端会把片头当成条目数读，字段全错位）。
    - **字符串：写端截断 + 读端夹长度，共用 `PacketGuards.MAX_TEXT`（**1024**，2026-10-06 由 512 提高，理由见常量注释）这一份常量**
      （事故二：快照包的 `flash` 写端不带上限、读端更小就抛 → `103 > 102` → 掉线）。
      写端一律 `PacketGuards.writeText(buf, s)`（先按**码点**截断，别切出半个代理对），
      读端一律 `PacketGuards.readText(buf, what)`。**不许**在别的类里另写一个上限数字 ——
      两边各写一个上限正是这类事故的根因。
      ⚠️⚠️ **别用 `buf.readUtf(maxChars)` 去读「已经自己读过长度前缀」的字符串**：
      那个方法会**再读一次长度前缀**（上一版就错在这里 —— 传进去的字节数被当成字符上限，
      第二次读到的是正文第一个字节，于是报出 `103 > 102` 这种看不懂的错，
      而且**只要 flash 非空就必然掉线**）。要限长就读长度 → 自己 `readBytes` → `new String(UTF_8)`。
      `readResourceLocation()` 内部是 `readUtf(32767)`（同样会抛），S2C 侧的物品 id 一律
      `PacketGuards.readUtf` + `ResourceLocation.tryParse` + 占位 id 兜底。
    - 数值数组（价格 / 持仓 / K 线 / 回收栏单价）**有意不加写端上限**：它们的长度是服务端编译期
      不变量（`StockDefs.COUNT` / `Portfolio.MAX_POSITIONS` / `CANDLE_MAX` / `BAR_SLOTS`），
      读端有界就足以避免掉线；写端再 `Arrays.copyOf` 一遍换不来实际收益
      （三处事故里有两处是「过度加工读取路径」引起的 —— **能不加的加工就不加**）。
      C2S 方向同样保持 vanilla（畸形客户端只会踢掉它自己，服务端本来就要防恶意输入）。
    - 锚是 `ShopCatalogPacketTest` + `PacketTextLimitsTest`（各 6 条）：
      分片边界（空 / 1 / 1024 / 1025）、单包 5000 条与负数条数不抛、越界片号与缺片只降级、
      32768 截断只保留最贵的。**新增/改任何 S2C 包都要照着这条补用例。**
39. **「空中飞人」成就：摔落高度必须在**伤害结算前**拿，且成就/清债要放在死亡事件里**（2026-10-21）：
    - 死亡事件（`LivingDeathEvent`）里 `player.fallDistance` **已经被重置成 0**，
      直接读永远判不出「百格」。所以 `StockEvents#onLivingFall` 在 `LivingFallEvent`
      里把 `getDistance()` 按 UUID 存进 `AerialistService` 的短生命周期缓存：
      **下一次摔落覆盖 / 不够高（< 100）就删 / 死亡即取即删 / 40 tick（2 秒）过期**。
    - 三条同时成立才触发（`AerialistJudge#isAerialist`）：`cash < 0` + 摔落 ≥ 100.0 格
      （**边界 100.0 算达标**）+ 死因是 `DamageTypes.FALL`（`event.getSource().is(...)`）。
    - 触发后：`StockService.setCash(player, 0)`（**直接置 0**，不是加钱、不按净值算）→
      `ServerPlayer#getAdvancements().award(holder, "impossible")` → 聊天栏一条提示。
      放在死亡事件里做是安全的：`ModAttachments.MONEY` 是 `copyOnDeath`，重生时复制到的就是 0。
    - advancement JSON 在 `data/mcstock/advancement/aerialist.json`：1.21.1 的图标格式是
      `"icon": {"count": 1, "id": "minecraft:feather"}`（不是老的 `"item"`），criteria 用
      `minecraft:impossible`（只能由代码授予）；它是**根成就**（没有 `parent`），所以要给
      `background`。`hidden: false` 让没拿到的人也能在成就界面里看到目标。
    - ⚠️ 授勋失败（数据包没加载出来）只记一条 WARN，**清债与提示照常执行** ——
      成就只是纪念品，不该因为它缺失就吞掉玩家实际的好处。
40. **农作物的定价有一对硬约束**（2026-10-21 补价时踩到的）：
    - 种子统一定价 **1 金币**；于是「1 南瓜 → 4 南瓜种子」要求 **南瓜 ≥ 4**，
      否则「买南瓜 → 拆种子 → 回收」白赚（南瓜定 2 时这条就是在亏钱边缘）。
    - `#minecraft:villager_plantable_seeds` 在 1.21.1 只覆盖 **wheat_seeds / beetroot_seeds /
      potato / carrot / torchflower_seeds / pitcher_pod** —— **不含西瓜种子和南瓜种子**，
      别以为给这个标签定价就等于给所有种子定价了（另两个要显式写或靠配方推导）。
    - 西瓜、干海带、蜂蜜块、蘑菇煲、蛋糕这些产物**没有种子**，靠推导；离线验算时
      `ValueTableFixture#MINI_RECIPES` 只复刻了「一步推导」的那几个，**别把它当第二份估值实现**。
    - 新增/改动农作物价格时，`ExchangeRateTest#seededRecipesCannotBeArbitraged` 里的
      农作物配方（南瓜→种子、西瓜↔西瓜片、海带→干海带、蜂蜜瓶↔蜂蜜块、蘑菇→蘑菇煲…）
      必须同步补上，否则「买原料做产物再卖」这条线没人守。
41. **解锁页（第 4 个页签）：投放区复用回收栏，「一组」= `getMaxStackSize()`**（2026-11-08）：
    机制一句话：**交出一整组 = 证明你能产出它 → 永久解锁该物品的购买资格**。
    - **不新开 18 格容器**，直接复用 `StockMenu#bar()`：两个动作本质都是「把东西交出去」，
      区别只在结算方式（换钱 / 换资格）。新开容器要增删槽位，会破坏
      `lastSlots`/`remoteSlots` 的对齐（第 18 条），风险差一个量级。
    - **几何整块复用回收页**：`Layout#unlockInfo/unlockBtn/unlockDetail` 返回的就是
      `barInfo/recycleBtn/barDetail`。好处是 `planRecycle` 那套「放不下就降级」
      （NORMAL / MERGED_INFO / COMPACT）直接继承，不用再写一份并各自漂移。
      锚是 `StockGuiLayoutTest#unlockPageReusesTheRecycleGeometry`。
    - **三条「不许静默吞东西」的消费口径**（纯规则在 `UnlockRules`，单测 `UnlockRulesTest`）：
      ① 只消耗**恰好一组**，多余的原样留在投放区；② 不足一组的**一件都不动**；
      ③ 已解锁 / 本来就不上锁的**一件都不动**。
    - **「一组」取 `getMaxStackSize()`**（材料 64 / 部分模组 16 / 工具护甲 1）：
      这是游戏自己给的批量定义，整合包不需要任何配置，而且天然是难度刻度
      （工具交 1 个、材料交 64 个）。
    - **上锁口径只有一条**：`!seeded && 买价 ≥ 500`（`UnlockRules#requiresUnlock`）。
      **有种子的原版物品永不上锁** —— 它们是购买页的基础物资、也是兑换尺度的锚
      （`ExchangeRateTest` 守的就是它们），上锁等于把购买页清空。
    - ⚠️ **客户端判不出「这个物品要不要解锁」**（种子表只在服务端的 `ItemValuator.origin` 里），
      所以客户端用「已解锁集合 + 目录价 < 500」近似，**服务端才是权威**。
      猜错的后果只是「按钮亮着、点下去服务端回一句不需要解锁」，**绝不误消耗**：
      `StockService#unlock` 里 `already` 的判定优先级最高。
    - **协议 v6**；解锁表走**独立小包** `S2CUnlocked`：绝不塞进每 2 秒的行情快照，
      也**绝不因为解锁一次就重发目录**（整合包里 7799 条 = 8 片，那会变成「点一下卡一下」）。
42. **采购价两条轨道：`max(材料价 × 1.18, 劳动时间 × 60)`；回收价一个字都不许动**（2026-11-09）：
    这是「整合包里后期物品被廉价卖掉」的正面修法，也是本项目**最容易改坏的一处**。
    - **病根**：`ItemValuator#derive` 只算材料成本 `floor(Σ 原料价 × 数量 / 产出)`，
      而模组机器链大量是 **1:1 配方** —— 十二层的链末端材料成本还是第一层的价。
      旧公式于是把后期物品按原料价卖，`/mcstock audit` 一眼能看到整条链同价。
    - **治法**：给「每一步工序」一个时间代价（`RecipeSteps`），逐层累加出
      `LaborTime`＝「从零做出它要多少分钟」。买价换成
      `max(材料价 × 1.18, 劳动时间 × 60)`。**只抬买价，永不抬回收价** ——
      抬买价不会造出印钞机（只会让买更难），抬回收价立刻造出一个。
    - ⚠️ **两条轨道共用一个锚**：`LaborTime.COINS_PER_MINUTE = 60`，即 **1 金币 = 1 秒劳动**
      （由「泥土 = 1 金币」倒推）。种子物品的 `T = 回收价 ÷ 60`，所以
      `T × 60 = 回收价 < 回收价 × 1.18` → **基础物资的买价仍然是材料价，不会被抬高**。
      涨价只发生在「配方推导出来的深链」上，这正是想要的效果。
    - ⚠️ **`RecipeSteps` 的模组默认值（4 分钟）是整套机制唯一需要人工标定的数字**，
      别处不许再出现第二个「难度系数」。整合包要调难度就改
      `data/<ns>/mcstock/recipe_steps/*.json` 里的 `#default`，不许改代码。
    - ⚠️ **`RecipeEntry` 多了 `type` 字段，回收价链路完全不用它**（`derive` 只读
      `inputs`/`outputCount`）。加字段是为了让「劳动时间」知道这一步是哪台机器做的；
      老调用方传 `null` = 按原版默认步长处理，**不影响任何回收价**。
    - ⚠️ **环路安全**：`LaborTime` 取 `min`（玩家会走最省的那条路），
      而绕一圈必然多付一次工序，所以 `9 锭 ↔ 1 块` 这类环路迭代到不动点就停，
      **不会一圈圈涨上去**。改成 `max` 或把工序时间摊进产出数量都会破坏这条
      （锚是 `LaborTimeTest#storageCycleDoesNotDiverge`）。
    - **原料没价就整条配方不算**（比回收价那边更严：那边是「总成本为 0 才跳过」）。
      代价是整合包里可能有一批物品推不出劳动时间 → 买价退回材料价，
      这是**降级不是失效**，`/mcstock audit` 里劳动时间为 0 的就是这批。
43. **时间锁（买价的 95%）与采购冷却（= 买价秒数）：两条都由价格推出来，不落盘**（2026-11-09）：
    - **时间锁**：`UnlockRules#isLocked` 三条路任一成立就开 ——
      ① 本来不用解锁（种子价 / 买价 < 500）；② 交过一组（`ModAttachments.UNLOCKED`）；
      ③ **玩家累计游戏时间 ≥ 买价 × 0.95 秒**。第 ③ 条是**算出来的、不存盘**，
      所以老存档进来立刻正确，**不需要任何迁移**（这也是为什么没有写迁移代码）。
    - 系数 **0.95** 的意义：留 5% 缺口让「主动交付」永远比「干等」早一点，
      否则谁都会干等，解锁页就成了摆设。别改成 1.0。
    - **游戏时间取 `Stats.PLAY_TIME`（按玩家、÷20 得秒）**，不是存档年龄也不是世界时间：
      后两者会把「服务器挂机」和「玩家真在玩」混为一谈，也让后进服的玩家凭空占便宜。
    - **冷却 = `ItemValuator#cooldownSeconds(买价)` = 买价秒数**（1 金币 = 1 秒劳动，
      所以「买入吞吐量」正好等于「自己生产的吞吐量」，不需要第二个配置项）。
      上限 24 小时（`MAX_COOLDOWN_SECONDS`）：无上限会让整合包物品锁到几个月后，
      玩家只会以为按钮坏了。
    - **冷却只存「上次买的时刻」**（`ModAttachments.PURCHASE_COOLDOWN`，物品 → game time），
      时长每次现算 → 调价不用迁移存档。只记时长 ≥ 1 分钟的物品，写入时顺手清过期条目，
      表的规模稳定在几十条。
    - ⚠️ **两道门都排在「扣钱之前」**，且顺序是「解锁 → 冷却 → 余额」。
      反过来的话玩家会看到「余额不足」，去赚钱回来还是买不了，白跑一趟（别对调）。
    - **协议 v7**：`S2CUnlocked` 头部追加了 `playSeconds`（旧客户端会把它当 id 条数读，
      整包错位）。客户端拿它 + 目录价调**同一份** `UnlockRules#isLocked`，
      两端结论一致；客户端唯一缺的信息是「有没有种子价」，猜错的后果只是
      「按钮亮着、服务端回一句已经不需要解锁」，**绝不误消耗**。
    - `/mcstock audit [n]` 是这套机制的**唯一标定工具**：按买价降序打印
      「回收 / 买 / 劳动分钟 / 冷却」。改 `recipe_steps` 之后跑一次，看末端物品落在什么价位。
    - ⚠️ **购买页故意不置灰未解锁的物品**（别「优化」成置灰）：整合包里能推价的物品
      7799 件，其中绝大多数都该上锁 —— 一格一格灰过去只会让整页变成废图；
      而玩家上一轮明确要求「删掉所有说明性的文字，购买页保持干净」。
      拒绝购买时的 flash 才是权威提示（「交一整组 / 再玩 N 分钟」两条路都写在里面）。
      客户端的锁判定只用在**解锁页**（那页只有投放区里那十几种物品，不会刷屏）。
    - 解锁表存成 `ModAttachments.UNLOCKED`（不可变 `Set`，写入一律 `setData` 整体替换）。

## 7. 实机验收与调试方法

这一节是几轮真机验收攒出来的**方法论**，比代码本身更容易被忘掉。新做一块界面 / 一个新特效，
建议照着走一遍。

### 7.1 取证优先于猜测：想清楚改动落在哪一层，再动手

观感/渲染类问题**先取证再改**，否则很容易改了三处、其实一处都没被画到。三个用过且每次都直接定位的手法：

1. **打印渲染调用链**，想清楚改动落在哪一层、谁在它之后画。
   `AbstractContainerScreen#render` 的层次是
   `render → renderBg（手绘：卡片/图表/槽位底）→ renderSlots（容器槽与物品）→ renderLabels（文字）
   → super.render（所有控件）→ renderTooltip`。
   本项目两个真实事故都出在这一步：关闭按钮的叉画在 `renderBg` 里被按钮皮肤盖掉（第 20 条）、
   帮助覆盖层只在 `renderBg` 早退导致控件层透出（第 19 条）。
   **做法**：在每一层入口打一行带开关的 `LOGGER.info`，跑一次就知道谁被调到、顺序如何。
2. **把图集按「声明坐标」逐像素打印成 ASCII，再和生成脚本里的坐标两处对照。**
   贴图错位这类问题，肉眼看 256×256 的图集看不出所以然。写十行探针：读 `StockGuiLayout` 里那个
   sprite 的 `(u, v, w, h)`（**代码声明的坐标**），把这块按 `alpha > 128 → '#'`、`alpha > 0 → '+'`、
   否则 `'.'` 打成字符画；同时在 `tools/gen_gui_textures.py` 里打印同一片区域的坐标与绘制表达式。
   两边**必须指向同一个东西** —— 对不上就是「脚本画的」和「代码取的」不是一块
   （`StockWidgets#blitSliced` 那条「纵向多取 28 行、一个按钮里叠三段颜色」就是这么定位的）。
3. **打印实际收到的字符串 / 数据形态，不要假设它是什么。**
   `LOGGER.info("flash=[{}] len={}", text, text.length())`、把 `S2CStockSnapshot` 里那个数组按
   `[stock][candle*4+f]` 摊平打出来、把 `Component.getString()` 的结果原样打出来。
   实例：`flash` 其实是**服务端翻译好的成品文本**（第 18 条）。

### 7.2 采样频率不能和信号同频：验「画面跳不跳」必须拍密帧

**踩过的坑**：验「K 线会不会乱跳」时，脚手架每 **2 秒**截一张图，而跳价周期正好也是 **2 秒** ——
两边同频、相位锁死，**两轮跳动被完整漏掉**，那段序列看起来「稳如老狗」
（`run/screenshots/mcstock3_seq_t0..t5.png` 就是那次：6 张 ≈ 10 秒，正好是「2 秒一张」）。

规则：

- 截图间隔必须 **< 信号周期的一半**（跳价 40 tick，就按 10~15 tick 一张拍）；
- 拍完不要靠肉眼，**逐帧做差分**（同一区域逐像素比较，输出「变了几个像素、哪些行」）；
- 把「不该动」钉成断言：**没有新数据到来的那一帧必须逐像素静止**。
  K 线验收用的就是这条 —— 跳价那一帧变、其余帧 0 像素变化，才敢说「不跳了」；
  修绝对分箱（第 15 条）之前，那里是「每份快照约 39 根柱子发生变化」。
- 同一条规则也适用于日志：判「音效/粒子到没到客户端」要按**线程名**区分
  （单机里同一次播放会有 Server thread 与 Render thread 两条 `PlayLevelSoundEvent`，
  只有 Render thread 那条代表客户端真的收到了包）。

### 7.3 索引路径必须对账：「屏幕局部下标 → 全局列表下标」只允许有一份实现

**本项目最严重的一个 bug**：购买页「格子在列表里的第几项」曾经有**三份**等价实现 ——
点击路径 `shopIndexAt`（漏了页偏移）、tooltip 路径 `startIndexOnGrid`（有页偏移）、
绘制路径手写的 `shopPage * pageSize + i`。三份里漏了一份，症状是
**悬停显示的是第 2 页的钻石、点下去买的是第 1 页同位置的泥土**
（玩家原话：「购买页面切换后，买东西还是按第一页的买」）。

做法：

- 动这类换算之前，先把**所有**「局部下标 → 全局下标」的路径列出来逐个对账
  （点击 / 悬停 tooltip / 绘制 / 滚动 / 键盘 / 服务端重算）；
- 收敛到**一个函数**（现在只有 `absoluteShopIndex(col, row)`），其余路径一律调它；
- 留一条**对拍日志**：点击那一刻把 `clickIndex` 与 `hoverIndex` 一起打出来，两者应当永远相等
  （`StockScreen#mouseClicked` 里那两行 `LOGGER.info` 就是干这个的，验完可降级成 debug）；
- 同一原则适用于所有「两份公式」的地方：数量 MAX 与服务端共用 `PositionMath#maxAffordable`、
  持仓浮盈/爆仓价两端共用 `PositionMath`、股票 id → 下标只有 `StockCatalog#indexOf`、
  金额格式化只有 `StockService#formatPrice`。

### 7.4 注释/文档里的数值必须与常量一致（这条骗过两轮评审）

`PRICE_TICK_TICKS` 的**注释**写着「2 秒」，**常量**却是 `100`（= 5 秒）—— 两轮评审都照着注释
讨论「2 秒一跳」，没人去数常量，直到**实机对拍时间轴**才发现。现在常量是 `40`（= 2 秒），
并且注释里把这段历史留了下来（见 `StockDefs`）。

规则：注释里出现数字就紧挨着写成常量名或算式（`40 tick = 2 秒`、`CANDLE_TICKS = 5 跳 = 10 秒`），
改常量时注释会一起被看见。这类问题在代码评审里几乎抓不到 —— 只有实机量一次「一跳实际多少秒」才行，
所以验收清单里要有一条**量时间**（本项目就是靠它发现跳价其实比需求慢了一倍半）。

### 7.5 布局类改动：让策划画图，比文字描述省事得多

文字描述布局**连续两次**跑偏：一次把「数量/杠杆两行要保留」写成了「数量/杠杆可以去掉」，
一次把「平仓按钮在**每行末尾**」写成了「右下角一个平仓按钮」；两次都是做完了才发现理解错了。

改布局**之前**先要一张图（截图、涂鸦、Excel 画的框都行），图上标注三件事：
① 这一块在哪（相对谁、左边/右边/下面）；② 保留什么、删什么；③ 行内元素从左到右的顺序。
拿到图再改 `StockGuiLayout`，改完**用效果图回给对方看**（`docs/review/<标签>_*.png`），不要用文字复述。

### 7.6 真机截图 > 自检脚本：「数字上全通过」不等于没问题

下面四个都**通过了当时的全部单测与自检脚本**，只有真机截图能看见：

| 现象 | 真因 | 代码里的记录 |
|---|---|---|
| 距爆仓显示 `-502.2%` | 千分比当百分比印（**大 10 倍**） | `StockWidgets#relativePermille` 注释里有完整算例 |
| 倍数列显示 `▯1` | 字体**图集与 json 不同步**：`small.png` 是 18 格（每格 3px）而 `small.json` 的 `chars` 只列了 9 个 → provider 按 54/9 = 6px 切片，每个字形都被按双倍宽读、起点全错 | `tools/gen_small_font.py` 是**唯一真相源**（一次写 json + png）；改了 `CHARS` 必须重跑，别手改 json |
| 页签 / 关闭按钮「看不出选中态 / 只有一个圈」 | 同一根因：切片在 **`renderBg`** 里画，而带皮肤的按钮控件在 **`super.render`** 期间才画，皮肤实测**完全不透明**（alpha = 255）→ 把底下的切片整个盖住 | 页签走 `StockWidgets#tabButton`（`StockButton` 的 `selected` → `TAB_ACTIVE`）、关闭按钮走 `closeButton`（图标画在皮肤**之后**）。**任何"控件底图"都必须由按钮自己画** |
| 购买页网格行数偏少 | 网格下那条提示行的**预留高度**（`SHOP_HINT_H`）与 `GRID_MAX_ROWS` 上限 | `StockGuiLayout`：`SHOP_HINT_H = 0`（提示行已删）、`GRID_MAX_ROWS = 16`，行数 = `可用高度 / SLOT`；⚠️ `GRID_MIN_ROWS = 4` **不是保底**（只用于其它判断），别照旧文档以为它会兜住 4 行 |
| 按钮通栏 | 切片**采样尺寸**被当成目标尺寸（同一类错误在 `blitSliced` 注释里记着纵向版：一个按钮里叠三段颜色） | `StockWidgets#blitSliced`（`uWidth/vHeight` 是取样大小） |
| 单跳跌了 25.03%（基线用例 `singleStepIsCapped` 挂） | 护栏只夹了 `drift`，没夹**取整后的价格**：`round(851 × 0.75) = 638` → 0.74971 | `MarketEngine#nextPrice` 里 `[ceil(p×0.75), floor(p×1.25)]` 那两行（第 24 条） |
| 「同一个存档读两次跑出不同行情」 | 手写的随机消耗镜像（`burnOneRound`）和真实抽签顺序不一致，且不会报错 | `MarketEngine#restore` 改成完整重放（第 26 条） |

所以每轮验收**至少留一张真机截图**（`run/screenshots/`），并且要**在游戏里逐条走一遍**验收清单，
不能只跑脚本。命名建议「轮次 + 场景」（`mcstock3_shop.png`、`live3_field_zoom.png`）；
评审用的另存到 `docs/review/<标签>_*.png`（`docs/gui_mockup_*.png` 是固定路径，聊天窗口/图床会按路径缓存老图）。

## 8. 当前进度

- ✅ **阶段 1**：工程骨架 + 布局引擎 + 规格书 + 占位图集。
- ✅ **阶段 2**：三页 GUI（回收 / 行情 / 购买）、窗口占屏 88%、阴阳柱大屏、圆角柔和配色。
- ✅ **阶段 3**：估值系统（种子 + 标签 + 配方推导）、市场引擎与存盘、网络协议 v2、容器菜单、
  独立仓位列表（多空互不对冲）、逐仓结算与强平、购买 / 回收、帮助面板、持仓表格与行尾平仓按钮。
- ✅ **阶段 4**：**真机验收完成**（回收 / 行情 / 购买三页 + 帮助覆盖层 + 独立仓位表格都实机走过；
  验收方法与踩过的坑见第 7 节）。单测见 `README.md` 的「开发状态」表。
- ✅ **阶段 5**：`/mcstock` 调试命令（权限 2）、`docs/INSTALL.md`、release jar 校验。
- ✅ **阶段 6（2026-10-06，本次）**：
  - **允许负债**（现金可为负、无上限；三处 clamp 去掉）+ **负债时自动卖背包里的回收物还债**
    （有耐久的工具装备一律不卖），提示两条、帮助面板一节（见 5.7）；
  - **行情事件系统 v2**：大盘状态机（平静 / 温和 / 起伏 / 动荡，按 β 影响个股）
    + 18 条具名事件（类型 / 影响映射含反向外溢 / 3~8σ 累计幅度 / 半衰期 20~60 跳 / σ 放大 1.5~3）
    + 传闻先涨后跌（自动澄清回吐 60~80%）+ 新闻成簇（30%，最多连 3 条）+ 新闻流（最近 8 条）
    + 市场指数；协议 **v3**（快照新增指数 / 大盘状态 / 新闻流）
    + 行情页新增两块：**左列市场指数小结**与**走势图表头行的黄色滚动新闻条**（见 5.8）。
- ✅ **阶段 7（2026-10-06，本次）**：
  - **界面去方向化**（见 5.9）：新闻条只叙述、不带数字；市场指数块只有三行、状态词中性化；
    帮助面板补风险提示并清掉「看涨/看跌/先涨后跌/赌」等措辞（含中英两份 lang）；
  - **经济平衡**（见 5.9）：两轮调参 —— 第一轮收紧（手续费 0.35%、购买价 1.25、均值回归 3%、
    爆仓线 40%、大盘偏空、利空 ×1.15、新增**插针** 0.05%/跳），第二轮按玩家反馈「赚钱太难」回调
    （**0.20% / 1.18 / 2% / 45% / 牛 +0.08% 熊 −0.12% / ×1.08 / 插针 0.03%/跳**）；
  - **模拟验收**：`EconomySimulationTest` 四条策略（实测 A/B/C 全亏、D 稳赚，数字见该测试的 stdout）。
- 🔧 **阶段 8（待办）**：这一切的**真机验收**（脚手架截图 + 实机走一遍验收清单：
  新闻条滚动与让位、负债自动还债的动作栏提示、负余额红字、市场指数、插针与买卖按钮配色）。

数值与规则以 `StockDefs` 与 `docs/gui_spec.md` 为准，不在本文件重复。
