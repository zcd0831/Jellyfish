# 变更日志

本文件记录 Jellyfish 内核（`zcd:jellyfish`）所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
