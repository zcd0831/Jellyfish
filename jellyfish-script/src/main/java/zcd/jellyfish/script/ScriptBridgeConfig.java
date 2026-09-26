package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 桥接插件的配置解析：把 {@code jellyfish.json} 里
 * {@code plugins.configurations.<插件标识>} 段解析成值对象。
 * <p>
 * <b>为什么它是一份而不是每种语言一份</b>：这一段里几乎每个键都与语言无关
 * （超时、空闲回收、抽取目录、事件收窄、熔断阈值），与语言有关的只有两件事——
 * 解释器写在哪一个键上、以及两个默认值（解释器与脚本根目录）。
 * 于是它们由子类在 {@link #from(Map, String, String, String)} 里传进来，
 * 而「键名要直白」这件事仍然成立：Python 用户看到的依旧是 {@code pythonPath}。
 * <p>
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的就是最终值；
 * 但 {@code ~} <b>没有</b>被展开（内核只在配置文件的路径段上做这件事），因此这里自己展开一次。
 * <p>
 * <b>只解析已经落地的键</b>：声明了一堆没人读的键，只会让人误以为配置已经生效。
 * 因此这里与 {@link #gatewaySettings(String)} / {@link #circuitBreakerSettings()} 的消费者严格同步。
 * <p>
 * <b>类型不对就报错，不退回默认值</b>：写错的配置静默走默认值，是「配置不生效」这类
 * 最难排查问题的标准成因——用户改了三处配置，只有一处没生效，而他没有任何线索。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptBridgeConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptBridgeConfig.class);

    /** 脚本根目录配置键。 */
    public static final String KEY_SCRIPTS_ROOT = "scriptsRoot";

    /** 单次调用超时配置键。 */
    public static final String KEY_INVOKE_TIMEOUT = "invokeTimeoutSeconds";

    /** worker 空闲自毁配置键。 */
    public static final String KEY_WORKER_IDLE = "workerIdleSeconds";

    /** 网关空闲自毁配置键。 */
    public static final String KEY_GATEWAY_IDLE = "gatewayIdleSeconds";

    /** 严格校验配置键。 */
    public static final String KEY_MANIFEST_STRICT = "manifestStrict";

    /** 网关资源抽取根目录配置键。 */
    public static final String KEY_GATEWAY_ROOT = "gatewayRoot";

    /** 网关 PID 文件目录配置键。 */
    public static final String KEY_PID_DIRECTORY = "pidDirectory";

    /** 事件收窄配置键。 */
    public static final String KEY_EVENTS = "events";

    /** 事件收窄里的白名单字段名。 */
    public static final String KEY_EVENTS_ALLOW = "allow";

    /** 熔断参数配置键。 */
    public static final String KEY_CIRCUIT_BREAKER = "circuitBreaker";

    /** 熔断参数里的连续失败次数上限字段名。 */
    public static final String KEY_FAILURES_TO_OPEN = "failuresToOpen";

    /** 熔断参数里的冷却秒数字段名。 */
    public static final String KEY_COOLDOWN_SECONDS = "cooldownSeconds";

    /** 熔断参数里的转永久轮数字段名。 */
    public static final String KEY_ROUNDS_TO_PERMANENT = "roundsToPermanent";

    /** 脚本根目录。 */
    private final Path scriptsRoot;

    /** 解释器可执行文件。 */
    private final String interpreterPath;

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

    /** 网关 PID 文件目录。 */
    private final Path pidDirectory;

    /** 额外收窄的事件白名单。 */
    private final List<String> allowedEvents;

    /** 熔断参数。 */
    private final CircuitBreakerSettings circuitBreaker;

    /**
     * 构造配置。
     *
     * @param scriptsRoot          脚本根目录
     * @param interpreterPath      解释器可执行文件
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param workerIdleSeconds    worker 空闲自毁秒数
     * @param gatewayIdleSeconds   网关空闲自毁秒数
     * @param manifestStrict       是否启用严格校验
     * @param gatewayRoot          网关资源抽取根目录
     * @param pidDirectory         网关 PID 文件目录
     * @param allowedEvents        额外收窄的事件白名单
     * @param circuitBreaker       熔断参数
     */
    private ScriptBridgeConfig(Path scriptsRoot, String interpreterPath, int invokeTimeoutSeconds,
                               int workerIdleSeconds, int gatewayIdleSeconds, boolean manifestStrict,
                               Path gatewayRoot, Path pidDirectory, List<String> allowedEvents,
                               CircuitBreakerSettings circuitBreaker) {
        this.scriptsRoot = scriptsRoot;
        this.interpreterPath = interpreterPath;
        this.invokeTimeoutSeconds = invokeTimeoutSeconds;
        this.workerIdleSeconds = workerIdleSeconds;
        this.gatewayIdleSeconds = gatewayIdleSeconds;
        this.manifestStrict = manifestStrict;
        this.gatewayRoot = gatewayRoot;
        this.pidDirectory = pidDirectory;
        this.allowedEvents = allowedEvents;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 从插件配置段解析配置。
     * <p>
     * <b>三个语言相关的参数由子类传进来</b>：解释器写在哪一个键上、它的默认值、
     * 以及脚本根目录的默认值。除这三件事之外，本方法对语言一无所知。
     *
     * @param configuration       插件配置段，可为 {@code null}
     * @param interpreterKey      解释器配置键，不可为 {@code null}
     * @param defaultInterpreter  解释器缺省值（如 {@code python3}），不可为 {@code null}
     * @param defaultScriptsRoot  脚本根目录缺省值（相对进程工作目录），不可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对时抛出
     */
    public static ScriptBridgeConfig from(Map<String, Object> configuration, String interpreterKey,
                                          String defaultInterpreter, String defaultScriptsRoot) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap()
                : configuration;
        return new ScriptBridgeConfig(
                normalizePath(requireText(values.get(KEY_SCRIPTS_ROOT), KEY_SCRIPTS_ROOT,
                        defaultScriptsRoot)),
                requireText(values.get(interpreterKey), interpreterKey, defaultInterpreter),
                seconds(values.get(KEY_INVOKE_TIMEOUT), KEY_INVOKE_TIMEOUT,
                        GatewaySettings.DEFAULT_INVOKE_TIMEOUT_SECONDS),
                seconds(values.get(KEY_WORKER_IDLE), KEY_WORKER_IDLE,
                        GatewaySettings.DEFAULT_WORKER_IDLE_SECONDS),
                seconds(values.get(KEY_GATEWAY_IDLE), KEY_GATEWAY_IDLE,
                        GatewaySettings.DEFAULT_GATEWAY_IDLE_SECONDS),
                bool(values.get(KEY_MANIFEST_STRICT), KEY_MANIFEST_STRICT,
                        GatewaySettings.DEFAULT_MANIFEST_STRICT),
                resolveGatewayRoot(values.get(KEY_GATEWAY_ROOT)),
                resolvePidDirectory(values.get(KEY_PID_DIRECTORY)),
                allowedEvents(values.get(KEY_EVENTS)),
                circuitBreaker(values.get(KEY_CIRCUIT_BREAKER)));
    }

    /**
     * 获取脚本根目录。
     * <p>
     * <b>不检查目录是否存在</b>：目录不存在等价于「还没有脚本插件」，是正常的冷启动状态，
     * 不是配置错误；检查留给启动期扫描，那里才能区分「不存在」与「存在但不可读」。
     *
     * @return 规范化绝对路径
     */
    public Path scriptsRoot() {
        return scriptsRoot;
    }

    /**
     * 获取解释器可执行文件。
     *
     * @return 解释器路径或命令名
     */
    public String interpreterPath() {
        return interpreterPath;
    }

    /**
     * 获取单次调用超时秒数。
     *
     * @return 超时秒数；{@code 0} 表示不超时
     */
    public int invokeTimeoutSeconds() {
        return invokeTimeoutSeconds;
    }

    /**
     * 获取 worker 空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    public int workerIdleSeconds() {
        return workerIdleSeconds;
    }

    /**
     * 获取网关空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    public int gatewayIdleSeconds() {
        return gatewayIdleSeconds;
    }

    /**
     * 获取网关资源抽取根目录。
     *
     * @return 抽取根目录
     */
    public Path gatewayRoot() {
        return gatewayRoot;
    }

    /**
     * 获取网关 PID 文件目录。
     *
     * @return 目录
     */
    public Path pidDirectory() {
        return pidDirectory;
    }

    /**
     * 组装下发给网关的设置。
     * <p>
     * 熔断参数不在这里：它只在 Java 侧使用，网关不需要知道——把它一起下发，
     * 就得让每种语言的网关各实现一遍同样的状态机，而三份实现里必然有两份会漂移。
     *
     * @param languageId 语言标识，用于推导 PID 文件名
     * @return 网关设置
     */
    public GatewaySettings gatewaySettings(String languageId) {
        return GatewaySettings.builder()
                .invokeTimeoutSeconds(invokeTimeoutSeconds)
                .workerIdleSeconds(workerIdleSeconds)
                .gatewayIdleSeconds(gatewayIdleSeconds)
                .manifestStrict(manifestStrict)
                .allowedEvents(allowedEvents)
                .pidFile(ScriptPidFiles.pathFor(pidDirectory, languageId).toString())
                .build();
    }

    /**
     * 获取熔断参数。
     *
     * @return 熔断参数
     */
    public CircuitBreakerSettings circuitBreakerSettings() {
        return circuitBreaker;
    }

    @Override
    public String toString() {
        return "ScriptBridgeConfig{scriptsRoot=" + scriptsRoot + ", interpreter=" + interpreterPath
                + ", invokeTimeout=" + invokeTimeoutSeconds + "s, workerIdle=" + workerIdleSeconds
                + "s, gatewayIdle=" + gatewayIdleSeconds + "s, manifestStrict=" + manifestStrict
                + ", gatewayRoot=" + gatewayRoot + ", pidDirectory=" + pidDirectory
                + ", " + circuitBreaker + '}';
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
     * 解析次数类配置：缺省用默认值，负数当场报错。
     * <p>
     * 与 {@link #seconds(Object, String, int)} 分开是因为报错文案：把一个「失败几次」的字段报成
     * 「必须是整数秒数」，会让写错的人按秒数去理解自己的配置。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 次数
     * @throws JellyfishException 值不是整数或为负时抛出
     */
    private static int count(Object raw, String key, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number)) {
            throw new JellyfishException(key + " 必须是非负整数，实际为 " + raw);
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
            return GatewayResources.defaultBaseDirectory();
        }
        return normalizePath(requireText(raw, KEY_GATEWAY_ROOT, ""));
    }

    /**
     * 解析网关 PID 文件目录。
     * <p>
     * 默认与网关资源目录<b>同级</b>的 {@code pids}，而不是它的子目录：资源目录名带内容摘要，
     * 改一个字节就换目录，而 PID 文件的全部价值就在「被下一次启动看见」。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 规范化绝对路径
     * @throws JellyfishException 值非法时抛出
     */
    private static Path resolvePidDirectory(Object raw) {
        if (raw == null) {
            return ScriptPidFiles.defaultDirectory();
        }
        return normalizePath(requireText(raw, KEY_PID_DIRECTORY, ""));
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
    private static List<String> allowedEvents(Object raw) {
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
        if (!(allow instanceof List)) {
            throw new JellyfishException(KEY_EVENTS + "." + KEY_EVENTS_ALLOW + " 必须是数组");
        }
        List<String> names = new ArrayList<String>();
        for (Object item : (List<Object>) allow) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new JellyfishException(KEY_EVENTS + "." + KEY_EVENTS_ALLOW
                        + " 只能包含非空字符串");
            }
            names.add(((String) item).trim());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * 解析熔断参数。
     * <p>
     * <b>它不在 {@link #gatewaySettings(String)} 里</b>：熔断是宿主侧的事，网关不需要知道；
     * 下发给网关就得让每种语言的网关各实现一遍同样的状态机。
     * <p>
     * 整个段缺失、段内单个字段缺失都走默认值；类型不对则当场报错——
     * 熔断参数写错的表现是「阈值悄悄变了」，而它的现场要等到某次故障才出现。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 熔断参数，保证非 {@code null}
     * @throws JellyfishException 结构或取值非法时抛出
     */
    @SuppressWarnings("unchecked")
    private static CircuitBreakerSettings circuitBreaker(Object raw) {
        CircuitBreakerSettings.Builder builder = CircuitBreakerSettings.builder();
        if (raw == null) {
            return builder.build();
        }
        if (!(raw instanceof Map)) {
            throw new JellyfishException(KEY_CIRCUIT_BREAKER + " 必须是对象，实际为 " + raw);
        }
        Map<String, Object> values = (Map<String, Object>) raw;
        return builder
                .failuresToOpen(count(values.get(KEY_FAILURES_TO_OPEN), key(KEY_FAILURES_TO_OPEN),
                        CircuitBreakerSettings.DEFAULT_FAILURES_TO_OPEN))
                .cooldownSeconds(seconds(values.get(KEY_COOLDOWN_SECONDS), key(KEY_COOLDOWN_SECONDS),
                        CircuitBreakerSettings.DEFAULT_COOLDOWN_SECONDS))
                .roundsToPermanent(count(values.get(KEY_ROUNDS_TO_PERMANENT),
                        key(KEY_ROUNDS_TO_PERMANENT),
                        CircuitBreakerSettings.DEFAULT_ROUNDS_TO_PERMANENT))
                .build();
    }

    /**
     * 拼出熔断参数的完整配置键，用于报错。
     *
     * @param field 字段名
     * @return 完整键名
     */
    private static String key(String field) {
        return KEY_CIRCUIT_BREAKER + "." + field;
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
        LOG.debug("脚本根目录解析为: {}", path);
        return path;
    }
}
