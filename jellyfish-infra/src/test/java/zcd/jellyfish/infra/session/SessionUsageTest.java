package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * {@link SessionUsage} 的单元测试：验证累加语义、{@code null} 用量处理与不可变性。
 *
 * @author zcd
 */
class SessionUsageTest {

    @Test
    void empty_should_have_zero_counters() {
        // When / Then
        assertEquals(0L, SessionUsage.EMPTY.getPromptTokens());
        assertEquals(0L, SessionUsage.EMPTY.getCompletionTokens());
        assertEquals(0L, SessionUsage.EMPTY.getTotalTokens());
        assertEquals(0L, SessionUsage.EMPTY.getLlmCalls());
    }

    @Test
    void plus_should_sum_all_counters_and_call_count() {
        // Given
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L);

        // When
        SessionUsage accumulated = usage.plus(new LlmUsage(5, 7, 12));

        // Then
        assertEquals(15L, accumulated.getPromptTokens());
        assertEquals(27L, accumulated.getCompletionTokens());
        assertEquals(42L, accumulated.getTotalTokens());
        assertEquals(2L, accumulated.getLlmCalls());
    }

    @Test
    void plus_should_only_increase_call_count_when_usage_null() {
        // Given：厂商未返回用量时 LlmUsage 为 null
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L);

        // When
        SessionUsage accumulated = usage.plus(null);

        // Then
        assertEquals(10L, accumulated.getPromptTokens());
        assertEquals(20L, accumulated.getCompletionTokens());
        assertEquals(30L, accumulated.getTotalTokens());
        assertEquals(2L, accumulated.getLlmCalls());
    }

    @Test
    void toString_should_render_all_counters() {
        // Given
        SessionUsage usage = new SessionUsage(1L, 2L, 3L, 4L);

        // When / Then
        assertEquals("SessionUsage{promptTokens=1, completionTokens=2, totalTokens=3, llmCalls=4}",
                usage.toString());
    }

    @Test
    void plus_should_return_new_instance_when_accumulated() {
        // Given
        SessionUsage usage = new SessionUsage(1L, 2L, 3L, 1L);

        // When
        SessionUsage accumulated = usage.plus(new LlmUsage(1, 1, 2));

        // Then：原实例不被修改
        assertNotSame(usage, accumulated);
        assertEquals(1L, usage.getPromptTokens());
        assertEquals(3L, usage.getTotalTokens());
        assertEquals(1L, usage.getLlmCalls());
    }
}
