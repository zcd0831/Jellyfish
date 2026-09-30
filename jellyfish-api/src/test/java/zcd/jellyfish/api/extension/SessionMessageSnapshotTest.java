package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "call-1", "read_file", null, new TokenUsageSnapshot(1, 2, 3, 4, 5), "想了一下", null);

        assertEquals("m-1", snapshot.getMessageId());
        assertEquals(5L, snapshot.getTimestamp());
        assertEquals("assistant", snapshot.getRole());
        assertEquals("内容", snapshot.getContent());
        assertEquals("call-1", snapshot.getToolCallId());
        assertEquals("read_file", snapshot.getName());
        assertEquals(3, snapshot.getUsage().getTotalTokens());
        assertEquals("想了一下", snapshot.getThinking());
    }

    @Test
    void constructor_should_defaultToolCallsToEmptyList() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "user", "内容",
                null, null, null, null, null, null);

        assertTrue(snapshot.getToolCalls().isEmpty());
    }

    @Test
    void constructor_should_keepThinkingNull_when_absent() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "user", "内容",
                null, null, null, null, null, null);

        assertNull(snapshot.getThinking());
    }

    @Test
    void of_should_buildSnapshotWithoutThinking() {
        SessionMessageSnapshot snapshot = SessionMessageSnapshot.of("m-1", 0L, "user", "内容",
                null, null, null, null);

        assertEquals("内容", snapshot.getContent());
        assertNull(snapshot.getThinking());
    }

    @Test
    void constructor_should_fail_when_messageIdBlank() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot(" ", 0L, "user", null,
                null, null, null, null, null, null));
    }

    @Test
    void constructor_should_fail_when_roleBlank() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot("m-1", 0L, "", null,
                null, null, null, null, null, null));
    }

    @Test
    void getToolCalls_should_returnUnmodifiableCopy() {
        List<SessionToolCallSnapshot> toolCalls = new ArrayList<SessionToolCallSnapshot>();
        toolCalls.add(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}"));
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, toolCalls, null, null, null);

        toolCalls.clear();

        assertEquals(1, snapshot.getToolCalls().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getToolCalls().add(new SessionToolCallSnapshot(1, "call-2", "x", "{}")));
    }

    @Test
    void constructor_should_rejectNullToolCallElement() {
        assertThrows(JellyfishException.class, () -> new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, Arrays.asList(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}"), null),
                null, null, null));
    }

    @Test
    void toString_should_showRoleAndToolCallCount() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "assistant", null,
                null, null, Arrays.asList(new SessionToolCallSnapshot(0, "call-1", "read_file", "{}")), null,
                null, null);

        assertFalse(snapshot.toString().isEmpty());
        assertTrue(snapshot.toString().contains("assistant"));
        assertTrue(snapshot.toString().contains("toolCalls=1"));
    }

    @Test
    void getMetadata_should_defaultToEmptyMap_when_absent() {
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "user", "内容",
                null, null, null, null, null, null);

        assertTrue(snapshot.getMetadata().isEmpty());
    }

    @Test
    void getMetadata_should_keepUnmodifiableCopy() {
        // Given：调用方给的映射可能在构造之后被改动（工具自己还持有它）
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1));
        SessionMessageSnapshot snapshot = new SessionMessageSnapshot("m-1", 0L, "tool", "内容",
                "call-1", "shell", null, null, null, metadata);

        // When
        metadata.put("late", "value");

        // Then：拷贝之后不受影响，且不可变
        assertEquals(1, snapshot.getMetadata().size());
        assertEquals(Integer.valueOf(1), snapshot.getMetadata().get(ToolMetadata.KEY_EXIT_CODE));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getMetadata().put("x", "y"));
    }
}
