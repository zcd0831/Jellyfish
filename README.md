# Jellyfish

A lightweight AI agent tool developed in Java 1.8, which supports capability extension through plugins.

## 运行

```bash
mvn -o clean package
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli -p "今天天气怎么样？"
```

三种启动模式共享同一个入口与同一份装配，只靠启动参数区分：

| 模式 | 形态 | 示例 | 状态 |
| --- | --- | --- | --- |
| `-cli` | 单次调用、不交互：进一个输入，出一次结果后退出 | `jellyfish -cli -p "今天天气怎么样？"` | 已落地 |
| `-tui` | 交互式终端界面（TamboUI）：消息区 + 输入框 + 状态栏 | `jellyfish -tui` | 已落地 |
| `-server` | HTTP 服务（Undertow），对外暴露能力接口 | `jellyfish -server 9096` | 已落地 |

### 参数

| 参数 | 说明 |
| --- | --- |
| `-cli` / `-tui` / `-server` | 模式旗标，三选一且必填；`-server` 可带位置端口 |
| `-p, --print <输入>` | 单次模式的输入；缺省时从 stdin 读到 EOF（管道可用） |
| `--session <会话>` | 切换到已有会话（需安装 `jellyfish-session-file` 等持久化插件；会话不存在时按用法错误退出 `2`） |
| `--agent <agentId>` | 新建会话时绑定 agent |
| `--model <provider/模型>` | 新建会话时指定模型，必须含 `/` |
| `--mode <plan\|normal>` | 新建会话的权限模式（`plan` 仅允许只读工具） |
| `--port <端口>` | 服务器端口（等价于 `-server` 的位置参数，缺省 `9096`） |
| `--host <地址>` | 服务器绑定地址（缺省 `127.0.0.1`） |
| `--show-thinking` | 展示模型的思考过程：`-cli` 打到 stderr，`-tui` 置为启动时展开 |
| `--verbose` | 日志级别降到 DEBUG（也可用 `-Djellyfish.log.level=DEBUG`） |
| `-h, --help` / `-V, --version` | 帮助 / 版本号 |

### 输出与退出码

**回答与命令结果走 stdout，诊断、工具进度与日志走 stderr**，因此重定向是安全的：

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli -p "总结这个项目" > answer.txt 2> diag.txt
echo "/help" | java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli
```

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功（含「未知命令」这类用户输入错误） |
| `2` | 用法错误：参数缺失 / 未知 / 冲突，`--session` `--agent` `--model` 指向不存在的东西，或没有输入 |
| `3` | 启动失败：配置、插件或装配出错 |
| `4` | 运行失败：回合抛异常，或命令执行失败 |
| `3`（TUI） | TUI 需要可交互终端而当前没有（stdin 或 stdout 被重定向也算） |
| `6` | 回合未收敛：达到最大轮次仍未给出最终回复 |

单次模式里输入以 `/` 开头就走命令域（`/help` `/model` `/agent` `/new` …），否则走一次 LLM 对话；
`/help` `/session` `/status` `/model` `/compact preview` 这些命令不需要模型配置，可以离线验证安装是否正常
（`/todo` 由待办插件提供；`/compact preview` 是纯只读的，无参 `/compact` 会真的发起一次摘要调用）。

改了配置文件（`models.json` / `agents.json` / `jellyfish.json`）后不用重启进程，敲一条 **`/reload`** 即可生效：
它会重读配置、重建模型 / agent 索引，并**只重启配置段变了的插件**（同步等结果，毫秒级）。
目录与启用 / 禁用名单的变化同样在这一次里收敛；新增 / 删除插件 jar 仍需重启进程（见「配置」一节）。

进程退出时会往日志里打一份**健康检查**（模型 / 插件 / 事件通道 / 压缩）与一份**运行期指标汇总**（命令、工具、权限、
会话、压缩、事件通道队列等），用于事后排查；指标只做程序化输出，没有 `/metrics` 命令。

### TUI 模式

> **在 IDEA 里调试**：IDEA 的运行控制台默认不是真终端，直接跑 `-tui` 会命中「需要可交互终端」的检查。
> 推荐做法是**在真实终端里启动、用 IDEA 远程调试挂接**：
>
> ```bash
> mvn -o package -DskipTests
> java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
>      -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
> ```
>
> 然后 `Run → Edit Configurations → + → Remote JVM Debug`（默认就是 5005）→ 点 Debug。
> 想在启动阶段（DI 装配、`bootstrap`）下断点就把 `suspend=n` 改成 `suspend=y`。
> IDEA 内置的 Terminal 标签页是真 PTY，也可以直接在那里运行。
> 若确认终端可用却被拦下，用 `-Djellyfish.tui.skipTerminalCheck=true` 跳过检查。

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
```

需要**可交互终端**（备用屏 + raw 模式）。在管道、CI 或没有终端的环境里启动会**立刻报错并退出 3**，
不会挂住——修复前它会退化到 dumb 终端后永久等待事件。**在 IDE 里调试请见下方「在 IDEA 里调试」。**
界面结构：

```
╭ jellyfish · 会话 ─────────────────┐
│  ❯ 用户消息                        │   ← 消息区（可滚动）
│  ⏺ jellyfish                       │
│    助手正文                         │
│      ⎿ read_file                   │   ← 工具轨迹
╰────────────────────────────────────╯
╭ 输入 ──────────────────────────────╮
│ …                                  │   ← 多行输入（1～6 行自适应）
╰────────────────────────────────────╯
 agent · provider/模型 · 权限模式 · token 用量（会话被压缩过时追加 `已压缩 N 条（丢弃 M 条）`）
```

