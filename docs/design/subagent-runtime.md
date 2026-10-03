# 设计：子代理运行时（agent run）（P0–P3 总纲）

> **状态：设计已定（决策见第 5 节）；P0 / P1 已落地，P2 / P3 未开工。**
> 本文是子代理从「父回合里的一个阻塞工具调用」升级为「一等公民 agent run」的设计与落地计划。
> 对外口径见 [architecture.md](../architecture.md) 与 [constraints/react-compact.md](../constraints/react-compact.md)；
> 施工按第 11 节的阶段顺序推进，每阶段独立可合并、可回滚。

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P0 | agent run 原语 + 并行调度 + governor | **已落地**（S1–S5：`RunRegistry` / `RunScheduler` / `AgentRuntime` / `RunContext`+`RunTree` / 墙钟与 token 预算 / `tryAcquireSpawn` / `cancelTree` 与孤儿清理） |
| P1 | 观测（run 事件 + 面板）与归档 | **已落地**（`RunEventBus` + `AgentRunEvent` + `SubAgentPanel` + `SubAgentArchive`；`-server` SSE 发 `run_started`/`run_finished`） |
| P2 | 声明式编排 spec（插件） | **已落地**（分册：[`subagent-runtime-p2.md`](subagent-runtime-p2.md)：api 委派端口 + core 适配器 + 插件 `jellyfish-plugin-workflow`；面板贡献与端到端待定） |
| P3 | 共享任务列表（agent 团队远景） | **未开工** |

> **P0 分册**：[`subagent-runtime-p0.md`](subagent-runtime-p0.md)（原语签名、`RunRegistry` / `RunScheduler` 边界、
> `RunScope` 去 `ThreadLocal` 的迁移路径、governor 落地与测试计划）。本文只保留机制与阶段；
> P0 的施工细则以分册为准。
>
> **P1 分册**：[`subagent-runtime-p1.md`](subagent-runtime-p1.md)（运行面板、归档、以及「run 事件是否要做」的落地取舍）。
>
> **P2 分册**：[`subagent-runtime-p2.md`](subagent-runtime-p2.md)（声明式 spec 的能力上限与 schema、
> 面向插件的委派端口、workflow 插件形态）。**设计待确认。**

---

## 1. 目标与非目标

### 目标

1. **提升任务编排能力**——这是本次演进的唯一目的，不是提升单任务质量。
2. **子代理升级为一等公民的 agent run**：有稳定身份、父子树、独立生命周期、独立上下文/工具/权限。
3. **内核提供机制、插件/脚本提供策略**：内核只管「能不能派生/等待/取消/聚合/限额」，编排逻辑不进制内。
4. **可观测**：能看到每个 run 的运行过程（进行中与事后），并配一套预算 governor 防止跑飞。

### 非目标

- **命令式编排脚本：明确不做**。编排表达止步于声明式 spec（能力上限见第 10 节）。
- **子代理 resume：不做**。归档只服务观测与排障，`/session` 与恢复路径一律不认子代理。
- **后台子代理：不做**。并行采用阻塞等待（第 7 节），父回合等整批子 run 返回后才继续。
- **上下文 fork：永久不做**（沿用既有决策）。每个 run 仍是 fresh context。
- **不做 workflow 引擎**：内核不解析 DAG、不做重试/退避策略、不做依赖调度算法。
- **不把嵌套回合排回 `react` 池**：并行使用独立执行资源（第 7 节）。

---

## 2. 现状与问题

### 2.1 现状

子代理今天是一条「父回合内的工具调用链」：

```
ReActLooper.loop（父回合，占一条 react 线程）
  └─ ToolExecutor.execute(task)
       └─ TaskTool.handle
            └─ SubAgentLauncher.run（准入）
                 └─ ReActLooper.runNested（内联在调用线程上，同步跑完）
                      └─ runTurn → loop（子会话）
```

`task` 是内核以 `owner=core` 注册的工具；子会话由 `SessionManager.createEphemeral` 创建，`SessionKind.EPHEMERAL`
（不落盘、不进会话列表）；委派作用域 `RunScope`（深度 + 扇出）随顶层回合经 `RunScopes` 的 `ThreadLocal` 传递。

