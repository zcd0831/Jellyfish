# 设计：子代理运行时 P0（agent run 原语 + 并行调度 + governor）

> **状态：设计已定，未开工。** 本文是 [`subagent-runtime.md`](subagent-runtime.md)（P0–P3 总纲）的 P0 分册，
> 与 `plugin-shell-contributions.md` 是 P3 分册同理。
> 本文只定 P0 的机制与迁移路径；P1（观测/归档）、P2（声明式 spec）、P3（共享任务列表）见总纲。
> 施工顺序见第 11 节，每步独立可合并、可回滚。

---

## 1. P0 范围

### 交付

1. **run 身份与生命周期**：`RunRegistry`（runId、父子树、状态机、终态恰好一次）。
2. **并行调度**：`RunScheduler`（独立于 `react` 池的执行资源 + governor 并发许可 + 有界排队）。
3. **原语门面**：`AgentRuntime`（`spawn` / `await` / `cancel` / `result` / 订阅），句柄式异步。
4. **编排作用域去 `ThreadLocal` 的共享问题**：`RunScope` → `RunContext` + `RunTree`（见第 6 节）。
5. **执行体改造**：`ReActLooper.runNested` 由「内联在调用线程」改为「由 `RunScheduler` 调度执行」；
   `SubAgentLauncher` 退化为 `task` 与运行时之间的适配器。
6. **Governor 落地**：并发、扇出、深度、墙钟、单 run token、单 run 树 token、轮数（见第 8 节）。

### 明确不在 P0

- 观测面板与归档（P1）；run 事件只做到「内核内部可用 + 可靠 lane 打标」，不做 UI。
- 声明式 spec 与 workflow 插件（P2）。
- **给插件的编排 API 暴露**：P0 先把原语建起来并让 `task` 走它；插件面向的收窄与暴露在 P2。
- **「模型一次并发发起多个 run」的触发方式**：见第 13 节开放问题 O1（P0 只保证机制可用，不改变
  `loop` 现有的「按序执行本批工具」语义）。
- 后台化 / 事件驱动挂起（总纲的方案 B）。
- 子代理动作窗口（维持既有决策：动作只投顶层回合）。

---

## 2. 现状基线（P0 要改的精确位置）

```
ReActLooper.chat                      // 顶层：提交 react 池（8 线程），异步返回句柄
  └─ execute                          // runScopes.open(maxDepth, maxSpawnsPerTurn) … finally close
       └─ runTurn → loop              // 每轮模型调用；有工具则按序执行本批工具
            └─ executeTool → ToolExecutor.execute(session, turn, …)
                 └─ TaskTool.handle   // 阻塞在 launcher.run(...)（因为 runNested 内联）
                      └─ SubAgentLauncher.run     // 准入：开关/任务非空/scope存在/深度预算/agent/模型
                           └─ delegate            // createEphemeral → runNested → toOutcome → finally 归集用量 + 关会话
                                └─ ReActLooper.runNested   // 读 runScopes.current()；inline 回合；enter/leave
                                     └─ runTurn → loop     // 子回合（同一条线程）
```

受限点：`runNested` 与父回合同线程 ⇒ 天然串行；`RunScope` 挂在 `ThreadLocal` 上 ⇒ 换线程读不到；
`runScopes.open/close` 只在 `execute` 里 ⇒ 树级计数只在单线程内可见。

---

## 3. 目标结构与职责

```
void  AgentRuntime          # 原语门面：准入 → 建会话 → spawn → 返回句柄；result/cancel/订阅
 ├─ RunRegistry             # 身份与树：runId / parentRunId / rootRunId / 状态机 / 活跃查询
 ├─ RunScheduler            # 执行资源：agent-run 线程 + 并发许可 + 有界队列 + 超时看门狗
 └─ Governor（RunTree 持有）# 树级与 run 级预算的共享计数（原子）
ReActLooper                 # 执行体：runNested 改由调度器在 agent-run 线程上调用
SubAgentLauncher            # 适配器：SubAgentCall ↔ AgentRunRequest / AgentRunResult ↔ SubAgentOutcome
```

- **依赖方向**：`core.subagent → core.runtime`（与今天 `core.subagent → core` 同向）。
  运行时类型放 `core` 的新包 `core/runtime`，避免 `api` 认识纯内核概念。