| 按键 | 行为 |
| --- | --- |
| `Enter` | **换行**（可写多行，输入框 1～6 行自适应） |
| `Ctrl+S` | **发送** |
| `Ctrl+C` | 退出 |
| `Ctrl+T` | 展开 / 折叠思考过程（与 `/thinking` 等价） |
| `Esc` | 中断当前回合（输入框内容保留） |
| `PageUp` / `PageDown` | 消息区翻页 |
| `End` | 跳到底部并恢复跟随 |
| `/exit` | 退出（由外壳处理，不在 `/help` 列表里） |
| `/ui` | 查看与切换插件的界面贡献（同样由外壳处理，不在 `/help` 列表里） |
| `/thinking` | 展开 / 折叠思考过程（同样由外壳处理，不在 `/help` 列表里） |

工具需要审批时（agent 策略把工具写进了 `askTools`）会弹出审批选择框，它**优先于**补全面板与二级选择页：

```
╭ ⏸ 需要审批 ────────────────────────╮
│ 工具 bash                          │
│ 参数 {"command": "rm -rf build"}   │
│ 理由 agent 策略要求人工审批该工具   │
│ 会话 a1b2c3d4… · 模式 normal       │
│                                    │
│  ❯ 允许一次   本次调用放行…        │
│    拒绝       本次调用按拒绝处理…  │
│    ↑/↓ 选择 · Enter 确认 · Esc 拒绝并中断 │
╰────────────────────────────────────╯
```

参数里的密钥（`apiKey`、`token` 等）显示为 `***`，超长参数折行显示并在截断处写明省略了几行；
`Esc` 是「拒绝**并**中断回合」——只拒绝的话模型往往会换个方式接着试。

**为什么是 `Ctrl+S` 发送而不是 `Enter` 发送**：终端在 raw 模式下，`Shift+Enter`、`Alt+Enter`、
CSI-u 等所有「带修饰的 Enter」编码都无法被底层框架区分（一律解码成无修饰的 `Enter`），
`Enter` 与 `\n` 也完全同形。因此「`Enter` 发送 + 修饰键换行」在任何终端上都不可实现。
反转之后 `Enter` 稳定换行，发送交给一个可稳定识别的组合键。

**思考过程默认折叠**：模型返回的思考过程（reasoning）会随消息一起落进会话，但屏幕上默认只占一行
——`✻ 思考过程（N 字，Ctrl+T 展开）`，流式期间是 `✻ 思考中…（N 字）`。想读全文按 `Ctrl+T`
（或敲 `/thinking`），这是一个**全局**开关：要么所有思考都展开，要么都折叠（屏幕上没有「选中某条消息」
这种交互，逐块展开只会换来一套选择态与焦点管理）。`--show-thinking` 让 TUI 启动时就是展开态，
与 `-cli` 的语义一致。

**助手正文按 markdown 渲染**：标题（`▌ ` 前缀）、无序 / 有序列表、引用（`│ ` 前缀）、代码块
（带语言围栏、内部不折行、超宽截断标 `…`）、行内代码（黄色）、加粗 / 斜体 / 删除线、链接
（显示成 `文本 (url)`）、分隔线都会被渲染出来；表格**降级为代码块**（终端里按列对齐中英混排
要赌终端的字宽表，算错了比不对齐更误导）。**用户消息保持纯文本**（用户打的多是自然语言，渲染收益低，
还可能吞掉原文空白），工具轨迹不变。图片与 HTML 原样显示源码，不请求也不解释。

**滚轮可用，代价是终端选择需按住修饰键**：滚轮事件要求应用捕获鼠标（`TuiConfig.mouseCapture(true)`，**默认开启**），
而捕获后终端的鼠标选择会被应用截走——复制屏幕文本需按住修饰键（macOS 为 Option）。若不能接受这个代价，用
`-Djellyfish.tui.mouseCapture=false` 退回：代价是滚轮在多数终端下**根本到不了应用**，消息区滚动改用 `PageUp` / `PageDown` / `End`。

**与 `-cli` 的口径差异**：TUI 独占备用屏，因此**没有 stdout 契约**（`> answer.txt` 不适用），
退出后也不回显会话内容。日志在 TUI 模式下改写到文件 `<用户主目录>/jellyfish/jellyfish-tui.log`（可用 `-Djellyfish.log.file=...` 改路径），
绝不写 stderr——否则会撕坏画面。

**已知限制**：界面滚动到内容末尾时自动跟随；用户上翻后不再打扰，状态栏出现 `↓ N 行`（生成中则显示 `↓ 正在生成…`）。
消息区只投影最近 500 条消息，更早的以 `⎿ N 条更早的消息已折叠` 占位。
编辑器的「光标到行尾」不可用（`End` 归消息区）；`\r\n` 输入会变成两个换行。

**插件 UI**：插件可以往界面上放两类东西——状态栏尾部的一小段文本（例如待办插件的 `待办 2/5`），
以及一块常驻面板（例如完整的待办清单）。插件**不能自己布局界面**——它只能贡献渲染无关的数据（文本 + 语义强调档位），
位置、宽度、行数全部由外壳决定：状态栏片段按显示宽度拼接、并从最后一个起**整块丢弃**超宽的部分；
面板的折行与行数上限由版式账本给出，插件无权把消息区挤没。

