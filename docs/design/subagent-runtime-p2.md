# 设计：子代理运行时 P2 —— 声明式编排（workflow 插件）

> **状态：设计已确认（D-P2-1…D-P2-7 已拍板，见第 8 节）；P2 全部落地（P2a–P2e 与端到端）。**
> 上游总纲：[`subagent-runtime.md`](subagent-runtime.md)；P0/P1 已落地。
> 本文覆盖总纲第 9 节（编排层）的落地细节，并把第 9.1 节的能力上限翻成具体 schema。

---

## 1. 范围

**做**：一个官方插件，注册一个 `workflow` 工具，接受一份**声明式 spec**，按 spec 派生一批子代理 run、
按依赖并发执行、按声明聚合结果。

**不做**（总纲 D7 / 第 9.1 节）：

- 命令式编排脚本（任意代码驱动内核）；
- 任意控制流：循环、递归、跳转、表达式求值；
- 重试 / 退避策略引擎；
- 运行期按结果动态生成步骤；
- 内核侧解析 spec、做依赖调度、做聚合策略。

---

## 2. 落地前的关键发现：内核还没把原语交出来

总纲 D8 写的是「引擎放插件；内核只出原语」。现状是**原语只出到了 `core`，没跨过 api 边界**：

| 今天的样子 | 为什么插件用不了 |
| --- | --- |
| `AgentRuntime.spawn(AgentRunRequest, CancellationToken, AgentRunBody)` | `AgentRunBody` 是 core 的 `@FunctionalInterface`——插件执行不了「跑一个嵌套回合」这段代码，那是内核的循环 |
| `AgentRunRequest` / `AgentRunSnapshot` / `AgentRunResult` | 也在 `core.runtime`，插件看不到 |
| `SubAgentCall` / `SubAgentOutcome` / `SubAgentLauncher.run` | 在 `core.subagent`，插件看不到 |
| `PluginContext`（插件唯一的入口） | 只有 `handle` / `contribute` / `observe` / `emit` / `submit` / `present`，没有任何「派生子代理」的出向边 |

api 侧 grep `delegat` / `SubAgent` / `AgentRun` 只命中一个无关的 `SessionMessageSnapshot`。

**结论**：P2 的第一步是内核补一条**面向插件的委派端口**（第 3 节的 D-P2-1…D-P2-6），
插件引擎（第 5 节）建立在这条端口之上。这也解释了为什么 P2 不能只写插件。

---

## 3. 内核侧决策

### D-P2-1 端口是「出向边」，挂在 `PluginContext` 上（**P2b 已落地**）

新增一个 api 接口（示意名 `SubAgentPort`），由 `PluginContext` 暴露：

```java
// api
public interface PluginContext {
    // …既有方法…
    SubAgentPort delegations();      // 新增
}

public interface SubAgentPort {
    /** 派生一个 run：立即返回句柄，不阻塞。 */
    DelegationHandle spawn(DelegationRequest request);
}
```

**落地时的三点修正**（均已在代码中）：

1. **去掉了 `cancel(String runId)`**：内核只有子树语义的 `cancelTree`，而插件拿不到 runId
   （它只有自己手上的句柄）——留一个「按 id 取消」的方法会让语义含糊。取消走 `DelegationHandle.cancel()`。
2. **`delegations()` 是 `default` 方法**，缺省返回 `SubAgentPort.unavailable()`（一个 `spawn` 必给
   `REJECTED` 结果的空对象）。这样「内核没装配这个能力」不会以异常形式出现在插件的正常路径上，
   也不打断任何既有 `PluginContext` 实现。
3. **包名是 `zcd.jellyfish.api.subagent`**（端口与它的值类型同住一个包），不另开 `delegation` 包。

**为什么不新增一个 `ExtensionRequest` 扩展点**：`handle` / `contribute` 的调用方向是
**内核 → 插件**，插件**没有** invoke 内核处理器的入口（`PluginContext` 刻意不给）。
把委派做成扩展点，插件仍然调不动它——方向错了。`emit` / `submit` / `present` 才是出向边，
委派与它们同类。

