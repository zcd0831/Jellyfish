# Command 模块落地方案

> 状态：**已全部裁决（Q1 / Q3 / Q4 / Q5 / Q8 / Q10 / Q13 / Q14 / Q15）并落地完成**，见 §11 裁决记录与 §12 落地记录
> 范围：交付 `api/extension` 的命令契约（新增 `CommandDescriptor` / `CommandArguments` / `CommandResult`，收紧 `CommandRequest`）+ `infra/command`（`CommandManager` + 命令行解析 + 结构化入口与清单）+ `infra/extension` 新增 `descriptorBindings(...)` + DI 装配 + 单测 + 文档同步；
> **不接**任何外壳（CLI / TUI / Server / Web 都还没落地）、**不实现**任何系统命令（`/help` / `/model` / `/agent` / `/mode` / `/new` / `/exit` 一律留 `TODO`，见 §9 L9）、**不做**补全与交互、**不发**命令审计事件。
>
> **服务面澄清（用户口径）**：命令域**不是 CLI 专属**。TUI / Server / Web 同样会用命令创建会话、切换模型 / agent 等；因此本模块的入口**不假设输入一定是一行原文**，输出**不假设一定打到终端**（见 §11.2 的 Q13～Q15）。

## 0. 口径草案与裁决状态

| # | 事项 | 口径 | 落地含义 | 裁决 |
| --- | --- | --- | --- | --- |
| 1 | `CommandRequest` 契约 | **收紧**：`命令名 + CommandArguments + sessionId`，结果类型 `CommandResult`；删掉泛化的 `payloadType` / `payload` | 与 `ToolCallRequest` / `PermissionCheckRequest` 一致：域数据是类型化字段，处理器不用强转 | **已裁决：收紧**（Q1） |
| 2 | 参数载体 | 新增 `api/extension/CommandArguments`：`tokens`（切分结果）+ `raw`（原文） | 结构化参数与自由文本各取所需，处理器不再自己切字符串 | 默认 |
| 3 | 结果载体 | 新增 `api/extension/CommandResult`：三态 `OK / ERROR / UNKNOWN` + `output` | `UNKNOWN` 让外壳区分「没这条命令（建议 /help）」与「命令执行失败」 | **已裁决：够**（Q3） |
| 4 | 命令清单取数面 | `ExtensionRegistry` 新增 `descriptorBindings(type, descriptorType)`，返回 `owner + routeKey + 可空描述符` | 帮助与菜单必须列出「没有描述符但可执行」的命令，现有 `descriptors()`（跳过 null）满足不了 | **已裁决：加**（Q4） |
| 5 | `CommandDescriptor` 字段 | `summary` / `usage` / `aliases`，**不含 name**（名字＝注册时的路由键） | 单一事实来源，杜绝「描述符里的名字 ≠ 路由键」这本错账 | **已裁决：行**（Q5） |
| 6 | 别名冲突 | 冲突别名**不可用**：使用时报错并列出候选 + WARN（不用别名时不打扰） | 不做「先注册者赢」这种隐式仲裁 | 默认（Q6） |
| 7 | 未知命令 / 非命令输入 | `execute` 返回结果而不是抛异常：非命令 → `UNKNOWN`，无处理器 → `UNKNOWN`，框架/处理器异常 → `ERROR` | 命令层是用户输入面，插件缺陷不该让外壳崩；WARN 日志保留线索 | 默认（Q7） |
| 8 | 系统命令 | **本轮不做，留 `TODO`**：`/help` `/model` `/agent` `/mode` `/new` `/exit` 依赖 `SessionManager` 与外壳；`CommandManager` 也不注册任何处理器 | 与「CommandMgr 只做解析与帮助」一致，避免自注册回环 | **已裁决：后面做**（Q8） |
| 9 | 事件边 | `CommandManager` **只注入 `ExtensionRegistry`**，不发任何事件（架构图里没有 `CommandMgr -.-> EventCh`） | 别名冲突等只打 WARN 日志 | 默认（Q9） |
| 10 | 会话标识 | `execute(..., sessionId)` 由调用方显式传入；`infra/command` **不依赖** `infra/session` | 架构图只有「外壳 ==> CommandMgr」一条入边，会话由外壳自己知道 | **已裁决：按推荐**（Q10） |
| 11 | 大小写与前缀 | 前缀固定 `/`，命令名**大小写敏感**，不做任何归一化 | 与 Unix 习惯一致；插件命令名不会因归一化而互相撞车 | 默认 |
| 12 | 引号未闭合 | 视作**解析错误**（`ERROR` + 明确文案），不猜用户意图 | 静默吞掉半个引号比报错更难排查 | 默认 |
| 13 | 原文入口 | `execute(input, sessionId)`：给「输入框」这类外壳用 | CLI / TUI 输入框、Web 的文本框都走它 | 默认 |
| 14 | 结构化入口 | `execute(名字, CommandArguments, sessionId)`：给「本来就有结构化参数」的外壳用 | Web API / TUI 菜单可直接调用，不必拼成一行再让内核拆回来 | **已裁决：提供**（Q14） |
| 15 | 结构化清单 | `commands()` → `List<CommandInfo>`；`renderHelp()` 只是它的文本渲染 | Web 下拉 / TUI 菜单自己排版，不去解析对齐过的文本 | **已裁决：提供**（Q15） |
| 16 | 结果是否带机器可读数据 | **不带**：副作用写回 `SessionManager`，外壳执行后读会话域拿状态 | 例如 `/new`：处理器建好并切换当前会话，Web 直接问 `SessionManager.current()`，不必从文本里抠标识 | **已裁决：不带**（Q13） |
| 17 | 索引与缓存 | **不建索引、不缓存**：每次执行 / 渲染时从注册表现算 | 人类节奏下 O(命令数) 可忽略，换来插件热部署天然正确 | 默认 |
| 18 | DI | 新增空的 `CommandModule` + `JellyfishComponent.commandManager()` | 与 `PermissionModule` 同构（`@Inject` 构造器即可，无需 `@Provides`） | 默认 |
| 19 | `AGENTS.md` | 本轮一并更新（§5.1） | 文档与实现同批交付 | 默认 |

## 1. 现状基线（实测）

