# 设计草案：插件 → 外壳的贡献通道（ShellContribution）

> **状态：已落地（决策见第 11 节）。** 它是 [总纲 `kernel-conversation-runtime.md`](kernel-conversation-runtime.md)
> 的 P3 分册；硬规则已经迁入 [`constraints/extensions.md`](../constraints/extensions.md) 与
> [`constraints/shells.md`](../constraints/shells.md)，架构决策已经迁入 [`architecture.md`](../architecture.md)。
> 这两份约束是后续改动的权威口径，本文保留的是**设计与取舍的推导**，以及落地时与初稿的差异（见第 13 节）。

---

## 1. 目标与非目标

### 目标

1. **插件能把内容实时/准实时推给外壳渲染**，而不是今天这样「发个事件没人看，或者改脏标记等外壳下一帧来拉」。
2. **三个外壳共用一条内核通道**：合并点在内核做一次，外壳只做「订阅 → 映射到自己的呈现」。
3. **渲染仍归外壳**：插件只贡献渲染无关数据（复用 `api/ui` 的 `UiLine` / `UiSegment` 纪律）。
4. **失败隔离、不阻塞回合、可丢可合并**：一个坏插件不能把界面或反应线程拖住。
5. **与既有三条通道边界清晰**：同步扩展点 / `EventChannel` / `ActionQueue` 各自职责不变。

### 非目标（明确不做）

| 不做 | 理由 |
| --- | --- |
| 让插件直接写**会话事件流**（不可丢） | 会拆掉「可丢 / 不可丢」分离；慢插件能挂住回合；插件能伪造内核事件 |
| 让插件**新开会话或回合** | 硬约束，见第 2 节；这条不因本设计而放宽 |
| 让插件**推送状态**（面板 / 状态栏内容） | 状态用拉取，推送只承载「事件」。两套真源是刻意排除的（见第 4 节原则 1） |
| 开放**交互请求**（插件弹确认框问用户） | 安全敏感；已有的先例是「插件只派发 `/命令`，由命令域承担审计与错误处理」 |
| 引入第三方事件总线 | 与 `constraints/extensions.md` 既有禁令一致 |

---

## 2. 硬约束：插件不能新开会话

这条必须在设计里被**结构性保证**，而不是靠文档约定。四道闸：

1. **类型上没有这个能力**：`ShellContribution` 只有 `NOTICE` / `INVALIDATED` 两种 kind，
   都不携带「发给谁」「什么内容给模型」这种载荷。贡献是**展示数据**，不是请求。
2. **无会话不自动建**：`Scope.SESSION` 的贡献必须指向一个**已存在**的会话；
   `sessionId` 为空、空白或查不到时，投递当场返回 `DROPPED_NO_SESSION`——
   **绝不调用 `SessionManager.create`**。
3. **不写模型上下文**：贡献**不产生 `LlmMessage`**、不参与 prompt 组装、不进 `SessionMessage`。
   与 `SessionExtensionEntry` 同一口径（「条目不进模型上下文」）。
   插件要让模型看见东西，唯一通路仍是 `ActionQueue.submit(PluginAction.sendUserMessage(...))`，
   而它**依然要求存在在途顶层回合**（`NO_TURN_IN_FLIGHT` 语义完全不变）。
4. **不落盘**：`NOTICE` 是临时显示，进程重启即消失。要持久的状态走既有的
   `PluginContext.putExtensionEntry` + `INVALIDATED` 回读，真源始终是会话文件。

**落地时的机械校验**（防止后人绕过）：`ShellIngress` 的构造器**不注入** `SessionManager` 的写入口与
`AgentHarness`；写一条测试断言它没有任何创建会话 / 起回合的调用路径。

---

## 3. 现状（本设计要解决的缺口）