面板占区域，而一块区域同一时刻只显示一个，因此两个插件抢同一位置时默认只显示 `order` 最小的那个，
其余进入候选。用 `/ui` 查看与切换（这命令归外壳，不进内核命令注册表）：

```
/ui                        列出所有贡献：区域 | 插件 | 标题 | 是否可见 | 还有哪些候选
/ui right                  在该区域轮换到下一个候选
/ui right jellyfish-todo   指定由某个插件占用该区域
/ui right off              关掉该区域（/ui right on 恢复）
```

界面窄于 80 列时左右侧栏自动隐藏；窄到放不下时面板会让位给消息区，而不是反过来。

外壳**不**每帧询问插件（那样空闲时也在反复调用处理器），只在失效时收集一次：会话切换、回合开始或结束、
命令执行后、插件加载卸载、以及插件自己发布 UI 失效事件。整体不要插件 UI 时用 `-Djellyfish.tui.pluginPanels=false`。

### Server 模式

HTTP 服务（Undertow），对外暴露 REST + SSE 接口，供第三方 Web 前端使用。

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -server 9096
```

**默认只绑 `127.0.0.1`**，对外开放必须显式 `--host 0.0.0.0`。

> ⚠️ **当前无鉴权**：任何能访问该端口的人都能建会话、跑命令（含文件工具）、读全部会话正文。
> 鉴权（API key / token）尚未落地，**不要把端口暴露到公网或他人可达网段**。命令域与对话域同权，
> `POST /sessions/{id}/commands` 能执行 `/reload` 等系统命令。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/sessions` | 建会话（body 可选 `agentId`/`provider`/`model`/`permissionMode`，缺省取 `--agent`/`--model`/`--mode`） |
| `GET` | `/sessions` | 会话摘要列表（不含消息正文），按最后变更时间倒序 |
| `GET` | `/sessions/{id}` | 完整会话快照（含消息与用量） |
| `DELETE` | `/sessions/{id}` | 删除会话（含插件持久化） |
| `POST` | `/sessions/{id}/chat` | 对话，**恒为 SSE**（`text/event-stream`） |
| `POST` | `/sessions/{id}/cancel` | 取消该会话在途回合 |
| `POST` | `/sessions/{id}/commands` | 执行命令（`input` 原文 或 `name`+`args` 结构化，二选一） |
| `GET` | `/commands` | 结构化命令清单（前端菜单用） |
| `GET` | `/commands/{name}/options?sessionId=` | 命令候选值 |
| `GET` | `/approvals` | 当前待审批项（无则 204） |
| `POST` | `/approvals/{requestId}` | 裁决审批（`{"approved":true|false}`） |
| `GET` | `/health` | 健康报告（UP / WARN / DOWN） |

**SSE 事件**：`turn_start` / `text` / `thinking` / `tool_start` / `tool_done` / `approval_required` /
`approval_resolved` / `done` / `cancelled` / `error`；空闲超时写 `: keepalive` 注释帧。
`done` / `cancelled` / `error` 是终态，写出后流结束。

几条与内核语义相关的约定：

- **会话一律按路径里的 id 寻址**；`-server` 不支持 `--session`（写了判用法错误退 `2`），
  `--agent` / `--model` / `--mode` 降级为「新建会话的默认值」。启动期不预建任何会话。
- **同会话同时只允许一个回合**：第二个请求返回 `409`（避免两个回合把消息历史交错写坏）；
  要打断就用 `POST /sessions/{id}/cancel`，或直接断开 SSE 连接（服务端据此取消回合）。
- **人工审批走 HTTP**：`askTools` 里的工具会在流里推 `approval_required`，客户端拿 `requestId` 调
  `POST /approvals/{requestId}`。`ApprovalChannel` 是**全局单槽位**，因此任一时刻最多只有一条待审批项，
  多会话并发时后面的会排队。
- **错误体**统一为 `{"error":"CODE","message":"…"}`；命令执行的三态在 `kind` 字段里
  （`UNKNOWN` 同时回 404）。

## 压缩上下文（`/compact`）

长会话的每一轮都要把整段历史重新发给模型，token 花得越来越多。压缩多花**一次**调用把更早的
对话压成一份摘要，之后每次请求只带摘要 + 最近若干条原文：

