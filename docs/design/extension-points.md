# 设计：扩展点补充（Extension Points Roadmap）

> 本文是一次**对标 pi agent 扩展面之后的补齐方案**，是设计文档、不是已落地行为的描述。
> 文中的「现状」指写下本文时的代码；「方案」部分在落地前都不是现有 API。
> 落地时需同步更新 `../constraints/extensions.md` 与各分域 constraints（清单见 §11）。
>
> 本文只覆盖一次对标中确认值得做的 9 个扩展点。**明确不写**：声明式资源包与 `resources_discover`
> （对标结论里的第 6 点，另有独立方案）。

---

## 0. 总览

### 0.1 覆盖范围

| 本文 | 对标序号 | 扩展点 | 通道 | 分期 |
| --- | --- | --- | --- | --- |
| §1 | 第一档 1 | 工具执行前后变换管道 | 同步 | P1 |
| §2 | 第一档 2 | 可取消的生命周期钩子 | 同步 | P2 |
| §3 | 第一档 3 | 文本级输入改写 | 同步 | P2 |
| §4 | 第一档 4 | 运行时信息只读快照 | 读能力（非注册） | P1 |
| §5 | 第二档 5 | 模型 / 厂商可插拔 | 同步 | P5 |
| §6 | 第二档 7 | 会话扩展条目与分支 | 数据模型 + 动作通道 | P4 |
| §7 | 第二档 8 | 工具激活与延迟加载 | 同步 | P6 |
| §8 | 第三档 9 | UI 深度（富数据 + 工具渲染提示 + 快捷键） | 同步 | P6 |
| §9 | 第三档 10 | 插件主动能力：动作通道 | 队列能力（非注册） | P3 |

### 0.2 判据：每个新点为什么落在这条通道

沿用既有唯一判据——**「能不能丢」，不是「有没有返回值」**（见 `../constraints/extensions.md`）：

- §1 / §2 / §3 / §5 / §7 / §8 全部**有返回值且不可丢**：它们决定「发出去什么」「跑不跑」「看得见哪些工具」，
  丢了就是功能坏了或安全性质变了 → 一律落 `ExtensionRegistry`（同步侧）。
- §4 / §9 不是注册型扩展点：§4 是只读快照读取，§9 是内核提供的**入站队列**（插件投递、内核排空）。
  两者都不需要新的派发策略，也都不引入第三方事件总线。
- §6 的持久化部分**不新增扩展点**：扩展条目直接随现有 `SessionPersistRequest` 的快照走；
  fork 动作走 §9 的队列。

### 0.3 统一约定（每个点都必须满足）

以下九条是本文所有设计的公共前提，落地时逐条自查：

1. **请求/结果类型放 `jellyfish-api`，恰好一个可见构造器 + 静态工厂**。
   新增字段只能用新静态工厂补，不加兼容构造器（`../constraints/session-config.md` 的跨边界载荷规则）。
2. **`-parameters` 是全局编译约定，不许去掉**：任何可能被持久化或反序列化的问题都在此列。
3. **注册表不做编排**：`ExtensionRegistry` 只提供「有序查找」与「执行单个 handler」，
   链式传递、短路、结果合并全部写在调用点的 `for` 循环里。
4. **`order` 升序，同序按注册顺序**——这是 `TypeRegistry` 既有语义，新点不得另立一套。
5. **同步侧没有超时、没有白名单、没有异常隔离**：调用点若不能容忍插件阻塞或抛错，
   **必须自己在调用点设超时或 try/catch**。本文对每个新点明确写出它的失败语义。
6. **不得放宽权限**：任何新点都不能让插件把一个被拒绝的调用变回可执行；
   需要「收紧」的走既有三态 `PermissionVerdict`（`ABSTAIN`/`ASK`/`DENY`，**顶层没有 `ALLOW`**）。
7. **注册窗口 = 插件存活期，`stop()` 之后注册 fail-closed**：走 `PluginContext` 的新入口一律经
   `ContextLifecycle` 检查，不得绕过。
8. **owner 与回收不变**：新注册的 owner 仍是 `pluginId` 或 `pluginId::child`，
   由 `PluginContextFactory.release` 按前缀一次收干净。
9. **注释中文、`@author zcd`、异常统一 `JellyfishException`、Java 8 语法**——
   新类型不得使用 Java 9+ API（`List.of` / `var` / `ProcessHandle` 等一律不可用）。

### 0.4 兼容性口径

- **新增请求类型天然向后兼容**：老插件不注册它，内核在调用点看到 0 个 handler 时走「无插件行为」。
  每个点必须显式定义「0 handler 时是什么行为」，并写进测试。
- **修改既有类型一律用新静态工厂**，不得新增公开构造器；`PanelContribution` / `StatusLineContribution`
  这类已经被插件构造的类型，宁可新增旁路类型也不改签名（§8.1 采用「扩词汇、不改容器」的路线）。
- 内核 `core` 依赖 `infra` 依赖 `api` 的单向依赖不得因为新点反向（§7 的 `ToolCatalog` 在 core，
  §2 的钩子调用点也必须落在 core 或 infra 的既有位置）。

---

## 1. 工具执行前后变换管道

### 1.1 现状与缺口

`ToolExecutor` 是**权限 → 路由 → 截断的唯一执行点**（`../constraints/tools-output.md`），
但插件对「工具调用」只有两个落点：

- `ToolCallRequest`：**就是**那个工具本身（要么全写一个工具，要么完全插不进去）；
- `PermissionCheckRequest`：只能裁 `ABSTAIN`/`ASK`/`DENY`，**不能改参数**。

结果是：脱敏、路径围栏、参数归一化、结果整形、审计快照这些「横切」需求，
今天只能靠「把内置工具整个覆盖掉」或「复制一个包装工具」来实现。这是与 pi 差距最直接的一块。

### 1.2 `ToolArgumentPreRequest`（执行前）

**新增类型（api/extension）**

```java
public final class ToolArgumentPreRequest implements ExtensionRequest<ToolArgumentDecision>
        // 字段：sessionId、agentId、toolName、arguments、permissionMode、source
public final class ToolArgumentDecision       // 静态工厂：abstain() / replace(Map) / deny(String)
```

**注册方式**：`contribute(ToolArgumentPreRequest.class, null, handler, RegisterOptions.order(n))`——
类型级贡献，允许 0..N 个，按 order 升序链式传递。

**调用点与顺序**（改 `ToolExecutor.execute(session, cancellation, toolCallId, toolName, arguments, listener)`）：

```
1. 参数已是 Map（JSON 解析在更早的重载里完成；解析失败仍直接返回失败结果）
2. 【新】ToolArgumentPreRequest 链
3. PermissionCheckRequest（对「变换后的参数」判定）
4. ToolCallRequest（handler 执行）
5. 【新】ToolResultPostRequest 链
6. ToolOutputLimiter.limit（唯一的截断与落盘点，位置不变）
7. 落会话 + 通知外壳
```

**为什么变换必须排在权限之前**（本文最关键的一条设计决定）：

- 若排在权限之后，就会出现 **TOCTOU**：审批浮层上显示的是参数 A，真正执行的是参数 B。
  用户批准的东西与执行的东西不是同一个，这比「参数被插件改写」严重得多。
- 排在之前，则**批准的就是执行的**，审批记录、TUI 轨迹行、`-cli --show-tool-args`、
  会话里落库的 `toolCalls` 全部是变换后的同一份文本——四个显示面共用一份口径本来就是既有纪律。
- 「恶意插件借此绕过权限」不成立：插件本来就是同进程任意代码，`--show-tool-args` 早已点明
  「参数不脱敏」；安全边界是「装不装这个插件」，不是「参数在第几步被看」。

**链式语义**（写在调用点的 `for` 循环里，注册表不参与）：

- 每个 handler 收到**上一个 handler 产出的**参数（首个收到原始参数）；
- `ABSTAIN` → 保持当前值继续下一个；
- `REPLACE` → 替换当前值继续下一个；
- `DENY` → **短路**，直接生成一条失败结果（与 `PermissionCheckRequest` 的 `DENY` 同形，
  但**不参与权限审计**，理由见 §1.4），并保证**该工具调用一定产生一条结果**（配对规则不可违反）。
  该结果的 `ToolMetadata.KEY_TERMINAL` 取 `REJECTED`（而不是 `FAILED`），原因写进回灌文本。

**无插件时**：0 handler → 参数原样进入权限判定，行为与今天逐字节一致（有测试锁定）。

**失败语义**：handler 抛错 → 调用点捕获、记 WARN、**按 `ABSTAIN` 处理**（与
`PermissionManager` 对插件拦截的处置同口径：绝不因为插件坏了就放行或崩溃）。

**不重新校验**：变换后的参数**不做 schema 校验**（内核没有该能力）。
这条必须写进 api 的 javadoc：写 `REPLACE` 的插件自己保证参数形状合法。

### 1.3 `ToolResultPostRequest`（执行后）

**新增类型**

```java
public final class ToolResultPostRequest implements ExtensionRequest<ToolResultAdjustment>
        // 字段：sessionId、agentId、toolName、arguments、output(Object)、metadata(Map)、failed(boolean)
public final class ToolResultAdjustment   // 静态工厂：abstain() / of(output, metadata) / metadataOnly(Map)
```

**注册方式**：同 §1.2，`contribute` + order 升序。

**调用点**：第 5 步，**必须在 `ToolOutputLimiter.limit` 之前**。理由：

- 截断之后回来改文本会产出「信封说截断了、正文却是完整的」这类自相矛盾的结果；
- 落盘文件是在 limit 那一步写的，后置变换改不动它，于是「回灌文本」与「`_path` 里的内容」分叉。

`output` 保持**原始类型**（`String` 或 `Map`/`List`），不得在这一步序列化成文本——
理由与 `invokeTool` 的注释一致：截断要知道「这是字符串还是结构化对象」才能选对算法。

**链式语义**：`ABSTAIN` 保持；`of(...)` 替换（字段为 `null` 表示「这一项不改」）；无 `DENY`。

**能改什么、不能改什么**：

| 项 | 可否修改 | 理由 |
| --- | --- | --- |
| `output`（文本 / 结构化对象） | 可 | 这是「结果整形」的正题（脱敏、归一化、加审计头） |
| `metadata` | 可 | `ToolMetadata` 的约定键本来就是工具自己填的，外壳只读不解释 |
| `success`（异常与否） | **不可** | 它由 `ToolExecutor` 的 catch 路径决定，是「工具有没有抛」的事实；界面判成败一律走 `ToolMetadata.failed()`，不得引入第二套判据 |
| 落盘文件内容 | **不可** | 已在第 6 步之外；把「结果变换」与「落盘」耦合会让信封与文件永久分叉 |

**失败语义**：handler 抛错 → 记 WARN、按 `ABSTAIN` 处理（保留原始结果）。

**必须线程安全且快**：它在 `react` 线程上执行，而 `React` 线程上此刻可能还有 `ToolOutputSink` 的
泵线程在收尾（`sink.finish()` 在 `finally` 里），因此 handler 不得阻塞、不得回调内核。

### 1.4 两条不可越过的规则

1. **`DENY` 只产生工具失败结果，不产生 `PermissionDecidedEvent`，也不新增审计事件**。
   权限审计回答的是「权限系统放没放行」，而 §1.2 的 `DENY` 是「插件主动拒绝这条参数」；
   把两者混进同一个事件会让 `MetricsSubscriber` 的权限计数失真，而给它加一个字段会让
   一条热路径事件背上一个极少用到的字段，所有订阅方都要跟着改。

   **已决**：v1 不审计。可观测性靠**既有通道**满足——`KEY_TERMINAL = "REJECTED"`
   让「工具自己失败」与「插件拒了参数」在展示层可区分（界面照旧只读 `ToolMetadata.failed()`，
   它已经返回 `true`），原因本来就在那条 tool 消息的正文里。
   将来若出现合规需求，**另发一条 `ToolArgumentRejectedEvent`（异步、可丢）**——
   新增事件类型是纯向后兼容的，没有锁死成本，所以不必现在为一个假想订阅方预留字段。
   推论：`ToolMetadata` 的「约定只有三个键」不变，只是 `KEY_TERMINAL` 的**取值集合**包含
   工具自己定义的值（`REJECTED` 由内核在 §1.2 的 `DENY` 路径上写入）。
