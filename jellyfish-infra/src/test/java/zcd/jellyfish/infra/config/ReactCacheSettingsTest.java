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

        // Then：缺省是水位触发，且水位略低于自动压缩阈值（80）
        assertEquals(ReactCacheSettings.DEFAULT_AGING_PERCENT, settings.getAgingPercent());
        assertEquals(70, settings.getAgingPercent());
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
        // When：0 = 沿用旧口径（逃生门），100 = 实际上永不触发；两者都是合法取值而非「未配置」
        ReactCacheSettings zero = new ReactCacheSettings(0);
        ReactCacheSettings hundred = new ReactCacheSettings(100);

        // Then
        assertEquals(0, zero.getAgingPercent());
        assertFalse(zero.isDefault());
        assertEquals(100, hundred.getAgingPercent());
        assertFalse(hundred.isDefault());
    }

    @Test
    void deserialization_should_bind_agingPercent() {
        // Given：用一个与缺省（70）不同的值，否则分不出「绑上了」与「用了缺省」
        String json = "{\"agingPercent\":40}";

        // When
        ReactCacheSettings settings = ObjectMapperWrapper.readValue(json, ReactCacheSettings.class);

        // Then
        assertEquals(40, settings.getAgingPercent());
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
