# 设计：子代理运行时 P1（观测与归档）

> **状态：设计已定，未开工。** 本文是 [`subagent-runtime.md`](subagent-runtime.md)（P0–P3 总纲）的 P1 分册，
> 与 `subagent-runtime-p0.md`（P0 分册）、`plugin-shell-contributions.md`（P3 分册）并列。
> P0（run 原语 / 并行调度 / governor / 取消）已落地；本文只谈观测与归档。

---

## 1. 范围

### 交付

1. **运行面板**：TUI 上看到「当前这个会话有哪些子代理在跑、各在什么状态、跑了多久」。
2. **归档**：run 终结时把它的完整 transcript 落到磁盘，供事后排障；**不进会话列表、不可 resume**。

### 不在 P1

- **输出流事件（`OUTPUT`）**：量大且需要可丢语义，待 P2 并发真的出现时再做（见 D-P1-5）。
- **步进事件（`STEP`）的喂数据**：词汇本步定下，但「每轮 / 每次工具调用回写一条」需要 `loop` 钩子
  （与 D-P1-3 的实时进度是同一根管子），与面板的实时轮数/token 一起做。
- **检索/回看归档的界面入口**：首版只保证「文件在磁盘上、路径可查」，不做 `/subagent` 之类命令。
- 共享任务列表（P3）。

---

## 2. 现状事实（决定了本分册的取舍）

| 事实 | 出处 | 影响 |
| --- | --- | --- |
| **面板是拉取式的**：`UiContributions.collect(sessionId)` 每帧按会话问一次 `PanelContributionRequest` 处理器 | `infra/ui/UiContributions` | 面板只需读「当前活跃 run」，**不需要事件推送** |
| 插件面板默认开启（逃生门是 `jellyfish.tui.pluginPanels=false`） | `TuiApp.pluginPanelsEnabled` | 内核以 `owner=core` 贡献的面板会自动显示 |
| 面板是**独占区域**、多贡献者由用户 `/ui` 切换 | `PanelContribution` 注释 | 没有活跃 run 时**必须返回空贡献**，不抢地盘 |
| `RunRegistry` 只存**身份/状态/起止时间**；轮数与 token 只在**终态**写入 | `RunRegistry` | 活跃 run 的面板拿不到实时轮数/token，除非补一条进度回写 |
| `SubAgentLauncher.delegate` 在 `await` 后**立即 `remove`** 注册项 | `SubAgentLauncher` | 活跃期间注册项存在（面板可见）；终态后消失（归档承接事后可观测） |
| `ToolOutputStore` 按**会话目录**做文件数/字节清理，配额来自 `ToolOutputSettings` | `infra/tooloutput/ToolOutputStore` | 归档若与工具输出共享目录，会被互相挤掉；需要独立命名空间 + 独立配额 |
| `AgentRuntime.activeRuns()` 已返回全部非终态 run 快照（含 `parentSessionId`） | `core/runtime/AgentRuntime` | 面板与归档都有现成的读入口 |

---

## 3. 决策

### D-P1-1 面板走拉取式，不新开 lane

内核以 `owner=core` 注册一个 `PanelContributionRequest` 处理器，读
`AgentRuntime.activeRuns()` → 按 `parentSessionId == request.getSessionId()` 过滤 → 渲染成行，
无活跃 run 时返回 `PanelContribution.empty()`。

- **为什么不用事件**：面板本来就是拉取式（每次收集重画），推事件只会引入「事件与注册表两份真源」。

**落地时的两个修正**（原写「每帧」，与外壳的缓存机制冲突）：

1. **面板不是每帧收集，而是「缓存失效时」收集**（见 `UiContributions` / `UiCache`）——
   外壳每 40ms 渲染一帧，若每帧问插件，空闲时也在反复调用插件处理器。
2. 因此需要一条**持续刷新**的来源：`TuiApp` 在**回合进行中每秒补一次失效**
   （`LIVE_REFRESH_MILLIS`）。之所以安全，是因为子代理 run 只可能存在于「回合进行中」，
   而一秒一次远低于面板处理器「快、只读」的预算；空闲时一次都不多问。
   没有这条，面板会在 run 结束后才第一次出现——那时它已经空了。

### D-P1-2 面板归属

与 `task` 工具、可委派类型清单同一个注册器（`core/subagent/SubAgentTools`，`owner=core`），
不新建注册器、不新建 UI 通路。

### D-P1-3 面板首版内容：只做「状态 + 耗时」

