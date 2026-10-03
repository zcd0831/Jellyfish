# 配置参考

想尽快跑起来，先看 [README 的快速开始](../README.md#快速开始)；本文给全量字段、优先级与合并规则。
设计缘由见 [architecture.md](architecture.md)。

## 五份文件

配置分四份**用户可改**的文件，每份对应一个配置类。**文件位置与插件扫描目录只在 `config.json` 里声明**，其余文件都走
「全局级 + 项目级」双源，项目级优先。另有一份随构件发布的 `default-agent.json`（内置系统默认 agent），**不走双源、用户改不了**。

| 文件 | 配置类 | 内容 |
| --- | --- | --- |
| `config.json` | `AppConfig` | 进程名 + 各配置文件路径 + 插件扫描目录 |
| `models.json` | `ModelSettings` | `defaultProvider` / `defaultModel` / `providers` |
| `agents.json` | `AgentSettings` | `agents`（用户自定义 agent） |
| `jellyfish.json` | `JellyfishSettings` | `plugins`（名单与插件配置段）等运行期设置 |
| `default-agent.json` | `AgentDefinition` | 内置系统默认 agent（classpath 根，不走双源；跟着读它的加载器放在 `jellyfish-infra/src/main/resources/`） |

### `config.json`

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

仓库里的 `config.json` 就是这份：**全局级约定目录 `~/.jellyfish/`、项目级约定目录 `<工作目录>/.jellyfish/`**，
三类配置的文件名固定（`models.json` / `agents.json` / `jellyfish.json`）。要换位置只改 `config.json`。

> **改完不用重启**：`models.json` / `agents.json` / `jellyfish.json` 的内容改动敲 `/reload` 即生效（重建索引 + 按差异重启
> 受影响的插件）。**`config.json` 不参与热更新**：它是「去哪个文件读配置、去哪个目录找插件」的部署事实，改它要重启进程；
> 同理，新增 / 删除插件 jar 也仍需重启（扫描目录与插件集合只在启动期确定）。

`plugins.roots` 是插件 jar 的扫描根目录（PF4J 在目录下一层找 `plugin.properties`）：顺序即扫描顺序，相对路径相对
**进程工作目录**解析，条目行首的 `~` 展开为用户主目录；留空则回退默认值 `plugins`。为什么它在这里而不是 `jellyfish.json`：
它与「去哪个文件读配置」同属部署事实；`jellyfish.json` 的 `plugins` 段只保留加载后的运行期设置。

## `models.json`

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

`type` 决定用哪套 provider 实现（`openai` 形协议即为其一），`apiKey` 建议走环境变量插值。`/model` 里的
`provider/model` 就是这里的键与 `models[].id`。

**`type` 可以由插件提供**：插件能为一个内核不认识的新类型提供传输实现（本地 llama.cpp、企业自建网关、
私有协议、自定义鉴权），用户只需在这里写一个指向该类型的 provider。两条硬规则：

- **内核自带的 `type` 不能被插件覆盖**（`openai` / `claude` / `deepseek` / `gemini` / `minimax` 及其别名）。
  这不是优先级问题：传输请求里带的是已解析好的 `apiKey`，能顶替 `openai` 的插件等于把密钥转发出去。
- **`models` 可以由插件动态发现**：插件接管的 provider 会被问一次「现在有哪些模型」，非空时**整体替换**
  这里写的 `models`。发现结果**不落盘**——本文件始终是模型的唯一持久事实，删掉插件后配置里那份依旧有效。

传一个不存在的 `type` 时，报错会列出内核认识的类型与可执行的下一步。

### `providers.<name>.cache`（可选，两项缺省都关）

这两个旋钮都只影响**命中率**、不影响正确性，而且都与**具体厂商的缓存实现**绑死——缓存 TTL 是几分钟
还是几小时、支不支持显式的缓存路由键，各家不一样，因此挂在 provider 上而不是全局。

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `promptCacheKey` | `false` | 把会话标识作为缓存路由键下发（OpenAI 系为 `prompt_cache_key`），让同一会话的请求尽量落到持有相同前缀的那台机器上。缺省关闭：老模型 / 老端点收到不认识的字段可能直接报错 |
| `keepAliveSeconds` | `0` | 空闲时每隔这么多秒重发一次「复用同一前缀、且不要求生成内容」的请求，把缓存的 TTL 续上。`0` 关闭。**它是要花钱的**，详见下 |

**这一层只是基线，不是全部**：厂商协议字段与缓存策略还能由插件经 `RequestTuningRequest` 逐请求调整
（缓存路由键、保留策略、断点数），因为「目标端点认哪些字段」是厂商知识而不是内核知识。

**Anthropic 与其余三家的区别要特别说明**：DeepSeek 默认就按前缀缓存、OpenAI 系前缀够长就自动缓存，
而 **Anthropic 必须显式用 `cache_control` 标出断点，不标就一个字节都不缓存**。因此 Claude 客户端会
**默认标注两个断点**（稳定前端 + 会话尾部）——这不是可选优化，不标的话前面所有「让前缀稳定」的工作
在 Claude 上都没有任何回报。想要退回去由插件接管，用 `RequestTuningRequest` 把断点数声明为 `0`。

**`keepAliveSeconds` 主要给 OpenAI 系用**：DeepSeek 的磁盘缓存要「几小时到几天」不用才清理，
按分钟级去续它基本没有意义，DeepSeek 配置里保持 `0` 即可。

**`keepAliveSeconds` 的账要算清楚**：一次保活命中约 `0.1×` 前缀，而它要防的是一次未命中（多花约 `0.9×`）。
因此它划算的前提很明确——**这一轮空闲之后你真的会回来接着问**。于是策略是自限的：
**每个空闲期最多续 3 次**（那笔总账约 `0.3×` 前缀，换掉一次未命中就是净赚），续完就停手；
你一有新动作，空闲期重新开始计数。只保活**当前会话**（`SessionManager.current()`），因为保活 N 个
会话等于为 N 份前缀持续付费。上限 1 小时，超出回退到关闭。

保活花掉的 token 照常进 `/usage`，也照常进 `llm.*` 指标——它不是隐形的开销。

**调参提示**：先按厂商文档的 TTL 开一个略小于它的间隔试一段（OpenAI 内存缓存约 5–10 分钟，
那 `240` 是个合理起点），然后看 `/usage` 的命中率有没有真的抬上去、以及输入 token 总量涨了多少。
**没抬上去就关掉它**——那说明你的空闲间隔要么短于 TTL（本来就没过期），要么长得超出了那三次续期。

## `agents.json`

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

除了 `description` / `permissions`，还有两个字段：

- **`delegatable`**（缺省 `false`）：这个 agent 能不能被 `task` 工具当作委派目标。不配就不出现在模型看到的可用类型清单里
  （见 [architecture.md 的子代理一节](architecture.md#子代理委派task)）。
- **`model`**：这个 agent 的偏好模型，写法与 `/model` 参数一致（`openai/gpt-4o-mini` 或裸 `gpt-4o-mini`）。解析顺序是
  **会话显式选定 → `agent.model` → `models.json` 的全局默认**，因此绑定一个 agent 会连它的模型一起生效，而你在会话里
  `/model` 过之后以会话为准。

### 工具权限

三段工具名的语义是：**字段缺失 = 不限制**，显式写 `[]` = 该方向上一个都不放行（`allowedTools: []` 就是全拦，
`deniedTools` 同理）。因此想表达「只显式拒绝两个工具、其余不限制」就**不要**写 `"allowedTools": []`，直接省略该字段。

优先级是 `deniedTools` > `askTools` > `allowedTools`。`askTools` 里的工具每次调用都要人工审批，三种外壳行为不同：

| 外壳 | 行为 |
| --- | --- |
| `-tui` | 弹出审批选择框（`↑`/`↓` 选，`Enter` 确认，`Esc` 拒绝并中断回合），批准才执行 |
| `-server` | 把待审批项推进 SSE 流（`approval_required`），由 `POST /approvals/{requestId}` 裁决 |
| `-cli` | 没有审批界面（也没有审批者），因此**一律按拒绝处理**——绝不静默放行 |

审批框等不到答复（缺省 120 秒，见 `permission.approvalTimeoutSeconds`）同样按拒绝处理。

> `allowedTools` 还有一层作用：它同时决定**子代理能看到哪些工具**（只随委派生效，主会话不受影响）。

### 提示词文件

每个 agent（包括内置的系统 agent）的系统提示词来自**与配置文件同目录**的 `{agentId}.md`。
上例的 `coder` 需要一份 `coder.md`，内容就是它的系统提示词，可以是多段长文。

`agentId` 同时是文件名，因此**不能含路径分隔符或 `..`**（含这类字符的条目会被整条丢弃并告警）。文件不存在不阻断启动，
只有用户 `/agent` 切过去时才提示「该 agent 没有系统提示词」。

## `default-agent.json`

内置，随 `jellyfish-infra` 发布在 classpath 根（资源跟着读它的加载器走，加载器在 infra）：

```json
{
  "agentId": "jellyfish",
  "description": "系统默认 agent",
  "permissions": {}
}
```

它配合同目录的 `jellyfish.md` 使用。**每次启动、每个新建会话都绑这个内置 agent**；想用自定义 agent 必须手动
`/agent <agentId>` 切换。用户 `agents.json` 里写同名 `jellyfish` 会被忽略并告警（内置定义不可被覆盖）。

## `jellyfish.json`

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
    },
    "cache": {
      "agingPercent": 70
    }
  },
  "permission": {
    "approvalTimeoutSeconds": 120
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

### `plugins`

- `plugins.enabled` / `plugins.disabled`：**启用 / 禁用名单**。**「未声明」与「声明为空」是两回事**：`enabled` 不声明表示
  「不额外限定」（全部插件都加载），显式写 `[]` 表示「一个都不启用」；`disabled` 不声明与写 `[]` 等价，都不禁用任何插件。
  同一个 pluginId 同时出现在两份名单里时按**禁用**处理，并发一条配置告警。
- `plugins.configurations.<pluginId>`：单个插件的配置段，逐插件的键值见插件自己的文档。
- 上文示例里的 `readOnlyTools` 是**只读白名单的唯一来源**：用户写哪些工具名，PLAN 模式下就只有哪些可用
  （工具提供方无法自称只读，描述符里已无该字段；详见
  [architecture.md](architecture.md#扩展层两条通道各管一件事)）。**不写就等于 PLAN 下全部不可用**——
  白名单语义下「用户没表态」与「用户不准」是同一件事。

### `react`（ReAct 循环）

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `maxRounds` | `16` | 单回合最大轮数 |
| `contextReserveTokens` | `1024` | 上下文预算里为系统提示词 / 插件注入的上下文预留的 token |
| `maxToolOutputChars` | `20000` | 单个工具输出回灌模型前的截断长度，也是**硬上限**（工具失控时由它保命） |
| `compactKeepRecentMessages` | `20` | 压缩默认保留的最近消息条数（写 `0` 即「不保留原文」） |
| `compactMaxSummaryChars` | `4000` | 摘要长度上限（提示模型别写太长，真超了按码点本地截断并留标记） |
| `autoCompactPercent` | `80` | 上下文用到多少百分比就自动压缩（写 `0` 关闭自动压缩，只留手动 `/compact`） |

非法值（非正数）回退到缺省值。

### 工具结果与落盘

`react.toolOutput` 段只管工具结果太长时怎么办：

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `dir` | `~/.jellyfish/tool-outputs` | 完整内容的落盘根目录（运行产物写在这里而不是项目目录） |
| `keepFiles` | `200` | 每个会话在该目录下的文件数上限，超了从最旧开始删（写 `0` 关闭清理） |
| `maxBytes` | `52428800`（50 MiB） | 每个会话在该目录下的字节数上限，超了从最旧开始删（写 `0` 关闭清理） |
| `keepRecentMessages` | `20` | 组装请求时最近多少条消息里的工具结果保留完整内容（写 `0` 关闭该裁剪） |
| `spillMaxBytes` | `33554432`（32 MiB） | 单个工具结果的落盘上限，运行期钳制为不超过 `maxBytes`；触及上限时后续内容不再保存、信封会带 `_partial` 说明它不完整 |

信封的结构、预览取头 30% / 尾 70% 的理由、`_path` 的有效期、`read_file` 单行超限为什么报错，见
[architecture.md](architecture.md#工具结果的信封与元数据)。

### 工具结果老化与提示词缓存

`react.cache` 段管的是「工具结果什么时候换成 stub」——它直接决定命中率，因为厂商的 prompt 缓存是
**前缀匹配**：改掉历史上的一个字节，它后面的整段都会从约 0.1× 的命中价变回 1× 全价。

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `agingPercent` | `70` | `0` = 按「距尾部多少条消息」老化，边界每轮重算（逃生门）；`> 0` = 只在上下文用量达到该百分比时老化，且**一个压缩周期内只推进一次**，其余轮次的边界冻住不动 |

- **为什么水位触发更省**：按条数老化时，每轮追加 2–3 条消息就使边界前移 2–3 条，
  恰好移过「最近、最贵、刚刚才被缓存」的那一段，于是真正的大内容永远落在变动区、永远不被复用。
  改成水位触发后，边界在一个压缩周期内是常量，已发出去的前缀因此保持 append-only。
- **为什么缺省是 `70`**：它是**推导出来的**——必须低于 `autoCompactPercent`（缺省 80）否则压缩先发生、
  老化永远轮不到；要留足够余量真正起到泄压作用；又要尽量晚，好让一次性切掉的是最老的内容。
  即「略低于自动压缩阈值，相差约 10 个点」。**改了 `autoCompactPercent` 就应一并重看这一项。**
- **为什么整件事是赚的**：老化删掉前缀中的 N 个 token 只省 0.1N（它们本来就是命中价），
  却让它断点之后的 M 个 token 从 0.1× 变回 1×，代价 0.9M——**M > 1.11N 时就是净亏**。
  按条数老化时断点前移得无止境，于是每轮都亏一次；水位触发把这次亏损压到**一个压缩周期一次**。
- **想精确复原升级前的行为**：写 `agingPercent: 0`。
- **想彻底关掉老化**：把 `react.toolOutput.keepRecentMessages` 写 `0`。它是总开关——
  既表示「不裁剪」，也表示「不做老化」；水位口径也需要它作为「保留窗口」。
  关掉之后上下文靠自动压缩治理，代价是旧的大结果会一直占着位置直到被压掉。

推导与实测数据见 [design/llm-cache.md](design/llm-cache.md)。

### `permission`

当前只有 `approvalTimeoutSeconds`：`askTools` 里的工具在 TUI 上弹审批框后最多等这么久，超时按拒绝处理。
它有缺省值（120 秒）而不允许「永不超时」——理由见 [architecture.md](architecture.md#权限与审批)。

### `subAgent`（子代理委派）

| 字段 | 缺省 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 全局开关。**关掉后 `task` 工具直接不再注册**，模型看不到它；敲 `/reload` 即可生效、不用重启 |
| `maxDepth` | `1` | 允许的最大委派层数（即「主会话 → 子代理 → 孙代理」），写 `0` 表示禁止委派。缺省保守为 1（只允许一层）。 |
| `maxSpawnsPerTurn` | `3` | 单个顶层回合内允许派生的子代理总数 |
| `maxRounds` | `8` | 子代理自己那个回合的最大轮数，**不跟随** `react.maxRounds`（子代理被设计来干一件窄活） |
| `maxConcurrentRuns` | `3` | **全局同时运行的子代理 run 数上限**（不含父回合）。超过的 run 排队；run 各自跑在独立的 `agent-run` 线程池上，父回合等待子 run 时会让出并发许可 |
| `runTimeoutMillis` | `300000` | 单个 run 的墙钟上限（毫秒）。到点取消该 run 并把它记为「截断」 |
| `runTokenBudget` | `500000` | 单个 run 的累计 token 上限；写 `0` 表示不限制 |
| `treeTokenBudget` | `1500000` | 一棵 run 树的累计 token 上限；写 `0` 表示不限制 |
| `archiveKeepFiles` | `200` | run 归档目录（`<toolOutput.dir>/subagent-runs`）最多保留的文件数；写 `0` 表示不清理 |
| `archiveMaxBytes` | `104857600` | run 归档目录最多占用的字节数（100 MiB）；写 `0` 表示不清理 |

**归档配额与工具输出分开算**：归档按 run 产生、含整份子会话 transcript，个体远大于一份工具结果。
共用一份预算时，一次长任务的归档就能把「可回查的工具结果」挤干净。两者分居不同目录、各有各的上限，
清理互不掏空对方的窗口。归档只是**可观测窗口**（最旧的会被清理），不是合规归档。

`maxDepth` 挡的是「一条链多深」，`maxSpawnsPerTurn` 挡的是「一层扇出多少」，
`maxConcurrentRuns` 挡的是「全局同时在跑多少」，三者正交；三个 token / 时间上限挡的是「跑飞了也停得下来」。
除 `enabled` 外非法值一律回退缺省值。

## 双源合并与插值

- **插值**：只有字符串值里的 `${VAR}` 会被替换为环境变量（`${VAR:-default}` 可取默认值，`\${VAR}` 转义为字面量），
  JSON 的 key 不替换。`{agentId}.md` 提示词是**原文**，不做模板插值。
- **路径**：`~` 与 `~/` 展开为用户主目录（`~other/...` 这种指定其他用户的形式不展开）；两条路径都支持。项目级路径相对
  **进程工作目录**解析，不是相对 jar 位置。文件不存在视为「该源未配置」，静默跳过（这是 `globalPath` 与 `projectPath`
  可以同时配上、缺哪份就少哪份的原因）。`config.json` 的 `plugins.roots` 同样支持 `~` 与相对路径，语义一致。
- **合并**：同名 `provider` / `agent` / 插件配置段以项目级**整对象**覆盖全局级，agent 的提示词 md 也随来源一起覆盖；
  `defaultProvider` / `defaultModel` 取项目级非空值，否则回退全局级；`react` / `permission` / `subAgent` 段项目级整对象
  覆盖全局级；启用 / 禁用名单项目级**已声明则整体替换**（写 `[]` 即清空该名单，不做并集）。`plugins.roots` 只在
  `config.json` 一处，不参与双源合并。
- **容错**：配置缺失或可疑只发配置告警事件，不中断启动；真正用到时才报错。
- **不要提交密钥**：`apiKey` 等敏感值通过环境变量注入，不要落到配置文件里。

## 常见配置任务

1. **让这台机器上的所有项目都能用同一套模型配置**：把同一份文件放到 `~/.jellyfish/` 即可（项目级同名条目会整对象覆盖全局级）。
2. **只给某个项目换模型**：在项目根建 `.jellyfish/models.json`，只写要覆盖的 `provider`。
3. **关掉自动压缩**：`react.autoCompactPercent: 0`，改用 `/compact preview` 先看会压多少再手动压。
4. **临时禁用某个插件**：`plugins.disabled: ["jellyfish-todo"]`，然后 `/reload`。
5. **关掉子代理委派**：`subAgent.enabled: false`，`/reload` 后 `task` 工具不再注册。
