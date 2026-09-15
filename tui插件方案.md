# TUI 插件 UI 扩展点落地方案（插件在终端界面上新增组件）

> 前置：`tui方案.md`（TUI 外壳本体）。本方案只回答一个问题：**插件怎么把东西放到 TUI 界面上，多插件怎么共存与切换。**
> 本文档是「方案」，尚未实施；实施后的偏差按 `react方案.md` 的惯例追加「落地记录」。

## 0. 已确认口径（用户裁决）

| # | 问题 | 裁决 |
| --- | --- | --- |
| Q1 | 槽位范围 | 做 P1（状态栏）+ P2（面板）；消息区内部插行不做 |
| Q2 | 驱动方式 | **不用「每帧拉」，用 B 方案**：失效时收集 + 插件主动失效通知 |
| Q3 | 状态栏片段是否加 `pluginId` 前缀 | **不加**，插件自己决定显示什么 |
| Q4 | 行模型放哪 | 新开 `jellyfish-api` 的 `ui` 包（不放 `extension`） |
| Q5 | 区域（region）归属 | **归外壳**，插件只能给 `preferredRegion` 软建议 |
| Q6 | 区域集合 | `STATUS` / `DOCK` / `LEFT` / `RIGHT` / `TOP` |
| Q7 | 多插件抢同一区域的仲裁 | 用户用 `/ui` 命令切换；**`/ui` 是外壳自有命令**，不进内核命令注册表 |
| Q8 | 侧栏宽度策略 | 单侧 `[20, W/4]`；左右总宽 ≤ `W/3`；`W < 80` 时侧栏自动隐藏 |
| Q9 | `/ui` 切换状态是否持久化 | P2 先不持久化（重启回默认） |
| Q10 | 分期 | P1 → P2a → P2b |

## 1. 现状基线（实测）

### 1.1 为什么「插件直接给一个 TamboUI 组件」不可行

| # | 约束 | 实测依据 | 后果 |
| --- | --- | --- | --- |
| 1 | `jellyfish-api` 零依赖 | `jellyfish-api/pom.xml` 无任何依赖 | api 里无法出现 `Element` / `Style` / `Line`，扩展点结果类型声明不出来 |
| 2 | PF4J 插件类加载器**子优先** | `PluginClasspathGuard` 只拦 `zcd/jellyfish/api/**` 与 `org/pf4j/**`，插件可以自带 TamboUI | 即使插件自带 TamboUI，它 `return` 的 `Element` 与内核那份**不是同一个 `Class`**，回传内核必然 `ClassCastException`。这是类型系统层面的死路，不是规范问题 |
| 3 | 消息区必须是**单个 `richText`** | 「TUI 的渲染载体是单个 RichText」实测：布局容器子元素在 120～180 个处断崖（38ms/帧 → >3000ms/帧），单个 `richText` 承载 3000 行约 1.96ms/帧 | 「插件一个组件 = 一个 Element 塞进消息区」不可行 |

**结论**：插件只能贡献**渲染无关的数据**，渲染归 TUI。

### 1.2 渲染时机：TamboUI 本来就是周期性重绘（本方案的关键前提）

实测 `dev.tamboui.tui.TuiConfig`：

```
DEFAULT_POLL_TIMEOUT = 40    // ms
DEFAULT_TICK_TIMEOUT = 40    // ms
TuiConfig.builder() 的 Builder 构造器把 pollTimeout / tickRate 都置为 Duration.ofMillis(40)
ticksEnabled() == (tickRate != null) → true
```

即事件循环每 **40ms** 走一圈并重绘一帧。这正是流式文本能实时上屏的原因——`InflightTurn` 的 `volatile dirty` 就是靠它被消费的（`ChatState.view` 第 331～338 行）。

**由此得出两个结论**：

1. 「失效标记」**不需要跨线程投递**：订阅回调（EventChannel 线程）只写一个 `volatile boolean`，下一个渲染帧自然消费它。这与 `InflightTurn` 是同一套模式。
2. B 方案（失效时收集）**不比 A 方案（每帧拉）快**，它的收益是**更少调用**：A 是每秒 25 次 × N 个插件（空闲时也一样），B 是「每次失效一次」，**空闲时 0 次**。对 handler 里可能读文件/查状态的实现，这是决定性的差异。

备用通道（本方案不用，记录备查）：`TuiRunner.runLater(Runnable)` / `runOnRenderThread(Runnable)` 的实现是把 `UiRunnable`（`implements Event`）offer 进 `eventQueue`，由渲染线程取出执行，天然触发一次重绘。将来若需要「唤醒一个没有 tick 的渲染循环」，通道在这里。

### 1.3 `DockElement` 原生支持四边停靠（版式实现的关键前提）

实测 `dev.tamboui.toolkit.elements.DockElement` 的方法：

