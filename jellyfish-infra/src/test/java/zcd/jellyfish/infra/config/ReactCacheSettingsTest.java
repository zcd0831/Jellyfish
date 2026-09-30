package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactCacheSettings} 的单元测试：验证缺省值（沿用旧口径）、非法值回退与反序列化。
 *
 * @author zcd
 */
class ReactCacheSettingsTest {

    @Test
    void constructor_should_apply_defaults_when_missing() {
        // When
        ReactCacheSettings settings = new ReactCacheSettings(null);

        // Then：缺省是「沿用旧口径」而不是「关闭老化」，升级本版本因此不改变任何既有行为
        assertEquals(ReactCacheSettings.DEFAULT_AGING_PERCENT, settings.getAgingPercent());
        assertEquals(0, settings.getAgingPercent());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_values_invalid() {
        // When / Then：负数与超过 100 都回退缺省；配置问题不阻断启动是本仓库的既有口径
        assertEquals(ReactCacheSettings.DEFAULT_AGING_PERCENT,
                new ReactCacheSettings(-1).getAgingPercent());
        assertEquals(ReactCacheSettings.DEFAULT_AGING_PERCENT,
                new ReactCacheSettings(101).getAgingPercent());
    }

    @Test
    void constructor_should_keep_zero_and_hundred_as_valid() {
        // When：0 = 沿用旧口径，100 = 实际上永不触发；两者都是合法取值而非「未配置」
        ReactCacheSettings zero = new ReactCacheSettings(0);
        ReactCacheSettings hundred = new ReactCacheSettings(100);

        // Then
        assertEquals(0, zero.getAgingPercent());
        assertTrue(zero.isDefault());
        assertEquals(100, hundred.getAgingPercent());
        assertFalse(hundred.isDefault());
    }

    @Test
    void deserialization_should_bind_agingPercent() {
        // Given
        String json = "{\"agingPercent\":70}";

        // When
        ReactCacheSettings settings = ObjectMapperWrapper.readValue(json, ReactCacheSettings.class);

        // Then
        assertEquals(70, settings.getAgingPercent());
        assertFalse(settings.isDefault());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // When
        ReactCacheSettings settings = ObjectMapperWrapper.readValue("{}", ReactCacheSettings.class);

        // Then
        assertTrue(settings.isDefault());
    }
}
