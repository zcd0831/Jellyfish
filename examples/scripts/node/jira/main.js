'use strict';

/**
 * 一个「像回事」的 Node 脚本插件：假装是一个工单系统。
 *
 * 与 `hello` 的区别在于它展示了**更完整的一套能力**：
 *
 * - 多个工具，且**只读与可写分开声明**（`readOnly` 决定 PLAN 模式下谁能过）；
 * - 命令带别名与用法，并且**候选查询写在单独的函数里**（候选与执行是两条独立的路）；
 * - 两个类型级贡献：一个进 system prompt，一个进 UI 面板；
 * - **订阅**内核事件，并且**发布**自己的事件（发布只有两类自由载荷事件可用，见下）。
 *
 * 注意它是**内存里的假工单系统**：没有任何网络调用，重启 worker 就忘光。
 * 真实的插件会在这里接 HTTP，而那正是「脚本进程隔离」的价值——
 * 依赖装在这个脚本自己的环境里，崩了也只波及它自己。
 */

const { ScriptError, tool, command, commandOptions, contributes, subscribe } = require('jellyfish_sdk');

/** 假工单库：`{KEY: {summary, status}}`。 */
const issues = {
    'PROJ-1': { summary: '脚本插件跑不起来', status: 'OPEN' },
    'PROJ-2': { summary: '文档里少了示例', status: 'DONE' },
};

/**
 * 最近几次操作，供面板显示。**有界**是刻意的：面板内容会被反复渲染，
 * 一个只增不减的列表迟早会把每次渲染都变成一次全量拼接。
 */
const recent = [];

/** 收到过哪些会话创建通知，供诊断使用。 */
const seenSessions = [];

/**
 * 记一条最近操作，只保留最后 5 条。
 *
 * @param {string} action 描述
 */
function remember(action) {
    recent.push(action);
    if (recent.length > 5) {
        recent.splice(0, recent.length - 5);
    }
}

// ---------------------------------------------------------------- 工具

/**
 * 读一个工单。只读工具，PLAN 模式下可以直接用。
 *
 * @param {object} params 请求参数
 * @returns {string} 工单摘要
 */
function readIssue(params) {
    const key = String(params.args.key || '').trim().toUpperCase();
    const issue = issues[key];
    if (!issue) {
        // 「没找到」也是失败：返回一个空字符串会让模型以为「工单没有内容」
        throw new ScriptError(`没有工单 ${key}`);
    }
    remember(`读 ${key}`);
    return `${key} [${issue.status}] ${issue.summary}`;
}

tool({
    name: 'jira_read',
    description: '读取一个工单的摘要与状态',
    parameters: { key: { type: 'string', description: '工单号，例如 PROJ-1' } },
    required: ['key'],
    readOnly: true,
}, readIssue);

/**
 * 新建工单（可写工具：PLAN 模式下需要用户批准）。
 *
 * 创建成功后**发布一条事件**。事件是旁路且尽力而为的：宿主可能拒绝、队列可能满、
 * 也可能没有任何订阅者——因此工单本身已经建好了，事件只是「顺带说一声」。
 * 真正需要可靠传递的结果走返回值。
 *
 * @param {object} params 请求参数
 * @param {object} ctx 调用上下文
 * @returns {string} 结果说明
 */
function createIssue(params, ctx) {
    const summary = String(params.args.summary || '').trim();
    if (!summary) {
        throw new ScriptError('缺少参数 summary');
    }
    const project = String(params.args.project || 'PROJ').trim().toUpperCase();
    const key = `${project}-${100 + Object.keys(issues).length}`;
    issues[key] = { summary: summary, status: 'OPEN' };
    remember(`建 ${key}`);
    ctx.emitEvent('PluginNotificationEvent', {
        payload: { kind: 'issue-created', key: key, sessionId: ctx.sessionId },
    });
    return `已创建 ${key}：${summary}`;
}

tool({
    name: 'jira_create',
    description: '新建一个工单，并发布一条内核通知事件',
    parameters: {
        summary: { type: 'string', description: '工单标题' },
        project: { type: 'string', description: '项目前缀，缺省 PROJ' },
    },
    required: ['summary'],
}, createIssue);