2. **变换只影响本次调用，不写回会话**：`toolCalls` 落库的是变换**后**的参数（因为变换在 append 之前），
   但会话里已有的历史一条不动。这天然满足缓存前缀的 append-only 性质（见
   `../design/llm-cache.md`）——变换只影响新产生的 token。

### 1.5 测试点

- `ToolExecutorTest`：0 handler 时参数、结果、元数据与改造前逐字段一致（防回归）。
- 链式顺序：两个 handler 的 order 决定谁先看到参数；后者能看到前者 `REPLACE` 的值。
- `DENY` 短路：后续 handler 不被调用，且恰好一条结果、`ToolMetadata.failed()` 为真、
  `KEY_TERMINAL == "REJECTED"`。
- `DENY` 路径**不产生** `PermissionDecidedEvent`，也不产生任何新事件类型。
- 抛错隔离：handler 抛 `JellyfishException` / `RuntimeException` 都按 `ABSTAIN` 处理。
- 后置变换发生在 limit 之前：构造一个超限结果，断言变换后的内容仍被截断成信封。

---

## 2. 可取消的生命周期钩子

### 2.1 现状与缺口

今天 Jellyfish 的事件**全是事后通知且可丢**（`EventChannel`），没有任何「切换前 / 压缩前 / 回合前」的
同步拦截点。于是这类需求都无处安放：脏仓库守护、git 检查点、合规拦截、压缩前的预算判断。

pi 的解法是 `session_before_switch` / `session_before_fork` / `session_before_tree` /
`session_before_compact` 四处可 `{cancel:true}` 的钩子。Jellyfish 对应的位置更少（没有会话树），
因此收敛成三个点。

### 2.2 三个钩子

**共同的否决结果类型**

```java
public final class LifecycleVerdict   // 静态工厂：proceed() / cancel(String reason)
```

三个钩子共用它，避免三份形状相同、语义略有差异的类型。

#### 2.2.1 `CompactionPreRequest` → `CompactionDirective`

```java
public final class CompactionPreRequest implements ExtensionRequest<CompactionDirective>
        // 字段：sessionId、trigger(MANUAL|AUTO)、messageCount、tokensBefore、
        //        keepRecentMessages（策略算完后的值）、previousBoundaryMessageId
public final class CompactionDirective  // 静态工厂：proceed() / cancel(String) / keepRecent(int)
```

- **调用点**：`ConversationCompactor` 在**选定范围之后、发起摘要模型调用之前**。
  这是唯一能在花钱之前拦住它的位置。
- **为什么是「改保留条数」而不是「给一份摘要」**：给摘要意味着插件要自己调模型，
  而插件今天没有模型调用能力（§5 才引入，且是传输层不是摘要层）。v1 只开放
  `cancel` 与 `keepRecent`（内核照旧按 `[0, 消息总数]` 与边界对齐规则钳制）。
  **「插件提供摘要正文」明确推后**，等 §5 落地后再评估。
- **无插件时**：proceed。
- **失败语义**：抛错 → 记 WARN、proceed（压缩不该因为一个观察者坏了而失败）。
- **与 `CompactionStrategyRequest` 的分工**：策略回答「怎么压」（指令与参数），
  钩子回答「这次要不要压 / 压多少」。两者都不给消息正文，这条边界不变。
- **并行落一条异步通知**：`CompactionFailedEvent(reason, sessionId, trigger)`，
  与既有的 `CompactionAppliedEvent` 配对。今天是「压缩失败只在状态里打个字符串」，
  可观测性订阅方看不到。

#### 2.2.2 `SessionBeforeCloseRequest` → `LifecycleVerdict`

```java
public final class SessionBeforeCloseRequest implements ExtensionRequest<LifecycleVerdict>
        // 字段：sessionId、agentId、reason(USER_REQUEST|RELOAD|SHUTDOWN)
```

- **调用点**：`SessionManager.close(sessionId)` 的**落盘之前**。
  于是「插件在关闭前做一次检查点 / 拒绝关闭」有一个正式落点。
- **`SHUTDOWN` 一律忽略 `cancel`**：关机路径不允许被插件拖住。
  请求里带 `reason` 是为了让插件知道自己在哪个阶段，**不是**为了让它在这里否决。
  这条要写进 javadoc，并有一条测试锁定。
- **`CLOSE` 与 `DELETE` 分开**：`delete` 走既有 `SessionDeleteRequest`（持久化 SPI），
  本钩子不覆盖它。
- **失败语义**：抛错 → 记 WARN、proceed。
- **不得阻塞**：handler 在关闭路径的调用线程上同步执行，因此 api 必须写死「只读、快、不阻塞」。

#### 2.2.3 `TurnBeforeRequest` → `TurnDirective`

```java
public final class TurnBeforeRequest implements ExtensionRequest<TurnDirective>
        // 字段：sessionId、agentId、input、nested(boolean)、depth、permissionMode
public final class TurnDirective  // 静态工厂：proceed() / cancel(String) / replaceInput(String)
```

- **调用点**：`ReActLooper.execute` 的**追加用户消息之前**（`runNested` 走同一个校验入口，
  否则子代理路径会绕过钩子）。
- **`cancel` 的语义**：不追加用户消息、不调用模型。`ReActResult` 今天只有
  `truncated` / `cancelled` 两个布尔标记 + 三个静态工厂（构造器不可见），因此这里
  **新增一个 `blocked` 标记与 `blocked(sessionId, reason)` 静态工厂**（不加构造器，
  符合 §0.3 第 1 条），`reason` 原样带到外壳（TUI 走 `ShellNotice`、CLI 走 stderr、
  Server 走 SSE）。**不伪造 assistant 消息**——那会污染历史。
- **`-cli` 的退出码是 `7`（`ExitCodes.TURN_BLOCKED`）**，不是 `4`：它不是运行失败（没抛异常、
  没被用户取消、没有资源故障），脚本对它的补救动作（改请求 / 找人确认）与对 `4`
  （看日志排故障）完全不同。`CliRunMode.executeTurn` 的判定顺序是
  `blocked → 7`、`cancelled → 4`、`truncated → 6`、否则 `0`；**stdout 必须保持空**
  （被否决的回合没有回答，把原因写到 stdout 会破坏「stdout = 回答」的契约），
  原因只进 stderr。TUI / Server 没有退出码概念，各自走 notice / SSE。
- **`replaceInput` 只允许顶层回合**：嵌套回合的输入是模型写出来的任务描述，
  改写它会让「模型要什么」与「子代理收到什么」分叉，且模型无从得知。请求里带 `nested`，
  handler 可据此自行判断；内核在 `nested == true` 时**忽略** `replaceInput` 并记 DEBUG。
- **无插件时**：proceed。
- **失败语义**：抛错 → 记 WARN、proceed。

### 2.3 三个钩子的公共纪律

- **全部走 `contribute`**（0..N，order 升序），合并规则统一为：
  **第一个 `cancel` 短路**（理由取自它）；`replaceInput` / `keepRecent` 取**最后一个非缺省**值。
  短路由调用点的 `for` 循环实现，注册表不参与。
- **全部不可丢** → 同步侧，不进 `EventChannel`。
- **全部禁止发布事件**（同 `UiContributions` 对处理器的三条硬约束的口径），
  否则会出现「否决 → 事件 → 否决」的自激。
- **`BLOCKED` 对 `-cli` 的退出码是 `7`**（见 §2.2.3），不进 `0/4/6` 中的任何一个。
  这是把「策略拦下」与「跑挂了」在机器契约层分开，与既有的「`0` 表示未知命令、
  `4` 表示跑挂了」同一条口径。

### 2.4 测试点

- 三个钩子各自的「无插件 / proceed / cancel / 抛错」四态。
- `SHUTDOWN` 下的 `cancel` 被忽略。
- `SessionBeforeCloseRequest` 确实在落盘之前触发（用一个记录调用顺序的 handler 断言）。
- `TurnBeforeRequest` 的 `replaceInput` 在 `nested == true` 时被忽略。
- `CompactionPreRequest` 的 `keepRecent` 越界后被钳制。
- `CompactionFailedEvent` 在摘要调用失败时恰好发一次。

---

## 3. 文本级输入改写

### 3.1 现状与缺口

输入层只有**标记式**扩展：`InputDirectiveRequest`（`!`）/ `InputReferenceRequest`（`@`），
且 `InputMarkers.requireMarker` 强制「单个非空白字符」。没有「任意文本改写 / 短路」的通用入口，
于是脱敏、自动补上下文、快捷指令这类需求都写不出来。

pi 的 `input` 事件（`continue` / `transform` / `handled`）就是这一层。

### 3.2 `InputTransformRequest` → `InputTransformResult`

```java
public final class InputTransformRequest implements ExtensionRequest<InputTransformResult>
        // 字段：sessionId（可为 null，首页无会话）、text、source(CLI|TUI|SERVER)、hasSession
public final class InputTransformResult
        // 静态工厂：continueAsIs() / replace(String) / handled(String notice)
```

**注册方式**：`contribute` + order 升序，链式传递。

**调用位置（顺序不可调换）**

```
外壳收到输入
  → CommandManager.shouldRunAsCommand(text, hasSession)   [现有，最先]
  → 若是命令：直接走命令域，结束（不进入变换）
  → 【新】InputTransformRequest 链
  → 若是 handled：走外壳的 notice 通道（ShellNotice），结束
  → InputDirectives.resolve(变换后的 text)                [现有]
  → 对话路径
```

**三个关键决定**：

1. **变换排在命令判定之后**。若排在之前，一个插件就能把 `/help` 改写成别的东西，
   用户看到的与执行的不是同一件事。命令域是用户显式意图，优先级最高。
2. **变换排在指令解析之前、且基于变换后的文本解析指令**。
   这样「插件把 `!ls` 归一化成 `! ls`」这类需求成立；代价是插件理论上可以把普通文本改成
   `!命令` 从而触发一次工具执行。**这个代价是刻意的**：插件本来就受信任，
   而工具执行仍走完整的权限与审批链路（`InputDirectives.submit` → `ToolExecutor`），
   所以它拿到的是「一次正常的、要过权限的调用」，不是绕过。
   这条要写进 api 的 javadoc，避免后人把它当成漏洞来「修」。
3. **`handled(notice)` 复用命令结果的渲染通道**，不新造一套。文本进
   `ShellNotice`（TUI）、stdout（CLI）、SSE（Server），与命令结果同口径。

**无插件时**：0 handler → 文本原样进入指令解析，行为与今天逐字节一致。

**失败语义**：抛错 → 记 WARN、按「保持当前文本」处理。

**线程约束（必须在 api 里点明）**：TUI 路径上它在**渲染线程**上执行
（与 `InputReferenceRequest` 的每帧查询同一个线程）。因此 handler 必须**纯计算、不阻塞、不回调内核**；
内核不为它设超时，这是同步侧既有语义。

### 3.3 测试点

- 命令前缀不被改写（构造 `text = "/help"`，断言变换链根本不被调用）。
- `replace` 之后的文本能正确被 `InputDirectives` 认出来。
- `handled` 时**不建会话、不发起回合**，只产生一条 notice。
- 无 handler 时文本逐字节不变。
- 首页（`hasSession == false`）也能执行，且 `sessionId` 为 `null` 时 handler 不被 NPE 打死。

---

## 4. 运行时信息只读快照

### 4.1 现状与缺口

`PluginContext` 只有 `pluginId` / `subContext` / `configuration` / 四个注册方法。
「插件碰不到会话、也拿不到工作目录」是刻意的（`../constraints/extensions.md`），
但**连「我在哪种外壳里、能不能弹框」都感知不到**，导致插件无法优雅降级——
一个只在 TUI 有意义的界面贡献，在 `-cli` 下也会被收集与渲染（只是没人看得见）。

