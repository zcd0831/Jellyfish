package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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

        // When：加显式转型——plus 现在有两个重载，裸 null 无法确定指向哪一个
        SessionUsage accumulated = usage.plus((LlmUsage) null);

        // Then
        assertEquals(10L, accumulated.getPromptTokens());
        assertEquals(20L, accumulated.getCompletionTokens());
        assertEquals(30L, accumulated.getTotalTokens());
        assertEquals(2L, accumulated.getLlmCalls());
    }

    @Test
    void plus_should_merge_all_counters_including_call_count() {
        // Given：子代理的一个回合可能调了很多次模型
        SessionUsage parent = new SessionUsage(10L, 20L, 30L, 1L);
        SessionUsage child = new SessionUsage(5L, 7L, 12L, 4L);

        // When
        SessionUsage merged = parent.plus(child);

        // Then：调用次数必须一并带过来，不能被压成一次
        assertEquals(15L, merged.getPromptTokens());
        assertEquals(27L, merged.getCompletionTokens());
        assertEquals(42L, merged.getTotalTokens());
        assertEquals(5L, merged.getLlmCalls());
    }

    @Test
    void plus_should_return_same_instance_when_other_null() {
        // Given
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L);

        // When / Then
        assertEquals(1L, usage.plus((SessionUsage) null).getLlmCalls());
        assertEquals(10L, usage.plus((SessionUsage) null).getPromptTokens());
    }

    @Test
    void plus_should_return_same_instance_when_other_empty() {
        // Given
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L);

        // When / Then
        assertSame(usage, usage.plus(SessionUsage.EMPTY));
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
