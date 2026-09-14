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
 * {@link ModelsLoadedEvent} 的单元测试：验证字段透传、集合只读与公共元信息。
 *
 * @author zcd
 */
class ModelsLoadedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        Set<String> providerNames = new LinkedHashSet<>(Collections.singletonList("openai"));

        // When
        ModelsLoadedEvent event = new ModelsLoadedEvent("openai", "gpt-4o", providerNames);

        // Then
        assertEquals("openai", event.getDefaultProvider());
        assertEquals("gpt-4o", event.getDefaultModel());
        assertEquals(providerNames, event.getProviderNames());
    }

    @Test
    void getProviderNames_should_return_empty_set_when_null() {
        // When
        ModelsLoadedEvent event = new ModelsLoadedEvent(null, null, null);

        // Then
        assertTrue(event.getProviderNames().isEmpty());
        assertNull(event.getDefaultProvider());
        assertNull(event.getDefaultModel());
    }

    @Test
    void getProviderNames_should_be_unmodifiable() {
        // Given
        ModelsLoadedEvent event = new ModelsLoadedEvent(null, null,
                new LinkedHashSet<>(Collections.singletonList("openai")));

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> event.getProviderNames().add("azure"));
    }

    @Test
    void getProviderNames_should_not_reflect_later_changes_of_source() {
        // Given
        Set<String> source = new LinkedHashSet<>(Collections.singletonList("openai"));
        ModelsLoadedEvent event = new ModelsLoadedEvent(null, null, source);

        // When
        source.add("azure");

        // Then
        assertEquals(1, event.getProviderNames().size());
    }

    @Test
    void meta_should_be_filled_when_constructed() {
        // When
        ModelsLoadedEvent event = new ModelsLoadedEvent(null, null, Collections.<String>emptySet());

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertNull(event.getSessionId());
    }
}
