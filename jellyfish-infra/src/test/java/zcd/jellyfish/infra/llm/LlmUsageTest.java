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
        LlmUsage usage = new LlmUsage(1, 2, 3, 4, 5);

        // When
        String text = usage.toString();

        // Then
        assertTrue(text.contains("promptTokens=1"));
        assertTrue(text.contains("completionTokens=2"));
        assertTrue(text.contains("totalTokens=3"));
        assertTrue(text.contains("cacheReadTokens=4"));
        assertTrue(text.contains("cacheWriteTokens=5"));
    }

    @Test
    void getCacheHitRate_should_divide_cacheRead_by_totalPrompt() {
        // Given：总输入 100，其中 25 命中缓存
        LlmUsage usage = new LlmUsage(100, 7, 107, 25, 5);

        // Then：分母是总输入，所以在任何厂商上都是同一个公式（厂商差异已在解析处归一化）
        assertEquals(0.25d, usage.getCacheHitRate(), 1e-9);
        assertEquals(25, usage.getCacheReadTokens());
        assertEquals(5, usage.getCacheWriteTokens());
    }

    @Test
    void getCacheHitRate_should_return_zero_when_prompt_is_zero() {
        // Given：厂商给了缓存计数却没给输入（异常数据）
        LlmUsage usage = new LlmUsage(0, 2, 2, 5, 0);

        // Then：分母为 0 时必须返回 0，不能是 NaN 或无穷——那个数字会一路流进界面
        assertEquals(0.0d, usage.getCacheHitRate());
    }

    @Test
    void getCacheHitRate_should_return_zero_when_cache_info_is_absent() {
        // Given：三参构造器表达的是「没有这份信息」，不是「查询过缓存但没命中」
        LlmUsage usage = new LlmUsage(100, 7, 107);

        // Then
        assertEquals(0, usage.getCacheReadTokens());
        assertEquals(0, usage.getCacheWriteTokens());
        assertEquals(0.0d, usage.getCacheHitRate());
    }

    @Test
    void constructor_should_clamp_negative_cache_counts() {
        // Given：厂商上报了负数（坏数据）
        LlmUsage usage = new LlmUsage(100, 7, 107, -5, -1);

        // Then：钳到 0，否则命中率会变成负数，而「命中 -5 个 token」看起来像是内核的 bug
        assertEquals(0, usage.getCacheReadTokens());
        assertEquals(0, usage.getCacheWriteTokens());
        assertEquals(0.0d, usage.getCacheHitRate());
    }
}
