# Jellyfish

用 Java 1.8 编写的轻量级 AI Agent 工具：单次问答、交互式终端界面、HTTP 服务三种形态共享同一个内核，
能力通过 PF4J 插件扩展。

- **三种形态，一个内核**：`-cli` 一次问答、`-tui` 终端界面、`-server` REST + SSE 服务。
- **工具即能力**：模型可以读写文件、跑命令、搜索代码（工具由插件提供），每次调用都过权限判定。
- **权限可控**：按模式收窄的授权由插件提供——官方 plan 插件只放行你列在 `readOnlyTools` 里的工具；`askTools` 里的工具逐次人工审批（TUI 弹框、Server 走 HTTP）。
- **上下文自己管**：长会话可压缩成摘要（`/compact`），工具输出过长自动落盘、回灌信封。
- **可委派**：把一段子任务丢给只读的子代理去做，只拿回结论，噪音留在那边。
- **插件式扩展**：工具、命令、提示词、权限拦截、会话持久化、UI 贡献、模型厂商都是扩展点，插件与内核无编译期依赖。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 8（源码与目标版本都是 1.8）；运行只需 JRE 8+ |
| Maven | 3.2.5 以上（构建用；`pom.xml` 里打包插件的版本线以此为准） |
| 终端 | `-tui` 需要可交互终端；`-cli` / `-server` 不需要 |
| 模型 | 一个 OpenAI 兼容的 API（没有也能跑命令，见下） |

## 构建

```bash
git clone <仓库地址> && cd Jellyfish
mvn -o clean package              # 全量构建 + 单测；-DskipTests 可跳测
```

产物是**一个可执行 fat jar**，三种模式都用它启动：

```
jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar
```

嫌路径长就设个别名：

```bash
alias jellyfish='java -jar /绝对路径/jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar'
```

常用构建命令（开发向）：

```bash
mvn -q compile                     # 只编译
mvn -q package -DskipTests         # 只打包
mvn -q test                        # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Dtest=ChatStateTest test   # 单类单测
```

## 快速开始

### 1. 先离线验证安装

**不需要模型配置**就能跑命令，用它确认 jar 起得来：

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli -p "/help"
```

看到命令清单就说明装好了。

### 2. 配一份模型

配置按「全局级 `~/.jellyfish/` + 项目级 `<工作目录>/.jellyfish/`」双源读取，项目级优先。
在项目根建 `.jellyfish/models.json`：

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

再把密钥放进环境变量（**不要把密钥写进配置文件**）：

```bash
export OPENAI_API_KEY=sk-...
```

想对**这台机器上的所有项目**生效，把同样的文件放到 `~/.jellyfish/models.json`。

`type` 也可以写成**插件提供的新类型**（本地 llama.cpp、企业自建网关、私有协议、自定义鉴权）——
插件能接管内核不认识的类型，而内核自带的类型（`openai` / `claude` / …）不允许被插件顶替。
这类 provider 的 `models` 还能由插件在启动与 `/reload` 时动态发现，发现结果不落盘。详见
[configuration.md](docs/configuration.md) 的 `models.json` 一节。

### 3. 问第一句话

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli -p "总结一下这个项目"
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
```

## 三种运行模式

三种模式共享同一个入口与同一份装配，只靠启动参数区分：

| 模式 | 形态 | 示例 |
| --- | --- | --- |
| `-cli` | 单次调用、不交互：进一个输入，出一次结果后退出 | `jellyfish -cli -p "今天天气怎么样？"` |
| `-tui` | 交互式终端界面（TamboUI）：消息区 + 输入框 + 状态栏 | `jellyfish -tui` |
| `-server` | HTTP 服务（Undertow），对外暴露 REST + SSE 接口 | `jellyfish -server 9096` |

### 参数

| 参数 | 说明 |
| --- | --- |
| `-cli` / `-tui` / `-server` | 模式旗标，三选一且必填；`-server` 可带位置端口 |
| `-p, --print <输入>` | 单次模式的输入；缺省时从 stdin 读到 EOF（管道可用） |
| `--session <会话>` | 切换到已有会话（需安装 `jellyfish-session-file` 等持久化插件；会话不存在时按用法错误退出 `2`） |
| `--agent <agentId>` | 新建会话时绑定 agent |
| `--model <provider/模型>` | 新建会话时指定模型，必须含 `/` |
| `--port <端口>` | 服务器端口（等价于 `-server` 的位置参数，缺省 `9096`） |
| `--host <地址>` | 服务器绑定地址（缺省 `127.0.0.1`） |
| `--show-thinking` | 展示模型的思考过程：`-cli` 打到 stderr，`-tui` 置为启动时展开 |
| `--show-tool-args` | `-cli` 的工具轨迹行上打出调用参数（单行，过长截断；TUI 用 `Ctrl+E` / `/toolargs`） |
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
| `7` | 回合被插件拦下：一句都没发给模型（stdout 保持为空，原因在 stderr） |

