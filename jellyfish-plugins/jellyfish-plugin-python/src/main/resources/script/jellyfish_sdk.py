# -*- coding: utf-8 -*-
"""Jellyfish Python 脚本插件 SDK。

脚本作者只与本模块打交道：用装饰器声明「我提供了什么」，剩下的（进程、协议、清单校验、
熔断、回收）都由宿主负责。因此本模块刻意不感知协议细节，只做三件事：

1. **声明**：把 ``@tool`` / ``@command`` / ``@contributes`` 等装饰器的参数记进注册表；
2. **分发**：按扩展点类型把请求交给对应的函数，并把返回值整形成协议要求的形状；
3. **生成清单**：``dump_manifest()`` 把注册表还原成 ``manifest.json``。

声明与清单必须一致，这是本方案唯一的高危点，因此两者都从这里出发：清单由代码生成，
而不是另写一份。严格校验（``manifestStrict``）在 worker 启动时把两者比一遍，
不一致就拒绝服务——宁可报错，也不要让模型按一份不存在的工具定义去调用。
"""

import json
import inspect
import os
import sys

# ---------------------------------------------------------------- 声明注册表

# 扩展点类型名 → 处理函数。类型名与 Java 侧 codec 的 typeName() 必须逐字一致。
_HANDLERS = {}

# 工具/命令声明，顺序即声明顺序，生成的清单与它保持同序（便于人读 diff）。
_TOOLS = []
_COMMANDS = []
_COMMAND_OPTIONS = []
_SUBSCRIPTIONS = []

# 声明过的贡献类型，用于检测「同一类型声明了两个函数」。
_CONTRIBUTION_TYPES = set()

# 可以发布的事件名（与 Java 侧 ScriptEventFactory 的白名单一致）。
#
# 只有两类，而且都是「自由载荷」：通知的 payload 任意 JSON，告警只有一句文本。
# 所以脚本**造不出内核语义事件**（工具完成、权限判定之类）——那类事件是内核事实的转述，
# 指标、审计与界面都按「它是真的」来消费，让脚本能造等于开了一条往审计里写假账的路。
#
# 这里再留一份的原因只是**报错更近**：不在这份清单里时在本地就提醒一句，
# 免得「事件发出去但没人收到」变成一个需要翻宿主日志的问题。
# 真正的裁决仍在宿主侧，因此这份清单哪怕过时也只会多一句提醒，不会吞掉事件。
EMITTABLE_EVENTS = frozenset(("PluginNotificationEvent", "ConfigWarningEvent"))


class ScriptError(Exception):
    """脚本侧的可预期失败。

    抛出它等于告诉宿主「这次调用失败了」，宿主会把它转成协议错误回灌给模型
    （工具调用表现为「工具执行失败：…」，回合继续）。**不要**用返回值里的
    ``{"error": ...}`` 表达失败——那会被当成正常输出，语义静默错位。
    """


class ScriptContext:
    """一次调用的上下文。

    只暴露脚本真正需要的东西：身份、会话标识与原始请求。刻意不暴露宿主对象，
    也不提供「回调宿主」的能力——脚本能做的事只有「处理这次请求并返回结果」。
    """

    def __init__(self, script_id, payload, emitter=None):
        self.script_id = script_id
        self._payload = payload or {}
        self._emitter = emitter

    @property
    def session_id(self):
        """当前会话标识；该扩展点没有会话上下文时为 ``None``。"""
        return self._payload.get("sessionId")

    @property
    def agent_id(self):
        """当前 agent 标识；仅权限拦截等扩展点会带。"""
        return self._payload.get("agentId")

    @property
    def payload(self):
        """原始请求载荷（只读用途，改它不会影响宿主）。"""
        return dict(self._payload)

    def emit_event(self, name, payload=None):
        """发布一条事件。

        **尽力而为，没有返回值，也不要依赖它一定送达**：事件通道的契约是「可以丢」，
        丢弃可能是宿主队列满、网关没在运行、或没有 worker 在听。真正需要可靠传递的信息
        请用返回值。

        ``name`` 见 :data:`EMITTABLE_EVENTS`；``payload`` 是任意 JSON（仅
        ``ConfigWarningEvent`` 要求 ``message`` 非空）。宿主会校验并可能拒绝，
        拒绝只记日志——脚本侧拿不到裁决，这也是「尽力而为」的一部分。

        :param name: 事件名
        :param payload: 事件载荷，可为 ``None``
        """
        if self._emitter is None:
            raise ScriptError("当前上下文不支持发布事件")
        if name not in EMITTABLE_EVENTS:
            print("[%s] 事件 %s 不在可发布清单 %s 内，宿主会拒绝"
                  % (self.script_id, name, sorted(EMITTABLE_EVENTS)), file=sys.stderr, flush=True)
        self._emitter(name, payload or {})

    def __repr__(self):
        return "ScriptContext(script_id=%r, session_id=%r)" % (self.script_id, self.session_id)