### 2.2 三个真实问题

1. **观测缺失**：子代理没有独立身份（只有 `task` 的 `toolCallId`），运行事件不上外壳通道，
   实时呈现只能挤在父回合「运行中的工具」那一个槽位里（`InflightTurn` 只有一个 `runningToolName`）。
   并行之后，多个 run 的进度会互相覆盖。
2. **没有任何 governor**：今天只有 `subAgent.maxDepth`、`maxSpawnsPerTurn` 与 `maxRounds`，
   没有并发上限、没有墙钟上限、没有 token 预算。一次跑飞的子代理可以烧掉上百万 token
   （实际发生过：单次委派 22 轮 / 176 万 token，且用户只能靠 Esc 停下）。
3. **「单例不能并行」是误判，真正的阻塞在别处**：`ReActLooper` 是无状态 `@Singleton`，
   每次 `chat()` 自带 `ReActTurnImpl` + 会话 + 线程，本来就能并发。真正挡住并行的是：
   `runNested` 内联执行、`RunScopes` 是 `ThreadLocal`、子代理被建模成父回合工具批次里的一个调用。

### 2.3 旧禁令为什么存在，以及现在怎么替代

「嵌套回合内联、绝不进 `react` 池」不是保守，而是回避一个真实事故：父回合占着 `react` 线程等待，
若把嵌套回合排回同一个池（上限 8），几条并发父回合就能占满池并互相等死。
**方向上要并行，就必须正面解决它，而不是继续回避**：用独立的 `agent-run` 执行资源，
并规定「等待中的 run 不占运行槽位」（第 7 节）。同理，「动作只投顶层回合」「内核不自己起回合」
这些禁令背后的危险（外壳在途状态被绕过、取消入口缺失）要在新方案里逐条回答，而不是当作不存在。

---

## 3. 目标形态

```
外壳（CLI / TUI / Server）
  │  可靠 lane（回合事件）+ run 事件总线（订阅）+ 面板（插件形态的 PanelContribution）
  ▼
jellyfish-core
  AgentRuntime        # run 原语：spawn / await / cancel / result / events（不解释 spec）
  ├─ RunRegistry      # run 身份 + 父子树 + 生命周期状态机
  ├─ RunScheduler     # 独立 agent-run 执行资源 + 并发/排队 + governor
  └─ ReActLooper      # 执行体（runNested 不再内联，改为被调度执行）
  ▼
编排层（插件/脚本，策略）
  ├─ task 工具（内核，薄委派）
  └─ workflow 插件（声明式 spec 解释器，后续开发）
  ▼
jellyfish-infra
  SessionManager / ToolOutputStore（归档命名空间）/ EventChannel / ExtensionRegistry
```

### 3.1 一等公民 run

- **身份**：每个 run 一个稳定 `runId`。`toolCallId` 只是可选别名（`task` 触发的 run 才有）。
- **父子树**：`parentRunId` + `rootRunId` + `parentSessionId`。取消、预算、面板、归档都按树组织。
- **生命周期**：`PENDING → RUNNING → (WAITING_CHILDREN) → DONE / FAILED / CANCELLED / TRUNCATED / BLOCKED`，
  恰好一条终态。

### 3.2 与现有概念的关系

| 现有 | 变化 |
| --- | --- |
| `SessionKind.EPHEMERAL` | **保留为判定来源**（`parentSessionId` 仍只做追溯）。落盘策略改为「归档」而非「会话」；新增独立的归档命名空间 |
| `TurnRegistry` | 不变（管顶层回合的互斥与取消）。run 的注册表是**另一个**表，不与它合并 |
| `ActionQueue` | 不变（仍只投顶层回合）。本设计**不给子 run 开动作窗口**（维持既有决策） |
| `ShellStreams` 可靠 lane | **不复用**：run 事件走运行时自持的 `RunEventBus`（`core.runtime`）。原计划「复用它打标签」会与 `core.conversation → core → core.runtime` 的依赖方向相撞；见 P1 分册 D-P1-5 |
| `ReActLooper.runNested` | 由「内联执行」改为「由 `RunScheduler` 调度执行」；`runTurn` / `loop` 语义不变 |
| `RunScope` / `RunScopes` | 由 `ThreadLocal` 改为 run 上下文（随 run 传递），否则并行时跨线程不可见 |
| `ToolOutputStore` | **复用**，但为子代理归档开**独立命名空间与独立配额**（第 9 节） |