```
top(Element) / top(Element, Constraint)
bottom(Element) / bottom(Element, Constraint)
left(Element) / left(Element, Constraint)
right(Element) / right(Element, Constraint)
center(Element)
```

**五个区域可以一次 `dock()` 表达完，不需要手工嵌套 `row`/`column`**：

```java
dock()
    .top(topPanel, length(topRows))
    .left(leftPanel, length(leftWidth))
    .right(rightPanel, length(rightWidth))
    .bottom(bottom, length(bottomRows))
    .center(messagePanel);
```

**但账本仍必须在外壳里自己算**：`ChatState.view` 需要 `width` / `viewportRows` 来切片，框架不会告诉我们 `center` 剩了多少。因此：

> **硬约束**：所有区域约束一律用 `length(n)`（固定值），**不用 `percent` / `fill`**。用固定值，框架算出的 `center` 尺寸与我们账本算出的完全一致；用百分比就要复刻框架的取整规则，迟早对不上。

### 1.4 现有接缝（本方案不改内核）

| 接缝 | 位置 | 说明 |
| --- | --- | --- |
| `ExtensionRegistry.bindings(type)` | `infra/extension` | 连 owner 一起给，owner 就是 pluginId，用作 `/ui` 里的稳定标识 |
| `EventChannel.subscribe(owner, type, listener)` | `infra/event` | owner 可以是内核组件名（注释明示），外壳用 `"tui"` 订阅；`unsubscribeAll(owner)` 反注册 |
| `PluginContext.emit(event)` | `infra/plugin/PluginContextImpl` | 目前是裸的 `events.publish(event)`，**不带来源标记**——这是「失效粒度只能全量重问」的直接原因 |
| `PluginStateChangedEvent` | `api/event/notification` | **已存在**，插件加载/停止/卸载时广播。外壳订阅它就能白拿「插件热部署后界面自动更新」 |
| `ChatInputView.panelRows()` | `tui` | 账本另一端（底部高度） |
| `TranscriptProjector` / `ChatState` / `LineWrapper` | `tui` | 宽高本来就是参数，侧栏只是喂一个更小的宽度，**投影与滚动一行不用改** |

## 2. 目标态

### 2.1 扩展点清单（api）

| 形态 | 请求 → 结果 | 注册方式 | 多插件语义 |
| --- | --- | --- | --- |
| **拼接型**（状态栏） | `StatusLineContributionRequest` → `StatusLineContribution{text}` | `contribute`（类型级） | **拼接共存**，不需要切换 |
| **独占型**（面板） | `PanelContributionRequest` → `PanelContribution{title, lines, preferredRegion}` | `contribute`（类型级） | **抢占同一区域**，用户用 `/ui` 切换 |

**为什么区分「拼接型」与「独占型」**：状态栏的定位是「一眼扫读的运行态指标」，一行里放多段最自然；面板带边框、占多行，一块区域同一时刻只显示一个才不挤。这是形态差异带来的自然结果，不是额外规则。

### 2.2 区域表（外壳的能力集合）

| 区域 | 形态 | 位置 | 约束 | 本轮 |
| --- | --- | --- | --- | --- |
| `STATUS` | 拼接型单行 | 现有状态栏尾部 | `length(1)` | P1 |
| `DOCK` | 独占型面板 | 消息区与输入框之间 | `length(Σ行)` | P2a |
| `TOP` | 独占型面板 | 消息区上方 | `length(Σ行)` | P2b |
| `LEFT` | 独占型面板 | 消息区左侧 | `length(宽)` | P2b |
| `RIGHT` | 独占型面板 | 消息区右侧 | `length(宽)` | P2b |

### 2.3 B 方案的机制（核心）

```
TuiApp 持有：volatile boolean uiDirty = true;    // 首帧必须收集一次
             UiSnapshot uiCache;

render():
    if (uiDirty) { uiCache = uiContributions.collect(sessionId); uiDirty = false; }
    用 uiCache 渲染状态栏片段与各区域面板
```

**失效触发源（必须齐全——漏一个就等于插件内容永久陈旧，这是 B 的固有代价）**：

| # | 触发源 | 谁置标记 | 为什么必须有 |
| --- | --- | --- | --- |
| 1 | 首帧 | 构造期 | 初始收集 |
| 2 | **会话切换** | `syncSession`（比对 sessionId） | 贡献按会话给内容 |
| 3 | **回合开始 / 收敛** | `startTurn` / 回合终结 | 兜底：插件状态常随回合变化 |
| 4 | **命令执行后** | `executeCommand` | 兜底：插件自己的命令会改状态 |
| 5 | **插件加载/卸载/启停** | 订阅 `PluginStateChangedEvent` | 插件装上立刻出现、卸下立刻消失 |
| 6 | **插件主动失效** | 订阅新事件 `UiInvalidatedEvent` | 插件唯一的自救通道 |

第 5 条是白拿的：`PluginStateChangedEvent` 内核已经在发，外壳只需订阅。

## 3. 设计细节

