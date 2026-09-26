package zcd.jellyfish.plugin.node;

import zcd.jellyfish.script.ScriptLanguage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node 语言适配：告诉语言无关的跨语言运行时「Node 怎么被宿主」。
 * <p>
 * 这是本插件里唯一与 Node 有关的 Java 代码；协议、进程管理、事件桥接、熔断全部由
 * {@code jellyfish-script} 提供（连「怎么注册脚本、怎么关进程、怎么渲染台账」都在那里），
 * 因此新增语言适配的成本就是这个类。这也正是方案 P9 的验收标准：
 * <b>机制层零改动</b>——Node 这一门语言没有让 {@code jellyfish-script} 增加一行代码。
 * <p>
 * <b>环境变量为什么是白名单而不是清空</b>：清空环境后 node 自己都找不到（{@code PATH} 缺失），
 * 而脚本插件又确实需要 {@code NODE_PATH}（脚本用 {@code require('jellyfish_sdk')} 引 SDK）
 * 与 npm 全局目录这类路径。于是取交集：只透传「运行时能跑起来 + 用户脚本能 require 到自己的依赖」
 * 所必需的那几个，不把 JVM 进程的整个环境（可能含密钥）复制给一个不受信的子进程。
 * <p>
 * <b>不需要 Python 那样的「关缓冲」开关</b>：Python 的 {@code PYTHONUNBUFFERED} 是因为它默认
 * 对非终端 stdout 做块缓冲，会让协议帧滞留在缓冲区里、双方互相等死。Node 侧不用环境变量解决：
 * 网关与 worker 都把 {@code process.stdout.write} 换成了同步写入（见各自的 {@code script/*.js}），
 * 那既管住了缓冲，也管住了「脚本一行 console.log 插进半帧协议」这件事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class NodeLanguage implements ScriptLanguage {

    /** 语言标识。 */
    static final String ID = "node";

    /** 透传的环境变量名（精确匹配）。 */
    private static final List<String> ENV_WHITELIST = Collections.unmodifiableList(Arrays.asList(
            "PATH", "HOME", "LANG", "NODE_PATH", "NODE_OPTIONS", "NODE_ENV", "NPM_CONFIG_PREFIX"));

    /** 透传的环境变量名前缀（区域设置有多套键，逐个列会漏）。 */
    private static final String ENV_WHITELIST_PREFIX = "LC_";

    /** 探测命令用的版本旗标。 */
    private static final String VERSION_FLAG = "--version";

    /** 网关入口相对路径（相对抽取根目录）。 */
    private static final String GATEWAY_ENTRY = "script/gateway.js";

    /**
     * 网关资源清单：由 {@code GatewayResources} 按内容摘要抽取到磁盘后交给解释器执行。
     * <p>
     * <b>为什么清单在这里</b>：「这门语言的网关由哪些文件组成」是桥接插件的打包事实，
     * 只有它知道；而运行时只需要「把这几份文件落到磁盘、告诉我落在哪」。
     * 两者用同一份常量，就不会出现「抽出来的资源」与「启动的命令」对不上。
     * <p>
     * 五个文件各司其职：{@code gateway.js} 是控制面（只与宿主说话），{@code worker.js} 是
     * 每个脚本一个的数据面，{@code script_wire.js} 是两侧共用的分帧，{@code jellyfish_sdk.js}
     * 是脚本作者看到的全部 API，{@code dump_manifest.js} 是离线清单生成器。
     * 它们必须落在同一个目录：网关靠自身目录拼 worker 与 {@code NODE_PATH} 的路径，
     * 脚本则靠 {@code NODE_PATH} require 到 SDK。
     */
    static final List<String> GATEWAY_RESOURCES = Collections.unmodifiableList(Arrays.asList(
            GATEWAY_ENTRY, "script/worker.js", "script/script_wire.js", "script/jellyfish_sdk.js",
            "script/dump_manifest.js"));

    /** 解释器可执行文件。 */
    private final String nodePath;

    /**
     * 构造语言适配。
     *
     * @param nodePath 解释器可执行文件，不可为空白
     */
    NodeLanguage(String nodePath) {
        this.nodePath = nodePath;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Node";
    }

    /**
     * 获取解释器可执行文件。
     *
     * @return 解释器路径或命令名
     */
    String nodePath() {
        return nodePath;
    }

    @Override
    public List<String> gatewayResources() {
        return GATEWAY_RESOURCES;
    }

    @Override
    public List<String> probeCommand() {
        return Collections.unmodifiableList(Arrays.asList(nodePath, VERSION_FLAG));
    }

    @Override
    public List<String> startCommand(Path gatewayDirectory) {
        // 入口路径与 GATEWAY_RESOURCES 同源，不做可配置：入口名可配只会让
        // 「抽出来的资源」与「启动的命令」对不上，属于纯粹的失配来源
        Path entry = gatewayDirectory.resolve(GATEWAY_ENTRY);
        return Collections.unmodifiableList(Arrays.asList(nodePath, entry.toString()));
    }

    @Override
    public Map<String, String> environment() {
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        Map<String, String> inherited = System.getenv();
        List<String> names = new ArrayList<String>(inherited.keySet());
        Collections.sort(names);
        for (String name : names) {
            if (isWhitelisted(name)) {
                resolved.put(name, inherited.get(name));
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public String toString() {
        return "NodeLanguage{nodePath=" + nodePath + '}';
    }

    /**
     * 判断环境变量名是否在白名单内。
     *
     * @param name 环境变量名，不可为 {@code null}
     * @return 在白名单内返回 {@code true}
     */
    private static boolean isWhitelisted(String name) {
        return ENV_WHITELIST.contains(name) || name.startsWith(ENV_WHITELIST_PREFIX);
    }
}
