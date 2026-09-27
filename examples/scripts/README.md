# 脚本插件示例

两种语言各两个示例，都可以直接跑。它们同时是**端到端测试的输入**——`mvn -Pscript-it test`
会真的把 `examples/scripts/<语言>/{hello,jira}` 跑起来（调用工具、执行命令、收事件、验清单），
所以这里的代码不会像放在文档里的示例那样腐烂。

| 示例 | 它演示什么 |
| --- | --- |
| `hello/` | 最小可用：一个只读工具 + 一个可写工具 + 一条命令（带别名与候选）+ 两种贡献（`prompt` / `status_line`）+ 订阅一个事件 |
| `jira/` | 更接近真实插件：多个工具（只读与可写分开声明）、带别名/用法的命令、候选查询写在单独的函数里、`prompt` + `panel` 贡献、**订阅并发布**事件 |

`jira` 是**内存里的假工单系统**（没有任何网络调用，重启 worker 就忘光）。真实的插件会把 HTTP
调用放在这里，而那正是脚本进程隔离的价值所在：依赖装在脚本自己的环境里，崩了也只波及它自己。

## 两门语言

同一件事在两边逐条对应，API 名刻意取得一样，会一门就会另一门：

| | Python | Node |
| --- | --- | --- |
| 位置 | `scripts/python/<id>/`（默认） | `scripts/node/<id>/`（默认） |
| 入口 | `main.py`（由 `manifest.json` 的 `entry` 指向） | `main.js` |
| 引入 SDK | `from jellyfish_sdk import tool, command` | `const { tool, command } = require('jellyfish_sdk')` |
| 声明能力 | 装饰器 `@tool(...)` / `@command(...)` | 声明函数 `tool({...}, fn)` / `command({...}, fn)` |
| 处理器签名 | `def f(args, ctx)`（命令是 `tokens, raw, ctx`） | `(params, ctx)`，工具读 `params.args`、命令读 `params.tokens` / `params.raw` |
| 候选查询（二级选择页） | `has_options=True` 或 `@command_options("x")` | `hasOptions: true` 或 `commandOptions('x', fn)` |
| 命令是否依赖会话 | `session_required=False`（缺省 `True`，保守） | `sessionRequired: false`（缺省 `true`，保守） |
| 缺省描述 | 取函数文档字符串第一行 | 必须显式写 `description`（JS 拿不到注释） |
| 缺省解释器 | `python3`（配置键 `pythonPath`） | `node`（配置键 `nodePath`） |
| 清单生成器 | `dump_manifest.py` | `dump_manifest.js` |
| 模块格式 | — | CommonJS（入口与 SDK 都用 `require`，ESM 不在当前范围内） |

Node 侧零第三方依赖：网关与 worker 只用 Node 内置模块，因此**不需要 `npm install`**；
你自己的脚本当然可以有自己的 `node_modules`（那是脚本目录的事，与宿主无关）。

## 跑起来

每个示例目录就是一个脚本插件，目录名就是脚本标识：

```bash
mkdir -p scripts/python scripts/node
cp -r hello jira scripts/python/          # 默认位置：进程工作目录下的 scripts/python
cp -r hello jira scripts/node/            # Node 侧同理：scripts/node
```