**为什么 `delegations()` 返回一个子接口而不是在 `PluginContext` 上加两个方法**：
端口是可选的（见 D-P2-6 的缺失语义），单独一个接口让「有没有这个能力」是一次判空，
而不是在每个方法里各判一次。

### D-P2-2 `spawn` 非阻塞 + 句柄阻塞等待（**已定**）

```java
public interface DelegationHandle {
    String runId();
    /** 阻塞在<b>调用方自己的线程</b>上直到终态；同一句柄重复调用返回同一个结果。 */
    DelegationResult await();
    void cancel();
}
```

- **`spawn` 立即返回**：并发扇出靠它——插件连发 N 个 `spawn`，N 个 run 在 `agent-run` 池上真并行，
  插件线程只负责逐个 `await`。若 `spawn` 自己阻塞，扇出就退化成串行。
- **`await` 阻塞在插件线程上**：这是**插件 → 内核**的方向，与「内核绝不同步回调插件」不冲突；
  它等价于今天 `task` 工具阻塞在自己的工具线程上。
- **结果只取一次**：内核在 `await` 返回前把登记表条目摘掉（与 `SubAgentLauncher` 今天一致），
  句柄自己缓存结果，重复 `await` 不返回空。

**为什么不做纯异步（`observe` + 轮询）：它与内核现有不变量相撞。**

1. **工具结果必须在返回时给出**。纯异步意味着 `workflow` 立刻返回「已启动」，真正的结果只能后来注入——
   可注入的唯一通道是 `PluginContext.submit(PluginAction)`，而 `ActionQueue` 的约束是
   「动作只能落进**正在跑**的顶层回合」。换句话说，纯异步 = **后台子代理**，而设计里明确不做。
2. **轮询也救不了**：插件轮询到结果时，父回合那次工具调用早已返回，结果没有归属。
3. **取消要自己造状态机**：阻塞等待天然把 `ToolCallRequest.getCancellationToken()` 透传给每个 run
   （Esc 一次扦断整批）；异步化后要自己维护「哪些 run 还没回来」。

**阻塞的代价（有界，且与总纲第 6.1 节同一笔账）**：

- 一次 `workflow` 占住**一条工具执行线程**直到整批 run 结束。并发度由 governor 决定（插件不建线程池），
  单 run 有 `runTimeoutMillis`，步骤数有上限，因此最坏等待有界。
- **可观测性不因此损失**：阻塞期间插件用 `ToolCallOutput`（工具输出旁路）逐步骤刷进度，
  与 `task` 今天打 `· 工具名` 是同一个机制。

### D-P2-3 依赖倒置：api 接口 + core 实现 + infra 持有 api 类型（**P2b 已落地**）

```
api    SubAgentPort / DelegationRequest / DelegationResult / DelegationHandle / DelegationStatus
core   SubAgentDelegationAdapter implements SubAgentPort
infra  PluginContextImpl / PluginContextFactory 持有 SubAgentPort（api 类型）——infra 不依赖 core
cli    PluginModule.provideSubAgentPort(SubAgentDelegationAdapter)：接口→实现的唯一连接点
```

`PluginContextImpl` 在 `jellyfish-infra`，而委派实现需要 `core.subagent`；`core → infra` 是既有方向，
反向会成环。因此**接口必须落在 `api`**，`infra` 只持有接口。这也是 `ActionQueue` / `ShellIngress`
已经在用的写法（infra 定义队列，core 侧投递）。

### D-P2-4 一致性靠构造：端口实现复用 `SubAgentLauncher`（**P2a 已落地**）

P2 的验收要求「插件驱动的 run 与 `task` 触发的 run 在 governor / 深度 / 取消 / 观测上行为一致」。
让它们一致的唯一可靠做法是**同一条代码路径**，而不是「两处都记得这么写」：

把 `SubAgentLauncher.delegate` 拆成两半（`task` 与端口共用）：

```java
/** 准入 + 建会话 + 派生。立即返回，不阻塞。 */
SubAgentRunHandle spawn(SubAgentCall call, ReActListener listener);

/** 等终态 → 归集用量 → 归档 → 关会话 → 摘登记。阻塞在调用方线程上。 */
SubAgentOutcome await(SubAgentRunHandle handle);
```