| 插件今天能做什么 | 路径 | 到外壳了吗 |
| --- | --- | --- |
| `PluginContext.emit(PluginNotificationEvent)` | → `EventChannel`（有界队列、可丢广播） | ❌ **没有外壳订阅它**；只有 `UiContributions` 订阅 `UiInvalidatedEvent` / `PluginStateChangedEvent` 置脏标记 |
| 插件工具的 `onToolCallOutput` | → `ReActListener` | ✅ 到了，但只在**插件自己的工具**、且**回合在跑**时 |
| `PluginContext.putExtensionEntry` | → `SessionExtensionEntry` | ⚠️ 落了会话，但**拉取式**，不是推送 |
| `PluginContext.submit(PluginAction)` | → `ActionQueue` | ❌ 反方向（插件 → 内核） |

结论：插件**没有任何准实时推送路径**，最多做到「改脏标记，等外壳下一帧来拉」。

同时，**「内容可能过期」的触发源今天是分散的**：`constraints/shells.md` 列了六个
（首帧、会话切换、回合开始、回合收敛、命令执行后、`UiInvalidatedEvent`、`PluginStateChangedEvent`），
并明确「漏一个就是内容永久陈旧」。这六个现在写在 `TuiApp` 的调用点里，`-server` / `-cli` 没有等价物。

---

## 4. 设计原则

1. **推送事件，拉取状态**。通知 / 失效提示走推送；面板、状态栏、会话条目的**内容**仍走拉取
   （`PanelContributionRequest` / `StatusLineContributionRequest` / `SessionExtensionEntry`）。
   把状态也改成推送会立刻产生第二份真源——这是明确要避免的。
2. **统一机制，但不合并通道：一套机制、两条 lane，可丢性挂在 lane 上**。
   - **可靠 lane**（`subscribe`）：内核回合事件（正文 / 思考 / 工具 / 回合终态）。**不可丢、有序、单来源**。
   - **尽力 lane**（`subscribeShell`）：插件贡献（`NOTICE` / `INVALIDATED`）。**可丢、可合并、多来源**。

   两条 lane 共用一套机制（一个内核路由入口、一套事件模型、一套订阅 API 形状、一套 owner 回收），
   但**各自的可丢性在通道级就确定**，不靠逐事件判断。外壳调的是同一族 API，
   内核仍是唯一路由点——这正是「统一机制」的含义，而 `architecture.md` 的
   「可丢与不可丢不能合成一条路」**仍然成立**。

   两条 lane **不需要互相排序**：按决策 1，`SESSION` scope 的 `NOTICE` 不进消息序列，
   所以它与回合事件之间根本没有「谁在前」的需求——合并成一条流反而只会白白引入排序难题。
3. **内核是唯一的合并点**。外壳不各自去订阅 `EventChannel` 再过滤合并——那就是「每套外壳实现一次」。
   注意「唯一合并点」指的是**路由**：外壳不需要认识 `ExtensionRegistry` / `EventChannel` / `ShellIngress`，
   只需要认识「可靠 lane + 尽力 lane」这一族订阅。
4. **词汇表封闭**。插件能贡献的 kind 是一份**有界清单**（与 `PluginAction` 同一思路），
   插件**永远拿不到** `TurnTextDelta` / `TurnCompleted` 这类内核事件的构造权。
5. **类型即地址 + owner 命名空间**。贡献按 owner 隔离与回收，与注册表、`ActionQueue` 同一套规则。
6. **入站不阻塞**。`present(...)` 只入队，绝不阻塞调用方（可能是插件自己的线程）。

---

## 5. 类型模型（`jellyfish-api`）

### 5.1 `ShellContribution`

包位置：`api/extension`（与其它扩展点值类型同处）。
构造：私有构造器 + 静态工厂（遵守「新字段只能用新静态工厂补，不加兼容构造器」）。

```java
public final class ShellContribution {

    /** 作用域：会话级（进入消息流）还是外壳级（通知 / 状态区）。 */
    public enum Scope { SESSION, SHELL }

    /** 封闭的贡献种类。 */
    public enum Kind { NOTICE, INVALIDATED }

    /** 严重程度，映射到外壳的中性 / 警示 / 错误样式。 */
    public enum Severity { INFO, WARN, ERROR }

    // scope / kind / sessionId / key / severity / lines / what  —— 全 final

    /** 一条通知：一段可渲染文本，可丢、可被同 key 覆盖。 */
    public static ShellContribution notice(Scope scope, String sessionId, String key,
                                           Severity severity, java.util.List<UiLine> lines) { ... }

    /** 一条失效提示：「我贡献的内容脏了，请重新拉取」。 */
    public static ShellContribution invalidated(Scope scope, String sessionId, String what) { ... }
}
```

