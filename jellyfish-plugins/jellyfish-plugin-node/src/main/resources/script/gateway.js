'use strict';

/**
 * 脚本网关：控制面（Node 版）。
 *
 * **唯一的常驻进程**：它只与宿主说话、只做路由，**从不 require 任何业务脚本**。
 * 因此「脚本把网关拖垮」这条路径不存在，整门语言不可用只剩「这个瘦进程自己挂了」一种原因。
 *
 * **它是单线程的**，而且是天然单线程：Node 的事件循环就是这套设计里 Python 版手写的那件事。
 * Python 版为了让「fork 时没有线程」恒真，特意把网关写成单线程 `select` 循环；
 * Node 这边不需要任何变通——除了脚本自己显式创建 worker_threads，进程里本来就没有第二条线程。
 *
 * **与 Python 版的结构差异只有进程拉起方式**：Python 用 `fork`（子进程继承内存里的脚本目录、
 * 入口与清单），Node 用 `spawn` + 一条 `init` 帧把这些送过去。顺带白拿两条性质：
 * 子进程不会继承网关与宿主之间的 stdin/stdout（Python 要靠 `dup2` 手工保证），
 * 子进程退出由 libuv 自动回收（Python 要手工 `waitpid`，不然攒一地僵尸）。
 *
 * **它不做的事**：不读消息正文、不发起模型调用、不解释业务语义。所有请求都是
 * 「宿主说 type+请求载荷」→「worker 说结果」的搬运。
 */

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const wire = require('./script_wire');

/** 协议错误码：与宿主侧 ScriptProtocol 的取值逐字一致。 */
const CODE_SCRIPT_FAILURE = -32000;
const CODE_UNKNOWN_SCRIPT = -32002;
const CODE_RESULT_TOO_LARGE = -32003;
const CODE_MANIFEST_MISMATCH = -32004;
const CODE_METHOD_NOT_FOUND = -32601;
const CODE_INTERNAL = -32603;

/** 杀 worker 的两段式等待（毫秒）：先请它走，到点再强杀。与宿主的关闭路径同一套口径。 */
const KILL_GRACE_MS = 2000;
const KILL_FORCE_MS = 2000;

/** 事件循环里一次等待的上限：即使没有 I/O，也要周期性检查各种截止时间。 */
const TICK_MS = 1000;

/** 两次拉起之间至少间隔多久：用于「一起来就崩」的脚本，避免被打成无限重启循环。 */
const SPAWN_INTERVAL_MS = 1000;

/**
 * 初始化至少给这么久的宽限：它要拉起每个 worker 并等它们加载完用户脚本，
 * 而「单次调用超时」是给一次业务执行的值（可能被配成几秒）。
 * **必须比宿主的初始化等待上限短**：否则宿主机先超时，用户只能看到「初始化没回应」，
 * 而真正有用的信息（哪个脚本、差在哪个扩展点）本来就在网关手里。
 */
const MIN_INITIALIZE_MS = 10000;

/**
 * 回声记忆条数：脚本刚发布出去的事件不再推回给同一个脚本（脚本收到自己发的事件就是环）。
 * 有界是必须的——它按条数而不是时间过期，因为「事件还在飞」这件事没有可观测的终点。
 */
const ECHO_MEMORY = 256;

/** 网关自己的目录：worker 与 SDK 都在这里，也是给脚本解析 `require('jellyfish_sdk')` 的搜索路径。 */
const HERE = __dirname;

/**
 * 把标准输出改成同步写入。
 *
 * 网关的 stdout 就是给宿主的协议通道，而 Node 对管道的 stdout 是**异步缓冲**的：
 * 一个几千字节的应答可能在写到一半时被打断，插进去的是一行 `console.log`。
 * 那种破坏的表现是「宿主偶尔收到一帧解析不了」，且只在结果大、日志多时出现。
 * 统一走同步写入之后，两者共享同一条串行路径。
 */
process.stdout.write = function writeSync(chunk, encoding) {
    fs.writeSync(1, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding || 'utf8'));
    return true;
};

/**
 * 把数据完整写到某个描述符。
 *
 * `fs.writeSync` 可能只写一部分（管道缓冲区满时会短写），而**半帧 JSON 比丢帧更糟**：
 * 对端会把它和下一帧粘在一起，报出来的是「JSON 解析失败」而不是「写不下」。
 *
 * @param {number} fd 描述符
 * @param {Buffer} data 数据
 */
function writeAll(fd, data) {
    let offset = 0;
    while (offset < data.length) {
        offset += fs.writeSync(fd, data, offset, data.length - offset);
    }
}

/**
 * 判断进程是否还在（不发送任何信号）。
 *
 * @param {number} pid 进程号
 * @returns {boolean} 存活返回 `true`
 */
function pidAlive(pid) {
    try {
        // 信号 0 只做存在性与权限检查
        process.kill(pid, 0);
        return true;
    } catch (error) {
        // EPERM：进程存在但不属于当前用户。排查场景下「它在」是更有用的结论
        return error.code === 'EPERM';
    }
}

/**
 * 一个正在运行的 worker 进程。
 *
 * **它不持有请求队列**：队列属于脚本，而不是属于某一代 worker。worker 会被
 * 空闲回收、被超时隔离、会崩溃，而队列里那些「还没开始执行」的请求不该跟着一起消失——
 * 它们会在新一代 worker 就绪后继续被派发。
 */
class Worker {

    /**
     * 构造 worker 句柄。
     *
     * @param {number} pid 进程号
     * @param {object} child 子进程句柄
     */
    constructor(pid, child) {
        this.pid = pid;
        this.child = child;
        this.buffer = Buffer.alloc(0);
        this.logBuffer = Buffer.alloc(0);
        this.ready = false;
        this.lastUsed = Date.now();
        this.killAt = null;
        this.forceKillAt = null;
    }
}

/**
 * 一个脚本的长期状态。
 *
 * **与 worker 分开**：worker 会反复死亡与重建（空闲自毁、崩溃、超时隔离），
 * 而「这个脚本的清单是什么」与「它是不是已经被判定为不一致」不该跟着进程一起丢掉。
 * 尤其后者：清单不一致的脚本必须**永久拒绝服务**，否则每次调用都会重新拉起一个
 * 注定失败的 worker，形成安静的无限重启。
 */
class ScriptState {

