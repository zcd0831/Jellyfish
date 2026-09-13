package zcd.jellyfish.api.event.callback;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Callback} 的单元测试：验证只读元信息与截止时间判定。
 * <p>
 * 应答槽已下沉到 infra，本测试不再覆盖结果回填语义。
 *
 * @author zcd
 */
class CallbackTest {

    @Test
    void isExpired_should_be_false_when_no_deadline() {
        // Given
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertFalse(callback.isExpired());
        assertEquals(0L, callback.getDeadlineNanos());
    }

    @Test
    void isExpired_should_be_true_when_deadline_passed() throws InterruptedException {
        // Given
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 1L);

        // When
        Thread.sleep(10L);

        // Then
        assertTrue(callback.isExpired());
    }

    @Test
    void getSessionId_should_return_session() {
        // Given
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), "s1", 0L);

        // Then
        assertEquals("s1", callback.getSessionId());
    }

    @Test
    void getResultType_should_return_declared_type() {
        // Given
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertEquals(ToolCallResult.class, callback.getResultType());
    }

    @Test
    void getCallbackId_should_be_unique_per_command() {
        // Given
        ToolCallRequest first = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);
        ToolCallRequest second = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertNotEquals(first.getCallbackId(), second.getCallbackId());
    }

    @Test
    void getRouteKey_should_return_tool_name() {
        // Given
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertEquals("calculator", callback.getRouteKey());
    }
}