### 3.1 api 契约

```
jellyfish-api/src/main/java/zcd/jellyfish/api/
├── extension/
│   ├── StatusLineContributionRequest.java   # 类型级（getRouteKey() 恒 null），带 sessionId
│   ├── StatusLineContribution.java          # {text}；of() / empty() / isEmpty()
│   ├── PanelContributionRequest.java        # 类型级，带 sessionId
│   └── PanelContribution.java               # {title, lines, preferredRegion}
├── ui/
│   ├── UiLine.java                          # List<UiSegment>
│   ├── UiSegment.java                       # {text, emphasis}
│   ├── UiEmphasis.java                      # NORMAL / DIM / ACCENT / WARN / ERROR
│   └── UiRegion.java                        # STATUS / DOCK / TOP / LEFT / RIGHT（**软建议值**）
└── event/notification/
    └── UiInvalidatedEvent.java              # 空事件（只用基类三个元信息）
```

**`UiEmphasis` 只给语义档位、不给颜色**：api 不能泄漏 TamboUI 的 `Style`；样式映射是外壳的事，唯一映射点在 `UiRender`。现成先例是 `ShellNotice.Kind → TranscriptProjector.styleOf`。

**`UiRegion` 的语义是「建议」而不是「位置」**：

- 插件写 `preferredRegion = LEFT` 只表示「我更想放左边」，外壳**可以完全忽略**；
- 外壳不认识某个枚举值（旧外壳 + 新插件）时落默认区域，不报错；
- 因此「外壳加一个区域」**不需要 api 先加枚举值**：外壳可以先支持 `TOP` 而 api 里还没有 `TOP`（插件用 `null` 走默认）。

**`UiInvalidatedEvent` 刻意不带字段**：语义就是「有插件改了它的 TUI 贡献，请重问」。带 `pluginId` 会诱导做按 owner 分片（见 §3.6，P2 不做）；带 `sessionId` 对全量重问没有影响。

**契约注释里必须写死的三条硬约束**：

1. handler 必须**纯只读**：只读自己的内存状态，**不做 I/O、不产生副作用**——它会被任一失效触发反复调用；
2. handler **不得 `emit` `UiInvalidatedEvent`**（否则死循环）；
3. 外壳**可能永远不会问**（CLI 单次模式不渲染 UI）、也可能忽略 `preferredRegion`，插件不能假设「我贡献了就一定显示、显示在我想的位置」——与 `PromptContributionRequest` 同口径。

### 3.2 二维账本（`ChatLayout`）

一维账本升到二维：

```java
// 现在（ChatShell）
messageAreaWidth(terminalWidth)                                   // = W - 边框*2
messageAreaRows(terminalHeight, inputPanelRows, overlayPanelRows)

// 之后（新的 ChatLayout，纯函数、可单测）
ChatLayout.compute(Size terminal, int inputPanelRows, int overlayRows, UiSnapshot ui)
    → MessageArea { int width; int rows; int topRows; int bottomRows; int leftWidth; int rightWidth; }
```

**计算顺序（先底/顶，再左右，最后消息区）**：

```
常量：
  BORDER           = 1
  STATUS_ROWS      = 1
  MIN_MESSAGE_ROWS = 5
  MIN_MESSAGE_WIDTH= 20
  SIDEBAR_MIN_W    = 20
  SIDEBAR_MAX_W    = W / 4
  SIDEBAR_TOTAL    = W / 3
  SIDEBAR_MIN_TERM = 80          // W < 此值时侧栏隐藏
  PANEL_MAX_ROWS   = 8           // 单个面板内容行上限
  REGION_MAX_ROWS  = min(H / 3, 12)   // 单个纵向区域（DOCK / TOP）总高上限

1. 各面板行数 = min(内容行数, PANEL_MAX_ROWS) + 2(边框)
   // 标题渲染在圆角边框首行上，不占额外行——现有 `overlayRows = lines.size() + BORDER_SIZE * 2` 就是这个口径
2. bottomRows = overlayRows + Σdock行数(≤ REGION_MAX_ROWS) + inputPanelRows + STATUS_ROWS
3. topRows    = Σtop行数(≤ REGION_MAX_ROWS)
4. messageRows = max(MIN_MESSAGE_ROWS, H - 2*BORDER - bottomRows - topRows)
5. leftWidth  = clamp(左栏内容宽 + 2, SIDEBAR_MIN_W, SIDEBAR_MAX_W)
   rightWidth = clamp(右栏内容宽 + 2, SIDEBAR_MIN_W, SIDEBAR_MAX_W)
   若 W < SIDEBAR_MIN_TERM → 两者归 0
   若 leftWidth + rightWidth > SIDEBAR_TOTAL → 保左栏，右栏归 0（记一次 WARN）
6. messageWidth = W - 2*BORDER - leftWidth - rightWidth
   若 messageWidth < MIN_MESSAGE_WIDTH → 依次收缩：右栏归 0 → 左栏归 0 → 仍不足则接受（不把消息区算成负数）
```

