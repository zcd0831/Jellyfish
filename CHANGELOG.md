# 变更日志

本文件记录 Jellyfish 内核（`io.github.zcd0831:jellyfish`）所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **配置里拼错字段名不再无声**：绑定向来容忍未知字段（配置文件要向前兼容），代价是拼错**完全没有提示**——
  用户写了 `"erros": {...}` 却以为配置生效了，现场表现只是「配置明明写了却没作用」。现在读取配置时会比对
  字段名，发现内核不认识的字段就发一条 `ConfigWarningEvent` 并打出 WARN 行，带上文件路径与字段名。
  只比字段名、不做类型转换（环境变量占位符与值本身写错各有各的报错路径，不混进这条）；
  自由格式的位置一概不看（`vendorBody`、逐插件配置段这类键名由使用方决定），宁可漏报也不报假警。
- **`ConfigWarningReporter`**：把 `ConfigWarningEvent` 渲染成日志行的订阅者。配置层只发事件的约定不变
  （呈现交给订阅方），但此前**唯一的订阅方是指标计数器**——「配置文件缺失」「项目级配置未信任」
  「未知的 agent 标识」这些提示其实都到不了用户眼前，关停时那句 `config.warnings=N` 只说数量不说内容。
  一条没人看得见的告警等于没有。渲染前过 `ControlChars.singleLine`：告警里的字段名来自用户的 JSON，
  带 `ESC` 或换行的键名能清屏、能伪造日志行。
- `PluginContext.configScope()` 与 `PluginContext.globalConfiguration()`（两个 `default` 方法）：
  插件由此能问出「我这个配置段是哪一级给的」，以及「只由全局级决定的那一份是什么」。
  两级合并时同名插件段是整对象替换，因此项目级一旦写了某个插件段，`configuration()` 给的就是项目级那份；
  而有一类键（提示内联上限、加载目录范围）只能认全局级——它们收紧的是一个安全边界，不该由随仓库变化
  的内容决定。两个都要给，是因为「来源是项目级就整段忽略」会把用户在全局级设过的值一起丢掉。
  默认实现退回合并值，因此老容器上的行为与改造前一致。
- `--trust-project-config` 启动参数：信任并加载**项目级**配置（`./.jellyfish/*.json`），仅对本次进程有效、
  不落盘。`-tui` 下改由启动时的确认框询问，可选「加载并记住」（写入
  `~/.jellyfish/trusted-project-configs.json`）或「仅本次加载」。
- `subAgent.maxQueuedRuns`：并发满员时允许排队的 run 数上限（缺省 64）。此前 `agent-run` 池没有等待队列，
  一次派出的 run 多于 `maxConcurrentRuns` 时，多出来的当场以失败落终态；现在它们排队等许可，
  只有「线程 + 等待区都满」才拒绝，且拒绝理由里说明该调哪个键。
- `PluginContext.ownerSessionId(String)`（`default` 方法）与 `SessionManager.ownerSessionId(String)`：
  问「这份数据 / 这次调用归哪个用户会话」。沿父链只穿 `EPHEMERAL`（子代理的临时会话）、遇到用户会话就停，
  因此子代理的活算在派它的用户会话上（嵌套委派照样穿过去），而分支（`FORKED`）会话归自己。
  这是 `SessionKind` 的第二次收口：`parentSessionId` 只回答「从哪派生」，
  「归谁所有」这件事此前被各处按「有没有父」自行推断，而那个推断在分支会话上是错的。
  `default` 实现返回入参本身，老容器上的行为与改造前一致。

### Changed

- **`DaggerJellyfishComponent.create()` 换成了构建者**（破坏性，仅嵌入方）：`AppConfig` 不再由 `ConfigModule`
  固定从 `classpath:config.json` 读，改为由调用方注入——
  `DaggerJellyfishComponent.builder().appConfig(...).build()`。想要原来那份来源（classpath）时用
  `ConfigModule.loadDefault()`。**为什么改**：手工装配侧（`JellyfishAssembler`）一直是调用方传入，
  而 Dagger 侧固定读 classpath，「两种装法交付同一契约」这句话在**配置来源**上并不成立——
  而配置来源是部署事实，本就该由各外壳决定（CLI 要的是 classpath，Spring 侧要的是它自己的来源）。
  现在两侧都由调用方给，`JellyfishAssemblerTest` 里的行为等价断言也改成喂**同一份**配置。
