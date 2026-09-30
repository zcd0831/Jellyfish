# AGENTS.md

本文件用于指导 AI 编码代理在本仓库中工作。修改代码前请先阅读。

> **本文件是常驻上下文，只放「每次动手前都该知道的规则」**：项目结构、模块边界、必读索引、编码 / 测试 / Git 约定。
> 分域的技术约束与类名 / 字段名 / 边界条件在 `docs/constraints/`；设计决策与推导的理由在 `docs/architecture.md`；
> **推导与实测数据在对应类的注释里，这里只留规则**。未落地与明确不做的部分见 `docs/architecture.md` 的「已知边界与后续项」。

## 文档分工

面向使用者的说明在 `README.md` 与 `docs/`。**同一条事实两处都出现时，用户向的解释与背景以文档为准，
类名、字段名、边界条件与「不要这么做」的约束以 `docs/constraints/` 为准。**

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 安装、构建、三种模式用法、命令速查、FAQ |
| [docs/architecture.md](docs/architecture.md) | 整体架构图、模块边界、各处设计决策的理由、已知边界与后续项 |
| [docs/configuration.md](docs/configuration.md) | 配置全字段、双源合并与优先级 |
| [docs/server-api.md](docs/server-api.md) | REST 接口、SSE 事件、鉴权 |
| [docs/constraints/extensions.md](docs/constraints/extensions.md) | 扩展层（注册表 / 事件通道）、插件运行时与 owner |
| [docs/constraints/session-config.md](docs/constraints/session-config.md) | 会话状态、落盘、配置加载与热更新、模型 / agent 解析 |
| [docs/constraints/permissions.md](docs/constraints/permissions.md) | 权限三层、插件拦截、审批、只读白名单 |
| [docs/constraints/react-compact.md](docs/constraints/react-compact.md) | ReAct 循环、上下文裁剪、压缩、子代理委派 |
| [docs/constraints/tools-output.md](docs/constraints/tools-output.md) | 工具执行、输出截断与落盘、输入指令、命令域 |
| [docs/constraints/shells.md](docs/constraints/shells.md) | CLI / TUI / Server 外壳、可观测性、跨模块约定 |

**改了用户可见行为就同步改文档**：新增命令 / 参数 / 配置字段 / 接口时，`README.md` 与 `docs/` 的对应位置要一起改；
改动某条约束时，回看它所在的 `docs/constraints/` 文档是否还成立。

## 项目概述

Jellyfish 是一个用 Java 1.8 编写的轻量级 AI Agent 工具，通过 PF4J 插件扩展能力（用法见 [README.md](README.md)）。

- 坐标：`zcd:jellyfish:0.0.1-SNAPSHOT`
- 构建：Maven（`pom.xml`）
- 运行环境：JDK 1.8（**不要使用 Java 9+ 的 API 或语法**）

## 常用命令

使用者的构建步骤见 [README.md](README.md) 的「构建」；这里是开发期的完整命令（含 README 不列的 `-Pserver-it`，它跑
Server 模式端到端：真 Undertow + 真内核，走本机回环）。

```bash
mvn -q compile
mvn -q package -DskipTests
mvn -q test                        # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Pserver-it test            # Server 模式端到端（真 Undertow + 真内核，走本机回环）
mvn -q -Dtest=ChatStateTest test   # 单类单测，把类名换成目标测试类
# 插件端到端测试（script-it / shell-it / mcp-it）在独立插件仓库（Jellyfish-Plugins）
```

## 仓库结构与模块边界

Maven 多模块；根 `jellyfish`（`zcd:jellyfish:0.0.1-SNAPSHOT`）是 `packaging=pom` 的聚合与父 POM。
**依赖方向单向，禁止反向或循环**；整体架构图见 [docs/architecture.md](docs/architecture.md) 的「整体架构图」。

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `jellyfish-api` | 插件作者唯一的稳定契约：SPI、扩展点/事件模型、统一异常 | 无 |
| `jellyfish-infra` | 基础设施层全部实现（会话 / agent / 模型 / 权限 / 插件运行时 / 命令域 / UI / 指标 / 配置） | api |
| `jellyfish-core` | 应用层：ReAct 循环与 `AgentHarness` 门面、提示词组装、压缩机制、系统命令、子代理委派 | api、infra |
| `jellyfish-tui` | TUI 外壳：TamboUI 界面、视图投影与滚动、TUI 版 `ReActListener` | api、infra、core |
| `jellyfish-server` | HTTP 外壳：Undertow 上的 REST + SSE、会话按 id 寻址、HTTP 化人工审批 | api、infra、core、undertow-core |
| `jellyfish-cli` | `main`、参数解析、模式分发、Dagger 装配、shade 可执行 jar | api、infra、core、tui、server |

