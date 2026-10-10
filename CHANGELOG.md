# 变更日志

本文件记录 Jellyfish 内核（`io.github.zcd0831:jellyfish`）所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.1] - 2026-10-10

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
- `CommandResult.handoff(String)`：命令可以只声明一段接力文本、由提交管线接着起回合（命令自己不必执行它）。
  `-server` 的 `CommandResultDto` 随之多一个 `handoff` 字段，客户端要自己 `POST /sessions/{id}/chat` 接上。
- `DelegationQuota` 与 `SubAgentPort.quota()`：编排方在 `spawn` 之前就能问出「本回合还能派几个」；
  `SubAgentOutcome` / `DelegationResult` 带上 run 的归档路径，`task` 未收敛时在回灌末尾给出。
- `PluginContext.parentSessionId(String)`（`default` 方法）：按会话树生效的策略（如 plan 模式只看不改）
  在子代理会话上也要判定得到。会话不存在或标识空白返回 `null`。

### Changed

- **审批与提问的等待超时可以把「超时」这一档关掉**：`permission.approvalTimeoutSeconds` /
  `ask.timeoutSeconds` 写 `0` 表示**永不超时**（一直等到有人裁决 / 作答），不配仍是 120 秒。
  **此前的 `0` 与负数一律被静默换成 120 秒**，于是「想让审批多等一会儿」只能写一个大数
  （超过 3600 秒会给告警），而那个大数终究会到点。现在两条出口语义分开：**`0` = 无限**，
  **负数 = 非法**（回退 120 秒并告警）。两处都走 `ConfigWarningEvent`（来源 `permission` / `ask`）：
  `0` 报一条说明代价，负数报一条说明「写了什么、按什么生效」——原先负数告警只说「非正数」，
  与「我其实想写 100」这件事对不上。提问侧的非法值此前**完全没有告警**（`AskSettings` 没有
  `warnings()`，事件面也没收它），现在与权限侧同口径。
  **代价要明知**：审批是 fail-closed，配 `0` 把它从「等不到人就拒绝」变成「等不到人就一直卡住」；
  两处等待都发生在 `react` 线程上，而那个池只有 8 条线程（= 并发顶层回合上限），
  占住的那条不会自愈——外壳退出（通道关闭）与回合被取消仍会放开。
- **jline 从 3.25.1 升到 3.30.17（`jdk8` 分类器）**（安全，依赖）：3.25.1 落在 jline 那几件 ReDoS 公告的
  受影响区间里（`<3.30.15`：`HISTORY_IGNORE` 的 `CVE-2026-77420`、`less` 视图与内置 `grep` 的
  `CVE-2026-77423`）。jline 是从 `dev.tamboui:tamboui-jline3-backend` 传递进来的，因此 `jellyfish-tui`
  把它**排除掉再显式声明**——classifier 不同的 artifact 在 Maven 眼里是两回事，不排除就会两个 jar
  同时在类路径上。**必须带 `jdk8` 分类器**：3.26.0 起主线 jar 里混着更高版本的字节码（实测主线
  `3.30.17.jar` 里有 **21 个 major 66 即 Java 22 的类**，全是 `org/jline/terminal/impl/ffm/*` 那批
  FFM provider），按需加载在 JDK 1.8 上虽然侥幸能用，但全类路径扫描、类加载校验与某些容器都会撞上；
  `jdk8` 分类器是官方给出的干净那份（整份 jar 里没有一个高于 52 的类）。
  判据 `JlineBackendCompatibilityTest`：来源是 jdk8 那份 + 整份 jar 无高版本类 + dumb 终端能真建起来 +
  TamboUI 后端引用的 jline 类型都还能解析（撤掉分类器即红，失败信息会列出那 21 个类）。
- **取会话快照收成唯一入口**（破坏性，仅直接调用过 `SessionSnapshots.capture(Session)` 的嵌入方）：
  该方法收为包私有，改用 `session.captureSnapshot()`。**为什么**：一致投影（整份读取在同一段临界区）
  此前只是写在注释里的纪律——`SessionSnapshots.capture` 逐个读同步 getter，调用方若忘了持有实例锁，
  快照里就会出现「用量含某条消息、消息列表里却没有它」这种自相矛盾的一对字段，且会随落盘留在磁盘上。
  收窄之后「忘了」这件事**编译不过**，新增调用点也不会走错。内核侧四处调用点（落盘、子代理归档、
  HTTP 响应两处、Spring 查询）已全部改过来——其中归档那处同样落盘，是修漏了的一处。
