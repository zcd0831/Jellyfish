package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettingsBinder} 的单元测试：验证环境变量占位符的替换规则，覆盖必需、可选、转义与非法值场景。
 *
 * @author zcd
 */
@SuppressWarnings("unchecked")
class SettingsBinderTest {

    private static final String KEY = "JELLYFISH_TEST_KEY";

    private static final String MISSING_KEY = "JELLYFISH_TEST_MISSING_KEY";

    /** 固定环境变量取值函数，避免测试依赖真实进程环境。 */
    private static final Function<String, String> ENV_PROVIDER = name -> {
        if (KEY.equals(name)) {
            return "secret";
        }
        if ("JELLYFISH_TEST_SPECIAL".equals(name)) {
            return "a\"b\\c";
        }
        return null;
    };

    /** 被测绑定器。 */
    private final SettingsBinder settingsBinder = new SettingsBinder(ENV_PROVIDER);

    @Test
    void bind_should_return_null_when_json_blank() {
        assertNull(settingsBinder.bind("   ", Map.class, null));
    }

    @Test
    void bind_should_substitute_placeholder_when_env_exists() {
        // When
        Map<String, Object> result = settingsBinder.bind("{\"k\":\"${" + KEY + "}\"}", Map.class, null);

        // Then
        assertEquals("secret", result.get("k"));
    }

    @Test
    void bind_should_substitute_all_placeholders_when_repeated() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"k\":\"${" + KEY + "}-${" + KEY + "}\"}", Map.class, null);

        // Then
        assertEquals("secret-secret", result.get("k"));
    }

    @Test
    void bind_should_throw_when_required_env_missing() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> settingsBinder.bind("{\"k\":\"${" + MISSING_KEY + "}\"}", Map.class, "test.json"));
    }

    @Test
    void bind_should_use_default_when_optional_env_missing() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"k\":\"${" + MISSING_KEY + ":-fallback}\"}", Map.class, null);

        // Then
        assertEquals("fallback", result.get("k"));
    }

    @Test
    void bind_should_use_empty_default_when_optional_env_missing_and_default_blank() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"k\":\"${" + MISSING_KEY + ":-}\"}", Map.class, null);

        // Then
        assertEquals("", result.get("k"));
    }

    @Test
    void bind_should_keep_literal_when_placeholder_escaped() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"k\":\"\\\\${" + KEY + "}\"}", Map.class, null);

        // Then
        assertEquals("${" + KEY + "}", result.get("k"));
    }

    @Test
    void bind_should_keep_value_with_quotes_and_backslash() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"k\":\"${JELLYFISH_TEST_SPECIAL}\"}", Map.class, null);

        // Then：值在解析后写入，含引号与反斜杠也不会破坏 JSON
        assertEquals("a\"b\\c", result.get("k"));
    }

    @Test
    void bind_should_not_substitute_placeholder_in_json_key() {
        // When
        Map<String, Object> result = settingsBinder.bind(
                "{\"${" + KEY + "}\":\"v\"}", Map.class, null);

        // Then
        assertTrue(result.containsKey("${" + KEY + "}"));
        assertEquals("v", result.get("${" + KEY + "}"));
    }
}
