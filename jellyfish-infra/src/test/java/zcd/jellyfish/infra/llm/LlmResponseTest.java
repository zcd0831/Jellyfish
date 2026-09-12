package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmResponse} 的单元测试。
 *
 * @author zcd
 */
class LlmResponseTest {

    @Test
    void getToolCalls_should_return_empty_list_when_tool_calls_is_null() {
        // Given
        LlmResponse response = new LlmResponse("hi", null, null, null, null);

        // Then
        assertTrue(response.getToolCalls().isEmpty());
        assertFalse(response.hasToolCalls());
    }

    @Test
    void getToolCalls_should_be_unmodifiable_when_constructed() {
        // Given
        List<LlmToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new LlmToolCall(0, "id", "name", "{}"));
        LlmResponse response = new LlmResponse("hi", null, toolCalls, null, null);

        // Then
        assertThrows(UnsupportedOperationException.class, () -> response.getToolCalls().add(null));
    }

    @Test
    void getToolCalls_should_not_reflect_external_mutation_when_source_list_changes() {
        // Given
        List<LlmToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new LlmToolCall(0, "id", "name", "{}"));
        LlmResponse response = new LlmResponse("hi", null, toolCalls, null, null);

        // When
        toolCalls.add(new LlmToolCall(1, "id2", "name2", "{}"));

        // Then
        assertEquals(1, response.getToolCalls().size());
        assertTrue(response.hasToolCalls());
    }

    @Test
    void text_should_build_text_only_response() {
        // When
        LlmResponse response = LlmResponse.text("hello");

        // Then
        assertEquals("hello", response.getContent());
        assertNull(response.getThinking());
        assertNull(response.getUsage());
        assertTrue(response.getToolCalls().isEmpty());
    }

    @Test
    void toString_should_contain_content_and_finish_reason() {
        // Given
        LlmResponse response = new LlmResponse("hi", "think", null,
                new LlmUsage(1, 2, 3), "stop");

        // When
        String text = response.toString();

        // Then
        assertTrue(text.contains("content='hi'"));
        assertTrue(text.contains("thinking='think'"));
        assertTrue(text.contains("finishReason='stop'"));
    }
}
