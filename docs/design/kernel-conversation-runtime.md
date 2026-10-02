# 设计：内核会话运行时与外壳订阅通道（P0–P3 总纲）

> **状态：设计已定（决策见第 12 节）；P0 / P1 / P2 / P3 均已落地。** 本文是 P0 / P1 / P2 的设计与落地计划；
> P3（插件 → 外壳贡献）另有专文 [`plugin-shell-contributions.md`](plugin-shell-contributions.md)，本文引用不重复。
> 施工按第 11 节的阶段顺序推进，每阶段独立可合并、可回滚。
>
> | 阶段 | 状态 |
> | --- | --- |
> | P0 `ConversationService` | **已落地** |
> | P1 `TurnRegistry` + 审批多槽位 | **已落地** |
> | P2 可靠 lane | **已落地** |
> | P3 尽力 lane | **已落地**（分册：`plugin-shell-contributions.md`） |

---

## 1. 目标与非目标

### 目标

1. **外壳与内核的对话通道统一**：外壳不再各自实现流式适配与回合编排。
2. **插件能实时 / 准实时推给外壳渲染**（P3，见专文）。
3. **架构收拢**：会话 / 回合状态的所有权从外壳收回内核，消除已经发生的漂移。

### 非目标

- 不做跨进程 client/server 拆分（P4，仅在出现非 JVM 客户端时再议）。
- 不做断线重连补发、不做 `seq` / `Resync`（见 [P3 草案决策 5 / 5b](plugin-shell-contributions.md)）。
- **不保留任何带 per-turn listener 的外壳入口**（决策 D3）：`AgentHarness.chat(sessionId, input, listener)`
  这一类签名全部移除，外壳只经「订阅 + 提交」两条边工作。
- 不改变 `ReActLooper` 的循环语义、不改变 `ActionQueue` 的窗口语义。
- 不引入特性开关 / 灰度机制（仓库没有这套基础设施；靠分阶段 PR + 测试保证）。

---

## 2. 目标形态

```
外壳（CLI / TUI / Server）
  │  ① ShellStreams.subscribe(sessionId, listener)  —— 先订阅（硬不变量）
  │  ② ConversationService.submit(...)              —— 再提交（唯一管线）
  │  ③ TurnRegistry.turnOf / cancel                 —— 在途回合与取消（唯一）
  │  ④ Approvals.pending(sessionId) / resolve       —— 审批可见与裁决
  │  ⑤ ShellStreams.subscribeShell()                —— 尽力 lane（P3）
  ▼
jellyfish-core
  ConversationService ──→ CommandManager / InputTransforms / InputDirectives / TurnRegistry
  TurnRegistry ──→ ReActLooper.chat ──→ ReActListener（**内核内部**）──→ 可靠 lane
  ShellStreams（订阅门面）──→ 可靠 lane + 尽力 lane
  ▼
jellyfish-infra
  SessionManager / ApprovalChannel（按会话多槽位）/ ShellIngress（P3）
```

**两条 lane 的契约（P2 / P3 共同定义）**：

| | 可靠 lane | 尽力 lane |
| --- | --- | --- |
| 订阅入口 | `subscribe(sessionId, listener)` | `subscribeShell(listener)` |
| 内容 | 内核回合事件（文本 / 思考 / 工具 / 审批 / 终态） | 插件贡献（`NOTICE` / `INVALIDATED`） |
| 丢失 | **不丢**（同步扇出，无队列） | **可丢**（每 owner 有界队列 + 合并） |
| 顺序 | 单来源、严格有序 | 同 owner FIFO；跨 owner 不承诺 |
| 生产者 | 仅内核 | 仅插件（`present`） |

两条 lane **不需要互相排序**：`SESSION` scope 的 `NOTICE` 不进消息序列（P3 决策 1）。

---

## 3. `ReActListener` 的定位变更（决策 D3）

**移除所有外壳可见的 listener 参数入口**：