pi 的 `ctx.mode` / `ctx.hasUI` 正是这一层。

### 4.2 `RuntimeInfo`

**新增 api 类型**

```java
public final class RuntimeInfo          // 恰好一个可见构造器（由内核构造）
        // 字段：shell(CLI|TUI|SERVER)、hasUI、supportsApproval、interactive
```

`PluginContext` 新增一个只读方法：

```java
RuntimeInfo runtimeInfo();
```

**字段的含义（每个都必须能被外壳如实回答）**：

| 字段 | 含义 | 取值来源 |
| --- | --- | --- |
| `shell` | 当前外壳 | `RunMode`（`cli` / `tui` / `server`） |
| `hasUI` | 有可交互界面 | TUI=true；CLI/Server=false |
| `supportsApproval` | 外壳**是否具备审批通道** | TUI=true；Server=true（HTTP 审批桥）；**CLI=false**（无审批者一律拒绝） |
| `interactive` | 有终端 | `System.console() != null` |

**为什么可以给**：这四个字段都是**进程级事实**，不含会话状态、不含工作目录，
不违反「插件拿不到会话与 cwd」这条边界。`supportsApproval` 尤其重要——插件据此可以
「没有审批通道时干脆不要提供需要写权限的动作」，而不是提供之后吃一个拒绝。

**`supportsApproval` 是静态语义，不是「此刻有人在看」**（这条决定要写进 javadoc，
字段名就是为此才不叫 `hasApprover`）：

- 动态语义在 HTTP 下**没有可靠判据**——客户端可以先 `POST /approvals/{id}` 而不持 SSE 流，
  也可以流断了但页面还开着。构造一个「八成正确、偶尔抖动」的值比一个诚实的静态值更坏，
  尤其 §4.3 决定 v1 不配事件，抖动会直接击穿「启动期一次性决策」这个主要用法。
- **两个方向猜错的代价都可接受**，所以不需要精确：猜 `true` 而实际无人 → 走
  `ApprovalChannel` 的 fail-closed 被拒；猜 `false` 而实际有人 → 插件少提供一个功能。
  安全边界本来就在 `ApprovalChannel`，不在这个字段。
- `-cli` 恒为 `false`：那里没有审批者，`ASK` 即拒绝（`permissions.md` 的既有语义）。

**实现落点**：

- `jellyfish-infra` 新增 `RuntimeInfoHolder`（可变持有 + `snapshot()` 返回不可变 `RuntimeInfo`）；
- `Launcher` / `RunMode.checkEnvironment` 在**内核 bootstrap 之前**写入；
  因此插件 `start()` 里拿到的已经是最终值（启动期插件注册不能被时序坑到）；
- `PluginContextFactory.create` / `PluginContextImpl` 构造器注入该 holder，`runtimeInfo()` 现读快照；
- 单元测试走 `PluginContextImpl` 那个不依赖 `PluginContextFactory` 的公开构造路径时，
  holder 缺省为「未知外壳」的快照——因此 `RuntimeInfo` 需要一个 `unknown()` 静态工厂
  （`shell = CLI`、其余全 false），避免测试与嵌入式用法被迫伪造外壳。

**明确不给**：`cwd`、`sessionId`、`agentId`、`contextUsage`、`systemPrompt`、
`modelRegistry`。前四个是会话/环境事实（既有边界不变），后两个是「让插件开始理解请求内容」的入口，
与 `RequestTuning` / `AgingStrategy` 只开放旋钮、不开放内容的既有纪律冲突（见 `architecture.md`）。

### 4.3 是否配事件

**v1 不配**。外壳在进程生命周期内不变；`supportsApproval` 按 §4.2 的**静态语义**取值，
因此它也不随连接变化——整个快照在一个进程里是常量。为常量配一条事件是净增维护面。
若将来真的需要「运行期切换」（例如 Server 要在管理动作里改写审批能力），
再加 `RuntimeInfoChangedEvent`（异步、可丢）；届时要先把 §4.2 的静态语义改成动态的，
并接受插件「启动期一次性决策」这个用法被削弱。

### 4.4 测试点

- `runtimeInfo()` 在三种外壳下返回预期值（用 holder 直接构造三种快照断言）。
- 插件 `start()` 期间读到的就是我最终值（在 `Launcher` 装配测试里断言时序）。
- `unknown()` 快照不抛错，且 `supportsApproval == false`。
- 三种外壳的 `supportsApproval` 取值被单独钉住：CLI=false、TUI=true、Server=true。
- 同一进程内重复调用 `runtimeInfo()` 返回相等快照（常量语义）。

---

## 5. 模型 / 厂商可插拔

### 5.1 现状与缺口

`ModelManager.refresh` 从 `models.json` 读 provider，`LlmClientFactory` 按 `Provider.getType()`
查 Dagger 注入的 `LlmClientCreator` 映射。**插件无法新增 provider type、无法新增模型目录、
无法动态发现模型**。这是能力上限最直接的一块（本地 llama.cpp、企业网关、私有协议、
自定义鉴权全都要改内核代码）。

pi 的 `registerProvider` 覆盖的正是这一层。

### 5.2 两条路线与取舍

**路线 A（已采用）：api 侧只加「传输契约」，`LlmRequest` 等模型留在 infra**

- api 新增 `LlmTransport`（只一个 `send(req, listener)`）；
- api 新增一组**传输值类型**（`LlmTransportRequest` / `Response` / `Message` / `Tool` /
  `ToolCall` / `Usage` / `Listener`），字段是各家协议都有的最小交集；
- infra 新增 `PluginLlmClientAdapter implements LlmClient`，把 `LlmRequest` ↔ `LlmTransportRequest` 互转；
- 插件实现 `LlmTransport`，由 `ProviderRegistrationRequest` 交回内核。

代价：十个左右的请求模型与一层 adapter。
收益：`LlmRequest` / `LlmMessage` / `LlmTool` 这些**会随厂商持续演化**的模型不进入稳定契约。

**路线 B：把 `LlmRequest` / `LlmMessage` / `LlmTool` / `LlmClient` 整体上提到 `api`**

更像 pi，但会把「唯一的稳定契约」变成「随厂商变动的契约」，
且要动 `LlmClientFactory`、所有厂商实现与大量测试。**已否决**。

**落地时的四点修订（P5 定稿）**：

1. **插件只能加 `type`，不能加 provider 实例**（初稿在两处自相矛盾：§5.3 路由键是 provider type，
   §5.4 又说「配置里显式声明的 provider 覆盖插件提供的同名 provider」——后者只有在插件能声明
   provider 实例时才存在）。只加 type 之后 provider 实例一律来自 `models.json`，
   「同名覆盖」这条自动成立且空洞。
2. **反过来加一条真正重要的规则：内核内置 type 不可被插件覆盖**。这不只是先来后到——
   传输请求里带的是<b>已解析好的 apiKey</b>（见 §5.5），一个能顶替 `openai` 的插件等于把所有用户的
   密钥转发到自己的服务器上。因此内核只在自带 type 查不到时才去问注册表，**没有配置开关**。
3. **`LlmTransport.listModels()` 与 `ProviderContribution.of(displayName, transport, models)` 一并去掉**，
   模型发现只留 `ModelCatalogRequest`（路由键 = provider 名）。
   理由：**模型属于 provider，不属于传输**——一个 `LlmTransport` 实例可以服务同一类型下的多个 provider，
   而三个来源（静态声明 / 目录 / 配置）会引出「谁的答案算数」这条没人会读、也没人会记得的优先级规则。
4. **`LlmHttpException` 从 `infra` 搬到 `api`**：§5.5 要求插件复用同一个异常类型，但插件看不到 `infra`，
   这条按字面无法落地。它是 `JellyfishException` 的直接子类、零 infra 依赖，搬家是纯位移；
   否则插件 provider 只能抛无状态码的普通异常，而 **`LlmCallFailedEvent` 丢的
   「400 该降级 / 429 该重试」恰恰是对插件 provider 失效的那块**。

### 5.3 两个请求类型

```java
public final class ProviderRegistrationRequest implements ExtensionRequest<ProviderContribution>
        // 字段：providerType（即 Provider.getType() 的取值，已去空白并小写）
public final class ProviderContribution
        // 静态工厂：unsupported() / of(displayName, LlmTransport transport)

public final class ModelCatalogRequest implements ExtensionRequest<ModelCatalogResult>
        // 字段：providerName、providerType
public final class ModelCatalogResult
        // 静态工厂：empty() / of(List<ModelDescriptor>)
```

- **`ProviderRegistrationRequest` 用 `handle`，路由键 = provider type**（同键唯一）。
  理由：一种类型只能有一个实现，多实现是 `AMBIGUOUS_HANDLER` 的真实场景——同一份配置到底发给谁，
  没有人能回答。（实际上同键唯一由注册表在<b>注册期</b>保证：第二个同类型注册会在那一刻拿到重复错误。）
- **`ModelCatalogRequest` 用 `handle`，路由键 = provider name**。它回答「这个 provider 现在有哪些模型」，
  供 `refreshModels` 式动态发现。**它只被问到插件接管的类型**，内核自带类型有固定的模型来源。
- **0 handler 时**：`ProviderRegistrationRequest` → `unsupported()`，内核报
  「未知 provider 类型」并列出内置类型、给出「装插件或改用既有类型」的下一步；
  `ModelCatalogRequest` → 保留配置里写的 `models`。两者都必须与没有这个扩展点时**逐字段一致**。
- **`ModelDescriptor` 的 `contextLength` / `maxOutputTokens` 允许为 0**，含义是「不知道」：
  内核对此已有统一口径（窗口未知就不按窗口裁历史）。插件因此不必猜一个「看起来合理」的值——
  猜错会让内核提前把历史丢掉，那比报错更难查。

### 5.4 优先级与热更新

- **内核自带的 provider type 永远胜过插件**（见 §5.2 修订 2）。插件提供的是**新类型**，不是新 provider 实例，
  因此不存在「配置 provider 与插件 provider 同名」这种情形。
- **插件 provider 的凭据来自 `models.json` 的 provider 条目本身**：内核把**已解析好的最终值**
  （`${ENV_VAR}` 已插值、双源合并已完成）交给传输，用户只需要维护一处。
  插件自己那层协议还需的额外凭据（企业网关的自定义 header……）走它自己的配置段
  （`PluginContext.configuration()`）——**插件不允许自行读配置文件**，双源合并与插值由内核完成。
- **动态目录在两种时机被询问**：启动时插件全部就绪之后一次，每次 `/reload` 重启插件之后一次。
  用的是独立的 `ModelManager.refreshCatalogs()` 而不是塞进 `refresh(boolean)`，因为后者的两个调用点
  都**早于插件就绪**（启动时 `refresh` 跑在 `pluginManager.bootstrap()` 之前，`/reload` 时它跑在
  `pluginManager.reload()` 之前）——问早了只会拿到空目录或旧实例的目录。
  发现结果**不落盘**（`models.json` 仍是唯一持久事实），且每次都从配置的 provider 列表重新出发，
  因此不会留下上一次发现到、这一次已消失的模型。
- **目录结果非空就整体替换该 provider 的模型列表，为空就保留配置**：两者无法区分时按「保留」处理——
  这是安全的那一侧（发现失败最坏是「用回配置」，而不是「provider 突然没有模型可用」）。
- **插件 provider 的模型同样可被 `/model` 使用**，解析仍走 `SessionModelResolver` 的三级回落，
  不新增第二条解析路径。

### 5.5 安全与失败语义

- **`apiKey` 绝不进日志、绝不进事件载荷**：`LlmTransportRequest` 的 `toString()` 已把它脱敏成 `***`
  （这是插件最容易顺手打日志的对象），内核自己产生的报错文案也不得包含它。
  哨兵单测落在**内核自己会携带/展示的对象**上（传输请求的 `toString()`、adapter 产生与
  `LlmClientFactory` 报出的异常消息）——仓库没有日志后端依赖，断言真实日志输出无从谈起，
  这比写一条永远不生效的日志断言诚实。
