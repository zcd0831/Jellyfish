package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmMessage} 的单元测试。
 *
 * @author zcd
 */
class LlmMessageTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void constructor_should_throw_when_role_is_blank(String role) {
        assertThrows(JellyfishException.class, () -> new LlmMessage(role, "content", null, null, null));
    }

    @Test
    void system_should_build_system_message() {
        // When
        LlmMessage message = LlmMessage.system("prompt");

        // Then
        assertEquals(LlmMessage.ROLE_SYSTEM, message.getRole());
        assertEquals("prompt", message.getContent());
        assertNull(message.getToolCallId());
        assertNull(message.getName());
    }

    @Test
    void user_should_build_user_message() {
        // When
        LlmMessage message = LlmMessage.user("hello");

        // Then
        assertEquals(LlmMessage.ROLE_USER, message.getRole());
        assertEquals("hello", message.getContent());
    }

    @Test
    void assistant_should_build_assistant_message_without_tool_calls() {
        // When
        LlmMessage message = LlmMessage.assistant("answer");

        // Then
        assertEquals(LlmMessage.ROLE_ASSISTANT, message.getRole());
        assertEquals("answer", message.getContent());
        assertFalse(message.hasToolCalls());
    }

    @Test
    void assistant_should_build_assistant_message_with_tool_calls() {
        // Given
        List<LlmToolCall> toolCalls = Arrays.asList(new LlmToolCall(0, "id", "tool", "{}"));

        // When
        LlmMessage message = LlmMessage.assistant(null, toolCalls);

        // Then
        assertNull(message.getContent());
        assertTrue(message.hasToolCalls());
        assertEquals(1, message.getToolCalls().size());
    }

    @Test
    void tool_should_build_tool_message_with_call_id_and_name() {
        // When
        LlmMessage message = LlmMessage.tool("call-1", "search", "result");

        // Then
        assertEquals(LlmMessage.ROLE_TOOL, message.getRole());
        assertEquals("call-1", message.getToolCallId());
        assertEquals("search", message.getName());
        assertEquals("result", message.getContent());
    }

    @Test
    void getToolCalls_should_return_empty_list_when_tool_calls_is_null() {
        // When
        LlmMessage message = LlmMessage.user("hi");

        // Then
        assertTrue(message.getToolCalls().isEmpty());
    }

    @Test
    void getToolCalls_should_not_reflect_external_mutation_when_source_list_changes() {
        // Given
        List<LlmToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new LlmToolCall(0, "id", "tool", "{}"));
        LlmMessage message = LlmMessage.assistant("answer", toolCalls);

        // When
        toolCalls.add(new LlmToolCall(1, "id2", "tool2", "{}"));

        // Then
        assertEquals(1, message.getToolCalls().size());
    }
}
