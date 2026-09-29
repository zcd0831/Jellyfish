package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥接插件配置解析的单元测试。
 * <p>
 * 重点是两类行为：缺省值必须可用（用户不写配置也能跑），以及<b>写错就当场报错</b>——
 * 配置值类型不对却静默退回默认值，是「配置不生效」这类问题的标准成因，这里用用例钉住不许回退。
 *
 * @author zcd
 */
@DisplayName("桥接插件配置解析")
class ScriptBridgeConfigTest {

    /** 本测试扮演的桥接插件所用的解释器配置键与两个默认值——实现里它们由子类传进来。 */
    private static final String KEY_INTERPRETER = "pythonPath";

    /** 解释器缺省值。 */
    private static final String DEFAULT_INTERPRETER = "python3";

    /** 脚本根目录缺省值。 */
    private static final String DEFAULT_SCRIPTS_ROOT = "scripts/python";

    /**
     * 按本测试扮演的那门语言解析配置。
     *
     * @param values 配置段，可为 {@code null}
     * @return 配置值对象
     */
    private static ScriptBridgeConfig parse(Map<String, Object> values) {
        return ScriptBridgeConfig.from(values, KEY_INTERPRETER, DEFAULT_INTERPRETER,
                DEFAULT_SCRIPTS_ROOT);
    }

    @Test
    @DisplayName("配置段缺失时应使用默认脚本根目录与默认解释器")
    void from_should_useDefaults_when_configurationIsNull() {
        ScriptBridgeConfig config = parse(null);

        assertEquals(Paths.get(DEFAULT_SCRIPTS_ROOT).toAbsolutePath().normalize(),
                config.scriptsRoot());
        assertEquals(DEFAULT_INTERPRETER, config.interpreterPath());
    }

    @Test
    @DisplayName("配置段为空映射时应使用默认值")
    void from_should_useDefaults_when_configurationIsEmpty() {
        ScriptBridgeConfig config = parse(Collections.<String, Object>emptyMap());

        assertEquals(DEFAULT_INTERPRETER, config.interpreterPath());
    }