| 现在 | 之后（已落地） |
| --- | --- |
| `AgentHarness.chat(sessionId, input, listener)` | **保留为内核内部接缝**（外壳已不再调用）：`ConversationService` 用它起回合，`listener` 是内核自建的 `ShellStreams.publisher(...)`。新增 4 参重载以使用内核生成的 `turnId` |
| `ReActLooper.chat(...)` | 同上；新增 `chat(sessionId, turnId, input, listener)` 重载 |
| `ReActLooper.runNested(..., listener, ...)` | **保留原样**：子代理回合不上外壳，`ReActListener` 继续是它的回调接口 |
| `InputDirectives.start(sessionId, call, listener)` | **保留签名**（内核内部）：`ConversationService` 传进去的就是可靠 lane 的发布器 |
| `ConversationService.submit(...)` | **去掉 `listener` 参数**（外壳可见的那一层） |
| `ReActTurn` / `ReActResult` | 不再是外壳 API：`Submission` 不再携带回合句柄，取消走 `TurnRegistry.cancel(sessionId)`，终态走可靠 lane 的终态事件 |
| `CliReActListener` / `TuiReActListener` / `SseReActListener` | **已删**，改为订阅者 `CliTurnListener` / `TuiTurnListener` / `SseTurnListener`，呈现逻辑逐行保留 |

**D3 的实际边界**：「外壳可见的 listener 入口」= `ConversationService.submit` 的 listener 参数与三个
`*ReActListener` 实现。`AgentHarness.chat` / `InputDirectives.start` 仍在，但降为内核内部接缝
（`ReActListener` 的 Javadoc 已写明这一点）——完全移除它们需要把「回合执行体」与「事件发布」合并，
而那会让子代理委派与嵌入式调用失去一条同步回调。

`ReActListener` 仍是 `jellyfish-core` 的公开类型（`core.tool` 等子包要跨包使用），
但 Javadoc 必须写明「**内核内部回调，不是外壳接口**」。

### 3.1 由此产生的硬不变量

没有 per-turn listener 之后，**订阅必须先于提交**：

> **外壳必须在调用 `submit` 之前建立该会话的订阅；晚于回合开始的订阅者只能收到后续增量。**

这条从 P2 的「建议」升级为**契约**，并写进 `ShellStreams.subscribe` 的 Javadoc 与测试。

### 3.2 多会话的订阅管理（已落地）

- **TUI 用 `subscribeAll`**：它从首页进入，而会话是 `submit` 内部才建的——提交之前拿不到会话标识。
  进程内它是唯一消费者，所有事件写同一个暂存区，因此不需要按会话过滤；
  一次订阅在 `onStart` 建立、`onStop` 关闭，**不随会话切换重订阅**。
- **CLI 按当前会话订阅**：单次模式、会话在提交之前就已确定，响应结束时关闭。
- **Server 按路径里的会话订阅**：每次 `/chat` 建一次、`finally` 关闭——这是唯一需要按会话过滤的外壳
  （多客户端并发时不能把别人的事件写进自己的响应）。

---

## 4. P0：`ConversationService`（提交管线）

### 4.1 问题

提交管线在三个外壳各写一份，且已经漂移（见 [P3 草案第 3 节](plugin-shell-contributions.md)）：

| | TUI `TuiApp.submit()` | Server `ChatHandler.handle()` | CLI `CliRunMode.run()` |
| --- | --- | --- | --- |
| 命令判定 | `shouldRunAsCommand(text, hasSession)` | 无（拆成独立端点） | `isCommand(text)` |
| 输入改写 | ✅ | ✅ | ✅ |
| 输入指令 `!` / `@` | ✅ | ❌ | ❌ |
| 建会话时机 | 分流之后按需建 | 要求已存在（404） | 启动期已建 |

### 4.2 API（`jellyfish-core`）

```java
public interface ConversationService {

    /**
     * 处理一次用户提交：命令判定 → 输入改写 → 输入指令 → 起回合。
     * 不缓冲输出（输出走 listener / 可靠 lane）；不建会话（见 SubmissionPolicy.sessions）。
     */
    Submission submit(String sessionId, String text, InputTransformRequest.Source source,
                      SubmissionPolicy policy, ReActListener listener);
}
```

> **`InputSource` 不新增枚举**：直接复用已有的 `InputTransformRequest.Source`（`CLI` / `TUI` / `SERVER`）
> ——输入改写请求本来就带这个字段，再造一个同形枚举会变成两个真源。

