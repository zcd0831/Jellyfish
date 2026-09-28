package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.server.dto.TurnCompleteEvent;
import zcd.jellyfish.server.dto.TurnErrorEvent;
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
    void onToolCallOutput_should_enqueue_tool_output_event() {
        SseReActListener listener = listener();

        listener.onToolCallOutput("c1", "shell", "building...\n");

        SseEvent event = listener.pollNow();
        assertEquals("tool_output", event.getName());
        assertFalse(event.isTerminal());
        TurnToolOutputEvent payload = (TurnToolOutputEvent) event.getPayload();
        assertEquals("t1", payload.getTurnId());
        assertEquals("c1", payload.getToolCallId());
        assertEquals("shell", payload.getToolName());
        assertEquals("building...\n", payload.getChunk());
    }

    @Test
    void onToolCallOutput_should_skip_when_chunk_empty() {
        SseReActListener listener = listener();

        listener.onToolCallOutput("c1", "shell", "");
        listener.onToolCallOutput("c1", "shell", null);

        assertNull(listener.pollNow());
    }

    @Test
    void onToolCallOutput_should_dropAndCount_when_pendingOverLimit() {
        // Given：待发上限是 64，而子进程能一直吐（消费端慢）
        SseReActListener listener = listener();

        // When：推 100 条但一条也不取
        for (int i = 0; i < 100; i++) {
            listener.onToolCallOutput("c1", "shell", "chunk-" + i);
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
    void onToolCallOutput_should_allowMore_when_pendingDrained() {
        // Given：上限按「未取走的条数」算，而不是按累计条数
        SseReActListener listener = listener();
        for (int i = 0; i < 100; i++) {
            listener.onToolCallOutput("c1", "shell", "chunk-" + i);
        }

        // When：取走全部之后继续推
        while (listener.pollNow() != null) {
            continue;
        }
        listener.onToolCallOutput("c1", "shell", "later");

        // Then：丢弃计数不再增长，新片段照常入队
        assertEquals(36, listener.getDroppedToolOutput());
        assertNotNull(listener.pollNow());
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
