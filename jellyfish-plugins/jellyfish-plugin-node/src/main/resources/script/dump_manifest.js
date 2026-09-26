'use strict';

/**
 * 清单生成器：把脚本的声明导出成 `manifest.json`（Node 版）。
 *
 * 脚本的能力对宿主而言**只有** `manifest.json` 一个来源，而实现的事实写在声明里，
 * 两者必须一致。手工维护两份声明迟早会漂移，而漂移的表现是「脚本拒绝服务」——
 * 甚至更糟，是「模型按一份不存在的工具定义去调用」。这个工具就是用来消除这份手写工作的：
 *
 * ```
 * # 打印生成结果
 * node dump_manifest.js scripts/node/jira
 *
 * # 检查仓库里的 manifest.json 是否跟得上实现（不一致退 1，并打印差异）
 * node dump_manifest.js scripts/node/jira --check
 *
 * # 生成时把 id 也写进去（桥接插件不读这个字段，它认的是目录名）
 * node dump_manifest.js scripts/node/jira --id jira
 * ```
 *
 * **为什么要独立成文件而不是塞进网关**：清单生成是**离线**动作（开发期用），
 * 它要求进程里只能有一个脚本被加载；而网关是运行期进程，同时管着多个脚本。
 * 两者共用一个入口，迟早会有人拿一个半配好的网关目录去生成清单。
 */

const fs = require('fs');
const path = require('path');
const sdk = require('./jellyfish_sdk');

/**
 * 把一段文本写到标准输出。
 *
 * **必须同步写**：Node 对管道的 stdout 是异步缓冲的，而本模块结尾会 `process.exit`，
 * 于是「清单已生成」这件事会变成「输出被丢掉、文件是空的」——重定向到 manifest.json 时
 * 完全看不出来，只表现为下游报「不是合法 JSON」。这与网关那边必须同步写协议帧是同一条教训。
 *
 * @param {string} text 文本
 */
function out(text) {
    fs.writeSync(1, text);
}

/**
 * 加载脚本目录里的入口文件。
 *
 * 按**绝对路径** require（而不是 `require('main')`）：入口文件常常叫 `main.js`，
 * 按名字 require 有可能命中 node_modules 里同名的第三方模块，而那种错误的表现是
 * 「脚本声明的工具全都不见了」，与真正的原因（加载了别人的 main）隔着很远。
 *
 * @param {string} scriptDir 脚本目录（绝对路径）
 * @param {string} entry 入口文件名
 * @returns {object} 已加载的模块
 */
function loadEntry(scriptDir, entry) {
    const entryPath = path.resolve(scriptDir, entry);
    if (!fs.existsSync(entryPath)) {
        throw new Error(`入口文件不存在: ${entryPath}`);
    }
    if (!sdk.ensureResolvable()) {
        throw new Error('无法让脚本解析到 jellyfish_sdk：请把网关资源目录加进 NODE_PATH');
    }
    // eslint-disable-next-line global-require
    return require(entryPath);
}

/**
 * 读脚本目录里已有清单声明的入口名；没有清单时按 `main.js`。
 *
 * **空文件按「没有清单」处理**，这不是宽容而是必需：`node dump_manifest.js dir > dir/manifest.json`
 * 这个很自然的写法会让 shell 在脚本运行之前就把目标文件截断成 0 字节，于是「读现有清单一节」
 * 读到的正是自己即将覆盖的空文件。按合法性硬失败的话，用户会拿到一句「不是合法 JSON」，
 * 而原因与他的操作看起来毫无关系。`--write` 能绕开这个陷阱，但没理由让不用它的人踩坑。
 *
 * @param {string} scriptDir 脚本目录
 * @returns {string} 入口文件名
 */
function entryName(scriptDir) {
    const manifestPath = path.join(scriptDir, 'manifest.json');
    if (!fs.existsSync(manifestPath)) {
        return 'main.js';
    }
    const text = fs.readFileSync(manifestPath, 'utf8').trim();
    if (text === '') {
        return 'main.js';
    }
    let declared;
    try {
        declared = JSON.parse(text);
    } catch (error) {
        throw new Error(`现有的 manifest.json 不是合法 JSON: ${error.message}`);
    }
    return declared.entry || 'main.js';
}

/**
 * 把一份清单补齐成可比对的统一形状。
 *
 * 只做「缺省值补全」与「集合排序」，不丢弃任何会被内核读到的字段：
 * 描述与参数也要比，因为它们决定模型看到的工具定义——漂移一次，
 * 模型就会按一个不存在的签名去调用。
 *
 * @param {object} manifest 清单
 * @returns {object} 补齐后的清单
 */