- `api/extension/CommandRequest` **已存在但很泛化**：`ExtensionRequest<Object>` + `name + payloadType + payload + sessionId`，注释解释泛化载荷是为了「插件不定义新 Java 类型」。全仓库无任何调用点，只有 `CommandRequestTest` 与若干把 `CommandRequest` 当**通用测试载体**的测试（见 §5）。
- `infra/command/` 是**空目录**；`infra/metrics/` 同样为空（本轮不动）。
- `ExtensionRegistry` 现有调用面：`handle` / `contribute` / `handler`（唯一，0 个 `NO_HANDLER`、多个 `AMBIGUOUS_HANDLER`）/ `handlers` / `bindings` / `invoke` / `descriptors` / `unregisterAll` / `snapshot`。其中 `descriptors(type, descriptorType)` **会跳过 `null` 描述符**，除测试外无人使用。
- `TypeRegistry.registrationsOf(type)` 能给出 `routeKey` + `descriptor` + `owner`，但那是底座原语，策略层刻意不暴露（权限轮的做法是给 `ExtensionRegistry` 加 `bindings(...)`，而不是让调用点去摸底座）。
- **任何外壳都尚未落地**：`cli/mode/` 为空，没有 `JellyfishApplication` / `Launcher` / `main`，也没有 TUI / Server / Web 实现，因此「命令怎么被调起来」没有任何既成事实——本模块必须**对外壳中立**（不得假设「输入＝终端里的一行」「输出＝打到 stdout」）。
- **没有任何系统命令的实现或清单**（全仓库搜不到 `/help`、`/exit` 之类），所以「系统命令」本轮不存在可复用资产。
- `SessionManager` 已提供会话级切换入口：`create` / `switchTo` / `current` / `bindAgent` / `switchModel` / `setPermissionMode` / `updateTitle` / `close`；`AgentManager.resolveDefault()` / `ModelManager.resolveDefault()` 已就绪。这些都是**将来系统命令处理器**的依赖，不是本模块的。
- `AGENTS.md` 对此模块的口径（必须逐句落地）：`CommandManager` = 「输入解析 / 别名 / 参数切分 / 帮助渲染 / 按类型查询注册表」；架构图两条出边 `解析后分发（命令名 + 参数）==> ExtReg`、`按类型查询命令描述符（名 / 别名 / 帮助）==> ExtReg`；「系统命令与插件命令同源」；注释「CommandMgr 只做解析与帮助」。

## 2. 目标态

### 2.1 依赖边（本轮新增）

```mermaid
flowchart LR
    SHELL["外壳<br>CLI / TUI / Server / Web<br>（本轮都未落地）"]
    CM["CommandManager<br>infra/command"]
    EXT["ExtensionRegistry<br>infra/extension"]
    REG["TypeRegistry<br>infra/registry（共用一份表）"]
    SYS["系统命令处理器<br>（本轮不落地，将来在 core，owner=\"core\"）"]
    PLG["插件命令处理器<br>（插件经 PluginContext.handle）"]

    SHELL ==>|"① 原文行 + sessionId（输入框）"| CM
    SHELL ==>|"② 命令名 + 参数 + sessionId（Web / TUI 直接调用）"| CM
    CM ==>|"③ CommandResult / commands() / renderHelp()"| SHELL
    CM ==>|"handler(CommandRequest, 名字) + invoke（调用点线程内联取值）"| EXT
    CM ==>|"descriptorBindings(CommandRequest, CommandDescriptor)（名字 / 别名 / 帮助）"| EXT
    EXT -->|"resolve / registrationsOf"| REG
    SYS ==>|"handle(CommandRequest, 名字, CommandDescriptor, handler)"| EXT
    PLG ==>|"同一入口，owner=pluginId"| EXT
```

- **只有一条新依赖边**：`infra/command → infra/extension`（外加既有 `command → api`）。`CommandManager` 不注入 `SessionManager`、不注入 `EventPublisher`、不注入配置。
- **对外壳一视同仁**：CLI / TUI / Server / Web 谁调都一样，三个入参（原文或名字 / 参数 / 会话标识）都由外壳提供，出参是一个 `CommandResult` 或结构化清单，怎么渲染是外壳的事。
- **命令的副作用不回流到结果里**：切模型、建会话这类动作由处理器写回会话域（`SessionManager`），外壳在命令返回后读会话域拿最新状态；`CommandResult` 只承载「给人看的文本」（Q13）。
- 图里那条 `SYS ==> EXT` 本轮**没有实现**（Q8），画出它是为了说明将来落点与 owner 归属（`"core"`，与插件 `pluginId` 区分）。

### 2.2 包结构

```
jellyfish-api/src/main/java/zcd/jellyfish/api/extension/
├── CommandRequest.java        # 【改】收紧为「命令名 + CommandArguments + sessionId」，结果类型 CommandResult
├── CommandDescriptor.java     # 【新】命令名片：summary / usage / aliases（随 handler 一起落表）
├── CommandArguments.java      # 【新】解析后的参数：tokens + raw
└── CommandResult.java         # 【新】命令执行结果：OK / ERROR / UNKNOWN + output

jellyfish-infra/src/main/java/zcd/jellyfish/infra/extension/
├── DescriptorBinding.java     # 【新】带 owner 与 routeKey 的描述符绑定（描述符可空）
└── ExtensionRegistry.java     # 【改】新增 descriptorBindings(...)；descriptors(...) 改为复用它

jellyfish-infra/src/main/java/zcd/jellyfish/infra/command/
├── CommandManager.java        # 【新】命令域服务：判定 / 解析 / 别名解析 / 分发 / 清单与帮助
├── CommandInfo.java           # 【新】结构化清单项：命令名 + 名片（可空）
├── CommandLineParser.java     # 【新，包私有】前缀判定 + 引号感知切分 + 原文提取
└── ParsedCommand.java         # 【新，包私有】解析结果：是否命令 / 名字 / 参数 / 解析错误

jellyfish-cli/src/main/java/zcd/jellyfish/cli/di/
├── CommandModule.java         # 【新】空模块（与 PermissionModule 同构）
└── JellyfishComponent.java    # 【改】新增 commandManager()
```

**为什么 `CommandDescriptor` 放 api**：请求类型即身份——插件拿不到描述符类型就没法给命令起别名、写帮助。
**为什么解析器与解析结果放 infra 且包私有**：切分规则是内核实现细节，插件只消费 `CommandArguments`，不感知原文的切分过程。
**为什么 `CommandInfo` 放 infra/command 而不是 api**：它是内核给外壳的**视图对象**，插件既不生产也不消费它；放在命令域包内可自由演进。

### 2.3 职责一览

| 类 | 做什么 | 不做什么 |
| --- | --- | --- |
| `CommandManager`（infra/command） | 判定输入是不是命令；解析命令名与参数；解析名字/别名；经 `ExtensionRegistry` 取出唯一处理器并内联执行；把框架与处理器异常翻译成结果；产出结构化清单与帮助文本 | 不注册任何处理器、不实现系统命令、不改会话、不读配置、不发事件、不缓存索引 |
| `CommandLineParser`（包私有） | 前缀判定、命令名提取、引号感知切分、原文提取、未闭合引号报错 | 不查注册表、不认识别名、不构造 `CommandRequest` |
| `CommandInfo`（infra/command） | 一条命令的结构化视图：命令名 + 名片（可空），供外壳自行排版 | 不做渲染、不含 owner、不含 handler |
| `CommandDescriptor`（api） | 只表达「这条命令怎么被找到与展示」：别名、一句话说明、用法 | 不知道自己叫什么（名字是路由键）、不校验参数、不执行 |
| `CommandArguments`（api） | 只表达「这一行剩下的东西」：切分后的 tokens + 原文 | 不做类型转换、不做必填校验、不做选项解析 |
| `CommandResult`（api） | 只表达「这次执行的结果长什么样」：三态 + 可渲染文本 | 不携带会话状态与机器可读数据、不表达退出信号、不知道输出到哪 |
| `DescriptorBinding`（infra/extension） | 一次注册的「名字 + 名片 + 来源」只读投影 | 不执行任何处理器、不参与派发 |

**为什么不给命令域单开一个 `CommandRegistry`**：命令的注册表**就是** `TypeRegistry`（`AGENTS.md`：工具与命令只是类型不同，不存在第二份注册表）。命令域需要的只是「按类型取名字与名片」这一个查询面，加在 `ExtensionRegistry` 上即可。

