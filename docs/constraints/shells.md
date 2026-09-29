# 约束：三种外壳与可观测性

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改 CLI / TUI / Server 外壳、可观测性、跨模块约定前必读**。
> 用法见 [README.md](../../README.md)，接口与鉴权见 [server-api.md](../server-api.md)，
> 背景见 [architecture.md](../architecture.md)。只写规则与事实；推导与实测数据在对应类的注释里。

## 三个外壳的共同约定

- **三种启动模式、一个内核**：`-cli` / `-tui` / `-server` 共用 main、DI、`AgentHarness`、`CommandManager`，
  差异收在 `RunMode`；界面层放 `jellyfish-tui`、服务层放 `jellyfish-server`
  （**放进 cli 会形成 `cli → 子模块 → cli` 循环依赖**）。
- **外壳只做两件事**：用 `CommandManager.shouldRunAsCommand(text, 有无会话)` 判「命令还是对话」
  （它内部先做 `isCommand` 的语法判定，再按 `sessionRequired` 与当前上下文决定），每轮现读 `SessionManager.current()`。
  启动期 `SessionBootstrap` 保证有当前会话——裸 `-tui`（进首页）与 `-server`（按 id 寻址、启动期不预建）例外。
- **CLI 输出契约**：回答与命令结果走 stdout，诊断 / 进度 / 日志走 stderr；回答按轮缓冲、收敛时整体写出。
  **退出码 `0/2/3/4/6` 是机器契约**（5 随占位模式一起移除）。

## TUI

### 视图与线程

- **视图 = 会话投影 + `InflightTurn` 暂存区**：消息区**不持有第二份消息列表**，由 `TranscriptProjector`
  纯函数投影；流式当前轮尚不在会话里，**必须暂存且随回合终结清空**。工具轨迹不进暂存区。
- **线程契约**：`ReActListener` 回调**都在 react 池线程**，界面状态**只在渲染线程变更**；
  react 线程只向线程安全暂存区追加并置 volatile 脏标记，`Esc` 中断由渲染线程直接调 `ReActTurn.cancel()`。
- **消息区必须是单个 `richText`**：布局子元素到 120～180 个即性能断崖；滚动偏移是 `ChatState` 自己的字段。

### 分流与首页

- **TUI 从首页进入**：无当前会话时显示 `HomeSplash`（53 列方块字字标，终端窄于 53 列时整块退回单行文本，
  并按消息区高度垂直居中）；**分流完全交给命令域，外壳不维护名字表**——
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
  解析前先过滤控制字符。
- **思考过程默认折叠、可全局展开**（`Ctrl+T` / `/thinking` / `--show-thinking`）：思考随消息落会话
  （`SessionMessage.thinking`），**不进 `LlmMessage`**。开关**必须纳入投影的「未变化」判据**。
- **审批浮层优先级高于二级选择页与补全面板**，可见时吞掉其余按键；`Esc` 是「拒绝 + 中断回合」；
  详情区必须过滤控制字符、超长参数折行而不是截断。
- **TUI 命令输出按时间戳插进消息流**（`ShellNotice`），不贴投影末尾；外观是独立块
  （`❯` 回显 + `⎿`/`!`/`✗` 三态），不要套 dim 或工具轨迹前缀。

### 插件界面贡献

- **插件只能贡献渲染无关数据**（状态栏片段是拼接型、面板是独占型），由 `infra/ui/UiContributions` 收集；
  **插件不可能自己造 TamboUI 组件**（子优先类加载器会让 `Element` 不是同一个 Class）。
- **区域归外壳**：`preferredRegion` 只是软建议，落位在 `UiPlacement`（用户指定优先于 order）；
  `/ui` 是外壳自有命令，清单要列出被挤下去的候选名字。
- **五边版式全用 `length(n)`，不用 `percent` / `fill`**，账本由 `ChatLayout` 自己算（侧栏单侧 `[20, W/4]`、
  合计 ≤ `W/3`、`W < 80` 隐藏，不足先砍右栏）；收敛规则都为消息区让路，模态浮层打开时面板让位。
