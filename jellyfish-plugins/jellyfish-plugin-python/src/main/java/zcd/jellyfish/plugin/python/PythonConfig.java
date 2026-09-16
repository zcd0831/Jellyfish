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
 * <b>只解析已经落地的键</b>：熔断参数（{@code circuitBreaker.*}）等它的消费者
 * （{@code ScriptCircuitBreaker}）落地时再加进来。提前声明一堆没人读的键，
 * 只会让人误以为配置已经生效。
 * <p>
 * <b>类型不对就报错，不退回默认值</b>：写错的配置静默走默认值，是「配置不生效」这类
 * 最难排查问题的标准成因——用户改了三处配置，只有一处没生效，而他没有任何线索。
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

    /** 单次调用超时配置键。 */
    static final String KEY_INVOKE_TIMEOUT = "invokeTimeoutSeconds";

    /** worker 空闲自毁配置键。 */
    static final String KEY_WORKER_IDLE = "workerIdleSeconds";

    /** 网关空闲自毁配置键。 */
    static final String KEY_GATEWAY_IDLE = "gatewayIdleSeconds";

    /** 严格校验配置键。 */
    static final String KEY_MANIFEST_STRICT = "manifestStrict";

    /** 网关资源抽取根目录配置键。 */
    static final String KEY_GATEWAY_ROOT = "gatewayRoot";

    /** 事件收窄配置键。 */
    static final String KEY_EVENTS = "events";

    /** 事件收窄里的白名单字段名。 */
    static final String KEY_EVENTS_ALLOW = "allow";

    /** 默认脚本根目录：相对进程工作目录，与内核「插件相对路径按进程 cwd 解析」同口径。 */
    static final String DEFAULT_SCRIPTS_ROOT = "scripts/python";

    /** 默认解释器：交给 PATH 解析，不写死绝对路径（虚拟环境场景由用户用 pythonPath 指定）。 */
    static final String DEFAULT_PYTHON_PATH = "python3";

    /** 脚本根目录。 */
    private final Path scriptsRoot;

    /** 解释器可执行文件。 */
    private final String pythonPath;

    /** 单次调用超时秒数。 */
    private final int invokeTimeoutSeconds;

    /** worker 空闲自毁秒数。 */
    private final int workerIdleSeconds;

    /** 网关空闲自毁秒数。 */
    private final int gatewayIdleSeconds;

    /** 是否启用清单与实现的严格校验。 */
    private final boolean manifestStrict;

    /** 网关资源抽取根目录。 */
    private final Path gatewayRoot;

    /** 额外收窄的事件白名单。 */
    private final java.util.List<String> allowedEvents;

    /**
     * 构造配置。
     *
     * @param scriptsRoot          脚本根目录
     * @param pythonPath           解释器可执行文件
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param workerIdleSeconds    worker 空闲自毁秒数
     * @param gatewayIdleSeconds   网关空闲自毁秒数
     * @param manifestStrict       是否启用严格校验
     * @param gatewayRoot          网关资源抽取根目录
     * @param allowedEvents        额外收窄的事件白名单
     */
    private PythonConfig(Path scriptsRoot, String pythonPath, int invokeTimeoutSeconds,
                         int workerIdleSeconds, int gatewayIdleSeconds, boolean manifestStrict,
                         Path gatewayRoot, java.util.List<String> allowedEvents) {
        this.scriptsRoot = scriptsRoot;
        this.pythonPath = pythonPath;
        this.invokeTimeoutSeconds = invokeTimeoutSeconds;
        this.workerIdleSeconds = workerIdleSeconds;
        this.gatewayIdleSeconds = gatewayIdleSeconds;
        this.manifestStrict = manifestStrict;
        this.gatewayRoot = gatewayRoot;
        this.allowedEvents = allowedEvents;
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
                resolvePythonPath(values.get(KEY_PYTHON_PATH)),
                seconds(values.get(KEY_INVOKE_TIMEOUT), KEY_INVOKE_TIMEOUT,
                        zcd.jellyfish.script.GatewaySettings.DEFAULT_INVOKE_TIMEOUT_SECONDS),
                seconds(values.get(KEY_WORKER_IDLE), KEY_WORKER_IDLE,
                        zcd.jellyfish.script.GatewaySettings.DEFAULT_WORKER_IDLE_SECONDS),
                seconds(values.get(KEY_GATEWAY_IDLE), KEY_GATEWAY_IDLE,
                        zcd.jellyfish.script.GatewaySettings.DEFAULT_GATEWAY_IDLE_SECONDS),
                bool(values.get(KEY_MANIFEST_STRICT), KEY_MANIFEST_STRICT,
                        zcd.jellyfish.script.GatewaySettings.DEFAULT_MANIFEST_STRICT),
                resolveGatewayRoot(values.get(KEY_GATEWAY_ROOT)),
                allowedEvents(values.get(KEY_EVENTS)));
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

    /**
     * 获取单次调用超时秒数。
     *
     * @return 超时秒数；{@code 0} 表示不超时
     */
    int invokeTimeoutSeconds() {
        return invokeTimeoutSeconds;
    }

    /**
     * 获取 worker 空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    int workerIdleSeconds() {
        return workerIdleSeconds;
    }

    /**
     * 获取网关空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    int gatewayIdleSeconds() {
        return gatewayIdleSeconds;
    }

    /**
     * 获取网关资源抽取根目录。
     *
     * @return 抽取根目录
     */
    Path gatewayRoot() {
        return gatewayRoot;
    }

    /**
     * 组装下发给网关的设置。
     * <p>
     * 熔断参数不在这里：它只在 Java 侧使用，网关不需要知道——把它一起下发，
     * 就得让每种语言的网关各实现一遍同样的状态机，而三份实现里必然有两份会漂移。
     *
     * @return 网关设置
     */
    zcd.jellyfish.script.GatewaySettings gatewaySettings() {
        return zcd.jellyfish.script.GatewaySettings.builder()
                .invokeTimeoutSeconds(invokeTimeoutSeconds)
                .workerIdleSeconds(workerIdleSeconds)
                .gatewayIdleSeconds(gatewayIdleSeconds)
                .manifestStrict(manifestStrict)
                .allowedEvents(allowedEvents)
                .build();
    }

    @Override
    public String toString() {
        return "PythonConfig{scriptsRoot=" + scriptsRoot + ", pythonPath=" + pythonPath
                + ", invokeTimeout=" + invokeTimeoutSeconds + "s, workerIdle=" + workerIdleSeconds
                + "s, gatewayIdle=" + gatewayIdleSeconds + "s, manifestStrict=" + manifestStrict
                + ", gatewayRoot=" + gatewayRoot + '}';
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
     * 解析秒数配置：缺省用默认值，负数当场报错。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 秒数
     * @throws JellyfishException 值不是整数或为负时抛出
     */
    private static int seconds(Object raw, String key, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number)) {
            throw new JellyfishException(key + " 必须是整数秒数，实际为 " + raw);
        }
        int value = ((Number) raw).intValue();
        if (value < 0) {
            throw new JellyfishException(key + " 不得为负，实际为 " + value);
        }
        return value;
    }

    /**
     * 解析布尔配置。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 布尔值
     * @throws JellyfishException 值不是布尔时抛出
     */
    private static boolean bool(Object raw, String key, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean)) {
            throw new JellyfishException(key + " 必须是布尔值，实际为 " + raw);
        }
        return ((Boolean) raw).booleanValue();
    }

    /**
     * 解析网关资源抽取根目录。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 规范化绝对路径
     * @throws JellyfishException 值非法时抛出
     */
    private static Path resolveGatewayRoot(Object raw) {
        if (raw == null) {
            return zcd.jellyfish.script.GatewayResources.defaultBaseDirectory();
        }
        return normalizePath(requireText(raw, KEY_GATEWAY_ROOT, ""));
    }

    /**
     * 解析事件收窄白名单。
     * <p>
     * 只做「额外收窄」：内核事件清单硬编码在运行时里（事件类随内核版本变，配置化只会带来漂移），
     * 因此这里写一个不存在的事件名不会扩大任何能力，只会把范围缩得更小。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 事件名列表，保证非 {@code null}
     * @throws JellyfishException 结构或取值非法时抛出
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> allowedEvents(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof Map)) {
            throw new JellyfishException(KEY_EVENTS + " 必须是对象，实际为 " + raw);
        }
        Object allow = ((Map<String, Object>) raw).get(KEY_EVENTS_ALLOW);
        if (allow == null) {
            return Collections.emptyList();
        }
        if (!(allow instanceof java.util.List)) {
            throw new JellyfishException(KEY_EVENTS + "." + KEY_EVENTS_ALLOW + " 必须是数组");
        }
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (Object item : (java.util.List<Object>) allow) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new JellyfishException(KEY_EVENTS + "." + KEY_EVENTS_ALLOW
                        + " 只能包含非空字符串");
            }
            names.add(((String) item).trim());
        }
        return java.util.Collections.unmodifiableList(names);
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
