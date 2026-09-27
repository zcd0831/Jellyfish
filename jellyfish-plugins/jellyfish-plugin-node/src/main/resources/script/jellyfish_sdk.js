'use strict';

/**
 * Jellyfish Node 脚本插件 SDK。
 *
 * 脚本作者只与本模块打交道：声明「我提供了什么」，剩下的（进程、协议、清单校验、熔断、回收）
 * 都由宿主负责。因此本模块刻意不感知协议细节，只做三件事：
 *
 * 1. **声明**：`tool()` / `command()` / `contributes()` / `subscribe()` 把参数记进注册表；
 * 2. **分发**：按扩展点类型把请求交给对应的函数，并把返回值整形成协议要求的形状；
 * 3. **生成清单**：`dumpManifest()` 把注册表还原成 `manifest.json`。
 *
 * 声明与清单必须一致，这是本方案唯一的高危点，因此两者都从这里出发：清单由代码生成，
 * 而不是另写一份。严格校验（`manifestStrict`）在 worker 启动时把两者比一遍，
 * 不一致就拒绝服务——宁可报错，也不要让模型按一份不存在的工具定义去调用。
 *
 * **与 Python 版逐条对应**：两边的方法名、参数名、返回值形状都刻意保持一致
 * （`tool` / `command` / `command_options` / `contributes` / `subscribe` / `declarations` /
 * `dump_manifest` / `compare_with` / `invoke`）。差异只在语言本身：
 * 装饰器变成「声明函数 + 就地注册」，缺省描述不再从文档字符串里取（JS 拿不到注释），
 * 因此 JS 作者要显式写 description——它正是模型看到的那句。
 */


/** 本模块所在目录：网关资源抽取目录，也就是 SDK 与 worker 所在的地方。 */
const HERE = __dirname;

/**
 * 让脚本里的 `require('jellyfish_sdk')` 在任何工作目录下都能解析出来。
 *
 * 脚本用**包名**而不是相对路径引入 SDK（与 Python 版的 `import jellyfish_sdk` 对称），
 * 而 Node 的非相对引入只查 `node_modules` 链与 `NODE_PATH`，不会像 Python 那样
 * 「先看脚本自己的目录」——脚本目录里没有 SDK，SDK 在网关资源目录里。
 * 因此谁加载脚本，谁就有责任先把这条路铺好：
 *
 * - 网关走的是**公开手段**：给 worker 的子进程环境设 `NODE_PATH`（见 gateway.js 的 spawn）；
 * - 离线生成器与手工调试没有那条链路，因此在进程内补一次（本方法）。
 *
 * 进程内补这一下用到了 `Module._initPaths`，它是 Node 的内部函数（`NODE_PATH` 只在进程
 * 启动时读一次，改完必须请 Node 重算搜索路径）。它从 Node 0.10 起就存在，且是循环里
 * 唯一的私有点，因此这里显式判一下：拿不到时返回 `false`，由调用方给出可操作的提示，
 * 而不是让脚本以「找不到模块」的形态失败。
 *
 * @returns {boolean} 铺好了返回 `true`
 */
function ensureResolvable() {
    // eslint-disable-next-line global-require
    const Module = require('module');
    const current = process.env.NODE_PATH ? process.env.NODE_PATH.split(require('path').delimiter) : [];
    if (current.indexOf(HERE) < 0) {
        process.env.NODE_PATH = [HERE].concat(current).join(require('path').delimiter);
    }
    if (typeof Module._initPaths !== 'function') {
        return false;
    }
    Module._initPaths();
    return true;
}

/** 扩展点类型名 → 处理函数。类型名与 Java 侧 codec 的 typeName() 必须逐字一致。 */
const handlers = new Map();

/** 工具声明，顺序即声明顺序，生成的清单与它保持同序（便于人读 diff）。 */
const tools = [];

/** 命令声明。 */
const commands = [];

/** 候选查询声明。 */
const commandOptionDecls = [];

/** 订阅的事件名。 */
const subscriptions = [];

/** 声明过的贡献类型，用于检测「同一类型声明了两个函数」。 */
const contributionTypes = new Set();