**为什么必须先底/顶、后左右**：底部高度由输入框内容决定（账本已有约定），顶部/底部不参与宽度竞争；左右侧栏是宽度竞争，且要先知道 `H` 才能定 `messageRows`（侧栏高度 = `messageRows`）。

**侧栏内容超长时截断**，不做侧栏内部滚动——「滚动」是消息区的语义，`ChatState` 是它唯一的家。侧栏高度永远等于 `messageRows`。

### 3.3 落位：区域归外壳

「插件有一块面板」与「这块面板显示在哪里」是两件事：

```java
// TuiApp / ChatState 持有的外壳视图状态（P2 不持久化）
Map<String, UiRegion> placement;    // uiKey(= pluginId) → 落位区域
Set<String> hidden;                 // 被用户 /ui off 的区域
```

**默认落位规则**：

| 情况 | 默认 |
| --- | --- |
| 插件给了 `preferredRegion` 且该区域可用 | 落到该区域 |
| 未给建议 / 建议的区域不可用 | `DOCK`（最通用的区域） |
| 同一区域已有他人 | 按 `RegisterOptions.order` 升序竞争，**默认只显示 order 最小者**，其余进 `/ui` 候选 |
| 用户 `/ui` 指定过 | 以用户选择为准，不再跟随 `preferredRegion` |
| 重启 | 回到默认（Q9） |

**为什么不把区域写进扩展点**（这是本方案最核心的取舍）：

1. **api 每加一个区域就长一次**——区域是外壳的布局能力，不该由契约定死；
2. **插件做的是盲选**：它看不到终端宽度，也不知道别的插件占了什么，「我想放左栏」这个决定没有依据；
3. **位置是竞争资源**（Q7 已证明），竞争资源必须有仲裁者，仲裁者只能是外壳 + 用户。

### 3.4 `/ui` 命令（外壳自有命令）

与 `/exit` 完全同口径：**只有这个外壳听得懂的东西，不注册进内核命令注册表**（CLI/server 没有区域概念，注册了只会让 `/help` 里出现一条对 `-cli` 毫无意义的条目）。

```
/ui                       列出所有贡献：区域 | pluginId | 标题 | 是否可见
/ui <region>              在该区域轮换到下一个（cycle）
/ui <region> <pluginId>   指定 pluginId 显示
/ui <region> off          关闭该区域
```

`region` 取值：`status` / `dock` / `top` / `left` / `right`。

**清单里能显示 `jellyfish-todo`**，因为 `DescriptorBinding.getOwner()` 就是 pluginId。这样同时满足 Q3（面板内容不强制带前缀）与「多插件可辨识」。

**改造点**：`ShellCommand` 现在只有 `/exit`（纯静态判定 → `TuiApp.submit()` 里 `quit()`）。加入 `/ui` 后：

- `ShellCommand.isShellCommand(text)` 扩成同时认 `/exit` `/quit` `/ui`（仍是纯函数，可单测）；
- `TuiApp.submit()` 里按名分派：`exit` → `quit()`；`ui` → `handleUi(text)`（需要实例状态，不能纯静态）；
- 补全清单里像 `EXIT_INFO` 一样塞一条 `UI_INFO`。

### 3.5 多插件仲裁（汇总）

| 问题 | 设计 |
| --- | --- |
| 面板上下/左右顺序 | `RegisterOptions.order` 升序，同序按注册顺序；同区域竞争时 order 最小者默认可见 |
| **一个插件贡献几个面板** | **至多一个**。多注册的取 order 最小者，其余记 WARN（否则一个插件能把自己刷满屏幕） |
| 状态栏多片段 | 每插件至多一段（按 owner 去重）；追加拼接；总宽超限时从**最后一个**起整块丢弃（不截断半个片段） |
| **失效粒度** | **全量重问**：一个插件失效 → 所有插件被重问一次 |
| 插件卸载 | 注册按 owner 回收 + `PluginStateChangedEvent` → 重问 → 自然消失，TUI 零特殊处理 |
| 模态浮层打开时 | 面板区整体隐藏（高度账本归零），但**缓存不清**——关掉浮层立刻恢复，不用重问 |
| **坏插件隔离** | 单 handler 抛错 → 该插件本次无贡献 + WARN，**其余插件不受影响**。多插件下这条最关键 |
| 日志刷屏 | 丢弃/异常只在**收集时**记一次 WARN，不在每帧记（收集本身稀疏，天然限流） |
| 逃生门 | `-Djellyfish.tui.pluginPanels=false` 整体关闭插件 UI（与 `jellyfish.tui.mouseCapture` 同风格） |

