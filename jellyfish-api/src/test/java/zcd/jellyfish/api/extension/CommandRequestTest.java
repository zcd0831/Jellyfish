package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CommandRequest} 的单元测试：验证命令名、载荷类型校验与路由键。
 *
 * @author zcd
 */
class CommandRequestTest {

    @Test
    void getRouteKey_should_return_command_name() {
        // Given
        CommandRequest request = new CommandRequest("sql:query", String.class, "select 1");

        // Then
        assertEquals("sql:query", request.getRouteKey());
        assertEquals("sql:query", request.getName());
    }

    @Test
    void getPayload_should_return_payload_when_type_matches() {
        // Given
        CommandRequest request = new CommandRequest("sql:query", String.class, "select 1");

        // Then
        assertEquals("select 1", request.getPayload());
        assertEquals(String.class, request.getPayloadType());
    }

    @Test
    void constructor_should_allow_null_payload() {
        // Given
        CommandRequest request = new CommandRequest("sql:query", String.class, null);

        // Then
        assertNull(request.getPayload());
    }

    @Test
    void constructor_should_throw_when_name_is_blank() {
        assertThrows(JellyfishException.class, () -> new CommandRequest("  ", String.class, null));
    }

    @Test
    void constructor_should_throw_when_payload_type_is_null() {
        assertThrows(NullPointerException.class, () -> new CommandRequest("sql:query", null, null));
    }

    @Test
    void constructor_should_throw_when_payload_type_mismatch() {
        assertThrows(JellyfishException.class, () -> new CommandRequest("sql:query", String.class, 42));
    }
}