## 3. 关键签名（Java 8）

### 3.1 api/extension

```java
/**
 * 命令名片：插件注册命令处理器时与处理器一起落表的「命令说明」。
 * <p>
 * <b>不含命令名</b>：名字就是注册时的路由键，避免「名片上的名字 ≠ 路由键」这本错账；
 * 它是给人看的信息（帮助与菜单）与给解析用的信息（别名）。
 */
public final class CommandDescriptor {

    /** 一句话用途说明，帮助列表里显示。 */
    private final String summary;

    /** 用法片段（如 {@code "<agentId>"}），可省略；不含命令名本身。 */
    private final String usage;

    /** 别名列表（不含前缀与外层命令名），可为空。 */
    private final List<String> aliases;

    /**
     * @param summary 一句话说明，可为 {@code null}
     * @param usage   用法片段，可为 {@code null}
     * @param aliases 别名列表，可为 {@code null}
     * @throws JellyfishException 别名为空白、含空白字符、以命令前缀（{@code /}）开头时抛出
     */
    public CommandDescriptor(String summary, String usage, List<String> aliases);

    public String getSummary();
    public String getUsage();

    /** @return 不可变别名列表，保证非 {@code null} */
    public List<String> getAliases();
}
```

```java
/**
 * 命令参数：一行输入里「命令名之后」的部分，两种视图并存。
 * <p>
 * {@code tokens} 用于结构化命令（{@code /agent coder}），{@code raw} 用于自由文本命令
 * （{@code /note 记得明天改配置}）；两者都由内核切分产出，处理器不需要自己拆字符串。
 * <p>
 * 结构化入口（Web / TUI 直接调用）也复用它：调用方自己给 tokens 与 raw，<b>不经过切分</b>。
 */
public final class CommandArguments {

    /** 空参数：无 token、空原文。 */
    public static final CommandArguments EMPTY = new CommandArguments(null, null);

    /** 切分后的参数，不含命令名。 */
    private final List<String> tokens;

    /** 命令名之后的原文（仅去掉紧随命令名的那段空白），可为空串。 */
    private final String raw;

    /**
     * @param tokens 切分结果，可为 {@code null}（等价空列表）
     * @param raw    原文，可为 {@code null}（等价空串）
     */
    public CommandArguments(List<String> tokens, String raw);

    /** @return 不可变 token 列表，保证非 {@code null} */
    public List<String> getTokens();

    /** @return 原文，保证非 {@code null}（可能为空串） */
    public String getRaw();

    /** @return 无 token 返回 {@code true} */
    public boolean isEmpty();

    /** @return token 数量 */
    public int size();
}
```

```java
/**
 * 命令执行结果：三态 + 可渲染文本。
 * <p>
 * {@code UNKNOWN} 与 {@code ERROR} 刻意分开：前者是「没有这条命令」（外壳可以提示 /help，
 * 也可以自行决定要不要把原输入交给 LLM），后者是「命令存在但这次没成」。
 * <p>
 * <b>只承载给人看的文本</b>：命令的副作用（切模型、建会话……）落在会话域里，
 * 需要机器可读结果的调用方在命令返回后去读对应域服务，而不是从这里抠字段。
 */
public final class CommandResult {

    /** 结果三态。 */
    public enum Kind {
        /** 命令已执行。 */
        OK,
        /** 命令存在，但执行失败。 */
        ERROR,
        /** 没有这条命令（或输入不是命令）。 */
        UNKNOWN
    }

    private final Kind kind;
    private final String output;

    /** @param output 可渲染文本，可为 {@code null}（命令只做副作用，无输出） */
    public static CommandResult ok(String output);
    public static CommandResult error(String output);
    public static CommandResult unknown(String output);

    public Kind getKind();

    /** @return 可渲染文本，可能为 {@code null} */
    public String getOutput();

    /** @return {@code ERROR} 或 {@code UNKNOWN} 返回 {@code true}（外壳据此选输出流 / 错误样式） */
    public boolean isError();
}
```

```java
/**
 * 具名命令请求：命令名 + 参数 + 会话标识，由内核在命令调用点构造。
 * <p>
 * 路由键即命令名，因此每条命令对应一个处理器（{@code PluginContext.handle}）。
 * 名字里的「命令」<b>不是「只有插件能用」</b>：系统命令与插件命令注册在同一份注册表里，
 * 方向始终是内核在调用点构造本请求、处理器响应。
 * <p>
 * 输入可能是「用户敲的一行」，也可能是外壳直接给的结构化参数；两种情况都由内核归一化成
 * {@link CommandArguments} 后带进来，处理器不需要也不应该自己拆字符串。
 *
 * @author zcd
 */
public final class CommandRequest extends ExtensionRequest<CommandResult> {

    private final String name;
    private final CommandArguments arguments;

    /**
     * @param name       命令名，不可为空白
     * @param arguments  参数，可为 {@code null}（按 {@link CommandArguments#EMPTY} 处理）
     * @param sessionId  会话标识，可为 {@code null}
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandRequest(String name, CommandArguments arguments, String sessionId);

    /** 进程级命令请求（无会话）。 */
    public CommandRequest(String name, CommandArguments arguments);

    @Override
    public String getRouteKey();          // 返回 name

    public String getName();

    /** @return 参数，保证非 {@code null} */
    public CommandArguments getArguments();
}
```

### 3.2 infra/extension：带名字与来源的描述符查询面

```java
/**
 * 描述符绑定：一次注册的「路由键 + 名片 + 来源」只读投影。
 * <p>
 * 与 {@code HandlerBinding} 的分工：那个给「要归因的执行调用点」用（owner + handler），
 * 这个给「要清单的查询调用点」用（owner + routeKey + descriptor）。
 * <p>
 * <b>描述符允许为 {@code null}</b>：不给命令写名片的插件确实存在，而它注册的命令一样可执行，
 * 帮助与菜单不能把它漏掉。
 */
public final class DescriptorBinding<D> {

    private final String owner;
    private final String routeKey;   // 可为 null（类型级注册）
    private final D descriptor;      // 可为 null

    /**
     * @param owner      来源（内核组件名或 pluginId），不可为空白
     * @param routeKey   路由键，可为 {@code null}
     * @param descriptor 描述符，可为 {@code null}
     */
    public DescriptorBinding(String owner, String routeKey, D descriptor);

    public String getOwner();
    public String getRouteKey();
    public D getDescriptor();
}
```

```java
// ExtensionRegistry 新增方法；既有方法对外行为一律不变
    /**
     * 列出某类型下全部注册的「名字 + 描述符 + 来源」。
     * <p>
     * 与 {@link #descriptors} 同一条取数路径、同一顺序（{@code order} 升序、同序按注册顺序），
     * 两点差别：① 连 owner 与 routeKey 一起给；② <b>描述符为 {@code null} 的注册也会返回</b>
     * ——清单类调用点（命令帮助与菜单）需要看见「没有名片但存在」的项。
     *
     * @param type           请求类型，不可为 {@code null}
     * @param descriptorType 期望的描述符类型，不可为 {@code null}
     * @param <D>            描述符类型
     * @return 不可修改的绑定列表；无注册时为空列表
     * @throws ExtensionException 存在非空描述符但类型不符时抛出
     */
    public <D> List<DescriptorBinding<D>> descriptorBindings(Class<? extends ExtensionRequest<?>> type,
                                                             Class<D> descriptorType);

    // descriptors(...) 改为基于 descriptorBindings(...) 过滤掉 null 后映射，既有单测必须继续全绿
```