**为什么失效粒度选全量而不是按 owner 分片**：分片要维护「owner → 贡献」映射、在 `bindings()` 结果里按 owner 过滤、还要处理「插件没自报身份」——因为 `PluginContextImpl.emit` 目前是裸的 `events.publish(event)`，事件里根本没有来源。换来的是「避免 N−1 个纯内存 handler 被多调一次」，不值。等实测出现贵的 handler 再增量加，事件加字段是兼容改动。

### 3.6 渲染（`UiRender`）

自有文本模型 → TamboUI 的唯一转换点，与 `ChatShell.toLine` 分工明确：

| 转换 | 责任方 |
| --- | --- |
| `UiEmphasis → Style` | `UiRender.emphasisStyle(UiEmphasis)` |
| `UiLine → VisualLine`（含 CJK 宽度处理） | `UiRender.toVisualLines(PanelContribution, int width)` |
| `VisualLine → TamboUI Line` | `ChatShell.toLine`（已存在，唯一一处） |

- 超宽行按 `LineWrapper` 折行，超长内容按面板行数上限截断（**插件无权控制宽度与行数**，否则一个插件就能撑坏整版）；
- 行数上限截断时末行追加省略提示。

### 3.7 infra 门面 `UiContributions`

让 TUI **不认识** `ExtensionRegistry` / `EventChannel`（与 `CommandManager` 是同一口径：注册表是 infra 的事）：

```java
package zcd.jellyfish.infra.ui;

public final class UiContributions implements AutoCloseable {
    public UiContributions(ExtensionRegistry extensions, EventChannel events, String owner);

    /** 收集一次（全量、含 owner）：状态栏片段已拼接、面板按 order 升序、坏插件已隔离。 */
    public Snapshot collect(String sessionId);

    /** 订阅「贡献可能已过期」。回调可能在任意线程触发，外壳只应置标记，不得在此做重活。 */
    public Subscription onInvalidated(Runnable listener);

    @Override public void close();   // unsubscribeAll(owner)
}

public final class Snapshot {          // infra/ui，公开值类型
    private final String statusLine;          // 可为空串
    private final List<OwnedPanel> panels;    // 按 order 升序
}
public final class OwnedPanel {        // infra/ui
    private final String owner;               // pluginId，供 /ui 与去重使用
    private final PanelContribution contribution;
}
```

`owner` 固定 `"tui"`（`EventChannel.subscribe` 的注释明示 owner 可以是内核组件名）。

## 4. 文件改动清单

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| api | `extension/StatusLineContribution*.java`（新 ×2） | 请求 + 结果 |
| api | `extension/PanelContribution*.java`（新 ×2） | 请求 + 结果 |
| api | `ui/UiLine.java` `ui/UiSegment.java` `ui/UiEmphasis.java` `ui/UiRegion.java`（新 ×4） | 行模型 + 建议区域 |
| api | `event/notification/UiInvalidatedEvent.java`（新） | 空事件 |
| infra | `ui/UiContributions.java`（新） | 门面：收集 + 异常隔离 + 去重 + 失效订阅 |
| infra | `ui/Snapshot.java` `ui/OwnedPanel.java`（新） | 公开值类型 |
| tui | `ChatLayout.java`（新） | 二维账本（纯函数） |
| tui | `UiRender.java`（新） | `UiEmphasis → Style`、`UiLine → VisualLine` |
| tui | `DockPanel.java`（新） | `{title, List<VisualLine>}`，与 `Overlay` **同形但独立类型**（共用会让两套账本口径混淆） |
| tui | `ChatShell.java`（改） | `render(...)` 增 `UiSnapshot` 与区域面板；`bottomParts` 顺序固定为 `[dock面板, overlay?, input, statusLine]`；`messageAreaRows` 拆给 `ChatLayout` |
| tui | `UiCommand.java`（新） | `/ui` 的解析与文本渲染（纯函数，可单测） |
| tui | `ShellCommand.java`（改） | 认 `/ui` |
| tui | `TuiApp.java`（改） | 加 `UiContributions` 依赖；`uiDirty` + `uiCache`；6 个失效触发源；`/ui` 分派；补全清单加 `UI_INFO` |
| tui | `StatusBarView.java`（改） | 加 `appendFragments` 纯函数：把插件片段接在内核字段之后，按显示宽度从最后一个起整块丢弃（**与本文档初稿的「不改」有偏差，理由见 §11**） |
| cli | `JellyfishComponent.java`（改） | `+ ExtensionRegistry extensionRegistry(); + EventChannel eventChannel();`（各 1 行） |
| cli | `Launcher.java` `mode/TuiRunMode.java`（改） | 把 `UiContributions` 传到 `TuiApp` |

**「投影与滚动一行不改」**：`TranscriptProjector` / `ChatState` / `LineWrapper` 的宽高本来就是参数，侧栏只是喂一个更小的宽度。

## 5. 测试计划