- **`RunRegistry` 与 `TurnRegistry` 不合并**：前者管 run（可多、可嵌套、有树），后者管顶层回合的
  互斥与取消（一会话一槽位）。两者语义正交，合并会让「谁该被互斥」变成一个需要判别的问题。
- **`ActionQueue` 不动**：run 不开动作窗口。

---

## 4. 身份与生命周期（`RunRegistry`）

### 4.1 数据

| 字段 | 说明 |
| --- | --- |
| `runId` | 稳定主键（`UUID`），事件 / 取消 / 归档 / 面板都用它 |
| `parentRunId` | 父 run；`task` 由顶层回合直接发起时为 `null` |
| `rootRunId` | 树根（顶层回合的委派根）；等于自身时为根 |
| `parentSessionId` | 父会话（追溯与归档归属） |
| `agentId` | 子代理类型 |
| `sessionId` | 该 run 的会话（`EPHEMERAL`） |
| `toolCallId` | 可选别名：`task` 触发的 run 才有（用于把结果关联回工具结果） |
| `status` | 见 4.2 |
| `rounds` / `usage` | 进度与用量 |
| `startedAt` / `finishedAt` | 墙钟观测与 governor |

### 4.2 状态机

```
PENDING ──► RUNNING ──► WAITING_CHILDREN ──► RUNNING ──► DONE
   │           │               │                          FAILED
   │           │               │                          CANCELLED
   │           │               │                          TRUNCATED
   └───────────┴───────────────┴──────────────────────────┘（任一转终态，恰好一次）
```

- 终态集合：`DONE` / `FAILED` / `CANCELLED` / `TRUNCATED`（`TRUNCATED` 覆盖轮数 / 墙钟 / token 触达）。
- 「恰好一条终态」由 `AtomicReference` 的 CAS 保证（与 `ReActLooper` 保证的四条终结路径一致）。
- `WAITING_CHILDREN` 只在方案 A + 深度 > 1 时出现（该 run 正阻塞等它的孩子），也是第 5.3 节让出许可的时点。

### 4.3 关键方法（内部）

```java
RunState register(AgentRunRequest request, Session child, RunTree tree, RunContext parent);
void     transition(String runId, RunStatus to);          // CAS，重复置终态幂等
boolean  cancelTree(String runId);                         // 取消自身与全部后代
List<RunState> activeOf(String rootRunId);                 // P1 面板用；P0 先建
void     reap(String runId);                               // 终态后从活跃表移出（保留结果到取走）
```

---

## 5. 调度（`RunScheduler`）

### 5.1 线程资源与并发许可是两件事（P0 最关键的取舍）

方案 A 下，父 run 等孩子时会**阻塞一条线程**。若把「线程数」直接当成「并发上限」，深度 > 1 时
就会自锁死（父占着线程等子，子的线程拿不到）。因此 P0 明确拆成两个独立概念：

- **线程资源**：`agent-run` 池，提供执行线程。用**有上界的 cached 池**（线程按需创建、空闲回收），
  而不是「固定 = 并发上限」。等待中的父 run 占一条线程，但它不占并发许可。
- **并发许可**：governor 的 `AtomicInteger` / `Semaphore`（`maxConcurrentRuns`，默认 3），
  只门控「**正在做模型调用 / 工具执行**」的 run。**转入 `WAITING_CHILDREN` 的 run 必须让出许可**，
  孩子全部到齐后重新申请。

这条拆分同时解决了两件事：既保住了「全局同时最多 3 个 run 在真干活」，又不会让等待把池占死。

### 5.2 调度流程

```
spawn(request)
  → 准入（在登记任何状态之前，与今天 SubAgentLauncher 的顺序一致）
  → createEphemeral 子会话
  → RunRegistry.register（PENDING）
  → 提交到 agent-run 池 → 线程内：acquirePermit → RUNNING → runNested → 终态 → releasePermit
```

- **排队**：许可拿不到就停在 `PENDING` 等许可（池线程可以先不占，或占一个「等待许可」的轻量令牌——
  见 5.4）。队列有界；满时 `spawn` 直接拒绝并回报（不静默丢弃）。
- **准入失败**：不建会话、不登记、不发事件（沿用今天的「准入全部排在副作用之前」）。

### 5.3 死锁规则