**为什么不让 `CommandManager` 直接注入 `TypeRegistry`**：那会绕过同步派发策略层，且 `HandlerRegistration.getDescriptor()` 是 `Object`，调用点得做无类型保障的强转；`descriptorBindings` 像 `descriptors` 一样做类型化投影并当场报 `DESCRIPTOR_TYPE_MISMATCH`。这与权限轮加 `bindings(...)` 是同一个做法。

### 3.3 infra/command：对外服务面

```java
/**
 * 命令域服务：输入解析 / 别名解析 / 分发 / 结构化清单 / 帮助渲染。
 * <p>
 * <b>只注入 {@link ExtensionRegistry}</b>：命令域不持有会话、不发事件、不读配置——
 * 会话标识由调用方随输入一起带进来，命令的副作用由处理器自己写回对应域服务。
 * <p>
 * <b>不注册任何处理器</b>（`TODO` 系统命令）：系统命令与插件命令都经
 * {@code ExtensionRegistry.handle(...)} 落表，本类只做「按名字取出来执行」与「按类型取名字与名片」。
 * <p>
 * <b>对外壳中立</b>：原文入口服务输入框（CLI / TUI / Web 文本框），结构化入口服务直接调用
 * （Web API / TUI 菜单），结构化清单服务自排版的外壳；本类不假设输出到哪里。
 * <p>
 * <b>无索引无缓存</b>：每次执行 / 渲染都从注册表现算，因此插件热部署后立刻可见；
 * 命令是人类节奏的调用，O(命令数) 的重建可以忽略。
 * <p>
 * <b>公共入口不抛异常</b>：用户输入错误与插件缺陷都被翻译成 {@link CommandResult} 或清单 / 文案
 * （另打 WARN 日志）。唯一的例外是 api 侧值对象的构造期校验——那是编程错误，立即抛。
 *
 * @author zcd
 */
@Singleton
public class CommandManager {

    /** 命令前缀：只有以它开头的输入才进入命令域。 */
    public static final String COMMAND_PREFIX = "/";

    @Inject
    public CommandManager(ExtensionRegistry extensions);

    /**
     * 判断一行输入在语法上是不是命令（以 {@code /} 开头且命令名非空）。
     * <p>
     * 只做语法判定、不查注册表：外壳据此决定「走命令还是走 LLM」，
     * 「有没有这条命令」由 {@link #execute} 的 {@code UNKNOWN} 结果回答。
     *
     * @param input 用户输入原文，可为 {@code null}
     * @return 语法上是命令返回 {@code true}
     */
    public boolean isCommand(String input);

    /**
     * 列出全部命令的结构化清单，按命令名升序。
     * <p>
     * 给「要自己排版」的外壳用：TUI 菜单、Web 下拉、Server 的 RPC 返回值；
     * 文本外壳直接用 {@link #renderHelp()}，它就是基于本方法渲染的。
     *
     * @return 不可修改的命令清单；没有任何命令时为空列表
     */
    public List<CommandInfo> commands();

    /**
     * 原文入口：执行一行命令（进程级，无会话）。
     *
     * @param input 用户输入原文（如 {@code "/help"}），可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String input);

    /**
     * 原文入口：执行一行命令。
     *
     * @param input     用户输入原文（如 {@code "/agent coder"}），可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String input, String sessionId);

    /**
     * 结构化入口：外壳直接给定命令名与参数，跳过切分但走同一条分发路径。
     * <p>
     * 给 Web / TUI 这类「本来就有结构化参数」的调用方用（不必把参数拼成一行原文再让内核拆回来）；
     * 别名解析、唯一性判定与异常处置与原文入口完全一致。
     *
     * @param commandName 命令名或别名，不可为空白
     * @param arguments   参数，可为 {@code null}（按 {@link CommandArguments#EMPTY} 处理）
     * @param sessionId   会话标识，可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     */
    public CommandResult execute(String commandName, CommandArguments arguments, String sessionId);

    /**
     * 渲染全量命令帮助：按命令名升序，列出用法、说明与别名。
     *
     * @return 帮助文本，保证非 {@code null}
     */
    public String renderHelp();

    /**
     * 渲染单条命令帮助，命令名或别名都可以查。
     *
     * @param nameOrAlias 命令名或别名，可为 {@code null}
     * @return 帮助文本，保证非 {@code null}
     */
    public String renderHelp(String nameOrAlias);
}
```

### 3.4 infra/command：结构化清单项与包私有解析器

```java
/**
 * 结构化清单项：外壳自渲染菜单 / 下拉 / RPC 返回值时的最小视图。
 * <p>
 * 与 api 侧 {@link CommandDescriptor} 的分工：名片是插件写的（别名 / 说明 / 用法），
 * 本类是内核投影出来的（命令名 + 名片），因此把「没写名片」这件事表达成可空字段，
 * 并给出空安全的便捷读取，免得每个外壳各写一遍判空。
 */
public final class CommandInfo {

    /** 命令名（即注册时的路由键）。 */
    private final String name;

    /** 命令名片，插件未提供时为 {@code null}。 */
    private final CommandDescriptor descriptor;

    /**
     * @param name       命令名，不可为空白
     * @param descriptor 命令名片，可为 {@code null}
     * @throws JellyfishException 命令名为空白时抛出
     */
    public CommandInfo(String name, CommandDescriptor descriptor);

    public String getName();

    /** @return 名片，可能为 {@code null} */
    public CommandDescriptor getDescriptor();

    /** @return 别名列表，无名片时为空列表 */
    public List<String> getAliases();

    /** @return 一句话说明，无名片时为 {@code null} */
    public String getSummary();

    /** @return 用法片段，无名片时为 {@code null} */
    public String getUsage();
}
```

```java
/** 包私有：把一行输入解析成「是否命令 / 命令名 / 参数 / 解析错误」，不查任何注册表。 */
final class CommandLineParser {

    static ParsedCommand parse(String input);
}

/** 包私有：一行输入的解析结果。 */
final class ParsedCommand {

    /** 语法上是不是命令（前缀 + 非空命令名）。 */
    boolean isCommand();

    /** 命令名（不含前缀）；非命令时为 {@code null}。 */
    String name();

    /** 解析出的参数；非命令时为空参数。 */
    CommandArguments arguments();

    /** 解析错误文案（引号未闭合等）；无错误时为 {@code null}。 */
    String error();
}
```

## 4. 语义

### 4.1 输入语法与切分规则（`CommandLineParser`）