/**
 * 可以发布的事件名（与 Java 侧 ScriptEventFactory 的白名单一致）。
 *
 * 只有两类，而且都是「自由载荷」：通知的 payload 任意 JSON，告警只有一句文本。
 * 所以脚本**造不出内核语义事件**（工具完成、权限判定之类）——那类事件是内核事实的转述，
 * 指标、审计与界面都按「它是真的」来消费，让脚本能造等于开了一条往审计里写假账的路。
 *
 * 这里再留一份的原因只是**报错更近**：不在这份清单里时在本地就提醒一句，
 * 免得「事件发出去但没人收到」变成一个需要翻宿主日志的问题。
 * 真正的裁决仍在宿主侧，因此这份清单哪怕过时也只会多一句提醒，不会吞掉事件。
 */
const EMITTABLE_EVENTS = Object.freeze(['PluginNotificationEvent', 'ConfigWarningEvent']);

/**
 * 脚本侧的可预期失败。
 *
 * 抛出它等于告诉宿主「这次调用失败了」，宿主会把它转成协议错误回灌给模型
 * （工具调用表现为「工具执行失败：…」，回合继续）。**不要**用返回值里的
 * `{error: ...}` 表达失败——那会被当成正常输出，语义静默错位。
 */
class ScriptError extends Error {
    /**
     * 构造脚本错误。
     *
     * @param {string} message 可读原因
     */
    constructor(message) {
        super(message);
        this.name = 'ScriptError';
    }
}

/**
 * 一次调用的上下文。
 *
 * 只暴露脚本真正需要的东西：身份、会话标识与原始请求。刻意不暴露宿主对象，
 * 也不提供「回调宿主」的能力——脚本能做的事只有「处理这次请求并返回结果」。
 */
class ScriptContext {
    /**
     * 构造上下文。
     *
     * @param {string} scriptId 脚本标识
     * @param {object} payload 原始请求载荷
     * @param {Function|null} emitter 事件发布回调
     */
    constructor(scriptId, payload, emitter) {
        this.scriptId = scriptId;
        this.payloadData = payload || {};
        this.emitter = emitter || null;
    }

    /** 当前会话标识；该扩展点没有会话上下文时为 `null`。 */
    get sessionId() {
        return this.payloadData.sessionId === undefined ? null : this.payloadData.sessionId;
    }

    /** 当前 agent 标识；仅权限拦截等扩展点会带。 */
    get agentId() {
        return this.payloadData.agentId === undefined ? null : this.payloadData.agentId;
    }

    /** 原始请求载荷（只读用途，改它不会影响宿主）。 */
    get payload() {
        return Object.assign({}, this.payloadData);
    }

    /**
     * 发布一条事件。
     *
     * **尽力而为，没有返回值，也不要依赖它一定送达**：事件通道的契约是「可以丢」，
     * 丢弃可能是宿主队列满、网关没在运行、或没有 worker 在听。真正需要可靠传递的信息
     * 请用返回值。
     *
     * `name` 见 {@link EMITTABLE_EVENTS}；`payload` 是任意 JSON（仅
     * `ConfigWarningEvent` 要求 `message` 非空）。宿主会校验并可能拒绝，
     * 拒绝只记日志——脚本侧拿不到裁决，这也是「尽力而为」的一部分。
     *
     * @param {string} name 事件名
     * @param {object} payload 事件载荷，可为 `null`
     */
    emitEvent(name, payload) {
        if (!this.emitter) {
            throw new ScriptError('当前上下文不支持发布事件');
        }
        if (EMITTABLE_EVENTS.indexOf(name) < 0) {
            process.stderr.write(`[${this.scriptId}] 事件 ${name} 不在可发布清单 [`
                + `${EMITTABLE_EVENTS.join(', ')}] 内，宿主会拒绝\n`);
        }
        this.emitter(name, payload || {});
    }
}