- `task` 工具 = `await(spawn(call, listener))`，对外语义一字不变。
- 端口实现 = 把 `DelegationRequest` 翻成 `SubAgentCall`，`spawn` 走前半、`await` 走后半。
- 于是 governor（`tryAcquireSpawn` / 并发许可 / 墙钟 / token 预算）、深度判定、run 事件、归档、
  用量归集**全都自动一致**——端口不再自己实现一遍准入。

**这是本步唯一一处对既有代码的结构性改动**，但它同时也是 `SubAgentLauncher` 该有的形状：
今天 `run` 的 javadoc 还写着「同步且内联」，而实际执行体早在 P0 就交给 `agent-run` 池了。

落地后的形状（P2a）：

- `spawn(call, listener)`：准入 → 建子会话 → `runtime.spawn`，返回 `SubAgentRunHandle`（新类型，`core.subagent`）。
  准入被拒或派生途中失败时，句柄**直接带着终态结果**返回（`isSettled()`），
  两条调用路径因此都只需 `spawn → await` 两步，不必为「早失败」各写一条分支。
- `await(handle)`：等终态 → 归集用量 → 归档 → 关子会话 → 摘登记。**幂等**（结果缓存在句柄上），
  且**早失败也走这里**——否则派生失败会漏掉子会话与登记表的清理。
- `run(call, listener)` = `await(spawn(call, listener))`，`task` 工具一字未改。

**测试为证**：`SubAgentLauncherTest` 新增 4 条——`spawn` 不等待 run 完成（用阻塞的执行体 + 耗时断言区分）、
**两个 run 真同时进入执行体**（扇出的正题）、`await` 只收尾一次（归档恰好一次）、
被拒时返回已终结的句柄且零副作用（不建会话、不归档）。

### D-P2-5 api 结果类型与 core 结果类型分开

api 侧新增 `DelegationResult`：`runId` / `status`（枚举）/ `text` / `rounds` / `totalTokens` / `reason`。
**不复用 `SubAgentOutcome`**——那是 core 类型，插件看不到。映射在适配器里做一次。

`DelegationRequest`：`agentId` / `prompt` / `parentSessionId` / `CancellationToken`。
前三个是必填，取消令牌由插件从 `ToolCallRequest.getCancellationToken()` 取。

### D-P2-6 能力缺失时的语义

`PluginContextImpl.delegations()` 在**没有绑定端口**时（内核单测、旧内核、非完整装配）返回
一个「永远拒绝」的实现：`spawn` 返回一个立即终结为 `REJECTED` 的句柄，理由是
「当前内核没有提供子代理委派能力」。**不抛异常**——插件因此不需要为「内核版本旧」写分支，
与 `ToolOutputSink.NOOP` / `CancellationToken.NONE` 是同一个口径。

端口上的拒绝（开关关闭、深度用尽、扇出用尽、类型不可委派、没有进行中的回合）复用
`SubAgentLauncher` 现有的中文理由文本，`DelegationResult.status = REJECTED`。

### D-P2-7 取消传播

插件的 spec 工具跑在某次工具调用上，`ToolCallRequest` 自带取消令牌（Esc / 父回合取消）。
端口把该令牌透传给每个 `SubAgentCall`：

- 父回合被取消 ⇒ 令牌触发 ⇒ **已派生的每个 run 被取消**（与 `task` 今天的行为一致）；
- 插件在引擎里感知到取消后，对未派生的步骤不再 `spawn`，并对已派生的逐个 `cancel()`（幂等）。

---

## 4. spec schema（v1，草案）

工具输入：