- **插件传输调用抛错**：状态码语义复用同一个 `LlmHttpException`（现已住在 `api`，见 §5.2 修订 4）——
  这是 `LlmCallFailedEvent` 已有的判据。插件的其它异常（包括同步路径上的原始 `RuntimeException`）
  由 adapter 在边界统一归一成 `JellyfishException`，否则调用点会漏报一条失败事件。
- **`LlmTransport.send` 是阻塞的**：返回时全部事件已交给监听器，且最后一个一定是
  `onComplete` / `onError` / `onCancelled` 之一。内核据此把同步调用内联执行、把流式调用放到线程池上，
  因此**不需要「等多久算超时」这个新配置项**。插件违约（没投终止事件）时 adapter **报一条错误**，
  而不是把「没有响应」当成「空响应」静默继续——后者会让用户看到一次莫名其妙的空回合，
  而在流式路径上更糟：ReAct 循环正等它收尾，少一个终止事件就是一次永久挂住。
- **取消是协作式的**，令牌复用既有的 `api` 侧 `CancellationToken`（`onCancel` 让插件中断自家 HTTP 调用）。
  内核的取消只是置标志并停止投递后续事件；一个既不轮询也不登记回调的插件会跑到自己的超时为止。
- **插件 provider 在 `stop()` 后失效**：注册按 owner 整批回收，后续查找回到「未知类型」那条路
  （fail-closed，自动成立）。**这是已知边界**：不做「卸载前等待在途调用结束」，如实记录。

### 5.6 非目标

- **OAuth / `/login`**：Jellyfish 没有登录命令与凭据存储，做它是另一整层能力。
  插件若要 OAuth，自己在 `start()` 里做完并写进自己的配置段。
- **嵌入 provider（`streamSimple` 级别的 API 形状定制）**：路线 A 的传输契约是
  「厂商无关的请求 → 厂商格式的 HTTP」，不支持插件重写内核的序列化逻辑。
- **插件提供 provider 实例**（自己声明名字 / 地址 / 模型台账）：只加 type，实例一律来自 `models.json`。
- **运行时注册 `/model` 命令的候选**：候选来自注册表，插件 provider 的模型天然进注册表，不需要新入口。

### 5.7 测试点（均已落地）

- 内核自带 type 与插件注册同名时，**内核胜出且插件处理器根本不会被调用**。
- `ProviderRegistrationRequest` 0 handler 时的报错文案包含内置类型与可执行的下一步。
- 插件 `LlmTransport` 的请求/响应经 adapter 往返后字段无损，**两个方向都覆盖**：
  内核请求 → 传输请求（含消息、工具、缓存三件套、`minimalOutput`），传输响应 → 内核响应
  （含分片工具调用按 index 归并、用量、结束原因）。
- `apiKey` 不出现在传输请求的 `toString()`、adapter 的报错里。
- 插件违约（没投终止事件 / 返回后什么都没投）在同步与流式两条路径上都报错。
- 插件抛 `LlmHttpException` 时状态码不被包装掉；抛其它异常时归一到 `JellyfishException`。
- `/reload` 在**插件重启之后**调一次 `refreshCatalogs()`，且不写 `models.json`。
- 目录结果非空整体替换、为空或处理器抛错时保留配置里的模型；**内核自带 type 不会被问**。

---

## 6. 会话扩展条目与分支

### 6.1 现状与缺口

- 会话是**线性**的：`Session.getMessages()` 一条链，压缩只记 `SessionCompaction.boundaryMessageId`。
- 插件想在会话里存自己的状态，只能塞进工具结果的 `details`（同 pi 的 `details` 惯用法），
  **且必须伪装成一个工具消息**。没有「扩展自有条目」这一层。
- 没有 fork / 检查点 / 回溯。于是「多路径探索」「A/B 对比」「改坏了回退到某一步」都做不了。
- 持久化已经是插件能力（`SessionPersistRequest`），但快照里没有任何位置放扩展数据。

这是**产品形态级别**的缺口，也是本文改动面最大的一块，因此单独拆成「数据模型」与「动作」两部分，
动作依赖 §9 的通道。

### 6.2 扩展条目（数据模型部分）

**新增 api 快照值类型**

```java
public final class SessionExtensionEntry   // 恰好一个可见构造器
        // 字段：key、value(Map<String,Object>)、updatedAt
```

**归属规则**：内核在写入时把 `key` 前缀成 `pluginId` 或 `pluginId::child`
（复用 `PluginOwnerNamespace` 的分隔符与 `requireChildId`），**插件无法写到别人的命名空间**，
与注册的 owner 纪律完全一致。回收：插件停止时**不删除**已落盘的条目
（那是用户的会话数据），只停止后续写入——这条要显式写清楚，否则会出现「插件卸了、历史里的状态也一起消失」。

**`SessionManager` 新增入口**

```java
public Session putExtensionEntry(String sessionId, String owner, String key, Map<String, Object> value);
public Session removeExtensionEntry(String sessionId, String key);
public List<SessionExtensionEntry> extensionEntries(String sessionId);
public List<SessionExtensionEntry> extensionEntriesOf(String sessionId, String owner);
```

**落地时的两点修订**（P4 定稿）：

1. **`owner` 作为显式参数**：上限报错与诊断输出要能说清「谁在写」，而从已拼好的 key 里反推 owner
   属于猜。两个列表入口也拆成两个：`extensionEntries` 给内核（全部 owner），
   `extensionEntriesOf` 给插件侧（前缀过滤，与写入的命名空间隔离对称）。
2. **插件侧入口在 `PluginContext` 上，不在 `SessionManager` 上**：插件拿不到 `SessionManager`，
   而 §6.1 的动机恰恰是「插件想在会话里存自己的状态」。因此新增
   `PluginContext.putExtensionEntry / removeExtensionEntry / extensionEntries` 三个方法，
   由它把 `pluginId` 或 `pluginId::子标识` 拼在 key 前面，并**拒绝含命名空间分隔符的 key**——
   否则 `plugin-a::a::b` 读不出来它到底是「子上下文 `a` 写的 key `b`」还是
   「根上下文写的 key `a::b`」，诊断输出就失去了可归因性。

- 走既有「唯一变更入口 + 标脏 + `flush` 落盘」机制，**不新增第三条落盘路径**。
- 值类型用 `Map<String, Object>`（与 `SessionMessageSnapshot.metadata` 同口径），
  避免 `api` 依赖 Jackson；**序列化由持久化插件负责**（它本来就在决定文件格式）。
- **条目不进 `LlmMessage`**（与 `ToolMetadata` 同口径）：模型不需要它，界面需要。

**容量上限（防止一个坏插件撑爆会话文件）**：条目是插件写入的数据，必须有界。
上限在 `SessionManager`（唯一变更入口）里检查，插件绕不过去；错误信息里带上触发它的 owner
（`pluginId` 或子命名空间），否则诊断输出里分不出是谁撑爆的。

| 项 | 缺省 | 超限行为 |
| --- | --- | --- |
| 单条 `value` 字节数 | 64 KiB | **抛 `JellyfishException`，拒绝写入** |
| 每会话条目总数 | 64 | 同上，错误信息给出「先删旧条目」的下一步 |
| `key` 长度 | 256 字符 | 同上 |
| 关闭整个能力 | — | 配 `0` 表示禁用（逃生门） |

**「字节数」是内核的规范编码**（`ObjectMapperWrapper` 序列化后的 UTF-8 字节数），不是
「插件写进文件后的字节数」——文件格式由持久化插件决定，内核无从得知。上限的目的是
「别让一个坏插件撑爆会话文件」，一个确定、可测、与真实编码同量级的度量就够了；
反过来写死成某个插件的格式会让 `SessionManager` 依赖它。
（初稿写的「配 `0` 关闭」未落地：目前上限是 `SessionManager` 上的三个常量，
要开放配置就连同 `configuration.md` 一起改。）

- **不截断**：截断一个 `Map` 会留下「看起来完整、实际缺字段」的数据——与 `read_file`
  「单行超限就报错、不切短」是同一条已经拍过板的理由
  （`tools-output.md` 原文：「切短会输出一行看起来完整、实际残缺的内容，模型无从判断自己拿到的是不是全文」）。
- **不静默淘汰**：`_path` 的「只在保留窗口内有效」之所以成立，是因为它**写进了用户可见的文档**；
  扩展条目没有这样一个契约位置，悄悄丢会让插件「写成功、重启后没了」，属于最难排查的一类失败。
  宁可让它在写入那一刻就炸。
- 配额粒度 v1 取**每会话总量**而不是每插件——要保护的对象是会话文件大小，总量直接对应它；
  **每插件配额**列为后续项（多个插件互相挤压时才需要）。

**快照 schema**：`SessionSnapshot` 新增 `extensionEntries`、`kind`、`parentSessionId`、
`forkPointMessageId` 四个字段（构造器从 11 参变 15 参）。
按既有规则**不加兼容构造器**——用新静态工厂，老文件缺少该字段时反序列化为空列表 / 空值，
并补一条**往返测试**（`SessionSnapshots` 的既有测试口径）。

**老快照没有 `kind` 字段的映射放在 `SessionSnapshot.getKind()` 里**（而不在恢复路径上）：
`kind` 为空时看 `parentSessionId`，非空则补 `EPHEMERAL`、否则 `NORMAL`。
这样连「插件自己产出的、缺字段的快照」也走同一条映射。
注意实际上这条映射**几乎走不到**：老快照连 `parentSessionId` 都没有（它是本次新加的），
因此真实历史快照总是落到 `NORMAL`；它守的是「中间状态的快照」。

### 6.3 分支与检查点（动作部分）

**v1 只做「单向 fork」，不做完整会话树**。理由是完整树要动 `Session` 的核心表示、
TUI 的 `/tree` 导航交互、压缩边界语义与配对规则，风险与收益不成比例；
而「从某一步复制出一条新会话」能覆盖绝大多数真实需求（检查点、A/B、回退重来）。

**动作**（走 §9 的通道，内核内部也可直接调 `SessionManager.fork`）：

```java
PluginAction.forkSession(sessionId, messageId, title)
```

**落地时的两点修订**（P4 定稿）：

1. **初稿的 `deliverAs` 去掉了**：P3 已定「动作只投进正在跑的回合、内核不起回合」，
   而 `deliverAs` 要表达的是「fork 完之后要不要往新会话里投点什么」——那需要起回合。
   新会话标识写在 `ActionHandle.result()` 里，插件也可以订阅 `SessionCreatedEvent` 自行跟踪。
   fork 在**回合边界**排空（与 `SWITCH_MODEL` 同点：两者都改会话集合类状态）。
2. **切点对齐往「后」推，而不是往「前」退**（推翻了 §12.3 的措辞）。直接套用压缩的对齐
   （向小下标退到 `assistant(tool_use)`）会得到一条**没有结果的** `assistant(tool_use)` 结尾，
   而 `PromptAssembler.dropTrailingDanglingToolCalls` 已经把这种结尾当成无效并从每次请求里丢掉
   ——于是那条消息「在历史里在、模型永远看不到」。更关键的是：往前退会**静默丢掉被指定的那条消息**，
   而按「点哪条就从哪条分」的直觉，复制范围必须包含它。往前进还不需要额外判断「这组结果齐不齐」
   （取消的回合已由 `appendNotRunResults` 补齐，崩溃留下的不齐组本来就在结尾）。
   判定顺序不变：**压缩记录的取舍必须在配对对齐之后**。

**语义**：

- 新会话 `forkedFromSessionId` = 源会话 id，`forkPointMessageId` = 切点；
- 消息复制到 `messageId`（含），**且切点必须落在工具调用组的边界上**——
  直接复用 `core/prompt/ToolPairing` 的对齐规则，否则新会话一开头就是孤儿 `tool` 消息，
  厂商会以 400 拒绝整次请求（这是既有约束，不是新问题）；
