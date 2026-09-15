package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话快照相关的请求与结果类型单元测试。
 *
 * @author zcd
 */
class SessionRequestTypesTest {

    @Test
    void persistRequest_should_carrySnapshotAndSessionId() {
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null);

        SessionPersistRequest request = new SessionPersistRequest(snapshot);

        assertEquals(snapshot, request.getSnapshot());
        assertEquals("s-1", request.getSessionId());
        assertEquals(Void.class, request.getResultType());
    }

    @Test
    void persistRequest_should_haveNullRouteKey() {
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null);

        assertNull(new SessionPersistRequest(snapshot).getRouteKey());
    }

    @Test
    void persistRequest_should_fail_when_snapshotNull() {
        assertThrows(JellyfishException.class, () -> new SessionPersistRequest(null));
    }

    @Test
    void restoreRequest_should_beProcessLevel() {
        SessionRestoreRequest request = new SessionRestoreRequest();

        assertNull(request.getSessionId());
        assertNull(request.getRouteKey());
        assertEquals(SessionRestoreResult.class, request.getResultType());
    }

    @Test
    void restoreResult_should_carrySnapshots() {
        SessionSnapshot snapshot = new SessionSnapshot("s-1", 0L, 0L, null, null, null, null,
                PermissionMode.NORMAL, null, null);

        SessionRestoreResult result = SessionRestoreResult.of(java.util.Collections.singletonList(snapshot));

        assertEquals(1, result.getSessions().size());
        assertEquals("s-1", result.getSessions().get(0).getSessionId());
    }

    @Test
    void restoreResult_should_beEmpty_when_nullOrEmpty() {
        assertTrue(SessionRestoreResult.of(null).getSessions().isEmpty());
        assertTrue(SessionRestoreResult.empty().getSessions().isEmpty());
        assertTrue(SessionRestoreResult.of(java.util.Collections.<SessionSnapshot>emptyList())
                .getSessions().isEmpty());
    }

    @Test
    void restoreResult_should_rejectNullElement() {
        assertThrows(JellyfishException.class,
                () -> SessionRestoreResult.of(java.util.Collections.singletonList((SessionSnapshot) null)));
    }

    @Test
    void usageSnapshots_should_keepValues() {
        TokenUsageSnapshot token = new TokenUsageSnapshot(1, 2, 3);
        SessionUsageSnapshot session = new SessionUsageSnapshot(10L, 20L, 30L, 4L);

        assertEquals(1, token.getPromptTokens());
        assertEquals(2, token.getCompletionTokens());
        assertEquals(3, token.getTotalTokens());
        assertEquals(10L, session.getPromptTokens());
        assertEquals(20L, session.getCompletionTokens());
        assertEquals(30L, session.getTotalTokens());
        assertEquals(4L, session.getLlmCalls());
    }

    @Test
    void usageSnapshots_should_allowUnknownCounters() {
        TokenUsageSnapshot token = new TokenUsageSnapshot(null, null, null);

        assertNull(token.getPromptTokens());
        assertNull(token.getCompletionTokens());
        assertNull(token.getTotalTokens());
    }

    @Test
    void toolCallSnapshot_should_keepRawArguments() {
        SessionToolCallSnapshot toolCall = new SessionToolCallSnapshot(0, "call-1", "read_file", "{\"a\":1}");

        assertEquals(0, toolCall.getIndex());
        assertEquals("call-1", toolCall.getId());
        assertEquals("read_file", toolCall.getName());
        assertEquals("{\"a\":1}", toolCall.getArguments());
    }
}
