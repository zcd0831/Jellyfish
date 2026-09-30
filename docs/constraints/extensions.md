# 约束：扩展层与插件运行时

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改扩展层、插件运行时、事件通道前必读**。
> 面向使用者的说明见 [README.md](../../README.md)，设计决策的背景见 [architecture.md](../architecture.md)。
> 只写规则与事实；推导与实测数据在对应类的注释里。

## 一份注册表 + 两种派发策略 + 一条入站队列

- **两个能力面**：`ExtensionRegistry`（同步：调用点内联、按 order 升序、取返回值、**不可丢**）与
  `EventChannel`（异步：有界队列、无返回值、**可丢**），共用 `infra/registry` 的同一份 `TypeRegistry`。
  **禁止引入第三方事件总线**（如 Guava EventBus）。
- **另外还有一条入站队列：`ActionQueue`**（插件主动动作）。它是**内核自有的、有界的、单向的**队列，
  不是事件总线——没有订阅、没有广播、没有 handler 注册：
  - 方向与上面两个相反：`handle` / `contribute` / `observe` 是「内核回头找插件」，
    `emit` 与 `submit` 是「插件往外发」（通知队列 / 动作队列），四条边都不是同步回调；
  - 它只接受 `PluginAction` 上列出的那几种动作，「能投什么」是一份有界清单，
    插件不能把它扩成开放式接口（`PluginAction` 的构造器是包级私有的，只能经静态工厂构造）；
  - 队列的存活期恰好是一个顶层回合：`ReActLooper.chat` 在提交任务之前开窗，任务的 `finally` 关窗。
    **动作只投进正在跑的顶层回合**，没有回合就没有窗口，入队当场回报 `FAILED`。
    「插件在回合之外想说话」明确不做，理由见 `design/extension-points.md` §9.8；
  - 结果由插件轮询 `ActionHandle` 取得（`QUEUED` / `EXECUTING` / `DONE` / `FAILED` / `DROPPED`），
    内核不在动作完成时回头调插件；
  - `submit` 与注册共用 `ContextLifecycle` 这条存活边界：`stop()` 之后当场抛 `JellyfishException`，
    停止时在途动作按 owner 命名空间整批丢弃（`DROPPED`）。
- **选择能力面的判据是「能否丢弃」，不是「有没有返回值」**：工具、工具参数改写与结果整形、生命周期钩子（含会话分支前）、厂商注册与模型目录发现、
  提示词注入、权限拦截、会话持久化、输入改写走同步侧（即使无返回值也不能丢）；轮次通知、指标、审计走异步侧。
- **类型即地址**：请求类型本身就是身份，注册表按「类型 + 路由键」找 handler；插件拿不到的类型就注册不了。
- **同步侧只提供有序查找与单处理器执行，注册表不做编排**：`handler` 同键唯一（0 个 `NO_HANDLER`、
  多个 `AMBIGUOUS_HANDLER`），`descriptorBindings` 取描述符清单（描述符为空的注册也返回）。
  调用顺序与结果合并由调用方决定。
- **同步派发没有超时、白名单、异常隔离**：调用方需要确定结果，若不能容忍插件阻塞或抛错，
  必须自己在调用点设超时或捕获。

## 新增扩展点的公共约定

新增一个同步扩展点时，以下四条是硬要求：

- **请求类型必须显式定义「0 个 handler 时是什么行为」**，并为它写一条测试。这是新增请求类型唯一的兼容性保障：
  老插件不注册它，内核在调用点必须走一条与改造前**逐字段一致**的路径。没有这条，新扩展点会以
  「装了插件的用户正常、没装的用户行为漂移」这种最难归因的形式破坏兼容。
- **必须写明失败语义**：handler 抛错时按「`ABSTAIN` / 保留原值 / 继续」中的哪一个处理，由调用点在
  `try/catch` 里落地。同步侧没有异常隔离（见上一节），不写就等于把「插件抛错时内核怎么办」留空。
- **请求 / 结果类型放 `api`，恰好一个可见构造器 + 静态工厂**：新增字段只能用新静态工厂补，
  不加兼容构造器（跨边界载荷规则见 [session-config.md](session-config.md)）。
- **`order` 升序、同序按注册顺序**：这是 `TypeRegistry` 的既有语义，新点不得另立一套；
  结果合并规则（第一个非 `ABSTAIN` 胜出 / 第一个 `cancel` 短路 / 取最后一个非缺省值）
  由调用点的 `for` 循环实现，注册表不参与。

## 插件生命周期