跨语言桥接运行时（原 `jellyfish-script`）与官方插件（tools / session-file / todo / project / compact / shell /
skills / mcp / python / node）都已迁往独立仓库（`Jellyfish-Plugins`）：前者连同 Jackson
（**含 `jackson-module-parameter-names`**）、Apache Commons Exec **shade 进桥接插件的包**，因此本仓库的
classpath 上不出现任何跨语言代码；后者由 `PF4JPluginManager` 运行时从 `config.json` 的 `plugins.roots` 加载，
与内核之间**没有编译期依赖**，因此都不在上面这张表的依赖链里。

包结构（只列包与少数枢纽类；其余类直接读代码）：

```
jellyfish-api/src/main/java/zcd/jellyfish/api/
├── JellyfishException.java     # 统一运行时异常
├── extension/                  # 同步扩展点：请求/结果类型、ToolDescriptor、会话快照
├── event/                      # 事件基类与 notification/ 下的具体通知
├── ui/                         # 插件界面内容的渲染无关模型
└── plugin/                     # 插件 SPI：JellyfishPlugin / PluginContext / PluginDeclaration

jellyfish-infra/src/main/java/zcd/jellyfish/infra/
├── registry/                   # 注册表底座 TypeRegistry
├── extension/                  # 同步派发策略 ExtensionRegistry
├── event/                      # 异步派发策略 EventChannel
├── session/                    # 会话运行态 Session / SessionManager / SessionDefaults / SessionSnapshots
├── agent/                      # Agent 定义注册表 AgentManager / AgentRegistry
├── command/                    # 命令域服务 CommandManager
├── model/                      # 模型注册与路由 ModelManager / SessionModelResolver（会话 → 模型的唯一解释器）
├── llm/                        # LLM 调用抽象与厂商实现
├── plugin/                     # 插件运行时 PF4JPluginManager / PluginRuntimeConfig / JellyfishPluginAdapter
├── permission/                 # 权限判定 PermissionManager / ApprovalChannel / PermissionPolicy / ReadOnlyTools
├── ui/                         # UI 贡献门面 UiContributions
├── metrics/                    # 指标与健康检查 MetricsRegistry / MetricsSubscriber / HealthCheck
├── config/                     # 配置加载与热更新 RuntimeConfig / ConfigReloader
├── tooloutput/                 # 工具结果治理：ToolOutputEnvelope / ToolOutputStore / ToolOutputLimiter
└── support/                    # 序列化封装、类型常量，以及跨外壳共用的展示口径（ControlChars / ToolArgumentsText）

jellyfish-core/src/main/java/zcd/jellyfish/core/
├── AgentHarness.java           # 组装门面（chat 是唯一智能入口）
├── ReActLooper.java            # 思考 → 行动 → 观察（顶层异步 + runNested 内联）
├── ReActTurn / ReActListener / ReActResult
├── RunScope / RunScopes        # 一次顶层回合的委派作用域：层数与派生预算（ThreadLocal）
├── prompt/                     # PromptAssembler / ContextWindow / ToolCatalog / ToolFilter / TokenEstimator / ToolResultAger / CacheBreakWatcher / ToolPairing
├── compact/                    # ConversationCompactor / CompactionPlan / CompactionHealthIndicator
├── tool/                       # ToolExecutor（权限→路由→截断的唯一执行点）/ CancellationTokenSource
├── input/                      # InputDirectives / InputDirectiveRun / InputDirectiveCall / InputReferenceCompletion
├── subagent/                   # 子代理：SubAgentLauncher / TaskTool / SubAgentTools（owner=core）
│                               #   + SubAgentCall / SubAgentOutcome / SubAgentStatus
└── command/                    # SystemCommands（owner=core）

jellyfish-cli/src/main/java/zcd/jellyfish/cli/
├── JellyfishApplication.java   # main；Launcher 模式选择与生命周期
├── console/                    # stdout=回答、stderr=诊断
├── mode/                       # CliRunMode / TuiRunMode / ServerRunMode
└── di/                         # Dagger 组件与 Module

jellyfish-tui/src/main/java/zcd/jellyfish/tui/
├── TuiApp.java                 # 唯一入口：按键路由、回合/命令分流、审批浮层
├── ChatShell / ChatLayout      # 版式与二维账本
├── TranscriptProjector / ChatState / InflightTurn   # 视图投影与状态
├── 其余视图 / 输入 / 插件 UI 类
└── text/                       # DisplayWidth / LineWrapper / MarkdownRenderer
```