单次模式里输入以 `/` 开头就走命令域（`/help` `/model` `/agent` `/new` …），否则走一次 LLM 对话。

单次模式的工具轨迹默认只给「开始」与「结束」两行（`→ shell` / `← shell 完成（N 字符）`）；
想看模型到底要跑什么，加 `--show-tool-args`，参数会以**单行、最多 200 字**的形式跟在其后
（`→ shell {"command": "mvn -q test", "cwd": "/x"}`）。它只归 `-cli`：TUI 用运行期开关，
`-server` 没有终端界面，两者带上它一律按用法错误退 `2`。**参数不脱敏**（与 Codex 的 `--verbose` 同口径）：
外壳按参数名猜不出哪个是密钥，遮不住命令原文与写入正文这些真正会出事的地方，所以把
「参数里可能有敏感信息」当作你自己知道的前提——`--show-tool-args` 的日志同样如此。

## TUI 用法

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
```

需要**可交互终端**（备用屏 + raw 模式）。在管道、CI 或没有终端的环境里启动会**立刻报错并退出 3**，不会挂住。

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
 agent · provider/模型 · token 用量（会话被压缩过时追加 `已压缩 N 条（丢弃 M 条）`）
```

| 按键 | 行为 |
| --- | --- |
| `Ctrl+S` | **发送**（`Enter` 是换行） |
| `Enter` | 换行（可写多行，输入框 1～6 行自适应） |
| `Ctrl+C` | 退出 |
| `Ctrl+T` | 展开 / 折叠思考过程（与 `/thinking` 等价） |
| `Ctrl+E` | 展开 / 折叠工具调用参数（与 `/toolargs` 等价） |
| `Ctrl+O` | 交还 / 收回鼠标（与 `/mouse` 等价）：交还后可直接拖选并复制 |
| `Esc` | 中断当前回合（输入框内容保留） |
| `PageUp` / `PageDown` | 消息区翻页 |
| `End` | 跳到底部并恢复跟随 |
| `/exit` | 退出（由外壳处理，不在 `/help` 列表里） |
| `/ui` | 查看与切换插件的界面贡献（同样由外壳处理） |
| `/thinking` | 展开 / 折叠思考过程（同样由外壳处理） |
| `/toolargs` | 展开 / 折叠工具调用参数（同样由外壳处理） |
| `/mouse` | 交还 / 收回鼠标（同样由外壳处理；`/mouse on` / `/mouse off` 显式指定） |

**为什么发送不是 `Enter`**：终端 raw 模式下所有「带修饰的 Enter」都无法与普通 `Enter` 区分，因此「`Enter` 发送 +
修饰键换行」在任何终端上都不可实现。反转之后 `Enter` 稳定换行、`Ctrl+S` 稳定发送。

**思考过程默认折叠**：屏幕上只占一行（`✻ 思考过程（N 字，Ctrl+T 展开）`），流式期间是 `✻ 思考中…（N 字）`。
`Ctrl+T` 是**全局**开关：要么所有思考都展开，要么都折叠。`--show-thinking` 让 TUI 启动时就是展开态。

**工具轨迹行带调用参数**：轨迹行是 `⎿ 工具名 · 结果摘要 · 调用参数`，参数取自会话里 assistant 的
`toolCalls`（**执行之前就已落库**），按 `toolCallId` 与结果配对——所以运行期、回合结束后、`-resume` 之后
看到的是**同一份文本**，命令跑完不会消失。参数按显示列折行，折叠态最多占 8 行，超出以
`… 参数过长，已省略后续内容（Ctrl+E 展开）` 收尾；`Ctrl+E` / `/toolargs` 把它展开到 200 行。
**参数不脱敏**（与 Codex 的 `--verbose` 同口径）：按参数名猜遮不住真正会出事的地方（`command`、`content`），
而外壳并不知道哪个参数是密钥——所以需要遮蔽时应该由工具或用户显式声明，而不是由外壳猜。

