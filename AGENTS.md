# AGENTS.md

本文件用于指导 AI 编码代理在本仓库中工作。修改代码前请先阅读。

## 项目概述

Jellyfish 是一个用 Java 1.8 编写的轻量级 AI Agent 工具，通过 PF4J 插件扩展能力；除 Java 插件外，还通过 PF4J 桥接插件支持 Python / TypeScript 等跨语言插件。

- 坐标：`zcd:jellyfish:0.0.1-SNAPSHOT`
- 构建：Maven（`pom.xml`）
- 运行环境：JDK 1.8（不要使用 Java 9+ 的 API 或语法）

## 常用命令

```bash
mvn -q compile
mvn -q package -DskipTests
mvn -q -Dtest=UserServiceTest test
```

## 整体架构图

```mermaid
flowchart TB
    subgraph AgentHarness["AgentHarness（Agent 运行时宿主）"]
        direction TB

        subgraph AppLayer["【应用层】ReAct 核心智能"]
            ReAct["ReAct Loop<br>思考 → 行动 → 观察"]
        end

        subgraph InfraLayer["【基础设施层】Harness 运行时环境"]
            direction LR

            subgraph CoreInfra["核心基础设施"]
                EventBus["JellyfishEventBus<br>内核与插件交互枢纽<br>双通道：回调 + 事件<br>Level1 Guava 派发 + Level2 细粒度注册表"]
                SessionMgr["SessionManager<br>会话隔离 / 消息列表<br>当前 agentId / 当前模型 / 会话级切换"]
                AgentMgr["AgentManager<br>Agent 定义注册表<br>按 agentId 提供系统提示词 / 权限策略"]
                ModelMgr["ModelManager<br>Provider/Model 注册/解析/路由<br>不持有全局当前态"]
                LLMClient["LLMClient<br>统一 LLM 调用抽象"]
                PluginMgr["PF4JPluginManager<br>插件加载/热部署/执行<br>Java 插件与跨语言桥接插件同构"]
                PermMgr["PermissionManager<br>Agent 粒度权限控制"]
            end

            subgraph SupportInfra["支撑基础设施"]
                Runtime["RuntimeConfig<br>配置解析/合并/注入"]
                Metrics["可观测性<br>Metrics/Health/Logging"]
            end
        end
    end

    subgraph External["外部依赖"]
        LLM["外部 LLM API<br>OpenAI/Azure/Ollama"]
        Plugins["PF4J 插件<br>Java: Tools/Memory/Cross<br>桥接: Python / TS 语言适配"]
        Scripts["脚本插件进程<br>Python 常驻网关 / TS·JS 常驻网关<br>单进程多路复用"]
        Jelly["jellyfish.json<br>全局级 + 项目级<br>Provider/Model/插件清单"]
        Agents["agents.json<br>全局级 + 项目级<br>Agent 定义"]
    end

    %% ===================== 应用层 -> 基础设施层 =====================
    ReAct -->|消息列表 / 上下文 / 当前 agentId / 当前模型| SessionMgr
    ReAct -->|getCurrentLlmClient| ModelMgr
    ReAct -->|调用 LLM| LLMClient
    ReAct -->|ToolCallRequest| EventBus
    EventBus -.->|ToolCallResult| ReAct

    %% ===================== 会话与 Agent 定义 =====================
    SessionMgr -->|按 currentAgentId 取 system prompt / Agent 定义| AgentMgr
    SessionMgr -->|按 currentModel 解析 provider/model| ModelMgr

    %% ===================== LLM 调用链 =====================
    ModelMgr -->|管理/创建/路由| LLMClient
    LLMClient -->|HTTP/API| LLM

    %% ===================== EventBus 调度 =====================
    EventBus -->|同步权限检查| PermMgr
    EventBus -->|同步工具调度| PluginMgr
    PluginMgr -->|加载/执行| Plugins
    Plugins -->|桥接插件拉起常驻进程| Scripts
    PluginMgr -.->|工具执行结果| EventBus

    %% ===================== RuntimeConfig 注入 =====================
    Runtime -->|读取合并, 项目级优先| Jelly
    Runtime -->|读取合并, 项目级优先| Agents
    Runtime -->|注入 Provider/Model 配置| ModelMgr
    Runtime -->|注入 Agent 定义| AgentMgr
    Runtime -->|注入插件配置| PluginMgr
    Runtime -->|注入权限配置| PermMgr
    Runtime -->|注入会话配置| SessionMgr
    Runtime -->|注入事件配置| EventBus

    %% ===================== 可观测性 =====================
    Metrics -.->|异步上报| EventBus
```