- **`InputDirectives.submit` / `start` 多了一个「结束通知」参数**（破坏性，仅内核内部与嵌入方）：
  `submit(sessionId, input, listener, completion)`，`completion` 在指令结束（成功 / 失败 / 取消）时
  恰好回调一次。参数**必须**在提交时给（而不是拿到句柄后再注册）——指令可能短到在你拿到句柄之前就结束，
  事后注册会漏掉那一次。`InputDirectiveRun` 的公开构造成了「外部手动组装」接缝，它没有提交方，
  因此不产生通知。
- **`TurnRegistry.bind(String, ReActTurn)` 换成 `bind(Slot, ReActTurn)`**（破坏性，仅内核内部与嵌入方）：
  槽位是「这次登记还算不算数」的唯一依据，不带槽位的登记在「回合先结束、句柄后到达」时是错的
  （见 Fixed 里那条）。**没有保留旧重载——那是为了不留一条会写坏在途表的路径**。
- **`RunScheduler` 实现了 `AutoCloseable`**（新增 `close()`）：嵌入方自己装配内核时要记得调它，
  `AgentHarness.shutdown()` 已代为调用。
- **审批与提问的 HTTP 端点改为按会话寻址**（破坏性）：裁决/作答走
  `POST /sessions/{id}/approvals/{requestId}` 与 `POST /sessions/{id}/asks/{requestId}`——
  要求该 `requestId` 属于路径里那个会话、且是它的头槽位，否则 `404`。此前是
  `POST /approvals/{id}` / `POST /asks/{id}`：内核的通道只认「某会话的头槽位」、不校验调用方说的会话，
  于是路径/参数里的会话只是个装饰，拿别人的 requestId 递进来会被照单执行（提问那条更重：
  答案会作为工具结果原文进别人的模型上下文）。**只读的发现入口 `GET /approvals` / `GET /asks` 保留**
  （跨会话最早一条，带 `sessionId`）：子代理的审批落在它自己的会话上，按主会话订阅的流看不到它，
  删掉它那条审批就只能等超时。新增 `GET /sessions/{id}/approvals` / `GET /sessions/{id}/asks`
  看本会话的待办项，两者都先校验会话存在（不存在回 `404`）。
- **`-server` 的命令端点加了闸门**（破坏性）：`/new`、`/resume`、`/reload`、`/session`、`/delete`、`/rm`
  这类「改进程级状态或按参数操作别的会话」的命令回 `403 COMMAND_NOT_ALLOWED`。本外壳按会话 id 寻址，
  建会话与删会话都有对应端点，这些命令在这里没有意义；它们仍可用于 TUI / CLI。
  `GET /commands` 照旧列出全部命令（清单是清单，闸门是闸门）。
- **带请求体的端点要求 `Content-Type: application/json`**（破坏性）：声明了别的媒体类型回 `415`。
  挡的是浏览器能跨站发出的那三种简单请求（`text/plain`、`x-www-form-urlencoded`、`multipart/form-data`）——
  它们不触发预检，副作用照常发生。只用 `curl -d` 的调用方要补一个
  `-H 'Content-Type: application/json'`；没声明这个头的调用方不受影响。
- **项目级配置默认不加载**（破坏性）：它按进程当前目录读取，因此内容取决于「在哪个仓库里启动」，
  而它能改写 provider 的 `baseUrl` / `apiKey`（同名 provider 整对象替换）、新增 agent（未声明权限即不受限）、
  改落盘目录与插件配置——一个 `git clone` 下来的目录就足以改变运行行为。现需显式信任，
  判据是「文件绝对路径 + 内容指纹」：内容一变即失效，避免一次 `git pull` 之后沿用旧的信任。
  未信任时跳过并发出 `ConfigWarningEvent` 说明解锁方式；`classpath:` 形式的项目级路径不受此闸管辖
  （它属于运行构件本身，与全局级同性质）。**只读全局级配置的使用者不受影响。**
