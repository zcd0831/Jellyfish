# 约束：三种外壳与可观测性

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改 CLI / TUI / Server 外壳、可观测性、跨模块约定前必读**。
> 用法见 [README.md](../../README.md)，接口与鉴权见 [server-api.md](../server-api.md)，
> 背景见 [architecture.md](../architecture.md)。只写规则与事实；推导与实测数据在对应类的注释里。

## 三个外壳的共同约定

- **三种启动模式、一个内核**：`-cli` / `-tui` / `-server` 共用 main、DI、`AgentHarness`、`CommandManager`，
  差异收在 `RunMode`；界面层放 `jellyfish-tui`、服务层放 `jellyfish-server`
  （**放进 cli 会形成 `cli → 子模块 → cli` 循环依赖**）。
- **分流顺序由内核统一，外壳不再自己排**：提交入口是
  `ConversationService.submit(sessionId, text, source, SubmissionPolicy, listener)`，顺序固定为
  **命令判定 → 输入改写 → 输入指令 → 起回合**（见 `docs/design/kernel-conversation-runtime.md`）。
  外壳只声明自己的 `SubmissionPolicy`（是否执行命令 / 是否解析 `!` `@` / 无会话时建不建），
  并 `switch` 返回的 `Submission.Kind` 做呈现；**外壳不得再自己复制这套顺序**——
  它此前在三个外壳各写一份，并已漂移出「`!` 只有 TUI 有」「命令谓词两套」两个问题。
  外壳仍自行截胡**外壳自有命令**（`/exit` `/ui` `/thinking` `/toolargs` `/mouse`：它们不进内核注册表），
  且在进入 `submit` 之前做。
- **每轮现读当前会话**：外壳不缓存 sessionId（`SessionManager.current()`），这样 `/new` `/resume` 之后立刻生效。
  需要会话的路径由 `SubmissionPolicy.sessions` 决定是「按需建」还是「必须有」：
  TUI 用 `CREATE_IF_NEEDED`（首页延迟建），CLI / Server 用 `REQUIRE_EXISTING`。
  启动期 `SessionBootstrap` 保证 CLI 有当前会话——裸 `-tui`（进首页）与 `-server`（按 id 寻址、启动期不预建）例外。
- **CLI 输出契约**：回答与命令结果走 stdout，诊断 / 进度 / 日志走 stderr；回答按轮缓冲、收敛时整体写出。
  **退出码 `0/2/3/4/6/7` 是机器契约**（5 随占位模式一起移除）。工具轨迹默认只给工具名（`→ 名字` / `← 名字 完成`），
  加 `--show-tool-args` 后参数**单行、200 码点封顶**地跟在工具名后（`stderr` 会被重定向、进 CI 日志，
  不能像 TUI 那样折行，也不能无界）；在该旗标的说明里**点明可能含敏感信息**（不脱敏，与 Codex 一致）；
  该旗标只被 `-cli` 接受，`-tui` / `-server` 退 `2`（与 `-p` / `--agent` 同口径）。
- **外壳种类是插件可见的进程级事实**：`Launcher` 在 `bootstrap()` **之前**把 `RuntimeInfo` 写进
  `RuntimeInfoHolder`（插件在 `start()` 里就会读它），取值如下表。**写入必须早于 bootstrap**，
  晚一步插件读到的就是缺省的「未知外壳」。

  | 外壳 | `shell` | `hasUI` | `supportsApproval` | `interactive` |
  | --- | --- | --- | --- | --- |
  | `-cli` | `CLI` | `false` | `false` | `System.console() != null` |
  | `-tui` | `TUI` | `true` | `true` | `System.console() != null` |
  | `-server` | `SERVER` | `false` | `true` | `System.console() != null` |

  **`supportsApproval` 是静态语义**（「外壳**具备**审批通道吗」），不表示此刻有审批者在线；
  进程内它是常量，因此不为它配 `RuntimeInfoChangedEvent`。不经过外壳启动流程的用法（测试、嵌入式集成）
  拿到的是 `RuntimeInfo.unknown()`（外壳记作 `CLI`、其余全 `false`）而不是 `null`——
  给一个保守的缺省比让调用方伪造一个外壳要好。
  它**不打开会话与工作目录**：四个字段全是进程级事实，`sessionId` / `agentId` / `cwd` 仍然拿不到。