# ---------------------------------------------------------------- 装饰器


def tool(name, description=None, parameters=None, required=None, read_only=False):
    """声明一个工具。

    ``parameters`` 是 JSON Schema 的 ``properties`` 部分，``required`` 是必填参数名列表：
    它们会原样进入 ``ToolDescriptor``，也就是模型看到的工具定义，因此必须与函数真正
    接受的参数一致——不一致的后果是模型按错误的签名调用，而错误只在运行期以
    「参数缺失」的形式出现。

    ``read_only`` 参与内核 PLAN 模式的只读白名单，缺省 ``False``（可写）：
    误声明只读等于给模型留了一个绕过 PLAN 的后门，因此只读必须是显式选择。
    """

    def decorate(func):
        if any(item["name"] == name for item in _TOOLS):
            raise ScriptError("工具名重复声明: %s" % name)
        _TOOLS.append({
            "name": name,
            "description": description or _first_doc_line(func),
            "parameters": parameters or {},
            "required": list(required or []),
            "readOnly": bool(read_only),
            "handler": func,
        })
        _HANDLERS[("tool", name)] = func
        return func

    return decorate


def command(name, summary=None, usage=None, aliases=None, has_options=False):
    """声明一条命令。

    ``has_options=True`` 表示本命令也回答候选查询（二级选择页）。它与 ``@command_options``
    是同一件事的两个入口，二者只能选一个：同时声明会被清单校验判为冲突——
    那必然是作者写错了，而写错的后果是「命令能执行、选择页永远空、且没有任何报错」。
    """

    def decorate(func):
        if any(item["name"] == name for item in _COMMANDS):
            raise ScriptError("命令名重复声明: %s" % name)
        _COMMANDS.append({
            "name": name,
            "descriptor": {
                "summary": summary or _first_doc_line(func),
                "usage": usage,
                "aliases": list(aliases or []),
            },
            "hasOptions": bool(has_options),
            "handler": func,
        })
        _HANDLERS[("command", name)] = func
        if has_options:
            _HANDLERS[("command_options", name)] = func
            _COMMAND_OPTIONS.append({"name": name, "handler": func, "implied": True})
        return func

    return decorate


def command_options(command_name):
    """声明「本函数回答这条命令的候选查询」。

    它与 ``@command(name, has_options=True)`` 等价，用于把候选查询放在单独的函数里
    （候选查询必须只读且快，常常与执行逻辑不是同一段代码）。
    """

    def decorate(func):
        if any(item["name"] == command_name and item.get("implied") for item in _COMMAND_OPTIONS):
            raise ScriptError("命令 %s 已用 has_options 声明候选查询，不要重复声明" % command_name)
        if any(item["name"] == command_name for item in _COMMAND_OPTIONS):
            raise ScriptError("命令 %s 重复声明候选查询" % command_name)
        _COMMAND_OPTIONS.append({"name": command_name, "handler": func, "implied": False})
        _HANDLERS[("command_options", command_name)] = func
        return func

    return decorate