| 规则 | 口径 | 例子 |
| --- | --- | --- |
| 前缀 | 先去首尾空白，首字符必须是 `/`；否则非命令 | `"  /help"` → 命令；`"help"` → 非命令 |
| 命令名 | 前缀之后到第一个空白为止，必须非空 | `"/"`、`"/   "` → **非命令**；`"/sql:query"` → `sql:query` |
| 原文 `raw` | 命令名之后到行尾，只去掉紧随命令名的那段空白，其余原样保留（含引号与内部空白） | `"/note   a  b"` → `raw = "a  b"` |
| 切分 `tokens` | 按空白（空格 / Tab）切分，`"` 与 `'` 成对包裹，引号内空白不切分、引号字符本身不进入 token | `"/m a \"b c\""` → `[a, b c]` |
| 显式空参数 | `""` 产出**一个空 token** | `"/m \"\""` → `[""]` |
| 转义 | 仅双引号内：`\"` → `"`、`\\` → `\`；引号外一律字面量 | `"/m \"a\\\\b\""` → `[a\b]` |
| 未闭合引号 | 解析错误（`ERROR`），不做「读到行尾」的猜测 | `"/m \"abc"` → `ERROR: 引号未闭合` |
| 大小写 | 敏感，不归一化 | `/Help` 不等于 `/help` |
| `//x` | 前缀只剥一层，命令名就是 `/x`（查不到即 `UNKNOWN`） | 不做「双斜杠」特例 |

**为什么保留 `raw`**：自由文本命令（`/note`、`/ask`）本来就该拿到原话；只给 tokens 会逼它在处理器里重新拼回去，而拼接必然丢信息（多个连续空格、原引号形态）。**结构化入口不需要切分**，但同样填 `raw`（由调用方给），这样处理器只有一种读取姿势。

### 4.2 名字与别名

取数：`extensions.descriptorBindings(CommandRequest.class, CommandDescriptor.class)` → 得到 `routeKey + descriptor + owner` 列表。由此现算：

1. **命令名集合** = 全部非空 `routeKey`（`null` 的路由键是类型级注册，不是一条命令）。
2. **别名索引** = `alias → 候选命令名列表`；同一别名被多条命令声明 → 候选数 > 1。

解析顺序（原文入口与结构化入口共用）：

| 输入 | 结果 |
| --- | --- |
| 恰好命中命令名 | 用该命令（**命令名优先**，不查别名） |
| 未命中命令名，别名唯一命中 | 用别名指向的命令 |
| 未命中命令名，别名命中多个 | `ERROR`：「别名 /m 有歧义，可能是 /model、/mode」+ WARN（含各自 owner） |
| 都没命中 | `UNKNOWN`：「未知命令：/x（输入 /help 查看可用命令）」 |
| 别名与某条命令同名 | 命令名优先，该别名**静默不可达**（见 §9 L2） |
| 命令没有描述符 | 仍可分派；清单与帮助里只显示名字 |

**为什么不做「先注册者赢」**：命令名与别名是人类输入面，静默仲裁会产生「同一行在不同机器上跑不同命令」这类不可复现问题；歧义当场报错更便宜。

### 4.3 分发流水线

```java
/** 原文入口：给「输入框」这类外壳用（CLI / TUI / Web 的单行输入）。 */
public CommandResult execute(String input, String sessionId) {
    ParsedCommand parsed = CommandLineParser.parse(input);
    if (!parsed.isCommand()) {
        return CommandResult.unknown("不是命令：" + input);
    }
    if (parsed.error() != null) {
        return CommandResult.error(parsed.error());
    }
    return dispatch(parsed.name(), parsed.arguments(), sessionId);
}

/** 结构化入口：给「直接调用」这类外壳用（Web API / TUI 菜单），跳过切分但仍走别名解析。 */
public CommandResult execute(String commandName, CommandArguments arguments, String sessionId) {
    return dispatch(commandName, arguments == null ? CommandArguments.EMPTY : arguments, sessionId);
}

/** 共享分发：名字优先、其次别名 → 取唯一处理器 → 调用点线程内联执行。 */
private CommandResult dispatch(String name, CommandArguments arguments, String sessionId) {
    Resolved resolved = resolve(name);                     // §4.2
    if (resolved == null) {
        return CommandResult.unknown("未知命令：/" + name + "（输入 /help 查看可用命令）");
    }
    if (resolved.error() != null) {
        return CommandResult.error(resolved.error());       // 别名歧义 / 描述符类型不符
    }

    // 一条命令一个处理器：不做链式、不做收集，因此用 fail-fast 的 handler(...)
    CommandRequest request = new CommandRequest(resolved.name(), arguments, sessionId);
    try {
        CommandResult result = extensions.invoke(resolved.handler(), request);
        return result == null ? CommandResult.ok(null) : result;
    } catch (RuntimeException e) {
        LOG.warn("命令执行失败: command={} sessionId={}", resolved.name(), sessionId, e);
        return CommandResult.error("命令执行失败：" + e.getMessage());
    }
}
```

语义要点：

- **两个入口共用分发**：原文入口只多做一次切分，别名解析、唯一性判定、异常处置完全一致——避免「同一命令两条路径两种行为」。
- **`handler(...)` 而不是 `handlers(...)`**：一条命令一个实现是命令域的硬约束；多命中说明有人在用 `contribute` 注册类型级命令处理器，当场暴露（`AMBIGUOUS_HANDLER` → `ERROR`，见 §9 L1）。
- **异常处置归调用点**：`invoke` 刻意没有护栏，所以在这里捕获 `RuntimeException`（`invoke` 已把受检异常包成 `JellyfishException`）→ WARN + `ERROR` 结果；这是「同步派发的护栏由调用方负责」的落地。
- **返回 `null` 合法**：`invoke` 允许 `null` 结果，命令域把它翻译成「执行成功、无输出」。
- **别名路径可能触发描述符查询**：`DESCRIPTOR_TYPE_MISMATCH`（有人把非 `CommandDescriptor` 塞进命令注册）在这里被捕获 → `ERROR` + WARN，不让用户输入把外壳打崩。
- **副作用不回填结果**：命令要改会话就自己调 `SessionManager`；外壳要状态就读会话域（Q13）。

### 4.4 结构化清单与帮助渲染

取数只有一个面：`commands()`（由 `descriptorBindings(...)` 投影出的「命令名 + 名片」，按命令名升序）。`renderHelp()` 只是它的**文本渲染**；非文本外壳（TUI 菜单 / Web 下拉 / Server RPC）直接用 `commands()` 自己排版，不去解析对齐过的文本。

文本格式确定、便于断言与肉眼扫读：

```
可用命令（3 条）：
  /agent <agentId>          切换当前会话绑定的 agent（别名：/a）
  /help [命令]              显示帮助（别名：/h、/?）
  /sql:query                （未提供说明）
```

- 左列 = `"/" + 命令名` + （`usage` 非空时 `" " + usage`），左侧对齐到 30 列，超出则用一个空格分隔。
- 右列 = `summary`，为空时用 `（未提供说明）` 占位；`aliases` 非空时追加 `（别名：/a、/b）`。
- 没有任何命令 → `当前没有任何可用命令。`
- 单命令帮助：

```
/help [命令]
  显示帮助（可用命令名或别名查询具体用法）。
  别名：/h、/?
```

- 查不到 → `未找到命令：/x（输入 /help 查看全部命令）`。
- 取数抛 `ExtensionException`（描述符类型不符）时：WARN + 返回单行 `命令清单读取失败：<code>`，**不抛出**（帮助是外壳的兜底入口，崩在这里最难看）。

### 4.5 明确不做的事

