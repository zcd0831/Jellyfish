# 设计：LLM 缓存对齐（Prompt Cache Alignment）

> 本文是一次**问题诊断 + 优化方案**的设计文档，不是已落地行为的描述。
> 文中的「现状」指写下本文时的代码；「方案」部分在落地前都不是现有 API。
> 落地时需同步更新 `../configuration.md`（新增配置项）与 `../constraints/react-compact.md`（组装与压缩规则）。

## 1. 问题

用户在 DeepSeek 平台的实际用量面板上观察到：Jellyfish 的 prompt cache 命中率显著低于同类 Agent。

这不是臆测，而是厂商侧的实测数据。本文的目标是回答四件事：厂商的缓存规则是什么、主流 Agent 怎么做的、
Jellyfish 为什么低、以及怎么改。

**结论先行**：命中率低与模型/厂商无关，是 Jellyfish **每轮重新改写了「已经发送过的前缀」**。
根因有两条是主因，其余是次要因素与度量缺失。

---

## 2. 厂商缓存规则

### 2.1 DeepSeek（重点）

出处：<https://api-docs.deepseek.com/guides/kv_cache/>

- **自动开启、零代码改动**，磁盘 KV 缓存，按实际命中的 token 计费（命中价约为未命中的 1/10）。
- **只匹配从第 0 个 token 开始的前缀**；文档明确「输入中段的局部匹配不会命中」。
- **关键且常被误解的一条**：命中要求**完整匹配一个「缓存前缀单元（cache prefix unit）」**，而不是任意长度的公共前缀。
  单元在三种时机落盘：
  1. **请求边界**：用户输入结束处、模型输出结束处；
  2. **公共前缀探测**：系统发现多个请求共享一段前缀时，把它单独落盘；
  3. **固定 token 间隔**：长输入 / 长输出按固定间隔切单元，避免长前缀永远等不到边界。
- 官方例子（最能说明问题）：
  - 请求 1 = `A + B`，请求 2 = `A + B + C` → 请求 2 完整匹配单元 `A + B`，**命中**。
  - 请求 1 = `A + B`，请求 2 = `A + C` → **不命中**（`A + C` 没有完整匹配 `A + B`）；
    但系统会探测出公共前缀 `A` 并落盘，请求 3 = `A + D` 才命中 `A`。
- **存储粒度**：2024 年公告为 **64 token**，「不足 64 token 不缓存」；社区实测 V4 上可靠命中的实际下限更高
  （约 256–1024），**官方文档未同步更新，此处标注为不确定**。
- **TTL**：不保证，停用后「a few hours to a few days」自动清除；**缓存构建需要数秒**。
- **度量字段**：`usage.prompt_cache_hit_tokens` / `usage.prompt_cache_miss_tokens`
  （流式需 `stream_options.include_usage`，Jellyfish 已设置）。

> **对 Agent 的直接含义**：因为要「完整匹配一个落盘单元」，**「每轮只改动前缀中段一点点」是最坏的模式**——
> 它连「公共前缀探测」都难以积累出稳定单元。**append-only 是唯一稳妥的形态。**

### 2.2 其他厂商

| 厂商 | 开启方式 | 最小前缀 | 计量粒度 | TTL | 计费 | 度量字段 |
| --- | --- | --- | --- | --- | --- | --- |
| **DeepSeek** | 自动 | 64 token 单元（实际更高，未确认） | 缓存前缀单元 | 小时–天 | 命中 ≈0.1× | `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` |
| **OpenAI** | 自动（可选 `prompt_cache_key`） | 1024 token | **128 token 递增** | 内存缓存约 5–10 分钟 | 命中约 0.1×–0.5× | `prompt_tokens_details.cached_tokens`、`cache_write_tokens` |
| **Anthropic** | 显式 `cache_control` 断点（也可自动） | 按模型 1024 / 2048 / 4096 | 断点，**最多 4 个** | 默认 **5 分钟**（可 1h） | 写 1.25×（5m）/ 2×（1h），读 **0.1×** | `cache_read_input_tokens` / `cache_creation_input_tokens` |
| **Gemini** | 隐式 + 显式 `cachedContents` | 按模型 | 显式资源 | 可配置 | 折扣 | `cachedContentTokenCount` |

Anthropic 官方明确的渲染顺序是 **`tools` → `system` → `messages`**：
**工具定义排在 messages 之前**，因此改工具定义等于把整段前缀作废。

### 2.3 五条硬约束