## 代码结构

Maven 多模块。模块边界与「整体架构图」的两层 + 对外契约一一对应；根 `jellyfish`（`zcd:jellyfish:0.0.1-SNAPSHOT`）是 `packaging=pom` 的聚合与父 POM，统一管理版本。

依赖方向单向，禁止反向或循环：

```mermaid
flowchart LR
    API["jellyfish-api<br>插件 SPI + 统一异常"]
    SCRIPT["jellyfish-script<br>跨语言插件运行时（语言无关）"]
    PY["jellyfish-plugin-python<br>PF4J 桥接插件"]
    NODE["jellyfish-plugin-node<br>PF4J 桥接插件（TS/JS）"]
    INFRA["jellyfish-infra<br>【基础设施层】"]
    CORE["jellyfish-core<br>【应用层】"]
    CLI["jellyfish-cli<br>入口 + DI 装配 + 分发"]

    PY --> SCRIPT
    NODE --> SCRIPT
    SCRIPT --> API
    CORE --> API
    CORE --> INFRA
    INFRA --> API
    CLI --> CORE
    CLI --> INFRA
    CLI --> API
```

跨语言桥接插件与内核之间没有编译期依赖：它们由 `PF4JPluginManager` 在运行时从 `plugins/` 目录加载，因此不出现在上面的依赖链里。

| 模块 | 坐标 | 职责 | 依赖 |
| --- | --- | --- | --- |
| `jellyfish-api` | `zcd:jellyfish-api` | 插件作者唯一需要依赖的稳定契约：SPI 接口、能力上下文、统一异常 | 无 |
| `jellyfish-infra` | `zcd:jellyfish-infra` | 架构图【基础设施层】的全部实现，含配置加载 | `jellyfish-api` |
| `jellyfish-core` | `zcd:jellyfish-core` | 架构图【应用层】：ReAct 循环与 `AgentHarness` 门面 | `jellyfish-api`、`jellyfish-infra` |
| `jellyfish-cli` | `zcd:jellyfish-cli` | `main`、启动参数解析、Dagger 装配、shaded 分发 | `jellyfish-api`、`jellyfish-infra`、`jellyfish-core` |
| `jellyfish-script` | `zcd:jellyfish-script` | 跨语言插件运行时（语言无关）：JSON-RPC over Stdio、常驻进程池、双向事件桥接、生命周期与安全护栏 | `jellyfish-api` |
| `jellyfish-plugin-python` | `zcd:jellyfish-plugin-python` | Python 桥接插件：声明宿主语言与脚本目录，拉起 Python 常驻网关，代脚本操作 `PluginContext` | `jellyfish-script`、`jellyfish-api` |
| `jellyfish-plugin-node` | `zcd:jellyfish-plugin-node` | TS/JS 桥接插件：同 Python 桥接插件，宿主 Node 常驻网关 | `jellyfish-script`、`jellyfish-api` |

包名一律全小写。