/**
 * 声明一个工具，并返回处理函数本身。
 *
 * `parameters` 是 JSON Schema 的 `properties` 部分，`required` 是必填参数名列表：
 * 它们会原样进入 `ToolDescriptor`，也就是模型看到的工具定义，因此必须与函数真正
 * 接受的参数一致——不一致的后果是模型按错误的签名调用，而错误只在运行期以
 * 「参数缺失」的形式出现。
 *
 * `readOnly` 参与内核 PLAN 模式的只读白名单，缺省 `false`（可写）：
 * 误声明只读等于给模型留了一个绕过 PLAN 的后门，因此只读必须是显式选择。
 *
 * JS 里没有装饰器，因此用法是「声明 + 就地注册」，返回值就是那个函数：
 * ```js
 * const greet = tool({name: 'hello_greet', description: '按名字打招呼', readOnly: true},
 *                    (params, ctx) => '你好，' + params.args.name);
 * ```
 *
 * @param {object} spec 声明：`name` / `description` / `parameters` / `required` / `readOnly`
 * @param {Function} handler 处理函数，签名 `(params, ctx)`；工具用 `params.args`
 * @returns {Function} 同一个处理函数，便于赋值给变量
 */
function tool(spec, handler) {
    requireName(spec, 'tool');
    if (tools.some((item) => item.name === spec.name)) {
        throw new ScriptError(`工具名重复声明: ${spec.name}`);
    }
    tools.push({
        name: spec.name,
        description: spec.description === undefined ? null : spec.description,
        parameters: spec.parameters || {},
        required: listOf(spec.required),
        readOnly: Boolean(spec.readOnly),
    });
    handlers.set(`tool\u0000${spec.name}`, handler);
    return handler;
}

/**
 * 声明一条命令，并返回处理函数本身。
 *
 * `hasOptions: true` 表示本命令也回答候选查询（二级选择页）。它与 `commandOptions()` 是
 * 同一件事的两个入口，二者只能选一个：同时声明会被清单校验判为冲突——
 * 那必然是作者写错了，而写错的后果是「命令能执行、选择页永远空、且没有任何报错」。
 *
 * 走这个入口意味着**同一个函数**要回答两条路，而两条路给的 `params` 不同：执行给真实的
 * `tokens` / `raw`，候选查询给 `tokens === null` / `raw === null`。因此函数要按
 * `params.tokens === null` 分支（`tokens` 为空数组则是「用户没输入参数的执行」，两者不是一回事）：
 *
 * ```js
 * command({ name: 'x', hasOptions: true }, (params, ctx) => {
 *     if (params.tokens === null) {
 *         return { choices: [...] };   // 候选查询（按下补全键时）
 *     }
 *     return '执行结果';                // 执行
 * });
 * ```
 *
 * 候选查询必须**只读且快**（它跑在用户按键的那一拍上），因此更常见的是用 `commandOptions()`
 * 把它放在单独的函数里。与 Python 版的唯一差别：JS 拿不到参数名，因此这里无法像 Python 那样
 * 在声明期就把写错的签名挡掉，但漏掉分支的后果同样是响的——
 * `params.tokens` 是 `null`，`null.map(...)` 会立刻抛错。
 *
 * @param {object} spec 声明：`name` / `summary` / `usage` / `aliases` / `hasOptions` / `sessionRequired`
 * @param {Function} handler 处理函数，签名 `(params, ctx)`；命令用 `params.tokens` 与 `params.raw`
 * @returns {Function} 同一个处理函数
 *
 * `sessionRequired` 声明「这条命令是不是必须有会话才能工作」，**缺省 `true`（保守）**。
 * 声明为 `false` 的命令在没有当前会话时（TUI 首页）也能执行，因此外壳不会把用户手敲的它
 * 当成普通对话发给模型。默认值取 `true` 的理由是：反过来默认「不需要」会让一条依赖会话的命令
 * 在无会话时静默地变成一句提示词，而作者根本没这么想过。
 */
function command(spec, handler) {
    requireName(spec, 'command');
    if (commands.some((item) => item.name === spec.name)) {
        throw new ScriptError(`命令名重复声明: ${spec.name}`);
    }
    commands.push({
        name: spec.name,
        descriptor: {
            summary: spec.summary === undefined ? null : spec.summary,
            usage: spec.usage === undefined ? null : spec.usage,
            aliases: listOf(spec.aliases),
            sessionRequired: spec.sessionRequired === undefined ? true : Boolean(spec.sessionRequired),
        },
        hasOptions: Boolean(spec.hasOptions),
    });
    handlers.set(`command\u0000${spec.name}`, handler);
    if (spec.hasOptions) {
        handlers.set(`command_options\u0000${spec.name}`, handler);
        commandOptionDecls.push({ name: spec.name, implied: true });
    }
    return handler;
}

