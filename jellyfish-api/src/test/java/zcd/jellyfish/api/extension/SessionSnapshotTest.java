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
        SessionSnapshot snapshot = SessionSnapshot.of("s-1", 1L, 2L, "标题", "coder", "openai", "gpt-4o",
                PermissionMode.PLAN, null, null);

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
        assertNull(snapshot.getUsage());
    }

    @Test
    void constructor_should_fail_when_sessionIdBlank() {
        assertThrows(JellyfishException.class, () -> SessionSnapshot.of("  ", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null));
    }

    @Test
    void constructor_should_fail_when_permissionModeNull() {
        assertThrows(JellyfishException.class, () -> SessionSnapshot.of("s-1", 0L, 0L, null, null, null, null,
                null, null, null));
    }

    @Test
    void getMessages_should_returnUnmodifiableCopy() {
        List<SessionMessageSnapshot> messages = new ArrayList<SessionMessageSnapshot>();
        messages.add(message("m-1"));
        SessionSnapshot snapshot = SessionSnapshot.of("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, messages, null);

        messages.clear();

        assertEquals(1, snapshot.getMessages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getMessages().add(message("m-2")));
    }

    @Test
    void constructor_should_rejectNullMessageElement() {
        assertThrows(JellyfishException.class, () -> SessionSnapshot.of("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, Arrays.asList(message("m-1"), null), null));
    }

    @Test
    void toString_should_notDumpMessageBodies() {
        SessionSnapshot snapshot = SessionSnapshot.of("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, Collections.singletonList(message("m-1")), null);

        assertEquals("SessionSnapshot{sessionId=s-1, kind=NORMAL, messages=1}", snapshot.toString());
    }

    @Test
    void of_should_leave_compactionNull() {
        // When：旧签名入口（不含压缩字段）
        SessionSnapshot snapshot = minimal("s-1");

        // Then：压缩为 null，表示「从未压缩过」
        assertNull(snapshot.getCompaction());
    }

    @Test
    void constructor_should_keep_compaction() {
        // Given
        SessionCompactionSnapshot compaction = new SessionCompactionSnapshot("摘要", "m-9", 7L, 4);

        // When
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null, compaction, SessionKind.FORKED, "s-0", "m-9", null);

        // Then
        assertEquals(compaction, snapshot.getCompaction());
    }

    @Test
    void constructor_should_default_kind_to_normal_when_absent() {
        // 老快照没有 kind 字段：Jackson 传进来的是 null，此时按普通会话处理
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null, null, null, null, null, null);

        assertEquals(SessionKind.NORMAL, snapshot.getKind());
        assertNull(snapshot.getParentSessionId());
        assertNull(snapshot.getForkPointMessageId());
        assertTrue(snapshot.getExtensionEntries().isEmpty());
    }

    @Test
    void constructor_should_reject_null_extension_entry() {
        List<SessionExtensionEntry> entries = new ArrayList<SessionExtensionEntry>();
        entries.add(null);

        assertThrows(JellyfishException.class, () -> new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null, null, SessionKind.NORMAL, null, null, entries));
    }

    @Test
    void compaction_should_reject_blank_summary_and_boundary() {
        assertThrows(JellyfishException.class, () -> new SessionCompactionSnapshot("  ", "m-9", 0L, 0));
        assertThrows(JellyfishException.class, () -> new SessionCompactionSnapshot("摘要", " ", 0L, 0));
    }

    @Test
    void compaction_toString_should_not_dump_summary() {
        SessionCompactionSnapshot compaction = new SessionCompactionSnapshot("很长的一段摘要", "m-9", 0L, 0);

        assertEquals("SessionCompactionSnapshot{boundaryMessageId=m-9, summaryLength=7}", compaction.toString());
    }

    /**
     * 构造一个最小会话快照。
     *
     * @param sessionId 会话标识
     * @return 会话快照
     */
    private static SessionSnapshot minimal(String sessionId) {
        return SessionSnapshot.of(sessionId, 0L, 0L, null, null, null, null, PermissionMode.NORMAL,
                null, null);
    }

    /**
     * 构造一条消息快照。
     *
     * @param messageId 消息标识
     * @return 消息快照
     */
    private static SessionMessageSnapshot message(String messageId) {
        return SessionMessageSnapshot.of(messageId, 0L, "user", "内容", null, null, null, null);
    }
}
