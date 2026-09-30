package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderCacheSettings} 的单元测试：缺省两项都关、非法值回退、反序列化。
 *
 * @author zcd
 */
class ProviderCacheSettingsTest {

    @Test
    void constructor_should_apply_defaults_when_missing() {
        // When
        ProviderCacheSettings settings = new ProviderCacheSettings();

        // Then：两项都关——它们都得先满足「厂商确实这么干」才谈得上收益
        assertFalse(settings.isPromptCacheKey());
        assertEquals(0, settings.getKeepAliveSeconds());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_keepAlive_outOfRange() {
        // When / Then：负数与超过 1 小时都回退到关闭；配置问题不阻断启动
        assertEquals(0, new ProviderCacheSettings(null, -1).getKeepAliveSeconds());
        assertEquals(0, new ProviderCacheSettings(null, ProviderCacheSettings.MAX_KEEP_ALIVE_SECONDS + 1)
                .getKeepAliveSeconds());
        assertEquals(ProviderCacheSettings.MAX_KEEP_ALIVE_SECONDS,
                new ProviderCacheSettings(null, ProviderCacheSettings.MAX_KEEP_ALIVE_SECONDS)
                        .getKeepAliveSeconds());
    }

    @Test
    void constructor_should_keep_explicit_values() {
        // When
        ProviderCacheSettings settings = new ProviderCacheSettings(true, 240);

        // Then
        assertTrue(settings.isPromptCacheKey());
        assertEquals(240, settings.getKeepAliveSeconds());
        assertFalse(settings.isDefault());
    }

    @Test
    void deserialization_should_bind_both_fields() {
        // Given
        String json = "{\"promptCacheKey\":true,\"keepAliveSeconds\":120}";

        // When
        ProviderCacheSettings settings = ObjectMapperWrapper.readValue(json, ProviderCacheSettings.class);

        // Then
        assertTrue(settings.isPromptCacheKey());
        assertEquals(120, settings.getKeepAliveSeconds());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // When
        ProviderCacheSettings settings = ObjectMapperWrapper.readValue("{}", ProviderCacheSettings.class);

        // Then
        assertTrue(settings.isDefault());
    }
}
