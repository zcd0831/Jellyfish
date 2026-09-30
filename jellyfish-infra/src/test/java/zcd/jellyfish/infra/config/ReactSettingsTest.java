package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactSettings} 的单元测试：验证缺省值、非法值回退与反序列化。
 *
 * @author zcd
 */
class ReactSettingsTest {

    @Test
    void constructor_should_apply_defaults_when_all_missing() {
        // When
        ReactSettings settings = new ReactSettings(null, null, null, null, null, null);

        // Then
        assertEquals(ReactSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, settings.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, settings.getMaxToolOutputChars());
        assertEquals(ReactSettings.DEFAULT_AUTO_COMPACT_PERCENT, settings.getAutoCompactPercent());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_values_invalid() {
        // When：轮数与输出长度非正、预留为负，全部回退缺省
        ReactSettings settings = new ReactSettings(0, -1, -5, -1, 0, null);

        // Then
        assertEquals(ReactSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, settings.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, settings.getMaxToolOutputChars());
    }

    @Test
    void constructor_should_keep_explicit_zero_reserve() {
        // When：显式 0 预留是合法配置，不能被当成「未配置」
        ReactSettings settings = new ReactSettings(null, 0, null, null, null, null);

        // Then
        assertEquals(0, settings.getContextReserveTokens());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_keep_valid_values() {
        // When
        ReactSettings settings = new ReactSettings(3, 512, 100, null, null, null);

        // Then
        assertEquals(3, settings.getMaxRounds());
        assertEquals(512, settings.getContextReserveTokens());
        assertEquals(100, settings.getMaxToolOutputChars());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_keep_zero_keepRecent_as_all() {
        // When：/compact all 对应 keepRecent = 0，是合法取值而非「未配置」
        ReactSettings settings = new ReactSettings(null, null, null, 0, null, null);

        // Then
        assertEquals(0, settings.getCompactKeepRecentMessages());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_compact_values_invalid() {
        // When：保留条数为负、摘要上限非正，都回退缺省
        ReactSettings settings = new ReactSettings(null, null, null, -1, 0, null);

        // Then
        assertEquals(ReactSettings.DEFAULT_COMPACT_KEEP_RECENT_MESSAGES, settings.getCompactKeepRecentMessages());
        assertEquals(ReactSettings.DEFAULT_COMPACT_MAX_SUMMARY_CHARS, settings.getCompactMaxSummaryChars());
    }

    @Test
    void constructor_should_keep_zero_autoCompact_as_disabled() {
        // When：0 表示关闭自动压缩，是合法取值而非「未配置」
        ReactSettings settings = new ReactSettings(null, null, null, null, null, 0);

        // Then
        assertEquals(0, settings.getAutoCompactPercent());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_and_clamp_when_autoCompactOutOfRange() {
        // Then：负数回退缺省，超过 100 压到 100
        assertEquals(ReactSettings.DEFAULT_AUTO_COMPACT_PERCENT,
                new ReactSettings(null, null, null, null, null, -1).getAutoCompactPercent());
        assertEquals(100, new ReactSettings(null, null, null, null, null, 130).getAutoCompactPercent());
    }

    @Test
    void deserialization_should_bind_compact_section() {
        // Given
        String json = "{\"compactKeepRecentMessages\":8,\"compactMaxSummaryChars\":1500,"
                + "\"autoCompactPercent\":60}";

        // When
        ReactSettings settings = ObjectMapperWrapper.readValue(json, ReactSettings.class);

        // Then
        assertEquals(8, settings.getCompactKeepRecentMessages());
        assertEquals(1500, settings.getCompactMaxSummaryChars());
        assertEquals(60, settings.getAutoCompactPercent());
    }

    @Test
    void deserialization_should_bind_partial_section_with_defaults() {
        // Given：只配 maxRounds，其余走缺省
        String json = "{\"maxRounds\":5}";

        // When
        ReactSettings settings = ObjectMapperWrapper.readValue(json, ReactSettings.class);

        // Then
        assertEquals(5, settings.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, settings.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, settings.getMaxToolOutputChars());
        assertEquals(ReactSettings.DEFAULT_COMPACT_KEEP_RECENT_MESSAGES, settings.getCompactKeepRecentMessages());
        assertEquals(ReactSettings.DEFAULT_COMPACT_MAX_SUMMARY_CHARS, settings.getCompactMaxSummaryChars());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // When
        ReactSettings settings = ObjectMapperWrapper.readValue("{}", ReactSettings.class);

        // Then
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_apply_toolOutput_defaults_when_section_missing() {
        // When
        ReactSettings settings = new ReactSettings(null, null, null, null, null, null);

        // Then
        assertEquals(ToolOutputSettings.DEFAULT_DIR, settings.getToolOutput().getDir());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_FILES, settings.getToolOutput().getKeepFiles());
        assertEquals(ToolOutputSettings.DEFAULT_MAX_BYTES, settings.getToolOutput().getMaxBytes());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_RECENT_MESSAGES,
                settings.getToolOutput().getKeepRecentMessages());
        assertTrue(settings.getToolOutput().isDefault());
    }

    @Test
    void deserialization_should_bind_toolOutput_section() {
        // Given
        String json = "{\"toolOutput\":{\"dir\":\"/tmp/spill\",\"keepFiles\":5,\"maxBytes\":1024,"
                + "\"keepRecentMessages\":2}}";

        // When
        ReactSettings settings = ObjectMapperWrapper.readValue(json, ReactSettings.class);

        // Then
        assertEquals("/tmp/spill", settings.getToolOutput().getDir());
        assertEquals(5, settings.getToolOutput().getKeepFiles());
        assertEquals(1024L, settings.getToolOutput().getMaxBytes());
        assertEquals(2, settings.getToolOutput().getKeepRecentMessages());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_apply_cache_defaults_when_section_missing() {
        // When：用最旧的那个便捷构造器（连 toolOutput 都不传）
        ReactSettings settings = new ReactSettings(null, null, null, null, null, null);

        // Then：缓存段也必须落在缺省上，而不是 null
        assertEquals(ReactCacheSettings.DEFAULT_AGING_PERCENT, settings.getCache().getAgingPercent());
        assertTrue(settings.getCache().isDefault());
    }

    @Test
    void deserialization_should_bind_cache_section() {
        // Given
        String json = "{\"cache\":{\"agingPercent\":70}}";

        // When
        ReactSettings settings = ObjectMapperWrapper.readValue(json, ReactSettings.class);

        // Then：只改了缓存段也必须被视为「配过了」，否则热更新会把它当空配置丢掉
        assertEquals(70, settings.getCache().getAgingPercent());
        assertFalse(settings.isDefault());
    }
}
