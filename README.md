# Jellyfish

A lightweight AI agent tool developed in Java 1.8, which supports capability extension through plugins.

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
  "model":     { "globalPath": "/etc/jellyfish/models.json",   "projectPath": "./models.json" },
  "agent":     { "globalPath": "/etc/jellyfish/agents.json",   "projectPath": "./agents.json" },
  "jellyfish": { "globalPath": "/etc/jellyfish/jellyfish.json", "projectPath": "./jellyfish.json" }
}
```

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
  }
}
```

约定：

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），JSON 的 key 不替换。`systemPrompt` 是**原文**，不做模板插值。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级；`defaultProvider` / `defaultModel` / `defaultAgent` 取项目级非空值，否则回退全局级；插件根目录与启用 / 禁用名单项目级非空则**整体替换**（不做并集）。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。
