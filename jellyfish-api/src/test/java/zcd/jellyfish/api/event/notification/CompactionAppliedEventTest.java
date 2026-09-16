package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompactionAppliedEvent} 的单元测试。
 *
 * @author zcd
 */
class CompactionAppliedEventTest {

    @Test
    void constructor_should_keep_all_fields() {
        CompactionAppliedEvent event = new CompactionAppliedEvent("s-1", "m-9", 42, 3, 1234);

        assertEquals("s-1", event.getSessionId());
        assertEquals("m-9", event.getBoundaryMessageId());
        assertEquals(42, event.getCompressedCount());
        assertEquals(3, event.getDroppedCount());
        assertEquals(1234, event.getSummaryLength());
    }

    @Test
    void meta_should_be_filled_and_match_session_when_constructed() {
        CompactionAppliedEvent event = new CompactionAppliedEvent("s-1", "m-9", 42, 3, 1234);

        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertTrue(event.belongsToSession("s-1"));
    }
}