## TUI

### 视图与线程

- **视图 = 会话投影 + `InflightTurn` 暂存区**：消息区**不持有第二份消息列表**，由 `TranscriptProjector`
  纯函数投影；流式当前轮尚不在会话里，**必须暂存且随回合终结清空**。工具轨迹不进暂存区。
- **线程契约**：可靠 lane（`ShellStreams`）的订阅者回调**不在渲染线程上**（`react` / `llm-stream`，
  以及工具的输出泵线程），界面状态**只在渲染线程变更**；订阅者（`TuiTurnListener`）只向线程安全暂存区追加，
  `Esc` 由渲染线程调 `TurnRegistry.cancel(sessionId)`。
- **订阅用 `subscribeAll`**：TUI 从首页进入，而会话是 `submit` 内部才建的——提交之前拿不到会话标识。
  进程内它是唯一消费者，所有事件写同一个暂存区，不需要按会话过滤。
- **消息区必须是单个 `richText`**：布局子元素到 120～180 个即性能断崖；滚动偏移是 `ChatState` 自己的字段。

### 分流与首页

- **TUI 从首页进入**：无当前会话时显示 `HomeSplash`（53 列方块字字标，终端窄于 53 列时整块退回单行文本）
  与 `HomeHints`（字标下方 dim 引导提示，**放不下就整行丢弃而不是裁切**，与状态栏片段同一口径）；
  两者连同外壳提示按消息区高度垂直居中。**分流完全交给命令域，外壳不维护名字表**——
  `sessionRequired=false` 的命令（`/help` `/new` `/session` `/resume` `/delete` `/reload`，
  以及降级的 `/model` `/agent` `/mode`）在首页直接执行且不建会话，其余命令与普通文本先建会话；
  首页手敲一条 `sessionRequired=true` 的命令（`/compact`）**按约定当作用户的话发给模型**（不额外提示）。
- **输入指令（`!`）排在命令域之后、对话之前**，且一律先建会话（结果要落进历史）——它的分流也是问内核
  （`InputDirectives.resolve`），外壳同样不维护标记名单。
- **首页状态栏按「`SessionDefaults` → 配置默认值」两级解析**（`resolvePendingModel`）——不读第一级的话，
  用户刚在首页改完会看到状态栏仍显示旧值，与实际将要用到的对不上。

### 渲染

- **markdown 只在 assistant 正文渲染**：只借 commonmark 的 AST，块级映射与换行自己写；用户消息与工具轨迹
  保持纯文本。**commonmark 锁 `0.21.0`**（0.22.0 起是 Java 11 字节码），渲染器**永不抛异常**、
  解析前先过滤控制字符。**表格画成网格**：按列量宽（`DisplayWidth`）后对齐排布，格子放不下时在列内**折行**
  （不按列截断——表格里最长的往往正是最该看的那一列），列数多到每列连 `MIN_COLUMN_WIDTH`（3 列）都分不到时
  退回**等宽代码块**降级。表头加粗、单元格内的行内样式（行内代码 / 加粗 / 删除线）照旧保留。
- **思考过程默认折叠、可全局展开**（`Ctrl+T` / `/thinking` / `--show-thinking`）：思考随消息落会话
  （`SessionMessage.thinking`），**不进 `LlmMessage`**。开关**必须纳入投影的「未变化」判据**。
- **工具调用参数进轨迹行**（`⎿ 工具名 · 结果摘要 · 失败后缀 · 调用参数`）：参数的唯一来源是会话里
  assistant 的 `toolCalls`（tool 消息只有工具名与结果），它在执行**之前**就已落库，因此运行期、回合结束后、
  `-resume` 之后都是同一份文本，界面**不缓存第二份参数**；配对靠 `toolCallId`（一轮多个调用与结果在消息列表里
  并不相邻）。工具名 / 摘要 / 失败后缀的顺序与文案**不变**，参数排在最后——失败后缀不能被参数挤出显示范围。
  参数按显示列**折行**并受行数上限约束（折叠 `MAX_ARGUMENT_ROWS` / 展开 `MAX_ARGUMENT_ROWS_EXPANDED`），
  超出以一行明确省略提示收尾，**不要退回按列截断**（长命令的尾巴恰恰是最需要看见的部分）。