资源位置：`default-agent.json` / `jellyfish.md` 在 infra 资源根；`summary-prompt.md` 在压缩插件资源根；
`config.json` / `log4j2*.xml` 在 cli 资源根。

## 改动前的必读索引

**动手前先按这张表读对应文档**——细则不在本文件里，只在 `docs/constraints/` 里：

| 你要改什么 | 先读 |
| --- | --- |
| 工具注册 / 输出截断 / 落盘 / 信封 / 元数据 / 取消令牌 | [constraints/tools-output.md](docs/constraints/tools-output.md) |
| 输入指令 `!` `@` / 命令注册 / `sessionRequired` / 命令审计 | [constraints/tools-output.md](docs/constraints/tools-output.md) |
| 权限判定 / 插件拦截 / 审批 / 只读白名单 / 工具清单过滤 | [constraints/permissions.md](docs/constraints/permissions.md) |
| ReAct 循环 / 轮次 / 上下文裁剪 / 压缩 / 提示词组装 | [constraints/react-compact.md](docs/constraints/react-compact.md) |
| 子代理委派 / `task` / 嵌套回合 / 委派预算 | [constraints/react-compact.md](docs/constraints/react-compact.md) |
| 会话状态 / 落盘 / 恢复 / 瞬时会话 / 用量记账 | [constraints/session-config.md](docs/constraints/session-config.md) |
| 配置字段 / 双源合并 / `/reload` 热更新 / 启动装配顺序 | [constraints/session-config.md](docs/constraints/session-config.md)、[configuration.md](docs/configuration.md) |
| agent 定义 / 提示词 md / 模型解析回落 | [constraints/session-config.md](docs/constraints/session-config.md) |
| 扩展点 / 事件通道 / 插件生命周期 / owner 命名空间 | [constraints/extensions.md](docs/constraints/extensions.md) |
| CLI 输出契约 / 退出码 / TUI 视图与线程 / 键位 / 插件面板 | [constraints/shells.md](docs/constraints/shells.md) |
| Server 路由 / SSE / 鉴权 / 审批桥 | [constraints/shells.md](docs/constraints/shells.md)、[server-api.md](docs/server-api.md) |
| 指标 / 健康检查 | [constraints/shells.md](docs/constraints/shells.md) |

## 内核速览

开工前够用的一句话索引（细节与边界条件全在上面那六份 constraints 里）：

- **`AgentHarness.chat(sessionId, input, listener)` 是外壳唯一智能入口**，委托 `ReActLooper` 在 `react` 线程池
  异步推进。改循环看 [react-compact.md](docs/constraints/react-compact.md)。
- **`SessionManager` 是会话的唯一变更入口**，落盘只有「创建不落盘」与「回合内只标脏」两个例外。
- **`ToolExecutor` 是权限 → 路由 → 截断的唯一执行点**，模型调用与输入指令共用它。
- **`ExtensionRegistry`（同步、不可丢）与 `EventChannel`（异步、可丢）是内核与插件之间唯一边界**；
  判据是「能否丢弃」而不是「有没有返回值」。禁止引入第三方事件总线。
- **权限三层**：核心策略 → PLAN 只读白名单 → 插件拦截；插件拦截三态且**没有 `ALLOW`**，
  审批 fail-closed（无审批者 / 超时 / 中断一律拒绝）。