> **前提**：压缩由插件提供策略（摘要指令 + 参数）。没有启用 `jellyfish-compact`（或同类插件）时，
> 压缩整体不可用——`/compact` 会直接告诉你，自动压缩也不生效。见「插件」一节里的
> [会话压缩（jellyfish-compact）](#会话压缩jellyfish-compact)。

```
/compact                 按默认档位压一次（保留条数取 react.compactKeepRecentMessages，缺省 20）
/compact preview         只回报「将压缩 X 条、保留 Y 条、丢弃 Z 条、摘要输入约 T token」，不发起压缩
```

**上下文用到 80% 时会自动压一次**（`react.autoCompactPercent`，写 `0` 关闭），不用你盯着 token 数；
另外只要某一次请求已经触发了机械裁剪（历史正在被静默丢弃），也会立刻压一次。自动压缩跑在当前那一轮
模型调用的旁边，不拖慢对话，结果在下一轮生效，完成提示会写明「已自动压缩」。

四条口径值得记牢：

- **历史一条不删**。压缩是非破坏式的：屏幕上的会话、`/resume`、落盘的那份文件都还是完整历史，
  变的只是「发给模型的那条链路从哪里开始」。因此不会有「压完就再也看不到原文」这种事。
- **可以反复压**。每次只压「上次边界之后新积累的那一段」，并把上一份摘要一起喂进摘要请求，
  所以会话里始终只有一块摘要、边界只会向后移（滚动摘要）；已压过的那段不会再花一次钱。
- **一次压完，装不下就丢最旧的**。摘要请求本身也受同一个上下文窗口约束，而「要压的东西大到发不出去」
  正是需要压缩的原因。因此待压范围超过预算时，从**最旧侧丢弃**到装得下为止，只把最新的一段交给模型——
  一次命令一次调用，耗时与花费都可预期。**被丢弃的那一段既不在摘要里，也不会再发给模型**：它是真正
  消失的数据，所以完成提示会写明「另有 N 条……未纳入摘要且不再发送」，system prompt 里那块摘要也会
  告诉模型「其中 N 条未收录」。`/compact preview` 会先把这件事算给你看。
- **摘要是给人读的提醒，也给模型看的背景**。它**进 system prompt 而不是当成一条消息**回灌
  （顺序：agent 提示词 → 插件贡献 → 历史摘要），否则会被后续每轮重复 append 回会话，
  越聊越像一份不断膨胀的假历史。

压缩调用的 token 计入会话用量（`/usage` 看得到）。`/status` 会显示「已压缩 N 条更早消息（丢弃 M 条）」，
TUI 状态栏也会追加 `已压缩 N 条（丢弃 M 条）`；压缩期间状态栏显示 `压缩中…`，完成后在消息流里贴一条
结果提示（失败也一样贴，并带上原因）。

摘要指令本身是一份资源文件，随**插件**发布（`summary-prompt.md`，在插件 jar 根目录），
不是硬编码在内核的代码里。插件只回答「这一次该怎么压」——摘要指令与两个数量参数；读消息、选范围、
发模型调用、推进边界、记用量全部由内核负责，插件拿不到任何一条消息正文。

## 配置

配置分四份用户可改的文件，每份对应一个配置类。**文件位置与插件扫描目录只在 `config.json` 里声明**，其余文件都走「全局级 + 项目级」双源，项目级优先。另有一份随构件发布的 `default-agent.json`（内置系统默认 agent），它不走双源、用户改不了。

| 文件 | 配置类 | 内容 |
| --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 + 插件扫描目录 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` |
| `agents.json` | `AgentSettings` | `agents`（用户自定义 agent） |
| `jellyfish.json` | `JellyfishSettings` | `plugins`（名单与插件配置段）等运行期设置 |
| `default-agent.json` | `AgentDefinition` | 内置系统默认 agent（classpath 根，不走双源；跟着读它的加载器放在 `jellyfish-infra/src/main/resources/`） |

`config.json` 放在 classpath 根（本仓库为 `jellyfish-cli/src/main/resources/config.json`）：

```json
{
  "processName": "Jellyfish",
  "model":     { "globalPath": "~/jellyfish/models.json",    "projectPath": "./jellyfish/models.json" },
  "agent":     { "globalPath": "~/jellyfish/agents.json",    "projectPath": "./jellyfish/agents.json" },
  "jellyfish": { "globalPath": "~/jellyfish/jellyfish.json", "projectPath": "./jellyfish/jellyfish.json" },
  "plugins":   { "roots": ["plugins", "~/jellyfish/plugins"] }
}
```

仓库里的 `config.json` 就是这份：**全局级约定目录 `~/jellyfish/`、项目级约定目录 `<工作目录>/jellyfish/`**，四类配置的文件名固定。要换位置只改 `config.json`。

> **改完不用重启**：`models.json` / `agents.json` / `jellyfish.json` 的内容改动敲 `/reload` 即生效（重建索引 + 按差异重启受影响的插件）。
> **`config.json` 不参与热更新**：它是「去哪个文件读配置、去哪个目录找插件」的部署事实，改它要重启进程；同理，新增 / 删除插件 jar 也仍需重启（扫描目录与插件集合只在启动期确定）。

`plugins.roots` 是插件 jar 的扫描根目录（PF4J 在目录下一层找 `plugin.properties`）：顺序即扫描顺序，相对路径相对**进程工作目录**解析，条目行首的 `~` 展开为用户主目录；留空则回退默认值 `plugins`。为什么它在这里而不是 `jellyfish.json`：它与「去哪个文件读配置」同属部署事实；`jellyfish.json` 的 `plugins` 段只保留加载后的运行期设置。

`models.json`：

```json
{
  "defaultProvider": "openai",
  "defaultModel": "gpt-4o",
  "providers": {
    "openai": {
      "type": "openai",
      "apiKey": "${OPENAI_API_KEY}",
      "models": [{ "id": "gpt-4o", "name": "gpt-4o", "contextLength": 128000, "maxOutputTokens": 4096 }]
    }
  }
}
```

`agents.json`（系统提示词**不写在这里**，而是同目录下的 `{agentId}.md`）：

```json
{
  "agents": {
    "coder": {
      "description": "通用编码助手",
      "permissions": {
        "deniedTools": ["write_file", "edit_file"],
        "askTools": ["bash"]
      }
    }
  }
}
```

三段工具名的语义是：**字段缺失 = 不限制**，显式写 `[]` = 该方向上一个都不放行（`allowedTools: []` 就是全拦，`enabled` / `deniedTools` 同理）。因此想表达「只显式拒绝两个工具、其余不限制」就**不要**写 `"allowedTools": []`，直接省略该字段。

优先级是「`deniedTools` > `askTools` > `allowedTools`」。`askTools` 里的工具每次调用都要人工审批：

- `-tui` 会弹出审批选择框（`↑`/`↓` 选，`Enter` 确认，`Esc` 拒绝并中断回合），批准才执行；
- `-server` 把待审批项推进 SSE 流（`approval_required`），由 `POST /approvals/{requestId}` 裁决（见「Server 模式」）；
- `-cli` 没有审批界面（也没有审批者），因此**一律按拒绝处理**——绝不静默放行；
- 审批框等不到答复（缺省 120 秒，见 `permission.approvalTimeoutSeconds`）同样按拒绝处理。

上例的 `coder` 还需要一份 `coder.md`（与 `agents.json` 同目录），内容就是它的系统提示词，可以是多段长文。

`default-agent.json`（内置，随 `jellyfish-infra` 发布在 classpath 根——资源跟着读它的加载器走，加载器在 infra）：

```json
{
  "agentId": "jellyfish",
  "description": "系统默认 agent",
  "permissions": {}
}
```

它配合同目录的 `jellyfish.md` 使用。**每次启动、每个新建会话都绑这个内置 agent**；想用自定义 agent 必须手动 `/agent <agentId>` 切换。用户 `agents.json` 里写同名 `jellyfish` 会被忽略并告警（内置定义不可被覆盖）。

`jellyfish.json`：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-tools": { "readOnlyTools": ["read_file", "list_dir"] }
    }
  },
  "react": {
    "maxRounds": 16,
    "contextReserveTokens": 1024,
    "maxToolOutputChars": 20000,
    "compactKeepRecentMessages": 20,
    "compactMaxSummaryChars": 4000,
    "autoCompactPercent": 80,
    "toolOutput": {
      "dir": "~/jellyfish/tool-outputs",
      "keepFiles": 200,
      "maxBytes": 52428800,
      "keepRecentMessages": 20
    }
  },
  "permission": {
    "approvalTimeoutSeconds": 120
  }
}
```

`react` 段控制 ReAct 循环：`maxRounds` 是单回合最大轮数；`contextReserveTokens` 是上下文预算里为系统提示词 / 插件注入的上下文预留的 token；`maxToolOutputChars` 是单个工具输出回灌模型前的截断长度，也是**硬上限**（工具失控时由它保命）；`compactKeepRecentMessages` 是压缩默认保留的最近消息条数（写 `0` 即「不保留原文」）；`compactMaxSummaryChars` 是摘要长度上限（提示模型别写太长，真超了按码点本地截断并留标记）；`autoCompactPercent` 是上下文用到多少百分比就自动压缩（写 `0` 关闭自动压缩，只留手动 `/compact`）。缺省值即为上表；非法值（非正数）回退到缺省值。

`react.toolOutput` 段只管工具结果太长时怎么办：`dir` 是完整内容的落盘根目录（缺省 `~/jellyfish/tool-outputs`，运行产物写在这里而不是项目目录）；`keepFiles` / `maxBytes` 是每个会话在该目录下的文件数与字节数上限（缺省 200 个 / 50 MiB，写 `0` 关闭清理），超了从最旧的开始删；`keepRecentMessages` 是组装请求时最近多少条消息里的工具结果保留完整内容（缺省 20，写 `0` 关闭该裁剪）。

**工具结果超过 `maxToolOutputChars` 时不会被从中间切断**：完整内容先落盘，回灌给模型的是一段合法 JSON 信封，内含 `_truncated`、原始大小、`_path` 与 `preview`（结构化结果是截断后的子树，纯文本是截断后的字符串）。模型据此知道发生了什么、去哪回查。工具自身也默认限流（`read_file` 的 `max_bytes`、`list_dir` 的 `limit`/`offset`、`grep_files` 的 `max_line_chars`/`max_bytes`），让绝大多数调用根本用不到内核这层兜底。

`permission` 段当前只有 `approvalTimeoutSeconds`：`askTools` 里的工具在 TUI 上弹审批框后最多等这么久，超时按拒绝处理。它有缺省值（120 秒）而不允许「永不超时」——审批请求发生在 `react` 线程上并被同步等待，一个永远不来的答复就是一条永远不返回的线程。

约定：

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），JSON 的 key 不替换。`{agentId}.md` 提示词是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录（`~other/...` 这种指定其他用户的形式不展开）；两条路径都支持。项目级路径相对**进程工作目录**解析，不是相对 jar 位置。文件不存在视为「该源未配置」，静默跳过（这是 `globalPath` 与 `projectPath` 可以同时配上、缺哪份就少哪份的原因）。`config.json` 的 `plugins.roots` 同样支持 `~` 与相对路径，语义一致。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级，agent 的提示词 md 也随来源一起覆盖；`defaultProvider` / `defaultModel` 取项目级非空值，否则回退全局级；`react` 段项目级整对象覆盖全局级；启用 / 禁用名单项目级**已声明则整体替换**（写 `[]` 即清空该名单，不做并集）。`plugins.roots` 只在 `config.json` 一处，不参与双源合并。
- **agent 提示词**：每个 agent（包括内置的系统 agent）的系统提示词来自与配置文件同目录的 `{agentId}.md`；`agentId` 同时是文件名，因此不能含路径分隔符或 `..`（含这类字符的条目会被整条丢弃并告警）。文件不存在不阻断启动，只有用户 `/agent` 切过去时才提示「该 agent 没有系统提示词」。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。

### 想真跑一轮对话

`config.json` 默认从 `~/jellyfish/`（全局级）与 `./jellyfish/`（项目级）读取三份配置。开箱能跑命令（如 `/help`），但没有模型配置时一发对话就会提示没有可用模型。要真跑：

1. 在项目根目录建 `jellyfish/models.json`（格式见上方示例），apiKey 用环境变量注入：`"apiKey": "${OPENAI_API_KEY}"`；
2. 按需建 `jellyfish/agents.json` 与 `jellyfish/jellyfish.json`；
3. 想让配置对**这台机器上的所有项目**生效，把同样的文件放到 `~/jellyfish/` 即可（项目级同名条目会整对象覆盖全局级）。

两个目录里的文件名与上方四类配置一一对应，不要改成别的名字。

## 插件

官方插件在 `jellyfish-plugins/` 下，**一个插件一个子模块**（PF4J 是「一个 jar 一个 `plugin.properties`」）：

| 模块 | plugin.id | 提供什么 |
| --- | --- | --- |
| `jellyfish-plugin-tools` | `jellyfish-tools` | 五个文件工具：`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_files` |
| `jellyfish-plugin-session-file` | `jellyfish-session-file` | 会话持久化：一个会话一个 JSON 文件，并用 git 管理历史 |
| `jellyfish-plugin-todo` | `jellyfish-todo` | 会话待办：模型可写的 `todo_write` 工具 + 只读 `/todo` + 注入 system prompt + 状态栏进度 + 侧栏清单面板 |
| `jellyfish-plugin-project` | `jellyfish-project` | 项目约定：探测工作目录下的 `AGENTS.md`，**小文件内联原文、大文件只给路径**（阈值可配） |
| `jellyfish-plugin-compact` | `jellyfish-compact` | 会话压缩策略：提供摘要指令与保留条数/摘要上限（**不装它就没有压缩**，见下文） |
| `jellyfish-plugin-python` | `jellyfish-plugin-python` | Python 脚本插件运行时：把 `scripts/python/<id>/` 下的脚本目录变成标准插件（控制面网关 + 每脚本一 worker 进程） |
| `jellyfish-plugin-node` | `jellyfish-plugin-node` | Node 脚本插件运行时：与 Python 同构（同一套协议与进程模型），零第三方依赖 |

`jellyfish-tools` 的五个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit`、`max_bytes` | 按行分片读取，命中 `limit` 或 `max_bytes` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path`、`offset`、`limit` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类；大目录分页 |
| `grep_files` | `pattern`、`path`、`max_results`、`max_line_chars`、`max_bytes` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

其中 `read_file`、`list_dir`、`grep_files` 在描述符里声明为**只读**，PLAN 模式下开箱可用；`write_file` 与 `edit_file` 会改动工作目录，PLAN 模式下会被拒绝。

打包与安装（扫描目录由 `config.json` 的 `plugins.roots` 决定，默认是**工作目录**下的 `plugins/`，该目录不入版本库）：

```bash
mvn -q package -DskipTests
mkdir -p plugins
cp jellyfish-plugins/jellyfish-plugin-tools/target/jellyfish-plugin-tools-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-session-file/target/jellyfish-plugin-session-file-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-todo/target/jellyfish-plugin-todo-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-project/target/jellyfish-plugin-project-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-compact/target/jellyfish-plugin-compact-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-python/target/jellyfish-plugin-python-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-node/target/jellyfish-plugin-node-*.jar plugins/
```

插件配置写在 `jellyfish.json` 的 `plugins.configurations.<pluginId>` 段：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-tools": {
        "readOnlyTools": ["read_file", "list_dir", "grep_files"]
      },
      "jellyfish-session-file": {
        "sessionDir": "~/jellyfish/sessions",
        "gitEnabled": true
      },
      "jellyfish-todo": {
        "todoDir": "~/jellyfish/todos",
        "readOnlyTools": ["todo_write"]
      },
      "jellyfish-compact": {
        "keepRecentMessages": 20,
        "maxSummaryChars": 4000
      },
      "jellyfish-project": {
        "maxInlineBytes": 32768
      }
    }
  }
}
```

- `readOnlyTools` 是**跨插件的约定键**（`jellyfish.json` 里位于插件配置段下），作用是**追加** PLAN 模式的只读白名单。
  - 工具的只读性**默认由工具提供方在 `ToolDescriptor` 里声明**，不需要用户再写一遍：`read_file` / `list_dir` / `grep_files`（`jellyfish-tools`）与 `todo_write`（`jellyfish-todo`）开箱即在 PLAN 白名单里；`write_file` / `edit_file` 不是。
  - 配置里的声明只能**追加**，用于把提供方没标只读的工具自行纳入，不能撤销提供方的声明。
  - 两个来源取**并集**，且不依赖任何缓存：插件热部署（装上 / 卸下 / 重载）后白名单立刻跟着变。
  - PLAN 模式下不在白名单里的工具一律拒绝。
- `sessionDir`（默认 `~/jellyfish/sessions`）：会话文件目录。会话是跨项目的运行态数据，因此默认放全局级目录。
- `gitEnabled`（默认 `true`）：首次落盘时在 `sessionDir` 里 `git init`，此后**每次内容变化的落盘留一次提交**（内容没变则不写文件、也不提交）。机器上没有 git 时只告警，文件照常落盘。
- `todoDir`（默认 `~/jellyfish/todos`）：待办文件目录，一个会话一个 JSON 文件，空表会删掉文件。
- `keepRecentMessages` / `maxSummaryChars`（`jellyfish-compact`，**都可省略**）：本插件对压缩参数的覆盖值；省略时用内核 `react` 段的缺省值。省略是「不表态」，不是「用 0」。
- `maxInlineBytes`（`jellyfish-project`，默认 `32768` 即 32 KiB）：约定文件**多大以内可以把原文放进 system prompt**。超过它只给路径指引；写 `0` 表示从不内联（彻底关掉内联的逃生门）。上限 1 MiB，超出或为负数会在启动期直接报错。约定文件名固定为 `AGENTS.md`，查找基准固定为进程工作目录——这两项不可配。

### 待办（jellyfish-todo）

待办是模型的计划草稿，由插件自己持有（内核不再有会话待办字段、也没有 `/todo` 系统命令）：

| 面 | 扩展点 | 说明 |
| --- | --- | --- |
| `todo_write` 工具 | `ToolCallRequest` | 模型写待办的唯一入口。**整表覆盖**：传完整的新列表，上次列过而这次没列出的项视为删除，空数组表示清空；状态只有 `pending` / `completed`，缺省按 `pending` 处理 |
| `/todo` 命令 | `CommandRequest` | 只读地列出当前会话待办（写入只走 `todo_write`，不给同一份状态第二套写入语义） |
| 上下文注入 | `PromptContributionRequest` | 每轮把待办块注入 system prompt，模型始终看得见自己的计划；没有待办时不注入 |
| 状态栏进度 | `StatusLineContributionRequest` | 状态栏尾部显示 `待办 2/5`，不敲命令也能看到还剩几件事；没有待办时不占位 |
| 侧栏清单 | `PanelContributionRequest` | 在侧栏常驻显示完整清单（已完成项整行变暗），建议放右栏；没有待办时不占区域 |

参数非法（`todos` 不是数组、项不是对象、`content` 为空、`status` 不在取值内）会**当场报错**，并作为工具结果回灌给模型让它自己改，而不是静默落盘一份坏数据。待办写完会广播一次 UI 失效事件，因此状态栏进度与侧栏清单不必等回合结束就更新。

面板是「独占型」贡献：它建议落在右栏，但外壳可以忽略这个建议（终端太窄时侧栏整体隐藏，也可能被用户用 `/ui` 改到别处）。

`todo_write` 只写插件自己的待办文件、不动工作目录里的项目文件，因此它**在描述符里就声明了只读**：PLAN 模式下开箱即可用，不需要任何配置。上面示例里的 `readOnlyTools: ["todo_write"]` 现在只是冗余写法，可以去掉（保留也不会出错）；配置那份只用于追加拿不写声明的工具。

会话恢复：启动时内核向所有注册了恢复处理器的插件要回会话，因此上次退出前的会话在下次启动时立即可见（`/session` 会列出来）。

### 项目约定（jellyfish-project）

`AGENTS.md` 是仓库里的项目约定（构建命令、编码规范、提交格式、模块边界）。这个插件把它交给模型，**按文件大小分两路**：

| 情形 | 注入内容 |
| --- | --- |
| 装得进 `maxInlineBytes`（默认 32 KiB） | `[项目约定]` + **文件原文**（外带一句定性：这是项目内文件的数据，不是系统指令） |
| 超过上限 | `[项目约定]` + **路径与文件大小**，让模型自己按需分段读 |

- **为什么小文件要内联**：常见项目的 `AGENTS.md` 只有几十行，直接给全文可以省掉一次读取工具的往返，也消除了「模型忘了去读」这个失败模式。
- **为什么大文件只给路径**：system prompt 每一轮都要随请求付一次 token，而大文件多半是参考性内容。本仓库这份 `AGENTS.md` 已压到约 32 KB，刚好落在内联阈值以内，因此走的是**内联**那条路——它每轮都会随请求计一次费。
- **不做「内联前 N KB + 给路径」**：头部往往恰是信息量最低的部分，而且截断点落在哪、模型知不知道「后面还有」都是新的失败模式。
- **内联是刻意的安全姿态取舍**：原文进的是 system prompt，即仓库内容拿到了最高优先级的话语权。护栏有三条：内联块开头的定性句、足够小的上限、以及把 `maxInlineBytes` 配成 `0` 彻底关掉。
- **「大文件」时给的实际大小**不是装饰：它让模型知道该分段读几次。
- **只查进程工作目录**，不向上查找父目录、也不查用户主目录：与文件工具的相对路径基准保持一致。
  - **因此请从仓库根目录启动**。`AGENTS.md` 的行业位置是仓库根，而本插件的基准是进程工作目录，两者只在你从仓库根启动时才重合；在子目录里启动会探测不到。
- **文件名不可配**，固定 `AGENTS.md`：这已是各家编码 agent 共同的约定，做成配置项只会多一个会填错的旋钮。
- **空文件不算命中**（指向它只会白费一次工具调用）；文件不存在时插件完全不注入，system prompt 里连空标题都不会出现。
- 因为走的是插件而不是内置提示词，**对所有 agent 生效**——用 `/agent` 换成自定义 agent 也照常。

**每个会话只读一次盘**：第一次组装请求时读取并缓存，同一会话后续每轮直接用缓存（会话关闭或删除时丢弃）。这与 Codex、Claude Code 的行为一致，代价是**会话中途修改 `AGENTS.md` 不生效**——开一个新会话即可。

注意缓存省的是磁盘 I/O 与「读文件」这个动作，**不省 token**：system prompt 每轮都要随请求发出去，内联的原文每轮都要重新计费。这也正是上限必须压住的原因。

### 会话压缩（jellyfish-compact）

压缩是**插件能力**，不是内核内置功能。内核手里只有机制——读消息、选范围、发模型调用、校验摘要、
推进边界、记用量、落盘；「这次该压成什么样」由本插件回答：一份摘要指令（本插件 jar 里的
`summary-prompt.md`）+ 两个可选参数。

**不启用它就没有压缩**：没有插件提供摘要指令，就没有可以发给模型的摘要请求。此时

- 自动压缩不生效（上下文满了只会走 `ContextWindow` 的机械裁剪，历史在**请求里**变少）；
- `/compact` 与 `/compact preview` 直接回答「压缩不可用：没有插件提供压缩策略」；
- `/status` 的压缩一行显示「不可用（没有插件提供压缩策略）」。

三种状态一眼可辨：**没装插件**（不可用）、**装了但还没压过**（未压缩）、**压过了**（已压缩 N 条）。
插件在 `~/jellyfish/plugins/` 里但没有列进 `jellyfish.json` 的 `plugins.enabled` 时，算「没装」；
启动日志里会有一条 `插件被禁用或版本不满足，未启动: pluginId=jellyfish-compact`。

插件里能调的只有两个数字，**摘要措辞改不了**（它在插件 jar 里）：`keepRecentMessages` 与
`maxSummaryChars`，省略即「不表态」，用 `react` 段里的缺省值。内核还会把它们钳制到合法区间——
插件写出荒谬的值不该让压缩失控。

摘要指令里用 `{maxSummaryChars}` 表示长度上限，内核替换成当次生效的值：

```markdown
… 5. 用与原文相同的语言书写，总长控制在 {maxSummaryChars} 字以内。
```

自己写一份也行：改 `jellyfish-plugin-compact/src/main/resources/summary-prompt.md` 后重新打包。占位符缺失不算错误（内核仍会按上限本地截断），但模型会少一条自我约束，因此内核记一条告警。

`/compact preview` 会把这次要付的代价先算给你看——压几条、保留几条、丢弃几条、摘要输入约多少 token。

### 脚本插件（Python / Node 桥接）

除了用 Java 写插件，还可以用 **Python 或 Node** 写。桥接插件把一个脚本目录变成内核眼里的标准
PF4J 插件，脚本与 Java 插件**同权**（11 个扩展点全开），能力边界由进程隔离 + 静态清单 + 熔断三层承担：

| 模块 | 脚本根目录（默认） | 解释器（配置键） |
| --- | --- | --- |
| `jellyfish-plugin-python` | `scripts/python/<脚本标识>/` | `python3`（`pythonPath`） |
| `jellyfish-plugin-node` | `scripts/node/<脚本标识>/` | `node`（`nodePath`） |

每个脚本目录只需一份静态 `manifest.json`（声明工具 / 命令 / 贡献 / 订阅的事件）与一个入口文件。
因此 **启动期零进程、零文件写入**：没装解释器也不影响内核启动，工具清单依然完整；首次真正调用某个脚本
时才拉起它的进程，空闲后自毁。脚本调用失败只影响它自己（每脚本一 worker 进程），连续失败按熔断冷却、
冷却后自动半开恢复。

脚本插件的 API、`manifest.json` 字段与两门语言的逐条对照见
[`examples/scripts/README.md`](examples/scripts/README.md)，仓库顶层 `examples/scripts/{python,node}/{hello,jira}`
是可直接拷贝运行的示例（`hello` 教学最小集、`jira` 真实形态，且被端到端用例直接加载）。

配置段写在 `jellyfish.json` 的 `plugins.configurations."jellyfish-plugin-python"`（或 `-node`）：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-python": {
        "scriptsRoot": "scripts/python",
        "pythonPath": "python3",
        "invokeTimeoutSeconds": 30,
        "workerIdleSeconds": 300,
        "gatewayIdleSeconds": 600
      },
      "jellyfish-plugin-node": {
        "scriptsRoot": "scripts/node",
        "nodePath": "node"
      }
    }
  }
}
```

- `scriptsRoot` 相对**进程工作目录**解析，其下每个含 `manifest.json` 的子目录是一个脚本插件；目录不存在等于「还没建脚本」（正常的冷启动状态）。
- `invokeTimeoutSeconds` 是单次调用超时，写 `0` 表示**没有截止时间**（不是「立刻超时」）；超时会隔离该脚本的 worker，并把这次失败计入熔断。
- `workerIdleSeconds` / `gatewayIdleSeconds` 分别为 worker 与网关的空闲自毁秒数（写 `0` 关闭），空闲回零是「懒启动」的配套。
- `manifestStrict`（默认 `true`）在脚本首次拉起时逐项比对清单与实现，任何漂移都报错并熔断该脚本；`dump_manifest.py` / `dump_manifest.js` 的 `--check`（比对）与 `--write`（直接落盘）用来让两者不漂移。
- `events.allow` **只收窄、不扩展**：可订阅事件清单硬编码在运行时里，这里写不存在的事件名不会扩大任何能力。
- 桥接插件 jar 本身与 Java 插件一样放在 `plugins/` 扫描目录（见上方打包命令），`jellyfish-script` 是库、不产出到 `plugins/`。
