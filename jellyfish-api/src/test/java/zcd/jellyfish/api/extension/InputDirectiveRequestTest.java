package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link InputDirectiveRequest} 的单元测试：验证标记校验与路由键口径。
 *
 * @author zcd
 */
class InputDirectiveRequestTest {

    @Test
    void constructor_should_expose_marker_as_route_key() {
        // When
        InputDirectiveRequest request = new InputDirectiveRequest("!", "!ls -la", "s-1");

        // Then
        assertEquals("!", request.getMarker());
        assertEquals("!", request.getRouteKey());
        assertEquals("!ls -la", request.getInput());
        assertEquals("s-1", request.getSessionId());
        assertEquals(InputDirectiveResult.class, request.getResultType());
    }

    @Test
    void constructor_should_default_null_input_to_empty() {
        // When
        InputDirectiveRequest request = new InputDirectiveRequest("!", null, null);

        // Then
        assertEquals("", request.getInput());
        assertEquals(null, request.getSessionId());
    }

    @Test
    void constructor_should_reject_invalid_marker() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new InputDirectiveRequest(null, "x", null));
        assertThrows(JellyfishException.class, () -> new InputDirectiveRequest("", "x", null));
        assertThrows(JellyfishException.class, () -> new InputDirectiveRequest("!!", "x", null));
        assertThrows(JellyfishException.class, () -> new InputDirectiveRequest(" ", "x", null));
    }
}
