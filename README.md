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
| `--show-thinking` | 把模型的思考过程打到 stderr |
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
| `Esc` | 中断当前回合（输入框内容保留） |
| `PageUp` / `PageDown` | 消息区翻页 |
| `End` | 跳到底部并恢复跟随 |
| `/exit` | 退出（由外壳处理，不在 `/help` 列表里） |
| `/ui` | 查看与切换插件的界面贡献（同样由外壳处理，不在 `/help` 列表里） |

**为什么是 `Ctrl+S` 发送而不是 `Enter` 发送**：终端在 raw 模式下，`Shift+Enter`、`Alt+Enter`、
CSI-u 等所有「带修饰的 Enter」编码都无法被底层框架区分（一律解码成无修饰的 `Enter`），
`Enter` 与 `\n` 也完全同形。因此「`Enter` 发送 + 修饰键换行」在任何终端上都不可实现。
反转之后 `Enter` 稳定换行，发送交给一个可稳定识别的组合键。

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

配置分四份文件，每份对应一个配置类。**文件位置与插件扫描目录只在 `config.json` 里声明**，其余文件都走「全局级 + 项目级」双源，项目级优先。

| 文件 | 配置类 | 内容 |
| --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 + 插件扫描目录 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` |
| `agents.json` | `AgentSettings` | `defaultAgent` / `agents` |
| `jellyfish.json` | `JellyfishSettings` | `plugins`（名单与插件配置段）等运行期设置 |

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

`agents.json`：

```json
{
  "defaultAgent": "coder",
  "agents": {
    "coder": {
      "description": "通用编码助手",
      "systemPrompt": "You are Jellyfish, a coding agent.",
      "permissions": {
        "deniedTools": ["write_file", "edit_file"],
        "askTools": [],
        "allowedTools": []
      }
    }
  }
}
```

`jellyfish.json`：

```json
{
  "plugins": {
    "enabled": [],
    "disabled": [],
    "configurations": {
      "jellyfish-tools": { "readOnlyTools": ["read_file", "list_dir"] }
    }
  },
  "react": {
    "maxRounds": 16,
    "contextReserveTokens": 1024,
    "maxToolOutputChars": 20000
  }
}
```

`react` 段控制 ReAct 循环：`maxRounds` 是单回合最大轮数；`contextReserveTokens` 是上下文预算里为系统提示词 / 插件注入的上下文预留的 token；`maxToolOutputChars` 是单个工具输出回灌模型前的截断长度。缺省值即为上表；非法值（非正数）回退到缺省值。

约定：

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），JSON 的 key 不替换。`systemPrompt` 是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录（`~other/...` 这种指定其他用户的形式不展开）；两条路径都支持。项目级路径相对**进程工作目录**解析，不是相对 jar 位置。文件不存在视为「该源未配置」，静默跳过（这是 `globalPath` 与 `projectPath` 可以同时配上、缺哪份就少哪份的原因）。`config.json` 的 `plugins.roots` 同样支持 `~` 与相对路径，语义一致。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级；`defaultProvider` / `defaultModel` / `defaultAgent` 取项目级非空值，否则回退全局级；`react` 段项目级整对象覆盖全局级；启用 / 禁用名单项目级非空则**整体替换**（不做并集）。`plugins.roots` 只在 `config.json` 一处，不参与双源合并。
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

`jellyfish-tools` 的五个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit` | 按行分片读取，命中 `limit` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类 |
| `grep_files` | `pattern`、`path`、`max_results` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

打包与安装（扫描目录由 `config.json` 的 `plugins.roots` 决定，默认是**工作目录**下的 `plugins/`，该目录不入版本库）：

```bash
mvn -q package -DskipTests
mkdir -p plugins
cp jellyfish-plugins/jellyfish-plugin-tools/target/jellyfish-plugin-tools-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-session-file/target/jellyfish-plugin-session-file-*.jar plugins/
cp jellyfish-plugins/jellyfish-plugin-todo/target/jellyfish-plugin-todo-*.jar plugins/
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

- `readOnlyTools` 是**跨插件的约定键**（`jellyfish.json` 里位于插件配置段下）：PLAN 模式下只有列在这里的工具能执行。没列的工具在 PLAN 模式一律拒绝。
- `sessionDir`（默认 `~/jellyfish/sessions`）：会话文件目录。会话是跨项目的运行态数据，因此默认放全局级目录。
- `gitEnabled`（默认 `true`）：首次落盘时在 `sessionDir` 里 `git init`，此后**每次内容变化的落盘留一次提交**（内容没变则不写文件、也不提交）。机器上没有 git 时只告警，文件照常落盘。
- `todoDir`（默认 `~/jellyfish/todos`）：待办文件目录，一个会话一个 JSON 文件，空表会删掉文件。

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

`todo_write` 是写操作，PLAN 模式下默认被权限拒绝；上面配置里的 `readOnlyTools: ["todo_write"]` 就是「计划模式下也允许维护计划」的声明，不需要可以去掉。

会话恢复：启动时内核向所有注册了恢复处理器的插件要回会话，因此上次退出前的会话在下次启动时立即可见（`/session` 会列出来）。