/**
 * 声明「本函数回答这条命令的候选查询」。
 *
 * 它与 `command({name, hasOptions: true})` 等价，用于把候选查询放在单独的函数里
 * （候选查询必须只读且快，常常与执行逻辑不是同一段代码）。
 *
 * 单独一个函数时通常不需要 `params.tokens`；要用也行，它在候选查询这条路上恒为 `null`
 * （与 `hasOptions: true` 那条入口同一套约定）。
 *
 * @param {string} commandName 命令名
 * @param {Function} handler 处理函数，签名 `(params, ctx)`，返回候选列表
 * @returns {Function} 同一个处理函数
 */
function commandOptions(commandName, handler) {
    if (commandOptionDecls.some((item) => item.name === commandName && item.implied)) {
        throw new ScriptError(`命令 ${commandName} 已用 hasOptions 声明候选查询，不要重复声明`);
    }
    if (commandOptionDecls.some((item) => item.name === commandName)) {
        throw new ScriptError(`命令 ${commandName} 重复声明候选查询`);
    }
    commandOptionDecls.push({ name: commandName, implied: false });
    handlers.set(`command_options\u0000${commandName}`, handler);
    return handler;
}

/**
 * 声明一个类型级扩展点贡献。
 *
 * 支持的类型：`prompt` / `status_line` / `panel` / `permission` /
 * `session_persist` / `session_restore` / `session_delete` / `compaction`。
 *
 * 同一类型只能声明一个函数：清单里的 `contributions` 是「类型名集合」，
 * 它表达不了「同一个类型挂两个函数」，因此第二个声明会被当场拒绝，
 * 而不是留到运行期变成「其中一个函数永远不会被调用」。
 *
 * 处理函数必须只读且快（`status_line` 与 `panel` 在界面渲染线程内联执行），
 * 且**不得发布事件**。
 *
 * @param {string} typeName 扩展点类型名
 * @param {Function} handler 处理函数，签名 `(params, ctx)`
 * @returns {Function} 同一个处理函数
 */
function contributes(typeName, handler) {
    if (contributionTypes.has(typeName)) {
        throw new ScriptError(`贡献类型重复声明: ${typeName}`);
    }
    contributionTypes.add(typeName);
    // 路由键就是类型名自身：类型级扩展点没有第二个维度，统一成 (类型, 类型)
    // 可以让分发逻辑只认一种键形状
    handlers.set(`${typeName}\u0000${typeName}`, handler);
    return handler;
}

/**
 * 声明订阅某个事件。
 *
 * 处理函数在事件到达时被调用，签名固定为 `(event, ctx)`：`event` 是事件字段表
 * （含 `event` 名字、`eventId`、`occurredAt`、`sessionId`，以及该事件的标量业务字段），
 * `ctx` 是 {@link ScriptContext}。返回值被忽略——事件是通知，没有「回答」这回事。
 *
 * **事件可以丢**，因此不要把它当成可靠投递：宿主队列满、网关没在运行、
 * 这个脚本的 worker 正忙或还没起过，事件都会跳过。需要可靠性的逻辑请写成工具。
 *
 * 处理器抛出的异常只记 stderr，不影响其它事件、也不影响在途调用：
 * 一个坏处理器不该把整个脚本带走。事件名必须是内核认识的（见宿主文档），
 * 写错会在清单校验时被拒绝。
 *
 * @param {...string} eventNames 事件名，可传多个
 * @returns {Function} 一个把处理函数原样返回的包装，便于 `module.exports` 风格使用
 */
function subscribe(...eventNames) {
    return (handler) => {
        for (const name of eventNames) {
            if (subscriptions.indexOf(name) < 0) {
                subscriptions.push(name);
            }
            handlers.set(`event\u0000${name}`, handler);
        }
        return handler;
    };
}

/**
 * 返回全部声明，供 worker 做清单校验与 `--dump-manifest` 使用。
 *
 * @returns {object} 声明表
 */
