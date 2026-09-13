package zcd.jellyfish.api.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PluginRequest} 的单元测试：验证命令名、载荷类型校验与路由键。
 *
 * @author zcd
 */
class PluginRequestTest {

    @Test
    void getRouteKey_should_return_command_name() {
        // Given
        PluginRequest callback = new PluginRequest("sql:query", String.class, "select 1");

        // Then
        assertEquals("sql:query", callback.getRouteKey());
        assertEquals("sql:query", callback.getName());
    }

    @Test
    void getPayload_should_return_payload_when_type_matches() {
        // Given
        PluginRequest callback = new PluginRequest("sql:query", String.class, "select 1");

        // Then
        assertEquals("select 1", callback.getPayload());
        assertEquals(String.class, callback.getPayloadType());
    }

    @Test
    void constructor_should_allow_null_payload() {
        // Given
        PluginRequest callback = new PluginRequest("sql:query", String.class, null);

        // Then
        assertNull(callback.getPayload());
    }

    @Test
    void constructor_should_throw_when_name_is_blank() {
        assertThrows(JellyfishException.class, () -> new PluginRequest("  ", String.class, null));
    }

    @Test
    void constructor_should_throw_when_payload_type_is_null() {
        assertThrows(NullPointerException.class, () -> new PluginRequest("sql:query", null, null));
    }

    @Test
    void constructor_should_throw_when_payload_type_mismatch() {
        assertThrows(JellyfishException.class, () -> new PluginRequest("sql:query", String.class, 42));
    }
}