def contributes(type_name):
    """声明一个类型级扩展点贡献。

    支持的类型：``prompt`` / ``status_line`` / ``panel`` / ``permission`` /
    ``session_persist`` / ``session_restore`` / ``session_delete`` / ``compaction``。

    同一类型只能声明一个函数：清单里的 ``contributions`` 是「类型名集合」，
    它表达不了「同一个类型挂两个函数」，因此第二个声明会被当场拒绝，
    而不是留到运行期变成「其中一个函数永远不会被调用」。

    处理函数必须只读且快（``status_line`` 与 ``panel`` 在界面渲染线程内联执行），
    且**不得发布事件**。
    """

    def decorate(func):
        if type_name in _CONTRIBUTION_TYPES:
            raise ScriptError("贡献类型重复声明: %s" % type_name)
        _CONTRIBUTION_TYPES.add(type_name)
        # 路由键就是类型名自身：类型级扩展点没有第二个维度，统一成 (类型, 类型)
        # 可以让分发逻辑只认一种键形状
        _HANDLERS[(type_name, type_name)] = func
        return func

    return decorate


def subscribe(*event_names):
    """声明订阅某个事件。

    装饰的函数在事件到达时被调用，签名固定为 ``(event, ctx)``：``event`` 是事件字段表
    （含 ``event`` 名字、``eventId``、``occurredAt``、``sessionId``，以及该事件的标量业务字段），
    ``ctx`` 是 :class:`ScriptContext`。返回值被忽略——事件是通知，没有「回答」这回事。

    **事件可以丢**，因此不要把它当成可靠投递：宿主队列满、网关没在运行、
    这个脚本的 worker 正忙或还没起过，事件都会跳过。需要可靠性的逻辑请写成工具。

    处理器抛出的异常只记 stderr，不影响其它事件、也不影响在途调用：
    一个坏处理器不该把整个脚本带走。事件名必须是内核认识的（见宿主文档），
    写错会在清单校验时被拒绝。
    """

    def decorate(func):
        for name in event_names:
            if name not in _SUBSCRIPTIONS:
                _SUBSCRIPTIONS.append(name)
            _HANDLERS[("event", name)] = func
        return func

    return decorate


# ---------------------------------------------------------------- 清单生成


def declarations():
    """返回全部声明，供 worker 做清单校验与 ``--dump-manifest`` 使用。"""
    return {
        "tools": _TOOLS,
        "commands": _COMMANDS,
        "commandOptions": _COMMAND_OPTIONS,
        "contributions": sorted(_CONTRIBUTION_TYPES),
        "events": list(_SUBSCRIPTIONS),
    }


def dump_manifest(script_id=None, entry="main.py"):
    """把声明还原成 manifest。

    只输出清单需要的字段：``handler`` 这类只有运行期才有意义的东西不能进清单，
    否则清单结构就不再是「协议定义的一份数据」。
    """
    manifest = {"entry": entry}
    if script_id:
        manifest["id"] = script_id
    manifest["tools"] = [
        {
            "name": item["name"],
            "description": item["description"],
            "parameters": item["parameters"],
            "required": item["required"],
            "readOnly": item["readOnly"],
        }
        for item in _TOOLS
    ]
    manifest["commands"] = [
        {"name": item["name"], "descriptor": item["descriptor"], "hasOptions": item["hasOptions"]}
        for item in _COMMANDS
    ]
    manifest["commandOptions"] = [
        {"name": item["name"]} for item in _COMMAND_OPTIONS if not item["implied"]
    ]
    if _CONTRIBUTION_TYPES:
        manifest["contributions"] = sorted(_CONTRIBUTION_TYPES)
    if _SUBSCRIPTIONS:
        manifest["events"] = list(_SUBSCRIPTIONS)
    return manifest


