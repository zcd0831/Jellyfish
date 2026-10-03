# 设计：子代理运行时 P2 —— 声明式编排（workflow 插件）

> **状态：设计已确认（D-P2-1…D-P2-7 已拍板，见第 8 节）；P2a 起进入施工。**
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

### D-P2-1 端口是「出向边」，挂在 `PluginContext` 上

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

    /** 取消单个 run（幂等）。 */
    void cancel(String runId);
}
```

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

### D-P2-3 依赖倒置：api 接口 + core 实现 + infra 持有 api 类型

```
api    SubAgentPort / DelegationRequest / DelegationResult / DelegationHandle
core   SubAgentDelegationAdapter implements SubAgentPort   （@Binds 到 PluginContextImpl）
infra  PluginContextImpl 持有 SubAgentPort（api 类型）——infra 不依赖 core
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

**回灌文本**：一行概要（`[workflow X 完成 · N 步 · M 轮 · T tok]`，失败时带
`⚠ <第一步失败的原因>`）+ 按步骤顺序的分节正文（`## <id> (<agent>)`），
`ToolMetadata.KEY_TERMINAL` 与 `task` 同口径填。

---

## 5. 插件侧工作（`Jellyfish-Plugins`）

新增 `jellyfish-plugin-workflow`：

| 组成 | 内容 |
| --- | --- |
| `workflow` 工具 | 描述符 + JSON Schema（spec 的结构）；处理器 = 校验 → 引擎 |
| 引擎 | 按 `needs` 做层序调度：同一层的步骤并发 `spawn`，再逐个 `await`；`when` 在执行前判定 |
| 提示词贡献 | `PromptContributionRequest`：把 spec 结构与「什么时候用 workflow 而不是 task」告诉模型 |
| 观测（可选，S4） | `PanelContributionRequest`：当前 workflow 的步骤状态 |

引擎**不自己起线程池**：并发度由内核的 governor（`maxConcurrentRuns`）决定，插件只负责依次
`spawn`；超出的 run 在内核侧排队——这正是 governor 存在的意义，插件不该有第二套并发控制。

---

## 6. 阶段

| 步 | 内容 | 仓库 | 依赖 | 可回滚 |
| --- | --- | --- | --- | --- |
| **P2a** | `SubAgentLauncher` 拆 `spawn` / `await`（纯重构，`task` 语义不变，测试为证） | Jellyfish | P0/P1 | 高 | **已完成** |
| **P2b** | api 端口（`SubAgentPort` / `DelegationRequest` / `DelegationResult` / `DelegationHandle`）+ core 适配器 + infra 持有 + 装配 | Jellyfish | P2a | 高（端口无人用即回退） |
| **P2c** | 插件骨架：模块、工具描述符、spec 校验（含全部拒绝路径的单测） | Plugins | P2b | 高 |
| **P2d** | 引擎：层序调度、并发扇出、`when`、聚合（`collect` / `summarize`） | Plugins | P2c | 高 |
| **P2e** | 提示词贡献 + 面板贡献 + 端到端（真内核跑一份 spec） | Plugins | P2d | 高 |

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