```java
/** 提交管线各分支的开关：外壳的能力与 UX 事实，由外壳声明，内核只执行。 */
public final class SubmissionPolicy {

    /** 是否把「看起来像命令」的原文交给命令域执行。 */
    private final boolean commands;

    /** 是否解析输入指令（! / @）。 */
    private final boolean directives;

    /** 无会话时按需创建，还是直接拒绝。 */
    private final SessionPolicy sessions;

    public enum SessionPolicy { CREATE_IF_NEEDED, REQUIRE_EXISTING }

    /** 通用入口（测试与将来可能的组合用）。 */
    public static SubmissionPolicy of(boolean commands, boolean directives, SessionPolicy sessions);

    /** TUI：命令 + 指令 + 首页按需建会话。 */
    public static SubmissionPolicy tui();

    /** CLI：命令、**不解析指令**（决策 D6）、必须有会话。 */
    public static SubmissionPolicy cli();

    /** Server `/chat`：只有对话（不执行命令、不解析指令），必须有会话。 */
    public static SubmissionPolicy serverChat();
}
```

> **没有 `serverCommand()`**：`POST /commands` 走 `CommandManager` 直接执行（结构化入口 `name` + `args`
> 是 `submit` 覆盖不了的），**不经本管线**。不给用不上的工厂函数。

```java
/** 判别式结果：外壳只需 switch，不再自己判顺序。 */
public final class Submission {
    public enum Kind {
        EXECUTED_COMMAND,   // 交给 CommandManager 执行完毕
        HANDLED_INPUT,      // 被输入改写短路（插件接过去了）
        STARTED_DIRECTIVE,  // 起了输入指令（! / @）
        STARTED_TURN,       // 起了 ReAct 回合（携带 turnId）
        REJECTED            // 无会话 / 空输入 / 回合进行中
    }
    // kind + CommandResult + turnId + rejectReason
}
```

**关键约束（顺序即不变量，全部收进 `submit` 一处）**：

1. 命令判定**先于**输入改写（插件改不动用户显式的命令）；
2. 输入改写**先于**输入指令解析（指令按改写后的文本解析）；
3. 输入改写**先于**建会话（`handled` 时不建会话）；
4. 输入指令**先于**普通对话；
5. `REQUIRE_EXISTING` 下无会话直接 `REJECTED(NO_SESSION)`，**绝不建会话**；
6. `commands=false` 时「看起来像命令」的输入**按普通文本处理**（不报错、不静默丢）。

### 4.3 三外壳的 `SubmissionPolicy`

| 外壳 / 入口 | commands | directives | sessions |
| --- | --- | --- | --- |
| TUI | ✅ | ✅ | `CREATE_IF_NEEDED` |
| CLI | ✅ | ❌（**决策 D6：不启用**） | `REQUIRE_EXISTING` |
| Server `POST /chat` | ❌ | ❌（**保持现状**） | `REQUIRE_EXISTING` |
| Server `POST /commands` | 由 `CommandManager` 直接执行，不走 submit | — | — |

### 4.4 各外壳改造点

| 外壳 | 现在 | 之后 |
| --- | --- | --- |
| TUI | `TuiApp.submit()` 约 60 行分流逻辑 | 调 `submit(...)`；`switch (result.getKind())` 做呈现 |
| Server | `ChatHandler.handle()` 只做改写 + 起回合 | 调 `submit(id, message, SERVER, serverChat())`；**对话语义与现状逐字段一致**（命令与指令仍不进 `/chat`）。<br>⚠️ **一处顺序差异**：输入改写现在发生在 `submit` 内部，因此它晚于 `SessionTurns.acquire` 与 `streamPermit` 的获取（改造前改写排在这两者之前）。后果：被插件接过去的输入会短暂占一个回合槽位与一个并发流许可；在并发流打满时它拿 503 而不是 `input_handled`。两者都在微秒级，且不会往会话里留痕迹（`submit` 保证 `handled` 不 append 消息） |
| CLI | `CliRunMode.run()` 自己判 `isCommand` | 调 `submit(...)`；按 `Kind` 映射退出码 |

### 4.5 验收