- **未声明的 `agentId` 由「放行 + 告警」改为「全拒 + 告警」**（行为变更）：`AgentRegistry.policyOf`
  对「绑了一个配置里查不到的身份」返回「全拦」策略，而不是此前的「不受限」。这段路今天可达——
  调用方直接给 `agentId`（`SessionManager.create` 不校验存在性）、落盘恢复出来的会话沿用它当初记下的
  `agentId`、以及 `/reload` 之后 agent 被改名或删除（`refresh` 重建两张表，但不回头校验既有会话）。
  也就是说「配置里改个名字」会让一批旧会话在无人察觉的情况下拿到全部工具权限。**「没绑 agent」那支
  的口径一个字没动**（仍是 fail-open，因为那是「没有身份」而不是「身份不成立」），因此「一个 agent
  都没配」这个合法状态不受影响。告警文案也改成说清后果与修法（原来只说「按不受限处理」）。
- **配置里数值写错会进事件面**：`react` 段（含 `react.toolOutput`）与每个 provider 的 `sampling` 段
  的非法值，如今都把「哪个键、原值多少、为什么非法、按什么处理」发成 `ConfigWarningEvent`。
  此前 `ReactSettings` 的六处回退**全文件没有一个 Logger**（字面静默），`SamplingSettings` 只写日志
  （TUI 下日志只进文件），于是「我调了配置却没变化」与「那个值一开始就被判非法」在外表上一样。
  判定与措辞收在新的 `SettingsGuard`（「回退但不静默」只有一处实现），告警只认「写错了」、不认「没配」。
- **`ConfigReloadedEvent` 带的是「被重启」而不是「被动过」**：它此前传的是
  `touchedPluginIds()`（启动 ∪ 停止 ∪ 重启 ∪ 失败），而字段注释与指标名
  （`config.reloadRestartedPlugins`）、脚本面暴露的名字（`restartedPluginIds`）说的都是「停止后重新启动」，
  于是「一个插件被禁用」在指标与订阅方那里长得像「它被重启了一次」。现在只带重启那一张表；
  `PluginReloadReport.touchedPluginIds()` 随之删掉（它已没有生产调用方，`/reload` 的渲染一直用的是
  四张分开的表）。
- **Undertow `2.2.39.Final` → `2.2.40.Final`**（安全，依赖）：修 `CVE-2026-28367`/`28368`/`28369`
  三件 HTTP 请求走私。**升级依据不是公告**——官方只把 `2.4.0.Final` 列为修复版，而 `2.2.x` 是否
  回移了修复无法从公告判断；依据是 tag `2.2.40.Final` 的 `Connectors.java` 历史里有那条
  `[UNDERTOW-2594][2595][2596] … Switching to strict HTTP parser`（`pom.xml` 里写明了这一点，
  以及升级后必须重跑 `-Pserver-it`）。JDK 1.8 上实测 `-Pserver-it` 绿。同一轮里 `jline` 的 CVE
  **判为不可行**：Maven Central 上最新稳定是 3.26.3，且自 3.26.0 起主线 jar 另发 `-jdk8` 分类器
  （主线已抬到 Java 11+），在 JDK 1.8 上拿不到修复版；Spring Boot 2.7.18 已 EOL 属基座问题，
  需平台级决策。两条都不在本文件里做，理由记在 `CODE-REVIEW.md` 第八节。
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
- **委派不得提权**：子 agent 的工具授权必须是父 agent 的子集，比父宽就整次拒绝委派，并说明是哪个工具
  让它变宽。堵的是「`subagent_type` 由模型自选」——一个受限 agent 只要定向到「没声明权限」（= 不受限）
  的 agent，就能做自己被禁的写文件与跑命令。
- **插件拦截抛错或返回 `null` 按拒绝处理**（此前按「无异议」放行）：异常不再穿出判定链，拒绝理由里
  写明来源 owner。此前这种失败会让外壳拿到 `500`，而不是一句「工具被拒」。
- **`-server` 绑非回环地址却没配 API key 就拒绝启动**（判据是 `InetAddress.isLoopbackAddress()`，
  退出码 `3`；回环仍允许无密钥）；`POST/PUT/PATCH/DELETE` 校验 `Origin`（其次 `Referer`）与请求
  `Host` 一致，不一致回 `403`（两个来源头都缺的 curl 类客户端不受影响）；命令端点开始校验会话存在。
- **`-cli` 的退出码按失败发生的步骤归类**：`bootstrap` 失败退 `3`，会话准备与 `mode.run` 的运行时异常
  退 `4`——此前别的运行时异常会穿到进程入口，被打成「初始化失败」退 `3`。