字段语义：

| 字段 | 语义 | 边界 |
| --- | --- | --- |
| `scope` | `SESSION` 进消息流；`SHELL` 进外壳通知区 | 无会话时必须用 `SHELL` 或接受 `DROPPED_NO_SESSION` |
| `kind` | `NOTICE` 有内容；`INVALIDATED` 无内容 | 封闭枚举，插件不能扩展 |
| `sessionId` | `SESSION` scope 必填且必须已存在 | **不自动建会话** |
| `key` | 非空时「同 owner + 同 key 的后到者覆盖先到者」 | `null` 表示不做合并；用于进度类通知原地更新 |
| `severity` | 见 `UiEmphasis` 同口径：语义，不是颜色 | 外壳映射，插件不猜颜色 |
| `lines` | `UiLine` 列表（渲染无关，宽度/折行/截断归外壳） | 行数由外壳封顶；**空 = 不显示**，不是「清空」 |
| `what` | `INVALIDATED` 的目标提示，`null` = 「我的全部贡献都脏了」 | 只是提示，不是协议：外壳可以忽略它并全量重拉 |

**为什么 `NOTICE` 用 `lines` 而不是纯字符串**：与 `PanelContribution` 同一口径。
纯字符串会逼插件用 ANSI 转义序列表达强调，而转义序列不占显示列、会破坏外壳的折行与宽度计算
（`UiSegment` 的类注释已论证过这条）。

**为什么 `what` 只是提示**：一旦外壳被要求「按 `what` 精确重拉某一块」，
`what` 就变成了跨边界的协议标识符（插件与外壳必须对同一套取值），
而它的全部价值只是「省一次全量重拉」。取值由外壳定义、插件也拿不到该枚举，
所以这里刻意只给一个自由文本，语义是「尽力帮忙定位」。

### 5.2 投递结果

```java
public enum ShellContributionStatus {
    ACCEPTED,              // 已入队，稍后交付给外壳
    COALESCED,             // 与同 owner + 同 key 的未交付项合并（只保留本次）
    DROPPED_QUEUE_FULL,    // 该 owner 队列已满
    DROPPED_NO_SESSION,    // SESSION scope 但会话不存在 / 未提供
    DROPPED_NO_RENDERER    // 当前外壳不渲染贡献（例如 -cli 单次模式）
}
```

**为什么要有返回值而不是 `void`**：`emit` 是 `void`，因为它发布给订阅者、没有「送没送到」的概念；
而贡献的消费者是具体的那个外壳进程，插件需要知道「我这条进度是不是把队列冲爆了」。
取值保持**扁平枚举**（与 `ActionFailureReason` 同思路：机器可读的那一份），
但**不提供 `ActionHandle` 式的轮询句柄**——贡献是即发即忘的，没有终态可等。

### 5.3 `PluginContext` 新入口

```java
/**
 * 向当前外壳贡献一条可渲染内容或失效提示。
 * <p>只入队，不阻塞调用方；stop() 之后调用当场抛 JellyfishException（与 emit / submit 同边界）。
 */
ShellContributionStatus present(ShellContribution contribution);
```

**为什么不是复用 `emit`**：`emit` 的语义是「发布 `JellyfishEvent` 给订阅者」，它走 `EventChannel`
（无界订阅者集合、可丢广播、无交付确认）。贡献是「给**当前这一个**外壳的一份载荷」，
两者在订阅者模型与交付语义上都不同。把贡献塞进 `emit` 会让「订阅者」与「外壳」两个概念混在一起，
而外壳恰恰是刻意**不订阅** `EventChannel` 的（它的队列是进程内的、可丢的，而外壳需要的是可寻址的一条流）。

---

## 6. 运行时（`jellyfish-infra`）

