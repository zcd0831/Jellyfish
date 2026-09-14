package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionException} 的单元测试：验证错误码与 cause 透出。
 *
 * @author zcd
 */
class ExtensionExceptionTest {

    @Test
    void constructor_should_expose_code_in_message() {
        // When
        ExtensionException exception = new ExtensionException(ExtensionException.Code.NO_HANDLER,
                "type=ToolCallRequest routeKey=calculator");

        // Then
        assertEquals(ExtensionException.Code.NO_HANDLER, exception.getCode());
        assertTrue(exception.getMessage().contains("NO_HANDLER"));
        assertTrue(exception.getMessage().contains("calculator"));
    }

    @Test
    void constructor_should_keep_cause() {
        // Given
        IllegalStateException cause = new IllegalStateException("boom");

        // When
        ExtensionException exception = new ExtensionException(ExtensionException.Code.RESULT_TYPE_MISMATCH,
                "detail", cause);

        // Then
        assertSame(cause, exception.getCause());
        assertEquals(ExtensionException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void exception_should_be_jellyfish_exception() {
        // Then：统一异常家族，core 可以一处捕获
        assertTrue(JellyfishException.class.isAssignableFrom(ExtensionException.class));
    }
}
