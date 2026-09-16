package zcd.jellyfish.plugin.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * Python 桥接插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-plugin-python} 段解析成值对象。
 * <p>
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的就是最终值；
 * 但 {@code ~} <b>没有</b>被展开（内核只在配置文件的路径段上做这件事），因此这里自己展开一次。
 * <p>
 * <b>只解析已经落地的键</b>：超时、空闲自毁、熔断参数等配置，等它们的消费者（{@code ScriptGateway} /
 * {@code ScriptCircuitBreaker}）落地时再加进来。提前声明一堆没人读的键，只会让人误以为配置已经生效。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PythonConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PythonConfig.class);

    /** 脚本根目录配置键。 */
    static final String KEY_SCRIPTS_ROOT = "scriptsRoot";

    /** 解释器配置键。 */
    static final String KEY_PYTHON_PATH = "pythonPath";

    /** 默认脚本根目录：相对进程工作目录，与内核「插件相对路径按进程 cwd 解析」同口径。 */
    static final String DEFAULT_SCRIPTS_ROOT = "scripts/python";

    /** 默认解释器：交给 PATH 解析，不写死绝对路径（虚拟环境场景由用户用 pythonPath 指定）。 */
    static final String DEFAULT_PYTHON_PATH = "python3";

    /** 脚本根目录。 */
    private final Path scriptsRoot;

    /** 解释器可执行文件。 */
    private final String pythonPath;

    /**
     * 构造配置。
     *
     * @param scriptsRoot 脚本根目录
     * @param pythonPath  解释器可执行文件
     */
    private PythonConfig(Path scriptsRoot, String pythonPath) {
        this.scriptsRoot = scriptsRoot;
        this.pythonPath = pythonPath;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对时抛出
     */
    static PythonConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap()
                : configuration;
        return new PythonConfig(resolveScriptsRoot(values.get(KEY_SCRIPTS_ROOT)),
                resolvePythonPath(values.get(KEY_PYTHON_PATH)));
    }

    /**
     * 获取脚本根目录。
     * <p>
     * <b>不检查目录是否存在</b>：目录不存在等价于「还没有脚本插件」，是正常的冷启动状态，
     * 不是配置错误；检查留给启动期扫描，那里才能区分「不存在」与「存在但不可读」。
     *
     * @return 规范化绝对路径
     */
    Path scriptsRoot() {
        return scriptsRoot;
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
    public String toString() {
        return "PythonConfig{scriptsRoot=" + scriptsRoot + ", pythonPath=" + pythonPath + '}';
    }

    /**
     * 解析脚本根目录：缺省用默认值，{@code ~} 展开为用户主目录。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 规范化的绝对路径
     * @throws JellyfishException 值不是非空字符串时抛出
     */
    private static Path resolveScriptsRoot(Object raw) {
        return normalizePath(requireText(raw, KEY_SCRIPTS_ROOT, DEFAULT_SCRIPTS_ROOT));
    }

    /**
     * 解析解释器路径：缺省用默认值。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 解释器路径或命令名
     * @throws JellyfishException 值不是非空字符串时抛出
     */
    private static String resolvePythonPath(Object raw) {
        return requireText(raw, KEY_PYTHON_PATH, DEFAULT_PYTHON_PATH);
    }

    /**
     * 取出非空字符串配置值。
     * <p>
     * 类型不对就当场抛出而不是退回默认值：配置写错了却静默走默认值，是「配置不生效」这类
     * 最难排查问题的标准成因。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 配置文本
     * @throws JellyfishException 值存在但不是非空字符串时抛出
     */
    private static String requireText(Object raw, String key, String defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new JellyfishException(key + " 必须是非空字符串，实际为 " + raw);
        }
        return ((String) raw).trim();
    }

    /**
     * 展开用户主目录前缀并规范化路径。
     *
     * @param raw 原始路径文本
     * @return 规范化绝对路径
     * @throws JellyfishException 无法确定用户主目录时抛出
     */
    private static Path normalizePath(String raw) {
        String text = raw;
        if (text.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                throw new JellyfishException("无法展开 ~ ：未取到 user.home");
            }
            text = home + text.substring(1);
        }
        Path path = Paths.get(text).toAbsolutePath().normalize();
        LOG.debug("Python 脚本根目录解析为: {}", path);
        return path;
    }
}