- **Java 插件与跨语言桥接插件在 `PF4JPluginManager` 眼里同构**，都只经 `PluginContext`
  （handle / contribute / observe / emit / submit）与内核交互。
- **注册窗口是插件的整个存活期，不是 `start()` 之内**：`start()` 返回到 `stop()` 之前，插件可在任意时刻注册、
  订阅、发布——运行期才发现自己能提供哪些能力的插件（MCP 客户端在握手后才知道工具集）必须如此。回收仍只按
  owner 一次收干净，因此运行期注册不会留下没人收的登记；`handle` / `contribute` / `observe` 返回的
  `Subscription.close()` 是插件侧主动注销的正式手段（**注销是可选优化，不是必须动作**）。
- **`stop()` 之后注册一律当场抛 `JellyfishException`（fail-closed）**：窗口放宽之后「停止期与注册期重叠」从文档约定
  变成真实竞态，因此 `PluginContextImpl` 持有一个与全部子上下文**共享**的 `ContextLifecycle`，
  `PluginContextFactory.release` 时**先关闭标记再回收注册**（顺序不能反，否则「先注册、再回收」会留下谁也回收不到的
  幽灵注册）。
  - 推论一：**产生注册的后台线程必须在 `stop()` 返回前停下来**。
  - 推论二：子上下文也必须复用父上下文的标记，否则回收根上下文管不住子上下文。
  - 推导见 `PluginContextImpl` / `ContextLifecycle` 的注释。
- **插件碰不到会话、也拿不到工作目录**：`PluginContext` 只有身份、四个注册方法与
  `runtimeInfo()`（它给的四个字段全是进程级事实：外壳种类、有无交互界面、是否具备审批通道、有无终端；
  **不含 `sessionId` / `agentId` / `cwd` / 上下文用量 / 提示词**）。工具相对路径按进程工作目录解析
  （`ToolPaths`）。状态只要按 `sessionId` 归属，插件就能自己持有。

## owner 与命名空间

- **owner 可以是命名空间**：插件可给内部子单元分独立 owner
  （`pluginId` + `api.PluginOwnerNamespace.SEPARATOR` + 子标识），`PluginContextFactory.release` 按命名空间回收
  （`pluginId` 自身与 `pluginId::*` 一起清），因此子单元的注册不会在插件停止后残留成幽灵注册。
- **分隔符常量在 `api`**：这是跨边界契约——插件拼来源、内核做前缀回收，必须同一个真源。
- 插件侧用 `PluginContext.subContext(childId)` 派生子上下文来注册到子命名空间（**子身份恒从当前身份派生，无法越界**）；
  子标识规则由 `PluginOwnerNamespace.requireChildId` 一处承担，插件侧与框架侧共用。
- **`plugin.id` 含分隔符的插件在描述符体检阶段被拒**：否则一个叫 `x::y` 的插件会把自己的注册挂进命名空间 `x`，
  `x` 停止时就会越界抹掉它的注册。
- **`EventChannel.unsubscribeAll` 仍是精确匹配**（它服务于内核内部来源，不参与命名空间前缀回收）。

## 插件扫描与配置

- **新增 / 删除插件 jar 需要重启进程**（扫描目录与插件集合只在启动期确定）；
  `jellyfish.json` 里插件配置段的变化由 `/reload` 按差异重启对应插件。
- **插件启用 / 禁用名单与逐插件配置段**在 `jellyfish.json` 的 `plugins` 段；字段语义与合并规则见
  [configuration.md](../configuration.md) 的 `plugins` 一节。
- **只读白名单**：插件只能声明工具描述符，用户只能追加；语义见 [permissions.md](permissions.md)。

## 改动检查清单

改这一域时逐条确认：

1. 新能力面是否必须同步（有返回值或不可丢）？若是，是否在调用点自己设了超时与异常捕获？
2. 新的注册是否会在 `stop()` 之后发生？若是，先看 `stop()` 前能不能停掉产生它的线程。
3. 新注册的 owner 是否需要命名空间隔离？用了 `::` 就要确认回收按前缀走。
4. 是否新增了跨边界常量？放 `api` 侧，别在 infra 与插件各写一份。
5. 若是新增的同步扩展点：0 handler 时的行为、失败语义、`order` 合并规则是否都已写明并有单测？
6. 若改了 `ActionQueue` 的窗口语义（开窗/关窗时机、容量、取用规则）：是否同时核对了
   `ReActLooper` 的两个排空点、`ActionDispatcher` 的回填，以及「回合结束时残留动作标失败」这条？
