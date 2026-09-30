# 约束：会话、持久化与配置

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改会话状态、落盘、配置加载与热更新、模型 / agent 解析前必读**。
> 字段清单与合并规则见 [configuration.md](../configuration.md)，背景见 [architecture.md](../architecture.md)。
> 只写规则与事实；推导与实测数据在对应类的注释里。

## 会话状态

- **会话状态一律归 `Session`，进程内没有全局当前态**：agentId / 模型 / 权限模式都是会话字段，由 `SessionManager`
  统一读写；会话是内存运行态，**不设 `session` 配置段**。
- **唯一的进程级字段是 `SessionDefaults`**（新建会话的待生效默认值），且**只在 `create` 那一刻被消费**，
  建完就跟会话无关。
- **`createDefault()` 四项全传 `null`，包括权限模式**：`null` 的含义是「按待生效默认值、其次按更下层的默认」；
  显式传 `PermissionMode.NORMAL` 会把首页设的那一层默认值直接跳过。

## 落盘：唯一入口 + 两个例外

`SessionManager` 是唯一变更入口。变更同步派发 `SessionPersistRequest` 且**异常原样上抛**
（关闭先落盘再移除，删除走 `SessionDeleteRequest`、**删不掉就当没删**）。

**关闭前可被插件拦下（`SessionBeforeCloseRequest` → `LifecycleVerdict`）**：调用点在<b>最后一次落盘之前</b>，
让插件有一个正式的收尾点（写检查点、导出记录）。三条规则：

- **只有 `USER_REQUEST` 的否决会被采纳**：进程收尾（`SHUTDOWN`）、配置重载（`RELOAD`）与内部收尾
  （`INTERNAL`，如瞬时子代理会话跑完）都是一个必须完成的事实，在那里按插件的意愿留下一个
  「本该关掉的会话」只会变成资源泄漏。钩子仍然会被调用，只是 `cancel` 被忽略。
- **否决是 fail-loud**：抛 `JellyfishException` 且会话留在表里，而不是静默不关——后者与
  「会话不存在」无法区分，调用方会以为已经关掉了。
- **handler 抛错按放行处理**，否则一个坏插件会让会话永远关不掉。

**它只管「结束运行态、保留快照」**：删除是另一件事，走既有的 `SessionDeleteRequest`。

两个例外：

- **例外一：创建不落盘**——空会话不留文件与提交，`create` 的失败语义随之从「创建时暴露」变成
  「第一次变更时暴露」。
- **例外二：回合内的消息追加只标脏**，由 `ReActLooper.execute` 的 `finally` 调 `flush` 落一次
  （放 `finally` 才盖得住收敛 / 取消 / 超轮次 / 异常四条路径）。因此新契约是「**回合收敛 = 已落盘**」。

其余语义：

- **恢复的失败语义相反**：`SessionRestoreRequest` 单个插件读不出只告警跳过；**恢复必须排在
  `pluginManager.bootstrap()` 之后**。
- **延迟落盘的失败语义与即时落盘相反**：`flush` 失败只记 WARN 并**保留脏标记**等下次重试
  （此刻回合已收敛、回答已展示，升级成回合失败既补不回来也无从补救）。
- **`AgentHarness.shutdown` 必须在 `pluginManager.close()` 之前调 `flushAll()`**——落盘经
  `ExtensionRegistry` 派发给插件，插件一停就没人接了；没有这一步，「回合级落盘」会把「Ctrl+C 丢当前回合」
  变成新行为。
- **只有消息追加被挂起**：命令、`recordUsage`、`applyCompaction`、`close` 仍即时落盘。因此跑在独立线程上的
  自动压缩天然不受回合作用域影响——它本来就是一个独立的落盘单元。

## 瞬时（子代理）会话与分支会话

- **`SessionKind` 是「哪一类会话」的唯一判定来源**：`NORMAL` / `EPHEMERAL` / `FORKED`。
  `parentSessionId` **降级为追溯信息**（子代理记录被哪次委派生出来，分支记录从哪个会话分出来），
  **不得再用它做判定**。
- **为什么必须拆开**：两个含义挤在一个字段上时，fork 会话会被误判成子代理会话，
  后果是它**不落盘**——一条静默的数据丢失。
- **老快照缺 `kind` 的映射在 `SessionSnapshot.getKind()` 里**：`kind` 为空时看 `parentSessionId`，
  非空补 `EPHEMERAL`、否则 `NORMAL`。放在快照类型上而非恢复路径上，是为了让
  「插件自己产出的、缺字段的快照」也走同一条映射。
  实际上这条几乎走不到：老快照连 `parentSessionId` 都没有（它是新加的）。
