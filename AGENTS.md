# AGENTS.md

本文件用于指导 AI 编码代理在本仓库中工作。修改代码前请先阅读。

> **本文件是常驻上下文，只放「每次动手前都该知道的规则」**：项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。
> 细节不在本文件里：面向使用者的说明在 `README.md`，跨模块的规范与约束在 `docs/constraints.md`，
> 推导与实测数据在对应类的 javadoc 里。

## 项目描述

Jellyfish 是一个用 Java 1.8 编写的轻量级 AI Agent 工具，通过 PF4J 插件扩展能力。

- **三种外壳，一个内核**：`-cli` 单次问答、`-tui` 交互终端（TamboUI）、`-server` HTTP（Undertow + REST/SSE）。
- **工具即能力**：模型能读写文件、跑命令、搜代码，工具全部由插件提供，每次调用都过权限判定。
- **插件与内核无编译期依赖**：插件是独立打包的 PF4J jar，运行时经 `jellyfish-api` 的 SPI 接入。
- **官方插件不在本仓库**：它们在 `Jellyfish-Plugins` 仓库（含插件开发教程）。
- 坐标 `io.github.zcd0831:jellyfish:0.1.1`，构建用 Maven，运行环境 **JDK 1.8**（**不要使用 Java 9+ 的 API 或语法**）。

## 常用命令

```bash
mvn -q compile
mvn -q package -DskipTests
mvn -q test                        # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Pserver-it test            # Server 模式端到端（真 Undertow + 真内核，走本机回环）
mvn -q -Dtest=ChatStateTest test   # 单类单测，把类名换成目标测试类
# 插件端到端测试（script-it / shell-it / mcp-it）在独立插件仓库（Jellyfish-Plugins）
```

## 技术架构

Maven 多模块；根 `jellyfish` 是 `packaging=pom` 的聚合与父 POM。
**依赖方向单向，禁止反向或循环**：

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `jellyfish-api` | 插件作者唯一的稳定契约：SPI、扩展点 / 事件模型、统一异常 | 无 |
| `jellyfish-infra` | 基础设施层全部实现（会话 / agent / 模型 / 权限 / 插件运行时 / 命令域 / UI / 外壳贡献信箱 / 指标 / 配置） | api |
| `jellyfish-core` | 应用层：会话提交管线、在途回合表、外壳通道门面、ReAct 循环、提示词组装、压缩机制、系统命令、子代理委派 | api、infra |
| `jellyfish-tui` | TUI 外壳：TamboUI 界面、视图投影与滚动 | api、infra、core |
| `jellyfish-server` | HTTP 外壳：Undertow 上的 REST + SSE、会话按 id 寻址、HTTP 化人工审批 | api、infra、core、undertow-core |
| `jellyfish-di` | 装配层（composition root）：Dagger2 组件 + 门面接口 `JellyfishRuntime` + 手工装配工厂 `JellyfishAssembler` | api、infra、core、dagger、okhttp |
| `jellyfish-cli` | `main`、参数解析、模式分发、shade 可执行 jar | api、infra、core、di、tui、server |

`jellyfish-di` 为什么单独成模块：装配知识（谁依赖谁、**哪些实例必须唯一**）只写一份，两种装法交付同一个
`JellyfishRuntime` 契约——CLI 用 Dagger（`DaggerJellyfishComponent`），不用 Dagger 的容器用
`JellyfishAssembler`。**代价是新增绑定时两处都要改**：`@Module` 的每条 `@Provides` 在 `JellyfishAssembler`
里都有一行对应物，兜底由 `JellyfishAssemblerTest`（同一段行为对两种装配各跑一遍）守。

包结构（只列包；其余类直接读代码）：

```
jellyfish-api/     extension/（同步扩展点）、event/（事件与通知）、ui/、subagent/、plugin/（SPI）
jellyfish-infra/   registry/ extension/ event/ session/ agent/ command/ model/ llm/ plugin/
                   permission/ ui/ shell/ metrics/ config/ tooloutput/ support/
jellyfish-core/    prompt/ compact/ tool/ conversation/ input/ subagent/ runtime/ command/
                   + AgentHarness / ReActLooper / RunContext 等枢纽类
jellyfish-di/      JellyfishRuntime / JellyfishAssembler / 10 个 Dagger Module
jellyfish-cli/     JellyfishApplication（main）、console/、mode/
jellyfish-tui/     TuiApp（唯一入口）、ChatShell / TranscriptProjector / text/
jellyfish-server/  路由、SSE、审批桥
```

资源位置：`default-agent.json` / `jellyfish.md` 在 infra 资源根；`summary-prompt.md` 在压缩插件资源根；
`config.json` / `log4j2*.xml` 在 cli 资源根；**`jellyfish-di` 不带任何资源**（它只装配）。

## 关键不变量

改动任何一处前先确认不会破坏它们（展开说明见 `docs/constraints.md`）：