- 子代理缺省值放宽：`subAgent.maxSpawnsPerTurn` 3 → 12、`treeTokenBudget` 1,500,000 → 6,000,000
  （= 12 × 单 run 的 50 万）。

### Fixed

- **项目级配置里没写的段不再把全局级的同名段顶成缺省值**：`jellyfish.json` 的 `react` / `permission` /
  `subAgent` / `ask` 四段是「项目级整对象覆盖全局级」，而合并层的那句 `project != null ? project : global`
  实际永远走左支——`JellyfishSettings` 把没写的段填成了缺省对象，于是「项目级没写这一段」被当成
  「写了、只是内容全是缺省值」。后果是：只要项目级配置被加载（`--trust-project-config`、TUI 里信任过，
  或 Spring 侧用 `classpath:` 指定，后者不受信任闸管辖），一份只写了别的段的文件就会让全局级配好的
  整段静默失效——例如全局级的 `ask.timeoutSeconds=0`（永不超时）回到 120、`react.maxRounds=9` 回到 16——
  而且**不产生任何告警**，用户只看得到「我配了却没生效」。现在按段级声明标记判定
  （`JellyfishSettings.isPluginsDeclared()` / `isReactDeclared()` / `isPermissionDeclared()` /
  `isSubAgentDeclared()` / `isAskDeclared()`），「没写就整段回退全局级」这句注释终于成立。
  **「不逐字段合并」这条语义一个字没变**：项目级写了这一段（哪怕写成空对象 `"ask": {}`）就整段覆盖，
  段内不与全局级混合。判据是「JSON 里有没有这个键」，而不是「值是否等于缺省」——
  后者会把 `"ask": {"timeoutSeconds": -1}`（非法值回退成缺省）误判成「没写」。
- **异常补上 `serialVersionUID`（七处）**：`CompactionUnavailableException`、`TurnInProgressException`、
  `ApiException`，以及插件侧的 `ScriptCallException` / `ScriptTimeoutException` / `ScriptCancelledException` /
  `ScriptConnectionException` / `ScriptNotHandledException`。**这不是修了一个能触发的故障**——核对过：
  内核没有把异常跨进程序列化的路径（无 RPC、不写进落盘文件），脚本那一侧协议帧里传的是错误码与文本
  而不是 Java 对象。补它的理由是它们都是**公共契约**：哪天真要跨边界传一个异常，缺这个字段会让
  「版本不同」表现为一次反序列化失败，而不是一句可读提示；而子类必须各自声明（序列化机制只看类
  自身声明的那个字段，不继承）。
- **客户端连上 SSE 却不读时，那次卡住的写会被掐断**（N-40 收口，新增配置 `writeTimeoutSeconds`，缺省 60 秒）：
  给监听器装上 `Options.WRITE_TIMEOUT`，到点由 Undertow 关连接并让那次写抛 `IOException`，
  随后走既有的断连收尾——取消回合、关订阅、**归还 stream 许可**。此前没有这道闸，而这条流唯一的写线程
  同时也是它唯一的消费者（连 keepalive 都由它发），于是它停在 `write` 里就再也没人发现、也没人归还许可：
  缺省 `maxStreams=16`，16 个「连上不读」的连接就能让之后所有 `/chat` 一律 503。
  **为什么必须放 `setSocketOption` 而不是 `setServerOption`**：`Options.WRITE_TIMEOUT` 是 XNIO 的**通道选项**，
  `HttpOpenListener` 是从连接自己的选项表里读它并据此装上 `WriteTimeoutStreamSinkConduit`；
  `setServerOption` 放的是 UndertowOptions 那张表，写超时会**静默不生效**（实测：放错表时写线程永远卡在
  `write` 里、连 `Undertow.stop()` 都跟着卡；放对之后 1 秒的时限就把它掐断）。
  **时限必须大于 keepalive 间隔**：实测它不只约束「一次写多久没写完」，也约束「两帧之间最长静默多久」——
  静默超过时限的连接会被判死，因此 60 秒对 15 秒留了 4 倍余量。
  `StalledSseClientTest` 用生产那一段配置真起 Undertow + 真 socket 守着（正反两条：卡住的写被掐断 /
  静默短于时限的流照常活着），把选项放错表或摘掉即红。
- **「有人在订阅、只是它过滤器坏了」不再被报成「没人订阅」**：`EventChannel` 派发时，订阅者的
  过滤器抛错会让命中数停在 0，于是既记 `unmatchedNotifications` 又打「通知无订阅者命中」——
  而事实是有人命中、它自己的过滤器炸了（已计入 `subscriberErrors`）。两条诊断的修法完全不同
  （「这条通知没有受众」vs「我的过滤器配错了」），合成一条会让后者唯一的线索消失。
