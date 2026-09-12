package zcd.jellyfish.api.event.command;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Command} 的单元测试：验证只读元信息与截止时间判定。
 * <p>
 * 应答槽已下沉到 infra，本测试不再覆盖结果回填语义。
 *
 * @author zcd
 */
class CommandTest {

    @Test
    void isExpired_should_be_false_when_no_deadline() {
        // Given
        ToolCallCommand command = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertFalse(command.isExpired());
        assertEquals(0L, command.getDeadlineNanos());
    }

    @Test
    void isExpired_should_be_true_when_deadline_passed() throws InterruptedException {
        // Given
        ToolCallCommand command = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 1L);

        // When
        Thread.sleep(10L);

        // Then
        assertTrue(command.isExpired());
    }

    @Test
    void getSessionId_should_return_session() {
        // Given
        ToolCallCommand command = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), "s1", 0L);

        // Then
        assertEquals("s1", command.getSessionId());
    }

    @Test
    void getResultType_should_return_declared_type() {
        // Given
        ToolCallCommand command = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertEquals(ToolCallResult.class, command.getResultType());
    }

    @Test
    void getCommandId_should_be_unique_per_command() {
        // Given
        ToolCallCommand first = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);
        ToolCallCommand second = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertNotEquals(first.getCommandId(), second.getCommandId());
    }

    @Test
    void getRouteKey_should_return_tool_name() {
        // Given
        ToolCallCommand command = new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);

        // Then
        assertEquals("calculator", command.getRouteKey());
    }
}