- `--show-thinking` 收紧为**只归 `-cli`**（破坏性）：`-tui` 带上它此前被接受、并真的以展开态启动；
  现在与 `--agent` / `--show-tool-args` 同口径，判用法错误退 `2`——TUI 的展开是运行期开关
  （`Ctrl+T` / `/thinking`），启动参数在界面上是多余的。随之 `TuiApp` 不再收初始展开态参数。
- `-server` 下 `--agent` / `--model` 的行为不变（一直判用法错误退 `2`），但此前解析器的注释、错误消息与
  `ServerRunMode` 的类注释称它们「降级为新建会话的默认值」——那句话与实现相反，且只在「只给 `--session`」
  时露出，正好是用户最可能照着试的场合。现统一为「新建会话的 agent 与模型由 `POST /sessions` 请求体给定」。
- 子代理墙钟（`subAgent.runTimeoutMillis`）改为**从 run 真正开始执行起算**：排队等许可的时间不再计入它，
  否则「一次派得多」会让排在后面的 run 一开头就被判超时（表现为「没跑一轮就被记为截断」）。
- 子代理未收敛（`TRUNCATED`）时的回灌文本首行原样给出原因（轮数上限 / token 预算 / 墙钟），
  工具侧首行改为中性的 `[子代理 x 未收敛 · N 轮]`：此前一律写成「达到轮数上限」，
  与实际原因不符，也会把排查带到错误的方向。
- `agent-run` 线程池改为按上界固定（`maxConcurrentRuns × (maxDepth + 1)`）并常驻、不再空闲回收：
  引入队列后按需扩容不会发生（JDK 只在队列满时才扩线程），会把并发反过来压成一两条线程。
- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号 0.1.1；父 POM 与 `jellyfish-api` / `jellyfish-infra` /
  `jellyfish-core` / `jellyfish-di` 已发布到 Maven Central，接入方不必再本地 `install`。
  Java 包名仍是 `zcd.jellyfish.*`——坐标与包名不要求一致。
- 新增 `release` profile（`-P release`）承载 source / javadoc / GPG 签名 / Central 发布四个插件，
  日常构建不加载它们。

### Fixed

- **`RunScheduler` 有了显式关闭，关停不再把在途 / 排队的 run 丢下**：关停时此前只关线程池
  （子代理 run 跑在自己的 `agent-run` 池上），而这一步有两个后果：**在途 run 会继续跑下去**——
  它还会写子会话、还会调已经停掉的插件（AGENTS 明令禁止）；而 `shutdownNow()` 会把**排队等许可**的任务
  直接丢掉，那些 run 从此没有人来给它们落终态，**等它们的父回合永久挂住**（拿不到结果的表现是挂住，
  不是一条错误）。现在 `RunScheduler.close()` 是一条明确的收尾顺序：立旗（关停之后新的委派一律以
  「内核正在关闭」失败，**不再被说成「排队已满」**）→ 取消在途 run → 丢掉排队任务 → 有界等待 2 秒 →
  把仍未收敛的按 `CANCELLED` 就地收尾 → 关看门狗；`AgentHarness.shutdown()` 在 `flushAll()` 与
  摘插件**之前**调它。