| 层 | 测试类 | 覆盖 |
| --- | --- | --- |
| api | `StatusLineContributionTest` | `of`/`empty`/空白文本归空、不可变 |
| api | `PanelContributionTest` | `title` 可空、`lines` 不可变、`preferredRegion` 可空 |
| api | `UiLineTest` | 段拼接、`UiSegment` 空文本、`UiEmphasis` 兜底 |
| api | `UiInvalidatedEventTest` | 基类元信息（sessionId 为 null） |
| infra | `UiContributionsTest` | order 升序；**面板按 owner 去重 + WARN**；**单 handler 抛错不影响他人**；空贡献过滤；状态栏拼接与超宽丢弃；`onInvalidated` 被 `UiInvalidatedEvent` 与 `PluginStateChangedEvent` 触发；`close` 反注册 |
| tui | `ChatLayoutTest` | 底部优先；`messageRows` 保底 5 行；侧栏 `W<80` 隐藏；`W/3` 超限保左栏；`messageWidth` 不足时依次收缩；**用 `length` 的账本与 `DockElement` 实际分配一致**（以固定尺寸断言） |
| tui | `UiRenderTest` | 五种 `UiEmphasis → Style` 映射；CJK 折行宽度；超行数截断 + 省略提示 |
| tui | `UiCommandTest` | 清单文本；`cycle` 轮换；指定 pluginId；`off`；非法区域与非法 pluginId 的错误文案 |
| tui | `ShellCommandTest`（改） | 认 `/ui` 与 `/ui dock`；`/exit now` 仍退出 |
| tui | `TuiAppTest` | `uiDirty` 被 §2.3 中 6 个触发源置位（把置位逻辑抽成可断言的纯方法，不启动 `ToolkitRunner`） |
| tui | `ChatShellTest` | 面板出现时消息区行数相应减少；模态浮层打开时面板区归零 |

**不测**：真终端渲染（沿用 `tui方案.md` 的 PTY 实测口径，不进单测）。

## 6. 实施阶段（每阶段结束都可编译、可测试、可验收）

### P1 —— 状态栏片段（拼接型最小闭环）

1. api：`StatusLineContributionRequest` / `StatusLineContribution`。
2. infra：`UiContributions`（只做状态栏部分）+ `Snapshot`；`onInvalidated` 订阅 `UiInvalidatedEvent` + `PluginStateChangedEvent`。
3. api：`UiInvalidatedEvent`。
4. tui：`TuiApp` 的 `uiDirty` + `uiCache` + **6 个失效触发源全量落地**；状态栏尾部追加片段（`DisplayWidth` 计算可用宽度，超限从最后一段整块丢弃）。
5. cli：接线。
6. 测试 + 一个演示插件（可以直接用 `jellyfish-plugin-todo`：状态栏显示 `3/5`）。

**零账本改动**——这正是 P1 作为「最小闭环」的定位：先把「贡献 → 缓存 → 失效 → 渲染」这条链路跑通并可验收，再动版式。

### P2a —— 面板骨架（`DOCK`）

1. api：`PanelContributionRequest` / `PanelContribution` / `ui/*`（行模型 + `UiRegion`）。
2. tui：`ChatLayout`（二维账本）+ `UiRender` + `DockPanel`；`ChatShell` 改版式（`DockElement` 五边停靠，只用 `length`）。
3. tui：`UiCommand` + `ShellCommand` + `TuiApp` 的 `/ui` 分派；落位状态与默认策略。
4. infra：`UiContributions` 加面板收集（order、owner 去重、异常隔离）。
5. 测试。

### P2b —— `TOP` / `LEFT` / `RIGHT`

1. 复用 P2a 骨架，只加区域 + 宽度策略 + `W<80` 自适应隐藏（全部落在 `ChatLayout` 一处）。
2. 测试。

**为什么 P2a/P2b 拆开**：P2a 已经把「二维账本 + 区域抽象 + `/ui` 切换 + 失效机制」全部搭好，P2b 只剩加区域和调预算。拆开后 P2a 出问题不会连累侧栏，也不会让侧栏的宽度策略问题掩盖骨架问题。

## 7. 验收标准

### 7.1 功能

- 插件注册 `StatusLineContribution` 后，片段出现在状态栏尾部；插件 `emit` `UiInvalidatedEvent` 后内容在下一帧更新；
- 插件注册 `PanelContribution` 后，面板出现在 `DOCK`；`title` 显示为面板标题；
- 两个插件抢同一区域时，默认只显示 order 最小者；`/ui dock <pluginId>` 可切换；`/ui dock off` 可关闭；
- 插件热部署（`PluginStateChangedEvent`）后，界面自动出现/消失对应面板，无需用户操作；
- 插件 handler 抛异常时，该插件本次无贡献，其它插件正常显示，日志有一条 WARN。

### 7.2 版式

- `W ≥ 80` 且面板不超预算时，消息区高度 = `H − 底 − 顶 − 边框`（`ChatLayoutTest` 断言）；
- `W < 80` 时侧栏不显示，消息区宽度不受影响；
- 模态浮层（补全 / 二级选择页）打开时面板区隐藏，关闭后恢复且**不重新收集**（缓存未失效）。