---

## 4. 内核原语契约

内核只出原语，**不解释 spec、不知道编排形态**。原语是**句柄式异步**的——内核绝不在关键路径上
同步回调插件、也不等插件（与 `ActionQueue`「不产生同步环」同源）。插件一旦能同步阻塞地驱动内核，
就能把内核线程卡住，等于把执行资源的生死交给插件。

```java
/** run 原语（jellyfish-core）。仅为契约示意，最终签名以落地为准。 */
@Singleton
public final class AgentRuntime {

    /** 派生一个 run：准入 → 建会话 → 入调度队列。立即返回句柄，不阻塞。 */
    AgentRunHandle spawn(AgentRunRequest request);

    /** 当前存活 run 的只读快照（面板用；按 root 过滤）。 */
    List<AgentRunSnapshot> runs(String rootRunId);

    /** 取消单个 run。 */
    void cancel(String runId);

    /** 取消一棵子树（含自身）。 */
    void cancelTree(String runId);

    /** 取终态结果；未完成时为空。 */
    Optional<AgentRunResult> result(String runId);

    /** 订阅 run 事件（内核内部与面板用）。 */
    Subscription subscribe(Consumer<AgentRunEvent> listener);
}
```

语义要点：

- **`spawn` 不阻塞**：准入（开关、agent 存在且 `delegatable`、深度/预算/并发）全部在产生副作用之前完成，
  与既有 `SubAgentLauncher` 的准入顺序一致；被拒绝的 run 不建会话、不发事件。
- **等待靠事件或轮询，不靠阻塞内核**：`task` 工具实现内部可以阻塞在它自己的工具线程上等结果
  （这与今天一致，因为它是工具自己的线程）；插件编排走 `subscribe` / `result`。
- **结果聚合由调用方决定**：内核只回逐个 run 的结果，不做「合并摘要」这类策略。
- **`runId` 是第一主键**：事件、归档、取消、面板全部以它为主键；`toolCallId` 只用于把 run 关联回 `task` 的工具结果。
- **不做的事**：不解析 spec、不做依赖调度、不做重试、不做聚合策略、不开动作窗口。

---

## 5. 决策记录（已定）

| # | 决策 | 取值 |
| --- | --- | --- |
| **D1** | 演进目的 | **提升任务编排能力**（不是单任务质量） |
| **D2** | 抽象 | 子代理升级为**一等公民 agent run**；远景纳入 agent 团队（共享任务列表） |
| **D3** | 并行等待方式 | **方案 A**：阻塞等待 + **独立的 `agent-run` 执行资源**（与 `react` 池分离） |
| **D4** | 并发默认值 | 全局同时运行 run 数 = **3**（不含父回合） |
| **D5** | 嵌套 | **可调深度**；默认 **1**（规避 A + 深度 ≥ 2 的死锁），上限 3 |
| **D6** | Governor | 全局并发 + 单回合扇出 + 单 run 墙钟 + 单 run token + 单 run 树 token + 单 run 轮数（见第 8 节） |
| **D7** | 编排表达 | **声明式 spec：做**；**命令式脚本：明确不做** |
| **D8** | 引擎位置 | **放插件**；内核只出原语，不解释 spec |
| **D9** | 观测 | **可观测、不可 resume** |
| **D10** | 传输 | run 事件走**运行时自持的可靠总线** `RunEventBus`（`core.runtime`）；**不挂 `ShellTurnEvent`、不改三外壳的回合契约**（细化见 P1 分册 D-P1-5） |
| **D11** | 呈现 | **独立面板**，以**插件形态**（`PanelContribution`，`owner=core`）提供 |
| **D12** | 归档 | **复用 `ToolOutputStore`**，但使用**独立命名空间与独立配额** |
| **D13** | spec 工具 | 插件**后续开发**；模型届时走插件的提示词贡献获知 schema |
| **D14** | `task` 薄工具 | **保留**（简单委派不该逼模型写 spec） |