### 6.1 `ShellIngress`

位置：`infra`，与 `ActionQueue` 同级。理由相同：入队入口是 `PluginContextImpl`（属于 `infra.plugin`），
而 `core` 依赖 `infra`，反过来不成立。

职责：**只负责「存、限、合并、取、丢」**，不负责渲染，也不知道外壳长什么样。

- **按 owner 分桶**：每个 owner（插件标识，含 `::` 子命名空间）一个有界 `ArrayDeque`。
  一个插件的洪水只填满自己的桶。
- **合并**：`NOTICE` 且 `key != null` 时，若桶里已有同 `(owner, key)` 的未交付项，就地替换并回 `COALESCED`。
  合并只在**未交付**范围内做（已交付给外壳的无法回收）。
- **容量与丢弃**：每 owner 容量 `CAPACITY`（初值 64，待定，见第 11 节）。满了直接丢**最新**一条并回
  `DROPPED_QUEUE_FULL` + 计数。丢最新而不是丢最旧：进度类通知里「最新」最有价值，
  而丢最旧会让界面停在中间态。**任何情况下不阻塞 `present`**。
- **回收**：插件 `stop()` 时整桶丢弃（与 `ActionQueue` 同规则、同一时刻），
  并 `reset(owner)`；之后 `present` 抛 `JellyfishException`（由 `ContextLifecycle` 保证）。
- **计数**：每 owner `accepted` / `coalesced` / `dropped`，进 `plugin.*` 指标，供诊断。

### 6.2 交付路径（两条 lane，一套机制）

```
可靠 lane（内核拥有，不可丢，有序，单来源）
  内核回合事件（react / llm-stream 线程，同步扇出）
     └─→ ShellTurnStream ──→ subscribe(sessionId, listener) ──→ 外壳

尽力 lane（内核拥有，可丢，可合并，按 owner 隔离）
  插件贡献（任意线程 → ShellIngress → drain）
     └─→ ShellContributionStream ──→ subscribeShell(listener) ──→ 外壳
```

**两条 lane 的共同点（这就是「统一机制」）**：

- 同一个内核拥有者（`core` 的 `ShellStreams` 门面），外壳不碰 `EventChannel` / 注册表；
- 同一族订阅 API 形状（`subscribe` / `subscribeShell`，均返回 `Subscription`）；
- 同一套生命周期（`start` / `close` 与 `EventChannel` 同级，幂等）。

**两条 lane 的差异（这就是「可丢性一眼可辨」）**：

| | 可靠 lane | 尽力 lane |
| --- | --- | --- |
| 内容 | 内核回合事件 | 插件贡献 |
| 丢失 | **不丢**（同步扇出，无队列） | **可丢**（每 owner 有界队列 + 合并） |
| 顺序 | 单来源、严格有序 | 同 owner FIFO；跨 owner 不承诺 |
| 跨进程适配（Server） | 保持今天的「无界队列 + 断连即取消回合」 | 入 SSE 前按条数封顶，超出直接丢并计数 |
| 生产者 | 内核（不可由插件触发） | 仅插件（`present`） |

- **插件结构性碰不到可靠 lane**：`PluginContext` 上没有任何方法能写可靠 lane；
  `ShellIngress` 只接插件贡献。这条比「靠 origin 标签区分」强得多——它把「插件不能伪造内核事件」
  从运行时校验变成了类型上不可能。
- **订阅者慢不得阻塞回合**：可靠 lane 的订阅者实现只允许「写入线程安全的本地缓冲 / 投进本地队列」
  （TUI 的 `InflightTurn`、Server 的 SSE 队列已是这个形态）；尽力 lane 的订阅者无论多慢都不会
  影响回合，因为它的队列在 `ShellIngress` 里且满即丢。
  这与今天 `ReActListener` 的既有契约一致，不是新增负担。

### 6.3 与失效触发源统一

`INVALIDATED` 覆盖两类来源，**分属不同 lane**：