- **`-cli` / `-server` 里「回合在拿到句柄之前就结束」不再让 `Esc` 失效**：`TurnRegistry.acquire` 与
  `bind` 之间隔着一次异步提交（`AgentHarness.chat` 把任务交给 `react` 池就返回），而回合完全可能在返回
  之前就收敛——会话不存在、前置语句抛错、被插件在开始前拦下都会走终态回调，槽位随之归还。那种迟到的
  句柄此前会被照原样写进在途表：轻则留下一个「已结束却仍被当成在途」的条目，重则在交错中**盖掉下一个
  回合**，于是取消键指向一个已结束的回合，**用户按 `Esc` 什么都中断不了**（而屏幕上那一轮还在跑）。
  现在登记必须带上槽位（`turns.bind(slot, turn)`），槽位已归还就不再登记；在途表按槽位身份删除，
  迟到的归也不会误删下一个占用者。
- **输入指令（`!`）的事件流现在有终态，且提交被拒时不再把异常穿过外壳**：指令的实时输出与回合事件
  走同一条可靠通道，而那条通道的契约是「每个标识的事件流恰好一条终态」——指令此前只有中间事件、没有
  终态，按契约实现的订阅者（判 `isTerminal` 收尾）会一直等一个不会来的事件。现在指令结束时发恰好一条
  终态（`Esc` 取消 → `cancelled`，其余 → `completed`），结束通知在**提交执行之前**就交进去，
  因此不存在「指令跑得太快、注册晚了就漏掉」的窗口。另一处：指令执行队列满时抛的是
  `RejectedExecutionException`——它不在 `ConversationService.submit` 的契约里，会一路穿到外壳；
  现在抛 `JellyfishException`（说明是队列满、可稍后重试），并把那条永远不会被执行的条目从在途表里摘掉。

- **`-server` 的 SSE 流不再漏掉嵌套子代理的 run**：一条 SSE 流只该看到自己那条会话里的子代理，因此
  按会话过滤是对的——但过滤用的是 `run.parentSessionId`，也就是「**谁派的它**」。委派可以嵌套
  （`subAgent.maxDepth ≥ 2`），那时孙代理的直接父是另一个子代理的**临时会话**，于是那些 run
  **一条都不会发出去**：用户在浏览器上看到「什么都没发生」，而实际上有一批活在跑。
  现在过滤问内核要「这个 run 归哪个用户会话」（与插件按会话归属数据用的是同一条规则）。
- **同一个「归谁所有」在三个地方各写了一遍，其中两处写错**：会话有两种「父」——子代理的临时会话
  （该归到派它的用户会话）与用户自己分出来的分支会话（**归自己**），而两者都带着 `parentSessionId`。
  按「有没有父」判归属会让**分支会话的数据写进源会话**（用户分出一条来试另一条路，结果两家串在一起）。
  现在这条规则由 `SessionManager.ownerSessionId` 一处实现，插件侧入口是
  `PluginContext.ownerSessionId`；沿父链只穿临时会话，并且**能穿多层**（嵌套委派下不能只看直接父）。
  父链走不动时停在能走到的那一层，不新增会抛错的查询路径。

- **流式请求不再可能永久挂住，也不会把半句话当完整答案**：流式客户端此前把读超时设成 0
  （注释理由是「流式响应可能长时间没有数据」），而那等于对端静默断连时读取一直阻塞——回合永久挂住，
  并一直占着一条 stream 许可。现在读超时是 **300 秒空闲**（只对单次读操作生效，因此语义是
  「这么久没有收到任何数据」；思考类模型在首字之前的安静不会误伤）。
  同时补上**收尾判定**：读到流结束标记（`[DONE]` / `message_stop`）**或**拿到结束原因，都算正常收尾；
  两者都没有才把结果标成 `isIncomplete()`——形状与「正常答完」一样的半句话，此前会被静默当成完整回答落库。
  它算进 `isTruncated()`，因此 CLI 的退出码、TUI 的终态、`ReActResult.notice` 都自动生效；
  提示措辞与「被输出上限截断」分开（这一种该重发，不是去调 `maxOutputTokens`）。
  **兼容厂商不受影响**：有 `finish_reason` 但不发 `[DONE]` 的写法仍算正常收尾。
- **流式响应的事件与单行都加了上限**：单个 SSE 事件 8 MiB、单行 1 MiB。此前事件的累积缓冲与行读取
  都没有上限，一个不回换行、一直吐 `data:` 的对端能把内存撑爆。