- 复制 `agentId` / `permissionMode` / `provider` / `model`；
- **扩展条目照带**：它们是「会话在那一刻的状态」的一部分（插件写的检查点、已扫过的文件……）。
  丢了它们，「回退到某一步」会得到一个自身标记全无的会话。
- **不复制 `usage`**（那是源会话花掉的钱，fork 之后要花新钱；复制会重复计入成本账）；
- **`compaction` 按「边界是否落在复制范围内」取舍**（已决）：
  `indexOf(boundaryMessageId) <= indexOf(切点)` 就带，否则丢弃。
  因为压缩是**非破坏式**的（消息一条不删），两种情况都不丢数据，且都精确复原了源会话在那一刻的状态：
  - 边界在范围内 → 摘要仍代表被丢出上下文的那段，原样带过去；
  - 边界不在范围内 → 那一刻本来还没压缩，不带才是准确复原。
  因此**不需要**「另有 N 条未纳入摘要」这类提示。
- **判定顺序（容易写错的一条）**：配对对齐会把切点沿工具结果**向后推**，可能因此跨到边界之后。
  所以**压缩记录的取舍必须在配对对齐之后判定**，不能在之前。
- 新会话立刻落盘（`fork` 是一次显式的用户/插件动作，不属于「创建不落盘」那个例外）。
- **不把新会话切为当前会话**：切换当前会话是外壳的主权，插件替用户跳过去会让屏幕在用户
  没操作的情况下换掉。

**配套否决钩子**：`SessionBeforeForkRequest` → `LifecycleVerdict`（同 §2 的形状），
调用点在复制之前、`SessionManager.fork` 里（与 `SessionBeforeCloseRequest` 同一处纪律），
让插件能拦下「脏仓库状态下 fork」这类场景。与关闭前钩子不同，**这里的否决一定被采纳**——
fork 是一条显式动作，不是进程收尾路径，不存在「否决只会把资源留在表里」的顾虑。
请求里给的是**对齐之后的切点**：插件看到的必须是内核真正要复制的范围。

### 6.4 与既有 `parentSessionId` 的冲突（必须先解决）

今天 `Session.parentSessionId` **一字段两用**：非空即表示「瞬时的子代理会话」
（`../constraints/session-config.md` 明确写在注释里）。fork 出来的会话也有「父」，
直接复用这个字段会让 `createEphemeral` 的识别条件把 fork 会话误判成瞬时的——
后果是新会话**不落盘**，这是静默的数据丢失。

**方案**：新增 `SessionKind { NORMAL, EPHEMERAL, FORKED }` 作为判定来源，
`parentSessionId` 降级为展示/追溯用的信息字段。

- `createEphemeral` 写入 `kind = EPHEMERAL`；`fork` 写入 `kind = FORKED`；
- 「不进 `all()`、不落盘」的判据从 `parentSessionId != null` 改为 `kind == EPHEMERAL`。
  因此 `FORKED` 与 `NORMAL` 同等对待：**进 `all()`、落盘、可被 `/resume` 与 `/delete`**——
  fork 出来的是用户的正常会话，不是子代理的临时工作区；
- **恢复兼容**：老快照没有 `kind` 字段 → `parentSessionId != null` 映射为 `EPHEMERAL`，
  否则 `NORMAL`（这条映射要有单测）；
- 这是一次**行为判定来源的迁移**，必须全文搜索 `getParentSessionId()` 的使用点逐个确认。

### 6.5 非目标

- **完整会话树 / `/tree` 导航 / 分支摘要**：推后。理由见 6.3，且它需要 TUI 交互设计配套。
- **删除 / 合并分支**：不做。分支的生命周期与普通会话一致（`/delete`）。
- **自动检查点**（每次回合自动 fork）：不做。它会让会话目录指数增长，
  而「谁来决定保留哪些」没有好答案；由插件用 §9 的通道自己决定何时 fork。

### 6.6 测试点

- 扩展条目的命名空间隔离：`pluginA` 写 `x`，`pluginB` 读写不到。
- 插件停止后条目仍在（重启后能读回）。
- fork 的切点落在工具组中间时自动对齐到组开头（复用 `ToolPairing` 的用例）。
- fork 会话 `kind = FORKED`：**进 `all()`、落盘、可被 `/resume` 与 `/delete`**；
  「不进 `all()`、不落盘」只归 `EPHEMERAL`。
- fork 不复制 `usage`；压缩记录按 §6.3 的规则取舍，**且「对齐后跨过边界」这一例要单独覆盖**
  （构造切点紧邻边界、对齐后被推到边界之前的会话）。
- 扩展条目上限：单条超 64 KiB / 超 64 条 / key 超 256 字符都抛 `JellyfishException`
  且**不写入**；错误信息包含 owner；配 `0` 时写入直接报错。
- 老快照（无 `kind`、有 `parentSessionId`）恢复为 `EPHEMERAL`。
- `SessionSnapshot` 往返：`extensionEntries` 与 `kind` 字段无损。

---

## 7. 工具激活与延迟加载

### 7.1 现状与缺口

`ToolCatalog` 按会话**冻结一份工具清单**，这是缓存前缀保证的一部分
（`../constraints/react-compact.md`：会话期间不得增删工具，Plan 模式走权限白名单而不是换工具集）。
这条纪律是对的，但它今天**只由内核执行**，插件没有任何表达「这个工具现在不该出现」的正式手段——
MCP 插件把工具全量注册，用户无法让某几个工具「先别进请求」。

pi 用 `getActiveTools` / `setActiveTools` + 原生 deferred tool loading 解决，
代价是每次激活变化都可能作废前缀（pi 文档自己承认这一点）。

### 7.2 `ToolActivationRequest` → `ToolActivation`

```java
public final class ToolActivationRequest implements ExtensionRequest<ToolActivation>
        // 字段：sessionId、agentId、toolName、ToolDescriptor descriptor、permissionMode
public final class ToolActivation
        // 静态工厂：abstain() / visible() / hidden(String reason)
```

**注册方式**：`contribute` + order 升序。合并规则：**第一个非 `ABSTAIN` 胜出**（order 最小者）。

**调用时机**：**只在 `ToolCatalog` 为该会话冻结清单的那一刻**，对每个已注册工具问一次。
**不是每轮**——每轮问一次就等于把「清单逐轮可变」放回来了，与冻结保证直接冲突。

**无插件时**：0 handler → 全部 `visible`。

**失败语义**：抛错 → 记 WARN、按 `abstain`（即 `visible`）处理。
理由：失败时**保留工具**比隐藏工具安全——隐藏会让模型「不知道有这个能力」而进入死路，
保留只是多几个 token。

**为什么只允许 `hidden`、不允许 `deferred`**：deferred loading 需要厂商协议支持
（Anthropic 的 `defer_loading` / OpenAI 的 `tool_search`），而 `LlmRequest` 今天
是厂商无关的扁平 `tools` 列表。**deferred 明确列为非目标**，等 provider 层（§5）
能表达「这个端点支持延迟加载」之后再评估。

**对子代理同样生效（叠加，不是二选一）**（已决）：

- `ToolActivationRequest` 对**每个**会话的清单冻结都求值，**包括瞬时的子代理会话**；
  子代理路径在求值结果之上再叠加 `ToolFilter`（§7.4），不需要第二条判定路径。
- 理由：「隐藏」表达的是「这个工具在当前上下文里没有意义」。主代理看不到、子代理却看得到，
  会产生**无法解释的不对称**，而且子代理的报告会带回来主会话根本拿不到的能力，
  破坏「只拿回结论」的隔离预期。
- 不会损失表达力：请求里带了 `agentId` 与 `permissionMode`，插件想区别对待时在 handler 里判一下即可。
- 保持 §7.6 的「隐藏 ≠ 禁用」：`ToolExecutor` 直接调用仍可执行，子代理也不会因为隐藏变成权限拒绝。

### 7.3 重建动作与缓存代价

冻结的代价是「插件无法在某轮把工具加回来」。因此配一个显式动作（走 §9 的通道）：

```java
RebuildToolCatalogAction(sessionId, reason)
```

- 内核重建该会话的清单，并**发一条 `CachePrefixChangedEvent`**，`reason` 字段
  新增取值 `TOOL_ACTIVATION`，让既有的缓存监控能归因；
- **必须由调用方显式发起**。内核绝不在注册表变化时自动重建——MCP 的会话中途工具变化
  正是靠「按会话冻结」被挡住的，自动重建会把那个保证拆掉；
- **记录一条 WARN**：这是明确的「用一次前缀断裂换一次能力变化」，值得在日志里留痕。

### 7.4 与子代理 `ToolFilter` 的关系

**不合并**。`ToolFilter` 是「子代理按自己 agent 配置收窄」的**执行期**判据，
`ToolActivation` 是「进不进本次请求的清单」的**组装期**声明。两者的调用点、
判定时机与失败语义都不同（`ToolFilter` 复用 `PermissionManager.usableTools` 的判定，
见 `../constraints/permissions.md`）。合成一个会让「这个工具为什么不在清单里」变成
一个需要判别的字段，而排查时看到的只有结果。

### 7.5 非目标

- **deferred / 延迟加载**（见 7.2）。
- **按轮激活**：`ToolActivation` 只在冻结时求值，不提供 per-turn 入口。
- **自动感知 `tools/list_changed`**：MCP 的重扫仍只改注册表，不影响已冻结的会话清单。

### 7.6 测试点

- 冻结时求值一次：注册变化后已有会话清单不变，新会话生效。
- `hidden` 的工具不进 `LlmTool` 列表，但**仍在注册表里**（`descriptors` 查得到，
  `ToolExecutor` 直接调用仍可执行——即「隐藏 ≠ 禁用」，这条要写进 javadoc）。
- handler 抛错时工具保持可见。
- **子代理的清单冻结也走同一次求值**：主会话隐藏的工具在子代理清单里同样不出现；
  `hidden` 不参与 `ToolFilter` 的收窄判据（两者叠加，不是合并）。
- `RebuildToolCatalogAction` 之后清单变化，且恰好发一条 `CachePrefixChangedEvent(reason=TOOL_ACTIVATION)`。

---

## 8. UI 深度

### 8.1 现状与缺口

插件只能贡献两种**纯数据**：状态栏片段（拼接型）与面板（独占型），
模型是 `UiLine` / `UiSegment`（文本 + `UiEmphasis` 档位），区域是 `UiRegion`
（`STATUS` / `DOCK` / `TOP` / `LEFT` / `RIGHT`）。
工具行的渲染完全由外壳决定，快捷键不可扩展，主题不可扩展。

pi 的表达力（自定义组件、overlay、替换 editor、消息/条目渲染器、快捷键、主题）最强，
但它与 Jellyfish 的「渲染无关 + 三外壳共享」直接冲突：
插件不可能自己造 TamboUI 组件（子优先类加载器会让 `Element` 不是同一个 Class，
这是 `../constraints/shells.md` 明确写过的）。

**本方案的原则：扩数据模型的词汇，不引入新的容器类型，不暴露任何 UI 类库。**

### 8.2 `UiSegmentKind`（扩词汇）

给 `UiSegment` 增加一个**有缺省值**的 `kind` 字段与一个新静态工厂：

```java
public enum UiSegmentKind { TEXT, HEADING, CODE, KEY_VALUE, PROGRESS, LINK }

// 既有构造器与 of(text) / of(text, emphasis) 完全不动，缺省 kind = TEXT
public static UiSegment of(String text, UiEmphasis emphasis, UiSegmentKind kind);
```

- **不新增构造器**（`UiSegment` 今天只有一个可见构造器），只加静态工厂 → 满足 §0.3 第 1 条。
- 外壳各自映射：TUI 按 `kind` 选样式；`-cli` / `-server` 降级为纯文本（`PROGRESS` 渲染成 `[####----] 40%`）。
  **映射表在内核之外**（`jellyfish-tui` 与 `jellyfish-cli`），插件永远只产出数据。
