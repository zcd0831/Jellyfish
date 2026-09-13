package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.PluginRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackKey} 的单元测试：验证空值校验、相等性与诊断文本。
 *
 * @author zcd
 */
class CallbackKeyTest {

    @Test
    void of_should_throw_when_command_type_is_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> CallbackKey.of(null, "calc"));
    }

    @Test
    void getters_should_return_constructed_values() {
        // When
        CallbackKey key = CallbackKey.of(PluginRequest.class, "calc");

        // Then
        assertEquals(PluginRequest.class, key.getCallbackType());
        assertEquals("calc", key.getRouteKey());
    }

    @Test
    void getRouteKey_should_return_null_when_type_unique() {
        // When
        CallbackKey key = CallbackKey.of(PluginRequest.class, null);

        // Then
        assertNull(key.getRouteKey());
    }

    @Test
    void equals_and_hashCode_should_match_when_same_type_and_route_key() {
        // Given
        CallbackKey key = CallbackKey.of(PluginRequest.class, "calc");
        CallbackKey same = CallbackKey.of(PluginRequest.class, "calc");

        // Then
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
    }

    @Test
    void equals_should_differ_when_route_key_differs() {
        // Given
        CallbackKey key = CallbackKey.of(PluginRequest.class, "calc");
        CallbackKey other = CallbackKey.of(PluginRequest.class, "other");

        // Then
        assertNotEquals(key, other);
    }

    @Test
    void equals_should_differ_when_command_type_differs() {
        // Given
        CallbackKey key = CallbackKey.of(PluginRequest.class, null);
        CallbackKey other = CallbackKey.of(OtherCallback.class, null);

        // Then
        assertNotEquals(key, other);
    }

    @Test
    void equals_should_return_false_for_other_types() {
        // Given
        CallbackKey key = CallbackKey.of(PluginRequest.class, "calc");

        // Then
        assertFalse(key.equals("calc"));
        assertTrue(key.equals(key));
    }

    @Test
    void toString_should_render_route_key_and_type_unique_marker() {
        // When / Then
        assertEquals("PluginRequest#calc", CallbackKey.of(PluginRequest.class, "calc").toString());
        assertEquals("PluginRequest#<type-unique>", CallbackKey.of(PluginRequest.class, null).toString());
    }

    /**
     * 另一个命令类型，仅用于验证键的类型维度。
     *
     * @author zcd
     */
    private static final class OtherCallback extends Callback<String> {

        /**
         * 构造测试命令。
         */
        private OtherCallback() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
