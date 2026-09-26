'use strict';

/**
 * 单个脚本的 worker：数据面（Node 版）。
 *
 * 一个 worker 只加载一个脚本，因此一个脚本卡死、崩溃或退出都不会牵连别的脚本——
 * 这是「每脚本一 worker」这个进程模型的全部意义。
 *
 * **与 Python 版的差异只有两处，都是语言本身的差异**：
 *
 * 1. Python 用 `fork` 让 worker 继承脚本目录、入口与清单，Node 没有 `fork` 的内存继承，
 *    因此改用一条 `init` 帧把同样的东西送进来（命令行走 argv 会让清单在进程列表里可见、
 *    还会撞上长度上限）。握手形状不变：worker 仍然先回一帧 `{id: 0, ready: …}`。
 * 2. **Node 没有 `setitimer` 的等价物**：定时器排在事件循环上，卡在一段同步 JS 里的 worker
 *    连自己的看门狗都不会响。因此「卡死的 worker 谁来收」在 Node 侧的答案是**网关的 SIGKILL**
 *    （内核送达，不需要 worker 配合），而 worker 自己的孤儿检查只在事件循环可用时有效——
 *    它是兜底，不是唯一防线。
 */

const fs = require('fs');
const path = require('path');
const sdk = require('./jellyfish_sdk');
const wire = require('./script_wire');

/** 协议错误码：与宿主侧 ScriptProtocol 的取值逐字一致。 */
const CODE_SCRIPT_FAILURE = -32000;
const CODE_RESULT_TOO_LARGE = -32003;
const CODE_INVALID_PARAMS = -32602;

/** 孤儿看门狗的检查周期（毫秒）。 */
const ORPHAN_CHECK_MS = 2000;

/**
 * 把标准输出改成同步写入。
 *
 * worker 的 stdout 就是协议通道，而 Node 对管道的 stdout 是**异步缓冲**的：
 * 一个几千字节的结果帧可能在写到一半时被打断，插进去的正是脚本自己的 `console.log`。
 * 那种破坏的表现是「宿主偶尔收到一帧解析不了」，且只在结果大、日志多时出现。
 * 统一走同步写入之后，两者共享同一条串行路径，代价是脚本的日志会阻塞——这正是我们想要的。
 */
process.stdout.write = function writeSync(chunk, encoding) {
    fs.writeSync(1, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding || 'utf8'));
    return true;
};

/**
 * 立刻退出，不等事件循环。
 *
 * 收到终止信号说明对端（网关）已经判定不需要这个 worker 的任何输出，
 * 因此这里不做收尾；半行协议帧由网关的脏行容忍吃掉。
 *
 * @param {string} scriptId 脚本标识，用于日志
 * @param {string} which 信号名或原因
 */
function dieNow(scriptId, which) {
    fs.writeSync(2, `[${scriptId}] 收到 ${which}，立即退出\n`);
    process.exit(0);
}

/**
 * 按空闲上限做自我回收。
 *
 * 网关也会做同一件事（它是双保险）：worker 自己退出更干净，因为那发生在脚本进程内部。
 * 检查周期取 `ORPHAN_CHECK_MS`，因此实际退出时间最多比配置晚这么多——
 * 这点误差换的是「孤儿检测与空闲回收共用同一个循环」，而分成两个定时器就会有两份状态。
 *
 * @param {object} session 会话状态
 * @param {number} idleSeconds 空闲秒数，0 表示不回收
 * @returns {object} 定时器
 */
function startWatchdog(session) {
    const timer = setInterval(() => {
        if (process.ppid === 1) {
            fs.writeSync(2, `[${session.scriptId}] 父进程已退出，自行退出\n`);
            process.exit(0);
        }
        if (session.idleSeconds && Date.now() - session.lastUsed > session.idleSeconds * 1000) {
            process.exit(0);
        }
    }, ORPHAN_CHECK_MS);
    // 定时器不该让进程活着：真正让进程活着的是标准输入，而它由网关把着
    if (timer.unref) {
        timer.unref();
    }
    return timer;
}

/**
 * 发一帧。失败只记 stderr：对端已经走了，报错也送不出去。
 *
 * @param {object} message 待发送的消息
 * @param {number} maxFrameBytes 单帧上限
 */