function declarations() {
    return {
        tools: tools,
        commands: commands,
        commandOptions: commandOptionDecls,
        contributions: Array.from(contributionTypes).sort(),
        events: subscriptions.slice(),
    };
}

/**
 * 把声明还原成 manifest。
 *
 * 只输出清单需要的字段：`handler` 这类只有运行期才有意义的东西不能进清单，
 * 否则清单结构就不再是「协议定义的一份数据」。
 *
 * @param {string} scriptId 脚本标识，可为空
 * @param {string} entry 入口文件名
 * @returns {object} 清单
 */
function dumpManifest(scriptId, entry) {
    const manifest = { entry: entry || 'main.js' };
    if (scriptId) {
        manifest.id = scriptId;
    }
    manifest.tools = tools.map((item) => ({
        name: item.name,
        description: item.description,
        parameters: item.parameters,
        required: item.required,
        readOnly: item.readOnly,
    }));
    manifest.commands = commands.map((item) => ({
        name: item.name,
        descriptor: item.descriptor,
        hasOptions: item.hasOptions,
    }));
    manifest.commandOptions = commandOptionDecls
        .filter((item) => !item.implied)
        .map((item) => ({ name: item.name }));
    if (contributionTypes.size > 0) {
        manifest.contributions = Array.from(contributionTypes).sort();
    }
    if (subscriptions.length > 0) {
        manifest.events = subscriptions.slice();
    }
    return manifest;
}

/**
 * 把声明与清单比一遍。
 *
 * 返回问题描述列表（空列表表示一致）。**比较的是名字集合而不是整份清单**：
 * 描述文本、参数 Schema 属于「给人看的信息」，改了它们不需要脚本作者同步改清单，
 * 而名字集合一旦不一致，模型看到的就是一份不存在的工具定义。
 *
 * 两种形态都接受：完整清单（`{name: ...}` 对象数组，即 `dumpManifest()` 的产物）
 * 与名字摘要（字符串数组，即宿主下发的那份）。宿主只下发名字，是因为它要判断的
 * 就是名字集合，带上整份清单会逼着网关跟随清单 schema 的每次演进。
 *
 * @param {object} manifest 清单或清单摘要
 * @returns {string[]} 问题描述，空数组表示一致
 */
function compareWith(manifest) {
    const problems = [];
    const source = manifest || {};
    compareNames(problems, 'tools', tools.map((item) => item.name), namesOf(source.tools));
    compareNames(problems, 'commands', commands.map((item) => item.name), namesOf(source.commands));
    // 只比显式声明的候选查询：`hasOptions` 的那部分由 commands 表达，
    // 清单里也不重复列（两者同时出现本来就被判为冲突）
    compareNames(problems, 'commandOptions',
        commandOptionDecls.filter((item) => !item.implied).map((item) => item.name),
        namesOf(source.commandOptions));
    compareNames(problems, 'contributions', Array.from(contributionTypes).sort(),
        listOf(source.contributions));
    compareNames(problems, 'events', subscriptions.slice(), listOf(source.events));
    return problems;
}

/**
 * 把「对象数组」或「字符串数组」都归一成名字列表。
 *
 * @param {*} value 待归一的值
 * @returns {string[]} 名字列表
 */
function namesOf(value) {
    const names = [];
    for (const item of listOf(value)) {
        if (item && typeof item === 'object') {
            if (item.name !== undefined && item.name !== null) {
                names.push(item.name);
            }
        } else if (item !== undefined && item !== null) {
            names.push(item);
        }
    }
    return names;
}

/**
 * 比较两边的名字集合，把差异写进问题列表。
 *
 * @param {string[]} problems 问题列表
 * @param {string} label 维度名
 * @param {string[]} declared 代码里声明的名字
 * @param {string[]} expected 清单里声明的名字
 */
function compareNames(problems, label, declared, expected) {
    const left = new Set(declared);
    const right = new Set(expected);
    const missing = declared.filter((name) => !right.has(name)).sort();
    const extra = expected.filter((name) => !left.has(name)).sort();
    if (missing.length > 0) {
        problems.push(`${label}: 代码里声明了但清单没有 ${missing.join(', ')}`);
    }
    if (extra.length > 0) {
        problems.push(`${label}: 清单声明了但代码没有 ${extra.join(', ')}`);
    }
}