```json
{
  "spec": {
    "name": "可选，只用于日志与面板",
    "steps": [
      { "id": "survey",  "agent": "scout",   "prompt": "盘点现有调用点" },
      { "id": "design",  "agent": "planner", "prompt": "给出改造方案", "needs": ["survey"] },
      { "id": "audit",   "agent": "auditor", "prompt": "审计风险",     "needs": ["survey"] },
      { "id": "verify",  "agent": "scout",   "prompt": "核对方案",     "needs": ["design", "audit"],
        "when": "on_success" }
    ],
    "aggregate": { "mode": "summarize", "agent": "summarizer" }
  }
}
```

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `steps[].id` | 是 | 步骤标识，spec 内唯一 |
| `steps[].agent` | 是 | 子代理类型；必须是 `delegatable` 的 agent，否则整份 spec 拒绝 |
| `steps[].prompt` | 是 | 该步的任务原文 |
| `steps[].needs` | 否 | 依赖的步骤 id；缺省 `[]`（无依赖 = 可与其它无依赖步骤并发） |
| `steps[].when` | 否 | `always`（缺省）/ `on_success` / `on_failure`：按**直接依赖**的成败决定本步是否执行 |
| `aggregate.mode` | 否 | `collect`（缺省，按步骤顺序拼接）/ `summarize`（再派一个 agent 汇总） |
| `aggregate.agent` | `summarize` 时必填 | 汇总用的子代理类型 |

**能力上限（现在就划死，越界即拒绝整份 spec）**：无循环、无跳转、无表达式、无变量、
无运行期动态生成步骤、无重试。`when` 只看「直接依赖的终态」，不做布尔组合
（要组合就拆步骤，或由模型重新发一份 spec）。

**校验全部在派生任何 run 之前完成**：重复 id、`needs` 指向不存在的 id、依赖成环、
agent 不存在或不可委派、`summarize` 缺 agent、超出步数上限——一律在 `spawn` 之前拒绝，
理由是「一份写错的 spec 不该在烧掉几个子代理之后才被拒绝」。

**落地时补的三条**（P2c/P2d）：

1. **步数上限 12**（`WorkflowSpecParser.MAX_STEPS`）：它是「一次编排最多烧掉多少个子代理」的上限，
   与内核 governor 管的「全局同时在跑多少」正交。
2. **`on_failure` 必须声明 `needs`**：没有前置步骤就没有失败可等，这种步骤永远不执行，
   模型只会看到「什么都没发生」而查不出原因——解析期直接拒绝并把话说清楚。
3. **「成功」= `COMPLETED` 或 `TRUNCATED`**：达到轮数上限的子代理确实跑完了（只是没收敛），
   它的正文对后续步骤仍然有用；把不完整当成失败会让整条下游一起被跳过。

**回灌文本**：一行概要（`[workflow X 完成 · N 步 · M 轮 · T tok]`，失败时带
`⚠ <第一步失败的原因>`）+ 按步骤顺序的分节正文（`## <id> (<agent>)`），
`ToolMetadata.KEY_TERMINAL` 与 `task` 同口径填。

---

## 5. 插件侧工作（`Jellyfish-Plugins`）

新增 `jellyfish-plugin-workflow`（**P2c/P2d 已落地**）：

| 类 | 内容 |
| --- | --- |
| `WorkflowPlugin` | 只占两个扩展点：`workflow` 工具 + 提示词贡献。引擎用 `context.delegations()` 装配 |
| `WorkflowTool` | 工具名片（JSON Schema）+ 校验 → 引擎 → 组装回灌文本与元数据 |
| `WorkflowSpecParser` / `WorkflowSpec` / `WorkflowStep` / `StepCondition` / `AggregateMode` / `SpecValues` | 解析与校验：类型、必填、唯一、引用存在、无环、能力上限 |
| `WorkflowEngine` | 层序调度、并发扇出、`when` 判定、`collect` / `summarize`、进度写进 `ToolOutputSink` |
| `WorkflowRun` / `StepOutcome` | 结局模型（跑过 / 未跑 + 原因 / 取消），累计轮数与 token（含汇总那一次） |
| `WorkflowGuidance` | 提示词贡献 |

**提示词贡献的落位做了收敛**（D13 的细化）：schema 已经写在工具名片里、每轮都在模型眼前，
再复述一遍只会多花 token，并在两边改动不同步时给出两份互相矛盾的说明。因此贡献<b>只给一个可照抄的
例子与选型规则</b>（什么时候该用 `workflow`、什么时候 `task` 就够），落位取 `STATIC`（编译期就固定，进可缓存前缀）。