---

## 6. 并行与调度

### 6.1 执行资源

- **独立于 `react` 池**：`agent-run` 执行资源只跑 run 的执行体；父回合仍占 `react` 线程等待。
  两者不共用队列，避免「父等子、子排在同池」的自锁死。
- **父回合的等待是阻塞的**（方案 A）：`task` / workflow 工具阻塞在自己的工具线程上直到整批 run 返回。
  代价是「一个 workflow 占一条 `react` 线程直到跑完」，当前 8 条足够。

### 6.2 并发与排队

- 全局同时运行的 run 数受 governor 限制（默认 3）；超出的 run **排队**而不是失败。
- 单回合扇出同样受限（默认 3）；模型一次要求更多时，排队并允许取消等待中的请求。
- 队列必须有界；满时按「拒绝并回报」处理，不静默丢弃。

### 6.3 死锁规则（必须写进实现与文档）

方案 A 下，父 run 在等子 run 时会**占着一条执行线程/槽位**。若深度 ≥ 2 且全局槽位被「正在等孩子的父 run」占满，
孩子永远排不上号——这与旧文档里 `react` 池自锁死是同一类问题，升高了一层。

因此：

1. **v1 默认 `maxDepth = 1`**（叶子子代理），此时不存在「父 run 占槽等子」，死锁不成立。
2. 深度 > 1 时，调度器必须做到「**等待中的 run 不占运行槽位**」：run 转入 `WAITING_CHILDREN` 时让出槽位，
   子 run 全部到达后重新参与调度。
3. 这条规则必须有一条**回归测试**（深度 2 + 并发吃满时不得挂死），否则将来把深度调大就会随机挂死且极难复现。

### 6.4 取消

- `cancel(runId)`：取消单个 run；`cancelTree(runId)`：取消整棵子树。
- **父回合被取消（Esc）⇒ 整棵树被取消**：父的取消令牌必须传播到全部在途 run，
  并给未执行的工具调用补结果（沿用既有 `ToolPairing` 约束，避免悬空 `toolCalls` 让后续请求被厂商 400 拒绝）。
- 取消是幂等的；终态恰好一次。

---

## 7. Governor

配置归属 `jellyfish.json` 的 `subAgent` 段（新增字段，缺省值如下）。非法值一律回退缺省，不阻断启动
（与 `SubAgentSettings` 既有口径一致）。

| 配置项 | 默认 | 最高 | 说明 |
| --- | --- | --- | --- |
| `maxDepth` | **1** | 3 | 见 6.3 |
| `maxConcurrentRuns` | **3** | 可配 | 全局，不含父回合 |
| `maxSpawnsPerTurn` | **3** | 可配 | 单回合扇出，超出排队 |
| `runTimeoutMillis` | **300000**（5 分钟） | 可配 | 到点截断并回报，不留后台残留 |
| `runTokenBudget` | **模型上下文窗口的 25%** | 可配 | 含绝对下限，避免小窗口模型被压到不可用 |
| `treeTokenBudget` | **`runTokenBudget` × 3** | 可配 | 防止「每个孩子不超、合起来爆」 |
| `maxRounds` | **8**（沿用现值） | 可配 | 子 run 的最大轮数 |

- **token 预算按 run 累计用量计**（跨该 run 的全部轮次），不是单次请求的上下文大小。
- 触达任一项预算：run 以 `TRUNCATED` 结束，回报文本说明触达了哪一项以及该调哪个配置键
  （沿用既有「截断提示按语境指向不同配置键」的做法）。
- **无进展检测暂不做**，列为后续项。

---

## 8. 观测与归档

### 8.1 事件（运行时自持总线，落地按 P1 分册）

- run 事件走**运行时自持的可靠总线** `RunEventBus`（`core.runtime`），不挂在 `ShellTurnEvent` 上，
  也不挤进 `ShellStreams`：那是外壳回合的契约，而 agent 运行时的观测面还要继续长（P3 的任务列表、代理间消息）。
  事件类型是独立的 `AgentRunEvent`，携带 run 身份与状态快照。