- **`AgentHarness.chat(sessionId, input, listener)` 是外壳唯一的智能入口**，在 `react` 线程池上异步推进。
- **`SessionManager` 是会话的唯一变更入口**；落盘只有「创建不落盘」与「回合内只标脏」两个例外。
- **`ToolExecutor` 是权限 → 路由 → 截断的唯一执行点**，模型调用与输入指令共用它。
- **`ExtensionRegistry`（同步、不可丢）与 `EventChannel`（异步、可丢）是内核与插件之间唯一边界**；
  判据是「能否丢弃」而不是「有没有返回值」。**禁止引入第三方事件总线**。
- **权限两层**：核心策略 → 插件拦截；插件拦截三态且**没有 `ALLOW`**，审批 fail-closed
  （无审批者 / 超时 / 中断一律拒绝）。
- **`CommandManager` 无状态、对外壳中立**；「需不需要会话」由命令自己声明（`sessionRequired` 缺省 `true`）。
- **压缩是插件能力、内核只提供机制**；没有策略插件即整体不可用，不回退内置。
- **提示词组装**：`PromptAssembler`；裁剪只裁本次请求（`ContextWindow` 成组丢弃），历史一条不动。
- **项目级配置默认不加载**（`ProjectConfigTrust`）：它按进程当前目录解析，一个 `git clone` 的目录就足以
  改写模型端点与密钥、新增 agent、改落盘目录。信任单位是「文件路径 + 内容指纹」，内容一变即失效；
  授予只有 `--trust-project-config`（本次，不落盘）与 TUI 确认框（可记住）两条。**未信任即跳过 + 告警**，
  `classpath:` 路径不受此闸管辖。**任何新增的项目级配置读取点都必须过这道闸**。
- **子代理**：`SubAgentLauncher` + `TaskTool`（owner=core）→ `AgentRuntime.spawn`；嵌套回合**调度到独立的
  `agent-run` 池上执行，绝不进 `react` 池**；并发由 governor 许可门控，等待中的 run 会让出许可。
  run 起止走运行时的 `RunEventBus`（不是外壳回合 lane）；**插件要驱动子代理走 `PluginContext.delegations()`**。
- **跨边界载荷必须是 `api` 侧快照值类型**，且快照类型恰好只有一个可见构造器；**`-parameters` 不许去掉**。
- **插件在 `stop()` 之后 fail-closed**：任何注册 / 发布当场失败；`start()` 抛错则插件转 `FAILED` 并回收已完成的注册。
- **生命周期收尾顺序**：`flushAll()` → `InputDirectives.close()` → `pluginManager.close()`
  （落盘经扩展点派发给插件，插件一停就没人接了）。
- **`-tui` / `-server` 的启动期都不建会话**；`--agent` / `--model` / `-p` / `--show-thinking` 只归 `CLI`，
  其余模式带上它们一律判用法错误退 2（**拒绝而不是静默忽略**）。

## 编码规范

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律用构造器注入 `@Inject`，不用字段注入。
- 内核与插件之间的交互只走 `ExtensionRegistry` / `EventChannel`，禁止引入第三方事件总线（如 Guava EventBus）。
- 注释用中文，说明「为什么」而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释；类注释加 `@author zcd`；方法注释用 `@param` 写清每个参数、
  用 `@return` 写清返回值。
- 异常统一抛 `JellyfishException`；**序列化统一走 `ObjectMapperWrapper`，不要直接 `new ObjectMapper`**。
- 请求 / 消息模型用与厂商无关的 `LlmRequest` / `LlmMessage` / `LlmTool`，`LlmRequest` 用 builder 构建。
- 配置类型命名：项目内部配置类用 `Config` 结尾，暴露给用户的配置类用 `Settings` 结尾。
- 工具方法 / 常量类用 `final` + 私有构造器（如 `ProviderTypes`、`LlmClients`）。
- 所有代码均需满足 sonar 规范要求。

## 单元测试规范

- JUnit5 + Mockito，JaCoCo 收集覆盖率。
- 测试类包路径与被测类一致；命名 `{被测试类名}Test`。
- 方法名 `{被测试方法}_should_{预期结果}_when_{条件}`；一个方法只验一个行为；测试独立、可重复、无外部依赖。
- 结构遵循 Given/When/Then 或 Arrange/Act/Assert。
- 只 mock 外部依赖或协作者，不 mock 被测类、POJO、DTO。
- 用 `@ExtendWith(MockitoExtension.class)`，统一 JUnit5 API，不混用 JUnit4。
- 断言用 JUnit5 `Assertions` 或 AssertJ，异常用 `assertThrows`。
- 多组输入用 `@ParameterizedTest`，覆盖正常、边界、异常场景。
- 单测不启动 Spring 容器，不访问数据库、网络等真实外部资源。

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
- **改了用户可见行为就同步改文档**：新增命令 / 参数 / 配置字段 / 接口时，`README.md` 与 `docs/constraints.md`的对应位置要一起改。
- 注释/文档应该记录的是最终状态，不要记录决策过程。有过时的注释/文档要及时更新。