首版每行：`子代理类型 · 状态 · 已运行 Xs`（`startedAt` 推耗时），**已实现**。

**轮数与 token 的实时值需要一条新钩子**：`RunRegistry` 目前只在终态写入它们。要显示实时进度，
需要 `ReActLooper.loop` 每轮把「轮次 + 会话累计用量」回写到注册表。这条钩子有代价
（`ReActLooper → AgentRuntime` 的新依赖，或 `RunContext` 持一个进度回调），且不改变任何行为，
因此**仍列为首版之后的可选增强**（与 `STEP` 事件共用同一条钩子，见 D-P1-5）。

> 现状已经比 P0 之前好得多：**卡住不再只能看「运行中」**——面板能看到耗时在涨、状态在变，
> 加上 P0 的墙钟上限会替用户收尾。

### D-P1-4 归档复用 `ToolOutputStore` + 独立命名空间与独立配额（**已实现**）

- **入口**：`ToolOutputStore.storeIn(命名空间, 键, 内容, 是否结构化, 文件数上限, 字节数上限)`
  ——把「根目录下一个子目录 + 一套独立配额」做成通用能力，工具输出与归档共用同一套
  写盘规则（原子改名、路径清洗、按上限清理）而互不干扰。
- **键**：`runId`（不是 `toolCallId`——插件驱动的 run 没有 `toolCallId`）。
- **内容**：`SubAgent` 收尾时写一份 JSON：run 身份（runId / parentRunId / rootRunId / agentId /
  两级会话 id / toolCallId）、终态、轮数、用量，以及**子会话的完整快照**
  （`SessionSnapshots.capture` → 含完整 transcript 与用量明细）。
- **配额进配置**：`subAgent.archiveKeepFiles` / `archiveMaxBytes`。为避免搅动既有调用点，
  `SubAgentSettings` 保留 8 参构造器（非 creator）并新增 10 参 `@JsonCreator`。
- **清理影响**：归档与工具输出分居不同目录，一侧的清理不会删另一侧的文件。
- **定位**：沿用 P0 分册的口径——它是「可观测窗口」，不是合规级归档。

### D-P1-5 run 事件：**做**，但分期，且不挂到 `ShellTurnEvent` 上

**为什么做**：它是后续计划的地基，不是 P1 的装饰——

- **P2（声明式 spec + 真并发）**：并发一出现，「一个父回合只有一个运行中工具槽位」就撑不住了；
  编排插件要观测 N 个 run 的推进、外壳要展示 N 条，事件流是唯一可扩展的形态。
- **P3（agent 团队 / 共享任务列表 / 代理间消息）**：那本质上是「一个 agent 运行时的可观测 + 可通信面」，
  run 事件是它的最小内核。
- **`-server` 是产品面**：SSE 客户端在另一个进程，拉不到进程内的注册表；没有事件，
  它永远只能看到 run 结束后的那一行。

**怎么切（关键取舍）**：run 事件是两档，成本差一个数量级，**分开做**：

| 档 | 事件 | 传输 | 分期 |
| --- | --- | --- | --- |
| **生命周期** | `STARTED` / `FINISHED`（带 run 身份、终态、轮数、用量） | **可靠**（不丢、有序） | **本步做** |
| **步进** | `STEP`（每轮 / 每次工具调用） | **可靠**（量小、是「推进」的骨干） | 本步定义词汇，喂数据待 `loop` 钩子（D-P1-3 同一条钩子） |
| **输出流** | `OUTPUT`（每段工具输出 / 正文增量） | **可丢**（量大，不得拖住 run 线程） | 待 P2 并发真的出现时再做 |

**为什么不挂到 `ShellTurnEvent`**：那是「外壳回合」的契约，已经跨三外壳；
agent 运行时的观测面（P3 还要长任务列表、代理间消息）不应挤进同一个判别式，
否则每加一个 agent 概念就要三外壳各重编一次。因此定义**独立的** `AgentRunEvent`。

**为什么不建到 `ShellStreams` 里**：run 生命周期在 `core.runtime`，而
`core.conversation → core → core.runtime`；把发布点接到 `ShellStreams` 要么做依赖倒置 + Dagger 绑定，
要么从 `core.subagent` 发。更干净的落法是**运行时自持一条可靠总线**（`RunEventBus`，`core.runtime`）：
内核发布、外壳/消费方订阅，**无循环依赖、无额外绑定**，且与 P3 的「agent 运行时事件」同源。
它的可靠语义与 `ShellStreams` 可靠 lane 同口径（同步扇出、不排队、订阅者抛错被隔离）。