- 事件集（示意）：`STARTED` / `STEP`（工具调用或轮次推进）/ `FINISHED`（终态 + 轮数 + 用量）。
  **当前只发布 `STARTED` / `FINISHED`**；`STEP` 与输出流待内核 `loop` 进度钩子与可丢通道（P1d）。
- **顺序与不丢**：与可靠 lane 同一口径（同步扇出、单来源有序、订阅者抛错被隔离）。
- **插件不走这条总线**：它们拿不到内核类型；插件若要观测 run 走 `EventChannel` 通知（后续）。
- **审批仍是拉取式**，不进事件 lane（与既有决策一致）。

### 8.2 面板（插件形态）

- 由 `core`（`owner=core`）注册一个 `PanelContribution`，与 `SystemCommands` / `SubAgentTools` 同一形态，
  **不新开内核 UI 通路**。
- 面板内容（**已落地**）：活跃 run 列表——子代理类型、状态、已耗时。
  轮数与 token 的实时值需要内核 `loop` 的进度钩子，列为可选增强（P1 分册 D-P1-3）；
  「选中某个 run 看步骤与输出」也留给后续（那需要 `STEP` / 输出流，见 D-P1-5）。
- **刷新不是每帧**：外壳只在缓存失效时收集，因此 TUI 在**回合进行中每秒补一次失效**——
  run 只可能存在于回合内，没有这条来源面板会在 run 结束后才首次出现（那时它已经空了）。
- 与父回合「运行中的工具」的区域分工：那里仍是父回合的实时区；面板承载**所有并发 run**，因此并行时不会互相覆盖。

### 8.3 归档（复用 store + 独立配额）

- 子 run 结束时，把完整 transcript 序列化为一段结构化 JSON，写入 `ToolOutputStore`：
  复用其目录/命名、原子写与清理机制，但放在**独立命名空间**（例如按 `sessionDir` 之外的 `subagent-runs/` 根，
  或会话目录下的独立子目录），并配**独立的文件数/字节阈值**，避免挤掉普通工具输出。
- 键：`runId`（插件驱动的 run 没有 `toolCallId`）；`task` 触发的 run 额外可用 `toolCallId` 关联。
- **它是可观测窗口，不是合规级归档**：会被清理淘汰。若将来要求永久留痕，再单独建 store。
- **不参与会话恢复**：`/session` 与 `restore()` 不认归档；子代理不可 resume。

---

## 9. 编排层

### 9.1 声明式 spec 的能力上限（现在就划死）

允许：有序步骤、依赖/扇出、并发、聚合（收集/合并/取摘要）、静态条件（如「某步失败则跳过后续」）。

**不做**：任意控制流（循环/递归/跳转）、表达式求值、重试/退避策略引擎、运行期动态生成步骤。
需要这些的场景由模型**重新发一次 spec**，或由插件用代码自行判断。

> 划这条线的理由：声明式 spec 一旦允许条件 + 循环 + 变量，就会在无人察觉时变成一门语言，
> 而维护一门语言（语法、版本兼容、错误语义）的成本远高于它带来的编排收益。

### 9.2 引擎与 schema

- **引擎在插件**：内核不背语言，也不解释 spec。
- **spec 工具后续开发**：由 workflow 插件注册一个工具（名称与 schema 由插件定），
  并通过 `PromptContributionRequest` 把 spec 的结构告诉模型。
- **`task` 薄工具保留**：简单委派继续走它，不必写 spec。
- 插件驱动的 run 同样受 governor 与深度约束（不因「不是 task 触发」而绕过）。

---

## 10. 阶段划分

| 阶段 | 内容 | 依赖 | 可回滚性 |
| --- | --- | --- | --- |
| **P0** | `AgentRuntime` 原语 + `RunRegistry` + `RunScheduler` + governor；`runNested` 由内联改调度；`RunScope` 去 `ThreadLocal` | 无 | 中（核心机制变更，但 `task` 的对外语义不变） |
| **P1** | run 事件（运行时总线）+ 面板（`core` 的 `PanelContribution`）+ 归档（独立命名空间与配额） | P0 | 高（面板与归档可整体摘除） |
| **P2** | 声明式 spec 插件（引擎 + spec 工具 + 提示词贡献） | P0 / P1 | 高（插件卸载即回退到 `task` 薄工具） |
| **P3** | 共享任务列表（agent 团队远景） | P2 | 高 |
| **P3** | 共享任务列表（agent 团队远景：run 间消息 + 任务容器） | P2 | 高（纯新增面） |