- **`-server` 的 SSE 写循环不再占用工作线程**：写 socket 会阻塞，而阻塞写没有可用的超时——客户端连上
  之后不读，写就一等等到天荒地老。这条循环此前跑在 Undertow 工作线程上，而工作线程还要服务 `/health`、
  `/commands` 这类短请求（缺省 `maxStreams=16` 而工作线程下限是 8，八条流就足以把它们排到队尾）。
  现在它跑在专用线程池（`jellyfish-sse-N`，守护线程）上，池容量取 `maxStreams`，
  因此「拿到并发流许可一定拿得到线程」；`stop()` 时只 `shutdownNow`，不等它们收敛。
  代价说明：慢客户端仍会占住它那一条许可与一个线程，直到断开——`maxStreams` 就是用来限制这个的。
- **MCP 子进程的输出加了行长上限**（4 MiB）：stdout（协议通道）与 stderr 哨兵此前都用
  `BufferedReader.readLine()`，它会把一整行读进内存。对面是用户从 npm/pip 拉下来的第三方 server，
  一个不换行、一直吐的进程能直接把宿主 JVM 撑爆——而它连「恶意」都不需要，一句把整个数据库 dump 到
  stderr 的日志就够了。超过上限即报错并结束该行读取。
- **`-cli` 的输出过了控制字符过滤**（安全修复）：此前 CLI 的 stdout / stderr 是**裸写**——
  它自己不打任何控制字符（前缀用空格缩进、无 ANSI 颜色），但写出去的文本几乎都来自不可信来源：
  模型回答、工具输出正文（`read_file` 读到一个含 `ESC` 的文件、`grep` 命中含 `ESC` 的行就够）、
  异常消息、命令回显。终端把 `ESC` 当控制序列引导符，一段 `ESC[2J` 就能清屏、`ESC]0;…BEL` 能改
  窗口标题——而 CLI 屏幕上的诊断正是人读来判断「刚才发生了什么」的东西。过滤收口在
  `SystemConsoleIO` 的三个写出方法（该外壳唯一往终端写字节的地方；模块内无一处直连 `System.out`）：
  流式的 `writeOut` / `writeErr` 走 `strip`（保留换行，增量诊断靠它断行），
  整行的 `writeErrLine` 走 `singleLine`（单行语义下换行是注入手段）。
  **判据取「文本从哪里来」，与「写到哪里去」无关**：stdout 重定向时下游不是终端、`ESC` 没有攻击性，
  但 JDK 1.8 没有可靠的终端判定，而「有时过滤有时不过滤」会让行为随运行环境变化；
  代价是重定向到文件时回答里的控制字符也会被剔掉（刻意取舍）。
  顺带把「压成单行」这条规则收进 `ControlChars.singleLine`，与 `-server` 的日志行共用一份实现。
- **回灌给模型的落盘路径不再带真实用户名**：工具结果被截断时，信封里的 `_path`（以及子代理归档
  那条「完整记录见 …」）给的是绝对路径，于是本机的用户名与主目录结构进入上下文——它会随会话落盘、
  被导出、被转发给上游模型，而它对「回查这个文件」毫无用处。现在主目录内的路径缩写成 `~` 形式
  （`HomePaths.abbreviate`，与 `expand` 是一对互逆变换，放在一起以免规则漂移），
  同时文件工具认 `~`（`ToolPaths.expandHome`）——**只缩不放宽可用性**：信封给的是不是一个能直接
  交给 `read_file` 的路径，是这条改动成不成立的前提。
- **`-server` 的错误响应不再回显请求原文**：`500` 此前直接回 `e.getMessage()`
  （可能是内部路径、上游地址、甚至请求里带过来的片段），`404`/`400` 回显请求路径、会话 id、
  agent / 模型名、命令原文与命令名、`Content-Type`、`requestId`。这些都进了客户端、日志、代理与监控，
  而未清洗的原文能伪造日志行、改终端显示；与「会话不存在」相关的答复还须与「存在但无权」逐字节相同。
  现在文案固定、原文只进日志，且经 `LogText.singleLine` 压成单行 + 限长（换行换空格、控制字符剥掉、
  超长截断）。`ApiException` 的消息（服务端自己的话）照旧回。
