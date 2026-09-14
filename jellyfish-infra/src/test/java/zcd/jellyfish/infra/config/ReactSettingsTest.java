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
        ReactSettings settings = new ReactSettings();

        // Then
        assertEquals(ReactSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, settings.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, settings.getMaxToolOutputChars());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_values_invalid() {
        // When：轮数与输出长度非正、预留为负，全部回退缺省
        ReactSettings settings = new ReactSettings(0, -1, -5);

        // Then
        assertEquals(ReactSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS, settings.getContextReserveTokens());
        assertEquals(ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS, settings.getMaxToolOutputChars());
    }

    @Test
    void constructor_should_keep_explicit_zero_reserve() {
        // When：显式 0 预留是合法配置，不能被当成「未配置」
        ReactSettings settings = new ReactSettings(null, 0, null);

        // Then
        assertEquals(0, settings.getContextReserveTokens());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_keep_valid_values() {
        // When
        ReactSettings settings = new ReactSettings(3, 512, 100);

        // Then
        assertEquals(3, settings.getMaxRounds());
        assertEquals(512, settings.getContextReserveTokens());
        assertEquals(100, settings.getMaxToolOutputChars());
        assertFalse(settings.isDefault());
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
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // When
        ReactSettings settings = ObjectMapperWrapper.readValue("{}", ReactSettings.class);

        // Then
        assertTrue(settings.isDefault());
    }
}
