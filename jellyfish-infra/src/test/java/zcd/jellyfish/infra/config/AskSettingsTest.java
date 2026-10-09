package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskSettings} 的单元测试：钉住「缺省 120 秒」「{@code 0} = 永不超时」「负数按非法回退」三条口径。
 * <p>
 * 前两条是同一件事的两侧：{@code 0} 必须是<b>无限</b>而不是「零秒超时」——后者会让等待立刻结束，
 * 而用户写 {@code 0} 想要的恰恰相反。第三条是刻意的选择：配置写错不阻断启动，但「写了个负数、
 * 却按 120 秒生效」这件事必须报出来，否则用户以为它立刻生效。
 *
 * @author zcd
 */
@DisplayName("AskSettings 提问配置")
class AskSettingsTest {

    @Test
    @DisplayName("不配置时使用缺省超时，且不产生告警")
    void constructor_should_use_default_when_absent() {
        // When
        AskSettings settings = new AskSettings(null);

        // Then
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS, settings.getTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertTrue(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }

    @Test
    @DisplayName("显式配置的正数生效，且不再算缺省")
    void constructor_should_accept_positive_value() {
        // When
        AskSettings settings = new AskSettings(300);

        // Then
        assertEquals(300, settings.getTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertFalse(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }

    @Test
    @DisplayName("写 0 表示永不超时：取值就是 0，但要靠 isInfinite 区分「无限」与「零秒」")
    void constructor_should_treat_zero_as_never_timeout() {
        // When
        AskSettings settings = new AskSettings(AskSettings.INFINITE_TIMEOUT_SECONDS);

        // Then
        assertTrue(settings.isInfinite());
        assertEquals(AskSettings.INFINITE_TIMEOUT_SECONDS, settings.getTimeoutSeconds());
        assertFalse(settings.isDefault(), "永不超时是刻意配的，不能算「什么都没配」");
        assertEquals(1, settings.warnings().size(), settings.warnings().toString());
        assertTrue(settings.warnings().get(0).contains("timeoutSeconds=0"), settings.warnings().get(0));
        assertTrue(settings.warnings().get(0).contains("8 条线程"), settings.warnings().get(0));
    }

    @ParameterizedTest
    @DisplayName("负数按非法处理：回退缺省值并告警")
    @ValueSource(ints = {-1, -120})
    void constructor_should_fall_back_to_default_when_negative(int value) {
        // When
        AskSettings settings = new AskSettings(value);

        // Then
        assertEquals(AskSettings.DEFAULT_TIMEOUT_SECONDS, settings.getTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertTrue(settings.isDefault());
        assertEquals(1, settings.warnings().size(), settings.warnings().toString());
        assertTrue(settings.warnings().get(0).contains("timeoutSeconds=" + value),
                settings.warnings().get(0));
        assertTrue(settings.warnings().get(0).contains("必须 ≥0"), settings.warnings().get(0));
    }

    @Test
    @DisplayName("无参构造等价于全缺省，且不产生告警")
    void default_constructor_should_equal_defaults() {
        // When
        AskSettings settings = new AskSettings();

        // Then
        assertEquals(new AskSettings(null).getTimeoutSeconds(), settings.getTimeoutSeconds());
        assertTrue(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }
}