1. 前缀**逐字节**一致，改一个字节 → 其后**全部**失效。
2. **工具定义属于前缀**，且位于 messages 之前。
3. 缓存**按模型隔离**，会话中途换模型 = 全量重建。
4. 命中折扣远大于「少发 token」的收益：读 0.1×，而**删掉前缀中段**要用 1× 买回它后面的全部内容。
5. 命中率是**可观测**的（三家都回传字段），应当像可用性一样被监控。

---

## 3. 主流 Agent 的优化策略

主要依据 Anthropic 官方文章
[Lessons from building Claude Code: Prompt caching is everything](https://claude.com/blog/lessons-from-building-claude-code-prompt-caching-is-everything)。

### 3.1 布局：静态在前、动态在后

```
1. 静态 system prompt + 工具定义    ← 全局共享
2. CLAUDE.md / 项目约定             ← 项目内共享
3. 会话上下文（env、MCP、输出风格）  ← 会话内共享
4. 对话消息                         ← 每轮增长
```

官方列出踩过的三个坑：**在静态 system prompt 里放详细时间戳**、**非确定性地打乱工具顺序**、
**动态修改工具的 agent 列表**。

### 3.2 用消息承载更新，而不是改 system prompt

> "There may be times when the information you put in your prompt becomes out of date…
> It may be tempting to update the prompt, but that would result in a cache miss…
> Consider if you can pass in this information via messages in the agent's next turn instead.
> In Claude Code, we add a `<system-reminder>` tag in the next user message or tool result."

**这是 Jellyfish「待办注入」问题的标准解法。**

### 3.3 会话内绝不增删工具

Plan Mode 不在切换时替换工具集，而是**保留全部工具** + 新增 `EnterPlanMode` / `ExitPlanMode` 两个工具
+ 用一条消息说明当前模式。MCP 工具多时用 `defer_loading` 只发轻量 stub。

### 3.4 压缩必须「cache-safe forking」

> 最简做法是「单独一次调用、自己的 system prompt、不带工具」——**那正是成本陷阱**：
> 前缀从第 0 个 token 就分叉，整段对话按未命中价计费，**对话越长，这一次越贵**。
>
> 解法：用与父会话**完全相同**的 system prompt / 上下文 / 工具定义，前置父会话消息，
> 把压缩指令作为**新的 user 消息追加在末尾**。

### 3.5 其他 Agent

| Agent | 做法 |
| --- | --- |
| **Aider** | `--cache-prompts`，按 `system → 只读文件 → repo-map → 可编辑文件 → 当前消息` 分层缓存；主动 ping 保活以延长 Anthropic 的 5 分钟 TTL |
| **Cline** | 3 个断点（system、最近 2 条 user）；被社区审计指出**把易变的当前 user 消息当成缓存断点**、**未传 `prompt_cache_key`**，浪费 30–90% 折扣 |
| **通用** | 有公开的 [prompt-cache-skills 审计表](https://github.com/OnlyTerp/prompt-cache-skills/blob/main/docs/scorecard.md)，逐 harness 打分「前缀稳定性」 |

> 「缓存命中率低」在这个领域是普遍病——Claude Code 团队自己要「对命中率设告警、低了按 SEV 处理」。

---

## 4. Jellyfish 现状与根因

### 4.1 每轮请求的组装链路

`ReActLooper.loop()`（`jellyfish-core/.../ReActLooper.java:314`）每轮调用 `PromptAssembler.assemble`：

```
systemPrompt = agent 提示词 + 各插件贡献（按 order） + 历史摘要      ← ①
history      = 会话消息（压缩边界之后）
history      = ToolResultAger.age(history)                        ← ②
history      = ContextWindow.crop(history, budget)                ← ③
messages     = [system] + history
tools        = ToolCatalog.tools(filter)                          ← ⑤
```

序列化见 `AbstractOpenAiCompatibleLlmClient.buildRequestBody`：system 作为 **`messages[0]`** 下发。

### 4.2 根因

#### R1 易变状态被注入 system prompt（token 0 断裂）

`PromptAssembler.systemPromptOf` 把 `contributionsOf(session)` 拼进 system prompt。
而 `TodoPromptContribution`（`Jellyfish-Plugins/jellyfish-plugin-todo/.../TodoPromptContribution.java`）
**每轮都返回当前待办清单**（`TodoText.promptBlock` 渲染 `[待办] - [~] …`）。

模型每次调 `todo_write` 就改一次状态 → **system prompt 变 → 整个请求从第 0 个 token 起全部未命中**。
在待办驱动的多步任务里，这可能是每 1–3 轮一次 → **命中率趋近 0**。

> **冗余性**：`TodoText.confirmation` 已经把「覆盖后的整份清单」写进 **tool 结果**了。
> 也就是说 system prompt 里那块待办**基本是冗余的**——而它恰好是唯一会打死缓存的注入位置。

其余贡献都是稳定的：`ProjectPromptContribution` 有会话级缓存（`ContributionCache`，「一会话只读一次盘」）、
`SkillPromptContribution` 扫目录缓存、`FileReferencePromptContribution` 是常量。**只有待办每轮变。**

#### R2 `ToolResultAger` 每轮改写历史中段（最主要、最隐蔽）

```java
int boundary = messages.size() - keepRecent;   // 默认 keepRecentMessages = 20
for (int index = 0; index < boundary; index++) { … 换成 stub … }
```

- 老化区在**头部**（除最后 20 条以外全部），「完整内容」在尾部。
- 每轮追加 2–3 条消息 → **边界每轮前移 2–3 条** → 上一轮还是完整内容的消息，这一轮变成 stub。
- 于是与上一轮请求的**公共前缀止于「第一条被新老化的消息」**：命中区 ≈ system + 一堆已经稳定了的短 stub；
  **真正的贵内容（最近 20 条、含全部完整工具结果）永远在变动区，永远不被复用。**

原设计动机是合理的（避免「几十轮前读的大文件一直占地方，直到被整组丢掉时连路径都没了」），
但它**用 1× 的价格买回了 0.1× 的缓存**：

> 删掉前缀中段的 N 个 token，省下 N；却让其后的 M 个 token 全部从 0.1× 变成 1×。
> 只要 M > 0.11N 就是净亏。而 M 是**整段后续历史**。

#### R3 `ContextWindow.crop` 从最旧侧成组丢弃

`ContextWindow.crop` 从最新组往回装，装不下就整组丢最旧。一旦超预算：
**每轮丢的位置都不同 → 每轮整个消息段分叉**。比 R2 更彻底。
好在 `autoCompactIfNeeded` 在「本次已被裁剪」时会立刻触发压缩，通常只发生 1–2 轮。
定位为**安全网**，目标是「极少触发」。

#### R4 压缩调用不从父前缀分叉

`ConversationCompactor.planOf`：

```java
LlmRequest.Builder builder = LlmRequest.builder(modelId)
        .systemPrompt(instructions)                                  // 与父会话不同
        .messages(Collections.singletonList(LlmMessage.user(body))); // 渲染后的全文，无工具
```

前缀从**第 0 个 token** 分叉 → **那次摘要调用把整段对话按未命中价重发一遍**，
而它恰恰发生在会话最长的时候。每次压缩（`autoCompactPercent` 默认 80%）都吃一次。

#### R5 工具清单可中途变化

- `ToolCatalog.tools` 按 `ExtensionRegistry` 的 `order` 升序 + **注册顺序**返回。顺序在进程内确定，
  但**依赖插件加载顺序**；`MCP` 插件在启动后异步注册工具，工具集合会中途增长。
- **没有踩的坑**（值得保持）：Plan 模式走权限白名单（`ReadOnlyTools`）而**不是**替换工具集；
  `ToolFilter` 只用于子代理。
- 隐患：`SessionModelResolver` 三级回落，若 `/reload` 改了偏好模型，会话中途换模型 = 缓存全废。

#### R6 完全无法度量

`AbstractHttpLlmClient.parseUsage` 只读 `prompt_tokens` / `completion_tokens` / `total_tokens`；
`LlmUsage` 与 `SessionUsage` 也只有这三个字段。**全仓库没有任何缓存 token 的解析**。

→ 既不能验证诊断，也不能设告警。Claude Code 的立场是「像监控 uptime 一样监控命中率」。

### 4.3 关于「摘要进 system prompt」的专项结论

摘要**确实**在 system prompt 里（`PromptAssembler.systemPromptOf` 的第三个 `appendBlock`，排在最后），
内容形如：

```
[历史摘要] 更早的 N 条消息已不在上下文中（其中 M 条因超出摘要预算未被收录）。摘要如下：
<summary>
```

**但它不是导致命中率低的独立原因，不需要为此优化。** 理由：

`SessionManager.applyCompaction` 在写入新摘要的**同一次调用里**推进 `boundaryMessageId`，
而 `PromptAssembler.toLlmMessages(session, boundary + 1)` 按边界截断消息列表：

| | 压缩前（第 30 轮） | 压缩后（第 31 轮） |
| --- | --- | --- |
| 现状（摘要在 system） | `[agent][contribs][S1]` + msgs[51..N] | `[agent][contribs][S2]` + msgs[121..N+2] |
| 假如摘要挪进消息区 | `[agent][contribs]` + [S1] + msgs[51..N] | `[agent][contribs]` + [S2] + msgs[121..N+2] |

两种排法的**公共前缀都止于 `[agent][contribs]`**——因为无论摘要在哪，压缩都同时把边界从 51 推到 121，
消息段的第 0 条就已经变了。**分叉点是同一个位置。**

因此：

- 🔎 **可以确定的事实**：摘要只在**压缩时**变化，不是每轮变化，因此它不产生每轮断裂。
- ❌ **不应期待收益**：把摘要挪到消息区**不会提升命中率**。不建议为缓存做这件事。
- ⚠️ **真正要修的压缩问题是 R4**：压缩**那次调用**的请求从第 0 个 token 就分叉。
- ✅ **值得顺手做的一件事**：让 system prompt 在整个会话内**逐字节恒定**（压缩时也不变），
  这样「system prompt 一变 = 一定有 bug」成为一条可自动检查的不变量。
  这是**工程可维护性收益，不是命中率收益**——如实标注，不含糊。
- ✅ **已经做对的一件事**：`SessionCompaction.createdAt` **没有**被渲染进提示词
  （`summaryBlockOf` 只用 `boundary + 1`、`droppedMessageCount`、`summary`）。
  如果时间戳进了 system prompt，那就是 Claude Code 点名的第一个坑，必须保持现状。

### 4.4 量化模拟

同构负载：system 2k；每轮 assistant 200 + 大工具结果 1200（有信封）+ 小结果 150；
`keepRecentMessages = 20`。指标为「与上一轮请求的公共前缀 token 数 / 本轮总 token」。

| 场景 | 整体命中 | 稳态（后 20 轮） |
| --- | --- | --- |
| **现状**：老化 + 待办每轮变 | **0.0%** | **0.0%** |
| 现状：老化 + 待办每 5 轮变 | 30.1% | 35.1% |
| 只关老化（`keepRecentMessages = 0`），待办仍每轮变 | 0.0% | 0.0% |
| 关老化 + 待办每 5 轮变 | 63.7% | 68.0% |
| **关老化 + 待办移出 system prompt（append-only）** | **78.0%** | **83.9%** |

最后一行 ≈ 理论上限（剩余「未命中」= 每轮真正新增的 ~1.5k token）。
**0% → 84% 的差距全部来自设计，与厂商无关。**

---

## 5. 优化方案

### 5.1 是改内核还是做插件？

**结论：必须改内核，但内核只提供「扩展点 + 安全默认」，策略回到插件。**

判据：**R1–R4 全部落在 `jellyfish-core` / `jellyfish-infra` 的请求组装路径上，而这条路径没有任何 SPI 可介入。**

| 改动 | 层级 | 能否纯插件 | 说明 |
| --- | --- | --- | --- |
| 解析缓存 token + 指标/告警 | `infra/llm`、`infra/metrics` | ❌ | 插件拿不到 LLM 响应与 usage |
| system prompt 布局与排序 | `core/prompt` | ❌ | 组装在内核 |
| 易变状态改走「消息尾部」 | `api` 新扩展点 + `core` 调用点 | ⚠️ 需内核开点 | 现在 `PromptContributionRequest` 明确写「插件唯一合法注入点是 system prompt」 |
| 老化语义（单调 / 预算触发） | `core/prompt` + 配置 | ❌ | `ToolResultAger` 在内核 |
| 裁剪策略 | `core/prompt` | ❌ | `ContextWindow` 在内核 |
| 压缩 cache-safe 分叉 | `core/compact` | ❌（策略字段仍归插件） | 请求构造在内核 |
| 工具清单稳定排序 | `infra/registry`、`core/prompt` | ❌ | 顺序由注册表决定 |
| MCP 延迟加载 / stub | `jellyfish-plugin-mcp` | ✅ | 插件自己控制注册时机 |
| 压缩策略参数（指令、保留条数） | `jellyfish-plugin-compact` | ✅ | `CompactionStrategy` 已存在 |

**纯插件方案的天花板很低**：只能改 `keepRecentMessages` 这类配置项，动不了 R1 / R2。

### 5.2 阶段划分

#### P0.5 · 出站消息序列的工具调用配对（内核，缓存优化的前置）

**先于缓存优化**：这是「请求发不出去」级别的故障，而 P4 的 cache-safe fork 要把父请求的消息序列
原样重发——序列本身非法时 fork 一样发不出去。三处修复与规则见
[../constraints/react-compact.md](../constraints/react-compact.md#消息序列的工具调用配对约束)：

1. `ConversationCompactor.planOf` 的压缩边界对齐到工具调用组的边界；
2. `PromptAssembler` 出站前跳过开头的孤儿 `tool` 消息、丢弃结尾悬空的 `assistant(toolCalls)`；
3. `ReActLooper` 取消时为未执行的工具调用补发合成结果（`terminal=CANCELLED`）。

#### P0 · 先度量（内核，无扩展点）

**已落地（P0a：把数字算出来并显示出来）**：

- `LlmUsage` 增加 `cacheReadTokens` / `cacheWriteTokens` 与 `getCacheHitRate()`；输入口径统一为
  **「总输入」**，即缓存计数恒为它的子集。
- 各厂商客户端按自己的口径解析并**当场归一化**：
  - OpenAI / DeepSeek：`prompt_tokens_details.cached_tokens` 或 `prompt_cache_hit_tokens`
    （两家 `prompt_tokens` 本已包含缓存部分）；
  - Gemini：`cachedContentTokenCount`（`promptTokenCount` 的子集）；
  - Anthropic：`cache_read_input_tokens` + `cache_creation_input_tokens`，且
    **总输入 = `input_tokens` + 两者**（三个字段是互斥划分，同步与流式两条路径都要加回去）。
- `SessionUsage` 增累计字段，命中率**按累计量现算**（累计命中 / 累计输入），不取每次命中率的平均；
  会话级累计随会话落盘（`SessionUsageSnapshot`），`/resume` 之后不归零。
- `/usage` 与 `/status` 给出「命中 H / 输入 N（P%）」。

> **一处行为变更（修正而非回归）**：Anthropic 的输入总数会变大——它此前把缓存部分**整个漏掉**
> （有缓存活动时，会话用量少算的恰好是最大那一块）。OpenAI / DeepSeek / Gemini 的展示数字不变。

**待做（P0b：持续观测与自动告警）**：

- `MetricsRegistry` 出缓存命中计数。**不能直接加**：本项目指标只从事件派生（`MetricsSubscriber`），
  而当前没有携带 `LlmUsage` 的事件，因此需要先加一个「一次模型调用完成」的通知类型。
- **缓存断裂告警**：内核每轮记录 `system prompt 哈希` + `工具清单哈希` + `第一条变动的消息下标`，
  与上一轮比对，变了就 WARN（可归因到「哪个插件贡献块变了」）。需要在 `PromptAssembler` 上挂每会话状态。
- TUI 状态栏显示命中率（目前只有输入 / 输出）。

#### 验收方式

改前 / 改后各跑一段同构对话，把 `/usage` 的命中率与 DeepSeek 平台的
`prompt_cache_hit_tokens / (hit + miss)` 对账。两者口径现在已经一致（都是「命中 / 总输入」），
因此差异应当很小——差异大就说明解析或累加有问题，而不是厂商在说谎。

#### P1 · 立即可用的配置止损（零代码）

`react.toolOutput.keepRecentMessages: 0`（关闭老化）。详见 §5.4。

#### P2 · system prompt 静态化（内核开点 + todo 插件迁移）

收益最大的一步。内核新增「回合上下文」扩展点，让易变状态以 **append-only** 的方式进入会话；
同时给 `PromptContribution` 增加 `placement`，把贡献分成三层并稳定排序。详见 §5.3。

#### P3 · 老化改为单调、预算触发（内核）

`ToolResultAger` 的触发条件从「距尾部 20 条」改为「**上下文用量越过水位线**」，并保证单调：

- 只在 `usage > agingPercent` 时才老化，一次老化一批，**一旦 stub 永不还原**；
- 同一批次内按绝对下标推进，避免边界随尾部滑动；
- 推荐默认关闭，让压缩承担这件事。

#### P4 · 压缩 cache-safe forking（内核）

`ConversationCompactor.planOf` 复用父请求前缀：

```java
LlmRequest parent = promptAssembler.buildRequest(session, resolvedModel);   // 与父会话逐字节相同
LlmRequest.Builder builder = LlmRequest.builder(modelId)
        .systemPrompt(parent.getSystemPrompt())                                 // 不再用 instructions
        .messages(msgs.subList(0, end) 原样 + LlmMessage.user(instructions))    // 指令追加在末尾
        .tools(parent.getTools())                                              // 工具集相同
        .toolChoice("none");                                                   // 只输出文本
```

- 前缀 = 父会话前缀的**真前缀** → 整段对话按 0.1× 命中，只有指令那几个 token 是新的。
- `ConversationCompactor` 需注入 `PromptAssembler`（`core → core`，依赖方向合法）。
- **「dropped」路径基本消失**：父请求既然刚刚发得出去，它就装得下；只需预留「压缩缓冲」
  （指令 + 摘要输出），因此 `autoCompactPercent` 必须能留出这段余量。
- 若某厂商拒绝「带 tools 的摘要请求」，回退旧路径（保留为 fallback）。

#### P5 · 工具清单稳定（内核少量 + MCP 插件）

- 排序键从「`order` + 注册顺序」改为「`order` + **名称**」，让顺序与插件加载顺序无关。
- MCP：`startupWaitSeconds` 超时后转异步注册会导致会话中工具集变化。改为**先注册轻量 stub**，
  命中 `tools/list_changed` 时通过既有注册窗口更新，而不是在会话中途增删。
- 把「**会话内绝不改变工具集**」写进 `../constraints/react-compact.md`，防止将来回退。
  （现有 Plan 模式的实现已符合，值得在文档里点明。）

#### P6 · 可选：TTL 保活、`prompt_cache_key`

- OpenAI 内存缓存 TTL 5–10 分钟，人工节奏的编码会话容易失效。可考虑低频 keepalive
  （用最小请求复用前缀）——但**写入本身要计费**，是否划算要按实际间隔实测后再定。
- OpenAI 的 `prompt_cache_key` 目前未设置。同一会话的请求路由到同一台机器可提升命中，
  值得作为 provider 配置项加上。

### 5.3 扩展点设计

#### 5.3.1 贡献块分层

```java
package zcd.jellyfish.api.extension;

/**
 * 贡献块的稳定性分层：决定它排在 system prompt 的哪一段。
 * <p>
 * <b>为什么要有分层</b>：厂商的 prompt 缓存是前缀匹配，改一个字节 → 其后全部失效。
 * 把跨会话恒定的内容排在最前、会话内会变的内容排在最后，能让「最容易变的那一小段」只作废它自己之后的部分，
 * 而不是让一次待办更新把整段 system prompt 连同全部历史一起作废。
 *
 * @author zcd
 */
public enum PromptPlacement {

    /** 跨会话、跨轮次逐字节不变（如 {@code @路径} 约定），排在最前。 */
    STATIC,

    /** 会话内不变（项目约定、skills 清单、可委派类型），排在中间。 */
    SESSION,

    /**
     * 会话内可能变。排在 system prompt 最后，且内核会对它的每次变化记一条缓存断裂告警。
     * <p>
     * <b>能用 {@link TurnContextRequest} 就不要用它</b>：这一类无论排在哪，都会作废它之后的全部消息。
     * 保留它的唯一理由是「某些内容必须比对话历史拥有更高优先级」。
     */
    VOLATILE
}
```

`PromptContribution.of(text)` 保持现状语义（默认 `SESSION`），**现有插件不改也不变行为**；
已稳定的三个只需显式声明 `STATIC` / `SESSION` 即可改善排序。

#### 5.3.2 回合上下文（易变状态的合法去处）

```java
package zcd.jellyfish.api.extension;

/**
 * 回合上下文请求：内核在把用户本轮输入写进会话<b>之前</b>构造，询问「有没有需要随本轮一起送达的即时状态」。
 * <p>
 * <b>与 {@link PromptContributionRequest} 的分工</b>：后者的产物进 system prompt——那是缓存前缀的
 * 第 0 个 token，改一次就把整个请求作废；本扩展点的产物被拼进<b>本轮用户消息的头部</b>并随消息落盘，
 * 因此它是 append-only 的：它只影响本轮新产生的 token，对之前已经发送过的全部内容没有任何影响。
 * <p>
 * <b>{@code userInput} 为什么给插件看</b>：插件据此判断「这条状态这一轮到底要不要说」
 * （例如用户没提待办、状态也没变，就不必注入）。
 *
 * @author zcd
 */
public final class TurnContextRequest extends ExtensionRequest<TurnContext> {

    /** 会话标识。 */
    private final String sessionId;

    /** 本轮用户输入原文。 */
    private final String userInput;

    /** 是否嵌套回合（子代理）。 */
    private final boolean nested;

    // 单一可见构造器 + 只读 getter + getRouteKey() 恒为 null（类型级扩展点）
}

/**
 * 回合上下文结果：要拼在本轮用户消息前的一段文本。
 *
 * @author zcd
 */
public final class TurnContext {

    /** 上下文文本，无内容时为 {@code null}。 */
    private final String text;

    // of(String) / empty() / getText() / isEmpty()
}
```

**内核调用点**（`ReActLooper.execute` / `runNested`，在 `sessionManager.appendMessage(userMessage)` 之前）：

```java
TurnContext ctx = turnContextOf(sessionId, input, nested);   // 询问各插件，按 order 拼接
LlmMessage userMessage = LlmMessage.user(merge(ctx.getText(), input));
sessionManager.appendMessage(sessionId, userMessage, null, null);
```

**去重**：内核为每个 `(owner, sessionId)` 记「上次注入文本的哈希」，与本次相同则跳过。
插件返回**全量状态**，内核负责去重。这样 `A→B→A` 会正确再注入，而 `A→A` 不会；
`/resume` 到新进程时缓存为空，最多多注入一次，且仍然是 append-only。

**todo 插件迁移**：从 system prompt 挪到 `TurnContextRequest`；保留 `TodoText.confirmation`
在 tool 结果里的既有行为。**待办改变的那一轮本来就有新 token，把它塞在尾部几乎是免费的。**

### 5.4 配置项设计

**立即可用（已存在，无需改代码）**：

```json
{
  "react": {
    "toolOutput": {
      "keepRecentMessages": 0
    }
  }
}
```

`keepRecentMessages = 0` 即关闭老化（`ToolResultAger.age` 在 `<= 0` 时原样返回）。
这是 R2 的零成本止损口，可用来在自己的环境里 A/B 验证。

**新增（随 P3–P5 落地，用户可见配置类为 `Settings` 后缀）**：

```json
{
  "react": {
    "cache": {
      "agingPercent": 0,
      "compactFork": true,
      "stableToolOrder": true,
      "breakWatch": true
    }
  }
}
```

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `cache.agingPercent` | `0` | `0` = 沿用现状（按距尾部条数、每轮重算）；`> 0` = 只在上下文用量达该百分比时老化，且**单调不可逆** |
| `cache.compactFork` | `true` | 压缩请求复用父会话的 system prompt 与工具定义，指令追加在消息末尾 |
| `cache.stableToolOrder` | `true` | 工具清单按 `order` + 名称稳定排序，与会话、与插件加载顺序无关 |
| `cache.breakWatch` | `true` | system prompt / 工具清单 / 消息前缀变化时记 WARN（诊断用） |

**调参要点（非显然的一条）**：压缩的**次数**就是缓存**重置的次数**，每次重置都要重建整个前缀。
因此「压缩阈值偏高 + 每次压得更狠（`compactKeepRecentMessages` 偏小）」比「频繁小幅压缩」更省缓存。
但这会牺牲最近原文的保真度，需要按使用习惯权衡。同时 `autoCompactPercent` 必须为 P4 的
压缩缓冲留出余量，不能贴到 100%。

### 5.5 预期收益

| 阶段 | 命中率（模拟同构负载） |
| --- | --- |
| 现状 | 0% – 35% |
| +P1（配置止损） | ~35% – 68% |
| +P2（system prompt 静态化） | ~75% – 84% |
| +P3 / P4 | 压缩调用省约 90% 输入；裁剪不再周期性发生 |
| **上限** | **≈84%**（其余是每轮真正新增的 token） |

---

## 6. 验证方法

1. **厂商侧对账（最权威）**：改前 / 改后各跑同一段同构对话，读 DeepSeek 平台的
   `prompt_cache_hit_tokens / (hit + miss)`。
2. **内核侧度量（P0 落地后）**：`/usage` 的命中率与 `MetricsRegistry` 的 gauge。
3. **单测：前缀稳定性回归**。构造 `PromptAssembler` 的连续多轮组装，断言：
   - 会话内 system prompt 逐字节恒定（除压缩事件外）；
   - 消息段是 append-only（第 k 轮的前缀是第 k+1 轮前缀的前缀）；
   - 工具清单在会话内恒定。
   这三条正是 R1 / R2 / R5 的守卫。
4. **量化基准**：把 §4.4 的模拟固化成基准用例，作为每次改动的回归门槛。

---

## 7. 未决项

- DeepSeek 在 V4 上的**实际最小可缓存前缀**（官方 64 token 与社区实测 256–1024 存在差距，官方未更新文档）。
- `cache.agingPercent` 的推荐缺省值需要实测；`keepRecentMessages` 的缺省值是否从 20 改为 0 需先验证。
- P4 落地后，是否存在拒绝「带 tools 的摘要请求」的厂商，需要保留回退路径。
- 会话中途换模型（`SessionModelResolver` 三级回落的副作用）是否需要显式告警。

---

## 附录 A：量化模拟脚本（§4.4 的证据）

这是一个**分析模型**，不是内核测试——它模拟「请求组装」的语义，用来在改动之前把收益算清楚。
P2 / P3 落地后，§6 第 3 条的真实 `PromptAssembler` 前缀稳定性测试应当取代它。

```python
# 模拟 Jellyfish 的请求组装，量化「与上一轮请求的公共前缀」= 理论缓存命中
SYSTEM = 2000          # agent prompt + 项目约定 + skills 等
SYS_VOLATILE = 300     # 待办块（每轮都可能变）
ASSISTANT = 200        # 每轮 assistant 文本/工具调用
TOOL_BIG, TOOL_SMALL = 1200, 150      # 工具结果：大（有信封）/ 小
STUB = 80              # 信封老化后的 stub
KEEP_RECENT = 20       # 默认 keepRecentMessages
ROUNDS = 40


def build(rounds, age=True, todo_churn=0, keep=KEEP_RECENT):
    """返回每轮的 [ (token_count, volatile_sys) ... ] 消息序列 + 系统提示词"""
    reqs = []
    msgs = []  # (tokens, is_envelope_tool)
    for r in range(rounds):
        msgs.append((ASSISTANT, False))
        msgs.append((TOOL_BIG, True))
        msgs.append((TOOL_SMALL, False))
        sys_tokens = SYSTEM + (SYS_VOLATILE if (todo_churn and r % todo_churn == 0 and r > 0) else 0)
        sys_ver = (r // todo_churn) if todo_churn else 0   # 待办一变，system prompt 版本就变
        emitted = []
        boundary = len(msgs) - keep if age and keep > 0 else len(msgs)
        for i, (tok, env) in enumerate(msgs):
            emitted.append(STUB if (i < boundary and env) else tok)
        reqs.append({"sys": sys_ver, "msgs": list(emitted), "total": sys_tokens + sum(emitted)})
    return reqs


def common_prefix(a, b):
    """公共前缀 token 数：系统提示词不同 => 0；否则逐条消息比 token 数"""
    if a["sys"] != b["sys"]:
        return 0
    n = 0
    for x, y in zip(a["msgs"], b["msgs"]):
        if x != y:
            break
        n += x
    return n


def report(name, **kw):
    reqs = build(ROUNDS, **kw)
    hits = [(common_prefix(reqs[i - 1], reqs[i]), reqs[i]["total"]) for i in range(1, len(reqs))]
    tot_hit, tot = sum(h for h, _ in hits), sum(t for _, t in hits)
    s_hit, s_tot = sum(h for h, _ in hits[-20:]), sum(t for _, t in hits[-20:])
    print("%-46s 整体命中 %5.1f%%   稳态(后20轮) %5.1f%%" % (name, 100 * tot_hit / tot, 100 * s_hit / s_tot))


print("== 默认 keepRecentMessages=20（现状）==")
report("现状：老化 + 待办常驻 system（每轮变）", age=True, todo_churn=1)
report("现状：老化 + 待办每 5 轮变一次", age=True, todo_churn=5)
print()
print("== 只关闭老化（keepRecentMessages=0），system 仍含待办 ==")
report("关闭老化 + 待办每轮变", age=False, todo_churn=1)
report("关闭老化 + 待办每 5 轮变一次", age=False, todo_churn=5)
print()
print("== 老化关闭 + 待办移出 system prompt（追加到消息尾部）==")
report("append-only（理想）", age=False, todo_churn=0)
```

输出见 §4.4 的表。
