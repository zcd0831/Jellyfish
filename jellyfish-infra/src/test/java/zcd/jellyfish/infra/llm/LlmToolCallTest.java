package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmToolCall} 的单元测试。
 *
 * @author zcd
 */
class LlmToolCallTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        LlmToolCall toolCall = new LlmToolCall(0, "id-1", "search", "{\"q\":\"x\"}");

        // Then
        assertEquals(0, toolCall.getIndex());
        assertEquals("id-1", toolCall.getId());
        assertEquals("search", toolCall.getName());
        assertEquals("{\"q\":\"x\"}", toolCall.getArguments());
    }

    @Test
    void getters_should_return_null_when_values_absent() {
        // Given
        LlmToolCall toolCall = new LlmToolCall(null, null, null, null);

        // Then
        assertNull(toolCall.getIndex());
        assertNull(toolCall.getId());
        assertNull(toolCall.getName());
        assertNull(toolCall.getArguments());
    }

    @Test
    void toString_should_contain_name_and_arguments() {
        // Given
        LlmToolCall toolCall = new LlmToolCall(1, "id", "search", "{}");

        // When
        String text = toolCall.toString();

        // Then
        assertTrue(text.contains("index=1"));
        assertTrue(text.contains("name='search'"));
        assertTrue(text.contains("arguments='{}'"));
    }
}