/**
 * 扩展点类型名 → (实参构造, 结果整形)。
 *
 * 集中成一张表是为了让「每个扩展点怎么被调用、返回值要长什么样」一眼可见。
 * 若散落成 if/else，新增一个扩展点时最容易漏掉的恰恰是「结果形状」那一半，
 * 而漏掉的后果是宿主拿到一个形状不对的载荷后静默地当成「脚本没返回内容」。
 */
const dispatch = new Map();

/**
 * 登记一个扩展点的实参构造与结果整形。
 *
 * @param {string} typeName 扩展点类型名
 * @param {Function} buildParams 载荷 → 处理函数的第一个参数
 * @param {Function|null} shape 处理函数返回值 → 协议结果
 */
function register(typeName, buildParams, shape) {
    dispatch.set(typeName, { buildParams: buildParams, shape: shape });
}

/**
 * 把结果整形成「一个映射」，不是对象时给出缺省值。
 *
 * @param {*} result 处理函数返回值
 * @param {object} fallback 缺省值
 * @returns {object} 映射或缺省值
 */
function asMapping(result, fallback) {
    const value = fallback === undefined ? null : fallback;
    return result !== null && typeof result === 'object' && !Array.isArray(result) ? result : value;
}

// ---- 工具 -----------------------------------------------------------------

register('tool', (payload) => ({ args: payload.arguments || {} }),
    // 任意 JSON 都能作为 output：字符串、数字、对象、数组都合法，
    // 因为内核的截断与序列化对它们的处理是统一的（见 ToolCodec）
    (result) => ({ output: result === undefined ? null : result }));

// ---- 命令 -----------------------------------------------------------------