- **不注册任何处理器**（Q8）：`/help` 将来由系统命令处理器调用 `renderHelp()` 实现，`/model` `/agent` `/mode` `/new` `/exit` 依赖会话与外壳，属 CLI/TUI/Web 那一轮；落点见 §9 L9 的 `TODO`。
- **不实现「切模型 / 建会话」这类动作本身**：动作由处理器（将来持有 `SessionManager` / `ModelManager` / `AgentManager` 的内核组件）完成并写回会话域；外壳在命令返回后读 `SessionManager` 拿最新状态（例如 `/new` 建好并切好当前会话，Web 直接问当前会话是谁）。
- **不做参数 Schema 与必填校验**：命令参数是自由 token，语义由处理器自己定义（与工具的 `ToolDescriptor` 不同：工具要让模型理解参数，命令是给人用的）。
- **不发事件、不记审计**：架构图没有 `CommandMgr -.-> EventCh` 这条边；需要审计时由外壳或处理器自己发。
- **不做补全、历史、命令重命名**：外壳职责。

## 5. 触达点改造清单

| 文件 | 动作 |
| --- | --- |
| `jellyfish-api/.../api/extension/CommandDescriptor.java` | 新增 |
| `jellyfish-api/.../api/extension/CommandArguments.java` | 新增 |
| `jellyfish-api/.../api/extension/CommandResult.java` | 新增 |
| `jellyfish-api/.../api/extension/CommandRequest.java` | **改**：结果类型收紧为 `CommandResult`；`payloadType/payload` → `CommandArguments` |
| `jellyfish-api/test/.../api/extension/CommandRequestTest.java` | **改**：删掉 3 个载荷用例，改为参数与会话透传、名字空白校验、结果类型断言 |
| `jellyfish-infra/.../infra/extension/DescriptorBinding.java` | 新增 |
| `jellyfish-infra/.../infra/extension/ExtensionRegistry.java` | **改**：新增 `descriptorBindings(...)`；`descriptors(...)` 改为复用它（对外语义不变） |
| `jellyfish-infra/.../infra/command/CommandManager.java` | 新增 |
| `jellyfish-infra/.../infra/command/CommandInfo.java` | 新增 |
| `jellyfish-infra/.../infra/command/CommandLineParser.java` | 新增（包私有） |
| `jellyfish-infra/.../infra/command/ParsedCommand.java` | 新增（包私有） |
| `jellyfish-infra/test/.../extension/{ExtensionRegistryTest,HandlerBindingTest}.java` | **改**：`CommandRequest` 收紧后泛型与 lambda 返回值要跟着改（机械改动） |
| `jellyfish-infra/test/.../plugin/{PluginContextFactoryTest,PluginContextImplTest}.java`、`infra/event/EventChannelTest.java` | **改**：同上（这些测试把 `CommandRequest` 当通用载体）；`jellyfish-api/test/.../plugin/PluginContextTest.java` 同样 |
| `jellyfish-cli/.../cli/di/CommandModule.java` | 新增（空模块） |
| `jellyfish-cli/.../cli/di/JellyfishComponent.java` | **改**：新增 `commandManager()` |
| 新增单测 7 个类 + 既有测试改造 | 见 §6 |

**明确不改**：`TypeRegistry`、`EventChannel`、`PluginContext`、`PluginContextImpl`、`SessionManager`、`AgentManager`、`ModelManager`、`AgentHarness`、`ReActLooper`（全仓库无调用点）；`ExtensionRegistry` 除「新增一个方法 + `descriptors` 内部复用」外不动。

### 5.1 `AGENTS.md` 同步更新清单

| 位置 | 现文 | 改为 |
| --- | --- | --- |
| 代码结构 infra 包列表 | `├── command/  # 命令域服务 CommandManager：输入解析 / 别名 / 参数切分 / 帮助渲染，按类型查询注册表；系统命令与插件命令同源` | 补「本轮已落地：解析 / 别名 / 分发 / 结构化清单 / 帮助；系统命令续做（`CommandManager` 内有 `TODO`）」，并点明「命令域只注入 `ExtensionRegistry`，对外壳中立」 |
| 架构图 `CommandMgr` 节点 | `CommandManager<br>命令域服务：输入解析 / 别名 / 参数切分<br>帮助渲染 / 按类型查询注册表<br>系统命令与插件命令同源` | 措辞收敛为「解析 / 别名 / 分发 / 结构化清单 / 帮助（无缓存、不注册处理器、不持有会话）」 |
| 架构图 `CLI` 节点与出边 | `CLI ==>|"用户输入：命令原文（/xxx 参数）"| CommandMgr` | 节点名保留 `CLI`（是模块名），出边措辞改为「命令原文或结构化调用（cli / tui / server 任一外壳）」，避免读成 CLI 专属 |
| 架构要点「同步侧只提供有序查找…」 | 已列 `handlers` / `handler` / `invoke` / `bindings` | 补 `descriptorBindings`（连 `routeKey` 与 owner 一起给的描述符清单，供命令帮助 / 菜单这类「要清单」的调用点用） |
| 架构要点新增一条 | — | 「**命令域只解析与分发，不拥有命令**：`CommandManager` 不注册处理器、不持有会话、不发事件；命令名即路由键，别名与用法来自随 handler 落表的 `CommandDescriptor`；原文入口与结构化入口共用同一条分发路径，任一外壳（cli / tui / server）都可调用」 |
| api 包结构注释 `extension/` | 「扩展点对外模型（同步派发侧）…」 | 示例类型里补 `CommandDescriptor` / `CommandArguments` / `CommandResult` |

### 5.2 其他文档

- `跨语言插件方案.md`：`register_command` 的映射说明补一句参数形态——脚本注册的命令经**结构化入口语义**被调用（命令名 + `CommandArguments`：tokens + raw），并去掉对 `payloadType/payload` 的暗含依赖。

## 6. 测试计划

| 测试类 | 覆盖点 |
| --- | --- |
| `CommandDescriptorTest` | `summary`/`usage` 可空；`aliases` 只读且非 null、重复别名去重；别名为空白 / 含空白 / 带 `/` 前缀 → 抛 `JellyfishException` |
| `CommandArgumentsTest` | `EMPTY` 语义；`tokens` 只读且非 null；`raw` 非 null（可为空串）；`size`/`isEmpty` |
| `CommandResultTest` | 三态工厂、`getKind`、`isError`（`ERROR`/`UNKNOWN` 为真）、`output` 可空 |
| `CommandInfoTest` | `name` 透传、空白名抛错；`descriptor` 可空；名片缺失时 `getAliases` 为空列表、`getSummary`/`getUsage` 为 `null` |
| `CommandRequestTest`（改造） | 路由键 = 命令名；参数透传、`null` 参数 → `EMPTY`；`sessionId` 透传；名字空白 → 抛 `JellyfishException`；**结果类型是 `CommandResult`** |
| `DescriptorBindingTest` | getter 透传；owner 空白 → 抛；`routeKey` / `descriptor` 可为 `null` |
| `ExtensionRegistryTest`（新增用例） | `descriptorBindings` 的顺序、只读性、空列表、`null` 描述符也返回、owner 与 routeKey 正确、类型不符抛 `DESCRIPTOR_TYPE_MISMATCH`；`descriptors` 行为不变（跳过 `null`） |
| `CommandLineParserTest` | `@ParameterizedTest` 覆盖：非命令（`null`/空/无前缀/`"/"`/`"/  "`）；前缀与首尾空白；命令名含 `:`/`/`；`raw` 保留内部空白；单/双引号切分；`""` 空 token；双引号内转义；Tab 分隔；未闭合引号 → error；大小写敏感 |
| `CommandManagerTest` | ① 非命令 → `UNKNOWN` 且**不查注册表**；② 已注册命令 → 分发到该 handler、参数与 `sessionId` 透传；③ 别名唯一命中 → 同一 handler；④ 未知命令 → `UNKNOWN` 文案含「/help」；⑤ handler 抛 `RuntimeException` → `ERROR` + 不抛出；⑥ handler 返回 `null` → `OK` 空输出；⑦ `contribute` 造成的多命中 → `ERROR` 且**不调用任何 handler**；⑧ 描述符类型不符 → 别名路径 `ERROR`、不抛出；⑨ 帮助按命令名升序、含 usage/summary/别名、无命令占位；⑩ 无描述符的命令出现在帮助里；⑪ 别名歧义 → 使用时报 `ERROR` 并列出候选；⑫ 别名与命令名同名 → 命令名优先执行；⑬ 注册后立刻可见（无缓存）、`unregisterAll` 后不再可见；⑭ `renderHelp(名字/别名)` 命中与未命中；⑮ 只调用命中的那一个 handler（第二个 handler 做反证）；⑯ **结构化入口** `execute(名字, 参数, sessionId)` 与原文入口命中同一 handler 且参数一致；⑰ 结构化入口的别名解析与原文入口一致、`null` 参数按 `EMPTY`；⑱ `commands()` 按命令名升序、只读、空清单、含无描述符项，且与 `renderHelp()` 覆盖同一批命令 |
| `CommandModuleTest`（可选） | `CommandManager` 可被 Dagger 装配（`@Inject` 构造器 + 共用 `ExtensionRegistry`） |