function send(message, maxFrameBytes) {
    let data = wire.encode(message);
    if (data.length > maxFrameBytes) {
        data = wire.encode({
            id: message.id,
            error: { code: CODE_RESULT_TOO_LARGE, message: `结果超过传输上限 ${maxFrameBytes} 字节` },
        });
    }
    try {
        fs.writeSync(1, data);
    } catch (error) {
        fs.writeSync(2, `发送协议帧失败: ${error.message}\n`);
    }
}

/**
 * 从请求载荷里取路由键。
 *
 * 路由键由请求字段决定而不是协议字段：宿主只下发 `type` 与 `request`，
 * 因此 tool / command 的名字只能从请求里读。其余扩展点是类型级的，路由键就是类型名。
 *
 * @param {string} typeName 扩展点类型名
 * @param {object} payload 请求载荷
 * @returns {string} 路由键
 */
function routeKey(typeName, payload) {
    if (typeName === 'tool') {
        return payload.tool;
    }
    if (typeName === 'command' || typeName === 'command_options') {
        return payload.command;
    }
    return typeName;
}

/**
 * 构造本次调用的「发布事件」回调。
 *
 * 会话标识从**当前这次调用**的载荷里取，而不是让脚本自己填：脚本手里没有会话标识
 * （那是宿主的概念），而事件必须能归属到会话——否则界面与审计都拿不到上下文。
 *
 * @param {object} session 会话状态
 * @param {object} payload 本次调用的载荷
 * @returns {Function} 发布回调
 */
function emitterFor(session, payload) {
    const sessionId = (payload || {}).sessionId;
    return function emit(name, body) {
        const eventPayload = Object.assign({}, body || {});
        if (sessionId !== undefined && sessionId !== null) {
            eventPayload.sessionId = sessionId;
        }
        // 不带 id：这是通知，网关不回答。宿主侧的裁决写日志，不回到脚本
        send({
            method: 'emit_event',
            params: { script: session.scriptId, event: name, payload: eventPayload },
        }, session.maxFrameBytes);
    };
}

/**
 * 处理网关发来的一帧 `invoke`。
 *
 * 网关对每个 worker 同时只会有一个在途请求，因此这里不需要处理并发；
 * 这也是选它的原因：worker 内部因此可以完全是同步的，脚本作者不必考虑线程。
 *
 * @param {object} frame 请求帧
 * @param {object} session 会话状态
 */
function handleInvoke(frame, session) {
    const payload = frame.request || {};
    try {
        const result = sdk.invoke(session.scriptId, frame.type, routeKey(frame.type, payload), payload,
            emitterFor(session, payload));
        send({ id: frame.id, result: result === undefined ? null : result }, session.maxFrameBytes);
    } catch (error) {
        if (error instanceof sdk.ScriptError) {
            send({
                id: frame.id,
                error: { code: CODE_SCRIPT_FAILURE, message: error.message },
            }, session.maxFrameBytes);
            return;
        }
        // 脚本里的任何异常都只该让这次调用失败：栈打到 stderr（网关会带上脚本前缀进内核日志），
        // 协议上只回一句可读信息——栈对模型没有意义，对作者才是
        fs.writeSync(2, `[${session.scriptId}] ${error && error.stack ? error.stack : error}\n`);
        send({
            id: frame.id,
            error: {
                code: CODE_SCRIPT_FAILURE,
                message: `${error && error.name ? error.name : 'Error'}: ${error && error.message}`,
            },
        }, session.maxFrameBytes);
    }
}

/**
 * 处理一条内核事件。
 *
 * **不应答**：事件是通知，没有「回答」这回事，因此这里不产生任何协议帧。
 * 处理器失败只打 stderr（由网关加 `[scriptId]` 前缀进内核日志）——事件是旁路，
 * 一个坏处理器不该影响任何在途调用，更不该把整个 worker 带走。
 *
 * @param {object} frame 事件帧
 * @param {object} session 会话状态
 */
function handleEvent(frame, session) {
    const params = frame.params || {};
    const name = params.event;
    const payload = params.payload || {};
    try {
        sdk.invoke(session.scriptId, 'event', name, payload, emitterFor(session, payload));
    } catch (error) {
        fs.writeSync(2, `[${session.scriptId}] 事件 ${name} 的处理器失败: `
            + `${error && error.stack ? error.stack : error}\n`);
    }
}

/**
 * 按文件路径加载入口模块。
 *
 * 用绝对路径 `require`（而不是 `require('main')`）：入口文件常常叫 `main.js`，
 * 按名字 require 有可能命中 node_modules 里同名的第三方模块，而那种错误的表现是
 * 「脚本声明的工具全都不见了」，与真正的原因（导入了别人的 main）隔着很远。
 *
 * @param {string} scriptDir 脚本目录（绝对路径）
 * @param {string} entryName 入口文件名
 */