- 插件主动：`present(ShellContribution.invalidated(...))` → **尽力 lane**；
- 内核侧：`PluginStateChangedEvent` → **尽力 lane**（可丢，丢了下次自然事件会再触发）；
- 回合开始 / 收敛 → **可靠 lane 自带的回合事件**，外壳在收到时就置脏，不再需要额外的失效事件；
- 会话切换 / 命令执行后 → 外壳自己发起的动作，**本地即可知道**（Server 是 HTTP 响应、
  TUI 是命令执行点），不需要跨 lane 通知。

外壳因此**不再需要维护六处 `uiCache.invalidate()` 调用点**，而是「收到任一条 lane 的事件就置脏
（外加本地动作）」——比「逐条判断可丢性」简单，也不破坏 lane 语义。
既有的 `UiInvalidatedEvent` **保留**为兼容入口（映射进尽力 lane），不删。

---

## 7. 与既有机制的边界

| 机制 | 方向 | 可丢 | 用途 | 本草案 |
| --- | --- | --- | --- | --- |
| `ExtensionRegistry.handle/contribute` | 内核 → 插件 | 不可丢（有返回值） | 工具、命令、面板 / 状态栏**拉取**、权限… | **不变**；变的是「何时拉」由统一失效事件驱动 |
| `PluginContext.observe` / `emit`（`EventChannel`） | 插件 ↔ 订阅者 | **可丢广播** | 插件间通信、可观测性、审计 | **不变**；明确它不是外壳通道 |
| `PluginContext.submit`（`ActionQueue`） | 插件 → 内核 | 有界 / 可失败 | 发消息、压缩、切模型、分支、重建工具清单 | **不变**；仍只在在途顶层回合内 |
| `PluginContext.putExtensionEntry` | 插件 → 会话 | 不可丢（落盘） | 会话级持久状态 | **不变**；配 `INVALIDATED` 实现准实时刷新 |
| `UiInvalidatedEvent` | 插件 → 订阅者 | 可丢 | 拉取失效 | **吸收**进统一失效事件；旧入口保留 |
| **`present`（`ShellIngress`）** | 插件 → 外壳 | **可丢 / 可合并** | 通知 + 失效提示 | **新增** |

---

## 8. 失败语义与不变量（逐条）

1. **0 贡献者时行为不变**：没有插件调用 `present` 时，外壳看到的流与改造前逐字段一致
   （这条是 `constraints/extensions.md` 对新增扩展点的硬要求）。
2. **插件处理器抛错隔离**：`INVALIDATED` 触发的拉取里，单处理器抛错只记 WARN 并跳过
   （沿用 `UiContributions` 既有行为）。
3. **`present` 不抛业务异常**：除「已 stop」这一条外全部用 `ShellContributionStatus` 回报。
4. **队列满只丢不阻塞**：见 6.1。
5. **不自动建会话**：见第 2 节。
6. **不写模型上下文 / 不落盘**：见第 2 节。
7. **外壳侧渲染上限**：插件通知在外壳的显示条数 / 行数有上限，超出按时间或严重程度淘汰
   （避免一个插件刷屏）。
8. **关停顺序**：插件停止时先 `ShellIngress.reset(owner)`，外壳随后可能收到一条
   `PluginStateChangedEvent` 触发的失效重拉，此时该 owner 的贡献已清空——顺序不能反。
9. **不可信文本必须过滤控制字符**：`lines` 里的文本来自插件，可能含 `ESC` 等控制序列。
   过滤是**硬要求**（沿用 `ToolArgumentsText` 与 `ReActListener.onToolCallStarted` 的既有纪律：
   过滤控制字符是安全要求，与「脱敏」不是一回事）。过滤点放外壳（渲染面统一），内核不做内容改写。
10. **`NOTICE` 丢弃不重试**：`DROPPED_QUEUE_FULL` 是「这条显示没赶上」，不是「操作失败」。
    插件不应据此重发——那会把一次洪水放大成持续洪水。只有 `INVALIDATED` 值得稍后重发
    （它是状态触发的，重发是幂等的）。这条必须写进 `present` 的 Javadoc，否则一定会被误用。

---

## 9. 测试计划（要点）