约定：JUnit5 + Mockito，`@ExtendWith(MockitoExtension.class)`；`CommandManagerTest` 用**真实 `ExtensionRegistry` + 真实 `TypeRegistry`**（命令域的全部价值就在于「经注册表取 handler」，mock 掉它等于什么都没测）；只在需要制造框架异常（描述符类型不符 / 多命中）时 mock 或特制注册。方法命名 `{被测试方法}_should_{预期}_when_{条件}`。

## 7. 实施阶段（每阶段结束都可编译、可测试）

| 阶段 | 内容 | 结束判据 |
| --- | --- | --- |
| P1 | api 契约：`CommandDescriptor` / `CommandArguments` / `CommandResult` + 单测 | `mvn -q -pl jellyfish-api test` 全绿 |
| P2 | `CommandRequest` 收紧 + `CommandRequestTest` 改造 + 7 个既有测试的机械适配 | `mvn -q test-compile` 通过，api 测试全绿 |
| P3 | `infra/extension`：`DescriptorBinding` + `descriptorBindings(...)` + `descriptors` 复用 + 单测 | `ExtensionRegistryTest` 既有用例原样全绿、新增用例全绿 |
| P4 | `infra/command`：`CommandLineParser` / `ParsedCommand` / `CommandInfo` / `CommandManager` + 单测 | `CommandLineParserTest` / `CommandManagerTest` 全绿 |
| P5 | DI 装配 + 系统命令 `TODO` 落点 + `AGENTS.md` / `跨语言插件方案.md` 同步 + 自查（`@author zcd`、`@param`/`@return`、无 Java 9+ API、无无用 import、认知复杂度） | 全量 `mvn -q test` 全绿，文档与代码一致，`TODO` 可检索 |

## 8. 验收标准

1. `mvn -q test` 全绿；新增类行覆盖率 ≥ 90%（JaCoCo 报告人工核对，仓库无硬阈值）。
2. `CommandManager` **只有一条外部依赖边**（`ExtensionRegistry`）：`grep` 不到 `infra/command` 对 `session` / `event` / `config` 的引用。
3. 命令域**不注册任何处理器**：全仓库除测试外没有 `handle(CommandRequest.class, ...)` 的调用点。
4. 原文入口与结构化入口经**同一条分发路径**（同一 `dispatch`），单测 ⑯⑰ 钉住。
5. 清单与帮助覆盖「有描述符」与「无描述符」两类命令，且顺序确定；`commands()` 与 `renderHelp()` 数据同源。
6. 既有类除 `CommandRequest` 与 `ExtensionRegistry`（纯新增 + 内部复用）外行为零改动；`descriptors()` 既有单测原样全绿。
7. 所有新增类/接口/私有方法/成员变量有文档注释，类注释带 `@author zcd`，注释解释「为什么」。
8. 「系统命令未落地」留有可检索的 `TODO`（`CommandManager` 类注释 + `AGENTS.md`），且不影响插件命令的端到端可用性。
9. `AGENTS.md` 按 §5.1 更新完毕。

## 9. 已知限制（本轮接受，写进代码注释）

| # | 限制 | 影响 | 后续 |
| --- | --- | --- | --- |
| L1 | 类型级（`routeKey=null`）的 `CommandRequest` 注册是合法注册，但没有名字 | 若它是唯一注册 → 所有输入都命中它（事实上成为兜底命令）；若与具名注册共存 → 所有命令 `AMBIGUOUS_HANDLER` → `ERROR` | 命令域约定只用 `handle`；`CommandRequest` 类注释写明。真要兜底命令，改在调用点显式实现 |
| L2 | 别名与某条命令同名时命令名优先 | 该别名静默不可达 | 文档写明；将来可在清单渲染时提示冲突 |
| L3 | 命令参数没有 Schema 与必填校验 | 处理器要自己判 `tokens` 数量并返回 `ERROR` | 与「命令是给人用的」一致；工具那边才有 `ToolDescriptor` |
| L4 | 帮助是纯文本（`commands()` 已提供结构化替代） | 想要更多元信息（分组、权限、参数类型）需再扩 `CommandInfo` | 等真出现需求（如 Web 端按插件分组）再加 |
| L5 | 无补全 / 历史 / 高亮 | 交互体验缺口 | 外壳职责 |
| L6 | 每次执行与渲染都现算别名索引 | O(命令数)；命令极多时才有感知 | 人类节奏可忽略；换来热部署天然正确 |
| L7 | 命令执行不发事件 | 无法从事件通道审计「谁执行了什么命令」 | 架构图无此边；需要时由外壳或处理器自己发 |
| L8 | `execute` 吞掉插件异常转 `ERROR` | 插件缺陷不会让外壳崩溃，但也意味着「异常即失败」不再是硬约束（有 WARN 日志） | 与权限轮「调用点决定异常处置」一致 |
| L9 | **系统命令（`/help` `/model` `/agent` `/mode` `/new` `/exit`）本轮不落地** | 命令域只有机制、没有一条真命令；端到端示例只能靠单测里注册的假命令 | **已闭环**：十条系统命令由 `core/command/SystemCommands` 以 owner = `core` 注册（react 轮），并由 CLI 外壳 `CliRunMode` 通过 `isCommand` 分流真实调用（`cli方案.md`）；`/exit` 归外壳，`/compact` 仍待落地 |
| L10 | `CommandResult` 不带机器可读数据（Q13 推荐口径） | 结果里的标识只能靠文本；外壳需要状态时得另读域服务 | 若将来真出现「必须从结果里机器读标识」的场景，再考虑加 `data` 字段 |

