'use strict';

/**
 * 最小可用的 Node 脚本插件。
 *
 * 照着改就能写出自己的插件。它同时示范四件事：
 *
 * 1. **用声明函数声明能力**：`tool` / `command` / `commandOptions` / `contributes` /
 *    `subscribe`。声明写在这里，`manifest.json` 里还要再写一遍——脚本启动时会逐项对比
 *    两份声明，对不上就拒绝服务（见下）。
 * 2. **清单与实现必须一致**：`manifest.json` 是宿主认识这个脚本的**唯一**来源，
 *    而声明是实现的事实。脚本被拉起时会比对工具名/命令名/贡献类型/事件名，
 *    对不上只影响这一个脚本（表现是「这个脚本拒绝了服务」，其余脚本照常工作）。
 *    也正因如此，工具的描述与参数只在清单里有——它决定模型看到的工具定义。
 * 3. **能力从 `ctx` 拿运行上下文**（当前会话、当前 agent），不是从全局变量：
 *    同一个 worker 进程会依次服务不同会话的请求。
 * 4. **事件是旁路且可以丢的**：`subscribe` 的处理器不会阻塞任何调用，也不会因为它
 *    出错而影响服务；因此事件只用来做通知与统计，不用来传递「必须到达」的结果。
 */

const fs = require('fs');
const path = require('path');
const { ScriptError, tool, command, commandOptions, contributes, subscribe } = require('jellyfish_sdk');

// 自己的目录要用 __dirname 定位：脚本可能从任何工作目录被拉起，
// 相对路径会落到进程的 cwd 上（那是宿主的工作目录，不是脚本的目录）
const NOTES = path.join(__dirname, 'notes.txt');

/** 见过多少次工具执行结束；由事件处理器累加，由状态栏读出去。 */
let completed = 0;

/**
 * 数一数便签里有几行。
 *
 * @returns {number} 行数
 */
function noteCount() {
    if (!fs.existsSync(NOTES)) {
        return 0;
    }
    return fs.readFileSync(NOTES, 'utf8').split('\n').filter((line) => line.trim() !== '').length;
}

// ---------------------------------------------------------------- 工具

/**
 * 按名字打招呼。
 *
 * `readOnly: true` 是对内核的**能力声明**，不是注释：它让这个工具进入 PLAN 模式的
 * 只读白名单。声明错了等于给模型留了一个绕过 PLAN 的后门，因此只读必须显式写出来
 * （缺省是「可写」）。宿主在清单里读到的 `readOnly` 才是真正生效的那一份。
 *
 * @param {object} params 请求参数，工具用 `params.args`
 * @param {object} ctx 调用上下文
 * @returns {string} 问候
 */
function greet(params, ctx) {
    const name = params.args.name;
    if (!name) {
        // 用抛异常表达失败，而不是返回 {error: ...}：
        // 后者会被当成正常输出，模型会以为「脚本说没有问题」
        throw new ScriptError('缺少参数 name');
    }
    return `你好，${name}！（会话 ${ctx.sessionId || '无'}）`;
}

tool({
    name: 'hello_greet',
    description: '按名字打招呼；这是只读工具',
    parameters: { name: { type: 'string', description: '要打招呼的人' } },
    required: ['name'],
    readOnly: true,
}, greet);

/**
 * 把一句话追加到便签里。
 *
 * 这是**可写**工具（没有 `readOnly`）：它在 PLAN 模式下会被拒绝，
 * 除非用户显式批准。示例故意让两个工具的只读性不同，好让 `/plan` 下的差别看得见。
 *
 * @param {object} params 请求参数
 * @returns {string} 结果说明
 */
function remember(params) {
    const note = params.args.note;
    if (!note) {
        throw new ScriptError('缺少参数 note');
    }
    fs.appendFileSync(NOTES, `${note.trim()}\n`, 'utf8');
    return `已记下：${note.trim()}（共 ${noteCount()} 条）`;
}

tool({
    name: 'hello_remember',
    description: '把一句话记到本脚本目录下的 notes.txt',
    parameters: { note: { type: 'string', description: '要记下的一句话' } },
    required: ['note'],
}, remember);

// ---------------------------------------------------------------- 命令

/**
 * `/hello <名字>`，别名 `/hi`；同时回答候选查询（二级选择页）。
 *
 * `params.tokens` 是已经切好的词，`params.raw` 是命令名之后的原文（没被切过）；
 * 两者都给，是因为「按词处理」和「原样记录」都是常见需求。
 *
 * 这里是候选查询的**两个入口之一**：`hasOptions: true` 让同一个函数回答两条路，
 * 而两条路给的 `params` 不同——执行给真实的 `tokens`，候选查询给 `tokens === null`
 * （空数组是「用户没输入参数的执行」，与它**不是**一回事）。另一个入口是把候选查询
 * 写在单独的函数里（见 `jira` 示例）：候选查询必须只读、必须快，通常也就不是执行那段
 * 代码，因此真实脚本里更常用那一个。
 *
 * @param {object} params 请求参数
 * @returns {object} 候选或命令结果
 */
function hello(params) {
    if (params.tokens === null) {
        return {
            choices: [
                { value: 'world', label: 'world', description: '经典开场' },
                { value: 'jellyfish', label: 'jellyfish', description: '本项目' },
            ],
        };
    }
    if (params.tokens.length === 0) {
        return { kind: 'OK', output: `用法：/hello <名字>（共记了 ${noteCount()} 条便签）` };
    }
    return `你好，${params.tokens[0]}！`;
}

command({
    name: 'hello',
    summary: '和示例脚本打个招呼',
    usage: '/hello <名字>',
    aliases: ['hi'],
    hasOptions: true,
}, hello);

// ---------------------------------------------------------------- 类型级贡献

/**
 * 往 system prompt 里加一段话。
 *
 * 这是**唯一**能影响模型行为的贡献点，也是「插件上下文不进消息历史」的体现：
 * 它每一轮都会被重新拼进 system prompt，而不是作为一条消息追加进历史。
 *
 * @returns {string|null} 追加文本；没有要补充的返回 `null`
 */
function prompt() {
    const count = noteCount();
    if (count === 0) {
        return null;
    }
    return `用户有 ${count} 条便签（用 hello_remember 追加，用 /hello 查看）。`;
}

contributes('prompt', prompt);

/**
 * 状态栏片段。多段之间由外壳用分隔符拼接。
 *
 * 它在**界面渲染线程**里被调用，因此只能读内存里的东西、必须立刻返回——
 * 这里刻意不读文件，读的是事件处理器维护的计数器。
 *
 * @returns {string} 片段
 */
function statusLine() {
    return `hello 见过 ${completed} 次工具结束`;
}

contributes('status_line', statusLine);

// ---------------------------------------------------------------- 事件

/**
 * 收到「工具执行结束」的通知。
 *
 * `event` 是**标量投影**：只有该事件的简单字段（这里用不到的都省了），
 * 嵌套的内核对象不会下发——否则内核内部结构就成了脚本的对外契约。
 * 处理器没有返回值：事件是通知，没有「回答」这回事。
 *
 * 处理器抛出的异常只记 stderr：一个坏处理器不该把整个脚本带走。
 */
subscribe('ToolCallCompletedEvent')(() => {
    completed += 1;
});