// ---------------------------------------------------------------- 命令

/**
 * `/jira` 显示列表；`/jira PROJ-1` 看一个；`/jira PROJ-1 DONE` 改状态。
 *
 * `params.tokens` 与 `params.raw` 都给：前者适合按词处理，后者适合原样记录。
 *
 * @param {object} params 请求参数
 * @returns {object} 命令结果
 */
function jira(params) {
    if (params.tokens.length === 0) {
        const listed = Object.keys(issues).sort()
            .map((key) => `${key}(${issues[key].status})`).join(', ');
        return { kind: 'OK', output: `工单：${listed}` };
    }
    const key = params.tokens[0].trim().toUpperCase();
    const issue = issues[key];
    if (!issue) {
        return { kind: 'ERROR', output: `没有工单 ${key}` };
    }
    if (params.tokens.length > 1) {
        issue.status = params.tokens[1].trim().toUpperCase();
        remember(`改 ${key}`);
    }
    return { kind: 'OK', output: `${key} [${issue.status}] ${issue.summary}` };
}

command({
    name: 'jira',
    summary: '查看或切换当前工单',
    usage: '/jira [工单号] [状态]',
    aliases: ['j'],
    // 本命令只读脚本自己的内存状态，与当前会话无关
    sessionRequired: false,
}, jira);

/**
 * `/jira` 的候选：现有工单号。
 *
 * 候选查询必须是**只读且快**的：它在用户按键补全时被调用，慢一次就是界面上的一次卡顿。
 *
 * @returns {object} 候选
 */
function jiraOptions() {
    return {
        choices: Object.keys(issues).sort()
            .map((key) => ({ value: key, label: key, description: issues[key].summary })),
    };
}

commandOptions('jira', jiraOptions);

// ---------------------------------------------------------------- 类型级贡献

/**
 * 告诉模型这个脚本能干什么。
 *
 * system prompt 每轮重新拼装，因此这里每次都返回最新状态；返回 `null`
 * 表示「这一轮没有要补充的」。
 *
 * @returns {string|null} 追加文本
 */
function prompt() {
    if (Object.keys(issues).length === 0) {
        return null;
    }
    return '工单系统可用：jira_read 读、jira_create 建，/jira 命令可以看列表。';
}

contributes('prompt', prompt);

/**
 * 一块 UI 面板：最近几次操作。
 *
 * `region` 只是**软建议**——一块区域同时只显示一个面板，抢同一区域的多个插件
 * 由用户用 `/ui` 切换，因此插件不能假设自己一定显示、也不能假设显示在哪。
 * 插件也无权决定尺寸：这里只给行，折行与截断由外壳负责。
 *
 * @returns {object|null} 面板内容
 */
function panel() {
    if (recent.length === 0) {
        return null;
    }
    return {
        title: 'Jira',
        region: 'DOCK',
        lines: recent.map((line) => ({ segments: [{ text: line, emphasis: 'DIM' }] })),
    };
}

contributes('panel', panel);

// ---------------------------------------------------------------- 事件

/**
 * 订阅「会话创建」。
 *
 * `event` 里带 `event` / `eventId` / `occurredAt`，以及这个事件的标量字段
 * （这里是 `agentId` 与 `sessionId`）。事件的送达**可以丢**，因此它只适合做
 * 「顺手记一笔」这类事，不能用来做状态同步。
 *
 * @param {object} event 事件字段
 */
function onSessionCreated(event) {
    seenSessions.push(event.sessionId);
    if (seenSessions.length > 5) {
        seenSessions.splice(0, seenSessions.length - 5);
    }
}

subscribe('SessionCreatedEvent')(onSessionCreated);

/**
 * 订阅「命令执行」：每条命令的出口都会广播一次（原文、命令名、三态、耗时）。
 *
 * 注意它**也在** `/jira` 自己被执行时到来一次——事件里没有「谁执行的」这种信息，
 * 真需要区分就得靠命令名判断。
 *
 * @param {object} event 事件字段
 */
function onCommandExecuted(event) {
    if (event.name === 'jira') {
        remember(`命令 ${event.kind || 'OK'}`);
    }
}

subscribe('CommandExecutedEvent')(onCommandExecuted);
