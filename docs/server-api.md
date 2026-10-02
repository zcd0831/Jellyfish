# Server 模式：HTTP 接口参考

面向**接入方（Web 前端 / 其他服务）**。起服务的命令、绑定地址与安全默认见 [README 的 Server 模式一节](../README.md#server-模式)；
这里给全部接口、SSE 事件与鉴权细节。

## 鉴权

| 情形 | 行为 |
| --- | --- |
| 没配密钥（缺省） | **不鉴权**：任何能访问该端口的人都能建会话、跑命令、读全部会话正文。对只绑回环的本地场景够用 |
| 配了密钥 | 除 `GET /health` 外**所有接口**都要 `Authorization: Bearer <密钥>`，否则 `401` |

密钥的来源与优先级、`--host` 的行为见 README。以下几条是设计口径，接入方据此判断哪些做法不该依赖：

- **为什么缺省不鉴权**：本服务默认只绑 `127.0.0.1`，对回环还要先配密钥只会把「本地跑一次」变成一件要读文档才能做的事；
  而**对外开放是显式动作**，那一步必须同时配密钥——**没配密钥时启动日志会给一条 WARN**（默认日志级别就是 WARN，
  因此这条一定看得见）——少了它，「以为配了」与「其实没配」在现象上都是「能访问」；配好了则是常规 INFO。
- **`GET /health` 不校验**：探活必须能在「还没有密钥」的场景下工作（容器编排的 liveness probe、起服务后的第一条 curl）。
  它只返回 UP / WARN / DOWN 与检查项名字，不含会话正文、路径与密钥。
- **不接受用 query 参数传密钥**：URL 会进访问日志、浏览器历史与 Referer。而本服务的对话入口是 `POST`，浏览器的
  `EventSource` 本来就用不了（它只能发 GET），客户端无论如何都要用 `fetch` 流式读取，而它能带请求头。
- **密钥比较是常时比较**（`MessageDigest.isEqual`）：避免用短路语义把密钥逐字节泄露给能反复试探的调用方。
  密钥短于 16 位会在启动日志里告警，但不拒绝启动。
- **命令域与对话域同权**：`POST /sessions/{id}/commands` 能执行 `/reload` 等系统命令，因此密钥泄露等于整机权限泄露。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/sessions` | 建会话（body 可选 `agentId`/`provider`/`model`/`permissionMode`，缺省取 `--agent`/`--model`/`--mode`；body 可省略） |
| `GET` | `/sessions` | 会话摘要列表（不含消息正文），按最后变更时间倒序 |
| `GET` | `/sessions/{id}` | 完整会话快照（含消息与用量） |
| `DELETE` | `/sessions/{id}` | 删除会话（含插件持久化） |
| `POST` | `/sessions/{id}/chat` | 对话，**恒为 SSE**（`text/event-stream`）；body 为 `{"message":"…"}` |
| `POST` | `/sessions/{id}/cancel` | 取消该会话在途回合 |
| `POST` | `/sessions/{id}/commands` | 执行命令（`input` 原文 或 `name`+`args` 结构化，二选一） |
| `GET` | `/commands` | 结构化命令清单（前端菜单用） |
| `GET` | `/commands/{name}/options?sessionId=` | 命令候选值 |
| `GET` | `/approvals` | 当前待审批项（无则 204） |
| `POST` | `/approvals/{requestId}` | 裁决审批（`{"approved":true\|false}`） |
| `GET` | `/health` | 健康报告（UP / WARN / DOWN）；**配了 API key 时只有它不校验**，探活无需凭据 |

## SSE 事件

`POST /sessions/{id}/chat` 的事件类型：

| 事件 | 说明 |
| --- | --- |
| `turn_start` | 回合开始 |
| `text` | 助手正文增量 |
| `thinking` | 思考过程增量 |
| `tool_start` | 工具调用开始 |
| `tool_output` | 工具执行期的实时输出（可丢） |
| `tool_done` | 工具调用结束（权威结果） |
| `approval_required` | 需要人工审批 |
| `approval_resolved` | 审批已裁决 |
| `shell_notice` | 插件推的一条通知，载荷 `{owner,sessionId,key,severity,lines}` |
| `shell_invalidated` | 插件说「我贡献的内容脏了」，载荷 `{owner,sessionId,what}` |
| `input_handled` | 终态：输入被插件接过去了，没有回合，载荷 `{sessionId,notice}` |
| `done` | 终态：回合正常结束 |
| `cancelled` | 终态：回合被取消 |
| `turn_blocked` | 终态：回合被插件在开始前拦下，载荷 `{turnId,sessionId,reason}` |
| `error` | 终态：回合出错 |

`done` / `cancelled` / `turn_blocked` / `input_handled` / `error` 是终态，写出后流结束；空闲超时写 `: keepalive` 注释帧。

**`input_handled` 与其他终态的区别**：它<b>根本没有回合</b>——没有用户消息落进会话、没有模型调用、没有轮数，
因此它不带 `turnId`，也不占一个在途回合槽位。客户端应当把它当成「外壳提示」而不是「回答」。

**`turn_blocked` 单独一档而不是归入 `error`**：它不是错误（没抛异常、没资源故障、客户端也没断开），
客户端对两者的处理不同（改请求 / 找人确认 vs 重试 / 报障）。同一条理由在 `-cli` 上是退出码 `7`。

**`tool_output` 是可丢的过程信息**：它是工具**执行期**的实时输出（命令行跑十分钟时能看见动静），载荷
`{turnId,toolCallId,toolName,chunk}`。服务端按待发条数封顶（超出即丢），客户端应当把它当成进度展示，
**权威结果始终是 `tool_done` 里的 `output`**。

**`tool_done`** 除了 `output` 还带 `metadata`：工具结果的结构化事实（命令行的 `exitCode` / `terminal`）。
**前端据字段渲染失败标记，不要去解析 `output` 的首行文案**——那行措辞是给模型看的，改一个词标记就会消失。
`terminal` 的取值集合含 `FAILED`（工具自己没成）与 `REJECTED`（参数被插件拒绝，工具压根没跑），
两者都让界面显示警示标记。
**工具抛异常时也带 `metadata.terminal=FAILED`**（并按条件带 `summary` 说明原因），因此前端不必再读 `success`
就能画出失败标记。没有元数据时它是空对象 `{}`。

**`shell_notice` / `shell_invalidated` 走的是尽力 lane，不是回合事件**：

- 它们与回合**没有关系**：插件可以在没有回合在跑的时候推（典型是「长任务做完了」或「插件刚加载」），
  因此客户端不能把它们当成回合的一部分，也不能用它们判断回合是否结束。
- **它们可丢**：服务端每 owner 有界、同 key 可合并、满了丢最新一条；
  因此客户端**不得把它们当状态真源**，面板 / 状态栏的内容仍然靠拉取（`GET /sessions/{id}` 等）。
- **它们不落盘、不进模型上下文**：`NOTICE` 是临时显示，进程重启即消失。
- **时延最多一秒**：写循环每秒会把积压的贡献冲一次（回合事件仍然一到就走）。
- `severity` 取值是 `INFO` / `WARN` / `ERROR`（语义，不是颜色）；`lines` 是**已经滤掉控制字符**的纯文本行，
  行内的强调信息不过线（见 `ShellNoticeEvent` 的注释）。
- **`SHELL` scope 的贡献发给每一条流**（它是进程级事实，与任何会话无关）；
  `SESSION` scope 只发给它自己的那条流。
- `key` 非空时表示「同 owner + 同 key 的后到者覆盖先到者」——客户端可以直接把同一个 `key` 的后到者当成
  前一条的**原地更新**，而不是追加一条新的。

## 会话语义

- **会话一律按路径里的 id 寻址**；`-server` 不支持 `--session`（写了判用法错误退 `2`），
  `--agent` / `--model` / `--mode` 降级为「新建会话的默认值」。启动期不预建任何会话。
- **同会话同时只允许一个回合**：第二个请求返回 `409`（避免两个回合把消息历史交错写坏）；要打断就用
  `POST /sessions/{id}/cancel`，或直接断开 SSE 连接（服务端据此取消回合）。
- **人工审批走 HTTP**：`askTools` 里的工具会在流里推 `approval_required`，客户端拿 `requestId` 调
  `POST /approvals/{requestId}`。`ApprovalChannel` 的头槽位是**每会话一个**：同一会话内仍是单槽位 + FIFO 队列，
  会话之间互不排队。`GET /approvals` 没有会话上下文，取的是跨会话最早的那一条，只适用于单客户端场景。
- **错误体统一为** `{"error":"CODE","message":"…"}`；命令执行的三态在 `kind` 字段里（`UNKNOWN` 同时回 404）；
  鉴权失败是 `401` + `{"error":"UNAUTHORIZED"}`，并带 `WWW-Authenticate: Bearer realm="jellyfish"`。

## 与另外两种模式的口径差异

同一份内核在三种外壳下行为一致，但有两条与 HTTP 有关的差异值得记住：`-cli` 没有审批界面，`askTools` 一律拒绝；
`-server` 恰好相反，审批被显式搬到 HTTP 层（见上）。另外 `-server` 是常驻进程，因此**没有** `-cli` 的单次退出码语义，
一轮对话的结果只能从 SSE 流里读。