- 三外壳在同一 `SubmissionPolicy` 下对同一输入的 `Kind` 一致（参数化测试：输入 × 四种 policy）。
- `REQUIRE_EXISTING` 且会话不存在时：`REJECTED(NO_SESSION)`，且 `SessionManager.all()` 不变。
- `handled` 时不建会话（沿用 TUI 既有约定）。
- **Server `/chat` 行为回归**：`/help`、`!ls` 在 `/chat` 下与改造前完全一致（作为文本发给模型）。
- CLI 退出码映射不变（`ExitCodes` 全部有测试）。

---

## 5. P1：`TurnRegistry` 与审批路由（已落地）

### 5.1 问题

- 「一会话一在途回合」只有 Server 有（`SessionTurns`，115 行）；
- 取消入口只有 TUI（Esc）与 Server（HTTP）；
- `ApprovalChannel` 是**全局单槽位**，多会话并发审批排队（`architecture.md` 自标「首轮不改」）；
- `SessionManager.current()` 是进程级单指针，Server 从不使用，TUI/CLI 依赖。

### 5.2 API（`jellyfish-core`，已落地）

```java
@Singleton
public final class TurnRegistry {
    /** 非重入占位；已占用抛 TurnInProgressException（Server 映射 409）。 */
    Slot acquire(String sessionId);
    void bind(String sessionId, ReActTurn turn);
    /** 幂等：同一个 Slot 重复归还只生效一次。 */
    void release(String sessionId, Slot slot);

    boolean isRunning(String sessionId);
    boolean cancel(String sessionId);
    Optional<ReActTurn> turnOf(String sessionId);

    /** 关键入口：把「终态回调」与「归还槽位」绑死，调用方不再自己写 try/finally。 */
    ReActListener releasing(String sessionId, Slot slot, ReActListener delegate);
}
```

**不变量**：`ConversationService.submit` 内部先 `acquire` 再 `harness.chat`
（`chat` 一返回就会 append 用户消息，事后判冲突已污染历史），`harness.chat` 同步抛错时在
`catch` 里归还。终态归还由 `releasing` 包装器完成——`ReActLooper` 保证四条终结路径恰好触发一个终态回调，
因此外壳不需要（也不应该）拿回合句柄，更不需要自己写 `try/finally`。

**输入指令不在闸门内**：`InputDirectiveRun` 的完成只能轮询，没有终态回调，因此没有可挂钩的归还点。
当前唯一会起指令的外壳（TUI）用界面自身的「进行中」状态挡住并发提交。

### 5.3 `ActionQueue` 窗口不合并

`ReActLooper.chat` 仍负责 `actionDispatcher.beginTurn/endTurn`（**不改**，「插件不能新开会话」的
窗口语义由它保证）。两者必须都存在，不能互相替代：

- `ActionQueue` 窗口是「插件动作能不能投递给这个回合」的开关；
- `TurnRegistry` 槽位是「第二个提交该不该被拒」的开关。

### 5.4 审批路由（决策 D1：按会话多槽位，已落地）

`ApprovalChannel` 从「全局单槽位」改为「**每会话一个槽位 + 每会话 FIFO 队列**」：

```java
Optional<Pending> pending(String sessionId);   // 本会话的头槽位（外壳绘制审批浮层用）
Optional<Pending> pending();                   // 跨会话最早的那条（只给不知道会话的晚到客户端）
boolean resolve(String id, boolean approved);  // 返回「是否真的落定了一条头槽位」（HTTP 层据此 404）
int waitingCount(String sessionId);            // 每会话
int waitingCount();                            // 合计（诊断用）
```

- 单会话内语义**逐字不变**（单槽位 + `MAX_WAITING` + 只对头生效 + 首次结论胜出 + fail-closed）；
- 多会话之间**不再互相阻塞**（修既有缺陷）；
- 头槽位表用 `ConcurrentHashMap` 无锁读（外壳每帧取件），写侧仍在一把锁内；
- Server：`ApprovalBridge.headFor(sessionId)` 直接取本会话头，不再从全局头过滤；
  `resolve` 的 404 改由 `ApprovalChannel.resolve` 的返回值判定。
  `ChatHandler.emittedApprovalId` **保留**：它现在是「本流内去重」，而不是为了绕开全局头过滤。

### 5.5 `SessionManager.current()` 的去向

