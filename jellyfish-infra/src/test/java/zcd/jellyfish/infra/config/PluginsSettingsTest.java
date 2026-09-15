package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginsSettings} 的单元测试：验证缺省值、只读性与反序列化。
 *
 * @author zcd
 */
class PluginsSettingsTest {

    @Test
    void getters_should_return_empty_values_when_not_set() {
        // When
        PluginsSettings settings = new PluginsSettings(null, null, null);

        // Then
        assertTrue(settings.getEnabled().isEmpty());
        assertTrue(settings.getDisabled().isEmpty());
        assertTrue(settings.getConfigurations().isEmpty());
        assertTrue(settings.isEmpty());
    }

    @Test
    void getEnabled_should_return_unmodifiable_list() {
        // Given
        PluginsSettings settings = new PluginsSettings(Collections.singletonList("plugin-a"), null, null);

        // When / Then
        List<String> enabled = settings.getEnabled();
        assertThrows(UnsupportedOperationException.class, () -> enabled.add("plugin-b"));
    }

    @Test
    void getConfigurations_should_return_unmodifiable_map() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", Collections.<String, Object>singletonMap("k", "v"));
        PluginsSettings settings = new PluginsSettings(null, null, configurations);

        // When / Then
        Map<String, Map<String, Object>> returned = settings.getConfigurations();
        assertThrows(UnsupportedOperationException.class,
                () -> returned.put("plugin-b", Collections.<String, Object>emptyMap()));
        assertEquals(Collections.singletonMap("k", "v"), returned.get("plugin-a"));
    }

    @Test
    void isEmpty_should_return_false_when_any_section_configured() {
        // Given / When / Then
        assertFalse(new PluginsSettings(Collections.singletonList("a"), null, null).isEmpty());
        assertFalse(new PluginsSettings(null, Collections.singletonList("a"), null).isEmpty());
        assertFalse(new PluginsSettings(null, null,
                Collections.singletonMap("plugin-a", Collections.<String, Object>emptyMap())).isEmpty());
    }

    @Test
    void isEnabledDeclared_should_distinguish_absent_from_declared_empty() {
        // When / Then：未声明表示不额外限定，声明为空表示一个都不启用
        assertFalse(new PluginsSettings(null, null, null).isEnabledDeclared());

        PluginsSettings declaredEmpty = new PluginsSettings(Collections.<String>emptyList(), null, null);
        assertTrue(declaredEmpty.isEnabledDeclared());
        assertTrue(declaredEmpty.getEnabled().isEmpty());
    }

    @Test
    void isEmpty_should_be_false_when_any_list_declared_empty() {
        // Given / When / Then：显式写了空数组也算配过
        assertFalse(new PluginsSettings(Collections.<String>emptyList(), null, null).isEmpty());
        assertFalse(new PluginsSettings(null, Collections.<String>emptyList(), null).isEmpty());
    }

    @Test
    void deserialization_should_bind_three_sections() {
        // Given
        String json = "{\"enabled\":[\"plugin-a\"],\"disabled\":[\"plugin-b\"],"
                + "\"configurations\":{\"plugin-a\":{\"readOnlyTools\":[\"read_file\"]}}}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-a"), settings.getEnabled());
        assertEquals(Collections.singletonList("plugin-b"), settings.getDisabled());
        assertEquals(Collections.singletonList("read_file"),
                settings.getConfigurations().get("plugin-a").get("readOnlyTools"));
    }

    @Test
    void deserialization_should_ignore_legacy_roots_key() {
        // Given：扫描目录已迁到 config.json，历史文件里残留的 roots 不再有语义
        String json = "{\"roots\":[\"plugins\"],\"enabled\":[\"plugin-a\"]}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-a"), settings.getEnabled());
        assertTrue(settings.getConfigurations().isEmpty());
    }

    @Test
    void deserialization_should_tolerate_missing_sections() {
        // Given
        String json = "{}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertTrue(settings.isEmpty());
        assertEquals(Arrays.asList(), settings.getEnabled());
        assertFalse(settings.isEnabledDeclared());
    }

    @Test
    void deserialization_should_keep_declared_empty_lists() {
        // Given：空数组必须与「字段缺失」区别开，否则会被读成未配置而把所有插件放进
        String json = "{\"enabled\":[],\"disabled\":[]}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertTrue(settings.isEnabledDeclared());
        assertTrue(settings.isDisabledDeclared());
        assertTrue(settings.getEnabled().isEmpty());
        assertFalse(settings.isEmpty());
    }
}
