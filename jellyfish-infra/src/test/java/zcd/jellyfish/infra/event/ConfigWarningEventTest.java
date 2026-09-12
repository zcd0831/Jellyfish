package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ConfigWarningEvent} 的单元测试：验证字段存取。
 *
 * @author zcd
 */
class ConfigWarningEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent("path", "message");

        // Then
        assertEquals("path", event.getSource());
        assertEquals("message", event.getMessage());
    }

    @Test
    void getSource_should_return_null_when_not_provided() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent(null, "message");

        // Then
        assertNull(event.getSource());
    }
}
