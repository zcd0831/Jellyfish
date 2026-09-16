package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