### 7.3 既有契约不回归

- `mvn test` 全绿，TUI 既有用例（`ChatStateTest` / `TranscriptProjectorTest` / `CommandCompletionTest` / `CommandChoicePickerTest` / `InputKeyMapperTest` …）一条不改；
- `-cli` 模式下 `UiContributions` **一次都不被调用**（CLI 没有 UI 贡献概念）。

## 8. 已知限制与后续 TODO

| # | 限制 | 说明 |
| --- | --- | --- |
| L1 | 状态栏只有一行 | 插件片段空间 = `W − 内核四段 − 余量`（通常 30～60 列）。若实测不够，升级路径是「状态栏 2 行」（`STATUS_ROWS` 常量 → 动态，账本改一处） |
| L2 | 侧栏不支持内部滚动 | 超长内容截断。要做侧栏滚动需要「每个区域一套滚动状态」，与本轮「滚动只有一份」的口径冲突 |
| L3 | `/ui` 切换不持久化 | 重启回默认（Q9）。后续可入 `jellyfish.json` 的 `tui` 段，或外壳私有偏好文件 |
| L4 | 失效粒度是全量 | 见 §3.5。后续可给 `UiInvalidatedEvent` 加 `pluginId`，做按 owner 分片 |
| L5 | 不支持消息区内部插行 | 与滚动/跟随耦合太深（「插在哪一行」没有稳定语义），暂不做 |
| L6 | 不支持插件组件接收按键 | 键位归属是全局稀缺资源（`Ctrl+S` / `Esc` / `PageUp` / `↑↓` 已占），且 handler 在渲染线程内联执行。插件要交互就注册命令（`/xxx`） |
| L7 | 区域集合固定 | 加区域要改 `TuiApp`/`ChatLayout`/`UiCommand`（外壳侧），**不需要改 api 契约**——这正是 §3.3 那个取舍的收益 |

## 9. 风险与陷阱

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | **漏掉一个失效触发源 → 插件内容永久陈旧**（B 方案的固有代价） | §2.3 的 6 个触发源作为验收项逐条测；插件侧契约要求「状态变化必须 `emit`」 |
| R2 | 账本与 `DockElement` 实际分配不一致 | 全区域只用 `length()`；`ChatLayoutTest` 用固定尺寸断言；真机 PTY 实测一次 |
| R3 | 面板把消息区挤没 | `MIN_MESSAGE_ROWS = 5`、`REGION_MAX_ROWS`、`PANEL_MAX_ROWS` 三层限制；`/ui off` 与系统属性逃生门 |
| R4 | handler 做 I/O 拖慢帧 | 契约写死「纯只读、无 I/O」；日志记录单次收集耗时，超阈值 WARN |
| R5 | 失效事件死循环（handler 里 `emit`） | 契约写明禁止；`UiContributions` 在收集期间**照常转发**并记 WARN——丢弃看似能避开自激，却会把「渲染线程刚开始收集、失效恰好到达」这个**正常竞态**一并吞掉；真正的不丢靠 `UiCache` 的版本号 |
| R6 | 侧栏把 CJK 内容算错宽度 | 统一走 `DisplayWidth`；`UiRenderTest` 覆盖 CJK |
| R7 | 插件把 `preferredRegion` 当契约用 | 文档 + 类注释写明是软建议；外壳忽略时不报错 |

## 10. 裁决记录

### 10.1 本轮确认（Q1～Q10）

见 §0。其中三条是本方案的地基，单独强调：

1. **Q5 区域归外壳**——它同时解决了「api 不随区域增长」与「Q7 用户切换」两个问题；
2. **Q2 B 方案**——它的收益是「空闲时零调用」而不是「更快」，前提是 TamboUI 的 40ms tick（§1.2 实测）；
3. **Q7 `/ui` 归外壳**——与 `/exit` 同口径，不进内核命令注册表。

### 10.2 我提出、用户未反对即采纳的（若不同意请指出）

| # | 决定 | 理由 |
| --- | --- | --- |
| D1 | `DockPanel` 与 `Overlay` 分开 | 同形不同语义，共用会让「模态浮层账本」与「常驻面板账本」混成一套 |
| D2 | 一个插件在**每个区域**至多一块面板 | 否则一个插件能把自己刷满屏幕 |
| D3 | 状态栏每插件至多一段 | 同上 |
| D4 | `UiInvalidatedEvent` 不带字段 | 不为将来可能的优化提前加字段；加字段是兼容改动 |
| D5 | 超限时整块丢弃而不是截断 | 截断出来的半个片段/半行毫无意义，还会让用户以为插件坏了 |
| D6 | 面板高度预算的三个数字（8 / H÷3 且 ≤12 / 消息区至少 5 行） | 待实测调整 |
| D7 | `UiContributions` 放 `infra/ui`（新包） | 外壳中立，将来 `jellyfish-server` 可直接复用 |
| D8 | 逃生门 `-Djellyfish.tui.pluginPanels=false` | 与既有 `jellyfish.tui.mouseCapture` 同风格 |