**保留**，降级为**外壳便捷入口**，内核服务一律接收显式 `sessionId`。Server 从不使用（现状已是）。

### 5.6 删除清单（已执行）

- `jellyfish-server/.../SessionTurns.java` —— **已删**（连同 `SessionTurnsTest`）。
- `ChatState` 的回合句柄与 `cancelTurn()` —— **已删**；改为 `beginWork()` + `cancelDirective()`，
  回合取消由 TuiApp 走 `turnRegistry.cancel(sessionId)`。
- Server `ChatHandler` 的槽位获取/归还 —— **已删**；改为映射 `TurnInProgressException` → 409。

### 5.7 验收（已有测试）

- 并发两个会话各自起回合：互不阻塞、审批互不排队（`TurnRegistryTest` / `ApprovalChannelTest` /
  `ApprovalBridgeTest` 各一组）。
- 同会话第二个提交：`TurnInProgressException`，且**第二个未到达 `harness.chat`**。
- 终态回调归还槽位；委托者抛错时也归还（`finally`）；`harness.chat` 同步抛错时也归还。
- 四类终态（收敛 / 取消 / 被拦下 / 异常）各有一条用例。

---

## 6. P2：可靠 lane（外壳订阅通道）（已落地）

### 6.1 事件模型（`jellyfish-core`）

```java
public final class ShellTurnEvent {
    public enum Kind { STARTED, TEXT, THINKING, TOOL_STARTED, TOOL_OUTPUT, TOOL_COMPLETED,
                       BLOCKED, COMPLETED, CANCELLED, ERROR }
    public String getSessionId();
    public String getTurnId();
    public boolean isTerminal();   // BLOCKED / COMPLETED / CANCELLED / ERROR
    // 其余为各 kind 的载荷：text / toolCallId / toolName / toolArguments / success / output /
    // metadata / rounds / truncated / reason / error
}
```

**为什么不是一堆积类而是「一个类 + 判别式 kind」**：新增一种事件不应该要求每个外壳都改一遍接口实现
（那正是 `ReActListener` 靠 default 方法才勉强向后兼容的痛点），而判别式字段让「漏了那个分支」
变成编译期的 `switch` 缺失，与 `Submission` / `CommandResult` 同一口径。

- **`turnId` 由内核生成**：`ConversationService` 在起回合之前生成，通过
  `ReActLooper.chat(sessionId, turnId, ...)` 交给执行体，因此与 `ReActTurn.getTurnId()` 是同一个值。
  外壳不再自造（改造前 Server 在 `ChatHandler` 里造过一个，目的是避开「早期回调带 null」）。
- **`STARTED` 由 `ConversationService` 显式发布**（`ReActListener` 没有「开始」这个方法），
  且它之前必须保证「恰好一条终态」：若 `harness.chat` 同步抛错，`ConversationService` 会补发一条 `ERROR`。
- 事件里**不带 `seq`**（决策 5b：不做重连补发）。
- **审批不进这条 lane**：审批是拉取式（TUI 每帧读 `pending(sessionId)`、Server 在 SSE 循环里同步头槽位）。
  审批要的是「当前是否还挂着一件」这样的**状态**语义，而事件是过去式的，用事件表达状态会引入两套真源。

### 6.2 订阅 API（`jellyfish-core`）

```java
@Singleton
public final class ShellStreams {
    /** 按会话订阅：Server 的 SSE 流用它，避免把别的会话的事件写进自己的响应。 */
    Subscription subscribe(String sessionId, ShellTurnListener listener);
    /** 全量订阅：TUI 用它（从首页进入，submit 之前拿不到会话标识）。 */
    Subscription subscribeAll(ShellTurnListener listener);
    /** 把 ReActListener 回调翻译成事件并发布（内核内部用）。 */
    ReActListener publisher(String sessionId, String turnId);
}
```

- **同步扇出**：无队列、不丢弃、不乱序。单个订阅者抛错只记 WARN 并跳过（与 `EventChannel` 同口径，
  但与旧 `ReActListener` 的「抛错穿透到 react 线程」不同）；
- **`subscribeAll` 是计划外但必需的第三个入口**：TUI 首页路径上会话是 `submit` 内部才建的，
  它不可能在提交之前按会话订阅；而进程内它是唯一消费者，按会话过滤本来也没有意义。