- **参数不做脱敏**（三个显示面同口径，与 Codex 的 `--verbose` 一致）：外壳按参数名猜不出哪个是密钥，
  遮不住真正会出事的地方（`shell.command` / `write_file.content`），却会给出「已经打过码了」的错误安全感。
  要遮蔽时应由工具或用户**显式声明**，而不是由外壳猜；渲染一律走
  `infra/support/ToolArgumentsText`（控制字符过滤照旧，那是安全要求，与脱敏不是一回事）。
- **工具参数默认折叠、可全局展开**（`Ctrl+E` / `/toolargs`）：与思考过程同一套全局开关语义
  （没有「选中某条调用」的交互模型），开关同样**必须纳入投影的「未变化」判据**。
- **审批浮层优先级高于二级选择页与补全面板**，可见时吞掉其余按键；`Esc` 是「拒绝 + 中断回合」；
  详情区必须过滤控制字符、超长参数折行而不是截断、参数**按原文显示**（不脱敏，理由见上）。
- **TUI 命令输出按时间戳插进消息流**（`ShellNotice`），不贴投影末尾；外观是独立块
  （`❯` 回显 + `⎿`/`!`/`✗` 三态），不要套 dim 或工具轨迹前缀。
  **插件通知走同一条通道**（`ShellNotice.plugin`，无 `command` 可回显）：给插件硬塞一个 owner 作命令原文，
  会被渲染成用户输入行（`❯` 前缀），那是误导；来源归因交给 `getOwner()`，只用于按来源封顶。
- **输入框必须自己定位终端硬件光标**：框架的 `TextArea.renderWithCursor` 只把光标格反显（改缓冲区样式），
  **不调用 `Frame.setCursorPosition`**（框架里只有单行 `TextInput` 会），硬件光标因此停在上一帧最后写入的
  那一格——首屏时是状态栏末列。输入法的预编辑串由终端画在硬件光标处，从末列换行会把整屏顶上去，
  而应用的缓冲区不知道屏幕滚动过，之后所有差量重绘立刻错位（输入框上移、旧画面残留）。因此
  `ChatInputView` 自带定位，且**显示行判定必须与 `TextArea` 同口径**（折行边界处硬件光标与软件反显
  不能差一格）。

### 插件界面贡献

- **两条 lane 共用一套订阅形状**（`core/conversation/ShellStreams`）：
  `subscribe` / `subscribeAll` 是**可靠 lane**（内核回合事件，同步扇出、不丢、不乱序），
  `subscribeShell` 是**尽力 lane**（插件贡献，队列在 `ShellIngress` 里、每 owner 有界、可合并、可丢）。
  **可丢性挂在通道上，不在事件上**，因此外壳不必逐条判断；两条 lane 也不需互相排序。
- **尽力 lane 要外壳自己来取**：`drainShell()` 在**调用者线程**上把积压的贡献交给订阅者。
  TUI 在 `render()` 帧首调它（交付点就是那一帧），Server 在 SSE 写循环里调它。
  插件推得再多也拖不住任何线程（队列满即丢）；相反地，订阅者慢只会拖住自己那一帧。
- **TUI 的贡献落地**：`NOTICE` → `ChatState.appendPluginNotice`（进与命令结果同一个缓冲区，
  按时间戳参与投影，但**不是会话消息**），`INVALIDATED` → `uiCache.invalidate()`。
  文本在落地前必须过 `ControlChars.strip`——`TranscriptProjector` 对提示块**不做**过滤（只对工具输出做）。
