package zcd.jellyfish.infra.registry;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistryKey} 的单元测试：验证空值校验、相等性与诊断文本。
 *
 * @author zcd
 */
class RegistryKeyTest {

    @Test
    void of_should_reject_null_type() {
        // When / Then
        assertThrows(NullPointerException.class, () -> RegistryKey.of(null, "calc"));
    }

    @Test
    void getters_should_return_constructed_values() {
        // When
        RegistryKey key = RegistryKey.of(CommandRequest.class, "calc");

        // Then
        assertEquals(CommandRequest.class, key.getType());
        assertEquals("calc", key.getRouteKey());
    }

    @Test
    void getRouteKey_should_return_null_when_type_wide() {
        // When / Then
        assertNull(RegistryKey.of(CommandRequest.class, null).getRouteKey());
    }

    @Test
    void equals_and_hashCode_should_match_when_type_and_route_key_are_equal() {
        // Given
        RegistryKey key = RegistryKey.of(CommandRequest.class, "calc");
        RegistryKey same = RegistryKey.of(CommandRequest.class, "calc");

        // Then
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
    }

    @Test
    void equals_should_differ_when_route_key_or_type_differs() {
        // Given
        RegistryKey key = RegistryKey.of(CommandRequest.class, "calc");

        // Then
        assertNotEquals(key, RegistryKey.of(CommandRequest.class, "other"));
        assertNotEquals(key, RegistryKey.of(ToolCallRequest.class, "calc"));
        assertNotEquals(RegistryKey.of(CommandRequest.class, null), RegistryKey.of(ToolCallRequest.class, null));
    }

    @Test
    void equals_should_return_false_for_other_types() {
        // Given
        RegistryKey key = RegistryKey.of(CommandRequest.class, "calc");

        // Then
        assertTrue(key.equals(key));
        assertFalse(key.equals("calc"));
    }

    @Test
    void toString_should_render_route_key_and_type_wide_marker() {
        // When / Then
        assertEquals("CommandRequest#calc", RegistryKey.of(CommandRequest.class, "calc").toString());
        assertEquals("CommandRequest#<type-wide>", RegistryKey.of(CommandRequest.class, null).toString());
    }
}