**助手正文按 markdown 渲染**：标题、列表、引用、代码块、行内代码、加粗 / 斜体 / 删除线、链接、分隔线、表格都会渲染；
**表格画成网格**（按显示列量宽对齐，格子放不下就在列内折行；列数多到分不下时退回等宽代码块）。**用户消息与工具轨迹保持纯文本**
（只换行、不做行内样式），图片与 HTML 原样显示源码。

**滚轮可用，代价是终端选择被应用截走**：滚轮要求应用捕获鼠标（默认开启），而捕获后终端的鼠标选择归应用，
复制屏幕文本需按住修饰键（macOS 为 Option，而 Terminal.app 上的 Option 拖动是矩形选择，实际等于没有）。
想复制时按 `Ctrl+O`（或敲 `/mouse`）把鼠标交还终端：拖选 + `⌘C` 立刻可用，期间滚轮停用
（消息区滚动改用 `PageUp` / `PageDown` / `End`），复制完再按一下收回。若整体不要鼠标捕获，
用 `-Djellyfish.tui.mouseCapture=false` 退回：代价是滚轮在多数终端下**根本到不了应用**。

**TUI 没有 stdout 契约**：它独占备用屏，因此 `> answer.txt` 不适用，退出后也不回显会话内容。日志改写到文件
`<用户主目录>/.jellyfish/jellyfish-tui.log`（可用 `-Djellyfish.log.file=...` 改路径），绝不写 stderr——否则会撕坏画面。

**已知限制**：界面滚动到内容末尾时自动跟随；用户上翻后不再打扰，状态栏出现 `↓ N 行`（生成中则显示 `↓ 正在生成…`）。
消息区只投影最近 500 条消息，更早的以 `⎿ N 条更早的消息已折叠` 占位。编辑器的「光标到行尾」不可用
（`End` 归消息区）；`\r\n` 输入会变成两个换行。

### 输入指令 `!` 与文件引用 `@`

输入框支持两种**由插件提供**的特殊语法（没有装对应插件时，它们只是一段普通文本）：

| 输入 | 行为 | 依赖插件 |
| --- | --- | --- |
| `!命令` | 手动执行一条 shell 命令。**不进模型**，执行完把「命令回显 + 输出」作为一条用户消息落进会话，因此下一次提问时模型看得到结果 | `jellyfish-shell` |
| `@路径` | 引用当前工作目录下的文件：敲 `@` 弹补全面板（目录在前、可逐层往下钻），接受后路径写进输入框 | `jellyfish-tools` |

两条口径值得知道：

- **`!` 依旧过权限与审批**：它复用与模型调用完全相同的工具执行链路，只读命令静默执行、其余照旧弹审批框；
  `Esc` 可以中断正在跑的命令。
- **`@` 不内联文件内容**：引用只是把路径写进消息，读取由模型调用 `read_file` 完成。因此大文件不会撑爆上下文，
  `read_file` 的 `max_bytes` 与权限判定照旧生效。

### 审批

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

参数**原样显示**（含 `apiKey` 这类键的值）：审批要回答的是「放不放行这次调用」，而能回答它的只有原文。
超长参数折行显示并在截断处写明省略了几行；`Esc` 是「拒绝**并**中断回合」——只拒绝的话模型往往会换个方式接着试。
等不到答复（缺省 120 秒）同样按拒绝处理。

### 插件面板

插件可以往界面上放两类东西——状态栏尾部的一小段文本（例如待办插件的 `待办 2/5`），以及一块常驻面板（例如完整的待办清单）。
位置、宽度、行数全部由外壳决定，插件无权把消息区挤没。

面板占区域，而一块区域同一时刻只显示一个，因此两个插件抢同一位置时默认只显示 `order` 最小的那个，其余进入候选：

```
/ui                        列出所有贡献：区域 | 插件 | 标题 | 是否可见 | 还有哪些候选 | 没生效的插件快捷键
/ui right                  在该区域轮换到下一个候选
/ui right jellyfish-todo   指定由某个插件占用该区域
/ui right off              关掉该区域（/ui right on 恢复）
```

界面窄于 80 列时左右侧栏自动隐藏；窄到放不下时面板会让位给消息区，而不是反过来。整体不要插件 UI 时用
`-Djellyfish.tui.pluginPanels=false`。

