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
  因此这条兜底是必需的，不是可选的。
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
    signal.signal(signal.SIGTERM, lambda *_: stopping.request("term"))
    signal.signal(signal.SIGINT, lambda *_: stopping.request("int"))

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
    if method != "invoke":
        _send(sock, {"id": request_id,
                     "error": {"code": CODE_INVALID_PARAMS, "message": "不支持的方法: %s" % method}})
        return
    type_name = frame.get("type")
    payload = frame.get("request") or {}
    route_key = _route_key(type_name, payload)
    try:
        result = sdk.invoke(script_id, type_name, route_key, payload)
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