def compare_with(manifest):
    """把声明与清单比一遍。

    返回问题描述列表（空列表表示一致）。**比较的是名字集合而不是整份清单**：
    描述文本、参数 Schema 属于「给人看的信息」，改了它们不需要脚本作者同步改清单，
    而名字集合一旦不一致，模型看到的就是一份不存在的工具定义。

    两种形态都接受：完整清单（``{"name": ...}`` 对象数组，即 ``dump_manifest()`` 的产物）
    与名字摘要（字符串数组，即宿主下发的那份）。宿主只下发名字，是因为它要判断的
    就是名字集合，带上整份清单会逼着网关跟随清单 schema 的每次演进。
    """
    problems = []
    _compare_names(problems, "tools", [item["name"] for item in _TOOLS],
                   _names(manifest.get("tools")))
    _compare_names(problems, "commands", [item["name"] for item in _COMMANDS],
                   _names(manifest.get("commands")))
    # 只比显式声明的候选查询：``has_options=True`` 的那部分由 commands 表达，
    # 清单里也不重复列（两者同时出现本来就被判为冲突）
    _compare_names(problems, "commandOptions",
                   [item["name"] for item in _COMMAND_OPTIONS if not item["implied"]],
                   _names(manifest.get("commandOptions")))
    _compare_names(problems, "contributions", sorted(_CONTRIBUTION_TYPES),
                   list(manifest.get("contributions", [])))
    _compare_names(problems, "events", _SUBSCRIPTIONS, list(manifest.get("events", [])))
    return problems


def _names(value):
    """把「对象数组」或「字符串数组」都归一成名字列表。"""
    names = []
    for item in value or []:
        if isinstance(item, dict):
            if item.get("name") is not None:
                names.append(item["name"])
        else:
            names.append(item)
    return names


def _compare_names(problems, label, declared, expected):
    missing = sorted(set(declared) - set(expected))
    extra = sorted(set(expected) - set(declared))
    if missing:
        problems.append("%s: 代码里声明了但清单没有 %s" % (label, ", ".join(missing)))
    if extra:
        problems.append("%s: 清单声明了但代码没有 %s" % (label, ", ".join(extra)))


# ---------------------------------------------------------------- 分发

# 扩展点类型名 → (实参构造, 结果整形)。
#
# 集中成一张表是为了让「每个扩展点怎么被调用、返回值要长什么样」一眼可见。
# 若散落成 if/else，新增一个扩展点时最容易漏掉的恰恰是「结果形状」那一半，
# 而漏掉的后果是宿主拿到一个形状不对的载荷后静默地当成「脚本没返回内容」。
_DISPATCH = {}


def _register(type_name, arguments, shaper):
    _DISPATCH[type_name] = (arguments, shaper)


def _as_mapping(result, default=None):
    return result if isinstance(result, dict) else default


# ---- 工具 -----------------------------------------------------------------


def _args_tool(payload):
    return {"args": payload.get("arguments") or {}}


def _shape_tool(result):
    # 任意 JSON 都能作为 output：字符串、数字、对象、数组都合法，
    # 因为内核的截断与序列化对它们的处理是统一的（见 ToolCodec）
    return {"output": result}


_register("tool", _args_tool, _shape_tool)


# ---- 命令 -----------------------------------------------------------------


def _args_command(payload):
    arguments = payload.get("arguments") or {}
    return {"tokens": list(arguments.get("tokens") or []), "raw": arguments.get("raw") or ""}


