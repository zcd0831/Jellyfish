# 约束：ReAct 循环、上下文与压缩、子代理

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改 ReAct 循环、上下文裁剪、压缩、子代理委派前必读**。
> 对外口径见 [architecture.md](../architecture.md) 与 [configuration.md](../configuration.md)。
> 只写规则与事实；推导与实测数据在对应类的注释里。

## ReAct 循环

- **`AgentHarness.chat(sessionId, input, listener)` 是外壳唯一智能入口**，委托 `ReActLooper` 在 `react` 线程池
  异步推进；**工具失败一律转成 tool 结果回灌，只有模型调用本身失败才上抛**。
- **react 池线程数上限 8**（即并发回合上限），队列 128，空闲回收 60 秒。
- **嵌套回合内联在调用线程上跑，绝不进 `react` 池**：调用它的工具调用此刻正占着一条 `react` 线程，
  把嵌套任务再排回同一个池里，8 条线程就能被并发父回合占满并互相等死。
  **这条是本设计最不能碰的一条**——改回提交线程池会让测试**挂死**（不是断言失败），
  因此嵌套用例带 `@Timeout` 兜底。

## 提示词布局与缓存

- **system prompt 按「稳定性」分层拼接，不按注册顺序**：`PromptPlacement` 的声明顺序
  （`STATIC` → `SESSION` → `VOLATILE`）就是拼接顺序，同一层内保持 `order` 升序。
  理由就是下一条：厂商的 prompt 缓存是**前缀匹配**，一块放在第几位直接决定「它一变要作废多少内容」。
- **易变状态不进 system prompt，随本轮用户消息走**：`TurnContextRequest` 的产物由 `ReActLooper`
  拼进本轮用户消息并**随消息落盘**，因此是 append-only 的——它只影响本轮新产生的 token。
  而 system prompt 是缓存前缀的**第 0 个 token**，把它放进去意味着「待办勾掉一项」要作废整个请求
  （连同全部历史）。判据只看「会话内会不会变」：不会变 → 提示词贡献；会变 → 回合上下文。
- **`PromptContribution.of(text)` 的缺省分层是 `SESSION`**：不改的老插件行为与引入分层之前完全一致。
- **回合上下文不做核内去重**：内核每轮都问一遍，需不需要去重由插件自己判断。
  核内去重会引入一个难查的失败模式——上一次注入的内容可能已经落进被压缩掉的那一段，
  于是模型从此再也看不到那份状态，而日志里什么都看不出来。
- **可缓存前缀的实际断裂会被观察并记日志**：`CacheBreakWatcher` 对比同一会话相邻两轮，
  指出断在哪一层（system prompt / 工具清单 / 第几条消息起）。从稳定变为断裂的那一轮记 WARN，
  持续期间降到 DEBUG，恢复后再断会重新告警。

## 上下文裁剪

- **裁剪只裁本次请求**：`ContextWindow` 按 `contextLength - maxOutputTokens - contextReserveTokens` 从最旧
  **成组**丢弃（toolCalls 与结果同生共死），**Session 历史一条不动**；模型未配 `contextLength` 时不裁剪。
