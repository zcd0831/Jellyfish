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
| `-tui` | 交互式终端界面（TamboUI） | `jellyfish -tui` | 尚未实现 |
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
| `5` | 模式尚未实现（当前的 `-tui` / `-server`） |
| `6` | 回合未收敛：达到最大轮次仍未给出最终回复 |

单次模式里输入以 `/` 开头就走命令域（`/help` `/model` `/agent` `/new` …），否则走一次 LLM 对话；
`/help` `/session` `/status` `/todo` `/model` 这些命令不需要模型配置，可以离线验证安装是否正常。

## 配置

配置分四份文件，每份对应一个配置类。**文件位置只在 `config.json` 里声明**，其余文件都走「全局级 + 项目级」双源，项目级优先。

| 文件 | 配置类 | 内容 |
| --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` |
| `agents.json` | `AgentSettings` | `defaultAgent` / `agents` |
| `jellyfish.json` | `JellyfishSettings` | `plugins` 等运行期设置 |

`config.json` 放在 classpath 根（本仓库为 `jellyfish-cli/src/main/resources/config.json`）：

```json
{
  "processName": "Jellyfish",
  "model":     { "globalPath": "~/jellyfish/models.json",    "projectPath": "./jellyfish/models.json" },
  "agent":     { "globalPath": "~/jellyfish/agents.json",    "projectPath": "./jellyfish/agents.json" },
  "jellyfish": { "globalPath": "~/jellyfish/jellyfish.json", "projectPath": "./jellyfish/jellyfish.json" }
}
```

仓库里的 `config.json` 就是这份：**全局级约定目录 `~/jellyfish/`、项目级约定目录 `<工作目录>/jellyfish/`**，四类配置的文件名固定。要换位置只改 `config.json`。

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
        "deniedTools": ["bash"],
        "askTools": ["write_file"],
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
    "roots": ["plugins"],
    "enabled": [],
    "disabled": [],
    "configurations": {
      "jellyfish-plugin-python": { "readOnlyTools": ["read_file", "list_dir"] }
    }
  },
  "react": {
    "maxRounds": 16,
    "contextReserveTokens": 1024,
    "maxToolOutputChars": 20000
  }
}
```

`react` 段控制 ReAct 循环：`maxRounds` 是单回合最大轮数；`contextReserveTokens` 是上下文预算里为系统提示词 / 待办注入预留的 token；`maxToolOutputChars` 是单个工具输出回灌模型前的截断长度。缺省值即为上表；非法值（非正数）回退到缺省值。

约定：

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），JSON 的 key 不替换。`systemPrompt` 是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录（`~other/...` 这种指定其他用户的形式不展开）；两条路径都支持。项目级路径相对**进程工作目录**解析，不是相对 jar 位置。文件不存在视为「该源未配置」，静默跳过（这是 `globalPath` 与 `projectPath` 可以同时配上、缺哪份就少哪份的原因）。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级；`defaultProvider` / `defaultModel` / `defaultAgent` 取项目级非空值，否则回退全局级；`react` 段项目级整对象覆盖全局级；插件根目录与启用 / 禁用名单项目级非空则**整体替换**（不做并集）。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。

### 想真跑一轮对话

`config.json` 默认从 `~/jellyfish/`（全局级）与 `./jellyfish/`（项目级）读取三份配置。开箱能跑命令（如 `/help`），但没有模型配置时一发对话就会提示没有可用模型。要真跑：

1. 在项目根目录建 `jellyfish/models.json`（格式见上方示例），apiKey 用环境变量注入：`"apiKey": "${OPENAI_API_KEY}"`；
2. 按需建 `jellyfish/agents.json` 与 `jellyfish/jellyfish.json`；
3. 想让配置对**这台机器上的所有项目**生效，把同样的文件放到 `~/jellyfish/` 即可（项目级同名条目会整对象覆盖全局级）。

两个目录里的文件名与上方四类配置一一对应，不要改成别的名字。