## 10. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| Q1 收紧后改 7 个既有测试文件，漏改导致编译失败 | 改动全在编译期暴露（泛型不匹配直接编译不过），无运行期静默风险；按 P2 独立提交 |
| 给 `ExtensionRegistry` 加调用面会影响既有语义 | `descriptorBindings` 是纯新增；`descriptors` 改为复用它但对外语义（跳过 null、顺序、只读）逐条对齐，既有单测必须原样全绿 |
| 结构化入口与原文入口行为分叉（Web 与 CLI 结果不一致） | 两者共用同一个私有 `dispatch(...)`；单测 ⑯⑰ 专门钉住「同 handler、同参数、同别名行为」 |
| 外壳把命令当「返回数据的 API」用，去抠帮助文本 | 提供 `commands()` 结构化清单（Q15）；`CommandResult` 明确只承载文本，状态一律读会话域 |
| 帮助 / 清单渲染撞上非法描述符（类型不符）导致外壳崩 | `renderHelp()` / `commands()` 捕获 `ExtensionException` → WARN + 空清单 / 单行错误文案 |
| 别名歧义在插件热部署时才出现 | 每次现算、不缓存，因此热部署后立刻能感知；只在真的用到该别名时报错，不打扰其它命令 |
| `CommandManager` 认知复杂度（语法 / 别名 / 分发 / 清单 / 帮助） | 语法下沉到 `CommandLineParser`；名字与别名解析抽成 `resolve(...)`；两个公共入口都只是薄壳，核心只有 `dispatch(...)` + `renderHelp(...)` |
| 「系统命令」迟迟不落地，命令域没有端到端示例 | 单测里用插件身份注册真命令覆盖端到端；续做时第一批建议 `/help` + `/new`（`/help` 直接调 `renderHelp()`，`/new` 建会话并 `switchTo`） |

## 11. 裁决记录与新增待确认项

### 11.1 已裁决（用户口径）

> 说明：Q13～Q15 的备选方案保留在 §11.2，用来记录「为什么不那么做」。

| # | 问题 | 裁决 | 落点 |
| --- | --- | --- | --- |
| Q1 | `CommandRequest` 是否收紧 | **收紧**：类型化字段 + `CommandResult` | §3.1 / §7 P2（含 7 个既有测试的机械适配） |
| Q3 | 结果三态 | **够**：`OK / ERROR / UNKNOWN` | §3.1 / §4.3 |
| Q4 | `descriptorBindings(...)` | **加** | §3.2 |
| Q5 | `CommandDescriptor` 不带 name | **行**（名字＝路由键） | §3.1 |
| Q8 | 系统命令 | **后面做，记 `TODO`** | §4.5 / §9 L9 / §5.1 |
| Q10 | 会话标识来源 | `execute(..., sessionId)` 由外壳显式传入 | §3.3 / §2.1 |
| — | 服务面 | 命令**不只服务 CLI**：TUI / Web 也会用它建会话、切模型等 | 本版新增：§2.1 外壳中立、结构化入口与清单 |
| Q13 | 结果是否带机器可读数据 | **不带**：副作用写回会话域，外壳读域服务 | §3.1 / §4.5 / §9 L10 |
| Q14 | 结构化入口 | **提供** `execute(名字, 参数, sessionId)` | §3.3 / §4.3 |
| Q15 | 结构化清单 | **提供** `commands()` + `CommandInfo` | §3.3 / §3.4 / §4.4 |

### 11.2 因「命令不只服务 CLI」新增的问题（均已裁决，备选留档）

| # | 问题 | 推荐 | 备选 | 影响面 |
| --- | --- | --- | --- | --- |
| Q13 | 命令结果是否要携带**机器可读数据**（例如 `/new` 回传新 sessionId 给 Web）？ | **不带**：副作用经处理器写回 `SessionManager`，外壳执行后读会话域拿状态；`CommandResult` 只回文本 | 给 `CommandResult` 加 `Map<String,Object> data`（通用，但重新引入无类型载荷） | 影响 `CommandResult` 形状与「外壳怎么拿状态」的约定 |
| Q14 | 是否提供**结构化入口** `execute(名字, CommandArguments, sessionId)`？ | **提供**：Web / TUI 本来就有结构化参数，不必拼成一行再让内核拆回来（与原文入口共用 `dispatch`） | 只保留原文入口，调用方自己拼字符串（要处理引号与转义，容易出错） | `CommandManager` 公共面 +1 个重载 |
| Q15 | 是否提供**结构化清单** `commands()` 供 TUI / Web 自渲染？ | **提供**：`renderHelp()` 基于它实现；非文本外壳不该去解析对齐过的文本 | 只给 `renderHelp()` 文本，Web 端自己解析 | `CommandManager` 公共面 +1 个方法、+`CommandInfo` 一个类 |

### 11.3 其余口径

Q2 / Q6 / Q7 / Q9 / Q11 / Q12 均按 §0 推荐口径执行，如无异议不再单独确认。

## 12. 落地记录（P1～P5）

| 阶段 | 落地内容 | 验证 |
| --- | --- | --- |
| P1 | `CommandDescriptor` / `CommandArguments` / `CommandResult` + 三个单测 | `jellyfish-api` 全绿 |
| P2 | `CommandRequest` 收紧（`ExtensionRequest<CommandResult>` + `CommandArguments`，删 `payloadType/payload`）；重写 `CommandRequestTest`；机械适配 `ExtensionRegistryTest` / `HandlerBindingTest` / `PluginContextTest` / `PluginContextFactoryTest` / `PluginContextImplTest` / `EventChannelTest` | 全量 `mvn -o test` 全绿 |
| P3 | `DescriptorBinding` + `ExtensionRegistry.descriptorBindings(...)`；`descriptors(...)` 改为复用它（跳过 null 的语义不变） | `ExtensionRegistryTest` 既有用例原样全绿 + 5 个新用例 |
| P4 | `CommandManager` / `CommandInfo` / `CommandLineParser` / `ParsedCommand`（后两个包私有） | `CommandManagerTest` 27 · `CommandLineParserTest` 19 · `CommandInfoTest` 4 · `DescriptorBindingTest` 4 |
| P5 | 空 `CommandModule` + `JellyfishComponent.commandManager()`；系统命令 TODO 落点；`AGENTS.md` / `跨语言插件方案.md` 同步 | 全量 `mvn -o test` 全绿；Dagger 已生成 `commandManager()` |

### 12.1 落地时对方案的两处收敛（已同步进本文档）

1. **`CommandDescriptor` 不校验「别名与命令名同名」**：名片里没有命令名，无从校验；该情况由「命令名优先」的解析顺序兜底（§4.2 已写明，测试 `execute_should_prefer_command_name_over_alias` 钉住）。§3.1 与 §6 的措辞已相应收紧。
2. **前缀常量只有一份**：`CommandManager.COMMAND_PREFIX` 是唯一对外常量（原文入口与帮助渲染都用它）；api 侧 `CommandDescriptor` 为校验别名只用一个私有字符常量，不引用 infra 常量（api 不依赖 infra）。二者都是「`/` 是命令前缀」这一不变语法事实。

### 12.2 落地与方案的差异说明

- **`commands()` 不吞异常**：描述符类型不符等注册缺陷会原样上抛 `ExtensionException`（fail-fast，静默返回空清单会被误读为「没有命令」）；由 `renderHelp()` 与分发路径分别捕获并翻译成文案 / `ERROR` 结果（§4.3 / §4.4 已有此语义）。
