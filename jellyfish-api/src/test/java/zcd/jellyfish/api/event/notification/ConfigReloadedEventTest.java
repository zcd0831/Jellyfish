package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigReloadedEvent} 的单元测试：验证重启插件集合的不可变拷贝与公共元信息。
 *
 * @author zcd
 */
class ConfigReloadedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        ConfigReloadedEvent event = new ConfigReloadedEvent(
                new LinkedHashSet<String>(Arrays.asList("a", "b")), 25L);

        // Then
        assertEquals(new LinkedHashSet<String>(Arrays.asList("a", "b")), event.getRestartedPluginIds());
        assertEquals(25L, event.getDurationMillis());
        assertNull(event.getSessionId());
    }

    @Test
    void restarted_plugins_should_be_an_immutable_copy() {
        // Given
        LinkedHashSet<String> source = new LinkedHashSet<String>(Arrays.asList("a"));
        ConfigReloadedEvent event = new ConfigReloadedEvent(source, 1L);

        // When：源集合后续变化不该影响事件
        source.add("b");

        // Then
        assertEquals(1, event.getRestartedPluginIds().size());
        assertThrows(UnsupportedOperationException.class, () -> event.getRestartedPluginIds().add("c"));
    }

    @Test
    void constructor_should_tolerate_null_restarted_plugins() {
        // Given
        ConfigReloadedEvent event = new ConfigReloadedEvent(null, 3L);

        // Then
        assertTrue(event.getRestartedPluginIds().isEmpty());
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
    }
}