- **插件通知按来源封顶**（`ChatState.MAX_NOTICES_PER_PLUGIN`，初值 3）：全局的 `MAX_NOTICES` 封的是总量，
  不是「谁先来谁占满」；淘汰的是**该来源最早的那一条**（插件通知多是进度类，留着旧的不如留新的）。
- **推送事件、拉取状态**：面板 / 状态栏的**内容**仍走 `UiContributions` 拉取；推送只负责说
  「内容脏了」（`INVALIDATED`）。把状态也改成推送会立刻产生第二份真源。
- **插件只能贡献渲染无关数据**（状态栏片段是拼接型、面板是独占型），由 `infra/ui/UiContributions` 收集；
  **插件不可能自己造 TamboUI 组件**（子优先类加载器会让 `Element` 不是同一个 Class）。
- **区域归外壳**：`preferredRegion` 只是软建议，落位在 `UiPlacement`（用户指定优先于 order）；
  `/ui` 是外壳自有命令，清单要列出被挤下去的候选名字。
- **五边版式全用 `length(n)`，不用 `percent` / `fill`**，账本由 `ChatLayout` 自己算（侧栏单侧 `[20, W/4]`、
  合计 ≤ `W/3`、`W < 80` 隐藏，不足先砍右栏）；收敛规则都为消息区让路，模态浮层打开时面板让位。
- **UI 贡献「失效时收集」而非每帧**：触发源＝首帧、会话切换、回合开始、回合收敛、命令执行后、
  `UiInvalidatedEvent`、`ShellContribution.INVALIDATED`、`PluginStateChangedEvent`；**漏一个就是内容永久陈旧**。
  缓存用版本号而非布尔 dirty。其中「回合开始 / 收敛」由可靠 lane 自带的回合事件覆盖（外壳在收到时就置脏），
  「会话切换 / 命令执行后」是外壳自己发起的动作（本地即可知道），因此不必额外造失效事件。
- **UI 贡献处理器三条硬约束**：纯只读、不得发布 `UiInvalidatedEvent`、必须快；单处理器抛错只记 WARN 跳过。
- **文本段有两个正交维度**：`UiSegmentKind`（是什么 → **修饰**：粗体 / 反显 / 下划线 / 斜体）与
  `UiEmphasis`（该多抢眼 → **颜色**）。**种类不许改颜色**，否则插件就无法在「这是一行小标题」的同时
  说「这行是错误」。词汇表只收**纯样式**：`PROGRESS` 这类需要解析文本的（要数值、要分隔点）不进 api
  ——「文本由插件给、外壳反过来解析它」就是两个真源。**映射只在 `UiRender` 一处**。
- **面板与轨迹行只有 TUI 渲染**：`-cli` / `-server` 不消费 `UiSegment`，因此不需要给它们写映射
  （那是死代码，不是「降级」）。
- **工具行渲染提示只改显示**：强调档位、默认折叠、是否显示参数；**改不了轨迹行的文本**（那由工具写进
  `ToolMetadata.KEY_SUMMARY` 的一句话决定）。**全局 `Ctrl+E` 优先于插件的 `showArguments=false`**。
  提示按**工具名**缓存（路由键就是工具名），表按实例传给 `TranscriptProjector`（保持它无依赖）。
- **插件快捷键只派发 `/命令`，不回调插件**：插件因此不需要「被内核调用」这个新能力，而命令域已有的
  审计、`sessionRequired` 判定与错误处理全部复用。键位形状收窄成 `ctrl+[a-z]`（终端事实），
  内核保留键位（`Ctrl+C/S/T/E/O`）拒绝占用；**命令存在性在收集时校验**（注册是活的，
  按注册顺序校验会让加载顺序变成正确性条件）。

### 终端事实与键位

- **TUI 日志必须与终端隔离**：`-tui` 在参数解析后、DI 装配前把 `log4j.configurationFile` 切到 `log4j2-tui.xml`，
  **必须赶在第一个 `Logger` 创建之前**。
- **键位反转：`Enter` 换行、`Ctrl+S` 发送，不要改成修饰键方案**：框架不解析修饰键编码，
  `Shift+Enter` / CSI-u / CSI-27 一律落成 UNKNOWN，裸 `\r` 与 `\n` 同形；Ctrl+字母只能比码点。
