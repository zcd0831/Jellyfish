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
| `-server` | HTTP 服务（Undertow），对外暴露能力接口 | `jellyfish -server 9096` | 尚未实现 |

### 参数

| 参数 | 说明 |
| --- | --- |
| `-cli` / `-tui` / `-server` | 模式旗标，三选一且必填；`-server` 可带位置端口 |
| `-p, --print <输入>` | 单次模式的输入；缺省时从 stdin 读到 EOF（管道可用） |
| `--session <会话>` | 切换到已有会话（会话不持久化，单次模式下实际不可用） |
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
| `5` | 模式尚未实现（当前的 `-server`） |
| `3`（TUI） | TUI 需要可交互终端而当前没有（stdin 或 stdout 被重定向也算） |
| `6` | 回合未收敛：达到最大轮次仍未给出最终回复 |

单次模式里输入以 `/` 开头就走命令域（`/help` `/model` `/agent` `/new` …），否则走一次 LLM 对话；
`/help` `/session` `/status` `/model` 这些命令不需要模型配置，可以离线验证安装是否正常（`/todo` 由待办插件提供）。

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
 agent · provider/模型 · 权限模式 · token 用量
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

**滚轮不可用**是刻意的：滚轮事件要求应用捕获鼠标，而捕获后终端的鼠标选择会被应用截走
（复制屏幕文本需按住修饰键）。消息区滚动请用 `PageUp` / `PageDown` / `End`。

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

## 配置

配置分四份用户可改的文件，每份对应一个配置类。**文件位置与插件扫描目录只在 `config.json` 里声明**，其余文件都走「全局级 + 项目级」双源，项目级优先。另有一份随构件发布的 `default-agent.json`（内置系统默认 agent），它不走双源、用户改不了。

| 文件 | 配置类 | 内容 |
| --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 + 插件扫描目录 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` |
| `agents.json` | `AgentSettings` | `agents`（用户自定义 agent） |
| `jellyfish.json` | `JellyfishSettings` | `plugins`（名单与插件配置段）等运行期设置 |
| `default-agent.json` | `AgentDefinition` | 内置系统默认 agent（classpath 根，不走双源） |

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
- `-cli` / `-server` 没有审批界面（也没有审批者），因此**一律按拒绝处理**——绝不静默放行；
- 审批框等不到答复（缺省 120 秒，见 `permission.approvalTimeoutSeconds`）同样按拒绝处理。

上例的 `coder` 还需要一份 `coder.md`（与 `agents.json` 同目录），内容就是它的系统提示词，可以是多段长文。

`default-agent.json`（内置，位于 `jellyfish-cli/src/main/resources/`，与 `config.json` 同目录）：

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
    "maxToolOutputChars": 20000
  },
  "permission": {
    "approvalTimeoutSeconds": 120
  }
}
```

`react` 段控制 ReAct 循环：`maxRounds` 是单回合最大轮数；`contextReserveTokens` 是上下文预算里为系统提示词 / 插件注入的上下文预留的 token；`maxToolOutputChars` 是单个工具输出回灌模型前的截断长度。缺省值即为上表；非法值（非正数）回退到缺省值。

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
| `jellyfish-plugin-project` | `jellyfish-project` | 项目约定：探测工作目录下的 `AGENTS.md`，在 system prompt 里给出**路径指引**（不注入全文） |

`jellyfish-tools` 的五个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit` | 按行分片读取，命中 `limit` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类 |
| `grep_files` | `pattern`、`path`、`max_results` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

其中 `read_file`、`list_dir`、`grep_files` 在描述符里声明为**只读**，PLAN 模式下开箱可用；`write_file` 与 `edit_file` 会改动工作目录，PLAN 模式下会被拒绝。

打包与安装（扫描目录由 `config.json` 的 `plugins.roots` 决定，默认是**工作目录**下的 `plugins/`，该目录不入版本库）：

```bash
mvn -q package -DskipTests
mkdir -p plugins
cp jellyfish-plugins/jellyfish-plugin-tools/target/jellyfish-plugin-tools-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-session-file/target/jellyfish-plugin-session-file-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-todo/target/jellyfish-plugin-todo-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-project/target/jellyfish-plugin-project-*.jar plugins/
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
- `jellyfish-project` **没有配置项**：约定文件名固定为 `AGENTS.md`，查找基准固定为进程工作目录。

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

`AGENTS.md` 是仓库里的项目约定（构建命令、编码规范、提交格式、模块边界）。这个插件让模型知道**当前工作目录里有这份文件**：

- **只给路径，不给正文**。注入的是一小段 `[项目约定]` 指引（约 6 行），内容是「工作目录下有 `AGENTS.md`，动手前先读它」。
  原因有两个：约定文件可能有几十上百 KB（本仓库这份就是 67KB），全文注入会每一轮都付这份 token；
  而仓库内容按「工具结果」的身份进入上下文是**数据**，塞进 system prompt 就变成了指令。
- **只查进程工作目录**，不向上查找父目录、也不查用户主目录：与文件工具的相对路径基准保持一致。
- **文件名不可配**，固定 `AGENTS.md`：这已是各家编码 agent 共同的约定，做成配置项只会多一个会填错的旋钮。
- **空文件不算命中**（指向它只会白费一次工具调用）；文件不存在时插件完全不注入，system prompt 里连空标题都不会出现。
- 因为走的是插件而不是内置提示词，**对所有 agent 生效**——用 `/agent` 换成自定义 agent 也照常。

代价是模型确实会去读那个文件（这是它遵守约定的前提），读进来的内容占多少上下文在 `/status` 里看得见，必要时 `/compact`。
