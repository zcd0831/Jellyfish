package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
import zcd.jellyfish.server.dto.TurnTextEvent;
import zcd.jellyfish.server.dto.TurnThinkingEvent;
import zcd.jellyfish.server.dto.TurnToolDoneEvent;
import zcd.jellyfish.server.dto.TurnToolStartEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseReActListener} 的回调 → 事件映射。
 *
 * @author zcd
 */
class SseReActListenerTest {

    /**
     * 造一个已绑定回合标识的监听器。
     *
     * @return 监听器
     */
    private static SseReActListener listener() {
        return new SseReActListener("s1", "t1");
    }

    @Test
    void onText_should_enqueue_text_event_when_delta_non_empty() {
        SseReActListener listener = listener();

        listener.onText("hi");

        SseEvent event = listener.pollNow();
        assertEquals("text", event.getName());
        assertFalse(event.isTerminal());
        assertEquals("hi", ((TurnTextEvent) event.getPayload()).getDelta());
        assertEquals("t1", ((TurnTextEvent) event.getPayload()).getTurnId());
    }

    @Test
    void onText_should_skip_when_delta_empty() {
        SseReActListener listener = listener();

        listener.onText("");
        listener.onText(null);

        assertNull(listener.pollNow());
    }

    @Test
    void onThinking_should_enqueue_thinking_event_when_delta_non_empty() {
        SseReActListener listener = listener();

        listener.onThinking("why");

        SseEvent event = listener.pollNow();
        assertEquals("thinking", event.getName());
        assertEquals("why", ((TurnThinkingEvent) event.getPayload()).getDelta());
    }

    @Test
    void onToolCallStarted_should_enqueue_tool_start_event() {
        SseReActListener listener = listener();

        listener.onToolCallStarted("c1", "read_file");

        SseEvent event = listener.pollNow();
        assertEquals("tool_start", event.getName());
        TurnToolStartEvent payload = (TurnToolStartEvent) event.getPayload();
        assertEquals("c1", payload.getToolCallId());
        assertEquals("read_file", payload.getToolName());
    }

    @Test
    void onToolCallCompleted_should_enqueue_tool_done_event() {
        SseReActListener listener = listener();

        listener.onToolCallCompleted("c1", "read_file", true, "content");

        SseEvent event = listener.pollNow();
        assertEquals("tool_done", event.getName());
        TurnToolDoneEvent payload = (TurnToolDoneEvent) event.getPayload();
        assertTrue(payload.isSuccess());
        assertEquals("content", payload.getOutput());
    }

    @Test
    void onComplete_should_enqueue_terminal_done_event() {
        SseReActListener listener = listener();

        listener.onComplete(ReActResult.completed("s1", "final", 2));

        SseEvent event = listener.pollNow();
        assertEquals("done", event.getName());
        assertTrue(event.isTerminal());
        TurnCompleteEvent payload = (TurnCompleteEvent) event.getPayload();
        assertEquals("final", payload.getContent());
        assertEquals(2, payload.getRounds());
        assertFalse(payload.isTruncated());
    }

    @Test
    void onComplete_should_mark_truncated_when_result_truncated() {
        SseReActListener listener = listener();

        listener.onComplete(ReActResult.truncated("s1", "too many rounds", 5));

        SseEvent event = listener.pollNow();
        assertTrue(((TurnCompleteEvent) event.getPayload()).isTruncated());
    }

    @Test
    void onCancelled_should_enqueue_terminal_cancelled_event() {
        SseReActListener listener = listener();

        listener.onCancelled();

        SseEvent event = listener.pollNow();
        assertEquals("cancelled", event.getName());
        assertTrue(event.isTerminal());
        assertNotNull(event.getPayload());
    }

    @Test
    void onError_should_enqueue_terminal_error_event_with_message() {
        SseReActListener listener = listener();

        listener.onError(new IllegalStateException("boom"));

        SseEvent event = listener.pollNow();
        assertEquals("error", event.getName());
        assertTrue(event.isTerminal());
        TurnErrorEvent payload = (TurnErrorEvent) event.getPayload();
        assertEquals("boom", payload.getMessage());
        assertEquals("INTERNAL_ERROR", payload.getCode());
    }
}