**插件观测走 `EventChannel`，不走这条总线**：插件拿不到内核类型（api 边界），
它们要观测 run 起止应另发一条 api 侧的通知事件（异步、可丢）——那是另一条边界，不混在这里。

**本步的消费方**：已接 `-server` 的 SSE（把 `STARTED` / `FINISHED` 转成 `run_started` / `run_finished`）。
TUI 面板仍是拉取式（D-P1-1），**不订阅总线**——它要的是「此刻有几个 run」，而那是快照语义。

**落地时的两个细节**：

- **运行时在广播终态前先把状态置为运行中**（`markRunning`）：否则面板与 SSE 都会把「已起跑」的 run
  一直显示成「排队中」。
- **`FINISHED` 必须先于 `handle.complete` 广播**：反过来会让「`await` 返回后事件还没到」成为一条
  难以复现的竞态（测试当场抓住了它）。

**`STEP` / `OUTPUT` 仍未接**：它们要么需要内核 `loop` 的进度钩子（与 D-P1-3 同一条），
要么需要一条可丢通道。列为 P1d，待 P2 并发真的出现时再做。

### D-P1-6 归档触发点在 `SubAgentLauncher.delegate`

`await` 返回后、`runtime.remove(runId)` 之前写归档——此刻子会话还在，transcript 完整。
写失败只记 WARN（与 `ToolOutputStore` 既有失败语义一致：磁盘问题不该把一次委派升级成失败）。

---

## 4. 阶段

| 步 | 内容 | 依赖 | 可回滚 | 状态 |
| --- | --- | --- | --- | --- |
| **P1a** | 运行面板：`SubAgentPanel` + 在 `SubAgentTools` 注册；读 `activeRuns()`；TUI 回合中每秒补失效 | P0 | 高（摘掉贡献即回退） | **已完成** |
| **P1b** | 归档：`ToolOutputStore.storeIn` 命名空间 + 独立配额；`SubAgentLauncher` 写 transcript | P0 | 高（不写即可回退） | **已完成** |
| **P1c** | run 生命周期事件：`AgentRunEvent` + `RunEventBus`（`core.runtime`）；运行时发布 `STARTED`/`FINISHED`；`-server` SSE 消费并把它们转成 `run_started`/`run_finished` | P0 | 中（新增运行时总线 + Server 订阅） | **已完成** |
| **P1d**（条件） | `STEP` / `OUTPUT` 事件：需要 `loop` 进度钩子与可丢传输；待 P2 并发真的出现时做 | P1c | 中 | 待定 |

---

## 5. 验收

**P1a**

- 同一会话有 1 个活跃 run 时，面板出现该 run；3 个并行时三行都在、各自独立。
- 没有活跃 run 时返回空贡献（不占区域）。
- 终态后该 run 从面板消失（注册项被 `remove`）。
- 面板处理器**只读且快**：不碰文件系统、不启动进程（与其它面板处理器同口径）。

**P1b**

- run 终结后归档文件存在，内含完整 transcript（消息条数与会话一致）。
- 清理普通工具输出时**不**连带删除归档；反之亦然（独立配额）。
- 归档不出现在 `/session`，无法 resume。
- 写失败不影响委派结果（只记 WARN）。

**P1c**

- 一个 run 从开始到终结，`RunEventBus` 的订阅者恰好收到 `STARTED` 与 `FINISHED` 各一条，runId 一致。
- `FINISHED` 携带终态 / 轮数 / 用量；被拒（池满）的 run 只发 `FINISHED`（没有 `STARTED`）也能让消费方对上。
- 单个订阅者抛错被隔离，不影响其它订阅者与 run 本身。
- `-server` 客户端在 SSE 里能看到 `run_started` / `run_finished`；断连不影响 run。

---

## 6. 待定

- **O-P1-1**：归档命名空间用「独立根目录」还是「会话目录下的独立子目录」；
  独立配额的缺省阈值取多少（对齐 `ToolOutputSettings` 的 200 文件 / 50MB 还是另设）。
- **O-P1-2**：是否给归档一个用户可见的查看入口（`/subagent <runId>` 或轨迹行可展开）；首版不做。
- **O-P1-3**：D-P1-3 的实时进度回写是否要做（决定面板显示「状态+耗时」还是「状态+轮数+token」）。
- **O-P1-4**：D-P1-5（run 实时事件）是否要在 P1 就做。