- **`ContextWindow` 对 `tool` 消息不做逐字符截断**，直接替成 stub（老化规则见
  [tools-output.md](tools-output.md#上下文老化)）。
- **上下文老化排在机械裁剪之前**。

## 消息序列的工具调用配对约束

- **出站序列必须满足两条厂商共同强制的规则**：每条 `tool` 消息都有前置的 `assistant(toolCalls)`；每条
  `assistant(toolCalls)` 都紧跟齐它的全部结果。违反任一条，厂商以 400 拒掉**整次请求**，而失败会一直重复
  ——现场表现是「换模型、改配置都救不回来」。判据与推导见 `core/prompt/ToolPairing` 的注释。
- **压缩边界必须落在工具调用组的边界上**：待保留段的起点先按条数算出（`messages.size() - keepRecent`），
  若该位置是 `tool` 消息，就**向前退到组开头**——`keepRecent` 是「**至少**保留最近多少条」，多留一组不违背它。
  不退的话切出来的请求会以孤儿 `tool` 消息开头。对齐后退到可压范围下界时，按「没有可压历史」处理。
- **组装请求时再兜一层**：`PromptAssembler` 出站前跳过开头的孤儿 `tool` 消息、丢弃结尾悬空的
  `assistant(toolCalls)`（结果一条都没落盘）。兜的是旧版本写下的 / 手工改过的会话。
- **回合被取消时必须给未执行的工具调用补结果**：`assistant(toolCalls)` 在**执行工具之前**落盘，
  因此在工具执行前被取消就会留下悬空调用。`ReActLooper` 用下标循环记住执行到哪，取消时为剩下的补上
  「已取消」结果并标 `terminal=CANCELLED`（不标的话界面会把没跑过的工具渲染成正常完成）。
- **`ToolExecutor` 不抛错，因此取消是唯一需要补的来源**：参数解析失败与工具抛错都转成失败结果回灌，
  循环不会因工具失败而提前退出。

## 压缩

- **`/compact` 非破坏式、滚动摘要**：消息一条不删，只记
  `Session.compaction = {boundaryMessageId, summary, droppedMessageCount}`，**失败无副作用**；
  每次只压「上次边界之后、再留 `keepRecent` 条」的那段，并把上一份摘要一起喂回，**边界只向后移**。
- **单次压缩、装不下就丢最旧**：待压范围超预算时从最旧侧丢弃，被丢弃条数如实上报并落盘
  （`droppedMessageCount`）；**至少进摘要 1 条**。
- **摘要是一条每次现算的出站合成消息，不再是 system prompt 里的一块**：它排在压缩边界之后、被保留历史之前，
  **不落盘**。这样 system prompt 在整个会话里逐字节恒定（只剩 agent 提示词与插件贡献块），
  「system prompt 一变」于是可以用作 bug 信号。角色用 `user`，紧随其后也是 `user` 时并入其中——
  既避开 Claude/Gemini 把 system 消息上提回顶层的行为，也避开 Anthropic 拒绝连续 user 的限制。
- **触发两条**：`/compact`（MANUAL），或每轮组装时自动压（AUTO）——用量达 `react.autoCompactPercent`
  （缺省 80，写 0 关闭）或本次已被机械裁剪。自动压缩跑在本轮调用旁边不阻塞，**无范围时零成本**。
- **`/compact` 只起头不等结果，且只有无参执行与 `preview` 两种形态**：跑在自持 `compact` 线程池，
  外壳每帧轮询 `status(sessionId)`（状态带触发原因），摘要调用的 token 经 `recordUsage` 记账但**不留消息**；
  不做用户可见档位，「没什么可压」报 ERROR，`preview` 同情况返回 OK 且**连模型都不解析**。

### 压缩是插件能力、内核只提供机制

- **`CompactionStrategyRequest` → `CompactionStrategy`** 给摘要指令与两个数量参数；没有插件即整体不可用、
  **不回退内置**，`isAvailable()` 只查注册表，自动那一路第一次用不了时记一次 WARN + `ConfigWarningEvent`。
- **插件拿不到消息正文、发起模型调用的能力、否决权**；策略合并取「order 最小且声明了该字段」的那一个，
  数值由内核钳制（保留 `[0, 消息总数]`、摘要上限 `[200, 20000]`）；处理器**必须只读且快，不得发布事件**。
- **摘要指令是插件自带资源 `summary-prompt.md`**，用插件自己的类加载器启动期读完；占位符 `{maxSummaryChars}`
  由内核替换，缺占位符只告警不失败。
- **插件上下文只走 system prompt**：`PromptContributionRequest` 按 order 用 `\n\n` 拼接，
  **不追加进 messages**；单个处理器抛错只记 WARN 跳过。

## 子代理（嵌套回合）

### 它是内核能力

- **不是插件**：子代理改的是「循环可以调用自己」——**循环的结构**，而不是增加一片叶子能力；
  且 `task` 的类型清单要由 `AgentManager` 现算，插件拿不到。形态与系统命令一致：内核以 `owner=core` 注册
  （`core/subagent/SubAgentTools`），插件要替换必须显式声明 `override`。
- **开关关掉时连工具一起摘掉，因此它必须自己听 `ConfigReloadedEvent`**：只把清单变空是不够的——模型依然看得到
  `task`，却没有一个合法的 `subagent_type` 可传，于是每个会话白调一次。而注册虽然已不再限于启动窗口，
  但这一步仍必须在启动期完成：`task` 要在插件启动前占住名字，插件要覆盖它得先有东西可覆盖。
  不听重载事件的话这个开关就得等下次重启才生效（这条结构上是硬约束：`ConfigReloader` 在 infra，而
  `SubAgentTools` 在 core，**infra 不可能反向知道它**）。重算是 best-effort（事件可丢），丢了的表现是外观陈旧
  而**不是放行**——委派本身仍会被 `SubAgentLauncher` 拒掉。
- **清单贡献里仍要再读一次开关**：重算与「组装本轮请求」之间有一段窗口（`/reload` 刚改完配置、重算事件还在队列里），
  那时若只靠注册状态，清单会多宣传一个已关掉的能力。

### 隔离与准入

- **子代理与主会话除了传入的任务之外相互隔离**（fresh-only，**没有 fork，且不做**）：它看不到父会话的消息、
  工具选择与模型；拿到的只有自己那份 `AgentDefinition`（提示词 / 权限 / 偏好模型）、项目约定（`AGENTS.md`）
  与这段任务原文。代价是「挑战我刚才的想法」这类对话条件型用法只能靠调用方把背景写进 `task.prompt`。
- **准入全部排在副作用之前**：开关、任务非空、回合作用域、层数、预算、类型存在且 `delegatable`、非委派给自己、
  模型可解析——**一个被拒绝的委派不建会话、不发事件**。
- **`REJECTED`（换个参数就能修）与 `FAILED`（已经跑起来但出错）分开**，否则模型会对「类型写错了」也去重试。
- **递归两道上限 + 一道授权**：`subAgent.maxDepth` 挡「一条链多深」，`subAgent.maxSpawnsPerTurn` 挡
  「一层扇出多少」（两者正交，只有其中一个都不够）；「子代理能不能再委派」由它自己的 `allowedTools` 是否含
  `task`（未声明 = 不限制）叠加在深度上。
- **作用域是一回合一账，不是一次委派一账**：`RunScope`（深度 + 已派生数）由 `ReActLooper.execute` 在顶层回合
  开闭、`runNested` 进出；react 池线程会被复用，因此**必须**在 `finally` 里清掉。
  **它放在 `core` 而不是 `core/subagent`**：依赖方向必须是 `core.subagent → core`。
- **子代理的工具清单按它自己的 agent 配置收窄**（`ToolFilter`），否则它会看到 `write_file`、调用、被拒，
  白跑一轮。**过滤只随嵌套回合传递，主会话路径传 `ToolFilter.none()`**。
- **清单过滤的判据不重写，而是复用执行期判定**——见 [permissions.md](permissions.md#与工具清单过滤的关系)。

### 结果与呈现

- **轨迹行上的标识走 `ToolMetadata.KEY_SUMMARY`，不是靠界面认工具名**：`task` 填一句
  `子代理 scout · 3 轮 · 123456 tok`，TUI 与 CLI 各自接在已有格式后面（两边都只做「有没有摘要」这一个判断）。
  - 摘要里**不带状态词**（取消 / 失败 / 未开始都已经有后缀在说）。
  - 轮数与 token **都只在跑过的情况下写**：`FAILED` / `REJECTED` 按构造就是 0，而前者可能已经跑了几轮才抛错，
    与其写不准的数字不如不说。
  - token 写**精确值不缩写**：状态栏那份 1000 进位缩写是另一个模块里服务于每帧重画版面的，为这一个小输出把它
    抽到共享位置代价大于收益，而精确值另有一个好处——能与 `/usage` 里的数字直接对上。
- **`TRUNCATED` 是唯一需要在摘要里额外说一句的状态**：它确实跑完了、只是没收敛，因此 `failed()` 为假、
  界面上没有任何警示后缀可用——「结论不完整」只能写在这里。
- **回灌文本首行写结论**（`[子代理 X 已完成 · N 轮]` / `[子代理未开始]`），失败与拒绝还填
  `ToolMetadata.KEY_TERMINAL` 让界面渲染警示标记——那个键的约定是「缺省 = 正常跑完」，取值本身是工具自己的字符串。
- **子代理的结果正文不进外壳**：TUI 的完成轨迹只有一行（`⎿ task · 子代理 scout · 3 轮 · 123456 tok`，
  CLI 是 `← task 完成（26 字符） · 子代理 scout · 3 轮 · 123456 tok`），报告正文只在回灌给模型的那条 tool 消息里。
  这是对所有工具的一贯设计（正文往往是一整篇报告，塞进消息区会把对话刷爆），摘要键正好补上「屏幕上少了正文之后，
  我刚才能看到的东西还在不在」。
- **进度只转「工具调用行」，不转正文增量**：写进 `ToolCallOutput` 旁路（`ToolOutputSink`），
  子代理刷 20 段文本会让界面变成两份交织的流；正文在结束时整段回灌。
- **`task` 的类型清单走提示词贡献而不是工具 enum**：`ToolDescriptor` 在注册那刻就固定了，而可委派 agent 会随
  `/reload` 变；贡献每轮现算，天然跟随配置。没有可委派类型时贡献为空。
- **用量归集到父会话且在 `finally` 里只记日志**：子会话马上被关掉，那些 token 是真花掉的；但 `finally` 里的异常
  会顶掉已经跑出来的结果，账目不准是小事。`SessionUsage.plus(SessionUsage)` 一并带上调用次数——压成一次会让
  「这一轮花了多少来回」失真。
