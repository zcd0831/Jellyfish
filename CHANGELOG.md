# 变更日志

本文件记录 Jellyfish 内核（`io.github.zcd0831:jellyfish`）所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `--trust-project-config` 启动参数：信任并加载**项目级**配置（`./.jellyfish/*.json`），仅对本次进程有效、
  不落盘。`-tui` 下改由启动时的确认框询问，可选「加载并记住」（写入
  `~/.jellyfish/trusted-project-configs.json`）或「仅本次加载」。
- `subAgent.maxQueuedRuns`：并发满员时允许排队的 run 数上限（缺省 64）。此前 `agent-run` 池没有等待队列，
  一次派出的 run 多于 `maxConcurrentRuns` 时，多出来的当场以失败落终态；现在它们排队等许可，
  只有「线程 + 等待区都满」才拒绝，且拒绝理由里说明该调哪个键。

### Changed

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
