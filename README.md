# Jellyfish

用 Java 1.8 编写的轻量级 AI Agent 工具：单次问答、交互式终端界面、HTTP 服务三种形态共享同一个内核，
能力通过 PF4J 插件扩展。

- **三种形态，一个内核**：`-cli` 一次问答、`-tui` 终端界面、`-server` REST + SSE 服务。
- **工具即能力**：模型可以读写文件、跑命令、搜索代码（工具由插件提供），每次调用都过权限判定。
- **权限可控**：按模式收窄的授权由插件提供——官方 plan 插件只放行你列在 `readOnlyTools` 里的工具；`askTools` 里的工具逐次人工审批（TUI 弹框、Server 走 HTTP）。
- **上下文自己管**：长会话可压缩成摘要（`/compact`），工具输出过长自动落盘、回灌信封。
- **可委派**：把一段子任务丢给只读的子代理去做，只拿回结论，噪音留在那边。
- **插件式扩展**：工具、命令、提示词、权限拦截、会话持久化、UI 贡献、模型厂商都是扩展点，插件与内核无编译期依赖。

## 姊妹仓库

| 仓库 | 是什么 |
| --- | --- |
| **Jellyfish**（本仓库） | 内核：三种外壳、装配、扩展点与插件运行时、插件 SPI |
| [Jellyfish-Plugins](https://github.com/zcd0831/Jellyfish-Plugins) | 官方插件（文件工具 / shell / 会话持久化 / 待办 / 压缩 / skills / MCP / 编排 / plan 模式 / 脚本插件运行时）+ **插件开发教程** |
| [Jellyfish-Spring-Boot-Starter](https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter) | 把内核接进**已有的 Spring Boot 应用** |

**插件不在本仓库**：这里只保留插件机制与 SPI。缺什么能力就去插件仓库找，或者按那边的教程自己写一个。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 8（源码与目标版本都是 1.8）；运行只需 JRE 8+ |
| Maven | 3.2.5 以上（构建用；`pom.xml` 里打包插件的版本线以此为准） |
| 终端 | `-tui` 需要可交互终端；`-cli` / `-server` 不需要 |
| 模型 | 一个 OpenAI 兼容的 API（没有也能跑命令，见下） |

## 安装与构建

```bash
git clone https://github.com/zcd0831/Jellyfish.git && cd Jellyfish
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

想对**这台机器上的所有项目**生效，把同样的文件放到 `~/.jellyfish/models.json`。全量字段见「配置」一节。

### 3. 问第一句话

```bash
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar                    # 不带参数 = 交互界面（-tui）
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -cli -p "总结一下这个项目"
java -jar jellyfish-cli/target/jellyfish-cli-0.0.1-SNAPSHOT.jar -tui
```

## 三种运行模式

三种模式共享同一个入口与同一份装配，只靠启动参数区分。**一个参数都不带直接运行 = `-tui`**——
交互界面是最常用的入口，不必先把旗标背下来。但兜底只此一处：**只要带了任何参数，模式就必须显式给**
（`jellyfish --verbose` 一样报「请指定启动模式」并退 `2`），这样「敲了没反应」和「悄悄挂在等 stdin」都不会发生。

| 模式 | 形态 | 示例 |
| --- | --- | --- |
| `-cli` | 单次调用、不交互：进一个输入，出一次结果后退出 | `jellyfish -cli -p "今天天气怎么样？"` |
| `-tui` | 交互式终端界面（TamboUI）：消息区 + 输入框 + 状态栏 | `jellyfish -tui`（等价于裸跑 `jellyfish`） |
| `-server` | HTTP 服务（Undertow），对外暴露 REST + SSE 接口 | `jellyfish -server 9096` |

### 参数

| 参数 | 说明 |
| --- | --- |
| `-cli` / `-tui` / `-server` | 模式旗标，三选一；**一个参数都不带时默认 `-tui`**，否则必填；`-server` 可带位置端口 |
| `-p, --print <输入>` | 单次模式的输入；缺省时从 stdin 读到 EOF（管道可用） |
| `--session <会话>` | 切换到已有会话（需安装 `jellyfish-plugin-session-file` 等持久化插件；会话不存在时按用法错误退出 `2`） |
| `--agent <agentId>` | 新建会话时绑定 agent |
| `--model <provider/模型>` | 新建会话时指定模型，必须含 `/` |
| `--port <端口>` | 服务器端口（等价于 `-server` 的位置参数，缺省 `9096`） |
| `--host <地址>` | 服务器绑定地址（缺省 `127.0.0.1`） |
| `--show-thinking` | 展示模型的思考过程：`-cli` 打到 stderr，`-tui` 置为启动时展开 |
| `--show-tool-args` | `-cli` 的工具轨迹行上打出调用参数（单行，过长截断；TUI 用 `Ctrl+E` / `/toolargs`） |
| `--verbose` | 日志级别降到 DEBUG（也可用 `-Djellyfish.log.level=DEBUG`） |
| `-h, --help` / `-V, --version` | 帮助 / 版本号 |

`--agent` / `--model` / `-p` / `--show-thinking` / `--show-tool-args` **只归 `-cli`**：`-tui` / `-server` 带上它们
一律判用法错误退 `2`（拒绝而不是静默忽略）。

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
加 `--show-tool-args` 会在其后补上**单行、最多 200 字**的调用参数（`→ shell {"command": "mvn -q test", "cwd": "/x"}`）。
**参数不脱敏**：外壳按参数名猜不出哪个是密钥，遮不住命令原文与写入正文这些真正会出事的地方。
`--show-tool-args` 与 TUI 的 `Ctrl+E` 同此口径。

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
| `/ui` | 查看与切换插件的界面贡献（同样由外壳处理；两级选择页） |
| `/thinking` | 展开 / 折叠思考过程（同样由外壳处理） |
| `/toolargs` | 展开 / 折叠工具调用参数（同样由外壳处理） |
| `/mouse` | 交还 / 收回鼠标（同样由外壳处理；`/mouse on` / `/mouse off` 显式指定） |

**为什么发送不是 `Enter`**：终端 raw 模式下所有「带修饰的 Enter」都无法与普通 `Enter` 区分，因此
「`Enter` 发送 + 修饰键换行」在任何终端上都不可实现——反转之后 `Enter` 稳定换行、`Ctrl+S` 稳定发送。

**思考过程默认折叠**：屏幕上只占一行（`✻ 思考过程（N 字，Ctrl+T 展开）`），流式期间是 `✻ 思考中…（N 字）`。
`Ctrl+T` 是**全局**开关（要么都展开、要么都折叠）；`--show-thinking` 让 TUI 启动时就是展开态。

**工具轨迹行带调用参数**：轨迹行是 `⎿ 工具名 · 结果摘要 · 调用参数`，参数取自会话里 assistant 的 `toolCalls`
（执行之前就已落库），按 `toolCallId` 与结果配对——所以运行期、回合结束后、`--session` 恢复之后看到的是
**同一份文本**。参数按显示列折行，折叠态最多 8 行，超出以 `… 参数过长，已省略后续内容（Ctrl+E 展开）` 收尾；
`Ctrl+E` / `/toolargs` 展开到 200 行。

**助手正文按 markdown 渲染**：标题、列表、引用、代码块、行内代码、加粗 / 斜体 / 删除线、链接、分隔线、表格都会渲染，
**表格画成网格**（按显示列量宽对齐，列内折行；列数多到分不下时退回等宽代码块）。**用户消息与工具轨迹保持纯文本**，
图片与 HTML 原样显示源码。

**滚轮可用，代价是终端选择被应用截走**：滚轮要求应用捕获鼠标（默认开启），而捕获后终端的鼠标选择归应用。
想复制时按 `Ctrl+O`（或敲 `/mouse`）把鼠标交还终端：拖选 + `⌘C` 立刻可用，期间滚轮停用
（消息区滚动改用 `PageUp` / `PageDown` / `End`），复制完再按一下收回。整体不要鼠标捕获时用
`-Djellyfish.tui.mouseCapture=false`：代价是滚轮在多数终端下**根本到不了应用**。

**TUI 没有 stdout 契约**：它独占备用屏，`> answer.txt` 不适用，退出后也不回显会话内容。日志改写到文件
`<用户主目录>/.jellyfish/jellyfish-tui.log`（可用 `-Djellyfish.log.file=...` 改路径），绝不写 stderr。

**已知限制**：界面滚动到内容末尾时自动跟随，用户上翻后不再打扰，状态栏出现 `↓ N 行`（生成中则显示
`↓ 正在生成…`）。消息区只投影最近 500 条消息，更早的以 `⎿ N 条更早的消息已折叠` 占位。编辑器的
「光标到行尾」不可用（`End` 归消息区）；`\r\n` 输入会变成两个换行。

### 输入指令 `!` 与文件引用 `@`

输入框支持两种**由插件提供**的特殊语法（没有装对应插件时，它们只是一段普通文本）：

| 输入 | 行为 | 依赖插件 |
| --- | --- | --- |
| `!命令` | 手动执行一条 shell 命令。**不进模型**，执行完把「命令回显 + 输出」作为一条用户消息落进会话，因此下一次提问时模型看得到结果 | `jellyfish-plugin-shell` |
| `@路径` | 引用当前工作目录下的文件：敲 `@` 弹补全面板（目录在前、可逐层往下钻），接受后路径写进输入框 | `jellyfish-plugin-tools` |

两条口径：**`!` 依旧过权限与审批**（复用与模型调用完全相同的工具执行链路，只读命令静默执行、其余照旧弹审批框，
`Esc` 可中断）；**`@` 不内联文件内容**（引用只是把路径写进消息，读取由模型调用 `read_file` 完成，
因此大文件不会撑爆上下文）。

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

### 提问

`ask_user` 工具让模型把一个问题连同若干选项摆到你面前，你挑一个即可，不必自己敲答案：

```
╭ ❓ 需要你的选择 ────────────────────╮
│ 先做通道还是先做 UI？                │
│                                      │
│  ❯ 先做通道   内核与插件先落地       │
│    先做 UI                           │
│    ✎ 其它（自己输入）                │
│ ↑/↓ 选择 · Enter 确认 · Esc 放弃作答 │
╰──────────────────────────────────────╯
```

选项都不合适时选最后一项「其它（自己输入）」，按键就交给输入框：写下自己的答案再按 `Enter` 提交，
`Esc` 退回选项。提问浮层的优先级**低于审批、高于二级选择页与补全**（它背后也有一条阻塞的 `react` 线程）。

**它与审批的区别值得留意**：在这里选任何一项都不会放行任何工具，只是把答案告诉模型。因此 `Esc` 是
「放弃作答」而不是「中断回合」——模型收到「用户取消了这次提问」后会自己接着判断。等不到答复
（缺省 120 秒，见 `ask.timeoutSeconds`）同样如此，工具会回一条「用户没有回答」让模型继续往下走。

### 插件面板

插件可以往界面上放两类东西——状态栏尾部的一小段文本（例如待办插件的 `待办 2/5`），以及一块常驻面板（例如完整的待办清单）。
位置、宽度、行数全部由外壳决定，插件无权把消息区挤没。

面板占区域，而一块区域同一时刻只显示一个，因此两个插件抢同一位置时默认只显示 `order` 最小的那个，其余进入候选。
`/ui` 用两级选择页来切换（`↑`/`↓` 选择、`Enter` 确认、`Esc` 取消）：

```
/ui                        选区域（二级页）：每行标出该区域显示的是谁、还有几个候选
/ui right                  选插件（三级页）：该区域的候选插件 + on / off，当前那个标「（当前）」
/ui right jellyfish-plugin-todo   直接指定：由某个插件占用该区域
/ui right cycle            在该区域轮换到下一个候选
/ui right off              关掉该区域（/ui right on 恢复）
/ui list                   不弹页面，直接列出所有贡献：区域 | 插件 | 标题 | 是否可见 | 还有哪些候选 | 没生效的插件快捷键
```

选择页的「当前」标记告诉你现在看的是谁的面板；二级页里没有贡献的区域标 `无贡献`。
快捷键诊断（「我绑的键为什么没反应」）只在 `/ui list` 里。

界面窄于 80 列时左右侧栏自动隐藏；窄到放不下时面板会让位给消息区，而不是反过来。整体不要插件 UI 时用
`-Djellyfish.tui.pluginPanels=false`。

审批框、提问框、补全面板这类浮层出现时，**面板不会消失，只会变矮**：左右侧栏的高度本来就跟着消息区走，
`DOCK` / `TOP` 则在「总高扣除消息区下限与底部固定段」剩下的预算里分配行数（`DOCK` 优先，分不够时先矮 `TOP`；
矮到放不下就暂时不显示）。浮层关掉后一切自动恢复。

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
**插件会带来更多命令**（例如待办插件的 `/todo`、skills 插件的 `/skills`、shell 的 `/shell`），装了就出现在 `/help` 里。

**离线可用**：`/help` `/session` `/status` `/model` `/compact preview` 这些命令**不需要模型配置**，
可以拿来验证安装是否正常。

### 会话与压缩

长会话的每一轮都要把整段历史重新发给模型，token 花得越来越多。压缩多花**一次**调用把更早的对话压成一份摘要，
之后每次请求只带摘要 + 最近若干条原文：

```
/compact                 按默认档位压一次（保留条数取 react.compactKeepRecentMessages，缺省 20）
/compact preview         只回报「将压缩 X 条、保留 Y 条、丢弃 Z 条、摘要输入约 T token」，不发起压缩
```

**上下文用到 80% 时会自动压一次**（`react.autoCompactPercent`，写 `0` 关闭）；另外只要某一次请求已经触发了机械裁剪
（历史正在被静默丢弃），也会立刻压一次。自动压缩跑在当前那一轮模型调用的旁边，不拖慢对话，结果在下一轮生效。

三条最该知道的：**历史一条不删**（屏幕上的会话、恢复出来的会话、落盘的文件都还是完整历史，变的只是
「发给模型的那条链路从哪里开始」）；**可以反复压**（每次只压上次边界之后新增的那段，边界只向后移）；
**一次压完，装不下就丢最旧的**（被丢弃的那段既不在摘要里、也不会再发给模型，完成提示会写明条数）。

压缩依赖**插件提供策略**：没有启用 `jellyfish-plugin-compact`（或同类插件）时压缩整体不可用，`/compact` 会直接告诉你。

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
**子代理不继承主会话的模型**（用自己的 `model`，没配则落到全局默认，想在探索上省钱就写个便宜模型）。

屏幕上你能看到的：子代理在跑时工具轨迹出现 `⎿ task`，下面缩进两格跟着它的每一步工具调用（`  · read_file`）；
完成之后收成一行：

```
      ⎿ task · 子代理 scout · 3 轮 · 123456 tok · {"prompt": "…"}
```

它花掉的 token **计入父会话**（`/usage` 看得到），但子代理的会话不留痕（不落盘、不进 `/session` 列表）。
递归有两道上限：`subAgent.maxDepth`（一条链多深）与 `subAgent.maxSpawnsPerTurn`（一层扇出多少）。

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

**去哪里找插件**：官方插件在 **`Jellyfish-Plugins`** 仓库（<https://github.com/zcd0831/Jellyfish-Plugins>）——
文件工具、shell、会话持久化、待办、压缩策略、skills、MCP、编排（workflow）、plan 模式，以及 Python / Node 脚本插件运行时。
那边也有**插件开发教程**，教你自己写一个。

插件能做什么由内核的扩展点与权限模型决定：工具、命令、提示词贡献、权限拦截、会话持久化 / 恢复、压缩策略、UI 贡献、
输入指令走同步扩展点；轮次与会话等通知走异步事件通道。**插件拿不到会话与工作目录**，也**不能自称某个写操作是只读的**
（`ToolDescriptor` 里已没有该字段；按模式收窄的名单归插件自己的配置段，例如官方 plan 插件的
`plugins.configurations.jellyfish-plugin-plan.readOnlyTools`）。

## 配置

配置分**五份文件**，每份对应一个配置类。**文件位置与插件扫描目录只在 `config.json` 里声明**，其余文件走
「全局级 + 项目级」双源、项目级优先：

| 文件 | 配置类 | 内容 | 寻找位置（缺省） |
| --- | --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 + 插件扫描目录 | classpath 根（本仓库 `jellyfish-cli/src/main/resources/config.json`），**不参与双源**，也不热更新 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` | `~/.jellyfish/` → `./.jellyfish/` |
| `agents.json` | `AgentSettings` | 用户自定义 agent | 同上 |
| `jellyfish.json` | `JellyfishSettings` | 插件名单与配置段、`react` / `permission` / `subAgent` | 同上 |
| `default-agent.json` | `AgentDefinition` | 内置系统默认 agent | classpath 根，**不走双源、用户改不了** |

### `config.json`

```json
{
  "processName": "Jellyfish",
  "model":     { "globalPath": "~/.jellyfish/models.json",    "projectPath": "./.jellyfish/models.json" },
  "agent":     { "globalPath": "~/.jellyfish/agents.json",    "projectPath": "./.jellyfish/agents.json" },
  "jellyfish": { "globalPath": "~/.jellyfish/jellyfish.json", "projectPath": "./.jellyfish/jellyfish.json" },
  "plugins":   { "roots": ["~/.jellyfish/plugins/"] }
}
```

仓库里的 `config.json` 就是这份：**全局级约定目录 `~/.jellyfish/`、项目级约定目录 `<工作目录>/.jellyfish/`**。
要换位置只改这里。`plugins.roots` 的顺序即扫描顺序，相对路径相对**进程工作目录**解析，行首 `~` 展开为用户主目录，
留空则回退默认值 `plugins`。

> **改完不用重启**：`models.json` / `agents.json` / `jellyfish.json` 的内容改动敲 `/reload` 即生效
> （重读配置、重建模型 / agent 索引，并**只重启配置段变了的插件**，同步等结果，毫秒级）。
> **`config.json` 不参与热更新**，新增 / 删除插件 jar 也仍需重启进程。

### `models.json`

```json
{
  "defaultProvider": "openai",
  "defaultModel": "gpt-4o",
  "providers": {
    "openai": {
      "type": "openai",
      "apiKey": "${OPENAI_API_KEY}",
      "cache": {
        "promptCacheKey": false,
        "keepAliveSeconds": 0
      },
      "models": [{ "id": "gpt-4o", "name": "gpt-4o", "contextLength": 128000, "maxOutputTokens": 4096 }]
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `defaultProvider` / `defaultModel` | 全局默认模型。模型解析顺序是**会话显式选定 → `agent.model` → 这里** |
| `providers.<name>.type` | 用哪套 provider 实现（`openai` / `claude` / `deepseek` / `gemini` / `minimax` 及其别名） |
| `providers.<name>.apiKey` | 建议写成 `${ENV_VAR}`，由环境变量注入 |
| `providers.<name>.models[]` | `id`（`/model` 里 `provider/model` 的后半段）/ `name` / `contextLength` / `maxOutputTokens` |

**`type` 可以由插件提供**：插件能为一个内核不认识的新类型提供传输实现（本地 llama.cpp、企业自建网关、私有协议、
自定义鉴权），用户只需在这里写一个指向该类型的 provider。两条硬规则：

- **内核自带的 `type` 不能被插件覆盖**。这不是优先级问题：传输请求里带的是已解析好的 `apiKey`，
  能顶替 `openai` 的插件等于把密钥转发出去。
- **`models` 可以由插件动态发现**：插件接管的 provider 会被问一次「现在有哪些模型」，非空时**整体替换**这里写的
  `models`。发现结果**不落盘**——本文件始终是模型的唯一持久事实，删掉插件后配置里那份依旧有效。

传一个不存在的 `type` 时，报错会列出内核认识的类型与可执行的下一步。

#### `providers.<name>.cache`（可选，两项缺省都关）

两个旋钮都只影响**命中率**、不影响正确性，且都与具体厂商的缓存实现绑死，因此挂在 provider 上。

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `promptCacheKey` | `false` | 把会话标识作为缓存路由键下发（OpenAI 系为 `prompt_cache_key`），让同一会话的请求尽量落到持有相同前缀的机器上。缺省关闭：老模型 / 老端点收到不认识的字段可能直接报错 |
| `keepAliveSeconds` | `0` | 空闲时每隔这么多秒重发一次「复用同一前缀、且不要求生成内容」的请求，把缓存 TTL 续上。`0` 关闭。**它是要花钱的**（上限 1 小时，超出回退到关闭；每个空闲期最多续 3 次） |

**这一层只是基线**：厂商协议字段与缓存策略还能由插件经 `RequestTuningRequest` 逐请求调整——「目标端点认哪些字段」
是厂商知识，不是内核知识。**Anthropic 必须显式用 `cache_control` 标出断点，不标就一个字节都不缓存**，
因此 Claude 客户端**默认标注两个断点**；想退回去由插件接管，用 `RequestTuningRequest` 把断点数声明为 `0`。
DeepSeek 默认按前缀缓存、且磁盘缓存要几小时到几天才清理，按分钟级续它没有意义，`keepAliveSeconds` 保持 `0` 即可。

`keepAliveSeconds` 花掉的 token 照常进 `/usage`，也进 `llm.*` 指标。**调参提示**：按厂商文档的 TTL 开一个略小于它的
间隔试一段（OpenAI 内存缓存约 5–10 分钟，`240` 是个合理起点），看 `/usage` 的命中率有没有抬上去；
**没抬上去就关掉它**。

### `agents.json`

系统提示词**不写在这里**，而是同目录下的 `{agentId}.md`：

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

| 字段 | 说明 |
| --- | --- |
| `description` | agent 的一句话说明 |
| `permissions` | 工具权限三段，见下 |
| `delegatable`（缺省 `false`） | 这个 agent 能不能被 `task` 工具当作委派目标。不配就不出现在模型看到的可用类型清单里 |
| `model` | 这个 agent 的偏好模型，写法与 `/model` 参数一致（`openai/gpt-4o-mini` 或裸 `gpt-4o-mini`） |

#### 工具权限

三段工具名的语义是：**字段缺失 = 不限制**，显式写 `[]` = 该方向上一个都不放行（`allowedTools: []` 就是全拦）。
因此想表达「只显式拒绝两个工具、其余不限制」就**不要**写 `"allowedTools": []`，直接省略该字段。

优先级是 `deniedTools` > `askTools` > `allowedTools`。`askTools` 里的工具每次调用都要人工审批，三种外壳行为不同：

| 外壳 | 行为 |
| --- | --- |
| `-tui` | 弹出审批选择框（`↑`/`↓` 选，`Enter` 确认，`Esc` 拒绝并中断回合），批准才执行 |
| `-server` | 把待审批项推进 SSE 流（`approval_required`），由 `POST /approvals/{requestId}` 裁决 |
| `-cli` | 没有审批界面（也没有审批者），因此**一律按拒绝处理**——绝不静默放行 |

审批框等不到答复（缺省 120 秒，见 `permission.approvalTimeoutSeconds`）同样按拒绝处理。
`allowedTools` 还有一层作用：它同时决定**子代理能看到哪些工具**（只随委派生效，主会话不受影响）。

#### 提示词文件

每个 agent（包括内置的系统 agent）的系统提示词来自**与配置文件同目录**的 `{agentId}.md`，内容就是它的系统提示词，
可以是多段长文。`agentId` 同时是文件名，因此**不能含路径分隔符或 `..`**（含这类字符的条目会被整条丢弃并告警）。
文件不存在不阻断启动，只有 `/agent` 切过去时才提示「该 agent 没有系统提示词」。

### `default-agent.json`

内置，随 `jellyfish-infra` 发布在 classpath 根，配合同目录的 `jellyfish.md` 使用：

```json
{
  "agentId": "jellyfish",
  "description": "系统默认 agent",
  "permissions": {}
}
```

**每次启动、每个新建会话都绑这个内置 agent**；想用自定义 agent 必须手动 `/agent <agentId>` 切换。
用户 `agents.json` 里写同名 `jellyfish` 会被忽略并告警（内置定义不可被覆盖）。

### `jellyfish.json`

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-plan": { "readOnlyTools": ["read_file", "list_dir", "grep_files"] },
      "jellyfish-plugin-todo": { "todoDir": "~/.jellyfish/todos" }
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
    },
    "cache": {
      "agingPercent": 70
    }
  },
  "permission": {
    "approvalTimeoutSeconds": 120
  },
  "ask": {
    "timeoutSeconds": 120
  },
  "subAgent": {
    "enabled": true,
    "maxDepth": 1,
    "maxSpawnsPerTurn": 3,
    "maxRounds": 8,
    "maxConcurrentRuns": 3,
    "runTimeoutMillis": 300000,
    "runTokenBudget": 500000,
    "treeTokenBudget": 1500000,
    "archiveKeepFiles": 200,
    "archiveMaxBytes": 104857600
  }
}
```

#### `plugins`

| 字段 | 说明 |
| --- | --- |
| `plugins.enabled` | 启用名单。**「未声明」与「声明为空」是两回事**：不声明表示「不额外限定」（全部加载），显式写 `[]` 表示「一个都不启用」 |
| `plugins.disabled` | 禁用名单。不声明与写 `[]` 等价，都不禁用任何插件。同一 pluginId 同时出现在两份名单里时按**禁用**处理，并发一条配置告警 |
| `plugins.configurations.<pluginId>` | 单个插件的配置段，逐插件的键值见插件自己的文档（`Jellyfish-Plugins` 仓库） |

**跨语言插件（`jellyfish-plugin-python` / `jellyfish-plugin-node`）多一层 `scripts`**：
`plugins.configurations.<桥接插件>.scripts.<脚本 id>` 是该脚本自己的配置段（脚本目录名就是脚本 id），
脚本用 `ctx.configuration`（Python 还可用模块级 `configuration()`）读到它。桥接层**只按脚本 id 切片转发**、
不解释里面的键，因此密钥、baseUrl、超时随便写；**字符串值里的 `${ENV}` 照常插值**，与 Java 插件是同一条密钥通道。
写错脚本 id（拼写 / 大小写）会在启动时告警；`scripts` 段写成非对象会当场报错。
**脚本进程的环境变量是严格白名单**（只透传解释器运行与依赖解析必需的那几个），因此 API key **不能**靠环境变量
直接传给脚本，只能走这一段。

脚本声明的**周期任务**，其间隔可以在 `scripts.<脚本 id>.schedules.<任务名>.intervalSeconds` 覆写。
任务名与间隔来自脚本自己的 `manifest.json`，这里的值**只覆盖间隔**、不能凭空新增或删除任务——
清单才是「有哪些任务」的唯一真源。只认整数秒且不得小于 1，写错会回落声明值并发一条配置告警。

模式类授权（例如「只跑只读工具」）由插件提供：官方 `jellyfish-plugin-plan` 的白名单写在
`plugins.configurations.jellyfish-plugin-plan.readOnlyTools`。**不写就等于开启时全部不可用**——
白名单语义下「用户没表态」与「用户不准」是同一件事。工具自身**无法自称只读**（描述符里已无该字段）。

#### `react`（ReAct 循环）

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `maxRounds` | `16` | 单回合最大轮数 |
| `contextReserveTokens` | `1024` | 上下文预算里为系统提示词 / 插件注入的上下文预留的 token |
| `maxToolOutputChars` | `20000` | 单个工具输出回灌模型前的截断长度，也是**硬上限**（工具失控时由它保命） |
| `compactKeepRecentMessages` | `20` | 压缩默认保留的最近消息条数（写 `0` 即「不保留原文」） |
| `compactMaxSummaryChars` | `4000` | 摘要长度上限（提示模型别写太长，真超了按码点本地截断并留标记） |
| `autoCompactPercent` | `80` | 上下文用到多少百分比就自动压缩（写 `0` 关闭自动压缩，只留手动 `/compact`） |

非法值（非正数）回退到缺省值。

#### `react.toolOutput`（工具结果落盘）

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `dir` | `~/.jellyfish/tool-outputs` | 完整内容的落盘根目录（运行产物写在这里而不是项目目录） |
| `keepFiles` | `200` | 每个会话在该目录下的文件数上限，超了从最旧开始删（写 `0` 关闭清理） |
| `maxBytes` | `52428800`（50 MiB） | 每个会话在该目录下的字节数上限，超了从最旧开始删（写 `0` 关闭清理） |
| `keepRecentMessages` | `20` | 组装请求时最近多少条消息里的工具结果保留完整内容（写 `0` 同时关掉裁剪与老化） |
| `spillMaxBytes` | `33554432`（32 MiB） | 单个工具结果的落盘上限，运行期钳制为不超过 `maxBytes`；触及上限时后续内容不再保存、信封会带 `_partial` 说明它不完整 |

#### `react.cache`（工具结果老化）

`agingPercent` 管的是「工具结果什么时候换成 stub」，它直接决定提示词缓存的命中率——厂商的缓存是**前缀匹配**，
改掉历史上的一个字节，后面的整段都会从约 `0.1×` 的命中价变回 `1×` 全价。

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `agingPercent` | `70` | `0` = 按「距尾部多少条消息」老化，边界每轮重算（复原升级前行为的逃生门）；`> 0` = 只在上下文用量达到该百分比时老化，且**一个压缩周期内只推进一次** |

缺省落在 `70` 是因为它必须**略低于 `autoCompactPercent`（80）**，相差约 10 个点——低了才会真正泄压，
高了就让压缩先发生、老化永远轮不到。**改了 `autoCompactPercent` 就应一并重看这一项。**
想彻底关掉老化：把 `react.toolOutput.keepRecentMessages` 写 `0`。

#### `permission`

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `approvalTimeoutSeconds` | `120` | `askTools` 里的工具在 TUI 上弹审批框后最多等这么久，超时按拒绝处理 |

它有缺省值而不允许「永不超时」：无审批者 / 超时 / 中断一律拒绝（fail-closed）。

#### `ask`

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `timeoutSeconds` | `120` | `ask_user` 弹出的提问框最多等这么久，超时后工具回一条「用户没有回答」让模型继续 |

它与 `permission.approvalTimeoutSeconds` **刻意分开**：审批是「能不能执行」卡在半路等人放行（等不到按拒绝处理），
提问是「问你一件事」（等不到就让模型照自己的判断走）。两者的合理等待长度不同，共用一个键会让改审批的行为顺带改掉提问。
同样有缺省值而不允许「永不超时」——提问发生在 `react` 线程上并被同步等待，而那个池只有 8 条线程（= 并发顶层回合上限）。

#### `subAgent`（子代理委派）

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 全局开关。**关掉后 `task` 工具直接不再注册**，模型看不到它；`/reload` 即可生效、不用重启 |
| `maxDepth` | `1` | 允许的最大委派层数（主会话 → 子代理 → 孙代理），写 `0` 表示禁止委派 |
| `maxSpawnsPerTurn` | `3` | 单个顶层回合内允许派生的子代理总数 |
| `maxRounds` | `8` | 子代理自己那个回合的最大轮数，**不跟随** `react.maxRounds` |
| `maxConcurrentRuns` | `3` | **全局同时运行的子代理 run 数上限**（不含父回合）。超过的 run 排队；run 跑在独立的 `agent-run` 线程池上，父回合等待子 run 时会让出并发许可 |
| `runTimeoutMillis` | `300000` | 单个 run 的墙钟上限（毫秒）。到点取消该 run 并记为「截断」 |
| `runTokenBudget` | `500000` | 单个 run 的累计 token 上限；写 `0` 表示不限制 |
| `treeTokenBudget` | `1500000` | 一棵 run 树的累计 token 上限；写 `0` 表示不限制 |
| `archiveKeepFiles` | `200` | run 归档目录（`<toolOutput.dir>/subagent-runs`）最多保留的文件数；写 `0` 表示不清理 |
| `archiveMaxBytes` | `104857600` | run 归档目录最多占用的字节数（100 MiB）；写 `0` 表示不清理 |

`maxDepth` 挡「一条链多深」，`maxSpawnsPerTurn` 挡「一层扇出多少」，`maxConcurrentRuns` 挡「全局同时在跑多少」，
三者正交；三个 token / 时间上限挡的是「跑飞了也停得下来」。除 `enabled` 外非法值一律回退缺省值。
归档配额与工具输出**分开算**（归档按 run 产生、含整份子会话 transcript，个体远大于一份工具结果），
两者分居不同目录、各有各的上限，清理互不掏空对方的窗口。

### 双源合并与插值

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），
  JSON 的 key 不替换。`{agentId}.md` 提示词是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录；项目级路径相对**进程工作目录**解析，不是相对 jar 位置。
  文件不存在视为「该源未配置」，静默跳过。`config.json` 的 `plugins.roots` 语义一致。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级，agent 的提示词 md 也随来源一起覆盖；
  `defaultProvider` / `defaultModel` 取项目级非空值，否则回退全局级；`react` / `permission` / `subAgent` 段项目级
  **整对象**覆盖全局级；启用 / 禁用名单项目级**已声明则整体替换**（写 `[]` 即清空该名单，不做并集）。
  `plugins.roots` 只在 `config.json` 一处，不参与双源合并。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。

### 常见配置任务

1. **让这台机器上的所有项目都用同一套模型配置**：把同一份文件放到 `~/.jellyfish/`（项目级同名条目会整对象覆盖全局级）。
2. **只给某个项目换模型**：在项目根建 `.jellyfish/models.json`，只写要覆盖的 `provider`。
3. **关掉自动压缩**：`react.autoCompactPercent: 0`，改用 `/compact preview` 先看会压多少再手动压。
4. **临时禁用某个插件**：`plugins.disabled: ["jellyfish-plugin-todo"]`，然后 `/reload`。
5. **关掉子代理委派**：`subAgent.enabled: false`，`/reload` 后 `task` 工具不再注册。

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
| `GET` | `/asks` · `POST /asks/{requestId}` | 待答提问与作答 |
| `GET` | `/health` | 健康报告（UP / WARN / DOWN） |

几条接入方必须知道的：**同会话同时只允许一个回合**（第二个请求 `409`，要打断用 `/cancel` 或断开 SSE）；
**人工审批走 HTTP**（流里推 `approval_required`，拿 `requestId` 调 `POST /approvals/{id}`；头槽位**每会话一个**，
会话之间互不排队，同一会话内是 FIFO 队列、上限 8 条，超出直接按拒绝处理）；**向用户提问同理**（流里推
`ask_required`，拿 `requestId` 调 `POST /asks/{id}`，请求体给 `{"optionId":"…"}` 或 `{"text":"…"}`；两种都不给回 400，
因为「用户自己填的答案」与「客户端写错了请求」必须能分开）；**`tool_output` 是可丢的过程信息**，
权威结果是 `tool_done` 的 `output`；**错误体统一为** `{"error":"CODE","message":"…"}`；
**`POST /chat` 不执行命令、不解析输入指令**，要与命令域打交道走 `/commands`。

SSE 事件名、鉴权细节与会话语义见 [docs/constraints.md](docs/constraints.md)。

## 常见问题

**没配模型会怎样？**
命令照常能用（`/help` `/session` `/status` `/model` `/compact preview` 都不需要模型），一发对话就会提示没有可用模型。
先按「快速开始」建一份 `models.json`。

**裸跑 `jellyfish`（不带任何参数）会干什么？**
等价于 `-tui`，因此同样需要可交互终端，在管道 / CI 里一样退 `3`。想跑单次问答请显式写 `-cli -p "..."`：
兜底只认「一个参数都不带」，带了参数却没给模式（如 `jellyfish --verbose`）仍判用法错误退 `2`。

**`-tui` 启动就退出 3？**
它在管道、CI、被重定向的 shell 或 IDE 的非真终端控制台里都会这样（这是有意的：否则 TamboUI 会永久挂住）。
在真实终端里运行，或按「在 IDEA 里调试」一节用远程调试挂接。确认终端可用却被拦下时用
`-Djellyfish.tui.skipTerminalCheck=true` 跳过检查。

**`-cli` 下工具调用总被拒绝？**
`-cli` 没有审批界面，`askTools` 里的工具**一律按拒绝处理**（绝不静默放行）。要在无人值守下跑，就别把该工具放进
`askTools`；要人工确认就用 `-tui` 或 `-server`。shell 这类插件另有白名单与可信表的口径，见插件仓库文档。

**`-cli` 下 `ask_user` 会怎样？**
不会失败、也不会挂住：`-cli` 不挂答复者，工具立刻返回一条「当前外壳没有交互界面，无法把提问送达用户」，
模型据此照自己的判断继续。真要人回答就用 `-tui` 或 `-server`。

**模型总是问它自己能查到的问题？**
`ask_user` 只是把问题摆到你面前，**选任何一项都不放行任何工具**。它的适用场景（关键决策、需求不明确、
不可逆操作前的确认）写在工具描述里，但那只是引导——模型偶尔仍会拿它当「确认一下再动手」的快捷键。
不想让它问就用 agent 的 `deniedTools` 把 `ask_user` 拦掉：那是硬结论，不是建议。

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

## 更多文档

使用中的规范与约束（跨模块约定、扩展层与插件运行时、会话与配置、权限与审批、ReAct 与压缩、工具与命令域、
三种外壳、Server 接口契约）集中在 **[docs/constraints.md](docs/constraints.md)** 一份文档里。

## License

[MIT](LICENSE)