    @Test
    @DisplayName("脚本根目录应以 ~ 开头时展开为用户主目录")
    void from_should_expandHomePrefix_when_scriptsRootStartsWithTilde() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, "~/scripts/python");

        ScriptBridgeConfig config = parse(values);

        assertEquals(Paths.get(System.getProperty("user.home"), "scripts/python")
                .toAbsolutePath().normalize(), config.scriptsRoot());
    }

    @Test
    @DisplayName("脚本根目录为相对路径时应规范化为绝对路径")
    void from_should_normalizeToAbsolutePath_when_scriptsRootIsRelative() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, "scripts/python");

        ScriptBridgeConfig config = parse(values);

        assertEquals(Paths.get("scripts/python").toAbsolutePath().normalize(), config.scriptsRoot());
    }

    @Test
    @DisplayName("解释器应取配置值")
    void from_should_takeConfiguredInterpreter_when_pythonPathIsSet() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(KEY_INTERPRETER, "/opt/venv/bin/python3");

        ScriptBridgeConfig config = parse(values);

        assertEquals("/opt/venv/bin/python3", config.interpreterPath());
    }

    @Test
    @DisplayName("脚本根目录类型不对时应报错，而不是静默退回默认值")
    void from_should_throwJellyfishException_when_scriptsRootIsNotText() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, 42);

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("脚本根目录为空白时应报错")
    void from_should_throwJellyfishException_when_scriptsRootIsBlank() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, "   ");

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("解释器为空白时应报错")
    void from_should_throwJellyfishException_when_pythonPathIsBlank() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(KEY_INTERPRETER, "");

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("网关相关键缺失时应使用与运行时一致的默认值")
    void from_should_useRuntimeDefaults_when_gatewayKeysAreAbsent() {
        ScriptBridgeConfig config = parse(null);

        // 默认值取自 GatewaySettings 而不是本地再写一份：两处各写一份的结果是
        // 「用户不配时行为取决于哪个类先被改」，而那种不一致没有任何测试能发现
        assertEquals(GatewaySettings.DEFAULT_INVOKE_TIMEOUT_SECONDS,
                config.invokeTimeoutSeconds());
        assertEquals(GatewaySettings.DEFAULT_WORKER_IDLE_SECONDS,
                config.workerIdleSeconds());
        assertEquals(GatewaySettings.DEFAULT_GATEWAY_IDLE_SECONDS,
                config.gatewayIdleSeconds());
        assertEquals(GatewayResources.defaultBaseDirectory(), config.gatewayRoot());
    }

    @Test
    @DisplayName("网关相关键应被解析进设置并下发")
    void gatewaySettings_should_carryConfiguredValues() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(7));
        values.put(ScriptBridgeConfig.KEY_WORKER_IDLE, Integer.valueOf(11));
        values.put(ScriptBridgeConfig.KEY_GATEWAY_IDLE, Integer.valueOf(13));
        values.put(ScriptBridgeConfig.KEY_MANIFEST_STRICT, Boolean.FALSE);
        Map<String, Object> events = new LinkedHashMap<String, Object>();
        events.put(ScriptBridgeConfig.KEY_EVENTS_ALLOW, java.util.Arrays.asList("SessionCreatedEvent"));
        values.put(ScriptBridgeConfig.KEY_EVENTS, events);

        ScriptBridgeConfig config = parse(values);
        GatewaySettings settings = config.gatewaySettings("demo");

        assertEquals(7000L, settings.invokeTimeoutMillis());
        assertEquals(11, settings.workerIdleSeconds());
        assertEquals(13, settings.gatewayIdleSeconds());
        assertFalse(settings.manifestStrict());
        assertEquals(java.util.Arrays.asList("SessionCreatedEvent"), settings.allowedEvents());
    }

    @Test
    @DisplayName("PID 文件目录应默认与网关资源目录同级，而不是它的子目录")
    void pidDirectory_should_defaultToSiblingOfGatewayRoot() {
        ScriptBridgeConfig config = parse(null);

        assertEquals(ScriptPidFiles.defaultDirectory(), config.pidDirectory());
        assertEquals(Paths.get(System.getProperty("user.home"), ".jellyfish", "pids")
                .toAbsolutePath().normalize(), config.pidDirectory());
        // 与网关资源目录同级：后者按内容摘要分目录，改一个字节就换目录，
        // 而 PID 文件必须“下一次启动还看得见”才有价值
        assertEquals(config.gatewayRoot().getParent(), config.pidDirectory().getParent());
    }

    @Test
    @DisplayName("PID 文件路径应按语言拼在配置的目录下，并随设置下发")
    void gatewaySettings_should_carryPidFile_when_pidDirectoryConfigured() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, "/tmp/jf-pids");

        ScriptBridgeConfig config = parse(values);
        GatewaySettings settings = config.gatewaySettings("demo");

        assertEquals(Paths.get("/tmp/jf-pids").toAbsolutePath().normalize(), config.pidDirectory());
        assertEquals(config.pidDirectory().resolve("script-demo.pid").toString(), settings.pidFile());
        assertTrue(settings.toJson().has("pidFile"), settings.toJson().toString());
    }

    @Test
    @DisplayName("PID 文件目录类型不对时应报错，而不是静默退回默认值")
    void from_should_throwJellyfishException_when_pidDirectoryIsNotText() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, 42);

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("秒数为负应报错，而不是当成 0")
    void from_should_rejectNegativeSeconds() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(-1));

        // 把负数当 0 处理会让「不超时」这个危险配置静默生效，因此必须报错
        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("秒数写成字符串应报错，而不是静默退回默认值")
    void from_should_rejectNonNumericSeconds() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_WORKER_IDLE, "300");

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("严格校验标志写成字符串应报错")
    void from_should_rejectNonBooleanStrictFlag() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_MANIFEST_STRICT, "true");

        assertThrows(JellyfishException.class, () -> parse(values));
    }

    @Test
    @DisplayName("事件白名单结构非法应报错")
    void from_should_rejectMalformedEventAllowList() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_EVENTS, java.util.Arrays.asList("x"));

        assertThrows(JellyfishException.class, () -> parse(values));

        Map<String, Object> badEntry = new LinkedHashMap<String, Object>();
        badEntry.put(ScriptBridgeConfig.KEY_EVENTS_ALLOW, java.util.Arrays.asList("ok", 1));
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put(ScriptBridgeConfig.KEY_EVENTS, badEntry);
        assertThrows(JellyfishException.class, () -> parse(second));
    }

    @Test
    @DisplayName("网关资源根目录应支持 ~ 展开")
    void gatewayRoot_should_expandTilde() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, "~/custom-gateway");

        ScriptBridgeConfig config = parse(values);

        assertEquals(Paths.get(System.getProperty("user.home"), "custom-gateway"),
                config.gatewayRoot());
    }

    @Test
    @DisplayName("熔断参数缺失时应使用与运行时一致的默认值")
    void circuitBreaker_should_useRuntimeDefaults_whenSectionIsAbsent() {
        CircuitBreakerSettings defaults =
                CircuitBreakerSettings.defaults();

        CircuitBreakerSettings absent = parse(null).circuitBreakerSettings();
        CircuitBreakerSettings empty =
                parse(Collections.<String, Object>emptyMap()).circuitBreakerSettings();

        assertEquals(defaults.failuresToOpen(), absent.failuresToOpen());
        assertEquals(defaults.cooldownSeconds(), absent.cooldownSeconds());
        assertEquals(defaults.roundsToPermanent(), absent.roundsToPermanent());
        assertEquals(defaults.failuresToOpen(), empty.failuresToOpen());
    }

    @Test
    @DisplayName("熔断参数应被解析，且只读它下面那一层")
    void circuitBreaker_should_carryConfiguredValues() {
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN, Integer.valueOf(5));
        breaker.put(ScriptBridgeConfig.KEY_COOLDOWN_SECONDS, Integer.valueOf(7));
        breaker.put(ScriptBridgeConfig.KEY_ROUNDS_TO_PERMANENT, Integer.valueOf(1));
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);

        CircuitBreakerSettings settings =
                parse(values).circuitBreakerSettings();

        assertEquals(5, settings.failuresToOpen());
        assertEquals(7, settings.cooldownSeconds());
        assertEquals(1, settings.roundsToPermanent());
    }

    @Test
    @DisplayName("熔断段写成非对象应报错，而不是当成「没配」")
    void circuitBreaker_should_rejectNonObjectSection() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, "2");

        String message = assertThrows(JellyfishException.class,
                () -> parse(values)).getMessage();

        assertTrue(message.contains(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER), message);
    }

    @Test
    @DisplayName("熔断次数写成非整数应报错，且报错文案说的是「整数」而不是「秒数」")
    void circuitBreaker_should_rejectNonNumericCount() {
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN, "两次");
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);

        String message = assertThrows(JellyfishException.class,
                () -> parse(values)).getMessage();

        // 把一个「失败几次」的字段报成「必须是整数秒数」，会让写错的人按错误的单位去理解配置
        assertTrue(message.contains("非负整数"), message);
        assertTrue(message.contains(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN), message);
    }

    @Test
    @DisplayName("熔断参数为负应报错，而不是当成 0（0 是「关闭」的合法写法）")
    void circuitBreaker_should_rejectNegativeValues() {
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_COOLDOWN_SECONDS, Integer.valueOf(-1));
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);

        String message = assertThrows(JellyfishException.class,
                () -> parse(values)).getMessage();

        assertTrue(message.contains(ScriptBridgeConfig.KEY_COOLDOWN_SECONDS), message);
    }

    @Test
    @DisplayName("熔断参数配成 0 应原样生效，而不是退回默认值")
    void circuitBreaker_should_keepZeroValues() {
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN, Integer.valueOf(0));
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);

        CircuitBreakerSettings settings =
                parse(values).circuitBreakerSettings();

        assertEquals(0, settings.failuresToOpen(), "0 是「显式关闭熔断」，不能被当成缺省");
    }

    @Test
    @DisplayName("解释器键与默认值来自调用方：别的语言的键不会被读到")
    void from_should_ignoreOtherLanguagesInterpreterKey_when_keyIsParameterized() {
        // 这条是「上收」这件事的安全网：如果哪一天有人在公共实现里又写死一个 pythonPath，
        // 第二门语言的解释器配置就会静默失效（用户改了配置、行为不变），而那种 bug 的表现
        // 与「配置系统坏了」完全一样
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(KEY_INTERPRETER, "/opt/venv/bin/python3");

        ScriptBridgeConfig node = ScriptBridgeConfig.from(values, "nodePath", "node", "scripts/node");

        assertEquals("node", node.interpreterPath());
        assertEquals(Paths.get("scripts/node").toAbsolutePath().normalize(), node.scriptsRoot());
        assertEquals("/opt/venv/bin/python3", parse(values).interpreterPath());
    }
}