- `ShellContribution`：**唯一可见构造器**（Jackson 隐式创建器规则）、空 `lines` 语义、`key` 为空与
  为空的区别、`SESSION` scope 缺 `sessionId` 的行为。
- `ShellIngress`：
  - 同 `(owner, key)` 合并 → `COALESCED`；
  - 容量打满 → `DROPPED_QUEUE_FULL`，且**调用方线程未被阻塞**（用计时断言）；
  - 一个 owner 洪水不影响另一个 owner 的桶；
  - 插件 stop 后 `present` 抛错；stop 时桶被清空；
  - 并发 `present` 与 drain 不丢计数（`accepted = delivered + coalesced + dropped` 这条账要对上）。
- **硬约束回归**：`ShellIngress` 没有任何创建会话 / 起回合的路径（构造器不注入写入口 +
  一条结构断言）。
- 合并流的顺序：同 owner FIFO；内核事件顺序不被插件事件打乱。
- 慢订阅者不阻塞回合（沿用 `EventChannelConcurrencyTest` 的思路）。

---

## 10. 落地阶段

| 阶段 | 内容 | 依赖 |
| --- | --- | --- |
| **P0** | `ConversationService.submit`：提交管线收归内核 | 无 |
| **P1** | 在途回合 + 取消 + 审批路由按 session 收归内核 | P0 |
| **P2** | `ShellEventStream`：会话级、内核拥有、`ReActListener` 变内部适配器 | P1 |
| **P3** | **本文**：`ShellContribution` + `ShellIngress` + 统一 `INVALIDATED` | P2 |
| **P4**（可选） | Server 变真正 client/server | 有外部客户端时 |

---

## 11. 决策记录（已定）

| # | 决策 | 取值 |
| --- | --- | --- |
| 1 | `SESSION` scope 的 `NOTICE` 是否进消息序列 | **不进**：只进外壳通知区（TUI 的 `ShellNotice` 语义：「按时间戳插进消息流」但**不是消息列表的一员**）。要真正进序列必须落盘成条目并由投影渲染，那是另一套设计 |
| 2 | `NOTICE` 是否落盘 | **不落盘**；持久状态走 `putExtensionEntry` + `INVALIDATED` 回读 |
| 3 | 面板 / 状态栏内容 | **维持拉取**（推送事件、拉取状态）；变的是「何时拉」由统一的 `INVALIDATED` 驱动 |
| 4 | 交付线程 | **同步扇出** + 订阅者**非阻塞**契约（与今天 `ReActListener` 的线程语义一致） |
| 5 | 断线重连补发 | **不做**。重连后由客户端重新拉取会话/界面快照即可（今天的 `GET /sessions/{id}` 已足够）|
| 5b | `seq` + 溢出 `Resync` | **不做**。可靠 lane 维持今天的「无界队列 + 写失败即取消回合」；不做 `seq`、不做 resync。理由：没有重连补发需求时，`seq`/`resync` 是纯粹的额外状态。**若日后改成有界队列**，必须重新打开这条（有界必丢，丢了必须告知） |
| 6 | 容量取值 | 每 owner 队列 `64`、每 owner 渲染上限 `3` 条（初值，后续按实测调） |
| 7 | 命名 / 包位置 | `PluginContext.present(ShellContribution)`；`ShellContribution` 放 `api/extension` |
| 8 | `UiInvalidatedEvent` | **保留**为公开入口，映射进统一失效事件，不删 |
| 9 | 插件贡献的审计 | **只记指标**（`plugin.*` 计数 accepted / coalesced / dropped），**不记审计事件** |
| 10 | `SHELL` scope 的可见范围 | **只在带界面的外壳渲染**；`-cli` 单次模式返回 `DROPPED_NO_RENDERER` |
| 11 | 订阅面形状 | **一套机制、两条 lane**：`subscribe(sessionId, listener)`（可靠）+ `subscribeShell(listener)`（尽力）；可丢性挂在 lane 上，不在事件上 |
| 12 | 交互请求（插件问用户） | **明确不做**；插件要交互只能派发 `/命令`，复用命令域的审计与错误处理 |