- **终端注入的落地点收口到一处**：进屏幕的文本来自不可信来源（模型、工具输出、插件贡献、文件名），
  而原先的过滤散在各个渲染器里——面板行、状态栏片段、工具名、文件名候选都各漏了一处。现在过滤放在
  `StyledSegment` 的构造器上（所有渲染路径的汇合点），新增路径自动被覆盖。走不到那一层的输入框
  （它直接交 `TextArea` 渲染）单独处理：粘贴、整体回填、逐字符键入三处都过滤。
- **`ControlChars.strip` 加了「无控制字符即原样返回」的快速路径**：它现在跑在渲染帧里，每帧每段都要
  过一遍；干净文本不再为一次过滤白复制一份字符串。
- **落盘权限收到只有本人**：会话正文与工具输出原先按默认 umask 落地（常见 0644，同机其他用户可读）。
  现在新建的文件是 600、新建的目录层级是 700（已存在的层级不动——那是用户的盘）。会话临时文件改用
  `NOFOLLOW_LINKS`：名字固定，若那里被预置了同名符号链接，普通 open 会跟着它写到别处去。
  不支持的平台（Windows）跳过权限设置，行为保持原样。
- **上游重定向不再能把密钥送去别的主机**：Claude 的密钥走 `x-api-key`、Gemini 走 `x-goog-api-key`，
  而 OkHttp 在跨主机重定向时只剥 `Authorization`——一个上游 `302` 就足以把这两个头送到任意主机。
  现在全局关掉重定向（`followRedirects` 与 `followSslRedirects` 都关，只关前者仍会跟 `http→https`）。
  **这是破坏性变更**：上游若真回 `301/302`，请求会当场失败，错误信息里写明「重定向被禁止」与目标
  主机与路径（不复制 `Location` 的 query，那里可能挂着一次性凭据），据此把 `baseUrl` 改成最终地址即可。
  顺带把 OkHttpClient 的构造收敛到 `LlmClients.newHttpClient()` 一处：Dagger 侧与手写装配侧
  原先各有一份逐字段相同的复制品，安全取舍不该有两个落点。
- **「取不到 agent 策略」不再静默放行**：会话绑了一个从未声明的 `agentId` 时，`policyOf` 仍按不受限
  处理（fail-open 口径不变），但会发一条 `ConfigWarningEvent` 说明这件事——此前这种会话享受着「无限制」
  而外表上看不出任何异常，「agent 名写错了」与「权限本来就这么宽」完全长得一样。同一个标识只报一次
  （它跑在每次工具调用的同步路径上），且逐条告警的标识数有上限（`agentId` 可来自会话参数），
  配置刷新后重新计数。

## [0.1.0] - 2026-10-07

### Added

- 三种运行模式：`-cli` 单次问答、`-tui` 交互式终端界面（TamboUI）、`-server` HTTP 服务（Undertow，
  REST + SSE）；不带任何参数时默认进入 `-tui`。
- 命令行参数：`-p/--print`、`--session`、`--agent`、`--model`、`--port`、`--host`、`--show-thinking`、
  `--show-tool-args`、`--verbose`、`-h/--help`、`-V/--version`。
- 系统命令：`/help`、`/new`、`/session`、`/resume`、`/model`、`/agent`、`/status`、`/usage`、`/delete`、
  `/compact`、`/reload`；外壳侧另有 `/ui`、`/thinking`、`/toolargs`、`/mouse`、`/exit`。
- 人工审批通道：权限拦截为三态裁定（含 `ASK`）。`-tui` 弹审批浮层、`-server` 经 SSE `approval_required`
  与 `POST /approvals/{requestId}` 裁决、`-cli` 没有审批者时一律按拒绝处理，绝不静默放行。
