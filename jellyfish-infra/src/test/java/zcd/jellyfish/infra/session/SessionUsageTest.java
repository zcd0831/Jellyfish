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
    void plusTokens_should_sum_counters_and_keep_call_count() {
        // Given
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L, 2L, 3L);

        // When：一条非 assistant 消息带来的用量，不该额外算一次调用
        SessionUsage accumulated = usage.plusTokens(new LlmUsage(5, 7, 12, 4, 6));

        // Then
        assertEquals(15L, accumulated.getPromptTokens());
        assertEquals(27L, accumulated.getCompletionTokens());
        assertEquals(42L, accumulated.getTotalTokens());
        assertEquals(1L, accumulated.getLlmCalls());
        assertEquals(6L, accumulated.getCacheReadTokens());
        assertEquals(9L, accumulated.getCacheWriteTokens());
    }

    @Test
    void plusTokens_should_return_same_instance_when_usage_null() {
        // Given：没有用量可加，也没有调用可计
        SessionUsage usage = new SessionUsage(10L, 20L, 30L, 1L);

        // When / Then
        assertSame(usage, usage.plusTokens(null));
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
        SessionUsage usage = new SessionUsage(1L, 2L, 3L, 4L, 5L, 6L);

        // When / Then
        assertEquals("SessionUsage{promptTokens=1, completionTokens=2, totalTokens=3, llmCalls=4, "
                + "cacheReadTokens=5, cacheWriteTokens=6}", usage.toString());
    }

    @Test
    void plus_should_accumulate_cache_counts_from_single_usage() {
        // Given：会话已有累计，再加入一次「总输入 100、命中 25、建缓存 5」的调用
        SessionUsage usage = new SessionUsage(10L, 2L, 12L, 1L, 3L, 1L);

        // When
        SessionUsage accumulated = usage.plus(new LlmUsage(100, 7, 107, 25, 5));

        // Then
        assertEquals(110L, accumulated.getPromptTokens());
        assertEquals(28L, accumulated.getCacheReadTokens());
        assertEquals(6L, accumulated.getCacheWriteTokens());
    }

    @Test
    void plus_should_keep_cache_counts_when_usage_is_absent() {
        // Given：厂商没返回用量
        SessionUsage usage = new SessionUsage(10L, 2L, 12L, 1L, 3L, 1L);

        // When：显式转型——plus 的两个重载都能接受 null，不转型是编译错误
        SessionUsage accumulated = usage.plus((LlmUsage) null);

        // Then：调用次数要涨，而 token 与缓存累计一个都不能被清掉
        assertEquals(2L, accumulated.getLlmCalls());
        assertEquals(10L, accumulated.getPromptTokens());
        assertEquals(3L, accumulated.getCacheReadTokens());
    }

    @Test
    void plus_should_accumulate_cache_counts_when_merging_sessions() {
        // Given：子代理的用量并入父会话
        SessionUsage parent = new SessionUsage(10L, 2L, 12L, 1L, 3L, 1L);
        SessionUsage child = new SessionUsage(100L, 5L, 105L, 2L, 40L, 0L);

        // When
        SessionUsage merged = parent.plus(child);

        // Then
        assertEquals(43L, merged.getCacheReadTokens());
        assertEquals(1L, merged.getCacheWriteTokens());
        assertEquals(3L, merged.getLlmCalls());
    }

    @Test
    void getCacheHitRate_should_use_accumulated_totals_not_average_of_rates() {
        // Given：一次「3 token 全命中」（单次 100%）与一次「1000 token 全未命中」（单次 0%）
        SessionUsage usage = SessionUsage.EMPTY
                .plus(new LlmUsage(3, 1, 4, 3, 0))
                .plus(new LlmUsage(1000, 1, 1001, 0, 0));

        // Then：按累计量算是 3/1003，而不是两次比率取平均的 50%——
        // 短调用不该与长调用等权，否则「命中率」会被大量无信息的小调用抬起来
        assertEquals(3.0d / 1003.0d, usage.getCacheHitRate(), 1e-9);
    }

    @Test
    void plus_should_return_new_instance_when_accumulated() {
        // Given
        SessionUsage usage = new SessionUsage(1L, 2L, 3L, 1L, 4L, 5L);

        // When
        SessionUsage accumulated = usage.plus(new LlmUsage(1, 1, 2));

        // Then：原实例不被修改（含缓存累计）
        assertNotSame(usage, accumulated);
        assertEquals(1L, usage.getPromptTokens());
        assertEquals(3L, usage.getTotalTokens());
        assertEquals(1L, usage.getLlmCalls());
        assertEquals(4L, usage.getCacheReadTokens());
        assertEquals(5L, usage.getCacheWriteTokens());
        assertEquals(2L, accumulated.getPromptTokens());
    }
}