function normalize(manifest) {
    const source = manifest || {};
    const normalized = { entry: source.entry || 'main.js' };
    if (source.id) {
        normalized.id = source.id;
    }
    normalized.tools = (source.tools || []).map((item) => ({
        name: item.name,
        description: item.description || '',
        parameters: item.parameters || {},
        required: item.required || [],
        readOnly: Boolean(item.readOnly),
    }));
    normalized.commands = (source.commands || []).map((item) => {
        const descriptor = item.descriptor || {};
        return {
            name: item.name,
            descriptor: {
                summary: descriptor.summary === undefined ? null : descriptor.summary,
                usage: descriptor.usage === undefined ? null : descriptor.usage,
                aliases: descriptor.aliases || [],
            },
            hasOptions: Boolean(item.hasOptions),
        };
    });
    normalized.commandOptions = (source.commandOptions || [])
        .map((item) => item.name).sort();
    normalized.contributions = (source.contributions || []).slice().sort();
    normalized.events = (source.events || []).slice().sort();
    return normalized;
}

/**
 * 取列表里每一项的名字；不是「每一项都带 name」时返回 `null`。
 *
 * 一份清单里的列表绝大多数是「带名字的条目」（工具、命令、候选查询），
 * 能取到名字就可以按名字比对——而那正是用户唯一想知道的事：
 * 「到底是哪一个工具对不上」。
 *
 * @param {Array} items 列表
 * @returns {string[]|null} 名字列表；取不出时返回 `null`
 */
function namesOf(items) {
    const names = [];
    for (const item of items) {
        if (typeof item !== 'object' || item === null || typeof item.name !== 'string') {
            return null;
        }
        names.push(item.name);
    }
    return names;
}

/**
 * 把值压成一行短文本，供差异输出使用。
 *
 * @param {*} value 任意值
 * @returns {string} 文本
 */
function brief(value) {
    const text = JSON.stringify(value);
    if (text === undefined) {
        return String(value);
    }
    return text.length <= 60 ? text : `${text.slice(0, 57)}...`;
}

/**
 * 逐层找出两份清单的差异。
 *
 * @param {*} expected 从声明生成的那一份
 * @param {*} actual 磁盘上那一份（已补齐）
 * @param {string} prefix 当前路径前缀
 * @returns {string[]} 差异描述列表
 */
function differences(expected, actual, prefix) {
    const where = prefix || '';
    const found = [];
    if (isMapping(expected) && isMapping(actual)) {
        const keys = Array.from(new Set(Object.keys(expected).concat(Object.keys(actual)))).sort();
        for (const key of keys) {
            const current = where ? `${where}.${key}` : key;
            if (expected[key] === undefined) {
                found.push(`${current}: 清单里多出了这一项（实现没有）`);
            } else if (actual[key] === undefined) {
                found.push(`${current}: 实现里有，清单里没有`);
            } else {
                found.push(...differences(expected[key], actual[key], current));
            }
        }
        return found;
    }
    if (Array.isArray(expected) && Array.isArray(actual)) {
        const expectedNames = namesOf(expected);
        const actualNames = namesOf(actual);
        if (expectedNames !== null && actualNames !== null) {
            // 按名字比而不是按下标：清单里插入一个条目会让后面所有下标错位，
            // 按下标报出来的是「第 2 个工具的名字不对」这种没用的结论
            const missing = expectedNames.filter((name) => actualNames.indexOf(name) < 0);
            const extra = actualNames.filter((name) => expectedNames.indexOf(name) < 0);
            if (missing.length > 0) {
                found.push(`${where}: 实现里有、清单里没有: ${missing.join('、')}`);
            }
            if (extra.length > 0) {
                found.push(`${where}: 清单里多出了这一项（实现没有）: ${extra.join('、')}`);
            }
            const byNameExpected = new Map();
            const byNameActual = new Map();
            for (const item of expected) {
                if (!byNameExpected.has(item.name)) {
                    byNameExpected.set(item.name, item);
                }
            }
            for (const item of actual) {
                if (!byNameActual.has(item.name)) {
                    byNameActual.set(item.name, item);
                }
            }
            // 两边都有的名字逐项比内容：即使上面刚报了「多了一个」也照比，
            // 因为「多了一个条目」与「另一个条目的描述忘了改」常常是同一轮改动造成的，
            // 一次报全比让用户改一处再跑一次便宜
            for (const name of Array.from(byNameExpected.keys()).sort()) {
                if (byNameActual.has(name)) {
                    found.push(...differences(byNameExpected.get(name), byNameActual.get(name),
                        `${where}[${name}]`));
                }
            }
            const counts = (names, name) => names.filter((item) => item === name).length;
            for (const name of Array.from(new Set(expectedNames.concat(actualNames))).sort()) {
                // 上面已经报告过「多出 / 缺失」的名字不再重复一遍次数
                if (missing.indexOf(name) >= 0 || extra.indexOf(name) >= 0) {
                    continue;
                }
                if (counts(expectedNames, name) !== counts(actualNames, name)) {
                    found.push(`${where}: ${name} 出现次数不同（实现 ${counts(expectedNames, name)}，`
                        + `清单 ${counts(actualNames, name)}）`);
                }
            }
            return found;
        }
        if (expected.length !== actual.length) {
            found.push(`${where}: 条目数不同（实现 ${expected.length}，清单 ${actual.length}）: `
                + `${brief(expected)} / ${brief(actual)}`);
            return found;
        }
        for (let index = 0; index < expected.length; index += 1) {
            found.push(...differences(expected[index], actual[index], `${where}[${index}]`));
        }
        return found;
    }
    if (JSON.stringify(expected) !== JSON.stringify(actual)) {
        found.push(`${where}: 实现是 ${brief(expected)}，清单是 ${brief(actual)}`);
    }
    return found;
}