### 在 IDEA 里调试

IDEA 的运行控制台默认不是真终端，直接跑 `-tui` 会命中「需要可交互终端」的检查。推荐做法是**在真实终端里启动、
用 IDEA 远程调试挂接**：

```bash
mvn -o package -DskipTests
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
```

然后 `Run → Edit Configurations → + → Remote JVM Debug`（默认就是 5005）→ 点 Debug。想在启动阶段（DI 装配、`bootstrap`）
下断点就把 `suspend=n` 改成 `suspend=y`。IDEA 内置的 Terminal 标签页是真 PTY，也可以直接在那里运行。
若确认终端可用却被拦下，用 `-Djellyfish.tui.skipTerminalCheck=true` 跳过检查。

## 常用命令

**输入以 `/` 开头即走命令域**，三条外壳（`-cli` / `-tui` / `-server`）命令一致。以下为内核自带：

| 命令 | 别名 | 说明 |
| --- | --- | --- |
| `/help [命令]` | `/h`、`/?` | 显示命令帮助 |
| `/new` | | 新建会话并切换为当前 |
| `/session` | `/sessions` | 列出全部会话 |
| `/resume <sessionId>` | | 切换到已有会话 |
| `/model [provider/model]` | | 查看或切换模型 |
| `/agent [agentId]` | `/a` | 查看或切换 agent |
| `/status` | | 显示当前会话概要 |
| `/usage` | `/cost` | 显示当前会话 token 用量与缓存命中率 |
| `/delete <sessionId>` | `/rm` | 删除会话（含持久化文件） |
| `/compact [preview]` | | 把更早的对话压成摘要（需压缩策略插件） |
| `/reload` | | 重新加载配置（模型 / agent / 插件） |

`-tui` 另有五个由外壳处理的命令，不在 `/help` 列表里：`/exit`、`/ui`、`/thinking`、`/toolargs`、`/mouse`。
**插件会带来更多命令**（例如待办插件的 `/todo`），装了就出现在 `/help` 里。

**离线可用**：`/help` `/session` `/status` `/model` `/compact preview` 这些命令**不需要模型配置**，可以拿来验证安装是否正常。

### 会话与压缩

长会话的每一轮都要把整段历史重新发给模型，token 花得越来越多。压缩多花**一次**调用把更早的对话压成一份摘要，
之后每次请求只带摘要 + 最近若干条原文：

```
/compact                 按默认档位压一次（保留条数取 react.compactKeepRecentMessages，缺省 20）
/compact preview         只回报「将压缩 X 条、保留 Y 条、丢弃 Z 条、摘要输入约 T token」，不发起压缩
```

**上下文用到 80% 时会自动压一次**（`react.autoCompactPercent`，写 `0` 关闭）；另外只要某一次请求已经触发了机械裁剪
（历史正在被静默丢弃），也会立刻压一次。自动压缩跑在当前那一轮模型调用的旁边，不拖慢对话，结果在下一轮生效。

三条最该知道的：**历史一条不删**（屏幕上的会话、`/resume`、落盘的文件都还是完整历史，变的只是「发给模型的那条链路从
哪里开始」）；**可以反复压**（每次只压上次边界之后新增的那段，边界只向后移）；**一次压完，装不下就丢最旧的**
（被丢弃的那段既不在摘要里、也不会再发给模型，完成提示会写明条数）。

压缩依赖**插件提供策略**：没有启用 `jellyfish-compact`（或同类插件）时压缩整体不可用，`/compact` 会直接告诉你。

### 委派给子代理

模型可以把一段自足的子任务**委派给另一个 agent** 去做，只拿回它的最终结论。这个能力**内核自带**，由 `agents.json` 里
标了 `delegatable: true` 的 agent 提供：

```json
{ "agents": { "scout": {
  "description": "只读摸清相关代码，只回传结论",
  "delegatable": true,
  "model": "openai/gpt-4o-mini",
  "permissions": { "allowedTools": ["read_file", "list_dir", "grep_files"] }
} } }
```

配好就能用。三条要注意的：**子代理与主会话除了那段任务之外相互隔离**（它看不到本次对话的历史、你读过哪些文件、
主会话用的哪个模型），因此**任务描述必须自足**；**`allowedTools` 只随委派生效**（只给它只读工具，它既看不到也调不了写类工具）；
**子代理不继承主会话的模型**（用自己的 `model`，没配则落到全局默认，想在探索上省钱就在定义里写个便宜模型）。