- `PanelContribution`、`StatusLineContribution`、`UiLine`、`UiRegion` **一个都不改**。
  这是「宁可新增旁路类型也不改既有签名」的应用：这几个类型已经被插件直接构造。

### 8.3 `ToolRenderHint`（工具行）

```java
public final class ToolRenderHintRequest implements ExtensionRequest<ToolRenderHint>
        // 字段：toolName、ToolDescriptor descriptor、shell(RuntimeInfo)
public final class ToolRenderHint   // 静态工厂：none() / of(emphasis, collapsedByDefault, showArguments)
```

**注册方式**：`handle`，路由键 = 工具名（同键唯一——一个工具行的样式只能有一套）。

**调用时机**：外壳渲染一条工具轨迹行时，经 `UiContributions` 现查（**不是**每帧——沿用
`UiContributions` 的「失效时收集」模型，工具行按 `toolCallId` 缓存）。

**它能改什么、不能改什么**：

| 项 | 可否 | 理由 |
| --- | --- | --- |
| 轨迹行的强调档位 | 可 | 复用既有 `UiEmphasis`，不需要新词汇 |
| 默认折叠 / 展开 | 可 | 与 `Ctrl+E` 的全局开关叠加，全局开关优先 |
| 是否显示参数行 | 可 | 某些工具的参数是纯噪音（如 heartbeat） |
| 轨迹行**文本内容** | **不可** | 那由 `ToolMetadata.KEY_SUMMARY` 决定，是工具自己写的一句话；让渲染器改写它就会有两个真源 |
| 自定义组件 | **不可** | 见 8.1 |

**与 `ToolMetadata.KEY_SUMMARY` 的关系**：`summary` 回答「这一行说什么」，
`ToolRenderHint` 回答「这一行怎么显示」。两者受众不同、互不解析，
与 `tools-output.md` 里「摘要不参与逻辑分支」的口径一致。

### 8.4 `ShortcutContribution`（快捷键）

```java
public final class ShortcutContributionRequest implements ExtensionRequest<ShortcutContribution>
        // 字段：shell(RuntimeInfo)、已占用的快捷键集合
public final class ShortcutContribution   // 静态工厂：none() / of(List<ShortcutBinding>)
public final class ShortcutBinding        // 字段：key、commandName、description
```

**注册方式**：`contribute` + order 升序（多个插件可共存），**冲突时 order 最小者胜**，
被挤掉的进 `/ui` 的候选清单（与面板落位同一口径）。

**key 的取值必须收窄**，理由是终端事实（`../constraints/shells.md`）：
框架不解析修饰键编码，`Shift+Enter` / CSI-u / CSI-27 一律落成 UNKNOWN，
**只有 `Ctrl+<字母>` 可以按码点比较**。因此：

- 合法形状固定为 `ctrl+[a-z]`（小写归一化）；
- 内核在注册时校验，非法形状**当场报 `JellyfishException`**（编程错误，不是运行时条件）；
- **保留键位不可被占用**：`Ctrl+C`（退出）、`Ctrl+S`（发送）、`Ctrl+T`（思考）、
  `Ctrl+E`（参数）、`Ctrl+O`（鼠标）。内核持有一份保留清单，冲突时拒绝注册并记 WARN。

**执行方式**：快捷键**不直接回调插件**，而是派发成一条 `/命令`——
即 `ShortcutBinding.commandName` 必须是一个已注册的命令。这样：

- 插件不需要「被内核回调」的新能力（保持 `JellyfishPlugin` 的「不能发起回调」）；
- 命令域已有的审计（`CommandExecutedEvent`）、`sessionRequired` 判定、错误处理全部复用；
- 快捷键的可发现性也顺带解决（`/help` 里本来就有这个命令）。

### 8.5 明确不做

| 项 | 理由 |
| --- | --- |
| 自定义 TamboUI 组件 / overlay | 子优先类加载器下 `Element` 不是同一个 Class；且三外壳无法共享 |
| 替换 editor / footer / header | 同上，且这是外壳的版式主权（`ChatLayout` 的账本不能被插件打乱） |
| 主题文件注册 / 自定义颜色 | 颜色映射是外壳的词表；插件只能选用已有 `UiEmphasis` 与 `UiSegmentKind`。若要新增档位，由内核加（本文不涉及） |
| 消息 / 条目的自定义渲染器 | 与「markdown 只在 assistant 正文渲染」的既有取舍冲突，且需要一套组件生命周期 |
| 富文本表格 | 终端里中英混排按列对齐要赌字宽表，`architecture.md` 已明确「表格降级为代码块」 |

### 8.6 测试点

- `UiSegment` 的既有构造器与 `of(text)` 行为不变（防回归）。
- 三种外壳对每个 `UiSegmentKind` 都有映射，且 `-cli` 不抛错。
- `ToolRenderHint` 0 handler 时工具行与今天逐字段一致。
- 快捷键非法形状（`shift+a`、`ctrl+1`）注册报错；保留键位被拒。
- 快捷键派发的是命令，且命令不存在时快捷键注册失败（`getCommands` 校验）。
- 两个插件抢同一个键时 order 小者生效，另一个出现在 `/ui` 候选里。

---

## 9. 插件主动能力：动作通道

### 9.1 缺口与设计约束

今天 `PluginContext` 只有「注册 / 订阅 / 发布」，**插件不能发起任何事**
（`JellyfishPlugin` 的 javadoc 原话：「不能发起回调」）。这换来的是
「没有重入、没有回调到内核的同步环、插件崩溃不会拖死内核」，
但也把插件锁死在「能力提供者」，做不了工作流参与者（检查点、handoff、文件触发、
自动提交——pi 的 `sendUserMessage` / `ctx.compact` / `ctx.abort` 覆盖的正是这些）。

**本方案要拿到的是能力清单，不是调用方式**：
插件仍然**不能同步回调内核**，它只能**投递一条声明式动作到队列**，
由内核在**定义好的安全点**排空并执行。

这与既有的一条纪律完全同构：**子代理的嵌套回合内联在调用线程上跑、绝不进 `react` 池**——
区别只在方向（那条是「内核调用内核」时的线程纪律，这条是「插件要求内核做事」时的时序纪律）。

### 9.2 动作清单（有界，逐条列明）

**api 新增抽象类型**（Java 8 没有 sealed，用抽象类 + 静态工厂 + 私有构造器）：

```java
public abstract class PluginAction {
    // 每个动作都带 sessionId：插件是进程级的（PF4J 一次加载、能看到所有会话），
    // 「当前会话」对它不是天然概念，目标会话必须显式给出
    public static PluginAction sendUserMessage(String sessionId, String text, DeliverAs as);
    public static PluginAction compact(String sessionId);
    public static PluginAction abortTurn(String sessionId);
    public static PluginAction switchModel(String sessionId, String provider, String model);
    public static PluginAction forkSession(String sessionId, String messageId, String title);
    public static PluginAction rebuildToolCatalog(String sessionId, String reason);
}

public enum DeliverAs { STEER, FOLLOW_UP }
```

**落地时的两点修订**（P3 定稿，与本文初稿的差别）：

1. **`DeliverAs` 只有两个取值，没有 `NEXT_TURN`**。它要表达的是「不着急，下次投递时带上」，
   而那需要一个跨回合存活的待发队列；P3 决定不做这个扩展点（见 §9.8）。
2. **`sendMessage` 与 `sendUserMessage` 合并成一个**。初稿里两个工厂参数完全相同，
   而没有任何一处能说明它们的行为差异——两个同名的行为会让插件作者无法判断该用哪个。
   保留 `sendUserMessage`：它说的是这条消息**以什么身份**进入会话，是本类唯一有定义的那件事。
3. **`compact` 不带 `instructions`**：内核与 `/compact` 都没有「按这段指示压」这条能力，
   而压缩策略已经有一条自己的路（`CompactionStrategy`）。保留一个没有落点的参数字段
   比去掉它更糟——它会静默失效。

**与 `PluginContext` 的接口**：

```java
ActionHandle submit(PluginAction action);   // 入队即返回，绝不内联执行
```

`ActionHandle` 提供 `status()`（`QUEUED` / `EXECUTING` / `DONE` / `FAILED` / `DROPPED`）与
`result()`——**轮询式**，与既有的 `InputDirectiveRun`、压缩状态同形态（外壳每帧轮询，
TUI 的渲染线程契约因此不被破坏）。

**失败是常态而不是异常**：`submit` 不抛「拿不到在途回合」这类错，而是回报 `FAILED` + 原因。
典型来源是插件从事件订阅回调投递（事件是异步投递的，可能落在回合刚结束之后）
或从自己的线程投递。

### 9.3 排空点（必须在实现时逐条写死）

| 动作 | 排空点 | 线程 | 说明 |
| --- | --- | --- | --- |
| `ABORT_TURN` | **不入队**，投递时直接对当前 `ReActTurn` 置取消标志 | 提交线程 | 与 `ReActTurnImpl.cancel()` 既有语义一致（「发个信号、置个标志」这类快动作），从渲染线程调用是既有的。入队再等排空反而会让它错过自己想中止的那个回合。**没有在途回合也算成功**：「已经没有回合可中止了」与「中止成功」的结果相同 |
| `STEER`（`sendUserMessage`） | 一轮工具批次执行完、**下一次模型调用之前** | `react` 线程 | 与 pi 的 steer 语义一致：不影响本轮的助手回复，影响下一轮 |
| `FOLLOW_UP`（`sendUserMessage`） | 模型**本要收敛那一刻**（它已经不再要求工具调用） | `react` 线程 | **不是开新回合，而是让本回合多跑一轮**（见下方「回合内的插入」） |
| `COMPACT` | 与 `/compact` 完全同一条路径（`compact` 线程池） | `react` 线程发起 | 复用既有的状态轮询与失败上报 |
| `SWITCH_MODEL` | 回合边界（与 `STEER` 同点） | `react` 线程 | 改缓存前缀，因此只在回合边界做，避免中途换模型 |
| `FORK_SESSION` | 投递时即回报 `FAILED` | — | P3 阶段尚未提供（依赖会话分支能力，随 P4 落地） |
| `REBUILD_TOOL_CATALOG` | 投递时即回报 `FAILED` | — | P3 阶段尚未提供（依赖工具清单缓存，随 P6 落地） |

### 9.3.1 回合内的插入（P3 定稿，推翻本文初稿）

初稿把 `FOLLOW_UP` 定为「回合 `finally` 里 `flush` 之后，**追加一个新的顶层回合**」。
**这条做不到**，而且做的方向也不对：

- **做不到**：那个时刻 Server 的回合闸门（`SessionTurns`）还没释放——
  `ReActTurnImpl.await()` 是 `future.get()`，而 `future` 在**整个任务返回时**才完成，
  `runTurn` 的 `finally` 是任务体的一部分。因此新回合走闸门必然 409，绕过闸门就是静默破坏
  「一会话一回合」；TUI / CLI 根本没有闸门，而后台回合的取消入口、在途指示、输入互斥
  全部缺位。
- **方向不对**：这个内核里「一次 `chat` 调用 = 一个回合」，回合的边界由外壳决定。
  让内核自己起回合，等于让外壳不知道发生了什么事。

**定稿的做法**：把两个用户消息类动作都做成**往当前回合里插一条消息**，不新增回合：

- `STEER` 插在工具批次之后，因此**下一轮**模型调用就带着它；
- `FOLLOW_UP` 插在收敛点，让本轮循环 `continue` 而不返回——回合长了一轮，但仍是一个回合：
  同一个取消句柄、同一个 listener、同一次 SSE 流、同一个在途状态。

**两个后果必须一起接受**：

1. **`maxRounds` 仍是硬上界**，插入不允许把它撑长。没有剩余轮次时消息类动作直接 `FAILED`
   （「本回合轮次已用尽」），而不是往历史里塞一条永远发不出去的提问——那会与用户的下一次输入
   连成两条 `user` 消息。
