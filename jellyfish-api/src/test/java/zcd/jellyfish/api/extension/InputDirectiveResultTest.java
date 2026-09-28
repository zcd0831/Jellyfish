package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputDirectiveResult} 的单元测试：验证两种意图的语义与参数防御性拷贝。
 *
 * @author zcd
 */
class InputDirectiveResultTest {

    @Test
    void toolCall_should_expose_tool_name_and_arguments() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "ls");

        // When
        InputDirectiveResult result = InputDirectiveResult.toolCall("shell", arguments);

        // Then
        assertTrue(result.isToolCall());
        assertEquals("shell", result.getToolName());
        assertEquals("ls", result.getArguments().get("command"));
    }

    @Test
    void toolCall_should_copy_arguments_defensively() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "ls");
        InputDirectiveResult result = InputDirectiveResult.toolCall("shell", arguments);

        // When
        arguments.put("command", "rm -rf /");

        // Then
        assertEquals("ls", result.getArguments().get("command"));
        assertThrows(UnsupportedOperationException.class, () -> result.getArguments().put("x", "y"));
    }

    @Test
    void toolCall_without_arguments_should_default_to_empty_map() {
        // When
        InputDirectiveResult result = InputDirectiveResult.toolCall("shell", null);

        // Then
        assertTrue(result.getArguments().isEmpty());
    }

    @Test
    void toolCall_should_reject_blank_tool_name() {
        // When / Then
        assertThrows(JellyfishException.class, () -> InputDirectiveResult.toolCall(null, null));
        assertThrows(JellyfishException.class, () -> InputDirectiveResult.toolCall("  ", null));
    }

    @Test
    void unclaimed_should_not_be_a_tool_call_and_should_have_no_tool() {
        // When
        InputDirectiveResult result = InputDirectiveResult.unclaimed();

        // Then
        assertFalse(result.isToolCall());
        assertEquals(null, result.getToolName());
        assertTrue(result.getArguments().isEmpty());
    }
}