**并发不靠插件**：引擎只做「同层先全部 `spawn`、再逐个 `await`」，并发度完全交给内核 governor。
插件若自建线程池，两套上限互相不知道对方，就会出现「插件以为在并发、实际全在排队」这种查不出的现象。

**失败不中断整条编排**：一个步骤失败只影响它自己的下游（由各步 `when` 决定），无依赖关系的步骤照常执行——
让整批在第一步失败时全停，模型就得重新推演剩下的部分。

引擎**不自己起线程池**：并发度由内核的 governor（`maxConcurrentRuns`）决定，插件只负责依次
`spawn`；超出的 run 在内核侧排队——这正是 governor 存在的意义，插件不该有第二套并发控制。

---

## 6. 阶段

| 步 | 内容 | 仓库 | 依赖 | 可回滚 | 状态 |
| --- | --- | --- | --- | --- | --- |
| **P2a** | `SubAgentLauncher` 拆 `spawn` / `await`（纯重构，`task` 语义不变，测试为证） | Jellyfish | P0/P1 | 高 | **已完成** |
| **P2b** | api 端口（`SubAgentPort` / `DelegationRequest` / `DelegationResult` / `DelegationHandle` / `DelegationStatus`）+ core 适配器 + infra 持有 + 装配 | Jellyfish | P2a | 高（端口无人用即回退） | **已完成** |
| **P2c** | 插件骨架：模块、工具描述符、spec 校验（含全部拒绝路径的单测） | Plugins | P2b | 高 | **已完成** |
| **P2d** | 引擎：层序调度、并发扇出、`when`、聚合（`collect` / `summarize`） | Plugins | P2c | 高 | **已完成** |
| **P2e** | 面板贡献（显示当前 workflow 的步骤状态） | Plugins | P2d | 高 | **已完成** |

### 6.1 落地记录：P2e

编排面板把「哪一步在跑、哪些完了、还剩几步」变成常驻信息——一次编排可以在没有输出的状态下跑好几分钟，
而它**是并行**的，工具行上滚动的那几行看不出全貌。

三条落地时定下的边界：

1. **落消息区上方（`TOP`）而不是停靠区**：停靠区里已经排了内核那块「子代理」面板（它声明了更小的
   `order`），而一次编排**就是**批量派子代理——两块面板恰好在同一时刻都要看（那边讲「谁在跑」，
   这里讲「编排走到第几步」），同区域等于永远只能看见一个。上方的行数预算与停靠区完全相同，
   换区域不多花一行，只是把冲突消掉；顺带还解决了纵向栏里步骤行折行的问题。
2. **状态变化要主动通知外壳**：外壳只在缓存失效时收集面板，而「某一步跑完了」发生在回合内部的工具调用里，
   外壳自己看不到。因此台账每次变化都广播一条 `UiInvalidatedEvent`（与 `jellyfish-todo` 写完待办同一件事）。
   **没有这条通知，面板会在编排结束后才第一次出现，而那时它已经空了。**
   通知失败只记日志：插件被停止时上下文已失效，那时面板停在最后一帧是对的，
   而编排本身不该因为一次界面刷新失败而失败——这是展示与业务的分界。
3. **行数上限由插件先收一道**：外壳会给面板兜底（折行、截断、行数上限），但插件不该依赖那个兜底——
   一个 12 步的编排加两行标题就能把停靠区顶掉小半屏。收在 12 行，超出部分折成一句「另有 N 步未显示」。

顺带一处语义修正：进度里的「已了结」**包含失败与跳过**（它们不会再跑第二次）。否则
「3 步跑了 2 步、其中 1 步失败」会显示成 1/3，看起来像卡住了；失败由标题的警示档位与 `[!]` 标记表达。

**面板能看到编排，靠的是「引擎写台账、面板读快照」**：引擎在工具线程上跑、面板在渲染线程上读，
外壳又是**拉取式**（只在缓存失效时收集），三者生命周期错开。因此需要一个共享台账
（`WorkflowTracker`）把它们连起来，快照是**读时拷贝**（与内核 `AgentRunSnapshot` 同一口径）。

### 6.2 落地记录：端到端

分两半各自证明，合起来才是一条完整的链——因为插件仓库与内核之间只有 `jellyfish-api` 的编译期契约，
内核里的委派端口实现在插件侧根本看不到：