---

## 12. 已知代价（诚实评估）

**两条 lane 的设计消掉了初版最严重的两条代价。** 初版把内核事件与插件贡献合到同一条流，
直接违背 `architecture.md` 的「可丢与不可丢不能合成一条路」；改成两条 lane 后：

- 可丢性重新回到**通道级**（可靠 lane 不丢、尽力 lane 可丢），一眼可辨；
- 不需要跨来源排序（两条 lane 无需互相排序，因为 `NOTICE` 不进消息序列）；
- 「插件不能伪造内核事件」从运行时校验（origin 标签）升级为**类型上不可能**
  （`PluginContext` 上没有任何方法能写可靠 lane）。

剩下的代价是真实的，但都比初版小：

1. **新的公开 API 面**。`ShellContribution` / `present` 一旦发布就是插件作者的稳定契约，
   兼容性只能靠新静态工厂补。这是长期负担，换来的是插件推送能力。
2. **解释成本上升（对本仓库尤其贵）**。现在这套架构一页纸能讲清（三个外壳、一个 `ReActListener`、
   四条通道一句话可丢性）；改完后要讲清需要一张图带「两条 lane + 两条订阅 → 两类外壳呈现」。
   而 `AGENTS.md` 自己要求「只放每次动手前都该知道的规则」——规则面变大就是后续改动更容易踩错。
3. **文档反转成本**。本设计把「插件只能在回合内说话」松动成「插件随时能显示状态」，
   并依赖 P2 把「内核不自己起回合」反转为「内核持有回合状态」。**四份约束文档必须同步改**；
   改不彻底时，架构会变得比现在**更不合理**（代码是 X、文档是 not-X）。
4. **复杂度前置、收益后置**。`ShellIngress` 的隔离与配额、两条 lane 的门面与生命周期，
   是为「插件推送」与「多客户端」买的；近期实际受益的主要是 TUI，Server 完整受益要等前端能消费。

**判定标准（什么时候本次改造是净改善）**：

- P0 / P1 **无条件净改善**：它们消除已经发生的漂移（`!`/`@` 只有 TUI 有、命令谓词两套、
  闸门只有 Server 有），且不引入上述任何代价。
- P2 / P3 **只在确有插件推送需求时是净改善**；多客户端/断线重连已不再是前置条件
  （决策 5 已取消，`seq`/`resync` 不做）。
- 只跑 TUI 单进程、也没有具体插件推送需求时，**仍然不要做 P2 / P3**。

---

## 13. 落地记录：与初稿的差异

本节只记**与上面设计不同的地方**，以及它们各自的理由。规则本身已经迁到约束文档，不在这里重复。

### 13.1 `ShellContribution` 是一个类 + 三个枚举，没有子类

初稿的 5.1 已经定成「私有构造器 + 静态工厂」，落地时确认了这条：`Scope` / `Kind` / `Severity` 是三个封闭枚举，
`notice` / `invalidated` 是仅有的两个工厂。与 P2 的 `ShellTurnEvent` 同一口径——**新增一种 kind 不应该要求
每个外壳都改一遍接口实现**，而判别式字段让「漏了那个分支」变成编译期的 `switch` 缺失。

**刻意没有 `equals` / `hashCode`**：内容里的 `UiLine` 自己没有值相等（`UiSegment` 也没有），
按字段比对只会在「内容一样」时给出 `false`，那比没有更危险。与 `PanelContribution` 一致：拿身份比较。

### 13.2 交付是「外壳自己来取」，不是同步扇出

初稿的 6.2 图里写了 `→ ShellIngress → drain`，但决策 4 写的是「同步扇出 + 订阅者非阻塞契约」，
两者其实是矛盾的：同步扇出的话，队列、合并、容量上限就都没有意义（投了立刻交付，不存在「未交付」）。

**落地取的是队列那一边**，理由是它同时满足另外三条不能让步的要求：