function loadScript(scriptDir, entryName) {
    const entryPath = path.resolve(scriptDir, entryName);
    if (!fs.existsSync(entryPath)) {
        throw new Error(`入口文件不存在: ${entryPath}`);
    }
    require(entryPath);
}

/**
 * 处理 `init` 帧：加载脚本、校验清单，并回一帧 ready。
 *
 * @param {object} frame init 帧
 * @param {object} session 会话状态
 * @returns {boolean} 可以开始服务时返回 `true`
 */
function handleInit(frame, session) {
    const params = frame.params || {};
    session.scriptId = params.script;
    session.maxFrameBytes = params.maxFrameBytes || wire.MAX_FRAME_BYTES;
    session.idleSeconds = Number(params.idleSeconds || 0);
    try {
        loadScript(params.dir, params.entry);
    } catch (error) {
        // 任何加载失败都必须变成「拒绝服务」而不是崩溃：崩溃会让宿主只看到
        // 「worker 起来了又没了」，而真正的原因（脚本语法错误）只在 stderr 里
        send({
            id: frame.id,
            ready: false,
            error: `脚本加载失败: ${error && error.name}: ${error && error.message}`,
        }, session.maxFrameBytes);
        return false;
    }
    const problems = sdk.compareWith(params.manifest);
    if (problems.length > 0 && params.strict) {
        // 严格校验失败：拒绝服务。**不服务的脚本比半服务的脚本安全**——
        // 后者会让模型按一份不存在的工具定义去调用，而失败点分散在每次调用里，
        // 汇总起来才是「这个脚本坏了」。
        send({
            id: frame.id,
            ready: false,
            error: `清单与实现不一致: ${problems.join('; ')}`,
        }, session.maxFrameBytes);
        return false;
    }
    if (problems.length > 0) {
        fs.writeSync(2, `[${session.scriptId}] 清单与实现不一致（manifestStrict=false，继续服务）: `
            + `${problems.join('; ')}\n`);
    }
    send({ id: frame.id, ready: true }, session.maxFrameBytes);
    return true;
}

/**
 * 处理一帧。
 *
 * @param {object} frame 帧
 * @param {object} session 会话状态
 */
function handleFrame(frame, session) {
    if (!session.ready) {
        if (frame.method !== 'init') {
            send({
                id: frame.id,
                error: { code: CODE_INVALID_PARAMS, message: '尚未 init' },
            }, session.maxFrameBytes);
            return;
        }
        session.lastUsed = Date.now();
        if (!handleInit(frame, session)) {
            // 拒绝服务：回完那一帧就退出，免得网关对着一个永远不会 ready 的进程等超时
            process.exit(1);
        }
        session.ready = true;
        return;
    }
    session.lastUsed = Date.now();
    if (frame.method === 'invoke') {
        handleInvoke(frame, session);
        return;
    }
    if (frame.method === 'event') {
        handleEvent(frame, session);
        return;
    }
    send({
        id: frame.id,
        error: { code: CODE_INVALID_PARAMS, message: `不支持的方法: ${frame.method}` },
    }, session.maxFrameBytes);
}

/**
 * worker 主函数。
 *
 * 不用 async/await：整个循环是「读到一帧就同步处理完」的，脚本因此永远不必考虑并发。
 * 唯一的异步是等待标准输入，而它只是本循环的入口。
 */
function main() {
    const session = {
        scriptId: 'unknown',
        maxFrameBytes: wire.MAX_FRAME_BYTES,
        ready: false,
        lastUsed: Date.now(),
        idleSeconds: 0,
    };
    let buffer = Buffer.alloc(0);
    const watchdog = startWatchdog(session);

    process.on('SIGTERM', () => dieNow(session.scriptId, 'SIGTERM'));
    process.on('SIGINT', () => dieNow(session.scriptId, 'SIGINT'));

    process.stdin.on('data', (chunk) => {
        const read = wire.feed(buffer, chunk);
        buffer = read.buffer;
        for (const frame of read.frames) {
            handleFrame(frame, session);
        }
    });
    // 网关走了（stdin 关掉）就没有再服务的理由；这是第三条常规退出路径
    process.stdin.on('end', () => {
        clearInterval(watchdog);
        process.exit(0);
    });
    process.stdin.resume();
}

main();