## 11. 落地记录

### 11.1 P1（状态栏片段）—— 已完成

| 层 | 交付物 |
| --- | --- |
| api | `StatusLineContributionRequest` / `StatusLineContribution`（`extension/`）、`UiInvalidatedEvent`（`event/notification/`） |
| infra | `ui/UiContributions`（收集 + 异常隔离 + 按 owner 去重 + 失效订阅）、`ui/UiSnapshot` |
| tui | `UiCache`（失效时收集）、`StatusBarView.appendFragments`、`TuiApp` 接入与**六处失效触发源** |
| cli | `JellyfishComponent` 加 `extensionRegistry()` / `eventChannel()`；`TuiRunMode` 创建并释放 `UiContributions`（owner = `tui`） |
| 插件 | `jellyfish-plugin-todo` 注册状态栏贡献（`待办 2/5`），并在 `todo_write` 之后广播 `UiInvalidatedEvent` |

### 11.2 与初稿的偏差（已确认口径一致）

1. **拼接与丢弃归 TUI，不归 infra**：初稿写着「`StatusBarView` 不改，插件片段由 `TuiApp` 追加并截断」，实现时改成了 `StatusBarView.appendFragments`。
   真正的原因是**片段边界必须留到知道可用宽度的那一刻**：infra 不知道终端有多宽，若它在里面把片段拼成一个字符串，
   TUI 就只剩「按字符截断」这一个选择，而「整块丢弃」需要一个能算**显示宽度**（CJK 两列）且能看见片段边界的纯函数。
   因此 `UiSnapshot` 交出去的是**片段列表**而不是拼好的字符串，拼接与丢弃都在 `StatusBarView` 里完成——它本来就是状态栏的渲染器，没有多一份状态。
2. **`onInvalidated` 返回 `void` 而不是 `Subscription`**：订阅由 `UiContributions` 内聚（`close()` 时逐个解除），
   外壳没有「提前只解除其中一个」的需求；返回句柄只会让每个调用点多一个必须处理的返回值（`Subscription` 刻意不继承 `AutoCloseable`，但没人要的值仍然要有人丢）。
3. **快照类型叫 `UiSnapshot`**（初稿写的是 `Snapshot`）：本仓库里 `Snapshot` 这个词已经属于会话持久化（`SessionSnapshot`），裸 `Snapshot` 放在 `infra/ui` 里读起来像一个全局概念。
4. **`UiCache` 自己也比对 `sessionId`**：初稿把「会话切换要失效」当成外壳必须记住的调用点，实现时把它降级为双保险
   （`TuiApp.syncSession` 照旧显式置失效）——这样即使将来多一个改会话的路径忘了置，也不会显示上一个会话的片段。
5. **`TodoStatusLine` 有一处有意识的契约偏离**：UI 贡献契约要求处理器纯只读、不做 I/O，而 `TodoStore` 在某个会话首次被访问时会懒加载一次它的文件。
   不读就不知道有别的待办，状态栏会在「本来有待办的会话」上一直空着。折中是：**每会话至多读一次**，之后全部命中内存缓存，且只读当前会话那一个文件。
6. **失效标记是版本号而不是布尔 `dirty`**：布尔标记在「渲染线程刚读到 false 并开始收集、事件线程此时置 true、收集结束又清回 false」这个竞态下会**静默丢掉那次失效**。
   改成「收集开始前读版本、收集后回写该版本」后，收集期间发生的失效会让版本对不上，下一帧自然再收集。
   由此 `UiContributions` 在收集期间也不再丢弃通知（§9 R5 的缓解手段随之一并修正）：丢弃会连正常竞态一起吞掉，自激只能靠「把处理器违规 loud 出来」去根除。
   `UiCacheTest` 用「处理器在收集过程中置失效」确定性地复现了这个竞态。

### 11.3 验收结果

- 全量 `mvn test`：**1401 用例，0 失败 0 错误**（P1 新增 55 个）。
- `-cli` 模式下 `UiContributions` **一次都不被创建**：它不在任何 Dagger 模块里，只由 `TuiRunMode.run()` 现场创建，
  因此「只做单次调用」的路径上不可能有插件 UI 调用（对应 §7.3 的验收项）。
- 待办插件的 `todo_write` → `UiInvalidatedEvent` → 缓存失效 这条链路有端到端单测（`TodoPluginTest`）。

### 11.4 尚未开始

P2a（面板骨架 + `DOCK` + `/ui`）与 P2b（`TOP` / `LEFT` / `RIGHT`）按 §6 分期推进；本轮的 `UiSnapshot` / `UiContributions`
已经是它们的长大点（加面板时只需加一个收集方法与非空结束条件）。
