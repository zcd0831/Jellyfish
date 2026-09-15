package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionSnapshot} 的单元测试。
 *
 * @author zcd
 */
class SessionSnapshotTest {

    @Test
    void constructor_should_keepAllFields() {
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 1L, 2L, "标题", "coder", "openai", "gpt-4o",
                PermissionMode.PLAN, null, null, null);

        assertEquals("s-1", snapshot.getSessionId());
        assertEquals(1L, snapshot.getCreatedAt());
        assertEquals(2L, snapshot.getUpdatedAt());
        assertEquals("标题", snapshot.getTitle());
        assertEquals("coder", snapshot.getAgentId());
        assertEquals("openai", snapshot.getProvider());
        assertEquals("gpt-4o", snapshot.getModel());
        assertEquals(PermissionMode.PLAN, snapshot.getPermissionMode());
    }

    @Test
    void constructor_should_defaultListsAndKeepNullableUsage() {
        SessionSnapshot snapshot = minimal("s-1");

        assertTrue(snapshot.getMessages().isEmpty());
        assertTrue(snapshot.getTodos().isEmpty());
        assertNull(snapshot.getUsage());
    }

    @Test
    void constructor_should_fail_when_sessionIdBlank() {
        assertThrows(JellyfishException.class, () -> new SessionSnapshot("  ", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null, null));
    }

    @Test
    void constructor_should_fail_when_permissionModeNull() {
        assertThrows(JellyfishException.class, () -> new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                null, null, null, null));
    }

    @Test
    void getMessages_should_returnUnmodifiableCopy() {
        List<SessionMessageSnapshot> messages = new ArrayList<SessionMessageSnapshot>();
        messages.add(message("m-1"));
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, messages, null, null);

        messages.clear();

        assertEquals(1, snapshot.getMessages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getMessages().add(message("m-2")));
    }

    @Test
    void constructor_should_rejectNullMessageElement() {
        assertThrows(JellyfishException.class, () -> new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, Arrays.asList(message("m-1"), null), null, null));
    }

    @Test
    void constructor_should_rejectNullTodoElement() {
        assertThrows(JellyfishException.class, () -> new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, Arrays.asList(new SessionTodoSnapshot("1", "a", false, 0L), null),
                null));
    }

    @Test
    void toString_should_notDumpMessageBodies() {
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, Collections.singletonList(message("m-1")), null, null);

        assertEquals("SessionSnapshot{sessionId=s-1, messages=1, todos=0}", snapshot.toString());
    }

    /**
     * 构造一个最小会话快照。
     *
     * @param sessionId 会话标识
     * @return 会话快照
     */
    private static SessionSnapshot minimal(String sessionId) {
        return new SessionSnapshot(sessionId, 0L, 0L, null, null, null, null, PermissionMode.NORMAL,
                null, null, null);
    }

    /**
     * 构造一条消息快照。
     *
     * @param messageId 消息标识
     * @return 消息快照
     */
    private static SessionMessageSnapshot message(String messageId) {
        return new SessionMessageSnapshot(messageId, 0L, "user", "内容", null, null, null, null);
    }
}