每阶段独立可合并、可回滚；合并前必须同步第 12 节的文档清单。

---

## 11. 验收点

**P0**

- 深度 1 + 全局并发吃满时，多父回合并发不阻塞、不挂死。
- 深度 2 + 并发吃满的**死锁回归**用例（带超时兜底，超时即失败）。
- 取消传播：父取消 ⇒ 全部在途 run 取消，且悬空工具调用全部补上结果。
- 用量记账：多个子 run 并发归集到同一父会话时**不丢、不重复**（原子累加）。
- governor 每一项各一条用例（并发/扇出/墙钟/token/树 token/轮数），触达时状态为 `TRUNCATED` 且提示指向正确配置键。
- 既有 `task` 行为回归：单发委派的结果文本、元数据、`ToolMetadata.KEY_SUMMARY` 与改造前一致。

**P1**

- run 事件序列一次性一致（录制回放喂给订阅者）；订阅先于 spawn 的契约用例。
- 3 个 run 并行时，面板能各自独立呈现，互不覆盖。
- 归档写入独立命名空间，且清理普通工具输出时不会连带删除归档；子代理不出现在 `/session`，不可 resume。

**P2**

- spec 解释器只支持第 9.1 节允许的能力；越界 spec 明确拒绝并回报。
- 插件驱动的 run 与 `task` 触发的 run 在 governor / 深度 / 取消 / 观测上行为一致。

---

## 12. 文档更新清单（与代码同 PR）

| 文档 | 改动 |
| --- | --- |
| `AGENTS.md` | 「子代理」速览条目改为「内联」→「独立调度 + governor」；「改动前的必读索引」子代理一行指向本文 |
| `docs/constraints/react-compact.md` | **子代理（嵌套回合）** 整节按本设计重写：删「内联绝不进池」的禁令，改为「独立执行资源 + 等待不占槽」与 governor；补 run 身份与并行语义 |
| `docs/architecture.md` | 「子代理」条目与「已知边界与后续项」：删「并行/链式/工作流编排明确不做」，改为「声明式 spec 支持、命令式脚本不做」；补 agent run 分层图 |
| `docs/constraints/session-config.md` | `SessionKind.EPHEMERAL` 的落盘语义从「不落盘」改为「归档到独立命名空间、不可 resume」；补 governor 配置字段 |
| `docs/constraints/extensions.md` | 明确内核原语是句柄式异步、不在关键路径同步回调插件；run 事件的呈现口径（运行时总线 + 外壳订阅） |
| `docs/constraints/shells.md` | 面板以 `PanelContribution` 提供；run 事件对三外壳的呈现口径 |
| `docs/configuration.md` | 新增 `subAgent` 的 governor 字段与缺省值表 |
| `README.md` | 如子代理的用户可见行为（并行、面板、配置）变化，同步 FAQ 与命令速查 |

---

## 13. 待定与开放问题

1. **原语的确切签名**：`AgentRunRequest` 要带哪些字段（agent、prompt、模型覆盖、预算覆盖、父链接）；
   订阅是回调还是轮询为主。落地时定。
2. **归档命名空间的具体位置**：独立根目录 vs 会话目录下的独立子目录；独立配额的默认阈值取多少。
3. **面板的版式与交互**：TUI 上占哪块区域、如何与现有「运行中的工具」区域共存、如何选中查看某个 run。
4. **spec 的结构**：步骤/依赖/并发/聚合/静态条件的具体字段；由 workflow 插件在 P2 定义。
5. **P3 的任务列表归属**：任务是内核容器还是插件容器；run 间消息是否需要（以及如何收窄）权限面。
6. **无进展检测**：是否引入、判据用什么（连续 N 轮零新增信息）。