1. **v1 默认 `maxDepth = 1`**：agent-run 线程上的 run 不会再 spawn，因此不存在 5.1 的等待占线程问题。
2. **深度 > 1**：`AgentRuntime.awaitAll(handles)` 的实现必须「**先让出当前 run 的许可、再阻塞等待、
   全部到齐后重新申请**」。这是 P0 必须实现的一条，否则深度 > 1 会随机挂死。
3. **必须有回归测试**：`maxDepth = 2` + `maxConcurrentRuns` 吃满 + 两层同时 fan-out，带 `@Timeout`
   兜底（沿用仓库既有「嵌套用例带超时」的做法）。

### 5.4 超时看门狗

- run 进入 `RUNNING` 时登记一个 `runTimeoutMillis`（默认 5 分钟）的定时任务；
- 到点：取消该 run 的 `ReActTurn`（掐断进行中的 LLM 流）、子树一并取消（若它在等孩子）、
  终态记 `TRUNCATED` 并标注命中项为「墙钟」；
- `loop` 每轮开始再自查一次 deadline 作为兜底（防止看门狗被阻塞）。
- 看门狗资源与 `agent-run` 池分离（一个单线程 `ScheduledExecutorService` 即可），避免超时处理本身被池饿死。

### 5.5 取消

- `cancel(runId)` = 取消该 run **及其全部后代**（一个没有父收集者的子 run 没有意义，留着就是泄漏）。
  总纲里 `cancel` / `cancelTree` 两个入口在 P0 收敛为同一个子树语义；若将来需要「只取消自己不取消子」，
  等有真实消费者再加。
- **父回合并取消 ⇒ 整棵树取消**：`spawn` 时把父的取消令牌接到新 run 的 turn 上（复用
  `ReActTurnImpl.inline(parentCancellation)` 的既有机制），并在 `RunRegistry` 里向上传播。
- 取消幂等；未执行的工具调用补结果（沿用 `ToolPairing`，避免悬空 `toolCalls`）。

---

## 6. 作用域迁移：`RunScope` → `RunContext` + `RunTree`

### 6.1 为什么 `ThreadLocal` 还能留，但必须换承载物

现状 `RunScope` 直接放进 `ThreadLocal`，字段含 `depth` / `spawnCount`。换线程读不到，且计数不是共享的。
P0 的做法是**保留 `ThreadLocal` 作为「当前 run 上下文」的载体**（这样不必把上下文穿过整条工具执行链，
`ToolCallRequest` 也不必认识内核类型），但把承载物换成：

```java
/** 一棵 run 树共享的账本（跨线程按引用共享，计数原子）。 */
final class RunTree {
    private final String rootRunId;
    private final AtomicInteger spawnCount;
    private final AtomicLong treeTokens;
    private final GovernorLimits limits;   // maxDepth / maxSpawnsPerTurn / 两个 token 预算
    boolean tryAcquireSpawn(int depth);    // CAS：depth < maxDepth && spawnCount < max，成功则自增
}

/** 单个 run 的上下文（挂在线程上，但 tree 字段是共享引用）。 */
final class RunContext {
    private final RunTree tree;
    private final String runId;
    private final String parentRunId;
    private final int depth;
    private final long deadlineEpochMillis;
    private final Object permit;           // 该 run 持有的并发许可；顶层回合为 null
    private final AtomicLong runTokens;
}
```

`RunScopes` 相应变为 `RunContextHolder`：

| 现 | 新 |
| --- | --- |
| `open(maxDepth, maxSpawnsPerTurn)` | `openRoot(RunTree tree)`（顶层 `execute` 调用，携带整棵树的 limits） |
| `current()` | `current()`（不变；顶层返回根上下文，agent-run 线程返回该 run 的上下文） |
| `close()` | `clear()`（不变） |
| — | `setForRun(RunContext)`（调度器在 agent-run 线程上执行 run 之前设置，`finally` 里 `clear()`） |

### 6.2 迁移要点

- **`spawn` 时在父线程捕获父上下文**（`current()`），调度器在子线程用「同一个 `RunTree` 引用 + `depth+1` + 新 `runId`」构造子上下文并设置。这样跨线程可见性由**引用共享**保证，而不是靠继承 ThreadLocal。
- **`tryAcquireSpawn` 取代 `canDelegate()` + `recordSpawn()` 的两步**：并发 spawn 下，先判后增会超发，必须 CAS 成一个操作。
- **`depth` 存在 `RunContext`**（每 run 一个），**`spawnCount` / `treeTokens` 存在 `RunTree`**（树共享）。
- 顶层回合的 `RunContext` 也放进同一个 `ThreadLocal`，因此 `SubAgentLauncher` 在「非回合内」判断的逻辑不变（`current() == null` 即拒绝）。