- **窗口被新回合替换时，恰好卡在那一刻的动作拿到正确的原因码**：`ActionQueue` 的原因码是插件按状态
  分流的凭据，而此前「回合跑完了」与「窗口被同会话新回合顶掉」在竞态路径上都被报成
  `TURN_ENDED_UNREACHED`；后者那条动作本该属于新回合，插件据此可以做别的决定。窗口现在记下自己的
  关闭原因，`offer` 撞上关闭时照实转述。（同一段窗口的两条既有用例随之收紧。）
- **日志这条终端的第四条出口也过滤控制字符**（安全）：落进日志的正是脚本、工具与对面进程喂进来的
  原文（含未捕获异常），而日志同样进终端与文件。此前两轮按渲染器清点出口都没把它算上
  （`SEC-10` 清 `StyledSegment`、`SEC-17` 清 `-cli` 的 `SystemConsoleIO`），于是「往日志里打一行
  带 `ESC` 的东西」能清屏、能伪造日志行。现在两份配置（`log4j2.xml` 的 `Console SYSTEM_ERR` 与
  `log4j2-tui.xml` 的 `RollingFile`）的 pattern 都用 `%replace` 剥掉除 `\n`/`\t` 外的控制字符，
  并加 `alwaysWriteExceptions="false"`（异常栈不再绕过 pattern 里的过滤）。
  `LogLayoutSanitizingTest` **从两份配置里取出 pattern 真渲染**敌意内容来守（撤掉过滤即红 2 条）。
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
- **`TypeRegistry` 的候选缓存改为按版本号失效**：清空若落在「开始收集」与「写回」之间，写回的就是一份
  基于旧表的视图，而它此后一直命中——新注册在该类型下一次变更之前**永远不可见**，且没有一行日志
  （现象是「装完插件，工具 / 命令没了」）。
- **审批 / 提问的队列满时只拒新请求**：此前会摘掉整个排队区，而被摘掉的请求既排不到头、又留在表里，
  用户点了「批准」或作答也无效（`resolve` 只认头槽位），只能各自等满超时。同时结论改为在锁内发布，
  消除「超时线程读到 `null`」那条 NPE 竞态。
- **`@` 引用认转义空白**：`@my\ file.txt` 现在是一个片段，含空格的文件名终于能补全、也能被当成路径
  读对——规则收在新的公共类 `InputReferenceEscapes`（`escape` / `unescape` / `isEscapedAt`），
  输入框补全与引用解析共用同一处。
- **回合的前置语句抛错时不再把回合悬着**：配置读取与作用域开启移入 `try`，失败时补一条终态并归还槽位
  （只在该回合没进过 `runTurn` 时补，避免发出第二条终态）——此前 `-cli` 会挂死，槽位与动作窗口都不归还。
- **看门狗不再把已经收尾的 run 误标成「墙钟截断」**（`markTimedOut()` 与新增的 `markBodyFinished()`
  共用实例锁）；工具结果的落盘文件名加一段唯一片段（调用 id 跨回合复用时撞名，撞上就是「`_path` 在、
  内容不是它」）；`-cli` 的括起粘贴加上 1 MiB 上限（超限整段不插入并发提示）。
- **`-tui` 的 `/reload` 输出不再把同一插件同时列成「插件重启」与「插件启动」两行**；`task` 缺
  `subagent_type` 时补 `REJECTED` 终态（此前停在中间态）；`LlmRequest` 的消息列表逐元素挡 `null`
  （此前的硬 NPE 会把它带下去）。
- **恢复出来的会话带上 `parentSessionId`**：`SessionManager.importSnapshots` 此前用两参构造器发
  `SessionCreatedEvent`，而同一字段在 fork 新建与关会话两条路径都传了，只有「恢复」这条静默丢了。
  同一批里压缩边界的回退改由方法自身抛 `JellyfishException` 拒绝，`compressedCount` 钳到 ≥0
  （它会被 `CompactionAppliedEvent` 广播并累加进指标）。
- **`-tui` 回合进行中放行外壳自有命令**（`/exit`、`/ui`、`/thinking`、`/toolargs`、`/mouse`）：
  此前它们被 `submit()` 里「回合运行中不提交」的检查挡掉，而 `Ctrl+T` 这类按键却照常生效。

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

[Unreleased]: https://github.com/zcd0831/Jellyfish/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/zcd0831/Jellyfish/releases/tag/v0.1.1
[0.1.0]: https://github.com/zcd0831/Jellyfish/releases/tag/v0.1.0