- **UI 贡献「失效时收集」而非每帧**：触发源＝首帧、会话切换、回合开始、回合收敛、命令执行后、
  `UiInvalidatedEvent`、`PluginStateChangedEvent`；**漏一个就是内容永久陈旧**。缓存用版本号而非布尔 dirty。
- **UI 贡献处理器三条硬约束**：纯只读、不得发布 `UiInvalidatedEvent`、必须快；单处理器抛错只记 WARN 跳过。

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
  残留的只有 `/model` `/agent` `/mode` 的「选中标记」——那些只读 `current()`，Server 下退化为无标记（外观问题）。
- **启动期不建会话**：`SessionBootstrap.deferCreation` 就是「不是 CLI」——TUI 先进首页、Server 按 id 寻址，
  两者启动期都没有「当前会话」这个概念。**`--agent` / `--model` / `--mode` 只归 CLI**：CLI 不能交互，
  新会话的初始值只能靠参数给；TUI 用 `/agent` `/model` `/mode` 命令，Server 用 `POST /sessions` 的请求体
  （不再有「服务级默认值」这一层，模型默认值归 `models.json`）。**其余模式带上这些参数一律判用法错误退 2**，
  `-p` / `--show-thinking` 同理——**拒绝而不是静默忽略**。
- **一会话一在途回合**：`SessionTurns` 用非重入的 `Semaphore(1)` 占位，且**占位早于 `AgentHarness.chat`**
  （回合任务一提交就 append 用户消息，事后判断冲突已经污染历史）；冲突回 409，`POST /sessions/{id}/cancel` 取消。
- **API key 鉴权包在路由外面**（`ApiKeyGuard` 是外层 handler）：逐个处理器里加校验等于「漏一个就是一条攻击面」，
  而「新加接口忘了校验」**没有任何测试能可靠拦住**；包在外面则新接口默认就被保护，例外只能是显式声明的——
  **目前只有 `GET /health`**（探活必须能在没有密钥时工作，且它不含会话正文与路径）。
  它自己先 `dispatch` 到工作线程再写 401，理由与 `Router` 同（**阻塞 I/O 不允许在 IO 线程上**）；
  密钥比较用 `MessageDigest.isEqual` 做**常时比较**。
- **缺省不鉴权是刻意的，但没配密钥时必须留下一条 WARN**（默认日志级别就是 WARN，因此这一档一定看得见；
  配好了走 INFO）：理由见 [server-api.md](../server-api.md#鉴权)。
- **不接受用 query 参数传密钥**（理由见 [server-api.md](../server-api.md#鉴权)）：密钥的来源是 `--api-key`
  或环境变量 `ServerConfig.ENV_API_KEY`（后者优先推荐：argv 会出现在 `ps` 里）。
- **SSE 单写者**：socket 写全在 Undertow 工作线程上循环完成，`react` 线程只把事件投进**无界队列**
  （`SseReActListener`）；写失败即客户端断连，据此取消回合。并发流用 `maxStreams` 封顶（超限 503），
  保住 `/health` 这类短请求。
- **turnId 由外壳生成**，不用 `ReActTurn.getTurnId()`：后者要等 `chat` 返回才拿得到，
  而监听器必须先交出去，否则早期回调会带 `null`。
- **审批走 HTTP，但复用 `ApprovalChannel` 不改内核**：SSE 内嵌 `approval_required` / `approval_resolved`
  （只发属于本会话的头槽位）、`GET /approvals` 给晚到的客户端、`POST /approvals/{id}` 裁决；
  **断连时主动拒绝仍待审的那条**，否则 react 线程要阻塞到审批超时。
- **单槽位是既有语义**：`ApprovalChannel` 全局只有一个头槽位，多会话并发时后面的审批排队——
  **首轮明确不改内核**，如实暴露现状。
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