屏幕上你能看到的：子代理在跑时工具轨迹出现 `⎿ task`，下面缩进两格跟着它的每一步工具调用（`  · read_file`）；完成之后收成一行：

```
      ⎿ task · 子代理 scout · 3 轮 · 123456 tok · {"prompt": "…"}
```

末尾那段 `task.prompt` 就是你委派给它的原文（参数过长时折行并按上限收尾，按 `Ctrl+E` 展开）。

它花掉的 token **计入父会话**（`/usage` 看得到），但子代理的会话不留痕（不落盘、不进 `/session` 列表）。
递归有两道上限：`subAgent.maxDepth`（一条链多深）与 `subAgent.maxSpawnsPerTurn`（一层扇出多少）。

## 配置

配置分四份用户可改的文件，**文件位置与插件扫描目录只在 `config.json` 里声明**（本仓库为
`jellyfish-cli/src/main/resources/config.json`），其余文件走「全局级 + 项目级」双源，项目级优先：

| 文件 | 内容 | 寻找位置（缺省） |
| --- | --- | --- |
| `models.json` | `defaultProvider` / `defaultModel` / `providers` | `~/.jellyfish/` → `./.jellyfish/` |
| `agents.json` | 用户自定义 agent（提示词在同目录的 `{agentId}.md`） | 同上 |
| `jellyfish.json` | 插件名单与配置段、`react` / `permission` / `subAgent` 运行期设置 | 同上 |
| `config.json` | 进程名 + 各配置文件路径 + 插件扫描目录 | classpath 根，**不参与双源**，也不热更新 |

几条要点：

- **同名条目项目级整对象覆盖全局级**；`react` / `permission` / `subAgent` 段同样整段覆盖；启用 / 禁用名单项目级已声明则整体替换。
- **字符串值里的 `${VAR}` 会替换为环境变量**（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量）。
- **改了配置不用重启**：敲 `/reload` 即生效（重读配置、重建模型 / agent 索引，并**只重启配置段变了的插件**，
  同步等结果，毫秒级）。`config.json` 与新增 / 删除插件 jar 仍需重启进程。
- **配置缺失或可疑只告警，不中断启动**；真正用到时才报错。
- **不要提交密钥**：`apiKey` 用环境变量注入。

上面列出的是常用字段；`jellyfish.json` 的 `react` / `permission` / `subAgent` 等段还有更多键，缺省值即开箱可用的配置。

## 插件

Jellyfish 通过 PF4J 插件扩展能力。插件是**独立打包的 PF4J jar**，由内核扫描加载，与内核之间**没有编译期依赖**。

**安装**：把插件 jar 放进扫描目录（缺省 `~/.jellyfish/plugins/`，PF4J 在目录下一层找 `plugin.properties`），
然后**重启进程**——新增 / 删除插件 jar 只在启动期确定：

```bash
mkdir -p ~/.jellyfish/plugins
# 把插件 jar 放进去，然后重新启动 jellyfish
```

扫描目录在 `config.json` 的 `plugins.roots` 里（顺序即扫描顺序，支持 `~` 与相对路径）。启用 / 禁用名单与逐插件配置段
写在 `jellyfish.json`，变化由 `/reload` 生效。

**去哪里找插件**：仓库 **`Jellyfish-Plugins`**（<https://github.com/zcd0831/Jellyfish-Plugins>）提供了部分常用插件，
本仓库只保留插件机制与 SPI（`jellyfish-api` 的插件契约 + `jellyfish-infra` 的插件运行时）。

插件能做什么由内核的扩展点与权限模型决定：工具、命令、提示词贡献、权限拦截、会话持久化 / 恢复、压缩策略、UI 贡献、
输入指令走同步扩展点；轮次与会话等通知走异步事件通道。**插件拿不到会话与工作目录**，也**不能自称某个写操作是只读的**
（`ToolDescriptor` 里已没有该字段；按模式收窄的名单归插件自己的配置段，例如官方 plan 插件的
`plugins.configurations.jellyfish-plan.readOnlyTools`）。

