package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionMessageSnapshot} 的单元测试。
 *
 * @author zcd
 */
class SessionMessageSnapshotTest {

    @Test
    void constructor_should_keepAllFields() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 5L, "assistant", "内容",
                "call-1", "read_file", null, new TokenUsageSnapshot(1, 2, 3));

        assertEquals("m-1", snapshot.getMessageId());
        assertEquals(5L, snapshot.getTimestamp());
        assertEquals("assistant", snapshot.getRole());
        assertEquals("内容", snapshot.getContent());
        assertEquals("call-1", snapshot.getToolCallId());
        assertEquals("read_file", snapshot.getName());
        assertEquals(3, snapshot.getUsage().getTotalTokens());
    }

    @Test
    void constructor_should_defaultToolCallsToEmptyList() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "user", "内容",
                null, null, null, null);

        assertTrue(snapshot.getToolCalls().isEmpty());
    }

    @Test
    void constructor_should_fail_when_messageIdBlank() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot(" ", 0L, "user", null,
                null, null, null, null));
    }

    @Test
    void constructor_should_fail_when_roleBlank() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot("m-1", 0L, "", null,
                null, null, null, null));
    }

    @Test
    void getToolCalls_should_returnUnmodifiableCopy() {
        List<SessionToolCallSnapshot> toolCalls = new ArrayList<SessionToolCallSnapshot>();
        toolCalls.add(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}"));
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, toolCalls, null);

        toolCalls.clear();

        assertEquals(1, snapshot.getToolCalls().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getToolCalls().add(new SessionToolCallSnapshot(1, "call-2", "x", "{}")));
    }

    @Test
    void constructor_should_rejectNullToolCallElement() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, Arrays.asList(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}"), null),
                null));
    }

    @Test
    void toString_should_showRoleAndToolCallCount() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, Arrays.asList(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}")), null);

        assertFalse(snapshot.toString().isEmpty());
        assertTrue(snapshot.toString().contains("assistant"));
        assertTrue(snapshot.toString().contains("toolCalls=1"));
    }
}