- **瞬时会话**：由 `SessionManager.createEphemeral` 创建，`kind = EPHEMERAL`。
  它们同样在会话表里（能追消息、发事件、被回查），但**不进 `all()`、不落盘**，因此不会给会话目录留下一批
  谁也认领不了的文件。恢复路径不涉及它们（从未落盘就不会被恢复）。收尾走 `close()`（不是 `delete()`）。
- **分支会话**：由 `SessionManager.fork` 创建，`kind = FORKED`，**与普通会话同等对待**
  （进 `all()`、落盘、可被 `/resume` 与 `/delete`）。它是用户的会话，不是子代理的临时工作区。

### fork 的四条硬规则

- **切点必须落在工具调用组的边界上，且往「后」推**：指定的是工具结果时，沿连续的工具结果推到这一组的末尾。
  往前退会留下一条**没有结果的** `assistant(tool_use)` 结尾，而 `PromptAssembler` 在组装请求时
  本来就会把那种结尾丢掉——于是那条消息「在历史里在、模型永远看不到」；而且往前退会静默丢掉
  被指定的那条消息。
- **不复制 `usage`**：那是源会话花掉的钱，带过去会把成本重复计入。
- **压缩摘要按「边界是否落在复制范围内」取舍，且判定在配对对齐之后**：
  `indexOf(boundaryMessageId) <= 切点` 就带，否则丢。压缩是非破坏式的，两种情况都不丢数据。
- **扩展条目照带**：它们属于「会话在那一刻的状态」，丢了它们「回退到某一步」会得到一个自身标记全无的会话。
- **`SessionBeforeForkRequest` 的否决一定被采纳**（与关闭前钩子不同）：fork 是一条显式动作，
  不是进程收尾路径。请求里给的是**对齐之后的切点**。

## 会话扩展条目

插件（与内核）可以在会话上挂自己的状态，不必再把它伪装成一次工具调用。

- **key 在写入时被拼上 owner 前缀**（`pluginId` 或 `pluginId::子标识`）：插件之间互相看不见，
  也无法写到别人的命名空间里。插件侧的 key **不得含 `::`**，否则
  `plugin-a::a::b` 读不出来是「子上下文 `a` 写的 key `b`」还是「根上下文写的 key `a::b`」。
- **插件侧入口在 `PluginContext` 上**（`putExtensionEntry` / `removeExtensionEntry` / `extensionEntries`），
  **不在 `SessionManager` 上**——插件拿不到 `SessionManager`。读取按命名空间过滤，
  与写入的隔离对称。
- **走既有的「唯一变更入口 + 标脏 + `flush`」机制**，不新增第三条落盘路径。
- **条目不进模型上下文**（与工具结果元数据同口径）：模型不需要它，界面与插件需要。
  但它随会话一起落盘，**因此有上限**：单条值 64 KiB / 每会话 64 条 / key 256 字符，
  超限抛 `JellyfishException` 且**不写入**（不截断、不静默淘汰），错误信息带 owner。
- **「字节数」是内核的规范编码**（`ObjectMapperWrapper` 的 UTF-8 字节数），不是插件文件格式的字节数——
  文件格式由持久化插件决定，内核无从得知。
- **插件停止不删条目**：那是用户会话里的数据，插件卸载后仍保留，重新装回来还能读到。

## 用量记账

- **`recordUsage` 有两个重载，别用错**：`LlmUsage` 那个是「一次调用」，恒定只加 1 次；子代理回合的累计用量走
  `SessionUsage` 那个，**把调用次数一并带过来**。
- 子代理的用量归集到父会话（那些 token 是真花掉的），**归集失败只记 WARN**。

## 跨边界载荷

- **跨边界载荷必须是 api 侧快照值类型**（`SessionSnapshot` 及嵌套），映射归
  `infra/session/SessionSnapshots`，并用**往返测试**守字段。
  当前 `SessionSnapshot` 携 `kind` / `parentSessionId` / `forkPointMessageId` / `extensionEntries`。
- **快照类型必须恰好只有一个可见构造器**：新增字段用静态工厂，**不要加兼容构造器**。
- **`-parameters` 是全局编译约定，不许去掉**：插件侧 Jackson 靠构造器参数名反序列化，丢了会
  「文件写得出、重启后读不回」。

## 配置加载