- 向用户提问的通道（`ask_user`）：三外壳均已接入，答案作为工具结果原文进入该会话的模型上下文。
- 权限模式由插件提供：内核不内置只读 / plan 模式，`plan` 模式由官方插件实现。
- 插件与内核之间的唯一边界是 `ExtensionRegistry`（同步、不可丢）与 `EventChannel`（异步、可丢）。
- 插件注册窗口为插件的整个存活期（不限 `start()`），`stop()` 之后一切注册与发布当场失败。
- 插件注册支持 owner 命名空间回收，插件上下文支持子命名空间派生。
- 扩展点：命令只读候选查询（`CommandOptions`）、输入层任意文本改写与短路、工具执行参数改写与结果整形
  （参数改写排在权限判定之前）、工具清单冻结点隐藏工具与显式重建、提示词回合上下文、LLM 缓存治理。
- 插件 UI 贡献：面板、状态栏片段、`/ui` 版式切换；可给文本段标语义种类、给工具行加提示、
  占用 `Ctrl+字母` 快捷键。
- 插件主动动作通道（动作只投进正在跑的回合），以及插件可见的运行时信息快照（外壳种类、审批能力）。
- 插件可接管新的 provider type，模型目录动态发现。
- 会话扩展条目与会话分支；会话由内核统一管理，命令自行声明是否需要会话（`sessionRequired`）。
- 工具清单按 `order` + 名称稳定排序，并按会话冻结（MCP 中途注册不改变会话内的清单）。
- 工具结果：结构化元数据 + 单行摘要（轨迹行显示「读了什么 / 改了什么」）、结构感知截断与超量落盘卸载、
  按水位触发的老化（缺省 70）。
- 工具调用支持取消信号，执行期输出实时转发给外壳并显示在轨迹行上。
- 工具调用参数进入显示面：`-cli` 用 `--show-tool-args`，TUI 用 `Ctrl+E` 或 `/toolargs`。
- 输入框的 `!命令` 手动执行与 `@` 文件引用入口。
- 子代理委派：内核原生 `task` 工具、嵌套回合、独立的 `agent-run` 线程池与 governor 许可门控、
  运行面板与 run 归档、面向插件的委派端口。
- 生命周期钩子（可取消）与「回合被拦下」一档独立终态。
- 配置：`/reload` 热更新（模型 / agent / 插件按差异重启）、`~` 展开、用户数据目录统一到 `~/.jellyfish`、
  区分「字段缺失」与「显式空数组」。
- `models.json` 参数能力：`sampling`（采样七项）、`vendorBody` / `vendorHeaders` 直通段、
  `maxTokensField`；`agents.json` 支持工具权限声明。
- LLM 缓存对齐：provider 侧缓存路由键与 TTL 保活、缓存断裂观察器，`/usage` 展示缓存命中率。
- 提示词按稳定性分层组装；`/compact` 摘要以出站合成消息实现，system prompt 在会话内恒定。
- 服务器模式（`-server`）：会话按 id 寻址、API key 鉴权（除 `GET /health` 外全部接口校验）、
  SSE 实时输出 `tool_output`、HTTP 化人工审批。
- TUI：assistant 正文按 Markdown 渲染、命令补全与两级选择页、滚轮滚动、插件面板版式、
  表格按列宽画成网格、首页方块字标与延迟建会话、无终端时快速失败、
  日志滚动（单文件上限 10MB、保留 5 档）。
- 指标采集与健康检查。
- `jellyfish-di` 装配模块：Dagger 组件 `DaggerJellyfishComponent` + 手工装配工厂 `JellyfishAssembler`，
  两种装法交付同一个 `JellyfishRuntime` 门面。
- 官方插件与脚本插件运行时在独立仓库 `Jellyfish-Plugins`。

[Unreleased]: https://github.com/zcd0831/Jellyfish/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zcd0831/Jellyfish/releases/tag/v0.1.0
