package zcd.jellyfish.plugin.python;

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

/**
 * Python 桥接插件配置解析的单元测试。
 * <p>
 * 重点是两类行为：缺省值必须可用（用户不写配置也能跑），以及<b>写错就当场报错</b>——
 * 配置值类型不对却静默退回默认值，是「配置不生效」这类问题的标准成因，这里用用例钉住不许回退。
 *
 * @author zcd
 */
@DisplayName("Python 桥接插件配置解析")
class PythonConfigTest {

    @Test
    @DisplayName("配置段缺失时应使用默认脚本根目录与默认解释器")
    void from_should_useDefaults_when_configurationIsNull() {
        PythonConfig config = PythonConfig.from(null);

        assertEquals(Paths.get(PythonConfig.DEFAULT_SCRIPTS_ROOT).toAbsolutePath().normalize(),
                config.scriptsRoot());
        assertEquals(PythonConfig.DEFAULT_PYTHON_PATH, config.pythonPath());
    }

    @Test
    @DisplayName("配置段为空映射时应使用默认值")
    void from_should_useDefaults_when_configurationIsEmpty() {
        PythonConfig config = PythonConfig.from(Collections.<String, Object>emptyMap());

        assertEquals(PythonConfig.DEFAULT_PYTHON_PATH, config.pythonPath());
    }

    @Test
    @DisplayName("脚本根目录应以 ~ 开头时展开为用户主目录")
    void from_should_expandHomePrefix_when_scriptsRootStartsWithTilde() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_SCRIPTS_ROOT, "~/scripts/python");

        PythonConfig config = PythonConfig.from(values);

        assertEquals(Paths.get(System.getProperty("user.home"), "scripts/python")
                .toAbsolutePath().normalize(), config.scriptsRoot());
    }

    @Test
    @DisplayName("脚本根目录为相对路径时应规范化为绝对路径")
    void from_should_normalizeToAbsolutePath_when_scriptsRootIsRelative() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_SCRIPTS_ROOT, "scripts/python");

        PythonConfig config = PythonConfig.from(values);

        assertEquals(Paths.get("scripts/python").toAbsolutePath().normalize(), config.scriptsRoot());
    }

    @Test
    @DisplayName("解释器应取配置值")
    void from_should_takeConfiguredInterpreter_when_pythonPathIsSet() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_PYTHON_PATH, "/opt/venv/bin/python3");

        PythonConfig config = PythonConfig.from(values);

        assertEquals("/opt/venv/bin/python3", config.pythonPath());
    }

    @Test
    @DisplayName("脚本根目录类型不对时应报错，而不是静默退回默认值")
    void from_should_throwJellyfishException_when_scriptsRootIsNotText() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_SCRIPTS_ROOT, 42);

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("脚本根目录为空白时应报错")
    void from_should_throwJellyfishException_when_scriptsRootIsBlank() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_SCRIPTS_ROOT, "   ");

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("解释器为空白时应报错")
    void from_should_throwJellyfishException_when_pythonPathIsBlank() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_PYTHON_PATH, "");

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("网关相关键缺失时应使用与运行时一致的默认值")
    void from_should_useRuntimeDefaults_when_gatewayKeysAreAbsent() {
        PythonConfig config = PythonConfig.from(null);

        // 默认值取自 GatewaySettings 而不是本地再写一份：两处各写一份的结果是
        // 「用户不配时行为取决于哪个类先被改」，而那种不一致没有任何测试能发现
        assertEquals(zcd.jellyfish.script.GatewaySettings.DEFAULT_INVOKE_TIMEOUT_SECONDS,
                config.invokeTimeoutSeconds());
        assertEquals(zcd.jellyfish.script.GatewaySettings.DEFAULT_WORKER_IDLE_SECONDS,
                config.workerIdleSeconds());
        assertEquals(zcd.jellyfish.script.GatewaySettings.DEFAULT_GATEWAY_IDLE_SECONDS,
                config.gatewayIdleSeconds());
        assertEquals(zcd.jellyfish.script.GatewayResources.defaultBaseDirectory(), config.gatewayRoot());
    }

    @Test
    @DisplayName("网关相关键应被解析进设置并下发")
    void gatewaySettings_should_carryConfiguredValues() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(7));
        values.put(PythonConfig.KEY_WORKER_IDLE, Integer.valueOf(11));
        values.put(PythonConfig.KEY_GATEWAY_IDLE, Integer.valueOf(13));
        values.put(PythonConfig.KEY_MANIFEST_STRICT, Boolean.FALSE);
        Map<String, Object> events = new LinkedHashMap<String, Object>();
        events.put(PythonConfig.KEY_EVENTS_ALLOW, java.util.Arrays.asList("SessionCreatedEvent"));
        values.put(PythonConfig.KEY_EVENTS, events);

        PythonConfig config = PythonConfig.from(values);
        zcd.jellyfish.script.GatewaySettings settings = config.gatewaySettings();

        assertEquals(7000L, settings.invokeTimeoutMillis());
        assertEquals(11, settings.workerIdleSeconds());
        assertEquals(13, settings.gatewayIdleSeconds());
        assertFalse(settings.manifestStrict());
        assertEquals(java.util.Arrays.asList("SessionCreatedEvent"), settings.allowedEvents());
    }

    @Test
    @DisplayName("秒数为负应报错，而不是当成 0")
    void from_should_rejectNegativeSeconds() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(-1));

        // 把负数当 0 处理会让「不超时」这个危险配置静默生效，因此必须报错
        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("秒数写成字符串应报错，而不是静默退回默认值")
    void from_should_rejectNonNumericSeconds() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_WORKER_IDLE, "300");

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("严格校验标志写成字符串应报错")
    void from_should_rejectNonBooleanStrictFlag() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_MANIFEST_STRICT, "true");

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));
    }

    @Test
    @DisplayName("事件白名单结构非法应报错")
    void from_should_rejectMalformedEventAllowList() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_EVENTS, java.util.Arrays.asList("x"));

        assertThrows(JellyfishException.class, () -> PythonConfig.from(values));

        Map<String, Object> badEntry = new LinkedHashMap<String, Object>();
        badEntry.put(PythonConfig.KEY_EVENTS_ALLOW, java.util.Arrays.asList("ok", 1));
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put(PythonConfig.KEY_EVENTS, badEntry);
        assertThrows(JellyfishException.class, () -> PythonConfig.from(second));
    }

    @Test
    @DisplayName("网关资源根目录应支持 ~ 展开")
    void gatewayRoot_should_expandTilde() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PythonConfig.KEY_GATEWAY_ROOT, "~/custom-gateway");

        PythonConfig config = PythonConfig.from(values);

        assertEquals(Paths.get(System.getProperty("user.home"), "custom-gateway"),
                config.gatewayRoot());
    }
}
