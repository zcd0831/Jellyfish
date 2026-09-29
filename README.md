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

#### 输入指令 `!` 与文件引用 `@`

输入框支持两种**由插件提供**的特殊语法（没有装对应插件时，它们只是一段普通文本）：

| 输入 | 行为 | 依赖插件 |
| --- | --- | --- |
| `!命令` | 手动执行一条 shell 命令。**不进模型**，执行完把「命令回显 + 输出」作为一条用户消息落进会话，因此下一次提问时模型看得到结果 | `jellyfish-shell` |
| `@路径` | 引用当前工作目录下的文件：敲 `@` 弹补全面板（目录在前、可逐层往下钻），接受后路径写进输入框 | `jellyfish-tools` |

两条口径值得知道：

- **`!` 依旧过权限与审批**：它复用与模型调用完全相同的工具执行链路（`ToolExecutor`），只读命令静默执行、其余照旧弹审批框；`Esc` 可以中断正在跑的命令。结果作为 user 消息而不是 tool 消息，因为这里没有模型回合。
- **`@` 不内联文件内容**：引用只是把路径写进消息，读取由模型调用 `read_file` 完成（tools 插件会用一段 system prompt 约定告诉模型这件事）。因此大文件不会撑爆上下文，`read_file` 的 `max_bytes` 与权限判定照旧生效。

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
退出后也不回显会话内容。日志在 TUI 模式下改写到文件 `<用户主目录>/.jellyfish/jellyfish-tui.log`（可用 `-Djellyfish.log.file=...` 改路径），
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

**默认只绑 `127.0.0.1`**。对外开放必须显式 `--host 0.0.0.0` 并配上 API key：

```bash
# 推荐：密钥走环境变量（不会出现在 ps 输出里）
JELLYFISH_SERVER_API_KEY=$(openssl rand -hex 32) java -jar ...jar -server --host 0.0.0.0
# 也可以：命令行参数（argv 会出现在 ps 里，同机其他用户看得见）
java -jar ...jar -server --host 0.0.0.0 --api-key <密钥>
```

| 情形 | 行为 |
| --- | --- |
| 没配密钥（缺省） | **不鉴权**：任何能访问该端口的人都能建会话、跑命令、读全部会话正文。对只绑回环的本地场景够用 |
| 配了密钥 | 除 `GET /health` 外**所有接口**都要 `Authorization: Bearer <密钥>`，否则 `401` |

- **为什么缺省不鉴权**：本服务默认只绑 `127.0.0.1`，对回环还要先配密钥只会把「本地跑一次」变成一件要读文档才能做的事；而 **对外开放是显式动作**，那一步必须同时配密钥——**没配密钥时启动日志会给一条 WARN**（默认日志级别就是 WARN，因此这条一定看得见）——少了它，「以为配了」与「其实没配」在现象上都是「能访问」；配好了则是常规 INFO。
- **`GET /health` 不校验**：探活必须能在「还没有密钥」的场景下工作（容器编排的 liveness probe、起服务后的第一条 curl）。它只返回 UP/WARN/DOWN 与检查项名字，不含会话正文、路径与密钥。
- **不接受用 query 参数传密钥**：URL 会进访问日志、浏览器历史与 Referer。而本服务的对话入口是 `POST`，浏览器的 `EventSource` 本来就用不了（它只能发 GET），客户端无论如何都要用 `fetch` 流式读取，而它能带请求头。
- **密钥比较是常时比较**（`MessageDigest.isEqual`）：避免用短路语义把密钥逐字节泄露给能反复试探的调用方。密钥短于 16 位会在启动日志里告警，但不拒绝启动。
- **命令域与对话域同权**：`POST /sessions/{id}/commands` 能执行 `/reload` 等系统命令，因此密钥泄露等于整机权限泄露。

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
| `GET` | `/health` | 健康报告（UP / WARN / DOWN）；**配了 API key 时只有它不校验**，探活无需凭据 |

**SSE 事件**：`turn_start` / `text` / `thinking` / `tool_start` / `tool_output` / `tool_done` /
`approval_required` / `approval_resolved` / `done` / `cancelled` / `error`；空闲超时写 `: keepalive` 注释帧。
`done` / `cancelled` / `error` 是终态，写出后流结束。

