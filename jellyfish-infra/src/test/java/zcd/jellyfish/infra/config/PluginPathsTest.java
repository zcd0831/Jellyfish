package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginPaths} 的单元测试：验证缺省值、只读性与反序列化。
 *
 * @author zcd
 */
class PluginPathsTest {

    @Test
    void getRoots_should_return_empty_list_when_not_configured() {
        // When / Then
        assertTrue(new PluginPaths(null).getRoots().isEmpty());
        assertTrue(new PluginPaths(Collections.<String>emptyList()).getRoots().isEmpty());
    }

    @Test
    void getRoots_should_return_unmodifiable_list() {
        // Given
        PluginPaths paths = new PluginPaths(Collections.singletonList("plugins"));

        // When / Then
        List<String> roots = paths.getRoots();
        assertThrows(UnsupportedOperationException.class, () -> roots.add("other"));
    }

    @Test
    void constructor_should_copy_list_when_source_changed_afterwards() {
        // Given
        java.util.List<String> source = new ArrayList<>();
        source.add("plugins");

        // When
        PluginPaths paths = new PluginPaths(source);
        source.add("other");

        // Then
        assertEquals(Collections.singletonList("plugins"), paths.getRoots());
    }

    @Test
    void deserialization_should_bind_roots_in_order() {
        // Given
        String json = "{\"roots\":[\"plugins\",\"~/jellyfish/plugins\"]}";

        // When
        PluginPaths paths = ObjectMapperWrapper.readValue(json, PluginPaths.class);

        // Then
        assertEquals(Arrays.asList("plugins", "~/jellyfish/plugins"), paths.getRoots());
    }

    @Test
    void deserialization_should_tolerate_missing_roots() {
        // When
        PluginPaths paths = ObjectMapperWrapper.readValue("{}", PluginPaths.class);

        // Then
        assertTrue(paths.getRoots().isEmpty());
    }
}
