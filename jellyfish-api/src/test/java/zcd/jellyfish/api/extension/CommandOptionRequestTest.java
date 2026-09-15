package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CommandOptionRequest} 的单元测试：验证路由键、会话透传与名字校验。
 *
 * @author zcd
 */
class CommandOptionRequestTest {

    @Test
    void constructor_should_expose_name_as_route_key_and_session() {
        // When
        CommandOptionRequest request = new CommandOptionRequest("agent", "session-1");

        // Then
        assertEquals("agent", request.getName());
        assertEquals("agent", request.getRouteKey());
        assertEquals("session-1", request.getSessionId());
        assertEquals(CommandOptions.class, request.getResultType());
    }

    @Test
    void constructor_without_session_should_default_to_null() {
        // When
        CommandOptionRequest request = new CommandOptionRequest("agent");

        // Then
        assertNull(request.getSessionId());
    }

    @Test
    void constructor_should_reject_blank_name() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new CommandOptionRequest(null, null));
        assertThrows(JellyfishException.class, () -> new CommandOptionRequest("  ", null));
    }
}