2. **插入的文本必须并入下一条 user 消息，不能另起一条**。`STEER` 的插入点上，会话里最后几条是
   `assistant(tool_use)` + `tool`，而 Anthropic 要求 user / assistant 交替（见
   `ClaudeLlmClient` 里「同一轮的多个工具结果必须合并」那句注释）。因此 `ClaudeLlmClient` 的映射
   要把「紧跟工具结果的用户文本」并进那条 `user` 消息，作为 `tool_result` 块之后的 `text` 块。
   这顺带修掉了一个既有隐患：回合被取消时为未执行的调用补上工具结果后，用户再开一轮也会形成同样的形状。

**四条硬规则**：

1. **绝不在提交者的栈上执行**。工具 handler 跑在 `react` 线程上，它提交的动作只在
   「工具批次结束后的排空点」执行——因此不存在「handler 还没返回，动作已经改了会话」的重入。
2. **每会话一个队列**（Server 下多会话并发），全局队列只在 `FOLLOW_UP` 需要「谁来起新回合」时用。
   与 `SessionTurns` 的「一会话一在途回合」语义对齐。
3. **目标会话必须是根会话**：子代理会话（瞬时）一律拒绝——它随父回合结束而消失，
   往里面写消息或压缩它都没有意义。动作通道只服务外壳看得见的会话。
   该拒绝在投递时给出（原因写明「只能投进正在跑的顶层回合」），不拖到排空点。
4. **队列有界**（缺省每会话 16），**满了就丢并记 WARN**。
   丢弃是安全的：动作是「建议内核做事」，不是「必须完成的事实」。
   （初稿写的「可配」未落地：容量目前是常量 `ActionQueue.DEFAULT_CAPACITY`，
   要开放配置就连同 `configuration.md` 一起改。）
5. **窗口的存活期恰好是一个回合**：`ReActLooper.chat` 在**提交任务之前**打开窗口，
   任务的 `finally` 关闭它。回合结束时仍在队列里的动作标 `FAILED`（「本回合内没能排空」）——
   它们不是被淘汰的，而是再也不会有人来取了，插件需要知道这次没赶上。

### 9.4 生命周期、归属与失败语义

- `submit` 经 `ContextLifecycle` 检查，**`stop()` 之后一律当场抛 `JellyfishException`**（fail-closed）。
- 每个动作带 owner（`pluginId` 或子命名空间）；**插件停止时，它的在途排队动作整批丢弃**
  （与注册的前缀回收同一时刻、同一纪律）。已经在执行的动作不打断。
- 动作执行失败：`ActionHandle.status() = FAILED`，`result()` 带原因；**不回灌给插件**（插件轮询得到），
  也不影响回合本身——除了 `COMPACT` / `FORK` 这类本来就有自己失败通道的。
- **动作不得放宽权限**：`sendUserMessage` 产生的用户消息进入正常回合，工具调用照旧过权限；
  `switchModel` 改的是会话模型，不影响权限判定链。

### 9.5 与「插件不能发起回调」的关系（必须同步改文档）

`JellyfishPlugin` / `PluginContext` 的 javadoc 今天写着「不能发起回调」。本方案落地后，
措辞要改成：

> 插件**不能同步回调内核**，只能**投递声明式动作**；动作由内核在安全点排空执行，
> 执行结果经 `ActionHandle` 轮询取得。

`AGENTS.md` 里「内核与插件之间的交互只走 `ExtensionRegistry` / `EventChannel`，
禁止引入第三方事件总线」这条**不变**：动作队列是内核自有的、有界的、单向的入站队列，
不是事件总线（没有订阅、没有广播、没有 handler 注册）。

### 9.6 明确不做

- **给插件的子代理委派能力面**：与 `architecture.md` 的「已知边界」一致——
  插件不能编排并行 / 链式委派。`PluginAction` **不含** `subagent` 类动作。
- **工作流引擎 / DAG 编排**：不做。动作是「单条请求」，不是「编排原语」。
- **直接执行工具**：不走动作通道。想执行工具就注册 `ToolCallRequest`
  （那本来就是插件的正题），或让用户敲 `!`。
- **关闭 / 删除会话**：那是用户主权（`/delete`），不做成插件动作。
- **写配置**：`SessionDefaults` 与配置文件都不开放（`session-config.md` 已有明确口径）。
- **直接改内核状态**：没有 `setMessages` / `setSystemPrompt` / `setUsage` 这类入口，
  与「`RequestTuning` / `AgingStrategy` 只开放旋钮」的既有纪律一致。

### 9.7 测试点

- `submit` 之后**在提交线程返回之前动作一定没执行**（`ActionQueueTest` 断言它停在 `QUEUED`）。
- `STEER` 在工具批次结束后、下一次模型调用前生效（`ReActLooperTest` 断言它落在工具结果之后）。
- `FOLLOW_UP` 让**本回合**多跑一轮（不是开新回合），且 `maxRounds` 仍是硬上界。
- `stop()` 之后 `submit` 抛 `JellyfishException`；停止时在途动作整批丢弃（`DROPPED`）。
- 队列满时丢弃并记 WARN，`ActionHandle.getStatus() == DROPPED`。
- `ABORT_TURN` 不入队，投递时就中断回合。
- 回合结束时残留的动作标 `FAILED`。
- 回合内插入的文本与工具结果合处同一条 `user` 消息（`ClaudeLlmClientTest`）。

### 9.8 明确不做：待发队列与「空闲时起回合」

初稿的 `NEXT_TURN` 与「`FOLLOW_UP` 在无在途回合时起一个新回合」都是对同一个问题的回答：
**插件在回合之外（或回合已结束时）想说话**。P3 决定不做它们，理由逐条如下：

- **那些时刻大多不在回合里**。插件「有话要说」绝大多数是由回合内的同步处理器触发的
  （工具 handler、权限判定、参数改写、`TurnBeforeRequest`……）——那正是动作通道完整覆盖的那一类。
  余下三类（回合外的同步处理器、事件订阅回调、插件自己的线程）都要么本来就不属于回合，
  要么来得太晚。
- **「空闲时起回合」需要先把回合所有权收归内核**：三个外壳上分别缺回合闸门（Server 的
  `SessionTurns` 在排空点上还没释放）、取消入口与在途指示（TUI / CLI），以及一个「谁在等」
  （CLI 是一次性批处理，跑完就收敛）。这不是一个选项，而是一次独立迁移。
- **待发队列会引入第二个真源**：「下次投递时带上」必须把消息并入下一条 `user` 消息
  （否则连续 `user` 被 Anthropic 拒），于是产生「合并规则 + 三个外壳各一条可见提示 +
  会话关闭时怎么清」一整套，而收益只覆盖「用户恰好在那之后开一轮」。

**可以恢复的条件**：出现真实的「无人值守、由外部事件驱动」诉求时，按
「外壳在自己空闲时收割队列」做——队列仍在 core，但起回合仍由外壳做，
取消入口、在途指示与输入互斥全部是现成的，**不需要动 `SessionTurns`**。
Server 的那一期还需要一个后台触发点，那是它的真实代价。

---

## 10. 落地分期

依赖关系：§9 是 §6.3 与 §7.3 的前置；§5 与其余各点互相独立。

**类型归属**：`LifecycleVerdict` 随 **P2** 落地（它的三个调用点都在 §2），`ActionHandle` 随 **P3** 落地
（它唯一的生产者是 `PluginContext.submit`）。**P0 不预先产出零调用点的 `api` 类型**——
`api` 是插件作者唯一的稳定契约，落进去就是承诺；在唯一驱动者出现前两期冻住它的形状，
等于用一个还没发生的场景去定契约。

| 期 | 内容 | 前置 | 风险 |
| --- | --- | --- | --- |
| **P0** | **只做文档**：公共约定口径（§0）落到 `constraints/`（无代码） | 无 | 低（无行为变化） |
| **P1** | §1 工具管道 + §4 `RuntimeInfo` | P0 | 中（动 `ToolExecutor` 的唯一执行点，必须有无插件回归测试） |
| **P2** | §3 输入改写 + §2 三个生命周期钩子 | P0 | 中（否决语义会牵动外壳的展示与退出码） |
| **P3** | §9 动作通道 | P0 | **高**（线程模型与生命周期，必须先写测试再改） |
| **P4** | §6 会话扩展条目与分支（含 `SessionKind` 迁移） | P3 | **高**（快照 schema + `parentSessionId` 语义迁移）**已完成** |
| **P5** | §5 模型 / 厂商可插拔 | P0 | 中（凭据处理与 adapter 转换）**已完成** |
| **P6** | §7 工具激活 + §8 UI 深度 | P3（§7.3） | 低（§8）／中（§7 与缓存前缀保证的交互） |

**跨期纪律**：每一期结束都必须保证「不注册任何新扩展点的老插件」行为**逐字节不变**，
并用上一期的回归用例守住。这是本文最重要的验收标准——
§1 与 §7 都直接触碰缓存前缀与执行语义，任何一处漂移都会以「命中率下降」这种
难归因的方式暴露出来。

---

## 11. 文档与测试清单

### 11.1 文档（落地时逐条同步）

| 文档 | 要补什么 |
| --- | --- |
| `../constraints/extensions.md` | 新增的同步扩展点清单、`RuntimeInfo` 的边界、动作通道与「不能发起回调」的新措辞、动作队列「不是事件总线」的说明、厂商可插拔与目录发现的 0 handler / 失败语义 |
| `../constraints/tools-output.md` | 工具执行管道的七步顺序、`DENY` 与权限审计分开、后置变换必须在 limit 之前 |
| `../constraints/permissions.md` | 「变换在权限之前」这条决定及其理由（TOCTOU）、`DENY` 不进权限审计 |
| `../constraints/react-compact.md` | `CompactionPreRequest`、`TurnBeforeRequest` 的 `BLOCKED`、`ToolActivation` 与清单冻结的关系、重建动作与前缀断裂 |
| `../constraints/session-config.md` | 扩展条目、`SessionKind` 迁移、fork 的配对对齐与「不复制 usage」 |
| `../constraints/shells.md` | `UiSegmentKind` 的三外壳映射、`ToolRenderHint`、快捷键的合法形状与保留键位、`RuntimeInfo` 的取值表 |
| `../architecture.md` | 扩展层表格补新点、LLM 层的「传输契约 + 插件 provider」说明；「已知边界与后续项」补本文的非目标（deferred 加载、完整会话树、自定义组件、OAuth、deferred provider） |
| `../../README.md` / `../configuration.md` | 仅当新增配置项（动作队列容量、`ToolActivation` 开关）时同步；P5 **无新配置项**，但 `models.json` 的 `type` 一节要补「插件可提供新 type」与动态目录的口径 |

### 11.2 测试

- **快照往返**：`SessionSnapshot` 新增字段（`extensionEntries`、`kind`）的往返测试，
  以及「老文件缺字段」的兼容映射。
- **顺序与合并**：每个 `contribute` 点的 order 语义、短路规则、`ABSTAIN` 传递。
- **fail-closed**：所有新入口在 `stop()` 之后的行为，以及 `SHUTDOWN` 下否决被忽略。
- **线程**：动作通道「不在提交者栈上执行」、嵌套回合的 `@Timeout` 兜底
  （`react-compact.md` 明确要求）。
- **回归**：每个扩展点的「0 handler = 今天的行为」逐字段断言。
- **命名规范**：`{被测试类名}Test` + `{方法}_should_{预期}_when_{条件}`，
  JUnit5 + Mockito，`@ExtendWith(MockitoExtension.class)`。

---

## 12. 已决事项（决策记录）

写下本文时留有 7 个待拍板项，现已全部决定。本节是**决策记录**：每条记下取值与当时的推导，
供后续改动时判断「能不能推翻」。正文中凡引用本节的地方，以此处的结论为准。

### 12.1 `BLOCKED` 回合的 CLI 退出码 = `7`

- **决定**：新增 `ExitCodes.TURN_BLOCKED = 7`；`CliRunMode.executeTurn` 的判定顺序为
  `blocked → 7`、`cancelled → 4`、`truncated → 6`、否则 `0`；**stdout 保持空**，原因只进 stderr。