    /**
     * 构造脚本状态。
     *
     * @param {object} spec 宿主下发的脚本描述
     */
    constructor(spec) {
        this.spec = spec;
        this.refusal = null;
        this.worker = null;
        // 待派发的宿请求（FIFO）与已派发、等回答的那一个。放在脚本这一层，
        // 意味着「换一代 worker」对排队中的请求是透明的
        this.queue = [];
        this.inflight = null;
        this.lastSpawn = 0;
        // 最近一次上报给宿主的（生命周期状态, 存活, PID）与（排队数, 是否在途）。
        // 后者靠循环里的对账推变化（见 checkWorkerBusy），因此必须记住上次报了什么
        this.reportedState = null;
        this.reportedBusy = null;
    }

    /** 脚本标识。 */
    get scriptId() {
        return this.spec.id;
    }

    /** 清单摘要（宿主下发的）。 */
    get manifest() {
        return this.spec.manifest || {};
    }
}

/**
 * 事件循环与全部状态。
 */
class Gateway {

    /** 构造网关。 */
    constructor() {
        this.states = new Map();
        this.running = true;
        this.exitReason = null;
        this.stdinBuffer = Buffer.alloc(0);
        this.pendingReady = new Set();
        this.pendingInit = null;
        this.lastBusy = Date.now();
        this.settings = {};
        // PID 文件信息：路径、自己的 PID、启动时发现的上一份内容（只报告不处置）
        this.pidInfo = null;
        this.seq = 0;
        this.finish = null;
        this.tick = null;
        // 事件：等待宿主裁决的发布请求（序号 → 脚本 id），以及「刚发布出去的事件 → 发起脚本」
        this.pendingEmit = new Map();
        this.emitted = new Map();
        // 事件计数：推送成功 / 因 worker 忙或没有 worker 而丢弃 / 因是自己的回声而跳过
        this.eventsPushed = 0;
        this.eventsDropped = 0;
        this.eventsEchoed = 0;
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 进入事件循环并跑到底。
     *
     * @returns {Promise<number>} 退出码
     */
    async run() {
        this.installSignals();
        this.tick = setInterval(() => this.onTick(), TICK_MS);
        if (this.tick.unref) {
            this.tick.unref();
        }
        process.stdin.on('data', (chunk) => this.readJava(chunk));
        // stdin 关闭 = 宿主走了。没有别的可能，因此直接退场，由收尾逻辑杀掉 worker
        process.stdin.on('end', () => this.stop('宿主已关闭 stdin'));
        process.stdin.resume();
        await new Promise((resolve) => {
            this.finish = resolve;
        });
        clearInterval(this.tick);
        await this.shutdown();
        return 0;
    }

    /**
     * 请求退出：唯一的出口，保证收尾逻辑只跑一次。
     *
     * @param {string} reason 退出原因
     */
    stop(reason) {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.exitReason = reason;
        if (this.finish) {
            this.finish();
        }
    }

    /**
     * 装信号处理：退出原因记下来，实际收尾交给 `run()` 的尾部。
     *
     * Node 在这里比 Python 省事：信号处理器本来就跑在事件循环上，因此不存在
     * 「处理器里只能翻标志」这条限制；但收尾仍然只放在一处——两条退出路径各写一遍收尾，
     * 就是两份会漂移的记忆。
     */
    installSignals() {
        for (const name of ['SIGTERM', 'SIGINT', 'SIGHUP']) {
            process.on(name, () => this.stop(`signal ${name}`));
        }
    }

    /**
     * 每个节拍要做的检查。
     *
     * Python 版把这些放在 `select` 循环里（每次 I/O 之后顺带做），Node 版放在定时器上：
     * 事件驱动的那部分由 `onData` 系列处理，与时间有关的那部分必须有人主动看表。
     */
    onTick() {
        if (!this.running) {
            return;
        }
        this.expire();
        this.pumpAll();
        // 两段式关闭的第二步必须在**循环里**做：worker 卡在一段同步 JS 里时，
        // 它的 SIGTERM 处理器根本没有机会跑（Node 的信号处理器排在事件循环上），
        // 只有内核送达的 SIGKILL 收得动它。Python 版靠的是「信号处理器里直接 os._exit」，
        // 因此它只在关闭路径上做这件事——这条差异是实测出来的，不是照抄能得到的
        this.escalate();
        this.checkWorkerBusy();
        this.checkIdle();
    }

    /**
     * 收尾：杀掉全部 worker 再退出。
     *
     * worker 是网关的子进程，因此这一步必须由网关做——宿主不知道 worker 的存在，
     * 也不该知道（回收与 PID 表维护都留在有父子关系的进程里）。
     *
     * @returns {Promise<void>} 全部 worker 处理完之后完成的 promise
     */
    async shutdown() {
        for (const state of this.states.values()) {
            this.killWorker(state, '网关退出');
        }
        const deadline = Date.now() + KILL_GRACE_MS + KILL_FORCE_MS;
        while (this.aliveWorkers() > 0 && Date.now() < deadline) {
            this.escalate();
            await new Promise((resolve) => setTimeout(resolve, 50));
        }
        for (const state of this.states.values()) {
            this.closeWorker(state);
        }
        this.removePidFile();
        if (this.eventsPushed || this.eventsDropped || this.eventsEchoed) {
            this.log(`事件统计: 推送 ${this.eventsPushed}，丢弃 ${this.eventsDropped}`
                + `（worker 忙或没有 worker），跳过回声 ${this.eventsEchoed}`);
        }
        this.log(`网关退出: ${this.exitReason || '正常关闭'}`);
    }

    // ------------------------------------------------------------ 宿主 → 网关

    /**
     * 读宿主的协议帧。
     *
     * @param {Buffer} chunk 新到的字节
     */
    readJava(chunk) {
        const read = wire.feed(this.stdinBuffer, chunk);
        this.stdinBuffer = read.buffer;
        for (const frame of read.frames) {
            this.handleJavaFrame(frame);
        }
    }

    /**
     * 处理宿主发来的请求、通知或应答。
     *
     * @param {object} frame 帧
     */
    handleJavaFrame(frame) {
        if (frame.id === undefined || frame.id === null) {
            this.handleJavaNotification(frame);
            return;
        }
        if (frame.method === undefined || frame.method === null) {
            // 既没有方法又有 id：这是宿主对**网关发起的调用**的应答。
            // 网关只发起一种调用（发布事件），因此这里只有一条分支——
            // 若哪天多了别的，这段就该改成按 id 查表分派，而不是继续 if/else
            this.handleEmitResponse(frame);
            return;
        }
        const params = frame.params || {};
        this.lastBusy = Date.now();
        switch (frame.method) {
            case 'initialize':
                this.initialize(frame.id, params);
                break;
            case 'invoke':
                this.invoke(frame.id, params);
                break;
            case 'kill_worker':
                this.killWorkerRequest(frame.id, params);
                break;
            case 'shutdown':
                this.reply(frame.id, {});
                this.stop('宿主请求关闭');
                break;
            default:
                this.replyError(frame.id, CODE_METHOD_NOT_FOUND, `不支持的方法: ${frame.method}`);
        }
    }

    /**
     * 处理宿主的通知。目前只有事件推送一种。
     *
     * @param {object} frame 帧
     */
    handleJavaNotification(frame) {
        if (frame.method === 'event') {
            this.pushEvent(frame.params || {});
            return;
        }
        this.log(`忽略未知的宿主通知: ${frame.method}`);
    }

    /**
     * 把一条事件扇出给「声明了它、且当前空闲」的 worker。
     *
     * 三条取舍都在这里：
     *
     * - **只推给已经在跑的 worker**：给事件补拉起一个进程，等于让「一条通知」变成一次
     *   拉起解释器的重操作，而通知本身是可以丢的。代价是没调用过的脚本收不到事件。
     * - **忙的 worker 直接丢**：worker 是单线程的，卡在一次长调用里时它的管道缓冲区
     *   迟早会被事件填满，而网关是单线程事件循环——一次阻塞写就把整个网关钉住。
     *   计数而不排队，是这里唯一不会让网关停摆的选择。
     * - **跳过发起者**：脚本收到自己刚发布的事件就是环。宿主在发布应答里给了 eventId，
     *   这里据此认出「这条事件是它自己发的」。
     *
     * @param {object} params 事件参数
     */
    pushEvent(params) {
        const name = params.event;
        const payload = params.payload || {};
        const allowed = this.settings.allowedEvents || [];
        if (allowed.length > 0 && allowed.indexOf(name) < 0) {
            this.eventsDropped += 1;
            return;
        }
        const eventId = payload.eventId;
        let origin = null;
        if (eventId) {
            origin = this.emitted.get(eventId);
            this.emitted.delete(eventId);
        }
        for (const state of this.states.values()) {
            if ((state.manifest.events || []).indexOf(name) < 0) {
                continue;
            }
            if (origin !== null && origin !== undefined && origin === state.scriptId) {
                this.eventsEchoed += 1;
                continue;
            }
            const current = state.worker;
            if (!current || current.killAt !== null || state.inflight !== null) {
                this.eventsDropped += 1;
                continue;
            }
            try {
                this.writeWorker(current, wire.encode(
                    { method: 'event', params: { event: name, payload: payload } }));
                this.eventsPushed += 1;
            } catch (error) {
                // 捕获面刻意开得很宽：事件是**旁路**，它失败最多丢一条通知，
                // 而从这里漏出去的异常会终止整个事件循环——等于把「少收一条通知」
                // 升级成「所有脚本全部不可用」。这个教训是实测来的：
                // Python 版一个 TypeError（把 socket 当 fd 用）就这样带走过一次网关
                this.log(`[${state.scriptId}] 推送事件失败: ${error.name}: ${error.message}`);
                this.eventsDropped += 1;
            }
        }
    }

    /**
     * 处理发布事件的应答：记住 eventId，供扇出时掐掉回声。
     *
     * @param {object} frame 应答帧
     */
    handleEmitResponse(frame) {
        const scriptId = this.pendingEmit.get(frame.id);
        this.pendingEmit.delete(frame.id);
        const result = frame.result || {};
        if (result.accepted && result.eventId) {
            this.emitted.set(result.eventId, scriptId);
            while (this.emitted.size > ECHO_MEMORY) {
                // Map 保持插入顺序，因此「最早的那一条」就是第一个键
                this.emitted.delete(this.emitted.keys().next().value);
            }
            return;
        }
        this.log(`脚本 ${scriptId} 发布的事件被拒绝: ${result.reason || '未知原因'}`);
    }

    /**
     * 下发脚本清单并为每个脚本拉起 worker。
     *
     * @param {number} requestId 宿主请求号
     * @param {object} params 参数
     */
    initialize(requestId, params) {
        this.settings = params.settings || {};
        const defaults = {
            invokeTimeoutSeconds: 30,
            workerIdleSeconds: 300,
            gatewayIdleSeconds: 600,
            manifestStrict: true,
        };
        for (const key of Object.keys(defaults)) {
            if (this.settings[key] === undefined) {
                this.settings[key] = defaults[key];
            }
        }
        this.recordPidFile();
        const specs = params.scripts || [];
        for (const spec of specs) {
            this.states.set(spec.id, new ScriptState(spec));
            this.pendingReady.add(spec.id);
        }
        // 初始化阶段一次性拉起全部 worker：清单与实现的一致性校验必须发生在
        // 「第一次真正用到之前」，否则那份不一致会以「某个工具永远失败」的形式
        // 分散暴露到每一次调用里
        for (const spec of specs) {
            this.spawn(this.states.get(spec.id));
        }
        if (specs.length === 0) {
            this.reply(requestId, this.initializeResult([]));
            return;
        }
        const timeout = this.settings.invokeTimeoutSeconds;
        // 截止时间为 null 表示「不超时」（配置成 0 的语义），此时只等就绪上报
        this.pendingInit = { id: requestId, deadline: timeout ? Date.now() + Math.max(MIN_INITIALIZE_MS, timeout * 1000) : null };
    }

    /**
     * 转发一次扩展点调用。
     *
     * @param {number} requestId 宿主请求号
     * @param {object} params 参数
     */
    invoke(requestId, params) {
        const scriptId = params.script;
        const state = this.states.get(scriptId);
        if (!state) {
            this.replyError(requestId, CODE_UNKNOWN_SCRIPT, `脚本不存在: ${scriptId}`);
            return;
        }
        if (state.refusal !== null) {
            this.replyError(requestId, CODE_MANIFEST_MISMATCH,
                `脚本 ${scriptId} 已拒绝服务: ${state.refusal}`);
            return;
        }
        const current = state.worker;
        if (current && current.killAt !== null) {
            // 正在被隔离的 worker 不再接新请求：把它留着只会让新请求一起陪葬，
            // 而「崩溃/被隔离后重新拉起」本来就是这套模型的既定能力
            this.closeWorker(state);
        }
        if (!state.worker && !this.spawn(state)) {
            this.replyError(requestId, CODE_INTERNAL, `脚本 ${scriptId} 的 worker 无法启动`);
            return;
        }
        const timeout = this.settings.invokeTimeoutSeconds;
        state.queue.push({
            seq: this.nextSeq(),
            javaId: requestId,
            type: params.type,
            request: params.request,
            deadline: 0,
            // 排队等待的上限：只用于「worker 迟迟起不来」这一种情形，
            // 派发时会被换成真正的执行截止时间
            waitDeadline: timeout ? Date.now() + timeout * 1000 : null,
        });
        this.pump(state);
    }

    /**
     * 宿主请求隔离某个脚本的 worker。
     *
     * 超时处置链里「谁来杀」的答案就是这里：宿主只发指令，**杀的动作由网关做**，
     * 因为回收与 PID 表都在有父子关系的一侧，宿主不必知道 worker 的存在。
     *
     * @param {number} requestId 宿主请求号
     * @param {object} params 参数
     */
    killWorkerRequest(requestId, params) {
        const state = this.states.get(params.script);
        if (!state) {
            // 「没杀到」是正常返回值：worker 可能已经自己退了，而调用方关心的是
            // 「它现在还在不在」，不是「我这一下有没有打中」
            this.reply(requestId, { killed: false });
            return;
        }
        const reason = params.reason || '宿主请求';
        const killed = this.killWorker(state, reason);
        this.failInflight(state, CODE_SCRIPT_FAILURE,
            `脚本 ${state.scriptId} 已被隔离: ${reason}`);
        this.reply(requestId, { killed: killed });
    }

    /**
     * 取下一个 worker 请求序号（每个 worker 同时只有一个在途，因此单调即可）。
     *
     * @returns {number} 序号
     */
    nextSeq() {
        this.seq += 1;
        return this.seq;
    }

    /**
     * 组装初始化应答：逐脚本结果，外加 PID 文件信息。
     *
     * @param {Array} scripts 逐脚本结果
     * @returns {object} 应答结果
     */
    initializeResult(scripts) {
        const result = { scripts: scripts };
        if (this.pidInfo !== null) {
            result.pidFile = this.pidInfo;
        }
        return result;
    }

    // ------------------------------------------------------------ PID 文件

    /**
     * 写自己的 PID；写之前先把上一份读出来**只报告、不处置**。
     *
     * 这个文件的全部价值在「JVM 被 `kill -9`、网关也一起失联」之后：
     * 那时它是唯一还活着的线索。因此它是**快照**而不是锁——两个网关同跑是可能的
     * （上一个 JVM 被强杀、它留下的网关还没自毁，新 JVM 又起来了），
     * 谁也不该根据它去做任何处置。
     */
    recordPidFile() {
        const target = this.settings.pidFile;
        if (!target) {
            return;
        }
        const info = {};
        const stale = this.readStalePid(target);
        if (stale !== null) {
            info.stale = stale;
            this.log(`发现遗留的 PID 文件 ${target}：${stale}（只报告，未处理）`);
        }
        try {
            const directory = path.dirname(target);
            if (directory) {
                fs.mkdirSync(directory, { recursive: true });
            }
            // 先写临时文件再改名：人正是靠这个文件判断「刚才那个进程是谁」，
            // 而读到一半的 PID（例如只写了一个数字的前缀）比没有文件更糟——它会被当成真的
            const temporary = `${target}.${process.pid}.tmp`;
            try {
                fs.writeFileSync(temporary, `${process.pid}\n`);
                fs.renameSync(temporary, target);
            } finally {
                // 改名成功时它已经不存在；失败时留下一份半成品只会让下一个看目录的人多一个疑问
                try {
                    fs.unlinkSync(temporary);
                } catch (ignored) {
                    // 不存在就是想要的结果
                }
            }
            info.pid = process.pid;
        } catch (error) {
            info.notice = `写入 PID 文件失败: ${error.message}`;
            this.log(info.notice);
        }
        this.pidInfo = info;
    }

    /**
     * 读上一份 PID 文件并判定它是否还在，返回可读描述；没有文件时返回 `null`。
     *
     * 存活判定用「信号 0」：不发送任何信号，只做一次权限与存在性检查。
     * 僵尸进程也会被判成存活，这是刻意的——排查时「它可能还在」比「它不在了」更保守。
     *
     * @param {string} target 文件路径
     * @returns {string|null} 可读描述
     */
    readStalePid(target) {
        let text;
        try {
            text = fs.readFileSync(target, 'utf8').trim();
        } catch (error) {
            if (error.code === 'ENOENT') {
                return null;
            }
            return `无法读取上一份 PID 文件: ${error.message}`;
        }
        if (!text) {
            return '空文件';
        }
        const pid = Number(text);
        if (!Number.isInteger(pid)) {
            return `内容不是 PID: ${text.slice(0, 40)}`;
        }
        if (pid <= 0) {
            return `内容不是有效 PID: ${pid}`;
        }
        return `PID ${pid}（${pidAlive(pid) ? '仍存活' : '已不存在'}）`;
    }

    /**
     * 退出时删掉自己的 PID 文件——**仅当它仍然记着我们自己的 PID 时**。
     *
     * 后来者可能已经覆盖了这个文件（旧网关还没死、新 JVM 又起来了）；
     * 这时把文件删掉，删掉的就是「后来者还活着」这份唯一证据。
     */
    removePidFile() {
        const info = this.pidInfo;
        if (!info || info.pid === undefined) {
            return;
        }
        const target = this.settings.pidFile;
        if (!target) {
            return;
        }
        try {
            const text = fs.readFileSync(target, 'utf8').trim();
            if (text !== String(process.pid)) {
                this.log(`PID 文件已被其它网关接管，不再删除: ${target}`);
                return;
            }
            fs.unlinkSync(target);
        } catch (error) {
            this.log(`清理 PID 文件失败: ${error.message}`);
        }
    }

    // ------------------------------------------------------------ worker → 网关

    /**
     * 读 worker 的协议帧。
     *
     * @param {object} state 脚本状态
     * @param {Buffer} chunk 新到的字节
     */
    readWorker(state, chunk) {
        const current = state.worker;
        if (!current) {
            return;
        }
        const read = wire.feed(current.buffer, chunk);
        current.buffer = read.buffer;
        for (const frame of read.frames) {
            this.handleWorkerFrame(state, frame);
        }
    }

    /**
     * 把 worker 的 stderr 转进网关自己的 stderr。
     *
     * 带脚本标识前缀：多个脚本同时说话时，「谁说的」是排查时第一个要回答的问题，
     * 而丢掉这个信息只需要省掉拼接这一下。
     *
     * @param {object} state 脚本状态
     * @param {Buffer} chunk 新到的字节
     */
    readWorkerLog(state, chunk) {
        const current = state.worker;
        if (!current) {
            // 同一批可读事件里，协议帧可能已经把 worker 判定为消失并关掉了管道，
            // 而日志事件仍在本批里等着处理
            return;
        }
        const read = wire.takeLines(current.logBuffer, chunk);
        current.logBuffer = read.buffer;
        for (const line of read.lines) {
            this.log(`[${state.scriptId}] ${line}`);
        }
    }

    /**
     * 处理 worker 发来的帧。
     *
     * @param {object} state 脚本状态
     * @param {object} frame 帧
     */
    handleWorkerFrame(state, frame) {
        if (frame.ready !== undefined) {
            this.handleReady(state, frame);
            return;
        }
        if (frame.method === 'emit_event') {
            this.forwardEmit(state, frame.params || {});
            return;
        }
        const current = state.worker;
        if (!current || state.inflight === null) {
            return;
        }
        const pending = state.inflight;
        state.inflight = null;
        current.lastUsed = Date.now();
        if (frame.error) {
            this.replyError(pending.javaId, frame.error.code === undefined ? CODE_SCRIPT_FAILURE : frame.error.code,
                frame.error.message || '脚本执行失败');
        } else {
            const payload = frame.result === undefined ? null : frame.result;
            const encoded = payload === null ? wire.encode(null) : wire.encode(payload);
            if (encoded.length > wire.MAX_FRAME_BYTES) {
                this.replyError(pending.javaId, CODE_RESULT_TOO_LARGE,
                    `脚本 ${state.scriptId} 的结果超过传输上限 ${wire.MAX_FRAME_BYTES} 字节`);
            } else {
                this.reply(pending.javaId, payload);
            }
        }
        this.lastBusy = Date.now();
        this.pump(state);
    }

    /**
     * 把脚本的发布请求转成宿主的一次调用。
     *
     * 转成**带 id 的调用**（而不是直接转成通知）是为了拿到宿主的裁决里的 eventId：
     * 扇出时要用它认出「这是某个脚本自己发的」，否则那个脚本会收到自己的事件。
     * 脚本侧仍然什么都不等——它发完就继续跑。
     *
     * @param {object} state 脚本状态
     * @param {object} params 参数
     */
    forwardEmit(state, params) {
        const requestId = this.nextSeq();
        this.pendingEmit.set(requestId, state.scriptId);
        this.writeJava({
            jsonrpc: '2.0',
            id: requestId,
            method: 'emit_event',
            params: {
                script: state.scriptId,
                event: params.event,
                payload: params.payload || {},
            },
        });
    }

    /**
     * 处理 worker 的就绪上报。
     *
     * @param {object} state 脚本状态
     * @param {object} frame 帧
     */
    handleReady(state, frame) {
        this.pendingReady.delete(state.scriptId);
        const current = state.worker;
        if (!frame.ready) {
            const reason = frame.error || 'worker 未说明原因';
            state.refusal = reason;
            this.log(`[${state.scriptId}] 拒绝服务: ${reason}`);
            this.killWorker(state, '拒绝服务');
            // 拒绝服务的脚本不该再留着队列：它永远不会服务，而队列会触发无谓的重启
            this.failQueued(state, CODE_MANIFEST_MISMATCH, `脚本 ${state.scriptId} 拒绝服务: ${reason}`);
            this.notifyWorkerState(state, 'refused', false);
            return;
        }
        if (current) {
            current.ready = true;
            current.lastUsed = Date.now();
        }
        this.notifyWorkerState(state, 'ready', true);
        this.pump(state);
    }

    /**
     * worker 消失后的统一处置。
     *
     * @param {object} state 脚本状态
     * @param {string} reason 原因
     */
    workerGone(state, reason) {
        const current = state.worker;
        if (!current) {
            return;
        }
        this.log(`[${state.scriptId}] ${reason}`);
        const deliberate = current.killAt !== null;
        this.failInflight(state, CODE_SCRIPT_FAILURE,
            `脚本 ${state.scriptId} 的 worker 已退出: ${reason}`);
        if (!deliberate) {
            // 崩溃（或者说「没被要求退场就没了」）：无法知道在途请求执行到哪一步，
            // 排队中的请求也本该由这一代 worker 依次执行，因此一起失败，让调用方明确重试。
            // 反过来，**被要求退场**（超时隔离、空闲自毁、重启）时排队中的请求从未开始，
            // 留着给下一代 worker 才是「重新拉起」的完整含义
            this.failQueued(state, CODE_SCRIPT_FAILURE,
                `脚本 ${state.scriptId} 的 worker 已退出: ${reason}`);
        } else if (state.queue.length > 0) {
            this.log(`[${state.scriptId}] 保留 ${state.queue.length} 个排队请求，等待重新拉起 worker`);
        }
        state.worker = null;
        this.closeWorkerFds(current);
        if (this.pendingReady.has(state.scriptId)) {
            this.pendingReady.delete(state.scriptId);
            state.refusal = state.refusal || reason;
        }
        this.notifyWorkerState(state, 'exited', false, current.pid);
    }

    // ------------------------------------------------------------ worker 管理

    /**
     * 给一个脚本补一个 worker。
     *
     * **Node 没有 `fork`，因此这里 `spawn` 一个新进程**，用同一条 `init` 帧把脚本目录、
     * 入口与清单送过去。顺带白拿两条性质：子进程不会继承网关与宿主之间的 stdio
     * （否则脚本一次误 print 就会往协议流里插一行），退出由 libuv 自动回收。
     *
     * @param {object} state 脚本状态
     * @returns {boolean} 发出了拉起动作返回 `true`
     */
    spawn(state) {
        if (state.worker || state.refusal !== null) {
            return false;
        }
        const spec = state.spec;
        // NODE_PATH 指到网关目录：脚本用 `require('jellyfish_sdk')` 就能拿到 SDK，
        // 而不必知道它被抽取到了哪里。用户自己的 NODE_PATH 保留在后面
        const nodePath = [HERE, process.env.NODE_PATH].filter(Boolean).join(path.delimiter);
        const child = spawn(process.execPath, [path.join(HERE, 'worker.js')], {
            stdio: ['pipe', 'pipe', 'pipe'],
            env: Object.assign({}, process.env, { NODE_PATH: nodePath }),
        });
        const worker = new Worker(child.pid, child);
        state.worker = worker;
        state.lastSpawn = Date.now();
        // worker 已经死掉时往它的 stdin 写会拿到 EPIPE；不挂这个处理，
        // 那个 error 事件会直接把网关整个带走——「写一帧失败」变成「所有脚本不可用」
        child.stdin.on('error', (error) => {
            this.log(`[${spec.id}] 写入 worker 失败: ${error.message}`);
        });
        child.stdout.on('data', (chunk) => this.readWorker(state, chunk));
        child.stderr.on('data', (chunk) => this.readWorkerLog(state, chunk));
        child.on('exit', (code, signalName) => {
            if (state.worker === worker) {
                this.workerGone(state, `worker 进程已退出（pid=${worker.pid}`
                    + `${signalName ? `，信号 ${signalName}` : `，退出码 ${code}`}）`);
            }
        });
        child.on('error', (error) => {
            if (state.worker === worker) {
                this.workerGone(state, `worker 无法启动: ${error.message}`);
            }
        });
        this.writeWorker(worker, wire.encode({
            id: 0,
            method: 'init',
            params: {
                script: spec.id,
                dir: spec.directory,
                entry: path.basename(spec.entry),
                manifest: state.manifest,
                strict: Boolean(this.settings.manifestStrict),
                idleSeconds: this.settings.workerIdleSeconds || 0,
                maxFrameBytes: wire.MAX_FRAME_BYTES,
            },
        }));
        this.log(`[${spec.id}] worker 已启动: pid=${child.pid}`);
        return true;
    }

    /**
     * 给 worker 写一帧。
     *
     * **走流的 `write` 而不是 `fs.writeSync`**：spawn 出来的管道没有暴露数字描述符，
     * 而这里也不需要同步写——网关对同一个 worker 的所有写入都经过同一条流，
     * 流自己保证顺序，不存在「两帧交错」的可能（Python 版之所以要自己拼
     * `writeAll`，是因为那里用的是裸 fd）。
     *
     * @param {object} current worker 句柄
     * @param {Buffer} data 帧字节
     */
    writeWorker(current, data) {
        current.child.stdin.write(data);
    }

    /**
     * 请求 worker 退出，返回是否发出了信号。
     *
     * **两段式**：先 SIGTERM，到点强杀 SIGKILL。在 Node 侧第二步才是主力——
     * worker 若卡在一段同步 JS 里，它的信号处理器根本没有机会跑，
     * 只有内核送达的 SIGKILL 才收得动它（Python 侧靠的是「信号处理器里直接 os._exit」）。
     *
     * @param {object} state 脚本状态
     * @param {string} reason 原因
     * @returns {boolean} 发出了信号返回 `true`
     */
    killWorker(state, reason) {
        const current = state.worker;
        if (!current || current.killAt !== null) {
            return false;
        }
        current.killAt = Date.now() + KILL_GRACE_MS;
        current.forceKillAt = current.killAt + KILL_FORCE_MS;
        current.ready = false;
        try {
            current.child.kill('SIGTERM');
        } catch (error) {
            this.log(`[${state.scriptId}] 请求 worker 退出失败: ${error.message}`);
        }
        this.log(`[${state.scriptId}] 请求 worker 退出: ${reason}`);
        return true;
    }

    /**
     * 两段式关闭的第二步：到点还没走就强杀。
     */
    escalate() {
        const now = Date.now();
        for (const state of this.states.values()) {
            const current = state.worker;
            if (!current || current.killAt === null) {
                continue;
            }
            if (current.forceKillAt !== null && now > current.forceKillAt) {
                this.log(`[${state.scriptId}] worker 未响应终止信号，强杀: pid=${current.pid}`);
                current.forceKillAt = null;
                try {
                    current.child.kill('SIGKILL');
                } catch (error) {
                    this.log(`[${state.scriptId}] 强杀失败: ${error.message}`);
                }
            }
        }
    }

    /**
     * 关闭并丢弃 worker 的连接。
     *
     * @param {object} state 脚本状态
     */
    closeWorker(state) {
        const current = state.worker;
        if (current) {
            this.closeWorkerFds(current);
            state.worker = null;
        }
    }

    /**
     * 关闭 worker 的管道与子进程句柄。
     *
     * @param {object} current worker 句柄
     */
    closeWorkerFds(current) {
        try {
            current.child.stdin.end();
        } catch (ignored) {
            // 对端已经走了：这正是我们想要的
        }
        try {
            current.child.stdout.destroy();
            current.child.stderr.destroy();
        } catch (ignored) {
            // 同上
        }
    }

    /**
     * 存活的 worker 数。
     *
     * @returns {number} 数量
     */
    aliveWorkers() {
        let count = 0;
        for (const state of this.states.values()) {
            if (state.worker) {
                count += 1;
            }
        }
        return count;
    }

    /**
     * 失败在途的请求（它可能已经执行了一半，因此不重试）。
     *
     * 必须做：worker 已经没了或已被隔离，在途的请求永远不会有人回答，
     * 而宿主会一直等到自己的超时——那是拿一个超时周期换取一条本来就能立刻给出的错误。
     *
     * @param {object} state 脚本状态
     * @param {number} code 错误码
     * @param {string} message 错误信息
     */
    failInflight(state, code, message) {
        const pending = state.inflight;
        if (!pending) {
            return;
        }
        state.inflight = null;
        this.replyError(pending.javaId, code, message);
    }

    /**
     * 失败尚未派发的请求。
     *
     * @param {object} state 脚本状态
     * @param {number} code 错误码
     * @param {string} message 错误信息
     */
    failQueued(state, code, message) {
        const pendingItems = state.queue.slice();
        state.queue = [];
        for (const pending of pendingItems) {
            this.replyError(pending.javaId, code, message);
        }
    }

    // ------------------------------------------------------------ 派发与到期

    /**
     * 给需要 worker 的脚本补一个，并派发可派发的请求。
     */
    pumpAll() {
        const now = Date.now();
        for (const state of this.states.values()) {
            // 队列非空却没有 worker：说明上一个 worker 已经退场，而请求还等着。
            // 不能等到「下一次调用」才补——那会让这次请求永远没人回答。
            // 速率限制是防止「一起来就崩」的脚本被打成无限重启循环
            if (state.queue.length > 0 && !state.worker && state.refusal === null
                && now - state.lastSpawn >= SPAWN_INTERVAL_MS) {
                this.spawn(state);
            }
            this.pump(state);
        }
    }

    /**
     * 派发 worker 当前队首的请求（每个 worker 同时只有一个在途请求）。
     *
     * 串行化的理由：worker 内部因此可以完全同步，脚本作者不必考虑并发；
     * 代价是同一脚本的并发调用会排队，而排队比「脚本里到处是竞态」便宜得多。
     *
     * @param {object} state 脚本状态
     */
    pump(state) {
        const current = state.worker;
        if (!current || !current.ready || state.inflight !== null || state.queue.length === 0) {
            return;
        }
        // 取出而不是只读队首：留在队列里会让它被反复派发，
        // 表现是同一个请求被脚本执行 N 次，而队列后面的请求永远轮不到
        const pending = state.queue.shift();
        state.inflight = pending;
        current.lastUsed = Date.now();
        // 截止时间从「派发」起算而不是「到达」起算：排队等待不是脚本的错，
        // 而超时的语义是「脚本执行太久」。真要排太久的队，前一个请求的超时
        // 会先把整个 worker 杀掉，队列里的人也一起拿到明确失败。
        // 超时配 0 表示「不超时」（宿主那侧同样如此），因此要显式落成 null 而不是
        // 拿 0 当截止时间——那会让 `now > deadline` 恒真，任何调用都在派发的下一拍被秒杀，
        // 而现象是「脚本永远跑不出结果」，与配置的字面意思完全相反
        const timeout = this.settings.invokeTimeoutSeconds;
        pending.deadline = timeout ? Date.now() + timeout * 1000 : null;
        try {
            this.writeWorker(current, wire.encode({
                id: pending.seq,
                method: 'invoke',
                type: pending.type,
                request: pending.request,
            }));
        } catch (error) {
            this.workerGone(state, `向 worker 发送请求失败: ${error.message}`);
        }
    }

    /**
     * 检查各种截止时间：请求超时、初始化超时、worker 空闲、网关空闲。
     */
    expire() {
        const now = Date.now();
        for (const state of this.states.values()) {
            const current = state.worker;
            if (current) {
                const deadline = state.inflight ? state.inflight.deadline : null;
                if (current.killAt === null && deadline !== null && deadline !== undefined
                    && now > deadline) {
                    const timeout = this.settings.invokeTimeoutSeconds;
                    this.log(`[${state.scriptId}] 请求超时（${timeout}s），隔离该 worker`);
                    // 只失败在途的那一个：它可能已经在脚本里跑了一半，重试会重复执行。
                    // 排队中的请求从未开始，因此留着给下一代 worker——那才是「重新拉起」的完整含义
                    this.failInflight(state, CODE_SCRIPT_FAILURE,
                        `脚本 ${state.scriptId} 已因超时被隔离（等待超过 ${timeout}s）`);
                    this.killWorker(state, '请求超时');
                }
            }
            this.expireWaiting(state, now);
        }
        this.expireInit(now);
    }

    /**
     * 初始化超时：等不到的脚本按失败上报，并让它转成「永久拒绝服务」。
     *
     * 这一条是必需的兜底：worker 起不来时若只是继续等，宿主会先超时，
     * 而宿主只知道「初始化没回应」，不知道是哪个脚本卡住了。
     *
     * @param {number} now 当前时间
     */
    expireInit(now) {
        if (this.pendingInit === null) {
            return;
        }
        const requestId = this.pendingInit.id;
        const deadline = this.pendingInit.deadline;
        const done = this.pendingReady.size === 0;
        if (!done && (deadline === null || now <= deadline)) {
            return;
        }
        const results = [];
        const ids = Array.from(this.states.values()).map((state) => state.scriptId).sort();
        for (const scriptId of ids) {
            const state = this.states.get(scriptId);
            if (this.pendingReady.has(scriptId)) {
                state.refusal = state.refusal || 'worker 未在超时前就绪';
                this.killWorker(state, '初始化超时');
            }
            // **每个脚本都要出现在结果里**：只在失败时上报的话，「worker 一起来就崩」
            // 的脚本会静默消失，而宿主看到的是「清单里有它、初始化结果里没有它」——
            // 那正是最难判断的一种不一致
            results.push({ script: scriptId, ok: state.refusal === null, error: state.refusal });
        }
        this.pendingReady.clear();
        this.pendingInit = null;
        this.reply(requestId, this.initializeResult(results));
        this.lastBusy = Date.now();
    }

    /**
     * 检查「还没派发出去」的请求是否等太久。
     *
     * 它们本不该超时：正常情况下队首会被派发，超时由在途的那一个触发。真正会走到这里的
     * 只有一种情形——worker 反复起不来。此时不能让请求无限等下去，
     * 否则用户看到的是「界面卡住」，而没有任何错误可以归因。
     *
     * @param {object} state 脚本状态
     * @param {number} now 当前时间
     */
    expireWaiting(state, now) {
        for (const pending of state.queue.slice()) {
            if (pending.waitDeadline && now > pending.waitDeadline) {
                state.queue.splice(state.queue.indexOf(pending), 1);
                this.replyError(pending.javaId, CODE_SCRIPT_FAILURE,
                    `脚本 ${state.scriptId} 的 worker 迟迟无法就绪，请求已放弃`);
            }
        }
    }

    /**
     * 空闲自毁。
     *
     * 两件事分开判断：worker 空闲了多少（各自算），以及「一个 worker 都没有」
     * 持续了多久（网关自己算）。后者是懒启动的必备配套——懒启动但不退场，
     * 等于只懒一次，而用户看到的是「跑过一次之后就一直占着内存」。
     */
    checkIdle() {
        const now = Date.now();
        const workerIdle = this.settings.workerIdleSeconds || 0;
        if (workerIdle) {
            for (const state of this.states.values()) {
                const current = state.worker;
                if (!current || current.killAt !== null || !current.ready) {
                    continue;
                }
                if (state.inflight === null && state.queue.length === 0
                    && now - current.lastUsed > workerIdle * 1000) {
                    this.log(`[${state.scriptId}] 空闲 ${workerIdle}s，worker 退场`);
                    this.killWorker(state, '空闲自毁');
                }
            }
        }
        const gatewayIdle = this.settings.gatewayIdleSeconds || 0;
        if (gatewayIdle && this.aliveWorkers() === 0 && !this.hasPendingRequests()
            && now - this.lastBusy > gatewayIdle * 1000) {
            this.stop(`空闲 ${gatewayIdle}s`);
        }
    }

    /**
     * 是否还有没答复宿主的请求（有就不能算空闲）。
     *
     * @returns {boolean} 有未答复请求返回 `true`
     */
    hasPendingRequests() {
        if (this.pendingInit !== null) {
            return true;
        }
        for (const state of this.states.values()) {
            if (state.worker && (state.queue.length > 0 || state.inflight !== null)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 与宿主通信

    /**
     * 回一个成功应答。
     *
     * @param {number} requestId 宿主请求号
     * @param {*} result 结果
     */
    reply(requestId, result) {
        this.writeJava({ jsonrpc: '2.0', id: requestId, result: result });
    }

    /**
     * 回一个失败应答。
     *
     * 错误必须走协议的错误对象：塞进 result 里会被调用方当成正常输出，
     * 对工具调用而言就是「脚本说没有内容」，语义静默错位。
     *
     * @param {number} requestId 宿主请求号
     * @param {number} code 错误码
     * @param {string} message 错误信息
     */
    replyError(requestId, code, message) {
        this.writeJava({ jsonrpc: '2.0', id: requestId, error: { code: code, message: message } });
    }

    /**
     * 把一帧写进宿主 stdout，并更新「最后一次忙碌」时间。
     *
     * @param {object} message 帧
     */
    writeJava(message) {
        let data = wire.encode(message);
        if (data.length > wire.MAX_FRAME_BYTES) {
            data = wire.encode({
                jsonrpc: '2.0',
                id: message.id,
                error: { code: CODE_RESULT_TOO_LARGE, message: '网关应答超过传输上限' },
            });
        }
        try {
            writeAll(1, data);
        } catch (error) {
            this.stop(`写宿主 stdout 失败: ${error.message}`);
        }
        this.lastBusy = Date.now();
    }

    /**
     * 上报 worker 状态快照（通知，不需要宿主应答）。
     *
     * 它是**快照更新**而不是事件日志：同一个生命周期状态可以在「在途/排队」变了之后
     * 再报一次，宿主拿到的是「这个脚本现在是什么样」而不是「刚才发生了什么」。
     * 生命周期名字（`ready` / `refused` / `exited`）不变，变的是随行的计数。
     *
     * @param {object} state 脚本状态
     * @param {string} name 生命周期状态名
     * @param {boolean} alive worker 是否可用
     * @param {number} pid worker 的 PID；省略时取当前 worker（已退场的那一代由调用方传）
     */
    notifyWorkerState(state, name, alive, pid) {
        const current = state.worker;
        let target = pid;
        if (target === undefined && current) {
            target = current.pid;
        }
        state.reportedState = { name: name, alive: alive, pid: target };
        state.reportedBusy = { queued: state.queue.length, inflight: state.inflight !== null };
        const params = {
            script: state.scriptId,
            state: name,
            alive: alive,
            started: current !== null && current !== undefined,
            queued: state.reportedBusy.queued,
            inflight: state.reportedBusy.inflight,
        };
        if (target !== undefined && target !== null) {
            // 进程已经不在时也不是不报：宿主拿它去 `ps -p` 一下，比「刚才那个进程没了」
            // 更能回答「它到底死透没有」
            params.pid = target;
        }
        this.writeJava({ jsonrpc: '2.0', method: 'worker_state', params: params });
    }

    /**
     * 把「在途/排队」的变化推给宿主。
     *
     * **在循环里对账，而不是在每个改动队列的地方各推一次**：后者要求每个改动点都记得推，
     * 而漏掉一个的表现是台账上的数字永久停在旧值——那比根本没有这个数字更糟。
     * 队列在这里只有一两项，比较一次的成本可以忽略，而循环本来就会周期性醒来。
     */
    checkWorkerBusy() {
        for (const state of this.states.values()) {
            if (!state.worker || state.reportedBusy === null) {
                continue;
            }
            const busy = { queued: state.queue.length, inflight: state.inflight !== null };
            if (busy.queued === state.reportedBusy.queued
                && busy.inflight === state.reportedBusy.inflight) {
                continue;
            }
            this.notifyWorkerState(state, state.reportedState.name, state.reportedState.alive,
                state.reportedState.pid);
        }
    }

    /**
     * 写网关自己的诊断日志：走 stderr，不碰协议流。
     *
     * @param {string} text 文本
     */
    log(text) {
        try {
            fs.writeSync(2, `[gateway] ${text}\n`);
        } catch (ignored) {
            // 诊断输出失败不该影响任何正事
        }
    }
}

/**
 * 命令行入口：网关不需要任何参数，配置由宿主的 `initialize` 下发。
 *
 * `--dump-manifest` 是**开发期**动作（离线生成脚本清单），转给同目录的清单生成器；
 * 它只在命令行分支里被加载，正常运行时网关从不碰它——网关不加载任何业务脚本。
 *
 * @param {string[]} argv 参数
 * @returns {Promise<number>} 退出码
 */
async function main(argv) {
    if (argv.length > 0 && argv[0] === '--dump-manifest') {
        // 延迟加载：正常路径上连这个模块都不该被读进来
        // eslint-disable-next-line global-require
        return require('./dump_manifest').main(argv.slice(1));
    }
    return new Gateway().run();
}

if (require.main === module) {
    // 显式 process.exit 而不是只设 exitCode：resume 过的 stdin 是一个「活着的句柄」，
    // 事件循环会因此一直转下去，网关就会出现「日志说退了、进程还在」的状态。
    // 这里可以放心硬退：协议应答与诊断日志全都是同步写出去的，没有待冲刷的缓冲
    main(process.argv.slice(2)).then((code) => {
        process.exit(code);
    }).catch((error) => {
        fs.writeSync(2, `[gateway] 未捕获的异常: ${error && error.stack ? error.stack : error}\n`);
        process.exitCode = 1;
    });
}

module.exports = { Gateway: Gateway };