- **交付必须发生在渲染线程上**（TUI）或 SSE 写循环里（Server），否则界面状态会被插件的任意线程改到；
- **入站不能阻塞**（原则 6）：`present` 可能在插件自己的线程、也可能在工具的输出泵线程上调用；
- **8.6「队列满只丢不阻塞」与 6.1 的「存/限/合并/取/丢」**只有在有队列时才成立。

因此：`present` **只入队**（`ShellIngress`，每 owner 有界、同 key 原地合并、满即丢最新），
交付由 `ShellStreams.drainShell()` 在**调用者线程**上同步扇出。
这条比初稿强的地方：**插件推得再多也拖不住任何线程**（初稿的同步扇出做不到这一点，
它只能靠「订阅者必须快」这条契约把问题推给订阅者）。

代价是 API 面比初稿多一个方法（`drainShell`），但它换来了「不做线程切换」这条实现上的确定性。

### 13.3 多了 `subscribeAll`（P2 就有）与「没有订阅者也要清空信箱」

`subscribeAll` 是 P2 的计划外增量（TUI 从首页进入，提交之前拿不到会话标识），尽力 lane 沿用同一族形状。
另外 `drainShell()` 在**没有订阅者时也把信箱清空**：否则一个永远不来取的外壳会让队列一直挂着积压的条目。
`present` 侧当然已经被 `DROPPED_NO_RENDERER` 挡了一道，但「没有渲染面」与「订阅者全都退订了」不是同一件事。

### 13.4 `DROPPED_NO_RENDERER` 的判据是外壳种类，不是 `RuntimeInfo.hasUI()`

决策 10 说「只在带界面的外壳渲染」。但 `RuntimeInfo.hasUI()` 对 HTTP 外壳是 `false`
（进程自己确实没有界面），而它的客户端有——直接用它会把 Server 也判成「没有渲染面」。
因此判据取 `runtimeInfo.snapshot().getShell() != CLI`：这对应的才是「这个外壳有没有渲染面」这个真正的问题。
未写入运行时信息时（嵌入式 / 单元测试）落到保守的「不收」一侧。

### 13.5 Server 的两个事件名与「一秒一片」的写循环

初稿没有规定 SSE 的事件名。落地取 `shell_notice` / `shell_invalidated` 两个，载荷里的 `lines` 是
**纯文本**而不是 `UiSegment` 列表：通知是尽力 lane 上的临时显示，把种类 / 强调两个维度搬过线会让载荷
从一层变成两层，而眼下没有任何客户端消费它（这是「不为想象中的需求预先付款」的同一取舍）。

时延上做了一处小改动：写循环从「一次等满 `keepaliveSeconds`」改成**一秒一片地等**。
不改的话，一条通知可能要十几秒才露到屏幕上，而「准实时」是这条 lane 的全部价值。
`keepalive` 的语义没有变（连续空闲满一个间隔才发一帧注释），回合事件仍然一到就走。

### 13.6 与失效触发源的统一只做了一半

6.3 说外壳「不再需要维护六处 `uiCache.invalidate()` 调用点」。落地后实际是**六处变五处**：

- 「回合开始 / 收敛」由 P2 的可靠 lane 事件覆盖（TUI 在 `render()` 里比对上一帧的进行中状态）；
- 「会话切换 / 命令执行后」是外壳自己发起的动作，本地即可知道，本来就没有跨 lane 通知的必要；
- `UiInvalidatedEvent` **保留**（决策 8），与 `PluginStateChangedEvent` 一起映射进 `INVALIDATED`。

**没有**把「首帧」与 `INVALIDATED` 合成同一条路径：首帧是缓存初值，不是失效事件。
这一条比初稿保守，但两者没有引入第二份真源，改与不改的行为差别为零。

### 13.7 没有做的事

- **没有把插件贡献做成持久 / 可补发**（决策 2、5、5b 不变）。
- **没有开放交互请求**（决策 12 不变）。
- **没有给 Server 加会话级常驻事件流**：贡献仍然只能经 `/chat` 的 SSE 流送达，
  一个不在跑回合的会话收不到任何推送。这是 P4 的范围（Server 变真正 client/server），
  在那里做才不用先把「谁在线」「补发什么」这套账建起来。
