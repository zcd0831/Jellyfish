package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断参数的单元测试。
 * <p>
 * 这里钉住的是「零是合法配置」这条约定：它看起来像细节，但它决定了「显式关闭熔断」
 * 是不是一个可用的手段——若零被当成「用默认值」，用户就失去了唯一一个能立刻止损的开关。
 *
 * @author zcd
 */
@DisplayName("熔断参数")
class CircuitBreakerSettingsTest {

    @Test
    @DisplayName("缺省值应为 2 次 / 60 秒 / 3 轮")
    void defaults_should_useDocumentedValues() {
        CircuitBreakerSettings settings = CircuitBreakerSettings.defaults();

        assertEquals(2, settings.failuresToOpen());
        assertEquals(60, settings.cooldownSeconds());
        assertEquals(60000L, settings.cooldownMillis());
        assertEquals(3, settings.roundsToPermanent());
        assertTrue(settings.toString().contains("failuresToOpen=2"), settings.toString());
    }

    @Test
    @DisplayName("三个参数都应该接受 0（分别表示不熔断 / 立即重试 / 永不转永久）")
    void builder_should_acceptZero_forEveryField() {
        CircuitBreakerSettings settings = CircuitBreakerSettings.builder()
                .failuresToOpen(0)
                .cooldownSeconds(0)
                .roundsToPermanent(0)
                .build();

        assertEquals(0, settings.failuresToOpen());
        assertEquals(0L, settings.cooldownMillis());
        assertEquals(0, settings.roundsToPermanent());
    }

    @Test
    @DisplayName("负数应在构造期就报错，而不是留到运行期")
    void builder_should_rejectNegativeValues() {
        assertTrue(assertThrows(JellyfishException.class, () -> CircuitBreakerSettings.builder()
                .failuresToOpen(-1)).getMessage().contains("failuresToOpen"));
        assertTrue(assertThrows(JellyfishException.class, () -> CircuitBreakerSettings.builder()
                .cooldownSeconds(-1)).getMessage().contains("cooldownSeconds"));
        assertTrue(assertThrows(JellyfishException.class, () -> CircuitBreakerSettings.builder()
                .roundsToPermanent(-1)).getMessage().contains("roundsToPermanent"));
    }
}