- **TUI 启动前必须做终端前置检查**（`System.console() == null` 判据，挂在 `RunMode.checkEnvironment`，
  由 `Launcher` 在启动内核前调用，**不满足退 3**），否则 TamboUI 会永久挂住；逃生门
  `-Djellyfish.tui.skipTerminalCheck=true`。
- **鼠标捕获默认开，且可在运行期交还终端**：滚轮属于鼠标捕获，关着到不了应用；非滚轮鼠标事件一律吞掉以保住焦点。
  代价是终端选择需按修饰键（macOS 的 Option，而 Terminal.app 上是矩形选择，等于没有），因此有两条退路：
  运行期 `Ctrl+O` / `/mouse` 把鼠标交还终端（拖选与 ⌘C 立刻可用，期间滚轮停用，状态栏留标记，
  **退回前若与启动配置不一致必须自己关掉上报，否则终端会带着鼠标模式回到 shell**），
  或整体逃生门 `-Djellyfish.tui.mouseCapture=false`。
  **括号粘贴必须保持打开**，否则多行粘贴被拆成多次提交。

## Server

- **会话一律按 path 里的 id 寻址，不读 `SessionManager.current()`**：那是进程级单指针，多客户端下不成立。
  残留的只有 `/model` `/agent` 的「选中标记」——那两者只读 `current()`，Server 下退化为无标记（外观问题）。
- **启动期不建会话**：`SessionBootstrap.deferCreation` 就是「不是 CLI」——TUI 先进首页、Server 按 id 寻址，
  两者启动期都没有「当前会话」这个概念。**`--agent` / `--model` 只归 CLI**：CLI 不能交互，
  新会话的初始值只能靠参数给；TUI 用 `/agent` `/model` 命令，Server 用 `POST /sessions` 的请求体
  （不再有「服务级默认值」这一层，模型默认值归 `models.json`）。**其余模式带上这些参数一律判用法错误退 2**，
  `-p` / `--show-thinking` 同理——**拒绝而不是静默忽略**。
- **一会话一在途回合（内核不变量）**：`TurnRegistry` 用非重入的 `Semaphore(1)` 占位，且**占位早于 `AgentHarness.chat`**
  （回合任务一提交就 append 用户消息，事后判断冲突已经污染历史）；冲突由 `ConversationService.submit` 抛
  `TurnInProgressException`，Server 映射 409、TUI 显示提示。**槽位的归还在内核**：
  `TurnRegistry.releasing` 把「终态回调」与「归还」绑死，调用方不需要（也不应该）自己写 `try/finally`。
  `POST /sessions/{id}/cancel` 与 TUI 的 `Esc` 都走 `TurnRegistry.cancel(sessionId)`；**取消成功会广播
  `TurnCancelledEvent`**（插件侧唯一的「被打断」信号，同一次取消只报一遍，见 [extensions.md](extensions.md)）。
- **API key 鉴权包在路由外面**（`ApiKeyGuard` 是外层 handler）：逐个处理器里加校验等于「漏一个就是一条攻击面」，
  而「新加接口忘了校验」**没有任何测试能可靠拦住**；包在外面则新接口默认就被保护，例外只能是显式声明的——
  **目前只有 `GET /health`**（探活必须能在没有密钥时工作，且它不含会话正文与路径）。
  它自己先 `dispatch` 到工作线程再写 401，理由与 `Router` 同（**阻塞 I/O 不允许在 IO 线程上**）；
  密钥比较用 `MessageDigest.isEqual` 做**常时比较**。
