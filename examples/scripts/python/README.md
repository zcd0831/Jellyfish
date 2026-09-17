# Python 脚本插件示例

两个可以直接用的示例插件。它们同时是**端到端测试的输入**——`mvn -Pscript-it test` 会真的把这两个
脚本跑起来（调用工具、执行命令、收事件、验清单），所以这里的代码不会像放在文档里的示例那样腐烂。

| 示例 | 它演示什么 |
| --- | --- |
| [`hello/`](hello/) | 最小可用：一个只读工具 + 一个可写工具 + 一条命令（带别名与候选）+ `prompt` 与 `status_line` 贡献 + 订阅一个事件 |
| [`jira/`](jira/) | 更接近真实插件：多个工具（只读与可写分开声明）、带别名/用法的命令、候选查询写在单独函数里、`prompt` + `panel` 贡献、**订阅并发布**事件 |

`jira` 是**内存里的假工单系统**（没有任何网络调用，重启 worker 就忘光）。真实的插件会把 HTTP
调用放在这里，而那正是脚本进程隔离的价值所在：依赖装在脚本自己的环境里，崩了也只波及它自己。

## 跑起来

每个示例目录就是一个脚本插件，目录名就是脚本标识：

```bash
mkdir -p scripts/python
cp -r hello jira scripts/python/          # 默认位置：进程工作目录下的 scripts/python
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
      }
    }
  }
}
```

启动后：

- `/python` 看台账（脚本、已登记能力、网关与 worker 的 PID、熔断、事件计数、单脚本问题）；
- `/hello 世界`、`/hi 世界`（别名）、`/jira PROJ-1 DONE`；
- 工具 `hello_greet` / `hello_remember` / `jira_read` / `jira_create` 直接由模型调用。

**启动 Agent 不会拉起任何脚本进程**：第一次真正用到某个脚本时才 fork 它的 worker，
空闲超时后自行退场（进程数与常驻内存回到 0）。

## 清单与实现必须一致

对宿主而言，脚本的能力**只有** `manifest.json` 这一个来源，而实现的事实写在装饰器里。
两者不一致时脚本会**拒绝服务**（宁可明确失败，也不要「模型按一份不存在的工具定义去调用」）。

因此**改完脚本一定要同步清单**。生成器就在网关资源目录里：

```bash
# 从装饰器导出清单到 stdout（可直接落盘）
python3 ~/jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira

# 检查目录里那份清单有没有落后于实现（不一致退 1，并打印差异）
python3 ~/jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira --check

# 同一个入口也能从网关脚本调用
python3 ~/jellyfish/gateway/python/<digest>/script/gateway.py --dump-manifest ./jira --check
```

生成的清单只包含清单需要的字段，且**会被内核的严格校验器读一遍**——这一条有端到端测试守着。
`manifestStrict: false` 可以把「不一致」降级成告警继续服务，但那只是在延长定位时间。

## 写自己的脚本时最容易踩的几处

- **清单里的描述与参数决定模型看到的工具定义**：这里的 `description` / `parameters` / `required`
  与装饰器里的值是两份（清单与实现的一致性校验只比名字），写错的表现是模型按错误签名调用。
- **只读必须显式声明**（`read_only=True`）：缺省是「可写」。误声明只读等于给模型留了一个
  绕过 PLAN 模式的后门。
- **用 `__file__` 定位脚本自己的文件**：相对路径会落到进程的 cwd，那是宿主的工作目录。
- **失败要抛 `ScriptError`**，不要返回 `{"error": ...}`：后者会被当成正常输出。
- **事件会丢，且对忙的 worker 直接丢**：只用来做通知与统计，不要用它传递必须到达的结果。
  另外注意「观察者效应」——你在事件之后紧接着发起的调用，本身就会让推送撞上忙碌窗口。
- **脚本不需要考虑并发**：worker 是单线程的（收请求、跑处理器、收事件都在同一个循环里），
  同一个脚本的并发调用会被网关排队。