### 6.3 各外壳改造点（已执行）

| 外壳 | 现在 |
| --- | --- |
| TUI | `TuiTurnListener` 订阅者写 `InflightTurn`；`onStart` 一次 `subscribeAll`，`onStop` 关闭；`Esc` 走 `TurnRegistry` |
| Server | `SseTurnListener` 写 SSE 队列；`handle` 里先 `subscribe(sessionId)` 再 `submit`，`finally` 关闭；
  `turn_start` 由 `STARTED` 事件序列化而来，不再手写；断连取消走 `TurnRegistry.cancel(sessionId)` |
| CLI | `CliTurnListener` 做 stdout/stderr 分流 + **终态闩锁**（`awaitTerminal()`）替代 `ReActTurn.await()`；
  退出码由终态种类决定 |

**明确保留**：三份呈现逻辑（stdout/stderr 分流、`InflightTurn` + `TranscriptProjector`、SSE 序列化）。
变的是它们从「实现 7 个回调方法」变成「订阅一条流 + switch」，且回合簿记不再由外壳持有。

### 6.4 验收

- 三外壳对同一回合收到的事件序列一致（录制回放喂给三个订阅者）。
- **订阅先于提交**：一个在 `submit` 之后才订阅的测试用例，明确断言「收不到已产生的增量」
  （把契约钉住，而不是让后人以为能拿到全量）。
- 订阅者在回合中途 `close()` 不抛错、不影响回合；订阅者抛错被隔离，不回灌 react 线程。
- `turnId` 在 `Started` 事件里就已确定，且整个回合一致。

---

## 7. P3：尽力 lane（插件贡献）

见 [`plugin-shell-contributions.md`](plugin-shell-contributions.md)。本文只固定接入点：

- `ShellIngress` 放 `jellyfish-infra`（与 `ActionQueue` 同理：入队入口是 `PluginContextImpl`）；
- `ShellStreams.subscribeShell` 的实现在 `jellyfish-core`（外壳只依赖 core）；
- `PluginContext.present(ShellContribution)` 写 `ShellIngress`；
- **插件拿不到可靠 lane 的任何写入口**（类型上不可能：`PluginContext` 无此方法）。

---

## 8. 失败语义与不变量（跨阶段）

1. **插件不能新开会话 / 回合**：`ShellIngress` 与 `present` 没有创建入口；插件无法调用 `submit`。
2. **提交顺序**：见 4.2 的六条，全部在 `submit` 一处实现。
3. **占位早于起回合**：`TurnRegistry.acquire` 必须先于 `chat`（5.2）。
4. **订阅先于提交**：见 3.1（D3 的直接推论）。
5. **审批 fail-closed 不变**：无审批者 / 超时 / 队列满 / 通道关闭 / 中断一律拒绝。
6. **可丢性挂在通道上**：可靠 lane 不丢、尽力 lane 可丢；插件无法写可靠 lane。
7. **0 贡献者 / 0 订阅者时行为不变**：新增扩展点的硬要求（沿用 `constraints/extensions.md`）。
8. **订阅者必须非阻塞**：可靠 lane 回调在发布线程上，实现只允许写线程安全缓冲。
9. **`ActionQueue` 窗口语义不变**：仍由 `ReActLooper.chat` 开闭，仍只在在途顶层回合内。

---

## 9. 文档更新清单（与代码同 PR）

| 文档 | 改动 |
| --- | --- |
| `constraints/extensions.md` | 「内核不自己起回合」→「内核持有回合可见状态；回合的*启动*仍只能由外壳经 `ConversationService` 发起」。新增「插件贡献只走尽力 lane」条目 |
| `constraints/shells.md` | 「外壳只做两件事」→「外壳只做输入采集与呈现」；「订阅先于提交」；Server「一会话一在途回合」升级为内核不变量；审批改为内核按会话路由 |
| `constraints/permissions.md` | `ApprovalChannel` 单槽位 → 按会话多槽位；补多会话并发审批语义 |
| `constraints/session-config.md` | `SessionManager.current()` 降级为外壳便捷入口；内核服务按 id 寻址 |
| `architecture.md` | 三通道表加两行（可靠 lane / 尽力 lane）；模块图加 `ConversationService` / `TurnRegistry` / `ShellStreams`；「已知边界」删掉审批单槽位一条 |
| `docs/server-api.md` | 明确 `POST /chat` 与 `POST /commands` 的边界（**`/chat` 不执行命令、不解析指令**，与改造前一致） |
| `docs/design/plugin-shell-contributions.md` | 决策表已冻结（本次同步） |