---

## 7. 执行体改造

### 7.1 `runNested`

签名与语义变化：

- **调用方**从 `SubAgentLauncher.delegate`（父工具线程）改为 `RunScheduler`（agent-run 线程）；
- **不再读 `runScopes.current()` 决定可行性**（准入已在 `spawn` 完成），而是接收调度器传入的 `RunContext`；
- 仍复用 `runTurn` / `loop`（语义不变），仍用 `ReActTurnImpl` 承载取消，但由调度器创建并登记到 `RunRegistry`。

> 兼容考虑：`runNested` 一度承担「进入嵌套回合」的语义（`scope.enter()/leave()`）。P0 之后这段
> 由 `spawn` 的准入 + 调度器的上下文设置承担，`runNested` 不再需要自己 enter/leave。

### 7.2 `task` 的等待

- `TaskTool.handle` 仍阻塞在自己的工具线程上（方案 A），但阻塞对象从「同线程跑完」变成
  `AgentRunHandle.await()`。
- `await()` 必须可被父取消唤醒：`spawn` 已把父令牌接上，父取消时 run 立刻进终态并 `countDown`。
- 对模型与外壳的**可观察行为不变**：`task` 仍是「调用 → 阻塞 → 一条结果」，`SubAgentOutcome` 渲染
  与 `ToolMetadata.KEY_SUMMARY` 保持逐字一致（P0 的回归红线）。

### 7.3 适配器

`SubAgentLauncher` 保留，做两件事：`SubAgentCall` → `AgentRunRequest`、`AgentRunResult` → `SubAgentOutcome`；
`forwardUsage` / `closeQuietly` 的语义移入 `AgentRuntime`（run 终态时归集用量、关会话）。
这样 `TaskTool`、`SubAgentOutcome`、现有测试都不必改。

---

## 8. Governor 落地

### 8.1 配置（`jellyfish.json` 的 `subAgent` 段新增）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `maxDepth` | 1 | 见 5.3 |
| `maxConcurrentRuns` | 3 | 全局并发许可 |
| `maxSpawnsPerTurn` | 3 | 树级扇出（`RunTree.tryAcquireSpawn`） |
| `runTimeoutMillis` | 300000 | 见 5.4 |
| `runTokenBudget` | 模型上下文窗口的 25%（带绝对下限） | 单 run 累计 |
| `treeTokenBudget` | `runTokenBudget` × 3 | 树累计 |
| `maxRounds` | 8 | 沿用现值 |

非法值回退缺省、不阻断启动（沿用 `SubAgentSettings` 口径）。

**落地状态**：`maxConcurrentRuns` 在 S3b 落地；`maxDepth` / `maxSpawnsPerTurn` 缺省值调整、`runTimeoutMillis`
与单 run / 树 token 预算在 **S4** 落地（`SubAgentSettings` 新增三个字段；`RunTree.tryAcquireSpawn` 取代
`canDelegate + recordSpawn` 两步；`ReActLooper.loop` 每轮检查预算；`RunScheduler` 带墙钟看门狗）。
**一处与设计值的偏离**：`runTokenBudget` / `treeTokenBudget` 采用**绝对值**（缺省 500k / 1.5M）而不是
「模型上下文窗口的 25%」——run 的预算是累计消耗，与单次上下文不是同一个量，用比例会得到一个随模型漂移的数。

### 8.2 检查点

| governor | 检查点 |
| --- | --- |
| 并发 | `spawn` → 申请许可（不足则排队 / 队列满则拒绝） |
| 扇出 / 深度 | `spawn` → `RunTree.tryAcquireSpawn(depth)` |
| 轮数 | `loop` 的 `for (round …)` 上界（沿用传入的 `maxRounds`） |
| 墙钟 | 5.4 看门狗 + `loop` 每轮自查 |
| 单 run token | `loop` 每次模型响应后：`RunContext.runTokens` 累计并比对 |
| 树 token | 同一次检查里比对 `RunTree.treeTokens` |