def _shape_command(result):
    if result is None:
        return {"kind": "OK", "output": None}
    if isinstance(result, str):
        return {"kind": "OK", "output": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("命令返回值必须是字符串或 {kind, output, choices}")
    shaped = dict(mapping)
    shaped.setdefault("kind", "OK")
    return shaped


_register("command", _args_command, _shape_command)


# ---- 命令候选查询 ---------------------------------------------------------


def _args_none(payload):
    return {}


def _shape_choices(result):
    if result is None:
        return {"choices": []}
    if isinstance(result, (list, tuple)):
        return {"choices": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("候选查询返回值必须是列表或 {choices: [...]}")
    shaped = dict(mapping)
    shaped.setdefault("choices", [])
    return shaped


_register("command_options", _args_none, _shape_choices)


# ---- 只返回一段文本的贡献 -------------------------------------------------


def _shape_text(result):
    if result is None:
        return None
    if isinstance(result, str):
        return {"text": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("该扩展点返回值必须是字符串、{text: ...} 或 None")
    return mapping


_register("prompt", _args_none, _shape_text)
_register("status_line", _args_none, _shape_text)


# ---- 面板 -----------------------------------------------------------------


def _shape_panel(result):
    if result is None:
        return None
    if isinstance(result, (list, tuple)):
        return {"lines": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("面板返回值必须是 None、{title, region, lines} 或行列表")
    return mapping


_register("panel", _args_none, _shape_panel)


# ---- 权限拦截 -------------------------------------------------------------


def _shape_permission(result):
    if result is None or result is False:
        return {"denied": False}
    if result is True:
        return {"denied": True}
    if isinstance(result, str):
        return {"denied": True, "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("权限拦截返回值必须是布尔、原因字符串或 {denied, reason}")
    shaped = dict(mapping)
    shaped.setdefault("denied", False)
    return shaped


_register("permission", _args_none, _shape_permission)


# ---- 会话持久化 / 恢复 / 删除 ---------------------------------------------


def _args_snapshot(payload):
    return {"snapshot": payload.get("snapshot")}


def _shape_nothing(result):
    # 这三个扩展点没有结果类型（协议里是 Void）。有返回值一律忽略而不是报错：
    # 「多返回了一个东西」不该让一次已经完成的持久化看起来失败
    return None


_register("session_persist", _args_snapshot, _shape_nothing)


def _shape_sessions(result):
    if result is None:
        return {"sessions": []}
    if isinstance(result, (list, tuple)):
        return {"sessions": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("会话恢复返回值必须是列表或 {sessions: [...]}")
    shaped = dict(mapping)
    shaped.setdefault("sessions", [])
    return shaped


_register("session_restore", _args_none, _shape_sessions)


def _args_session_id(payload):
    return {"session_id": payload.get("sessionId")}


_register("session_delete", _args_session_id, _shape_nothing)


# ---- 压缩策略 -------------------------------------------------------------


def _args_request(payload):
    return {"request": dict(payload or {})}


_register("compaction", _args_request, _as_mapping)


# ---- 事件 -----------------------------------------------------------------


def _args_event(payload):
    return {"event": payload}


def _shape_nothing_ignored(_result):
    # 事件处理器的返回值没有去处：事件是通知，不是请求。返回 None 让调用方
    # 不必因为脚本「顺手 return 了一个值」而报错
    return None


_register("event", _args_event, _shape_nothing_ignored)


# ---- 入口 -----------------------------------------------------------------


def invoke(script_id, type_name, route_key, payload, emitter=None):
    """按类型分发一次调用。

    处理函数抛出的任何异常都由调用方（worker）转成协议错误：脚本失败必须走
    「失败」这条路，而不是返回一个看起来正常的空结果——后者会让模型以为
    「脚本说没有内容」，问题就此静默。

    :param script_id: 脚本标识，用于填上下文
    :param type_name: 扩展点类型名
    :param route_key: 路由键（工具名 / 命令名）；类型级扩展点用类型名
    :param payload: 请求载荷
    :param emitter: 事件发布回调（由 worker 注入）；``None`` 表示当前上下文不支持发布事件
    :return: 结果载荷，可为 ``None``
    """
    lookup_key = route_key if route_key is not None else type_name
    handler = _HANDLERS.get((type_name, lookup_key))
    if handler is None:
        raise ScriptError("没有处理 %s=%s 的函数" % (type_name, lookup_key))
    arguments, shaper = _DISPATCH[type_name]
    context = ScriptContext(script_id, payload, emitter)
    result = handler(**arguments(payload or {}), ctx=context)
    return shaper(result) if shaper is not None else None


def _first_doc_line(func):
    """取函数文档的第一行作为缺省描述。"""
    doc = inspect.getdoc(func)
    if not doc:
        return None
    for line in doc.splitlines():
        if line.strip():
            return line.strip()
    return None


__all__ = [
    "ScriptError",
    "EMITTABLE_EVENTS",
    "ScriptContext",
    "tool",
    "command",
    "command_options",
    "contributes",
    "subscribe",
    "declarations",
    "dump_manifest",
    "compare_with",
    "invoke",
]
