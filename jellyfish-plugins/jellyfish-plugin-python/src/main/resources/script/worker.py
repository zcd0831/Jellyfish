#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单个脚本的 worker：数据面。

一个 worker 只加载一个脚本，因此一个脚本卡死、崩溃或退出都不会牵连别的脚本——
这是「每脚本一 worker」这个进程模型的全部意义。

它由网关 ``fork`` 出来，因此**不通过命令行接收任何参数**：脚本目录、入口文件与清单
都在内存里，继承比序列化一遍更简单也更不容易错。命令行入口只留给清单生成器
（``--dump-manifest``），因为那一步是给作者手工跑的。

生命周期由两件事决定：
* 网关发信号让它退出（空闲自毁、超时隔离、关闭）——这是正常路径；
* 它自己发现父进程已经没了（``os.getppid() == 1``）——这是兜底路径，
  用于网关被 ``kill -9`` 这种谁都收不到通知的场景。macOS 没有 ``PR_SET_PDEATHSIG``，
  因此这条兜底是必需的，不是可选的；Linux 上还额外请内核代发一次 SIGKILL。
"""

import importlib.util
import json
import os
import select
import signal
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import jellyfish_sdk as sdk  # noqa: E402  必须先把自身目录放进 sys.path
import script_wire as wire  # noqa: E402

# 协议错误码：与宿主侧 ScriptProtocol 的取值逐字一致。
CODE_SCRIPT_FAILURE = -32000
CODE_RESULT_TOO_LARGE = -32003
CODE_INVALID_PARAMS = -32602
CODE_INTERNAL = -32603

# 单次读套接字的缓冲上限，避免对端异常时无限吃内存。
READ_CHUNK = 64 * 1024

# 两次循环检查之间最长的等待：孤儿检测与空闲判断都挂在这个循环上，
# 因此它的上限必须存在且很小，而不能由配置决定（配成 0 也照样要检查）。
MAX_CHECK_INTERVAL = 5.0

# 孤儿看门狗的检查周期（秒）。它必须由**定时器**驱动而不是放在事件循环里，见 _install_orphan_watchdog。
ORPHAN_CHECK_SECONDS = 2.0

# Linux 的 prctl 选项号：父进程死亡时给本进程发一个信号（值来自 linux/prctl.h）。
PR_SET_PDEATHSIG = 1


class _Stopping(object):
    """退出标志。

    信号处理器只能做最少的事，因此它只翻这个标志；真正的收尾在主循环里做。
    """

    def __init__(self):
        self.value = False
        self.reason = None

    def request(self, reason):
        if not self.value:
            self.value = True
            self.reason = reason


def _die_now(script_id, which):
    """收到终止信号：立刻退出，不等主循环。

    **只置一个标志位是不够的**，这一点是实测出来的：脚本可能正卡在自己的代码里
    （``time.sleep(600)`` 之类），而 CPython 在信号处理函数返回后会**恢复**那个被中断的
    系统调用。于是主循环永远轮不到，「父进程没了」也永远查不到——现场是
    gateway 早已退出、这个 worker 却要等到用户代码自己醒来才消失（十分钟量级），
    而日志里没有任何一条指向它。

    发出信号的一方（网关：超时隔离 / 空闲自毁 / 关闭 / 拒绝服务）已经判定不需要
    这个 worker 的任何输出，因此这里直接退出；半行协议帧由网关的脏行容忍吃掉。
    """
    print("[%s] 收到 %s，立即退出" % (script_id, which), file=sys.stderr, flush=True)
    os._exit(0)


def _install_parent_death_signal(script_id):
    """请内核在父进程死亡时直接杀掉自己（仅 Linux）。

    这是「父死子亡」在 Linux 上更彻底的一半：信号由内核在父进程消失的瞬间发出，
    既不依赖 worker 自己醒来检查，也不怕 worker 卡在任何系统调用里。
    定时器看门狗仍然保留——macOS 没有这个设施，而两者并存没有害处：谁先到谁生效，
    重复退出由 ``os._exit`` 幂等。

    **失败只记一行就放过**：非 Linux 平台本来就没有这个设施，那是正常情况而不是错误；
    为一个「锦上添花的内核特性」拒绝服务，会把局部加固变成全局不可用。
    """
    if not sys.platform.startswith("linux"):
        return
    try:
        import ctypes
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(PR_SET_PDEATHSIG, signal.SIGKILL, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "prctl 返回非零")
    except (ImportError, AttributeError, OSError) as error:
        print("[%s] 未能启用父死子亡信号: %s" % (script_id, error), file=sys.stderr, flush=True)
        return
    # 父进程可能在「fork 之后、prctl 之前」就没了：那种情况下信号永远不会来，
    # 必须自己查一次（内核发出的 PDEATHSIG 只覆盖「设置之后」的父进程死亡）
    if os.getppid() == 1:
        print("[%s] 父进程已退出（设置信号前），自行退出" % script_id, file=sys.stderr, flush=True)
        os._exit(0)


def _install_orphan_watchdog(script_id):
    """装上定时器检查，使「父进程没了就退出」不依赖主线程配合。

    worker 的常规退出路径有三条：网关发 SIGTERM、协议套接字读到 EOF、事件循环顶部检查
    ``os.getppid()``。**三条都要求主线程回到事件循环**，而一段卡在用户代码里的脚本
    （这正是最该被清理的情形——它已经让宿主超时了一次）可能十几分钟不回来。
    届时若网关是被强杀的（没人给它发信号），这个 worker 就成了没有任何人知道它存在的孤儿。

    定时器 + 信号处理函数是唯一不依赖主线程配合的手段：即使主线程正卡在 ``time.sleep``
    里，处理函数也会被调用，而 PEP 475 会随后恢复那个调用（脚本不受影响）。
    """
    def _check(*_):
        if os.getppid() == 1:
            print("[%s] 父进程已退出，自行退出" % script_id, file=sys.stderr, flush=True)
            os._exit(0)

    signal.signal(signal.SIGALRM, _check)
    signal.setitimer(signal.ITIMER_REAL, ORPHAN_CHECK_SECONDS, ORPHAN_CHECK_SECONDS)


def serve(sock, script_id, script_dir, entry_name, manifest, strict, idle_seconds):
    """worker 主函数，由网关在 ``fork`` 之后直接调用。

    :param sock: 与网关通信的套接字（已就绪的一侧）
    :param script_id: 脚本标识
    :param script_dir: 脚本目录（绝对路径）
    :param entry_name: 入口文件名（相对脚本目录）
    :param manifest: 网关下发的清单摘要，用于一致性校验
    :param strict: 严格校验失败时是否拒绝服务
    :param idle_seconds: 空闲多久后自行退出（网关也会做同一件事，这里是双保险）
    :return: 退出码
    """
    stopping = _Stopping()
    signal.signal(signal.SIGTERM, lambda *_: _die_now(script_id, "SIGTERM"))
    signal.signal(signal.SIGINT, lambda *_: _die_now(script_id, "SIGINT"))
    _install_parent_death_signal(script_id)
    _install_orphan_watchdog(script_id)

    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)

    try:
        load_script(script_id, script_dir, entry_name)
    except Exception as error:  # noqa: BLE001  任何加载失败都必须变成「拒绝服务」而不是崩溃
        _send(sock, {"id": 0, "ready": False, "error": "脚本加载失败: %s: %s"
                     % (type(error).__name__, error)})
        return 1

    problems = sdk.compare_with(manifest)
    if problems and strict:
        # 严格校验失败：拒绝服务。**不服务的脚本比半服务的脚本安全**——
        # 后者会让模型按一份不存在的工具定义去调用，而失败点分散在每次调用里，
        # 汇总起来才是「这个脚本坏了」。
        _send(sock, {"id": 0, "ready": False,
                     "error": "清单与实现不一致: " + "; ".join(problems)})
        return 1
    if problems:
        print("[%s] 清单与实现不一致（manifestStrict=false，继续服务）: %s"
              % (script_id, "; ".join(problems)), file=sys.stderr, flush=True)

    _send(sock, {"id": 0, "ready": True})

    buffer = b""
    last_used = time.time()
    stopping.value = False
    while not stopping.value:
        if _orphaned():
            stopping.request("orphaned")
            break
        timeout = _remaining(idle_seconds, last_used)
        try:
            ready, _, _ = select.select([sock], [], [], timeout)
        except (OSError, ValueError):
            break
        if not ready:
            continue
        try:
            chunk = sock.recv(READ_CHUNK)
        except OSError:
            break
        if not chunk:
            break
        buffer, frames = wire.feed(buffer, chunk)
        for frame in frames:
            last_used = time.time()
            _handle(sock, script_id, frame)
    return 0


def load_script(script_id, script_dir, entry_name):
    """按文件路径加载入口模块。

    用 ``spec_from_file_location`` 而不是 ``import main``：入口文件常常叫 ``main.py``，
    直接 import 有可能命中 sys.path 上同名的第三方模块，而那种错误的表现是
    「脚本声明的工具全都不见了」，与真正的原因（导入了别人的 main）隔着很远。

    :return: 已加载的模块
    """
    path = os.path.join(script_dir, entry_name)
    if not os.path.isfile(path):
        raise IOError("入口文件不存在: %s" % path)
    module_name = "jellyfish_script_%s" % script_id.replace("-", "_")
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise IOError("无法加载入口文件: %s" % path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    # 脚本目录必须优先于其它路径：它自己的辅助模块不能被同名第三方模块顶掉
    if script_dir in sys.path:
        sys.path.remove(script_dir)
    sys.path.insert(0, script_dir)
    spec.loader.exec_module(module)
    return module


def _handle(sock, script_id, frame):
    """处理网关发来的一帧。

    网关对每个 worker 同时只会有一个在途请求，因此这里不需要处理并发；
    这也是选它的原因：worker 内部因此可以完全是同步的，脚本作者不必考虑线程。
    """
    request_id = frame.get("id")
    method = frame.get("method")
    if method == "event":
        _handle_event(sock, script_id, frame)
        return
    if method != "invoke":
        _send(sock, {"id": request_id,
                     "error": {"code": CODE_INVALID_PARAMS, "message": "不支持的方法: %s" % method}})
        return
    type_name = frame.get("type")
    payload = frame.get("request") or {}
    route_key = _route_key(type_name, payload)
    try:
        result = sdk.invoke(script_id, type_name, route_key, payload,
                            _emitter_for(sock, script_id, payload))
    except sdk.ScriptError as error:
        _send(sock, {"id": request_id, "error": {"code": CODE_SCRIPT_FAILURE, "message": str(error)}})
        return
    except Exception as error:  # noqa: BLE001  脚本里的任何异常都只该让这次调用失败
        import traceback
        traceback.print_exc(file=sys.stderr)
        _send(sock, {"id": request_id,
                     "error": {"code": CODE_SCRIPT_FAILURE,
                               "message": "%s: %s" % (type(error).__name__, error)}})
        return
    _send(sock, {"id": request_id, "result": result})


def _handle_event(sock, script_id, frame):
    """处理一条内核事件。

    **不应答**：事件是通知，没有「回答」这回事，因此这里不产生任何协议帧。
    处理器失败只打 stderr（由网关加 ``[scriptId]`` 前缀进内核日志）——事件是旁路，
    一个坏处理器不该影响任何在途调用，更不该把整个 worker 带走。
    """
    params = frame.get("params") or {}
    name = params.get("event")
    payload = params.get("payload") or {}
    try:
        sdk.invoke(script_id, "event", name, payload, _emitter_for(sock, script_id, payload))
    except Exception as error:  # noqa: BLE001  见上文：事件失败必须就地消化
        import traceback
        print("事件 %s 的处理器失败: %s: %s" % (name, type(error).__name__, error),
              file=sys.stderr, flush=True)
        traceback.print_exc(file=sys.stderr)


def _emitter_for(sock, script_id, payload):
    """构造本次调用的「发布事件」回调。

    会话标识从**当前这次调用**的载荷里取，而不是让脚本自己填：脚本手里没有会话标识
    （那是宿主的概念），而事件必须能归属到会话——否则界面与审计都拿不到上下文。
    """
    session_id = (payload or {}).get("sessionId")

    def emit(name, body):
        event_payload = dict(body or {})
        if session_id is not None:
            event_payload["sessionId"] = session_id
        # 不带 id：这是通知，网关不回答。宿主侧的裁决写日志，不回到脚本
        _send(sock, {"method": "emit_event",
                     "params": {"script": script_id, "event": name, "payload": event_payload}})

    return emit


def _route_key(type_name, payload):
    """从请求载荷里取路由键。

    路由键由请求字段决定而不是协议字段：宿主只下发 ``type`` 与 ``request``，
    因此 tool / command 的名字只能从请求里读。其余扩展点是类型级的，路由键就是类型名。
    """
    if type_name == "tool":
        return payload.get("tool")
    if type_name in ("command", "command_options"):
        return payload.get("command")
    return type_name


def _send(sock, message):
    """发送一帧。失败只记 stderr：对端已经走了，报错也送不出去。"""
    data = wire.encode(message)
    if len(data) > wire.MAX_FRAME_BYTES:
        data = wire.encode({"id": message.get("id"),
                            "error": {"code": CODE_RESULT_TOO_LARGE,
                                      "message": "结果超过传输上限 %d 字节" % wire.MAX_FRAME_BYTES}})
    try:
        sock.sendall(data)
    except OSError as error:
        print("发送协议帧失败: %s" % error, file=sys.stderr, flush=True)


def _remaining(idle_seconds, last_used):
    """计算下一次醒来前的等待时间。

    **即使配成「不回收」也必须周期性醒来**：本循环同时承担孤儿检测（``getppid() == 1``），
    而那个检查只在循环顶部执行。若这里返回 ``None``，``select`` 会永久阻塞，
    父进程被 ``kill -9`` 之后就再没有任何人会发现——**孤儿进程会一直留着**，
    而现象是「机器上悄悄多出一批 python 进程」，没有任何日志指向它。
    这不是理论问题：本函数最初就在「不回收」配置下返回了 ``None``。
    """
    if not idle_seconds:
        return MAX_CHECK_INTERVAL
    wait = idle_seconds - (time.time() - last_used)
    return max(0.1, min(wait, MAX_CHECK_INTERVAL))


def _orphaned():
    """判断父进程是否已经不在。

    ``getppid() == 1`` 说明原父进程已死、自己被 init 收养。macOS 没有
    ``PR_SET_PDEATHSIG``，因此网关被 ``kill -9`` 时只有这条能兜住不泄漏。
    """
    return os.getppid() == 1


def dump_manifest(script_dir, entry_name, script_id):
    """生成清单：把代码里的声明还原成 ``manifest.json``。

    生成器是「清单与实现一致性」这条约束的配套工具：既然一致性由代码保证不了，
    至少要让作者不必手写清单——手写的那份必然会在某次改动后忘记同步。
    """
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)
    load_script(script_id or "manifest", script_dir, entry_name)
    return sdk.dump_manifest(script_id, entry_name)


def main(argv):
    """命令行入口：只提供清单生成，worker 本体由网关 ``fork`` 调用。"""
    if "--dump-manifest" not in argv:
        print("用法: worker.py --dump-manifest <scriptDir> [--entry main.py] [--id <scriptId>]",
              file=sys.stderr)
        return 2
    script_dir = None
    entry_name = "main.py"
    script_id = None
    index = argv.index("--dump-manifest") + 1
    if index < len(argv):
        script_dir = argv[index]
    if "--entry" in argv:
        entry_name = argv[argv.index("--entry") + 1]
    if "--id" in argv:
        script_id = argv[argv.index("--id") + 1]
    if not script_dir:
        print("缺少脚本目录", file=sys.stderr)
        return 2
    manifest = dump_manifest(os.path.abspath(script_dir), entry_name, script_id)
    print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