register('command', (payload) => {
    const arguments_ = payload.arguments || {};
    return { tokens: listOf(arguments_.tokens), raw: arguments_.raw || '' };
}, (result) => {
    if (result === undefined || result === null) {
        return { kind: 'OK', output: null };
    }
    if (typeof result === 'string') {
        return { kind: 'OK', output: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('命令返回值必须是字符串或 {kind, output, choices}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.kind === undefined) {
        shaped.kind = 'OK';
    }
    return shaped;
});

// ---- 命令候选查询 ---------------------------------------------------------

// 与执行那条路**同一套形状**，只是两个实参为 null：约定「tokens === null ⇒ 这次是候选查询」。
// 早先这里给的是空对象，于是「同一个函数回答两条路」（hasOptions: true）在用户按下补全键时
// 才会炸，而报出来的是与候选查询看不出关系的 undefined 相关错误
register('command_options', () => ({ tokens: null, raw: null }), (result) => {
    if (result === undefined || result === null) {
        return { choices: [] };
    }
    if (Array.isArray(result)) {
        return { choices: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('候选查询返回值必须是列表或 {choices: [...]}');
    }
    if (mapping.choices === undefined) {
        // 缺 choices 是最难查的一种写法：hasOptions 的函数忘了按 tokens === null 分支时，
        // 返回的正是执行结果（常常是 {kind, output}），而补齐缺省会让它安静地变成
        // 「没有候选」——选择页空着，没有任何报错
        throw new ScriptError('候选查询返回值里必须有 choices 键：返回候选列表，或 {choices: [...]}');
    }
    return Object.assign({}, mapping);
});

// ---- 只返回一段文本的贡献 -------------------------------------------------

/**
 * 把「一段文本」整形成协议形状。
 *
 * @param {*} result 处理函数返回值
 * @returns {object|null} 形状化结果
 */
function shapeText(result) {
    if (result === undefined || result === null) {
        return null;
    }
    if (typeof result === 'string') {
        return { text: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('该扩展点返回值必须是字符串、{text: ...} 或 null');
    }
    return mapping;
}

register('prompt', () => ({}), shapeText);
register('status_line', () => ({}), shapeText);

// ---- 面板 -----------------------------------------------------------------

register('panel', () => ({}), (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (Array.isArray(result)) {
        return { lines: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('面板返回值必须是 null、{title, region, lines} 或行列表');
    }
    return mapping;
});

// ---- 权限拦截 -------------------------------------------------------------

register('permission', () => ({}), (result) => {
    if (result === undefined || result === null || result === false) {
        return { denied: false };
    }
    if (result === true) {
        return { denied: true };
    }
    if (typeof result === 'string') {
        return { denied: true, reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('权限拦截返回值必须是布尔、原因字符串或 {denied, reason}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.denied === undefined) {
        shaped.denied = false;
    }
    return shaped;
});

// ---- 会话持久化 / 恢复 / 删除 ---------------------------------------------

/**
 * 三个没有结果类型的扩展点的整形函数。
 *
 * 有返回值一律忽略而不是报错：「多返回了一个东西」不该让一次已经完成的持久化看起来失败。
 *
 * @returns {null} 恒为 `null`
 */
function shapeNothing() {
    return null;
}

register('session_persist', (payload) => ({ snapshot: payload.snapshot }), shapeNothing);

register('session_restore', () => ({}), (result) => {
    if (result === undefined || result === null) {
        return { sessions: [] };
    }
    if (Array.isArray(result)) {
        return { sessions: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('会话恢复返回值必须是列表或 {sessions: [...]}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.sessions === undefined) {
        shaped.sessions = [];
    }
    return shaped;
});

register('session_delete', (payload) => ({ sessionId: payload.sessionId }), shapeNothing);

// ---- 压缩策略 -------------------------------------------------------------

register('compaction', (payload) => Object.assign({}, payload || {}), (result) => asMapping(result, null));

// ---- 事件 -----------------------------------------------------------------

register('event', (payload) => ({ event: payload }),
    // 事件处理器的返回值没有去处：事件是通知，不是请求。返回 null 让调用方
    // 不必因为脚本「顺手 return 了一个值」而报错
    () => null);

/**
 * 按类型分发一次调用。
 *
 * 处理函数抛出的任何异常都由调用方（worker）转成协议错误：脚本失败必须走
 * 「失败」这条路，而不是返回一个看起来正常的空结果——后者会让模型以为
 * 「脚本说没有内容」，问题就此静默。
 *
 * @param {string} scriptId 脚本标识，用于填上下文
 * @param {string} typeName 扩展点类型名
 * @param {string} routeKey 路由键（工具名 / 命令名）；类型级扩展点用类型名
 * @param {object} payload 请求载荷
 * @param {Function|null} emitter 事件发布回调（由 worker 注入）；`null` 表示当前上下文不支持发布事件
 * @returns {*} 结果载荷，可为 `null`
 */
function invoke(scriptId, typeName, routeKey, payload, emitter) {
    const lookupKey = routeKey === undefined || routeKey === null ? typeName : routeKey;
    const handler = handlers.get(`${typeName}\u0000${lookupKey}`);
    if (!handler) {
        throw new ScriptError(`没有处理 ${typeName}=${lookupKey} 的函数`);
    }
    const entry = dispatch.get(typeName);
    if (!entry) {
        throw new ScriptError(`未知的扩展点类型: ${typeName}`);
    }
    const context = new ScriptContext(scriptId, payload, emitter);
    const result = handler(entry.buildParams(payload || {}), context);
    return entry.shape ? entry.shape(result) : null;
}

/**
 * 校验声明里必须有名字。
 *
 * @param {object} spec 声明
 * @param {string} kind 声明种类，用于报错
 */
function requireName(spec, kind) {
    if (!spec || typeof spec.name !== 'string' || spec.name.trim() === '') {
        throw new ScriptError(`${kind} 声明缺少非空的 name`);
    }
}

/**
 * 把可空值归一成数组。
 *
 * @param {*} value 待归一的值
 * @returns {Array} 数组，保证非 `null`
 */
function listOf(value) {
    if (value === undefined || value === null) {
        return [];
    }
    return Array.isArray(value) ? value.slice() : [value];
}

module.exports = {
    EMITTABLE_EVENTS,
    ScriptError,
    ensureResolvable,
    ScriptContext,
    tool,
    command,
    commandOptions,
    contributes,
    subscribe,
    declarations,
    dumpManifest,
    compareWith,
    invoke,
};