```
jellyfish-api/src/main/java/zcd/jellyfish/api/
├── JellyfishException.java        # 统一运行时异常，插件抛错也能被 core 统一捕获
└── plugin/                        # 插件 SPI：插件总入口、工具/记忆/横切三类插件接口与能力上下文，面向仓库外插件作者的唯一稳定契约

jellyfish-infra/src/main/java/zcd/jellyfish/infra/
├── event/          # 内核与插件交互枢纽：回调通道（有返回值，0..N，命令是 unique 退化特例）+ 事件通道（无返回值，0..N），含 ISOLATED 执行器；Level1 Guava 派发 + Level2 细粒度注册表
├── callback/       # 回调通道的同步请求-响应模型：Callback / CallbackHandler / XxxRequest
├── session/        # 会话运行态：会话隔离、消息列表，以及会话内当前 agentId 与当前模型（仅内存态）
├── agent/          # Agent 定义注册表：从配置装载定义，按 agentId 提供提示词与权限策略
├── model/          # 模型注册与路由：维护 provider/model 索引，按名字解析模型并给出 LLM 客户端（不持有全局当前态）
├── llm/            # LLM 调用抽象：统一的同步/流式调用接口与各厂商实现
├── plugin/         # 插件运行时：Java 插件加载、热部署与执行调度，按统一 SPI 看待桥接插件，不感知底层脚本进程
├── permission/     # 权限控制：Agent 粒度的插件授权与人工审批判定，含脚本插件声明的语言与事件权限
├── metrics/        # 可观测性：指标采集、健康检查与日志上报
├── config/         # 配置加载：全局级 + 项目级双源读取与合并，只读
└── support/        # 通用支撑：序列化封装、类型常量等底层工具

jellyfish-core/src/main/java/zcd/jellyfish/core/
├── AgentHarness.java              # 组装门面：外部入口只认它
├── ReActLooper.java               # 思考 → 行动 → 观察
└── prompt/                        # 系统提示词与上下文组装

jellyfish-cli/src/main/java/zcd/jellyfish/cli/
├── JellyfishApplication.java      # main：解析 -cli/-tui/-server 后交给 Launcher
├── Launcher.java                  # 启动模式分发
├── mode/                          # 启动模式实现：CLI 已实现，TUI / Server 预留占位
└── di/                            # composition root：最外层负责依赖装配
    ├── JellyfishComponent.java    # Dagger2 组件定义
    └── module/                    # 各依赖域的 Dagger2 Module

jellyfish-script/src/main/java/zcd/jellyfish/script/
├── ScriptGateway.java             # 单例门面：常驻进程池、按 language + method 路由、订阅记录、生命周期
├── ScriptProcess.java             # 单个子进程 stdio 封装：sendRequest / notify / isAlive / destroyForcibly
├── ScriptProtocol.java            # JSON-RPC 2.0 over Stdio：帧编解码、请求 id 配对、超时
├── ScriptRequestHandler.java      # 脚本上行请求：register_tool / register_command / subscribe_event / emit_event
├── EventBridge.java               # 双向事件桥接：内核通知 ↔ 脚本，防循环 + 白名单 + 限流
├── ScriptLanguage.java            # 语言枚举与适配：决定各语言的启动命令与网关资源路径
├── ScriptLifecycle.java           # ShutdownHook / PID 文件 / 看门狗 / 空闲自毁
└── ScriptPermissions.java         # 权限常量：SCRIPT_PYTHON / SCRIPT_NODE / EVENT_SUBSCRIBE / EVENT_EMIT

jellyfish-plugin-python/src/main/
├── java/zcd/jellyfish/plugin/python/PythonBridgePlugin.java   # 实现 JellyfishPlugin，全部委托 ScriptGateway
└── resources/
    ├── plugin.properties        # PF4J 描述符：Plugin-Id、依赖、声明的权限
    └── scripts/                 # gateway.py 常驻网关 + 业务脚本目录

jellyfish-plugin-node/src/main/
├── java/zcd/jellyfish/plugin/node/NodeBridgePlugin.java       # 实现 JellyfishPlugin，全部委托 ScriptGateway
└── resources/
    ├── plugin.properties        # PF4J 描述符：Plugin-Id、依赖、声明的权限
    └── scripts/                 # gateway.js 常驻网关 + 业务脚本目录

src/main/resources/config.json     # 应用配置（进程名 + 各配置段的双源文件路径）
```

