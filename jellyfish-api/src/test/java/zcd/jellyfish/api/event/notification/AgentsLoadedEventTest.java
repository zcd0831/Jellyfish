package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentsLoadedEvent} 的单元测试：验证字段透传、集合只读与公共元信息。
 *
 * @author zcd
 */
class AgentsLoadedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        Set<String> agentIds = new LinkedHashSet<>(Collections.singletonList("coder"));

        // When
        AgentsLoadedEvent event = new AgentsLoadedEvent("coder", agentIds);

        // Then
        assertEquals("coder", event.getDefaultAgentId());
        assertEquals(agentIds, event.getAgentIds());
    }

    @Test
    void getAgentIds_should_return_empty_set_when_null() {
        // When
        AgentsLoadedEvent event = new AgentsLoadedEvent(null, null);

        // Then
        assertTrue(event.getAgentIds().isEmpty());
        assertNull(event.getDefaultAgentId());
    }

    @Test
    void getAgentIds_should_be_unmodifiable() {
        // Given
        AgentsLoadedEvent event = new AgentsLoadedEvent("coder",
                new LinkedHashSet<>(Collections.singletonList("coder")));

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> event.getAgentIds().add("other"));
    }

    @Test
    void getAgentIds_should_not_reflect_later_changes_of_source() {
        // Given
        Set<String> source = new LinkedHashSet<>(Collections.singletonList("coder"));
        AgentsLoadedEvent event = new AgentsLoadedEvent("coder", source);

        // When
        source.add("other");

        // Then
        assertEquals(1, event.getAgentIds().size());
    }

    @Test
    void meta_should_be_filled_when_constructed() {
        // When
        AgentsLoadedEvent event = new AgentsLoadedEvent("coder", Collections.<String>emptySet());

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertNull(event.getSessionId());
    }
}