/**
 * 判断一个值是不是「普通映射」（而不是数组或 null）。
 *
 * @param {*} value 待判断的值
 * @returns {boolean} 是映射返回 `true`
 */
function isMapping(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 解析命令行参数。
 *
 * 不用第三方解析器：网关与生成器都是零依赖的，这里只认四个开关，手写反而更清楚。
 *
 * @param {string[]} argv 参数
 * @returns {object} 解析结果
 */
function parseArgs(argv) {
    const options = { scriptDir: null, scriptId: null, check: false, entry: null, write: false };
    for (let index = 0; index < argv.length; index += 1) {
        const item = argv[index];
        if (item === '--check') {
            options.check = true;
        } else if (item === '--write') {
            options.write = true;
        } else if (item === '--id') {
            index += 1;
            options.scriptId = argv[index];
        } else if (item === '--entry') {
            index += 1;
            options.entry = argv[index];
        } else if (item === '--help' || item === '-h') {
            options.help = true;
        } else if (item.startsWith('-')) {
            throw new Error(`不认识的参数: ${item}`);
        } else if (options.scriptDir === null) {
            options.scriptDir = item;
        } else {
            throw new Error(`多余的参数: ${item}`);
        }
    }
    return options;
}

/**
 * 命令行入口。
 *
 * @param {string[]} argv 参数列表
 * @returns {number} 进程退出码
 */
function main(argv) {
    const options = parseArgs(argv);
    if (options.help || !options.scriptDir) {
        fs.writeSync(2, '用法: dump_manifest.js <脚本目录> [--id <脚本标识>] [--entry <入口文件>]'
            + ' [--check] [--write]\n'
            + '  --check  与目录里的 manifest.json 比对，不一致退 1\n'
            + '  --write  直接写入目录里的 manifest.json（推荐，绕开 shell 重定向先把文件截空）\n');
        return options.help ? 0 : 2;
    }
    const scriptDir = path.resolve(options.scriptDir);
    if (!fs.existsSync(scriptDir) || !fs.statSync(scriptDir).isDirectory()) {
        fs.writeSync(2, `脚本目录不存在: ${scriptDir}\n`);
        return 2;
    }
    const entry = options.entry || entryName(scriptDir);
    try {
        loadEntry(scriptDir, entry);
    } catch (error) {
        fs.writeSync(2, `加载脚本失败: ${error && error.stack ? error.stack : error}\n`);
        return 1;
    }
    const generated = sdk.dumpManifest(options.scriptId, entry);
    // 打印的是**内核认得的清单原文**，可以原样落盘；用于比对的是补齐缺省值之后的形状。
    // 两者必须是两份数据：打印一份「为比较而改过的」形状，用户抄回去就会得到一份
    // 内核不认的清单（例如 commandOptions 变成了字符串数组）
    const text = JSON.stringify(generated, null, 2);

    const manifestPath = path.join(scriptDir, 'manifest.json');
    if (options.write && !options.check) {
        fs.writeFileSync(manifestPath, `${text}\n`);
        out(`已写入清单: ${manifestPath}\n`);
        return 0;
    }
    if (!options.check) {
        out(`${text}\n`);
        return 0;
    }
    if (!fs.existsSync(manifestPath)) {
        fs.writeSync(2, `清单不存在: ${manifestPath}\n生成结果如下，可直接落盘：\n${text}\n`);
        return 1;
    }
    let actual;
    try {
        actual = normalize(JSON.parse(fs.readFileSync(manifestPath, 'utf8')));
    } catch (error) {
        fs.writeSync(2, `现有的 manifest.json 不可读: ${error.message}\n`);
        return 1;
    }
    const found = differences(normalize(generated), actual, '');
    if (found.length === 0) {
        out(`清单与实现一致: ${manifestPath}\n`);
        return 0;
    }
    fs.writeSync(2, `清单与实现不一致: ${manifestPath}\n`);
    for (const line of found) {
        fs.writeSync(2, `  ${line}\n`);
    }
    fs.writeSync(2, `\n实现里的声明（把它抄进清单，或直接采用）:\n${text}\n`);
    return 1;
}

module.exports = { main: main };

if (require.main === module) {
    let code;
    try {
        code = main(process.argv.slice(2));
    } catch (error) {
        fs.writeSync(2, `${error && error.message ? error.message : error}\n`);
        code = 2;
    }
    process.exit(code);
}
