"""一个「像回事」的 Python 脚本插件：假装是一个工单系统。

与 ``hello`` 的区别在于它展示了**更完整的一套能力**：

- 多个工具，且**只读与可写分开声明**（``readOnly`` 决定 PLAN 模式下谁能过）；
- 命令带别名与用法，并且**候选查询写在单独的函数里**（候选与执行是两条独立的路）；
- 两个类型级贡献：一个进 system prompt，一个进 UI 面板；
- **订阅**内核事件，并且**发布**自己的事件（发布只有两类自由载荷事件可用，见下）。

注意它是**内存里的假工单系统**：没有任何网络调用，重启 worker 就忘光。
真实的插件会在这里接 HTTP，而那正是「脚本进程隔离」的价值——
依赖装在这个脚本自己的环境里，崩了也只波及它自己。
"""

from jellyfish_sdk import ScriptError, command, command_options, contributes, subscribe, tool

#: 假工单库：``{KEY: {"summary": ..., "status": ...}}``
ISSUES = {
    "PROJ-1": {"summary": "脚本插件跑不起来", "status": "OPEN"},
    "PROJ-2": {"summary": "文档里少了示例", "status": "DONE"},
}

#: 最近几次操作，供面板显示。**有界**是刻意的：面板内容会被反复渲染，
#: 一个只增不减的列表迟早会把每次渲染都变成一次全量拼接
RECENT = []

#: 收到过哪些会话创建通知，供 prompt 贡献使用
SEEN_SESSIONS = []


def _remember(action):
    """记一条最近操作，只保留最后 5 条。"""
    RECENT.append(action)
    del RECENT[:-5]


# ---------------------------------------------------------------- 工具


@tool(
    name="jira_read",
    description="读取一个工单的摘要与状态",
    parameters={"key": {"type": "string", "description": "工单号，例如 PROJ-1"}},
    required=["key"],
    read_only=True,
)
def jira_read(args, ctx):
    """读一个工单。只读工具，PLAN 模式下可以直接用。"""
    key = (args.get("key") or "").strip().upper()
    issue = ISSUES.get(key)
    if issue is None:
        # 「没找到」也是失败：返回一个空字符串会让模型以为「工单没有内容」
        raise ScriptError("没有工单 %s" % key)
    _remember("读 %s" % key)
    return "%s [%s] %s" % (key, issue["status"], issue["summary"])


@tool(
    name="jira_create",
    description="新建一个工单，并发布一条内核通知事件",
    parameters={
        "summary": {"type": "string", "description": "工单标题"},
        "project": {"type": "string", "description": "项目前缀，缺省 PROJ"},
    },
    required=["summary"],
)
def jira_create(args, ctx):
    """新建工单（可写工具：PLAN 模式下需要用户批准）。

    创建成功后**发布一条事件**。事件是旁路且尽力而为的：宿主可能拒绝、队列可能满、
    也可能没有任何订阅者——因此工单本身已经建好了，事件只是「顺带说一声」。
    真正需要可靠传递的结果走返回值。
    """
    summary = (args.get("summary") or "").strip()
    if not summary:
        raise ScriptError("缺少参数 summary")
    project = (args.get("project") or "PROJ").strip().upper()
    key = "%s-%d" % (project, 100 + len(ISSUES))
    ISSUES[key] = {"summary": summary, "status": "OPEN"}
    _remember("建 %s" % key)
    ctx.emit_event("PluginNotificationEvent", {
        "payload": {"kind": "issue-created", "key": key, "sessionId": ctx.session_id},
    })
    return "已创建 %s：%s" % (key, summary)


# ---------------------------------------------------------------- 命令


@command(name="jira", summary="查看或切换当前工单", usage="/jira [工单号] [状态]", aliases=["j"],
         session_required=False)
def jira(tokens, raw, ctx):
    """``/jira`` 显示列表；``/jira PROJ-1`` 看一个；``/jira PROJ-1 DONE`` 改状态。

    ``tokens`` 与 ``raw`` 都给：前者适合按词处理，后者适合原样记录。
    """
    if not tokens:
        listed = ", ".join("%s(%s)" % (key, value["status"]) for key, value in sorted(ISSUES.items()))
        return {"kind": "OK", "output": "工单：%s" % listed}
    key = tokens[0].strip().upper()
    issue = ISSUES.get(key)
    if issue is None:
        return {"kind": "ERROR", "output": "没有工单 %s" % key}
    if len(tokens) > 1:
        issue["status"] = tokens[1].strip().upper()
        _remember("改 %s" % key)
    return {"kind": "OK", "output": "%s [%s] %s" % (key, issue["status"], issue["summary"])}


@command_options("jira")
def jira_options(ctx):
    """``/jira`` 的候选：现有工单号。

    候选查询必须是**只读且快**的：它在用户按键补全时被调用，慢一次就是界面上的一次卡顿。
    """
    return {"choices": [
        {"value": key, "label": key, "description": value["summary"]}
        for key, value in sorted(ISSUES.items())
    ]}


# ---------------------------------------------------------------- 类型级贡献


@contributes("prompt")
def prompt(ctx):
    """告诉模型这个脚本能干什么。

    system prompt 每轮重新拼装，因此这里每次都返回最新状态；返回 ``None``
    表示「这一轮没有要补充的」。
    """
    if not ISSUES:
        return None
    return "工单系统可用：jira_read 读、jira_create 建，/jira 命令可以看列表。"


@contributes("panel")
def panel(ctx):
    """一块 UI 面板：最近几次操作。

    ``region`` 只是**软建议**——一块区域同时只显示一个面板，抢同一区域的多个插件
    由用户用 ``/ui`` 切换，因此插件不能假设自己一定显示、也不能假设显示在哪。
    插件也无权决定尺寸：这里只给行，折行与截断由外壳负责。
    """
    if not RECENT:
        return None
    return {
        "title": "Jira",
        "region": "DOCK",
        "lines": [{"segments": [{"text": line, "emphasis": "DIM"}]} for line in RECENT],
    }


# ---------------------------------------------------------------- 事件


@subscribe("SessionCreatedEvent")
def on_session_created(event, ctx):
    """订阅「会话创建」。

    ``event`` 里带 ``event`` / ``eventId`` / ``occurredAt``，以及这个事件的标量字段
    （这里是 ``agentId`` 与 ``sessionId``）。事件的送达**可以丢**，因此它只适合做
    「顺手记一笔」这类事，不能用来做状态同步。
    """
    SEEN_SESSIONS.append(event.get("sessionId"))
    del SEEN_SESSIONS[:-5]


@subscribe("CommandExecutedEvent")
def on_command_executed(event, ctx):
    """订阅「命令执行」：每条命令的出口都会广播一次（原文、命令名、三态、耗时）。

    注意它**也在** ``/jira`` 自己被执行时到来一次——事件里没有「谁执行的」这种信息，
    真需要区分就得靠命令名判断。
    """
    name = event.get("name")
    if name == "jira":
        _remember("命令 %s" % (event.get("kind") or "OK"))