### 8.3 触达语义

- 终态 `TRUNCATED`，回报文本说明**触达了哪一项**，并指向该配置键（沿用「截断提示按语境指向不同配置键」）。
- `TRUNCATED` 仍附子代理最后一段正文（沿用总纲/既有行为）。
- token 触达的判定用**累计用量**（该 run 全部轮次之和），不是单次请求的上下文大小。

---

## 9. 用量记账（并发正确性）

- 今天的 `SubAgentLauncher.forwardUsage` 在子回合结束时 `sessionManager.recordUsage(parentId, childUsage)`。
  并发多个子 run 后，这会变成对同一父会话的并发累加。
- **现状是安全的**：`Session.recordUsage` 是 `synchronized`，`SessionUsage` 不可变（`plus` 返回新实例），
  因此并发累加不会丢更新。`SessionManager.recordUsage` 里的 `persist` 由会话级 `persistLock` 串行化。
- **P0 要补的**：`RunTree.treeTokens` 的累加必须在同一处做（`loop` 的用量检查点），
  否则「树级预算」与「会话实际用量」会出现两份不一致的账。两者的关系：`treeTokens` 是
  各 run 增量的和，`parentSession.usage` 是归集后的总数；**以 `RunTree.treeTokens` 为预算判据**，
  `parentSession.usage` 仍是用户可见的账。
- 并发归集会产生多次冗余落盘（每个 run 结束一次）。P0 接受；若成为热点，P1 可加合并/去抖。

---

## 10. 与既有边界的关系（明确不动）

| 体系 | P0 的态度 |
| --- | --- |
| `TurnRegistry` | 不动。run 用 `RunRegistry`，两条表不合并 |
| `ActionQueue` | 不动。run 不开窗口；插件动作仍只投顶层回合 |
| `ShellStreams` 可靠 lane | 不动（P0 不往它发 run 事件；P1 再加标签与事件） |
| `SessionKind.EPHEMERAL` | 语义暂不动（仍不落盘、不进列表）；归档是 P1 |
| `react` 池（8） | 不动。agent-run 池是新增的独立资源 |
| `ToolCatalog` 冻结 / `ToolFilter` | 不动。子 run 的工具清单仍按 agent 收窄 |
| `PromptAssembler` | 不动。子 run 仍组装自己的 system prompt（agent 提示 + 贡献块） |
| `ReActLooper.runTurn` / `loop` | 语义不动，只在 `loop` 增加 governor 检查点 |

---

## 11. 迁移步骤与回滚

| 步 | 内容 | 状态 | 可回滚 |
| --- | --- | --- | --- |
| S1 | 从 `RunScope` 里抽出 `RunTree`（树共享账本），`RunScope` 改为持有它 + 自己的 `depth`。**行为逐字不变** | 已落地 | 高 |
| S3a | `RunScope → RunContext` / `RunScopes → RunContextHolder` 改名，并迁到 `core.runtime`。**行为逐字不变** | 已落地 | 高 |
| S2 | 引入 `RunRegistry` + `AgentRunStatus` / `AgentRunRequest` / `AgentRunSnapshot` + `AgentRuntime` 门面；`SubAgentLauncher` 在 `delegate` 里登记与终结 run。**执行仍内联** | 已落地（`AgentRunResult`/`AgentRunHandle` 按计划推到 S3） | 高 |
| S3b | 引入 `RunScheduler`（agent-run 池 + 许可）与 `AgentRunResult` / `AgentRunHandle` / `AgentRunBody`；`runNested` 改由调度器在 agent-run 线程上调用；`task` 的等待改 `runtime.await()`；等待时让出并发许可（**从 S5 提前**，否则默认深度 2 会真死锁） | 已落地 | 中 |
| S4 | 落地剩余 governor 检查点（墙钟看门狗 / 单 run token / 树 token）与 `tryAcquireSpawn` 并发语义；把 `maxDepth` / `maxSpawnsPerTurn` 缺省值改成第 8.1 节的值 | 已落地 | 中 |
| S5 | 并发取消传播强化（`cancelTree`）、跨层取消与用法记账的边界对齐 | 已落地 | 中 |

**关于 S3b 与计划的差异**（已实现，在此备案）：