在 `<工作目录>/jellyfish/jellyfish.json` 里（或全局 `~/jellyfish/jellyfish.json`）可选地配置：

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
        "nodePath": "node",
        "invokeTimeoutSeconds": 30,
        "workerIdleSeconds": 300,
        "gatewayIdleSeconds": 600
      }
    }
  }
}
```

两段配置除了解释器键（`pythonPath` / `nodePath`）与脚本根目录默认值之外完全一样——
它们由同一份公共实现解析（`ScriptBridgeConfig`），因此不会出现「超时语义两边不同」这类漂移。

启动后：

- `/python`、`/node` 看各自的台账（脚本、已登记能力、网关与 worker 的 PID、熔断、事件计数、单脚本问题）；
- `/hello 世界`、`/hi 世界`（别名）、`/jira PROJ-1 DONE`；
- 工具 `hello_greet` / `hello_remember` / `jira_read` / `jira_create` 直接由模型调用。

**启动 Agent 不会拉起任何脚本进程**：第一次真正用到某个脚本时才 fork 它的 worker，
空闲超时后自行退场（进程数与常驻内存回到 0）。Node 侧同理（`spawn` + 一条 `init` 帧）。

## 清单与实现必须一致

对宿主而言，脚本的能力**只有** `manifest.json` 这一个来源，而实现的事实写在声明里。
两者不一致时脚本会**拒绝服务**（宁可明确失败，也不要「模型按一份不存在的工具定义去调用」）。

因此**改完脚本一定要同步清单**。生成器就在网关资源目录里（两个语言各一份）：

```bash
# Python
python3 ~/jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira --write
python3 ~/jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira --check

# Node
node ~/jellyfish/gateway/node/<digest>/script/dump_manifest.js ./jira --write
node ~/jellyfish/gateway/node/<digest>/script/dump_manifest.js ./jira --check
```

- `--check` 与目录里那份比对，不一致退 1，并**按名字**报差异（「清单里多出了这一项: 幽灵工具」
  「tools[jira_read].description: 实现是 a，清单是 b」）；
- `--write` 直接写盘，**推荐**：用 shell 重定向（`> manifest.json`）会让 shell 先把目标文件
  截空，而生成器恰好要读它来确认入口文件名（两个生成器都对空文件做了容错，但没必要踩）。

生成的清单只包含清单需要的字段，且**会被内核的严格校验器读一遍**——这一条有端到端测试守着。
`manifestStrict: false` 可以把「不一致」降级成告警继续服务，但那只是在延长定位时间。

## 写自己的脚本时最容易踩的几处

- **清单里的描述与参数决定模型看到的工具定义**：这里的 `description` / `parameters` / `required`
  与声明里的值是两份（一致性校验只比名字），写错的表现是模型按错误签名调用。
- **只读必须显式声明**（Python `read_only=True` / Node `readOnly: true`）：缺省是「可写」。
  误声明只读等于给模型留了一个绕过 PLAN 模式的后门。
- **用脚本自己的目录定位文件**（Python `__file__` / Node `__dirname`）：相对路径会落到进程的
  cwd，那是宿主的工作目录。
- **失败要抛 `ScriptError`**，不要返回 `{"error": ...}`：后者会被当成正常输出。
- **事件会丢，且对忙的 worker 直接丢**：只用来做通知与统计，不要用它传递必须到达的结果。
  另外注意「观察者效应」——你在事件之后紧接着发起的调用，本身就会让推送撞上忙碌窗口。
- **脚本不需要考虑并发**：worker 是单线程的（收请求、跑处理器、收事件都在同一个循环里），
  同一个脚本的并发调用会被网关排队。
- **候选查询与执行是两条路，而且它只该读不该写**：用户按下补全键时被调用的那一次，
  拿到的是 `tokens = None`（Node 里是 `null`）——**这就是「这次是候选查询」的标记**。
  执行那次一定给真实的 `tokens`（`[]` 表示用户没输入参数，与 `None` 不是一回事）。
  两个入口各走一个示例：`hello` 用 `has_options=True` 让同一个函数按 `tokens is None` 分支，
  `jira` 把候选查询写在单独的函数里（真实脚本更常用这个，因为候选查询必须只读且快）。
  写成 `has_options=True` 却看不出两条路的函数会在**启动时**被拒绝，报错里带着可照抄的写法。
- **卡死的脚本会被强杀**，两边都由网关执行：Python 侧先 SIGTERM（信号处理器直接 `os._exit`），
  Node 侧 SIGTERM 往往无效（事件循环被同步代码占着），因此靠的是到点后的 SIGKILL。
