package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        PluginsSettings settings = new PluginsSettings(null, null, null, null);

        // Then
        assertTrue(settings.getRoots().isEmpty());
        assertTrue(settings.getEnabled().isEmpty());
        assertTrue(settings.getDisabled().isEmpty());
        assertTrue(settings.getConfigurations().isEmpty());
        assertTrue(settings.isEmpty());
    }

    @Test
    void getRoots_should_return_unmodifiable_list() {
        // Given
        PluginsSettings settings = new PluginsSettings(Collections.singletonList("plugins"), null, null, null);

        // When / Then
        List<String> roots = settings.getRoots();
        assertThrows(UnsupportedOperationException.class, () -> roots.add("other"));
    }

    @Test
    void getConfigurations_should_return_unmodifiable_map() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", Collections.<String, Object>singletonMap("k", "v"));
        PluginsSettings settings = new PluginsSettings(null, null, null, configurations);

        // When / Then
        Map<String, Map<String, Object>> returned = settings.getConfigurations();
        assertThrows(UnsupportedOperationException.class,
                () -> returned.put("plugin-b", Collections.<String, Object>emptyMap()));
        assertEquals(Collections.singletonMap("k", "v"), returned.get("plugin-a"));
    }

    @Test
    void isEmpty_should_return_false_when_any_section_configured() {
        // Given / When / Then
        assertTrue(!new PluginsSettings(Collections.singletonList("plugins"), null, null, null).isEmpty());
        assertTrue(!new PluginsSettings(null, Collections.singletonList("a"), null, null).isEmpty());
        assertTrue(!new PluginsSettings(null, null, Collections.singletonList("a"), null).isEmpty());
        assertTrue(!new PluginsSettings(null, null, null,
                Collections.singletonMap("plugin-a", Collections.<String, Object>emptyMap())).isEmpty());
    }

    @Test
    void deserialization_should_bind_four_sections() {
        // Given
        String json = "{\"roots\":[\"plugins\",\"extra\"],\"enabled\":[\"plugin-a\"],\"disabled\":[\"plugin-b\"],"
                + "\"configurations\":{\"plugin-a\":{\"readOnlyTools\":[\"read_file\"]}}}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertEquals(Arrays.asList("plugins", "extra"), settings.getRoots());
        assertEquals(Collections.singletonList("plugin-a"), settings.getEnabled());
        assertEquals(Collections.singletonList("plugin-b"), settings.getDisabled());
        assertEquals(Collections.singletonList("read_file"),
                settings.getConfigurations().get("plugin-a").get("readOnlyTools"));
    }

    @Test
    void deserialization_should_tolerate_missing_sections() {
        // Given
        String json = "{}";

        // When
        PluginsSettings settings = ObjectMapperWrapper.readValue(json, PluginsSettings.class);

        // Then
        assertTrue(settings.isEmpty());
    }
}
