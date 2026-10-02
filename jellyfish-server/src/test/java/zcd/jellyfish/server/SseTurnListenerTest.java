package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
import zcd.jellyfish.server.dto.TurnStartEvent;
import zcd.jellyfish.server.dto.TurnTextEvent;
import zcd.jellyfish.server.dto.TurnThinkingEvent;
import zcd.jellyfish.server.dto.TurnToolDoneEvent;
import zcd.jellyfish.server.dto.TurnToolOutputEvent;
import zcd.jellyfish.server.dto.TurnToolStartEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseTurnListener} 的「可靠 lane 事件 → SSE 事件」映射。
 *
 * @author zcd
 */
class SseTurnListenerTest {

    /** 会话标识。 */
    private static final String SESSION = "s1";

    /** 回合标识（现在由内核生成并随事件一起到达）。 */
    private static final String TURN = "t1";

    /**
     * 造一个订阅者。
     *
     * @return 订阅者
     */
    private static SseTurnListener listener() {
        return new SseTurnListener(SESSION);
    }

    @Test
    void started_should_enqueue_turn_start_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.started(SESSION, TURN));

        SseEvent event = listener.pollNow();
        assertEquals("turn_start", event.getName());
        assertFalse(event.isTerminal());
        assertEquals(TURN, ((TurnStartEvent) event.getPayload()).getTurnId());
    }

    @Test
    void text_should_enqueue_text_event_when_delta_non_empty() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.text(SESSION, TURN, "hi"));

        SseEvent event = listener.pollNow();
        assertEquals("text", event.getName());
        assertFalse(event.isTerminal());
        assertEquals("hi", ((TurnTextEvent) event.getPayload()).getDelta());
        assertEquals(TURN, ((TurnTextEvent) event.getPayload()).getTurnId());
    }

    @Test
    void thinking_should_enqueue_thinking_event_when_delta_non_empty() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.thinking(SESSION, TURN, "why"));

        SseEvent event = listener.pollNow();
        assertEquals("thinking", event.getName());
        assertEquals("why", ((TurnThinkingEvent) event.getPayload()).getDelta());
    }

    @Test
    void toolStarted_should_enqueue_tool_start_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.toolStarted(SESSION, TURN, "c1", "read_file", null));

        SseEvent event = listener.pollNow();
        assertEquals("tool_start", event.getName());
        TurnToolStartEvent payload = (TurnToolStartEvent) event.getPayload();
        assertEquals("c1", payload.getToolCallId());
        assertEquals("read_file", payload.getToolName());
    }

    @Test
    void toolOutput_should_enqueue_tool_output_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", "building...\n"));

        SseEvent event = listener.pollNow();
        assertEquals("tool_output", event.getName());
        assertFalse(event.isTerminal());
        TurnToolOutputEvent payload = (TurnToolOutputEvent) event.getPayload();
        assertEquals(TURN, payload.getTurnId());
        assertEquals("c1", payload.getToolCallId());
        assertEquals("shell", payload.getToolName());
        assertEquals("building...\n", payload.getChunk());
    }

    @Test
    void toolOutput_should_skip_when_chunk_empty() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", ""));
        listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", null));

        assertNull(listener.pollNow());
    }

    @Test
    void toolOutput_should_dropAndCount_when_pendingOverLimit() {
        // Given：待发上限是 64，而子进程能一直吐（消费端慢）
        SseTurnListener listener = listener();

        // When：推 100 条但一条也不取
        for (int i = 0; i < 100; i++) {
            listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", "chunk-" + i));
        }

        // Then：队列里有 64 条，其余 36 条丢弃并计数（权威文本随后由 tool_done 给出）
        assertEquals(36, listener.getDroppedToolOutput());
        int queued = 0;
        while (listener.pollNow() != null) {
            queued++;
        }
        assertEquals(64, queued);
    }

    @Test
    void toolOutput_should_allowMore_when_pendingDrained() {
        // Given：上限按「未取走的条数」算，而不是按累计条数
        SseTurnListener listener = listener();
        for (int i = 0; i < 100; i++) {
            listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", "chunk-" + i));
        }

        // When：取走全部之后继续推
        while (listener.pollNow() != null) {
            continue;
        }
        listener.onTurnEvent(ShellTurnEvent.toolOutput(SESSION, TURN, "c1", "shell", "later"));

        // Then：丢弃计数不再增长，新片段照常入队
        assertEquals(36, listener.getDroppedToolOutput());
        assertNotNull(listener.pollNow());
    }

    @Test
    void toolCompleted_should_enqueue_tool_done_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.toolCompleted(SESSION, TURN, "c1", "read_file", true,
                "content", null));

        SseEvent event = listener.pollNow();
        assertEquals("tool_done", event.getName());
        TurnToolDoneEvent payload = (TurnToolDoneEvent) event.getPayload();
        assertTrue(payload.isSuccess());
        assertEquals("content", payload.getOutput());
    }

    @Test
    void completed_should_enqueue_terminal_done_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.completed(SESSION, TURN, "final", 2, false));

        SseEvent event = listener.pollNow();
        assertEquals("done", event.getName());
        assertTrue(event.isTerminal());
        TurnCompleteEvent payload = (TurnCompleteEvent) event.getPayload();
        assertEquals("final", payload.getContent());
        assertEquals(2, payload.getRounds());
        assertFalse(payload.isTruncated());
    }

    @Test
    void completed_should_mark_truncated_when_event_truncated() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.completed(SESSION, TURN, "too many rounds", 5, true));

        SseEvent event = listener.pollNow();
        assertTrue(((TurnCompleteEvent) event.getPayload()).isTruncated());
    }

    @Test
    void cancelled_should_enqueue_terminal_cancelled_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.cancelled(SESSION, TURN));

        SseEvent event = listener.pollNow();
        assertEquals("cancelled", event.getName());
        assertTrue(event.isTerminal());
        assertNotNull(event.getPayload());
    }

    @Test
    void blocked_should_enqueue_terminal_blocked_event() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.blocked(SESSION, TURN, "工作区不干净"));

        SseEvent event = listener.pollNow();
        assertEquals("turn_blocked", event.getName());
        assertTrue(event.isTerminal());
    }

    @Test
    void error_should_enqueue_terminal_error_event_with_message() {
        SseTurnListener listener = listener();

        listener.onTurnEvent(ShellTurnEvent.error(SESSION, TURN, new IllegalStateException("boom")));

        SseEvent event = listener.pollNow();
        assertEquals("error", event.getName());
        assertTrue(event.isTerminal());
        TurnErrorEvent payload = (TurnErrorEvent) event.getPayload();
        assertEquals("boom", payload.getMessage());
        assertEquals("INTERNAL_ERROR", payload.getCode());
    }
}
