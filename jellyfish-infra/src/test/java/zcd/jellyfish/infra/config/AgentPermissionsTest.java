package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentPermissions} 的单元测试：验证缺省值、只读性与反序列化。
 *
 * @author zcd
 */
class AgentPermissionsTest {

    @Test
    void getters_should_return_empty_lists_when_not_set() {
        // When
        AgentPermissions permissions = new AgentPermissions(null, null, null);

        // Then
        assertTrue(permissions.getDeniedTools().isEmpty());
        assertTrue(permissions.getAskTools().isEmpty());
        assertTrue(permissions.getAllowedTools().isEmpty());
    }

    @Test
    void isEmpty_should_return_true_only_when_all_groups_empty() {
        // Given / When / Then
        assertTrue(new AgentPermissions(null, null, null).isEmpty());
        assertFalse(new AgentPermissions(Collections.singletonList("bash"), null, null).isEmpty());
        assertFalse(new AgentPermissions(null, Collections.singletonList("write_file"), null).isEmpty());
        assertFalse(new AgentPermissions(null, null, Collections.singletonList("read_file")).isEmpty());
    }

    @Test
    void getDeniedTools_should_return_unmodifiable_list() {
        // Given
        AgentPermissions permissions = new AgentPermissions(Collections.singletonList("bash"), null, null);

        // When / Then
        List<String> denied = permissions.getDeniedTools();
        assertThrows(UnsupportedOperationException.class, () -> denied.add("rm"));
    }

    @Test
    void getDeniedTools_should_not_reflect_later_changes_of_source() {
        // Given
        List<String> source = new java.util.ArrayList<>(Collections.singletonList("bash"));
        AgentPermissions permissions = new AgentPermissions(source, null, null);

        // When
        source.add("rm");

        // Then
        assertEquals(1, permissions.getDeniedTools().size());
    }

    @Test
    void toString_should_render_all_three_groups() {
        // Given
        AgentPermissions permissions = new AgentPermissions(Collections.singletonList("bash"),
                Collections.singletonList("write_file"), Collections.singletonList("read_file"));

        // When
        String rendered = permissions.toString();

        // Then
        assertTrue(rendered.contains("bash"));
        assertTrue(rendered.contains("write_file"));
        assertTrue(rendered.contains("read_file"));
    }

    @Test
    void deserialization_should_bind_three_groups() {
        // Given
        String json = "{\"deniedTools\":[\"bash\"],\"askTools\":[\"write_file\"],"
                + "\"allowedTools\":[\"read_file\",\"grep\"]}";

        // When
        AgentPermissions permissions = ObjectMapperWrapper.readValue(json, AgentPermissions.class);

        // Then
        assertEquals(Collections.singletonList("bash"), permissions.getDeniedTools());
        assertEquals(Collections.singletonList("write_file"), permissions.getAskTools());
        assertEquals(Arrays.asList("read_file", "grep"), permissions.getAllowedTools());
    }

    @Test
    void deserialization_should_tolerate_missing_groups() {
        // Given
        String json = "{}";

        // When
        AgentPermissions permissions = ObjectMapperWrapper.readValue(json, AgentPermissions.class);

        // Then
        assertTrue(permissions.isEmpty());
    }
}