- **`CommandManager` 无状态、对外壳中立**；「需不需要会话」由命令自己声明（`sessionRequired` 缺省 `true`）。
- **提示词组装**：`PromptAssembler`；裁剪只裁本次请求（`ContextWindow` 成组丢弃），历史一条不动。
- **压缩是插件能力、内核只提供机制**；没有策略插件即整体不可用，不回退内置。
- **子代理**：`SubAgentLauncher` + `TaskTool`（owner=core），嵌套回合**内联在调用线程上跑，绝不进 `react` 池**
  ——这一条是本设计最不能碰的一条。
- **跨边界载荷必须是 `api` 侧快照值类型**，且快照类型恰好只有一个可见构造器；**`-parameters` 不许去掉**。
- **`-tui` / `-server` 的启动期都不建会话**；`--agent` / `--model` / `--mode` / `-p` / `--show-thinking` 只归 CLI，
  其余模式带上它们一律判用法错误退 2（**拒绝而不是静默忽略**）。
- **生命周期收尾顺序**：`flushAll()` → `InputDirectives.close()` → `pluginManager.close()`（落盘经扩展点派发给插件，
  插件一停就没人接了）。

## 代码约定

- **异常**统一抛 `JellyfishException`；**序列化**统一走 `ObjectMapperWrapper`，**不要直接 new `ObjectMapper`**。
- **请求 / 消息模型**：`LlmRequest` / `LlmMessage` / `LlmTool` 是与厂商无关的统一模型，`LlmRequest` 用 builder 构建。
- **配置类型命名**：项目内部配置类用 `Config` 结尾，暴露给用户的配置类用 `Settings` 结尾。

## 已知边界与后续项

三种外壳均已端到端落地；**尚未做**与**明确不做**的清单（含 Server 的「不做 Web 前端 / TLS / 审批多槽位」、
压缩不回退内置、子代理**永久不做上下文 fork**、不做后台子代理与工作流编排等）见
[docs/architecture.md](docs/architecture.md) 的「已知边界与后续项」，**不要把它们当成现存 API**。

## 编码约定

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律使用构造器注入 `@Inject`，不使用字段注入。
- 内核与插件之间的交互只走 `ExtensionRegistry` / `EventChannel`，禁止引入第三方事件总线（如 Guava EventBus）。
- 注释使用中文，说明“为什么”而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释，类注释要加`@author zcd`，方法注释要用`@param`写清楚每个参数、用`@return`写清楚返回值。
- 异常统一抛出 `JellyfishException`。
- 工具方法/常量类使用 `final` + 私有构造器（如 `ProviderTypes`、`LlmClients`）。
- 所有代码均需要满足sonar规范要求。

## 单元测试

- 使用 Junit5 + Mockito 进行单元测试，使用 JaCoCo 收集单元测试覆盖率。
- 单元测试类的包路径与被测试类的路径一致。
- 单元测试类命名统一按`{被测试类命}Test`格式。
- 测试方法命名统一按 `{被测试方法}_should_{预期结果}_when_{条件}` 格式。
- 一个测试方法只验证一个行为，测试独立、可重复、无外部依赖。
- 测试结构遵循 Given/When/Then 或 Arrange/Act/Assert。
- 只 mock 外部依赖或协作者，不 mock 被测类、POJO、DTO。
- 使用 `@ExtendWith(MockitoExtension.class)`，统一 JUnit5 API，不混用 JUnit4。
- 断言使用 JUnit5 `Assertions` 或 AssertJ，异常用 `assertThrows`。
- 多组输入用 `@ParameterizedTest`，覆盖正常、边界、异常场景。
- 单元测试不启动 Spring 容器，不访问数据库、网络等真实外部资源。

## Git 约定

- 只 `git add` 本会话实际修改的文件，禁止 `git add -A` / `git add .`。
- 提交前先 `git status` 确认暂存范围。
- 提交信息格式：`{feat,fix,docs,refactor,chore}[(scope)]: 描述`，描述简洁说明改动。
- 不要提交密钥、真实 apiKey 或本地配置文件。
- 提交代码前必须先得到允许。

## 语言

- 使用中文沟通

## 行为准则

- 开发时必须严格按与用户确认的方案执行，如果开发过程中发现方案有问题，先征求用户意见，禁止私自变更方案。
