#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""脚本网关：控制面。

**唯一的常驻进程**：它只与宿主说话、只做路由，**从不 import 任何业务脚本**。
因此「脚本把网关拖垮」这条路径不存在，整门语言不可用只剩「这个瘦进程自己挂了」一种原因。

它是**单线程**的，全部并发由一次 ``select`` 多路复用解决：

* 宿主 stdin（协议帧）、宿主 stdout（应答）；
* 每个 worker 的套接字（数据面）；
* 每个 worker 的日志管道（stderr/stdout 转进宿主日志）；
* 一个唤醒管道（信号：``SIGTERM`` / ``SIGINT`` / ``SIGCHLD``）。

**为什么坚持单线程**：``fork`` 在多线程进程里是不安全的（3.12 起会告警，未来默认改 spawn），
而方案要求「worker 在没有任何线程时被 fork」。只要网关自己不存在线程，这条约束就恒真——
包括「worker 空闲自毁后重新拉起」这种方案原文没覆盖的时刻。代价是网关必须自己做事件循环，
收益是它再也不需要小心「哪一步之前不能起线程」。

**它不做的事**：不读消息正文、不发起模型调用、不解释业务语义。所有请求都是
「宿主说 type+请求载荷」→「worker 说结果」的搬运。
"""

import errno
import json
import os
import select
import signal
import socket
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import script_wire as wire  # noqa: E402
import worker  # noqa: E402  同目录的 worker 模块，fork 后直接调用

# 协议错误码：与宿主侧 ScriptProtocol 的取值逐字一致。
CODE_SCRIPT_FAILURE = -32000
CODE_UNKNOWN_SCRIPT = -32002
CODE_RESULT_TOO_LARGE = -32003
CODE_MANIFEST_MISMATCH = -32004
CODE_METHOD_NOT_FOUND = -32601
CODE_INVALID_PARAMS = -32602
CODE_INTERNAL = -32603

# 杀 worker 的两段式等待（秒）：先请它走，到点再强杀。与宿主的关闭路径同一套口径。
KILL_GRACE_SECONDS = 2.0
KILL_FORCE_SECONDS = 2.0

# 读缓冲块大小。
READ_CHUNK = 64 * 1024

# 事件循环里一次等待的上限：即使没有 I/O，也要周期性检查各种截止时间。
MAX_WAIT = 1.0

# 两次拉起之间至少间隔多久：用于「一起来就崩」的脚本，避免被打成无限重启循环。
SPAWN_INTERVAL_SECONDS = 1.0

# 初始化至少给这么久的宽限：它要 fork 每个 worker 并等它们 import 完用户脚本，
# 而「单次调用超时」是给一次业务执行的值（可能被配成几秒）。
# **必须比宿主的初始化等待上限短**：否则宿主机先超时，用户只能看到「初始化没回应」，
# 而真正有用的信息（哪个脚本、差在哪个扩展点）本来就在网关手里
MIN_INITIALIZE_SECONDS = 10.0


class Worker(object):
    """一个正在运行的 worker 进程。

    **它不持有请求队列**：队列属于脚本，而不是属于某一代 worker。worker 会被
    空闲回收、被超时隔离、会崩溃，而队列里那些「还没开始执行」的请求不该跟着一起消失——
    它们会在新一代 worker 就绪后继续被派发。
    """

    def __init__(self, pid, sock, log_fd):
        self.pid = pid
        self.sock = sock
        self.log_fd = log_fd
        self.buffer = b""
        self.log_buffer = b""
        self.ready = False
        self.last_used = time.time()
        self.kill_at = None
        self.force_kill_at = None


class ScriptState(object):
    """一个脚本的长期状态。

    **与 worker 分开**：worker 会反复死亡与重建（空闲自毁、崩溃、超时隔离），
    而「这个脚本的清单是什么」与「它是不是已经被判定为不一致」不该跟着进程一起丢掉。
    尤其后者：清单不一致的脚本必须**永久拒绝服务**，否则每次调用都会重新 fork 一个
    注定失败的 worker，形成安静的无限重启。
    """

    def __init__(self, spec):
        self.spec = spec
        self.refusal = None
        self.worker = None
        # 待派发的宿请求（FIFO）与已派发、等回答的那一个。放在脚本这一层，
        # 意味着「换一代 worker」对排队中的请求是透明的
        self.queue = []
        self.inflight = None
        self.last_spawn = 0.0

    @property
    def script_id(self):
        return self.spec["id"]

    @property
    def manifest(self):
        return self.spec.get("manifest") or {}


class Gateway(object):
    """事件循环与全部状态。"""

    def __init__(self):
        self.states = {}
        self.running = True
        self.exit_reason = None
        self.stdin_buffer = b""
        self.pending_ready = set()
        self.pending_init = None
        self.last_busy = time.time()
        self.settings = {}
        self.wakeup_r = None
        self.wakeup_w = None
        self.seq = 0
        self.fd_map = {}

    # ------------------------------------------------------------ 生命周期

    def run(self):
        """进入事件循环。"""
        self._install_signals()
        while self.running:
            self._expire()
            self._pump_all()
            self._reap()
            self._check_idle()
            self._wait()
        self._shutdown()
        return 0

    def _install_signals(self):
        """装信号处理：只翻标志，实际动作留给循环。"""
        self.wakeup_r, self.wakeup_w = os.pipe()
        # 两端都设非阻塞：读端若阻塞，排空唤醒管道时会把整个事件循环卡住
        os.set_blocking(self.wakeup_r, False)
        os.set_blocking(self.wakeup_w, False)
        signal.set_wakeup_fd(self.wakeup_w)
        for name in ("SIGTERM", "SIGINT", "SIGHUP", "SIGCHLD"):
            if hasattr(signal, name):
                signal.signal(getattr(signal, name), self._on_signal)

    def _on_signal(self, number, _frame):
        """信号处理器：只记录退出原因。"""
        if number == getattr(signal, "SIGCHLD", None):
            return
        self.running = False
        self.exit_reason = "signal %d" % number

    def _shutdown(self):
        """收尾：杀掉全部 worker 再退出。

        worker 是网关的子进程，因此这一步必须由网关做——宿主不知道 worker 的存在，
        也不该知道（``waitpid`` 回收、PID 表维护都留在有父子关系的进程里）。
        """
        for state in list(self.states.values()):
            self._kill_worker(state, "网关退出")
        deadline = time.time() + KILL_GRACE_SECONDS + KILL_FORCE_SECONDS
        while self._alive_workers() and time.time() < deadline:
            self._escalate()
            time.sleep(0.05)
            self._reap()
        for state in self.states.values():
            self._close_worker(state)
        self._log("网关退出: %s" % (self.exit_reason or "正常关闭"))

    # ------------------------------------------------------------ 事件循环

    def _wait(self):
        """等待可读事件，顺带把唤醒管道排掉。"""
        # fd → 处置动作。用映射而不是事后比对 fileno()：套接字一旦关闭，
        # fileno() 返回 -1，而 -1 会与另一个已关闭的 fd 撞上，表现是「读错了 worker」
        self.fd_map = {0: lambda _fd: self._read_java(),
                       self.wakeup_r: self._drain}
        readers = list(self.fd_map.keys())
        for state in self.states.values():
            current = state.worker
            if current is None or current.kill_at is not None:
                continue
            self.fd_map[current.sock] = lambda _fd, s=state: self._read_worker(s)
            readers.append(current.sock)
            if current.log_fd is not None:
                self.fd_map[current.log_fd] = lambda _fd, s=state: self._read_worker_log(s)
                readers.append(current.log_fd)
        try:
            ready, _, _ = select.select(readers, [], [], MAX_WAIT)
        except OSError as error:
            if error.errno == errno.EINTR:
                return
            self.running = False
            self.exit_reason = "select 失败: %s" % error
            return
        for item in ready:
            action = self.fd_map.get(item)
            if action is not None:
                action(item)

    def _drain(self, fd):
        """把唤醒管道里的字节读干净，否则它会一直可读、循环变成忙等。"""
        try:
            while os.read(fd, 4096):
                pass
        except OSError:
            pass

    def _read_java(self):
        """读宿主的协议帧。"""
        try:
            chunk = os.read(0, READ_CHUNK)
        except OSError as error:
            if error.errno == errno.EINTR:
                return
            self.running = False
            self.exit_reason = "读宿主 stdin 失败: %s" % error
            return
        if not chunk:
            # stdin 关闭 = 宿主走了。没有别的可能，因此直接退场，由收尾逻辑杀掉 worker
            self.running = False
            self.exit_reason = "宿主已关闭 stdin"
            return
        self.stdin_buffer, frames = wire.feed(self.stdin_buffer, chunk)
        for frame in frames:
            self._handle_java_frame(frame)

    def _read_worker(self, state):
        """读 worker 的协议帧。"""
        current = state.worker
        if current is None:
            return
        try:
            chunk = current.sock.recv(READ_CHUNK)
        except OSError:
            chunk = b""
        if not chunk:
            self._worker_gone(state, "worker 已断开连接")
            return
        current.buffer, frames = wire.feed(current.buffer, chunk)
        for frame in frames:
            self._handle_worker_frame(state, frame)

    def _read_worker_log(self, state):
        """把 worker 的 stdout/stderr 转进网关自己的 stderr。

        带脚本标识前缀：多个脚本同时说话时，「谁说的」是排查时第一个要回答的问题，
        而丢掉这个信息只需要省掉拼接这一下。
        """
        current = state.worker
        if current is None or current.log_fd is None:
            # 同一批可读事件里，套接字事件可能已经把 worker 判定为消失并关掉了日志管道，
            # 而管道事件仍在本批里等着处理
            return
        try:
            chunk = os.read(current.log_fd, READ_CHUNK)
        except OSError:
            chunk = b""
        if not chunk:
            try:
                os.close(current.log_fd)
            except OSError:
                pass
            current.log_fd = None
            return
        current.log_buffer, lines = wire.take_lines(current.log_buffer, chunk)
        for line in lines:
            self._log("[%s] %s" % (state.script_id, line))

    def _pump_all(self):
        """给需要 worker 的脚本补一个，并派发可派发的请求。"""
        now = time.time()
        for state in self.states.values():
            # 队列非空却没有 worker：说明上一个 worker 已经退场，而请求还等着。
            # 不能等到「下一次调用」才补——那会让这次请求永远没人回答。
            # 速率限制是防止「一起来就崩」的脚本被打成无限重启循环
            if state.queue and state.worker is None and state.refusal is None \
                    and now - state.last_spawn >= SPAWN_INTERVAL_SECONDS:
                self._spawn(state)
            self._pump(state)

    def _pump(self, state):
        """派发 worker 当前队首的请求（每个 worker 同时只有一个在途请求）。

        串行化的理由：worker 内部因此可以完全同步，脚本作者不必考虑并发；
        代价是同一脚本的并发调用会排队，而排队比「脚本里到处是竞态」便宜得多。
        """
        current = state.worker
        if current is None or not current.ready or state.inflight is not None:
            return
        if not state.queue:
            return

        # 取出而不是只读队首：留在队列里会让它被反复派发，
        # 表现是同一个请求被脚本执行 N 次，而队列后面的请求永远轮不到
        pending = state.queue.pop(0)
        state.inflight = pending
        current.last_used = time.time()
        # 截止时间从「派发」起算而不是「到达」起算：排队等待不是脚本的错，
        # 而超时的语义是「脚本执行太久」。真要排太久的队，前一个请求的超时
        # 会先把整个 worker 杀掉，队列里的人也一起拿到明确失败
        pending["deadline"] = time.time() + self.settings["invokeTimeoutSeconds"]
        data = wire.encode({"id": pending["seq"], "method": "invoke",
                            "type": pending["type"], "request": pending["request"]})
        try:
            current.sock.sendall(data)
        except OSError as error:
            self._worker_gone(state, "向 worker 发送请求失败: %s" % error)

    def _expire(self):
        """检查各种截止时间：请求超时、初始化超时、worker 空闲、网关空闲。"""
        now = time.time()
        for state in list(self.states.values()):
            current = state.worker
            if current is not None:
                if current.kill_at is None and state.inflight is not None \
                        and now > state.inflight["deadline"]:
                    timeout = self.settings["invokeTimeoutSeconds"]
                    self._log("[%s] 请求超时（%ss），隔离该 worker" % (state.script_id, timeout))
                    # 只失败在途的那一个：它可能已经在脚本里跑了一半，重试会重复执行。
                    # 排队中的请求从未开始，因此留着给下一代 worker——那才是「重新拉起」的完整含义
                    self._fail_inflight(state, CODE_SCRIPT_FAILURE,
                                        "脚本 %s 已因超时被隔离（等待超过 %ss）"
                                        % (state.script_id, timeout))
                    self._kill_worker(state, "请求超时")
            self._expire_waiting(state, now)
        self._expire_init(now)

    def _expire_init(self, now):
        """初始化超时：等不到的脚本按失败上报，并让它转成「永久拒绝服务」。

        这一条是必需的兜底：worker 起不来时若只是继续等，宿主会先超时，
        而宿主只知道「初始化没回应」，不知道是哪个脚本卡住了。
        """
        if self.pending_init is None:
            return
        request_id, deadline = self.pending_init
        done = not self.pending_ready
        # 截止时间为 None 表示「不超时」（配置成 0 的语义），此时只等就绪上报
        if not done and (deadline is None or now <= deadline):
            return
        results = []
        for state in sorted(self.states.values(), key=lambda item: item.script_id):
            if state.script_id in self.pending_ready:
                state.refusal = state.refusal or "worker 未在超时前就绪"
                self._kill_worker(state, "初始化超时")
            # **每个脚本都要出现在结果里**：只在失败时上报的话，「worker 一起来就崩」
            # 的脚本会静默消失，而宿主看到的是「清单里有它、初始化结果里没有它」——
            # 那正是最难判断的一种不一致
            results.append({"script": state.script_id,
                            "ok": state.refusal is None,
                            "error": state.refusal})
        self.pending_ready = set()
        self.pending_init = None
        self._reply(request_id, {"scripts": results})
        self.last_busy = time.time()

    def _expire_waiting(self, state, now):
        """检查「还没派发出去」的请求是否等太久。

        它们本不该超时：正常情况下队首会被派发，超时由在途的那一个触发。真正会走到这里的
        只有一种情形——worker 反复起不来。此时不能让请求无限等下去，
        否则用户看到的是「界面卡住」，而没有任何错误可以归因。
        """
        for pending in list(state.queue):
            deadline = pending.get("wait_deadline")
            if deadline and now > deadline:
                state.queue.remove(pending)
                self._reply_error(pending["java_id"], CODE_SCRIPT_FAILURE,
                                  "脚本 %s 的 worker 迟迟无法就绪，请求已放弃" % state.script_id)

    def _check_idle(self):
        """空闲自毁。

        两件事分开判断：worker 空闲了多少（各自算），以及「一个 worker 都没有」
        持续了多久（网关自己算）。后者是懒启动的必备配套——懒启动但不退场，
        等于只懒一次，而用户看到的是「跑过一次之后就一直占着内存」。
        """
        now = time.time()
        worker_idle = self.settings.get("workerIdleSeconds") or 0
        if worker_idle:
            for state in list(self.states.values()):
                current = state.worker
                if current is None or current.kill_at is not None:
                    continue
                if state.inflight is None and not state.queue and current.ready:
                    if now - current.last_used > worker_idle:
                        self._log("[%s] 空闲 %ss，worker 退场" % (state.script_id, worker_idle))
                        self._kill_worker(state, "空闲自毁")

        gateway_idle = self.settings.get("gatewayIdleSeconds") or 0
        if gateway_idle and not self._alive_workers() and not self._has_pending_requests():
            if now - self.last_busy > gateway_idle:
                self.running = False
                self.exit_reason = "空闲 %ss" % gateway_idle

    def _has_pending_requests(self):
        """是否还有没答复宿主的请求（有就不能算空闲）。"""
        if self.pending_init is not None:
            return True
        for state in self.states.values():
            current = state.worker
            if current is not None and (state.queue or state.inflight is not None):
                return True
        return False

    def _reap(self):
        """回收退出的子进程。

        **必须做，否则留僵尸**：子进程退出后在父进程 ``waitpid`` 之前一直是僵尸，
        worker 反复起落会累积出一片僵尸进程，而它们的表项会占住 PID 空间。
        """
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except OSError:
                return
            if pid == 0:
                return
            for state in self.states.values():
                current = state.worker
                if current is not None and current.pid == pid:
                    self._worker_gone(state, "worker 进程已退出（pid=%d）" % pid)

    # ------------------------------------------------------------ 宿主请求

    def _handle_java_frame(self, frame):
        """处理宿主发来的请求或通知。"""
        if "id" not in frame:
            self._handle_java_notification(frame)
            return
        request_id = frame["id"]
        method = frame.get("method")
        params = frame.get("params") or {}
        self.last_busy = time.time()
        if method == "initialize":
            self._initialize(request_id, params)
        elif method == "invoke":
            self._invoke(request_id, params)
        elif method == "kill_worker":
            self._kill_worker_request(request_id, params)
        elif method == "shutdown":
            self._reply(request_id, {})
            self.running = False
            self.exit_reason = "宿主请求关闭"
        else:
            self._reply_error(request_id, CODE_METHOD_NOT_FOUND, "不支持的方法: %s" % method)

    def _handle_java_notification(self, frame):
        """处理通知。事件桥接尚未接通，因此只记一条日志。"""
        self._log("收到通知 %s（事件桥接尚未接通，已忽略）" % frame.get("method"))

    def _initialize(self, request_id, params):
        """下发脚本清单并为每个脚本拉起 worker。"""
        self.settings = params.get("settings") or {}
        self.settings.setdefault("invokeTimeoutSeconds", 30)
        self.settings.setdefault("workerIdleSeconds", 300)
        self.settings.setdefault("gatewayIdleSeconds", 600)
        self.settings.setdefault("manifestStrict", True)
        specs = params.get("scripts") or []
        for spec in specs:
            state = ScriptState(spec)
            self.states[spec["id"]] = state
            self.pending_ready.add(spec["id"])
        # 初始化阶段一次性拉起全部 worker：清单与实现的一致性校验必须发生在
        # 「第一次真正用到之前」，否则那份不一致会以「某个工具永远失败」的形式
        # 分散暴露到每一次调用里
        for spec in specs:
            self._spawn(self.states[spec["id"]])
        if not specs:
            self._reply(request_id, {"scripts": []})
            return
        timeout = self.settings["invokeTimeoutSeconds"]
        if timeout:
            grace = max(MIN_INITIALIZE_SECONDS, timeout)
            self.pending_init = (request_id, time.time() + grace)
        else:
            self.pending_init = (request_id, None)

    def _invoke(self, request_id, params):
        """转发一次扩展点调用。"""
        script_id = params.get("script")
        state = self.states.get(script_id)
        if state is None:
            self._reply_error(request_id, CODE_UNKNOWN_SCRIPT, "脚本不存在: %s" % script_id)
            return
        if state.refusal is not None:
            self._reply_error(request_id, CODE_MANIFEST_MISMATCH,
                              "脚本 %s 已拒绝服务: %s" % (script_id, state.refusal))
            return
        current = state.worker
        if current is not None and current.kill_at is not None:
            # 正在被隔离的 worker 不再接新请求：把它留着只会让新请求一起陪葬，
            # 而「崩溃/被隔离后重新拉起」本来就是这套模型的既定能力
            self._close_worker(state)
        if state.worker is None:
            self._spawn(state)
            if state.worker is None:
                self._reply_error(request_id, CODE_INTERNAL, "脚本 %s 的 worker 无法启动" % script_id)
                return
        current = state.worker
        timeout = self.settings["invokeTimeoutSeconds"]
        state.queue.append({"seq": self._next_seq(), "java_id": request_id,
                            "type": params.get("type"), "request": params.get("request"),
                            "deadline": 0,
                            # 排队等待的上限：只用于「worker 迟迟起不来」这一种情形，
                            # 派发时会被换成真正的执行截止时间
                            "wait_deadline": (time.time() + timeout) if timeout else None})
        self._pump(state)

    def _kill_worker_request(self, request_id, params):
        """宿主请求隔离某个脚本的 worker。

        超时处置链里「谁来杀」的答案就是这里：宿主只发指令，**杀的动作由网关做**，
        因为回收与 PID 表都在有父子关系的一侧，宿主不必知道 worker 的存在。
        """
        state = self.states.get(params.get("script"))
        if state is None:
            self._reply(request_id, {"killed": False})
            return
        reason = params.get("reason") or "宿主请求"
        killed = self._kill_worker(state, reason)
        self._fail_inflight(state, CODE_SCRIPT_FAILURE,
                            "脚本 %s 已被隔离: %s" % (state.script_id, reason))
        self._reply(request_id, {"killed": killed})

    def _next_seq(self):
        """取下一个 worker 请求序号（每个 worker 同时只有一个在途，因此单调即可）。"""
        self.seq += 1
        return self.seq

    # ------------------------------------------------------------ worker 请求

    def _handle_worker_frame(self, state, frame):
        """处理 worker 发来的帧。"""
        if "ready" in frame:
            self._handle_ready(state, frame)
            return
        current = state.worker
        if current is None or state.inflight is None:
            return
        pending = state.inflight
        state.inflight = None
        current.last_used = time.time()
        if "error" in frame:
            error = frame["error"] or {}
            self._reply_error(pending["java_id"], error.get("code", CODE_SCRIPT_FAILURE),
                              error.get("message") or "脚本执行失败")
        else:
            payload = frame.get("result")
            encoded = wire.encode(payload) if payload is not None else b"null"
            if len(encoded) > wire.MAX_FRAME_BYTES:
                self._reply_error(pending["java_id"], CODE_RESULT_TOO_LARGE,
                                  "脚本 %s 的结果超过传输上限 %d 字节"
                                  % (state.script_id, wire.MAX_FRAME_BYTES))
            else:
                self._reply(pending["java_id"], payload)
        self.last_busy = time.time()
        self._pump(state)

    def _handle_ready(self, state, frame):
        """处理 worker 的就绪上报。"""
        self.pending_ready.discard(state.script_id)
        current = state.worker
        if not frame.get("ready"):
            reason = frame.get("error") or "worker 未说明原因"
            state.refusal = reason
            self._log("[%s] 拒绝服务: %s" % (state.script_id, reason))
            self._kill_worker(state, "拒绝服务")
            # 拒绝服务的脚本不该再留着队列：它永远不会服务，而队列会触发无谓的重启
            self._fail_queued(state, CODE_MANIFEST_MISMATCH,
                              "脚本 %s 拒绝服务: %s" % (state.script_id, reason))
            self._notify_worker_state(state, "refused", False)
            return
        if current is not None:
            current.ready = True
            current.last_used = time.time()
        self._notify_worker_state(state, "ready", True)
        self._pump(state)

    def _worker_gone(self, state, reason):
        """worker 消失后的统一处置。"""
        current = state.worker
        if current is None:
            return
        self._log("[%s] %s" % (state.script_id, reason))
        deliberate = current.kill_at is not None
        self._fail_inflight(state, CODE_SCRIPT_FAILURE,
                            "脚本 %s 的 worker 已退出: %s" % (state.script_id, reason))
        if not deliberate:
            # 崩溃（或者说「没被要求退场就没了」）：无法知道在途请求执行到哪一步，
            # 排队中的请求也本该由这一代 worker 依次执行，因此一起失败，让调用方明确重试。
            # 反过来，**被要求退场**（超时隔离、空闲自毁、重启）时排队中的请求从未开始，
            # 留着给下一代 worker 才是「重新拉起」的完整含义
            self._fail_queued(state, CODE_SCRIPT_FAILURE,
                              "脚本 %s 的 worker 已退出: %s" % (state.script_id, reason))
        elif state.queue:
            self._log("[%s] 保留 %d 个排队请求，等待重新拉起 worker"
                      % (state.script_id, len(state.queue)))
        state.worker = None
        self._close_worker_fds(current)
        if state.script_id in self.pending_ready:
            self.pending_ready.discard(state.script_id)
            state.refusal = state.refusal or reason
        self._notify_worker_state(state, "exited", False)
        self._pump(state)

    # ------------------------------------------------------------ worker 管理

    def _spawn(self, state):
        """fork 一个 worker。

        **``os.fork()`` 在这里永远安全**，因为本进程没有任何线程：这是「网关单线程」
        这个选择买到的东西——包括 worker 空闲自毁之后重新拉起，也不再需要第二条拉起路径。
        """
        if state.worker is not None or state.refusal is not None:
            return
        spec = state.spec
        parent_sock, child_sock = socket.socketpair()
        log_r, log_w = os.pipe()
        pid = os.fork()
        if pid == 0:
            # 子进程：只留自己的通信与日志描述符，其余全部关掉。
            # 尤其必须关掉网关与宿主之间的 stdio，否则脚本一次误 print 就会
            # 往协议流里插一行，而宿主看到的是一帧无法识别的消息
            parent_sock.close()
            os.close(log_r)
            exit_code = 1
            try:
                self._child_setup(child_sock.fileno(), log_w)
                exit_code = worker.serve(child_sock, spec["id"], spec["directory"],
                                         os.path.basename(spec["entry"]),
                                         state.manifest,
                                         bool(self.settings.get("manifestStrict", True)),
                                         self.settings.get("workerIdleSeconds") or 0)
            except BaseException as error:  # noqa: BLE001  子进程绝不能把异常带进网关的栈
                print("worker 异常退出: %s: %s" % (type(error).__name__, error),
                      file=sys.stderr, flush=True)
            finally:
                # 用 _exit 而不是 sys.exit：不能跑从网关继承来的任何收尾逻辑，
                # 那些逻辑会把网关的其它 worker 一起杀掉
                os._exit(exit_code)
        child_sock.close()
        os.close(log_w)
        state.worker = Worker(pid, parent_sock, log_r)
        state.last_spawn = time.time()
        self._log("[%s] worker 已启动: pid=%d" % (spec["id"], pid))

    def _child_setup(self, sock_fd, log_w):
        """子进程的 fd 整理：0 指向 /dev/null，1/2 指向日志管道，其余关闭。"""
        devnull = os.open(os.devnull, os.O_RDONLY)
        os.dup2(devnull, 0)
        os.dup2(log_w, 1)
        os.dup2(log_w, 2)
        _close_except(set([sock_fd]))

    def _kill_worker(self, state, reason):
        """请求 worker 退出，返回是否发出了信号。"""
        current = state.worker
        if current is None or current.kill_at is not None:
            return False
        current.kill_at = time.time() + KILL_GRACE_SECONDS
        current.force_kill_at = current.kill_at + KILL_FORCE_SECONDS
        current.ready = False
        try:
            os.kill(current.pid, signal.SIGTERM)
        except OSError:
            self._worker_gone(state, "worker 已不存在")
            return False
        self._log("[%s] 请求 worker 退出: %s" % (state.script_id, reason))
        return True

    def _escalate(self):
        """两段式关闭的第二步：到点还没走就强杀。"""
        now = time.time()
        for state in self.states.values():
            current = state.worker
            if current is None or current.kill_at is None:
                continue
            if current.force_kill_at is not None and now > current.force_kill_at:
                self._log("[%s] worker 未响应终止信号，强杀: pid=%d" % (state.script_id, current.pid))
                current.force_kill_at = None
                try:
                    os.kill(current.pid, signal.SIGKILL)
                except OSError:
                    self._worker_gone(state, "worker 已不存在")

    def _close_worker(self, state):
        """关闭并丢弃 worker 的连接。"""
        current = state.worker
        if current is not None:
            self._close_worker_fds(current)
            state.worker = None

    def _close_worker_fds(self, current):
        """关闭 worker 的套接字与日志管道。"""
        try:
            current.sock.close()
        except OSError:
            pass
        if current.log_fd is not None:
            try:
                os.close(current.log_fd)
            except OSError:
                pass
            current.log_fd = None

    def _alive_workers(self):
        """存活的 worker 数。"""
        return sum(1 for state in self.states.values() if state.worker is not None)

    def _fail_inflight(self, state, code, message):
        """失败在途的请求（它可能已经执行了一半，因此不重试）。

        必须做：worker 已经没了或已被隔离，在途的请求永远不会有人回答，
        而宿主会一直等到自己的超时——那是拿一个超时周期换取一条本来就能立刻给出的错误。
        """
        pending = state.inflight
        if pending is None:
            return
        state.inflight = None
        self._reply_error(pending["java_id"], code, message)

    def _fail_queued(self, state, code, message):
        """失败尚未派发的请求。"""
        pending_items = list(state.queue)
        state.queue = []
        for pending in pending_items:
            self._reply_error(pending["java_id"], code, message)

    # ------------------------------------------------------------ 与宿主通信

    def _reply(self, request_id, result):
        """回一个成功应答。"""
        self._write_java({"jsonrpc": "2.0", "id": request_id, "result": result})

    def _reply_error(self, request_id, code, message):
        """回一个失败应答。

        错误必须走协议的错误对象：塞进 result 里会被调用方当成正常输出，
        对工具调用而言就是「脚本说没有内容」，语义静默错位。
        """
        self._write_java({"jsonrpc": "2.0", "id": request_id,
                          "error": {"code": code, "message": message}})

    def _write_java(self, message):
        """把一帧写进宿主 stdout，并更新「最后一次忙碌」时间。"""
        data = wire.encode(message)
        if len(data) > wire.MAX_FRAME_BYTES:
            data = wire.encode({"jsonrpc": "2.0", "id": message.get("id"),
                                "error": {"code": CODE_RESULT_TOO_LARGE,
                                          "message": "网关应答超过传输上限"}})
        try:
            _write_all(1, data)
        except OSError as error:
            self.running = False
            self.exit_reason = "写宿主 stdout 失败: %s" % error
        self.last_busy = time.time()

    def _notify_worker_state(self, state, name, alive):
        """上报 worker 状态变化（通知，不需要宿主应答）。

        宿主拿到它只用于诊断与日志：它不改变任何判断，因此丢掉也无所谓——
        这正是「事件可丢」的一个正当用例。
        """
        self._write_java({"jsonrpc": "2.0", "method": "worker_state",
                          "params": {"script": state.script_id, "state": name,
                                     "alive": alive, "started": state.worker is not None}})

    def _log(self, text):
        """写网关自己的诊断日志：走 stderr，不碰协议流。"""
        try:
            sys.stderr.write("[gateway] %s\n" % text)
            sys.stderr.flush()
        except (OSError, ValueError):
            pass


def _write_all(fd, data):
    """把数据完整写出。

    ``os.write`` 可能只写一部分（管道缓冲区满时会短写），而**半帧 JSON 比丢帧更糟**：
    对端会把它和下一帧粘在一起，报出来的是「JSON 解析失败」而不是「写不下」。
    """
    view = memoryview(data)
    while view:
        written = os.write(fd, view)
        view = view[written:]


def _close_except(keep):
    """关掉除 ``keep`` 之外的全部描述符。

    逐个 ``os.close`` 而不是靠 close-on-exec：``fork`` 不执行 exec，
    因此 ``FD_CLOEXEC`` 在这里一点用都没有，而残留的描述符会让
    「宿主死了」这件事在 worker 一侧永远等不到 EOF。
    """
    try:
        max_fd = os.sysconf("SC_OPEN_MAX")
    except (ValueError, OSError):
        max_fd = 4096
    keep = sorted(fd for fd in keep if fd >= 3)
    start = 3
    for fd in keep:
        if fd > start:
            os.closerange(start, fd)
        start = fd + 1
    os.closerange(start, max_fd)


def main(_argv):
    """命令行入口：网关不需要任何参数，配置由宿主的 ``initialize`` 下发。"""
    return Gateway().run()


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(0)