- `tool_output` 是工具**执行期**的实时输出（命令行跑十分钟时能看见动静），载荷 `{turnId,toolCallId,toolName,chunk}`。
  **它是可丢的过程信息**：服务端按待发条数封顶（超出即丢），客户端应当把它当成进度展示，
  **权威结果始终是 `tool_done` 里的 `output`**。
- `tool_done` 除了 `output` 还带 `metadata`：工具结果的结构化事实（命令行的 `exitCode` / `terminal`）。
  **前端据字段渲染失败标记，不要去解析 `output` 的首行文案**——那行措辞是给模型看的，改一个词标记就会消失。
  **工具抛异常时也带 `metadata.terminal=FAILED`**（并按条件带 `summary` 说明原因），因此前端不必再读 `success` 就能画出失败标记。
  没有元数据时它是空对象 `{}`。

几条与内核语义相关的约定：

- **会话一律按路径里的 id 寻址**；`-server` 不支持 `--session`（写了判用法错误退 `2`），
  `--agent` / `--model` / `--mode` 降级为「新建会话的默认值」。启动期不预建任何会话。
- **同会话同时只允许一个回合**：第二个请求返回 `409`（避免两个回合把消息历史交错写坏）；
  要打断就用 `POST /sessions/{id}/cancel`，或直接断开 SSE 连接（服务端据此取消回合）。
- **人工审批走 HTTP**：`askTools` 里的工具会在流里推 `approval_required`，客户端拿 `requestId` 调
  `POST /approvals/{requestId}`。`ApprovalChannel` 是**全局单槽位**，因此任一时刻最多只有一条待审批项，
  多会话并发时后面的会排队。
- **错误体**统一为 `{"error":"CODE","message":"…"}`；命令执行的三态在 `kind` 字段里
  （`UNKNOWN` 同时回 404）；鉴权失败是 `401` + `{"error":"UNAUTHORIZED"}`，并带
  `WWW-Authenticate: Bearer realm="jellyfish"`。

## 压缩上下文（`/compact`）

长会话的每一轮都要把整段历史重新发给模型，token 花得越来越多。压缩多花**一次**调用把更早的
对话压成一份摘要，之后每次请求只带摘要 + 最近若干条原文：

> **前提**：压缩由插件提供策略（摘要指令 + 参数）。没有启用 `jellyfish-compact`（或同类插件）时，
> 压缩整体不可用——`/compact` 会直接告诉你，自动压缩也不生效。策略插件在独立插件仓库中，细节见其文档。

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

## 委派给子代理（`task`）

模型可以把一段自足的子任务**委派给另一个 agent** 去做，只拿回它的最终结论。这个能力是**内核自带的**，不需要额外装插件；它由 `agents.json` 里标了 `delegatable: true` 的 agent 提供。

**子代理与主会话除了那段任务之外相互隔离**：它看不到本次对话的历史、你读过哪些文件、主会话用的是哪个模型。它拿到的只有三样——它自己那份 `{agentId}.md` 提示词、项目约定（`AGENTS.md`）、以及模型写的那段 `task.prompt`。因此**任务描述必须自足**，背景、目标、验收标准都要写进去。

代价与收益是同一件事的两面：探索与实现产生的噪音留在子代理那边，主对话只多一行结论；反过来，「挑战我刚才的想法」这类需要共享对话背景的用法，就只能靠模型把背景写进任务描述。

配好就能用：

```json
{ "agents": { "scout": {
  "description": "只读摸清相关代码，只回传结论",
  "delegatable": true,
  "model": "openai/gpt-4o-mini",
  "permissions": { "allowedTools": ["read_file", "list_dir", "grep_files"] }
} } }
```

四条值得知道的约束：

