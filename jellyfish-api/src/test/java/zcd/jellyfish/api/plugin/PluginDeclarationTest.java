package zcd.jellyfish.api.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginDeclaration} 的单元测试：验证取值契约与不可变性。
 *
 * @author zcd
 */
class PluginDeclarationTest {

    @Test
    void of_should_reject_blank_plugin_id() {
        // When / Then
        assertThrows(JellyfishException.class, () -> PluginDeclaration.of(null));
        assertThrows(JellyfishException.class, () -> PluginDeclaration.of("  "));
        assertThrows(JellyfishException.class, () -> PluginDeclaration.of(null, null));
    }

    @Test
    void of_should_treat_null_configuration_as_empty() {
        // When
        PluginDeclaration declaration = PluginDeclaration.of("sample");

        // Then
        assertEquals("sample", declaration.getPluginId());
        assertTrue(declaration.getConfiguration().isEmpty());
    }

    @Test
    void getters_should_return_unmodifiable_configuration() {
        // Given
        PluginDeclaration declaration = PluginDeclaration.of("sample", configurationOf("precision", 4));

        // Then
        assertThrows(UnsupportedOperationException.class, () -> declaration.getConfiguration().put("x", "y"));
    }

    @Test
    void configuration_should_be_copied_from_source() {
        // Given：声明不可变，外部持有的映射被改动后不得影响已构造的声明
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("precision", 4);
        PluginDeclaration declaration = PluginDeclaration.of("sample", source);

        // When
        source.put("injected", true);

        // Then
        assertFalse(declaration.getConfiguration().containsKey("injected"));
    }

    @Test
    void toString_should_not_leak_configuration() {
        // Given：配置段可能含密钥，不允许出现在诊断输出里
        PluginDeclaration declaration = PluginDeclaration.of("sample", configurationOf("apiKey", "super-secret"));

        // Then
        assertFalse(declaration.toString().contains("super-secret"));
        assertTrue(declaration.toString().contains("sample"));
    }

    @Test
    void configuration_should_be_readable() {
        // When
        PluginDeclaration declaration = PluginDeclaration.of("sample", configurationOf("precision", 4));

        // Then
        assertEquals(4, declaration.getConfiguration().get("precision"));
    }

    /**
     * 构造测试用配置段。
     *
     * @param key   键
     * @param value 值
     * @return 配置段
     */
    private static Map<String, Object> configurationOf(String key, Object value) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put(key, value);
        return configuration;
    }
}