- **`AppConfig` 绑定 `classpath:config.json`，只有它声明各配置文件位置与插件扫描目录**；
  默认全局 `~/.jellyfish/`、项目 `./.jellyfish/`。`SettingsBinder` 做 `${ENV_VAR}` 插值（`\${VAR}` 转义）。
- **插件扫描目录在 `config.json` 的 `plugins.roots`**，**不参与双源合并**；展开行首 `~`、丢弃空白条目，
  空列表回退默认目录 `plugins`。
- **四份配置对四类配置类**：config→`AppConfig`、models→`ModelSettings`、agents→`AgentSettings`、
  jellyfish→`JellyfishSettings`（plugins / react / permission / subAgent 四段）；`classpath:default-agent.json`
  是内置只读定义，**不走双源**。
- **资源跟着读者走**：`default-agent.json` / `{agentId}.md` 归 infra，`config.json` / `log4j2*.xml` 归 cli——
  否则换 composition root 时会以「内置 agent 缺失」启动失败而单测全绿。（摘要指令等资源归各自插件。）
- **agent 提示词来自同目录 `{agentId}.md`**，JSON 里的 `systemPrompt` 被忽略；**默认 agent 恒为内置**
  （启动与新建会话都绑它，只能 `/agent` 切换）。非法 `agentId` 整条丢弃并告警，用户与内置同名时**保留内置**。
- **配置驱动的索引在启动期建立**：构造期只建空索引，`AgentHarness.bootstrap()` 里 `runtimeConfig.refresh()`
  之后才装载；**`PluginRuntimeConfig` 必须在 `pluginManager.bootstrap()` 之前刷新**。
- **`global` / `project` 合并**：同名 provider / agent / 插件配置段以 project **整对象**覆盖；列表段项目级
  已声明则整体替换（写 `[]` 即清空）。`subAgent` 段同口径——四个参数互相牵制（关掉开关时其余三项无意义），
  「一半来自全局、一半来自项目」会让「这个项目到底允许多深的委派」无法从任何单份文件看出来。
- **「字段缺失」≠「显式空数组」**：`allowedTools` / `plugins.enabled` 缺失（`null`）表示不限制，`[]` 表示
  一个都不放行 / 不启用；归一成空集合会让 `[]` 退化成 fail-open。`plugins.roots` 不适用。

## agent 定义的两个字段

`AgentDefinition` 上这两个字段各只有一个含义，不要把它们当成别的意思：

- **`delegatable`**（缺省 `false`）只回答「能不能被 `task` 当作目标」，**不**回答「它自己能不能再往下委派」
  （后者由深度上限 + 它自己的 `allowedTools` 是否含 `task` 决定）。
- **`model`** 是模型引用的最低一级回落（会话显式 → `agent.model` → 全局默认），写法与 `/model` 参数一致
  （`provider/model` 或裸 model 名），由 `ModelManager.resolveReference` 统一解析——`/model` 命令与它共用同一份。

## 热更新

- **顺序固定**：`modelManager.refresh(true)`（唯一重读文件 + 清客户端缓存）→ `agentManager.refresh(false)` →
  `pluginRuntimeConfig.refresh` → 比对插件配置段 → `pluginManager.reload` → 广播 `ConfigReloadedEvent`。
- **`synchronized` 单飞，不回滚，触发只有 `/reload`**。
- **「重启插件」= stop + start**，成立前提是能力上下文在每次 `start()` 现造（`JellyfishPluginAdapter` 持有
  `Supplier<PluginContext>`）；PF4J 插件实例在装载期缓存，**stop 不会重置它**。
- **`config.json` 不参与热更新**（部署事实），新增 / 删除插件 jar 也仍需重启。

## 待生效默认值不落盘

**首页设的「待生效默认值」是运行态，不是配置**：`SessionDefaults` 纯内存、进程退出即失效，**绝不写回任何配置文件**。

写配置文件是另一整层能力（写全局还是项目级？项目级覆盖时写全局等于无效；格式保真；与 `/reload` 的顺序），
而 `-cli --model x` 今天也是进程级的，语义保持一致、不制造第二套「默认」。它只盖在配置默认值上面
（字段为 `null` 表示「这一项继续跟随更下层」），并由 `SessionManager.create` 在建会话那一刻消费。

## 模型解析三级回落

**收在一处**：会话显式 → `agent.model` → 全局默认，由 `SessionModelResolver` 实现；`ReActLooper` 与
`ConversationCompactor` 共用它，因为**压缩必须按同一个模型的窗口裁剪、花同一个模型的额度**。
**子代理不继承父会话的模型**（子会话的 provider / model 留空，于是自然落到第 2 级）。
