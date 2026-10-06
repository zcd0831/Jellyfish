package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskSettings} 的单元测试：钉住「缺省 120 秒」与「非正数回退缺省」两条口径。
 * <p>
 * 后者是刻意的选择：配置写错不阻断启动，而「超时为 0」在语义上只会退化成「一律超时」，
 * 与「没配」撞成同一种表现，不如直接当没配。
 *
 * @author zcd
 */
@DisplayName("AskSettings 提问配置")
class AskSettingsTest {

    @Test
    @DisplayName("不配置时使用缺省超时")
    void constructor_should_use_default_when_absent() {
        // When
        AskSettings settings = new AskSettings(null);

        // Then
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS, settings.getTimeoutSeconds());
        assertTrue(settings.isDefault());
    }

    @Test
    @DisplayName("显式配置的正数生效，且不再算缺省")
    void constructor_should_accept_positive_value() {
        // When
        AskSettings settings = new AskSettings(300);

        // Then
        assertEquals(300, settings.getTimeoutSeconds());
        assertFalse(settings.isDefault());
    }

    @ParameterizedTest
    @DisplayName("非正数一律回退缺省值，不报错")
    @ValueSource(ints = {0, -1, -120})
    void constructor_should_fall_back_to_default_when_not_positive(int value) {
        // When
        AskSettings settings = new AskSettings(value);

        // Then
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS, settings.getTimeoutSeconds());
        assertTrue(settings.isDefault());
    }

    @Test
    @DisplayName("无参构造等价于全缺省")
    void default_constructor_should_equal_defaults() {
        // When / Then
        assertEquals(new AskSettings(null).getTimeoutSeconds(), new AskSettings().getTimeoutSeconds());
        assertTrue(new AskSettings().isDefault());
    }
}
