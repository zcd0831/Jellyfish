package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmUsage} 的单元测试。
 *
 * @author zcd
 */
class LlmUsageTest {

    @Test
    void getTotalTokens_should_use_provided_total_when_total_is_positive() {
        // Given
        LlmUsage usage = new LlmUsage(1, 2, 10);

        // Then
        assertEquals(1, usage.getPromptTokens());
        assertEquals(2, usage.getCompletionTokens());
        assertEquals(10, usage.getTotalTokens());
    }

    @ParameterizedTest
    @CsvSource({
            "1, 2, 0, 3",
            "1, 0, 0, 1",
            "3, 4, -1, 7"
    })
    void getTotalTokens_should_sum_prompt_and_completion_when_total_is_not_positive(
            int promptTokens, int completionTokens, int totalTokens, int expected) {
        assertEquals(expected, new LlmUsage(promptTokens, completionTokens, totalTokens).getTotalTokens());
    }

    @Test
    void toString_should_contain_all_counts() {
        // Given
        LlmUsage usage = new LlmUsage(1, 2, 3);

        // When
        String text = usage.toString();

        // Then
        assertTrue(text.contains("promptTokens=1"));
        assertTrue(text.contains("completionTokens=2"));
        assertTrue(text.contains("totalTokens=3"));
    }
}
