package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link InputReferenceRequest} 的单元测试：验证标记校验、空值归一与光标钳制。
 *
 * @author zcd
 */
class InputReferenceRequestTest {

    @Test
    void constructor_should_expose_marker_as_route_key_and_fields() {
        // When
        InputReferenceRequest request = new InputReferenceRequest("@", "src/ma", "看看 @src/ma", 9, "s-1");

        // Then
        assertEquals("@", request.getMarker());
        assertEquals("@", request.getRouteKey());
        assertEquals("src/ma", request.getToken());
        assertEquals("看看 @src/ma", request.getInput());
        assertEquals(9, request.getCursor());
        assertEquals("s-1", request.getSessionId());
        assertEquals(InputReferenceResult.class, request.getResultType());
    }

    @Test
    void constructor_should_normalize_null_token_and_input() {
        // When
        InputReferenceRequest request = new InputReferenceRequest("@", null, null, 0, null);

        // Then
        assertEquals("", request.getToken());
        assertEquals("", request.getInput());
        assertEquals(null, request.getSessionId());
    }

    @Test
    void constructor_should_clamp_negative_cursor_to_zero() {
        // When
        InputReferenceRequest request = new InputReferenceRequest("@", "", "", -5, null);

        // Then
        assertEquals(0, request.getCursor());
    }

    @Test
    void constructor_should_reject_invalid_marker() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new InputReferenceRequest(null, "", "", 0, null));
        assertThrows(JellyfishException.class, () -> new InputReferenceRequest("@@", "", "", 0, null));
    }
}