---

## 10. 测试计划（跨阶段）

- **参数化一致性**：同一组输入 × 四种 `SubmissionPolicy` → 同一 `Kind`（P0）。
- **回归**：三个 `*ReActListenerTest` 改为订阅者测试；事件序列用「录制回放」而非各自构造。
- **并发**：会话 A / B 各自回合 + 各自审批不互相阻塞（P1）。
- **结构断言**：`ShellIngress` / `present` 无创建会话 / 起回合路径（P3）。
- **顺序不变量**：`submit` 六条顺序各一条测试（含 `handled` 不建会话、`commands=false` 当文本）。
- **订阅契约**：晚订阅收不到已产生增量（6.4）。
- **`mvn -q -Pserver-it test`** 全程作为端到端门禁。

---

## 11. 落地顺序与回滚

| 阶段 | 依赖 | 状态 | 可回滚性 |
| --- | --- | --- | --- |
| P0 `ConversationService` | 无 | 已落地 | 高（把分流逻辑搬回外壳即可） |
| P1 `TurnRegistry` + 审批多槽位 | P0 | 已落地 | 中（`ApprovalChannel` / `TurnRegistry` API 变更外部可见） |
| P2 可靠 lane（外壳改订阅者） | P1 | 已落地 | 中（订阅者可换回 listener 实现，但 D3 已定不保留） |
| P3 尽力 lane | P2 | 已落地 | 低（`present` 一旦发布即插件契约） |

> **`chat(listener)` 的移除归 P2，不归 P1**：没有可靠 lane 之前，外壳没有第二条接收事件的途径，
> 此时把 listener 入口抽掉会让三个外壳无事件可渲染。因此 P1 只把「回合状态与取消」收归内核，
> 外壳仍然通过 `submit(..., listener)` 拿回调。
>
> **P2 的实际结果**：外壳可见的那一层（`submit` 的 listener 参数与三个 `*ReActListener` 实现）**已移除**；
> `AgentHarness.chat` / `InputDirectives.start` 保留为**内核内部接缝**（详见第 3 节的表）。
> 保留它们的理由：完全去掉需要把「回合执行体」与「事件发布」合并，而子代理委派（`runNested`）
> 与嵌入式调用仍然需要一条同步回调。因此 `ReActListener` 仍是公开类型，但 Javadoc 已写明它
> **不是外壳接口**。

**建议 P0 / P1 各一个 PR，P2 / P3 各一个 PR**；每阶段合并前必须同步第 9 节对应文档。
**P0 + P1 + P2 是可独立交付的完整改进**；P3 是「有真实插件推送需求才做」的条件投资。
P3 的落地记录（与设计分册的差异）见 `plugin-shell-contributions.md` 第 13 节。

---

## 12. 决策记录（已定）

| # | 决策 | 取值 |
| --- | --- | --- |
| **D1** | 审批改为按会话多槽位 | **采纳**（修既有缺陷；TUI 单会话行为逐字不变） |
| **D2** | CLI 取消入口（信号 / 超时） | **暂不做**，只保留 `TurnRegistry.cancel` 能力 |
| **D3** | 保留 `AgentHarness.chat(listener)` | **不保留**。所有外壳可见的 listener 入口移除；`ReActListener` 降级为内核内部回调；取消走 `TurnRegistry`，终态走可靠 lane |
| **D4** | 回合中途订阅语义 | **不补发**，只给后续增量（`GET /sessions/{id}` 可取全量） |
| **D5** | Server `/chat` 是否执行命令 | **不执行**。对话与命令的 API 保持分开：`/chat` 只跑对话管线，命令只走 `/commands`（与改造前行为一致） |
| **D6** | CLI 是否启用输入指令 `!` / `@` | **不启用**：`SubmissionPolicy.cli()` 里 `directives=false`，CLI 行为与改造前一致 |