## Server 模式

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -server 9096
```

**默认只绑 `127.0.0.1`**，且**没配密钥时不鉴权**——对只绑回环的本地场景够用。对外开放必须显式 `--host 0.0.0.0`
并配上 API key：

```bash
# 推荐：密钥走环境变量（不会出现在 ps 输出里）
JELLYFISH_SERVER_API_KEY=$(openssl rand -hex 32) java -jar ...jar -server --host 0.0.0.0
# 也可以：命令行参数（argv 会出现在 ps 里，同机其他用户看得见）
java -jar ...jar -server --host 0.0.0.0 --api-key <密钥>
```

配了密钥后，**除 `GET /health` 外所有接口**都要 `Authorization: Bearer <密钥>`。跑通一次：

```bash
curl -s localhost:9096/health
SID=$(curl -s -X POST localhost:9096/sessions | jq -r .sessionId)
curl -sN -X POST localhost:9096/sessions/$SID/chat \
     -H 'Content-Type: application/json' -d '{"message":"总结这个项目"}'
```

**接口速查**：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/sessions` | 建会话（body 可选 `agentId`/`provider`/`model`） |
| `GET` | `/sessions` | 会话摘要列表（不含消息正文） |
| `GET` | `/sessions/{id}` | 完整会话快照（含消息与用量） |
| `DELETE` | `/sessions/{id}` | 删除会话 |
| `POST` | `/sessions/{id}/chat` | 对话，**恒为 SSE**（body 为 `{"message":"…"}`） |
| `POST` | `/sessions/{id}/cancel` | 取消在途回合 |
| `POST` | `/sessions/{id}/commands` | 执行命令 |
| `GET` | `/commands` | 结构化命令清单 |
| `GET` | `/commands/{name}/options` | 命令候选值 |
| `GET` | `/approvals` · `POST /approvals/{requestId}` | 待审批项与裁决 |
| `GET` | `/health` | 健康报告（UP / WARN / DOWN） |

几条接入方必须知道的：**同会话同时只允许一个回合**（第二个请求 `409`，要打断用 `/cancel` 或断开 SSE）；
**人工审批走 HTTP**（流里推 `approval_required`，拿 `requestId` 调 `POST /approvals/{id}`，全局单槽位因此多会话会排队）；
**`tool_output` 是可丢的过程信息**，权威结果是 `tool_done` 的 `output`；**错误体统一为** `{"error":"CODE","message":"…"}`。

## 常见问题

**没配模型会怎样？**
命令照常能用（`/help` `/session` `/status` `/model` `/compact preview` 都不需要模型），一发对话就会提示没有可用模型。
先按「快速开始」建一份 `models.json`。

**`-tui` 启动就退出 3？**
它在管道、CI、被重定向的 shell 或 IDE 的非真终端控制台里都会这样（这是有意的：否则 TamboUI 会永久挂住）。
在真实终端里运行，或按「在 IDEA 里调试」一节用远程调试挂接。确认终端可用却被拦下时用
`-Djellyfish.tui.skipTerminalCheck=true` 跳过检查。

**`-cli` 下工具调用总被拒绝？**
`-cli` 没有审批界面，`askTools` 里的工具**一律按拒绝处理**（绝不静默放行）。要在无人值守下跑，就别把该工具放进
`askTools`；要人工确认就用 `-tui` 或 `-server`。

**命令行的失败标记怎么来的？**
工具结果的信封里带 `metadata`（`exitCode` / `terminal`），界面按字段渲染，不解析文本。前端同理。

**`read_file` 报了「单行超过上限」？**
这是刻意的：切短会产出一行看起来完整、实际残缺的内容。按错误信息给的三条出路办——缩小 `limit`、调大 `max_bytes`、
或改用 `grep_files` 定位。

**几十轮之前的工具输出路径不见了？**
落盘目录有清理上限（缺省每会话 200 个文件 / 50 MiB），从最旧开始删，所以 `_path` 只在保留窗口内有效。
要长期留用的内容请在它还在时另存一份。

**滚动看着不对 / 复制文本选不中？**
滚轮依赖鼠标捕获（默认开），而捕获后终端本地的鼠标选择归应用：想复制时按 `Ctrl+O`（或敲 `/mouse`）把鼠标交还终端，
拖选 + `⌘C` 即可，期间滚轮停用、消息区滚动改用 `PageUp` / `PageDown` / `End`，复制完再按一下收回。
若整体不要鼠标捕获，用 `-Djellyfish.tui.mouseCapture=false` 退回滚轮换取原生选择。

**改了配置不生效？**
三个配置文件敲 `/reload`；`config.json`、新增 / 删除插件 jar 需要重启进程。

## License

[MIT](LICENSE)