- **`allowedTools` 决定了子代理能看到哪些工具**（执行时也照旧受权限判定约束）。只给它只读工具，它就既看不到也调不了写类工具——这既是省 token，也是「别邀请它去做一件必然被拒的事」。这条**只随委派生效**：主会话照旧看到全部工具。
- **子代理不继承主会话的模型**：它的模型来自自己的 `model` 字段，没配就落到 `models.json` 的全局默认。想在探索上省钱，就在那份定义里写一个便宜的模型。
- **它跑在自己的会话里，但那个会话不留痕**：不落盘、不进 `/session` 列表，进程退出也不会「恢复」出一堆子代理会话。但它花掉的 token **计入父会话**（`/usage` 看得到），生命周期事件带 `parentSessionId` 供可观测性区分。
- **递归有两道上限**：`subAgent.maxDepth`（一条链多深）与 `subAgent.maxSpawnsPerTurn`（一层扇出多少），见下。子代理自己能不能再往下委派，取决于它的 `allowedTools` 里有没有 `task`（省略 `allowedTools` 表示不限制工具，那时只受深度上限约束）。

屏幕上你能看到的：子代理在跑的时候，工具轨迹那块会出现 `⎿ task`，下面缩进两格跟着它的每一步工具调用（`  · read_file`）；完成之后收成一行

```
      ⎿ task · 子代理 scout · 3 轮 · 123456 tok
```

命令行模式同口径（走 stderr）：`← task 完成（26 字符） · 子代理 scout · 3 轮 · 123456 tok`。

（出问题时末尾再加一个警示标记，如 `⎿ task · 子代理 scout ⚠ REJECTED`；`⚠` 后面是原因。token 是精确值，可与 `/usage` 里的数字直接对上。）子代理的报告正文不进屏幕——它是一整篇长文，会作为工具结果回灌给模型；轨迹行上那一句摘要就是「刚才那一行到底是什么事」的答案。

委派被拒绝时（类型写错、层数用尽、任务为空……）模型拿到的是同一段文本——它会换个参数重试，或者自己把活干了。

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
  "model":     { "globalPath": "~/.jellyfish/models.json",    "projectPath": "./.jellyfish/models.json" },
  "agent":     { "globalPath": "~/.jellyfish/agents.json",    "projectPath": "./.jellyfish/agents.json" },
  "jellyfish": { "globalPath": "~/.jellyfish/jellyfish.json", "projectPath": "./.jellyfish/jellyfish.json" },
  "plugins":   { "roots": ["~/.jellyfish/plugins/"] }
}
```

仓库里的 `config.json` 就是这份：**全局级约定目录 `~/.jellyfish/`、项目级约定目录 `<工作目录>/.jellyfish/`**，四类配置的文件名固定。要换位置只改 `config.json`。

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
    },
    "scout": {
      "description": "只读摸清相关代码，只回传结论",
      "delegatable": true,
      "model": "openai/gpt-4o-mini",
      "permissions": {
        "allowedTools": ["read_file", "list_dir", "grep_files"]
      }
    }
  }
}
```

除了 `description` / `permissions`，还有两个字段：

- **`delegatable`**（缺省 `false`）：这个 agent 能不能被 `task` 工具当作委派目标。不配就不出现在模型看到的可用类型清单里（见「委派给子代理」）。
- **`model`**：这个 agent 的偏好模型，写法与 `/model` 参数一致（`openai/gpt-4o-mini` 或裸 `gpt-4o-mini`）。解析顺序是**会话显式选定 → `agent.model` → `models.json` 的全局默认**，因此绑定一个 agent 会连它的模型一起生效，而你在会话里 `/model` 过之后以会话为准。

三段工具名的语义是：**字段缺失 = 不限制**，显式写 `[]` = 该方向上一个都不放行（`allowedTools: []` 就是全拦，`enabled` / `deniedTools` 同理）。因此想表达「只显式拒绝两个工具、其余不限制」就**不要**写 `"allowedTools": []`，直接省略该字段。