- **分层靠模块强制**：`core` 与 `infra` 拆开，Maven 才能在编译期守住「应用层 → 基础设施层」这条依赖方向；`api` 独立，是因为它的消费者是仓库外的插件。
- **`AgentHarness` 是唯一的组装门面**：`cli`/`tui`/`server` 都通过同一个 `JellyfishApplication` 入口走它，三种外壳只靠启动参数区分。
- **DI 装配在最外层**：Dagger 组件与 Module 只放在 `jellyfish-cli`，core/infra 只暴露构造器与 `@Module`，新增启动模式不必改动 core/infra。
- **`tui`/`server` 先不建模块**：暂时只在 `cli/mode` 留占位，等真正开工再抽 `jellyfish-tui`/`jellyfish-server`。
- **跨语言靠桥接插件落地**：`jellyfish-script` 只做语言无关的跨语言运行时，由桥接插件自行 shade 进插件 jar；内核 classpath 上不出现任何跨语言代码，`infra`/`core` 也不知道脚本进程存在。
- **脚本插件与 Java 插件同构**：脚本只能注册回调处理器、订阅事件、发布事件，**不能发起回调**，权限边界与 Java 插件完全一致。


## 架构要点

- **依赖注入（Dagger2）**：通过 Dagger2 进行依赖注入，对各个模块进行解耦。
- **配置加载**：`AppConfig` 直接绑定 `classpath:config.json`，应用级配置。`SettingsReader` 会把 `${ENV_VAR}` 替换为环境变量，apiKey 通常这样注入。
- **global/project 合并**：`RuntimeConfig` 合并两者，同名 provider 以 project 覆盖 global，默认 provider/model 同理。
- **流式调用**：`AbstractHttpLlmClient` 用 OkHttp 手写 SSE（`text/event-stream`）解析，流式请求在线程池（守护线程，名为 `llm-stream`）中执行，句柄可 `cancel()`。OpenAI 兼容协议的公共逻辑在 `AbstractOpenAiCompatibleLlmClient`。
- **异常**：统一抛 `JellyfishException`。
- **序列化与反序列化**: 读写统一走 `ObjectMapperWrapper`，不要直接 new `ObjectMapper`。
- **请求/消息模型**：`LlmRequest`、`LlmMessage`、`LlmTool` 是与厂商无关的统一模型，`LlmRequest` 用 builder 构建。
- **配置类型**：应用内部配置类（即项目代码里的配置，不会暴露给用户）用`Config`结尾，提供用用户的配置类用`Settings`结尾。
- **交互枢纽双通道**：`JellyfishEventBus` 是内核与插件的唯一交互枢纽，回调通道负责「有返回值的调用」（`invoke` 取单个 / `invokeAll` 收集），事件通道负责「无返回值的广播」；回调的唯一性、顺序、空表策略、结果数量、执行模式与失败语义均由其扩展点定义（`@ExtensionPoint` + `ExtensionPointRegistry`）驱动，而非按类型硬编码。
- **两种回调执行模式**：`INLINE` 在调用线程内联执行（B 提供）；`ISOLATED` 提交到独立有界线程池（核心 4 / 最大 32 / 队列 256）并逐个施加 per-handler 超时（默认 2000ms），超时即 `cancel(true)` 并丢该结果；拒绝即降级不阻塞调用线程，因此处理器不得依赖 `ThreadLocal`。
- **插件模型**：Java 插件与跨语言桥接插件在 `PF4JPluginManager` 眼里完全同构，都只经 `PluginContext`（`tools()` / `commands()` / `events()` / `publisher()`）与内核交互；脚本进程只是桥接插件背后的一台「无状态计算器」。
- **跨语言通信**：JSON-RPC 2.0 over Stdio，每行一个 JSON；每种语言最多一个常驻进程（单进程多路复用），请求统一经 `ScriptGateway` 路由，脚本不直接管理进程。
- **跨语言事件桥接**：内核通知经 `EventBridge` 推给脚本，脚本 `emit_event` 反向回总线；脚本来源事件带来源标记避免回推，事件类型走白名单、负载限 1MB、队列有界。

## 编码约定

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律使用构造器注入 `@Inject`，不使用字段注入。
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