- **缺省不鉴权是刻意的，但没配密钥时必须留下一条 WARN**（默认日志级别就是 WARN，因此这一档一定看得见；
  配好了走 INFO）：理由见 [server-api.md](../server-api.md#鉴权)。
- **不接受用 query 参数传密钥**（理由见 [server-api.md](../server-api.md#鉴权)）：密钥的来源是 `--api-key`
  或环境变量 `ServerConfig.ENV_API_KEY`（后者优先推荐：argv 会出现在 `ps` 里）。
- **SSE 单写者**：socket 写全在 Undertow 工作线程上循环完成，可靠 lane 的订阅者
  （`SseTurnListener`）只把事件投进**无界队列**；写失败即客户端断连，据此取消回合。
  并发流用 `maxStreams` 封顶（超限 503），保住 `/health` 这类短请求。
- **订阅先于提交**：`turn_start` 与第一批增量由内核发布，晚订阅会丢掉开头那一段。
- **turnId 由内核生成**并随事件一起到达（`Submission.getTurnId()` / `ShellTurnEvent.getTurnId()`）：
  外壳不再自造——自造时它必须在 `chat` 之前就确定，否则早期回调会带 `null`。
- **审批走 HTTP，路由由内核按会话做**：SSE 内嵌 `approval_required` / `approval_resolved`
  （`ApprovalBridge.headFor(sessionId)` 直接取本会话头槽位，不再从全局头里过滤）、
  `GET /approvals` 给晚到的客户端（取跨会话最早的那一条，因为晚到客户端不知道自己是哪个会话）、
  `POST /approvals/{id}` 裁决；**断连时主动拒绝仍待审的那条**，否则 react 线程要阻塞到审批超时。
- **插件贡献也进 SSE，但它是尽力 lane**：`shell_notice` / `shell_invalidated` 两个事件名，
  由 `SseContributionListener` 按会话过滤（`SHELL` scope 发给每一条流）、出线前滤掉控制字符。
  **写循环改成 1 秒一片地等**（原来是等满 `keepaliveSeconds`）：回合事件一到就走，
  而插件贡献的延后最多一秒；keepalive 的语义不变（连续空闲满一个间隔才发一帧注释）。
  交付仍只有一个写者：`drainShell()` 只做同步扇出，写仍全在本线程。
- **头槽位每会话一个**：同会话内语义不变（单槽位 + 队列 + 只对头生效），**会话之间不再互相排队**——
  这是修既有缺陷，不是新语义。
- **关闭顺序由 `JellyfishServer` 自己保证**：它的钩子先停 HTTP、再放行 `awaitShutdown()`，
  使 `run()` 返回后 `Launcher` 的 `finally` 才收内核。
- **绑定失败退 3**（启动条件不具备），不是 4；macOS 上 Undertow 会设 `SO_REUSEPORT`，已占端口仍能绑上——
  **测「绑定失败」用不可用地址，不要用占端口**。

## 可观测性

- **可观测性是纯订阅者，自己绝不发事件**：否则形成「事件 → 指标 → 事件」自激；事件通道统计直接作仪表读取，
  只订阅异步侧。
- **通道是并发派发（核心 2、上限 8）且允许乱序，因此断言计数时「依赖的每一个计数器都要各自等一遍」**：
  等一个总数到位只说明最后那条通知开始了处理，另一个分类项可能还在别的线程手里。**修法不是等更久，
  而是不要假设顺序**；`EventChannelConcurrencyTest` 把「慢订阅者不拖住其它通知」「乱序是允许的」这两条性质钉住。
- **启动顺序**：`eventChannel.start()` 之后、`runtimeConfig.refresh()` 之前启动 `MetricsSubscriber`；
  `shutdown()` 先打健康检查，末尾退订并打指标汇总。
- **诊断输出必须比被诊断对象更稳**：坏仪表跳过、检查项抛错降级为 DOWN、关闭路径日志失败只记 WARN；
  健康检查三档 UP/WARN/DOWN，检查项可插拔由装配根跨层拼装。**刻意不加 `/metrics`**。

## 跨模块约定

- **异常**统一抛 `JellyfishException`；**序列化**统一走 `ObjectMapperWrapper`，**不要直接 new `ObjectMapper`**。
- **请求 / 消息模型**：`LlmRequest` / `LlmMessage` / `LlmTool` 是与厂商无关的统一模型，`LlmRequest` 用 builder 构建。
- **配置类型命名**：项目内部配置类用 `Config` 结尾，暴露给用户的配置类用 `Settings` 结尾。