- **推导**：它不是运行失败（没抛异常、没被用户取消、没有资源故障），脚本对它的补救动作
  （改请求 / 找人确认）与对 `4`（看日志排故障）完全不同。现有集合已经在做同类区分
  （`0` = 未知命令 vs `4` = 跑挂了），把「策略拦下」塞进 `4` 会丢掉同一层的信息。
  `ExitCodes` 的注释明确写了「刻意避开 shell 保留的 126/127 与信号码」，`7` 空闲且不冲突。
- **推翻条件**：若实测发现大量脚本把「任意非零」当基础设施故障处理，才重新考虑。
- **影响面**：`ExitCodes`、`CliRunMode.executeTurn`、`README.md` 的退出码契约表、`shells.md`。

### 12.2 `ToolArgumentPreRequest` 的 `DENY` 不审计

- **决定**：不新增事件；`ToolMetadata.KEY_TERMINAL = "REJECTED"`（不是 `FAILED`），
  原因进回灌文本；`PermissionDecidedEvent` 仍是权限审计的唯一真源。
- **推导**：`PermissionDecidedEvent` 回答的是「权限系统放没放行」，而这个 `DENY` 是「插件拒了这条参数」；
  合进去会让权限计数失真。给 `ToolCallCompletedEvent` 加字段则会让一条热路径事件背上罕见字段，
  所有订阅方都要改。今天没有合规消费者，而**新增事件类型是纯向后兼容的**，没有锁死成本。
  `REJECTED` 让展示层可区分「工具自己失败」与「插件拒了参数」，且不需要第四个约定键。
- **推翻条件**：出现第一个真实合规订阅方时，加 `ToolArgumentRejectedEvent`（异步、可丢）。
- **影响面**：`ToolMetadata` 加一个常量与判断；`tools-output.md` 的「约定只有三个键」补一句
  「`KEY_TERMINAL` 的取值集合包含工具自定义的值」。

### 12.3 fork 的压缩记录按「边界是否落在复制范围内」取舍

- **决定**：`indexOf(boundaryMessageId) <= indexOf(切点)` 就带，否则丢弃；
  **判定必须在配对对齐之后**；不需要「另有 N 条未纳入摘要」这类提示。
  **【P4 落地时修订】**对齐方向改为**向后推**（见 §6.3 的落地修订）：初稿的「向前退到工具组开头」
  会留下一条没有结果的 `assistant(tool_use)` 结尾，而且会静默丢掉被指定的那条消息。
  本条的其余部分（判定顺序、取舍规则、不丢数据）不变——把「对齐后的切点」代入即可。
- **推导**：压缩是非破坏式的（消息一条不删，只记 `boundaryMessageId`），所以两种情况都不丢数据，
  且都精确复原了源会话在那一刻的状态（边界不在范围内时，那一刻本来还没压缩）。
  配对对齐会把切点向前退到工具组开头、可能跨到边界之前，因此顺序不能反。
- **推翻条件**：若将来压缩改成破坏式（真的删消息），本条立刻失效，需重新设计。
- **影响面**：`SessionManager.fork`、`session-config.md`、`@see` 配对对齐的用例。

### 12.4 动作可跨会话投递，但只有当前会话能起新回合

> **【P3 落地时被 §9.8 部分推翻】**下面的推导（「写历史无害、花模型钱要有理由」）仍然成立，
> 但结论里的「`FOLLOW_UP` 降级为 `NEXT_TURN`」不成立——`NEXT_TURN` 与「空闲时起回合」都归到了
> §9.8 的「明确不做」。现在的口径是：**动作只投进正在跑的顶层回合**，
> 跨会话投递因此只剩「写进某个正在跑的回合」这一种形态，而它与目标是不是当前会话无关。

- ~~**决定**：`PluginAction` 都带 `sessionId`；允许写给任意非瞬时会话；
  瞬时会话（子代理）一律拒绝；`FOLLOW_UP` 的目标不是当前会话时**自动降级为 `NEXT_TURN`**；
  `ABORT_TURN` 不受限制。~~
- **仍然成立的部分**：`PluginAction` 都带 `sessionId`；瞬时会话（子代理）一律拒绝
  （它随父回合结束而消失）；`ABORT_TURN` 不受限制。
- **推导**：Jellyfish 的插件是进程级的（PF4J 一次加载、能看到所有会话），
  「当前会话」对它不是天然概念，一刀切禁止会挡掉真实用法（把 CI 结果写进指定会话）。
  但「允许自动起回合」会在**用户没看着的会话上烧 token**，而那是既没审批也没提示的动作。
  收窄这一半、放开另一半，正好对应「写历史无害、花模型钱要有理由」。
  与 pi 对齐：pi 的扩展实例随会话重绑（`session_shutdown` → `session_start`），它也不跨会话自动起回合。
- **推翻条件**：若出现「必须由外部事件驱动某会话自动跑一轮」的真实需求，
  那应当是显式打开的能力（甚至是一条显式命令），而不是把默认放开。
- **影响面**：§9.2 的动作签名、§9.3 的排空点表、§9.8、`extensions.md`。

### 12.5 `RuntimeInfo.supportsApproval` 是静态语义

- **决定**：字段名从 `hasApprover` 改为 `supportsApproval`；TUI=true、Server=true、CLI=false；
  v1 不配事件。
- **推导**：动态语义在 HTTP 下没有可靠判据（可以先 POST 而不持 SSE 流，也可以流断了页面还开着）；
  一个「八成正确、偶尔抖动」的值比诚实的静态值更坏，尤其 v1 不配事件时会直接击穿
  「启动期一次性决策」这个主要用法。两个方向猜错的代价都可接受（猜 true 被 fail-closed 拒、
  猜 false 少提供功能），安全边界本来就在 `ApprovalChannel`。**改名是为了让「此刻有人吗」
  这种误读在编译期就不可能发生**，比在 javadoc 里写一句「注意不是实时值」有效。
- **推翻条件**：出现「必须在运行期切换」的真实需求时，改成动态并接受启动期决策被削弱。
- **影响面**：`RuntimeInfo`（尚未落地，改名零成本）、`shells.md` 的取值表。

### 12.6 `ToolActivation.hidden` 对子代理叠加生效

- **决定**：`ToolActivationRequest` 对**每个**会话的清单冻结都求值（含瞬时的子代理会话）；
  子代理路径在求值结果之上再叠加 `ToolFilter`，不合并判据。
- **推导**：「隐藏」表达的是「这个工具在当前上下文里没有意义」；主代理看不到、子代理却看得到，
  会产生无法解释的不对称，而且子代理的报告会带回来主会话根本拿不到的能力，
  破坏「只拿回结论」的隔离预期。插件想区别对待时本来就拿得到 `agentId` 与 `permissionMode`，
  所以叠加不损失表达力，只把默认行为定成一致。实现上也让子代理复用同一次求值，
  不会出现「两处各写一遍判定、在某个边界上分叉」——这正是 `permissions.md` 明确反对的形态。
- **推翻条件**：无。除非将来 `ToolActivation` 的语义从「能不能进清单」变成「准入」。
- **影响面**：`ToolCatalog` 的冻结路径（子代理路径必须走同一次求值）、`react-compact.md` 的工具清单条目。

### 12.7 扩展条目上限：64 KiB / 64 条 / key 256，超限 fail-loud

- **决定**：单条 `value` 64 KiB、每会话 64 条、`key` 256 字符；超限**抛 `JellyfishException`、
  拒绝写入**（不截断、不静默淘汰），错误信息带 owner；配 `0` 关闭整个能力；检查在 `SessionManager`。
- **推导**：**不截断**是与 `read_file`「单行超限就报错、不切短」同一条已经拍过板的理由——
  截断一个 `Map` 会留下「看起来完整、实际缺字段」的数据。**不静默淘汰**是因为
  `_path` 的「只在保留窗口内有效」之所以能被接受，靠的是它**写进了用户可见的文档**；
  扩展条目没有这样一个契约位置，悄悄丢会让插件「写成功、重启后没了」，属于最难排查的一类失败。
  配额粒度取每会话总量：要保护的对象是会话文件大小，总量直接对应它。
- **推翻条件**：多个插件互相挤压成为真实问题时，再加每插件配额（叠加，不取代总量），
  以及按会话自动清理的策略——但那时必须先把清理规则写进用户可见的文档。
- **影响面**：`SessionManager` 的写入路径、`JellyfishSettings`（新增上限配置）、
  `configuration.md`、`session-config.md`。

### 12.8 仍未决（不在本轮范围）

- `ToolActivation` 的 `deferred` 取值：等 §5 的 provider 层能表达「这个端点支持延迟加载」之后再评估。
- `CompactionDirective` 能不能让插件直接提供摘要正文：取决于 §5 落地后要不要开放摘要层的能力面。
- 每插件（而不是每会话）的扩展条目配额：见 §12.7 的推翻条件。

### 12.9 P3 落地时对 §9 的三处修正（已归入正文）

- **`FOLLOW_UP` 不开新回合，而是让本回合多跑一轮**（§9.3.1）。初稿把它定在
  「回合 `finally` 里追加一个新的顶层回合」，而那个时刻 Server 的回合闸门还没释放，
  TUI / CLI 则根本没有任何回合所有权机制。
- **`DeliverAs` 无 `NEXT_TURN`，`sendMessage` 与 `sendUserMessage` 合并，`compact` 不带指示**（§9.2）。
- **待发队列与「空闲时起回合」明确不做**（§9.8），含恢复条件。

### 12.14 P5 落地时对 §5 的修正（已归入正文）

- **插件只能加 `type`，不能加 provider 实例**；反过来，**内核内置 type 不可被插件覆盖**
  （安全红线：传输请求带已解析的 apiKey）。初稿的「配置 provider 覆盖插件同名 provider」被改写。
- **模型发现只留 `ModelCatalogRequest`**（路由键 = provider 名），去掉 `LlmTransport.listModels()`
  与 `of(displayName, transport, models)`。
- **`LlmTransport.send` 定为阻塞式**，取消复用既有 `CancellationToken`，因此不新增超时配置项；
  插件没投终止事件时 adapter 报错，而不是当成空响应（流式路径上那就是一次永久挂住）。
- **`LlmHttpException` 从 `infra` 搬到 `api`**：否则 §5.5 的「插件复用同一个异常类型」按字面无法落地。

### 12.15 动态目录的询问时机（P5 定稿）

**决定**：用独立的 `ModelManager.refreshCatalogs()`，由装配根在**插件就绪之后**调两次——
启动时（`pluginManager.bootstrap()` 之后）与 `/reload` 时（`pluginManager.reload()` 之后）。
初稿说的「`ModelManager.refresh` 之后、广播 `ConfigReloadedEvent` 之前」在**时间区间上仍然成立**，
但**不能真的在 `refresh` 里做**：两个 `refresh` 调用点都早于插件就绪（启动时早于 `bootstrap()`，
`/reload` 时早于 `reload()`），问早了只会拿到空目录或旧实例的目录。两个调两处都用
`InOrder` 单测钉住。

---

## 附：改动一览（本轮决策改到正文的哪些地方）

| 决策 | 改到的正文位置 |
| --- | --- |
| 12.1 `BLOCKED` = `7` | §2.2.3、§2.3 |
| 12.2 `DENY` 不审计 | §1.2、§1.4、§1.5 |
| 12.3 fork 与压缩边界 | §6.3、§6.4、§6.6 |
| 12.4 动作跨会话 | §9.2、§9.3、§9.8（P3 落地时部分推翻） |
| 12.5 `supportsApproval` | §4.2、§4.3、§4.4 |
| 12.6 `hidden` 叠加 | §7.2、§7.6 |
| 12.7 条目上限 | §6.2、§6.6 |
| 12.9 P3 对 §9 的修正 | §9.2、§9.3、§9.3.1、§9.7、§9.8 |
| 12.14 P5 对 §5 的修正 | §5.2、§5.3、§5.4、§5.5、§5.7 |
| 12.15 目录询问时机 | §5.4、§5.7 |