> `allowedTools` 还有一层作用：它同时决定**子代理能看到哪些工具**（只随委派生效，主会话不受影响）。见「委派给子代理」。

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
      "dir": "~/.jellyfish/tool-outputs",
      "keepFiles": 200,
      "maxBytes": 52428800,
      "keepRecentMessages": 20,
      "spillMaxBytes": 33554432
    }
  },
  "permission": {
    "approvalTimeoutSeconds": 120
  },
  "subAgent": {
    "enabled": true,
    "maxDepth": 2,
    "maxSpawnsPerTurn": 32,
    "maxRounds": 8
  }
}
```

`react` 段控制 ReAct 循环：`maxRounds` 是单回合最大轮数；`contextReserveTokens` 是上下文预算里为系统提示词 / 插件注入的上下文预留的 token；`maxToolOutputChars` 是单个工具输出回灌模型前的截断长度，也是**硬上限**（工具失控时由它保命）；`compactKeepRecentMessages` 是压缩默认保留的最近消息条数（写 `0` 即「不保留原文」）；`compactMaxSummaryChars` 是摘要长度上限（提示模型别写太长，真超了按码点本地截断并留标记）；`autoCompactPercent` 是上下文用到多少百分比就自动压缩（写 `0` 关闭自动压缩，只留手动 `/compact`）。缺省值即为上表；非法值（非正数）回退到缺省值。

`react.toolOutput` 段只管工具结果太长时怎么办：`dir` 是完整内容的落盘根目录（缺省 `~/.jellyfish/tool-outputs`，运行产物写在这里而不是项目目录）；`keepFiles` / `maxBytes` 是每个会话在该目录下的文件数与字节数上限（缺省 200 个 / 50 MiB，写 `0` 关闭清理），超了从最旧的开始删；`keepRecentMessages` 是组装请求时最近多少条消息里的工具结果保留完整内容（缺省 20，写 `0` 关闭该裁剪）；`spillMaxBytes` 是单个工具结果的落盘上限（缺省 32 MiB，运行期钳制为不超过 `maxBytes`），触及上限时后续内容不再保存、信封会带 `_partial` 说明它不完整。

**信封里的 `_path` 只在保留窗口内有效**：上面那两个清理上限一旦被触到，就从最旧的文件开始删，因此**几十轮之前那个路径可能已经不在了**（`read_file` 会报文件不存在）。这不是缺陷——文件是运行产物，引用计数式保留会让落盘与会话历史互相耦合。要长期留用的内容请在它还在时另存一份。

**工具结果超过 `maxToolOutputChars` 时不会被从中间切断**：完整内容先落盘，回灌给模型的是一段合法 JSON 信封，内含 `_truncated`、原始大小、`_path` 与 `preview`（结构化结果是截断后的子树，纯文本是截断后的字符串）。**预览取头 30% + 尾 70%**，并写明省略了多少行、多少字符——结论往往在末尾，只留头会让模型看到「一切正常的前 90%」。模型据此知道发生了什么、去哪回查。工具自身也默认限流（`read_file` 的 `max_bytes`、`list_dir` 的 `limit`/`offset`、`grep_files` 的 `max_line_chars`/`max_bytes`），让绝大多数调用根本用不到内核这层兜底。

**工具结果还带结构化元数据**：命令行的退出码与终止原因既出现在回灌文本的首行（给模型读），也作为字段随会话与 SSE 一起走（给界面与审计读）。两者同源，因此不会出现「文本说成功、字段说失败」。元数据随工具结果消息落进会话文件，**重启后警告标记仍在**。**工具抛异常时内核也补 `terminal=FAILED`**，并在工具按约定抛出 `JellyfishException` 时把它那句原因的首行放进 `summary`——于是 TUI 上能看到 `⚠ FAILED` 与为什么失败，而不必去翻日志。

**`read_file` 遇到「单行就超过 `max_bytes`」会报错而不是切短**：切短会产出一行看起来完整、实际残缺的内容，而模型无从判断自己拿到的是不是全文。错误信息里给了三条出路（缩小 `limit`、调大 `max_bytes`、改用 `grep_files` 定位）。多行累加超预算仍然是正常分页（内容还在文件里，可按 `offset` 续读）。

`permission` 段当前只有 `approvalTimeoutSeconds`：`askTools` 里的工具在 TUI 上弹审批框后最多等这么久，超时按拒绝处理。它有缺省值（120 秒）而不允许「永不超时」——审批请求发生在 `react` 线程上并被同步等待，一个永远不来的答复就是一条永远不返回的线程。

`subAgent` 段控制子代理委派：`enabled` 是全局开关（缺省 `true`，**关掉后 `task` 工具直接不再注册**，模型看不到它，敲 `/reload` 即可生效、不用重启）；`maxDepth` 是允许的最大委派层数（缺省 `2`，即「主会话 → 子代理 → 孙代理」，写 `0` 表示禁止委派）；`maxSpawnsPerTurn` 是单个顶层回合内允许派生的子代理总数（缺省 `32`）——深度挡的是「一条链多深」，它挡的是「一层扇出多少」，两者正交，只有其中一个都不够；`maxRounds` 是子代理自己那个回合的最大轮数（缺省 `8`，**不跟随** `react.maxRounds`：子代理被设计来干一件窄活）。除 `enabled` 外非法值一律回退缺省值。

约定：

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），JSON 的 key 不替换。`{agentId}.md` 提示词是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录（`~other/...` 这种指定其他用户的形式不展开）；两条路径都支持。项目级路径相对**进程工作目录**解析，不是相对 jar 位置。文件不存在视为「该源未配置」，静默跳过（这是 `globalPath` 与 `projectPath` 可以同时配上、缺哪份就少哪份的原因）。`config.json` 的 `plugins.roots` 同样支持 `~` 与相对路径，语义一致。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级，agent 的提示词 md 也随来源一起覆盖；`defaultProvider` / `defaultModel` 取项目级非空值，否则回退全局级；`react` / `permission` / `subAgent` 段项目级整对象覆盖全局级；启用 / 禁用名单项目级**已声明则整体替换**（写 `[]` 即清空该名单，不做并集）。`plugins.roots` 只在 `config.json` 一处，不参与双源合并。
- **agent 提示词**：每个 agent（包括内置的系统 agent）的系统提示词来自与配置文件同目录的 `{agentId}.md`；`agentId` 同时是文件名，因此不能含路径分隔符或 `..`（含这类字符的条目会被整条丢弃并告警）。文件不存在不阻断启动，只有用户 `/agent` 切过去时才提示「该 agent 没有系统提示词」。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。

### 想真跑一轮对话

`config.json` 默认从 `~/.jellyfish/`（全局级）与 `./.jellyfish/`（项目级）读取三份配置。开箱能跑命令（如 `/help`），但没有模型配置时一发对话就会提示没有可用模型。要真跑：

1. 在项目根目录建 `.jellyfish/models.json`（格式见上方示例），apiKey 用环境变量注入：`"apiKey": "${OPENAI_API_KEY}"`；
2. 按需建 `.jellyfish/agents.json` 与 `.jellyfish/jellyfish.json`；
3. 想让配置对**这台机器上的所有项目**生效，把同样的文件放到 `~/.jellyfish/` 即可（项目级同名条目会整对象覆盖全局级）。

两个目录里的文件名与上方四类配置一一对应，不要改成别的名字。

## 插件

Jellyfish 通过 PF4J 插件扩展能力。**官方插件已拆分到独立仓库**（`Jellyfish-Plugins`），本仓库只保留插件机制与 SPI：`jellyfish-api`（插件契约）与 `jellyfish-infra` 的插件运行时（跨语言桥接运行时随桥接插件走独立插件仓库）。

插件是**独立打包的 PF4J jar**，由内核从 `config.json` 的 `plugins.roots` 扫描加载（见「配置」一节），与内核之间**没有编译期依赖**。插件能做什么由内核的扩展点与权限模型决定：

- **扩展点是内核与插件之间唯一的边界**：工具、命令、提示词贡献、权限拦截、会话持久化 / 恢复、压缩策略、UI 贡献、输入指令走同步扩展点；轮次与会话等通知走异步事件通道。
- **插件拿不到会话与工作目录**，只拿得到 `PluginContext`（handle / contribute / observe / emit）；工具的相对路径按进程工作目录解析。
- **权限只能收紧不能放宽**：插件拦截是三态（`ABSTAIN` / `ASK` / `DENY`），没有 `ALLOW`；只读白名单 = 工具描述符声明 ∪ `plugins.configurations.<pluginId>.readOnlyTools`（用户只能追加）。
- **启用 / 禁用与配置**：`plugins.enabled` 缺省表示不限制，显式 `[]` 表示一个都不启用；单个插件的配置写在 `plugins.configurations.<pluginId>`。
- **新增 / 删除插件 jar 需要重启进程**（扫描目录与插件集合只在启动期确定）；`jellyfish.json` 里插件配置段的变化由 `/reload` 按差异重启对应插件。

官方插件（tools / session-file / todo / project / compact / shell / skills / mcp / python / node）的清单、安装方式、逐插件配置与用法，见独立仓库（`Jellyfish-Plugins`）的文档。
