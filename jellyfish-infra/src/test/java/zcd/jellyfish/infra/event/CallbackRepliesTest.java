package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CallbackReplies} 的单元测试：验证应答槽的回填、类型校验、异常回传与回收。
 *
 * @author zcd
 */
class CallbackRepliesTest {

    /** 被测应答槽。 */
    private final CallbackReplies replies = new CallbackReplies();

    @Test
    void await_should_return_result_when_completed() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        replies.complete(callback, new ToolCallResult("calculator", 42));

        // Then
        ToolCallResult result = replies.await(callback);
        assertEquals("calculator", result.getToolName());
    }

    @Test
    void complete_should_throw_when_result_type_mismatch() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class, () -> replies.complete(callback, "not-a-result"));

        // Then
        assertEquals(ExtensionException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void await_should_throw_no_response_when_not_answered() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When / Then
        assertThrows(JellyfishException.class, () -> replies.await(callback));
    }

    @Test
    void await_should_throw_no_response_when_slot_missing() {
        // Given
        ToolCallRequest callback = toolCall();

        // When / Then
        assertThrows(JellyfishException.class, () -> replies.await(callback));
    }

    @Test
    void await_should_rethrow_runtime_exception_when_failed() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        replies.fail(callback, new IllegalStateException("boom"));

        // Then
        assertThrows(IllegalStateException.class, () -> replies.await(callback));
    }

    @Test
    void await_should_wrap_checked_exception_when_failed() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        replies.fail(callback, new Exception("checked"));

        // Then
        JellyfishException exception = assertThrows(JellyfishException.class, () -> replies.await(callback));
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void await_should_rethrow_error_when_failed_with_error() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        replies.fail(callback, new AssertionError("fatal"));

        // Then
        assertThrows(AssertionError.class, () -> replies.await(callback));
    }

    @Test
    void await_should_throw_no_response_after_close() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);
        replies.complete(callback, new ToolCallResult("calculator", 42));

        // When
        replies.close(callback);

        // Then
        assertThrows(JellyfishException.class, () -> replies.await(callback));
    }

    @Test
    void complete_should_allow_result_of_any_type_when_result_type_is_object() {
        // Given
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        replies.open(callback);

        // When
        replies.complete(callback, "anything");

        // Then
        assertEquals("anything", replies.await(callback));
    }

    /**
     * 构造工具调用命令。
     *
     * @return 工具调用命令
     */
    private static ToolCallRequest toolCall() {
        return new ToolCallRequest("calculator", Collections.<String, Object>emptyMap());
    }
}