1. **超时看门狗延到 S4**：它属于「governor 检查点」那一组，与墙钟/token 预算一起落地更内聚；
   因此 `SubAgentSettings` 本次只新增了 `maxConcurrentRuns`，`runTimeoutMillis` 留给 S4。
2. **`runNested` 保留 `enter()/leave()`**：调度器在执行线程上装载的上下文深度等于**父路径当前深度**，
   由 `runNested` 自己进入 / 退出子层。这样「深度只在本线程有意义」这条语义不变，
   也保住了直接调用 `runNested` 的既有测试。
3. **`AgentRunRequest` 去掉了 `parentRunId`**：父子关系由 `AgentRuntime.spawn` 从当前上下文推出后
   传给登记表，不由调用方按字面填（避免两处各说一遍而漂移）。
4. **`cancel` / `cancelTree` 收敛为「子树语义」**：`RunRegistry.cancelTree(runId)` 取消一个 run 及其全部后代
   （S5 落地）；调度器在 run 收尾时调 `cancelDescendants(runId)` 清理孤儿后代。父取消的级联靠取消令牌链
   （`spawn` 时 `parentCancellation.onCancel(handle::cancel)`），`parent_cancellation_should_cancel_child`
   与 `failed_run_should_cancel_orphan_descendants` 两条用例分别守住「Esc 级联」与「孤儿清理」。

**S1 单独可合并且零行为变化**，是整条链的风险闸门；S2–S5 建议一个 PR 或按步拆 PR，每步合并前跑第 12 节测试。

---

## 12. 测试计划

- **S1 回归**：`RunScope` 相关既有用例全绿；顶层回合行为逐字不变（`ReActLooperTest`、`SubAgentLauncherTest`）。
- **并行正确性**：直接调 `AgentRuntime.spawn` 并发 N 个 run → 全部完成、结果各自正确、用量总和正确。
- **死锁回归**：`maxDepth = 2` + 许可吃满 + 两层 fan-out，`@Timeout` 兜底（必须通过，不得挂死）。
- **取消传播**：父取消 ⇒ 子树全取消；取消幂等；未执行工具调用被补结果。
- **governor**：并发 / 扇出 / 深度 / 轮数 / 墙钟 / 单 run token / 树 token 各一条，断言 `TRUNCATED` + 命中项 + 提示指向的配置键。
- **并发 spawn 不超发**：`tryAcquireSpawn` 的 CAS 在并发下的上限断言。
- **`task` 行为回归**：单发委派的结果文本、`SubAgentOutcome` 字段、`ToolMetadata.KEY_SUMMARY` 与改造前逐字一致。
- **用量并发**：多子 run 同时归集到同一父会话，断言不丢不重。
- 全量 `mvn -q test` 作为门禁。

---

## 13. 开放问题

- **O1（已定）「模型可并发发起 run」的触发方式**：**P0 不提供模型入口**。`loop` 保持
  「按序执行本批工具」；并行只由 P2 的 workflow 插件（声明式 spec）驱动，P0 仅交付地基并提供
  「直接调 `AgentRuntime.spawn` 并发 N 个 run」的测试证明。这样 P0 零批次语义改动，风险最小。
- **O2** `AgentRuntime` 面向插件的暴露范围（P0 只内部用，P2 再收窄暴露面）。
- **O3（已定）`agent-run` 池的上界**：
  - 池类型：`ThreadPoolExecutor(0, agentRunMax, 60s, SynchronousQueue, 名为 agent-run 的守护线程, AbortPolicy)`
    —— 线程按需创建、空闲回收，它只负责「提供线程」，并发控制交给许可（5.1）。
  - `agentRunMax = maxConcurrentRuns × (maxDepth + 1)`：每一层最多 `maxConcurrentRuns` 个 run 在飞，
    加上父层，就是「活跃 + 等待中的父」的上界。缺省 `3 × (1 + 1) = 6`（深度 1）；深度 3 时为 12。
  - 提交被拒（池满）时 `spawn` 返回 `REJECTED`，理由写明「运行线程已满」——不静默丢弃。
  - **已知边界**：这套「等待中的父占线程」在树的内节点数超过 `agentRunMax` 时不再成立
    （深度与扇出都拉满的病态配置）。那时需要方案 B（事件驱动挂起），列为后续项而非 P0 目标。
- **O4** 看门狗与 `loop` 自查的墙钟判定以哪个为准（建议：看门狗硬砍、`loop` 兜底）。
