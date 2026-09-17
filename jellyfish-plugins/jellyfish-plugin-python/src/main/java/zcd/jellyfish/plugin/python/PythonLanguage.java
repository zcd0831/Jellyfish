package zcd.jellyfish.plugin.python;

import zcd.jellyfish.script.ScriptLanguage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 语言适配：告诉语言无关的跨语言运行时「Python 怎么被宿主」。
 * <p>
 * 这是本插件里唯一与 Python 有关的 Java 代码；协议、进程管理、事件桥接、熔断全部由
 * {@code jellyfish-script} 提供，因此新增一门语言的成本就收敛到这类实现 + 一份该语言的常驻网关资源。
 * <p>
 * <b>环境变量为什么是白名单而不是清空</b>：清空环境后连解释器自己都找不到（{@code PATH} 缺失），
 * 而脚本插件又确实需要 {@code PYTHONPATH} / {@code VIRTUAL_ENV} 这类运行时路径。于是取交集：
 * 只透传「解释器能跑起来 + 用户脚本能 import 到自己的依赖」所必需的那几个，
 * 不把 JVM 进程的整个环境（可能含密钥）复制给一个不受信的子进程。
 * <p>
 * <b>为什么强制 {@code PYTHONUNBUFFERED}</b>：协议走 stdout，Python 默认对非终端 stdout 做块缓冲。
 * 一旦缓冲，脚本写出的帧会滞留在缓冲区里，Java 侧读到的是「脚本没响应」，
 * 双方就此互相等死——这类死锁在排查时毫无线索，所以直接在环境里根除。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PythonLanguage implements ScriptLanguage {

    /** 语言标识。 */
    static final String ID = "python";

    /** 透传的环境变量名（精确匹配）。 */
    private static final List<String> ENV_WHITELIST = Collections.unmodifiableList(Arrays.asList(
            "PATH", "HOME", "LANG", "PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"));

    /** 透传的环境变量名前缀（区域设置有多套键，逐个列会漏）。 */
    private static final String ENV_WHITELIST_PREFIX = "LC_";

    /** 强制关闭 stdout 缓冲的变量名。 */
    static final String ENV_UNBUFFERED = "PYTHONUNBUFFERED";

    /** 探测命令用的版本旗标。 */
    private static final String VERSION_FLAG = "--version";

    /** 网关入口相对路径（相对抽取根目录）。 */
    private static final String GATEWAY_ENTRY = "script/gateway.py";

    /**
     * 网关资源清单：由 {@code GatewayResources} 按内容摘要抽取到磁盘后交给解释器执行。
     * <p>
     * <b>为什么清单在这里</b>：「这门语言的网关由哪些文件组成」是桥接插件的打包事实，
     * 只有它知道；而运行时只需要「把这几份文件落到磁盘、告诉我落在哪」。
     * 两者用同一份常量，就不会出现「抽出来的资源」与「启动的命令」对不上。
     * <p>
     * 四个文件各司其职：{@code gateway.py} 是控制面（只与宿主说话），{@code worker.py} 是
     * 每个脚本一个的数据面，{@code script_wire.py} 是两侧共用的分帧，{@code jellyfish_sdk.py}
     * 是脚本作者看到的全部 API。后两个必须与前者同目录：网关靠自身目录做 {@code sys.path}
     * 起点，脚本则靠它 import 到 SDK。
     */
    static final List<String> GATEWAY_RESOURCES = Collections.unmodifiableList(Arrays.asList(
            GATEWAY_ENTRY, "script/worker.py", "script/script_wire.py", "script/jellyfish_sdk.py",
            "script/dump_manifest.py"));

    /** 解释器可执行文件。 */
    private final String pythonPath;

    /**
     * 构造语言适配。
     *
     * @param pythonPath 解释器可执行文件，不可为空白
     */
    PythonLanguage(String pythonPath) {
        this.pythonPath = pythonPath;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Python";
    }

    /**
     * 获取解释器可执行文件。
     *
     * @return 解释器路径或命令名
     */
    String pythonPath() {
        return pythonPath;
    }

    @Override
    public List<String> probeCommand() {
        return Collections.unmodifiableList(Arrays.asList(pythonPath, VERSION_FLAG));
    }

    @Override
    public List<String> startCommand(Path gatewayDirectory) {
        // 入口路径与 GATEWAY_RESOURCES 同源，不做可配置：入口名可配只会让
        // 「抽出来的资源」与「启动的命令」对不上，属于纯粹的失配来源
        Path entry = gatewayDirectory.resolve(GATEWAY_ENTRY);
        return Collections.unmodifiableList(Arrays.asList(pythonPath, entry.toString()));
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
        resolved.put(ENV_UNBUFFERED, "1");
        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public String toString() {
        return "PythonLanguage{pythonPath=" + pythonPath + '}';
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