| 覆盖范围 | 用例 | 证明了什么 |
| --- | --- | --- |
| 插件侧 | `WorkflowEndToEndTest`（Plugins） | 真 PF4J 加载 + 真扩展点注册表 + 从注册表取处理器调用（内核 `ToolExecutor` 的同一条路）：三步 spec 跑完、步骤按依赖分批派生、汇总材料里带上前几步结论、成环的 spec 被拒且**一个 run 都没派生** |
| 内核侧 | `SubAgentDelegationEndToEndTest`（Jellyfish） | 真会话域 / 登记表 / 调度器 / 运行时 / 归档器 + **真端口**，只把 `ReActLooper`（模型那一层）换成脚本：两个并行委派的结果被翻译、用量归到父会话、每个 run 写了归档、登记表清空、子会话关闭且不进会话列表；无回合时拒绝理由是**内核准入的那句**（而不是端口缺失那句）——据此排除「装配接错实现」 |
| 装配 | `PluginModuleTest`（Jellyfish） | `SubAgentPort` 绑的是 `core` 里的真适配器（不是 `unavailable()` 占位），且装配出来的 `PluginContext.delegations()` 交给插件的就是它。绑定「存不存在」由 Dagger 代码生成在编译期保证（少一个绑定直接编译不过） |

**没有覆盖的**：真模型参与的全链路（`workflow` → 子代理真的调模型）。它需要的是一条
可脚本化的 LLM 装配路径，而内核当前的模型层绑在 Dagger 图里、没有测试替身的入口。
这一层的正确性目前由「`task` 与端口共用 `SubAgentLauncher`」这条构造性事实承担——
两者的差异只有适配器里那几行翻译，而那几行已被 `SubAgentDelegationAdapterTest` 逐字段锁住。

**每一阶段都必须让「插件卸载 = 回退到 `task` 薄工具」成立**：插件缺席时不注册 `workflow`，
内核侧端口零调用。

---

## 7. 验收

**P2a**
- `task` 的全部既有用例不改行为（`spawn` + `await` 的组合与拆前逐字段一致）。
- 父回合取消 ⇒ 在途 run 被取消；`await` 之后登记表不残留条目（归档已完成）。

**P2b**
- 无端口绑定时 `delegations().spawn(...)` 返回 `REJECTED` 而不是抛异常。
- 端口驱动的 run 与 `task` 驱动的 run：并发许可、深度判定、run 事件、归档文件名与内容形状一致。
- 端口在自己线程上阻塞等待，不占 `react` 池线程。

**P2c/P2d**
- 越界 spec（成环 / 重复 id / 未知 agent / 表达式 / 步数超限）在派生任何 run 之前被拒绝，
  且拒绝理由是中文可读的一句话。
- 4 步 spec（1 → 2 并发 → 1）实际并发执行（用假端口的时序断言），且聚合文本按步骤顺序稳定。
- 单步失败时：`on_failure` 的步骤执行、`on_success` 的跳过、`always` 的照常执行。
- 插件不引入任何第二套并发控制（无自建线程池）。

**P2e**
- 提示词贡献只在插件启用时出现；`workflow` 工具不在时模型看不到它。
- 端到端：真内核 + 真插件跑一份两步骤 spec，`/usage` 里能看到两个子代理的 token 都记进父会话。

---

## 8. 待确认（全部已拍板）

1. **D-P2-1 端口挂在 `PluginContext` 上**（`delegations()`）——**已确认：接受**（唯一的新权限面，
   受 governor 与深度约束）。
2. **D-P2-2 阻塞等待 vs 纯异步**——**已定：阻塞等待**（理由见 D-P2-2；纯异步与
   「工具结果必须在返回时给出」「不做后台子代理」「ActionQueue 只投顶层回合」三条不变量相撞）。
3. **D-P2-4 拆 `SubAgentLauncher`**——**已确认：接受**。
4. **spec schema（第 4 节）**——**暂时按此方案，后续迭代**（`when` 三个取值、不做布尔组合）。
5. **插件命名**——**已确认：`jellyfish-plugin-workflow`**（工具名 `workflow`）。
