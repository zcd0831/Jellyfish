package zcd.jellyfish.api.llm;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmTransportRequest} 的单元测试：验证字段透传、集合只读、默认值与凭据脱敏。
 *
 * @author zcd
 */
class LlmTransportRequestTest {

    @Test
    void builder_should_copy_fields() {
        LlmTransportRequest request = LlmTransportRequest.builder("my-type", "m")
                .providerName("local")
                .apiKey("key")
                .baseUrl("http://localhost")
                .systemPrompt("sys")
                .message(LlmTransportMessage.text(LlmTransportMessage.ROLE_USER, "你好"))
                .tools(Collections.singletonList(
                        new LlmTransportTool("read", "读", Collections.<String, Object>emptyMap(),
                                Collections.<String>emptyList())))
                .toolChoice("auto")
                .temperature(0.5d)
                .maxTokens(100)
                .topP(0.9d)
                .stop(Collections.singletonList("停"))
                .cacheKey("k")
                .cacheRetention("5m")
                .cacheBreakpoints(2)
                .minimalOutput()
                .build();

        assertEquals("my-type", request.getProviderType());
        assertEquals("local", request.getProviderName());
        assertEquals("m", request.getModel());
        assertEquals("sys", request.getSystemPrompt());
        assertEquals(1, request.getMessages().size());
        assertTrue(request.hasTools());
        assertEquals(2, request.getCacheBreakpoints());
        assertTrue(request.isMinimalOutput());
        assertEquals(Collections.singletonList("停"), request.getStop());
    }

    @Test
    void builder_should_reject_blank_provider_type_or_model() {
        assertThrows(JellyfishException.class, () -> LlmTransportRequest.builder(" ", "m").build());
        assertThrows(JellyfishException.class, () -> LlmTransportRequest.builder("t", null).build());
    }

    @Test
    void cancellation_token_should_default_to_never_cancelled() {
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m").build();

        assertSame(CancellationToken.NONE, request.getCancellationToken());
        assertFalse(request.getCancellationToken().isCancelled());
    }

    @Test
    void builder_should_keep_explicit_cancellation_token() {
        CountingToken token = new CountingToken();
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m")
                .cancellationToken(token).build();

        assertSame(token, request.getCancellationToken());
    }

    @Test
    void builder_should_normalize_blank_cache_fields_to_null() {
        // 「设了一个空值」与「没设」在厂商侧是两回事，归一之后只留一种含义
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m")
                .cacheKey("  ")
                .cacheRetention("")
                .build();

        assertNull(request.getCacheKey());
        assertNull(request.getCacheRetention());
    }

    @Test
    void collections_should_be_unmodifiable_and_defensive() {
        List<LlmTransportMessage> messages = new java.util.ArrayList<LlmTransportMessage>();
        messages.add(LlmTransportMessage.text(LlmTransportMessage.ROLE_USER, "a"));
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m").messages(messages).build();

        messages.add(LlmTransportMessage.text(LlmTransportMessage.ROLE_USER, "b"));

        assertEquals(1, request.getMessages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.getMessages().add(LlmTransportMessage.text(LlmTransportMessage.ROLE_USER, "c")));
    }

    @Test
    void null_message_should_be_ignored() {
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m")
                .message(null)
                .build();

        assertTrue(request.getMessages().isEmpty());
    }

    @Test
    void toString_should_redact_api_key() {
        // 请求对象是插件最容易顺手打日志的东西，脱敏必须是它自己的责任
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m")
                .apiKey("sk-SENTINEL").build();

        assertFalse(request.toString().contains("sk-SENTINEL"), request.toString());
        assertTrue(request.toString().contains("***"), request.toString());
    }

    @Test
    void toString_should_mark_absent_api_key_as_none() {
        LlmTransportRequest request = LlmTransportRequest.builder("t", "m").build();

        assertTrue(request.toString().contains("apiKey=none"), request.toString());
    }

    @Test
    void message_should_default_blank_role_to_user() {
        LlmTransportMessage message = new LlmTransportMessage("  ", null, null, null, null, null);

        assertEquals(LlmTransportMessage.ROLE_USER, message.getRole());
    }

    @Test
    void message_should_report_tool_result_only_when_id_present() {
        assertTrue(new LlmTransportMessage(LlmTransportMessage.ROLE_TOOL, "out", null, null, "c1", "read")
                .isToolResult());
        assertFalse(LlmTransportMessage.text(LlmTransportMessage.ROLE_ASSISTANT, "hi").isToolResult());
        assertFalse(new LlmTransportMessage(LlmTransportMessage.ROLE_TOOL, "out", null, null, "", null)
                .isToolResult());
    }

    @Test
    void tool_should_defensively_copy_and_expose_empty_collections() {
        LlmTransportTool tool = new LlmTransportTool("read", null, null, null);

        assertTrue(tool.getParameters().isEmpty());
        assertTrue(tool.getRequired().isEmpty());
        assertNull(tool.getDescription());
    }

    @Test
    void response_should_copy_tool_calls() {
        List<LlmTransportToolCall> calls = new java.util.ArrayList<LlmTransportToolCall>();
        calls.add(new LlmTransportToolCall(0, "c", "read", "{}"));
        LlmTransportResponse response = new LlmTransportResponse("t", null, calls, null, "stop");

        calls.clear();

        assertEquals(1, response.getToolCalls().size());
        assertThrows(UnsupportedOperationException.class,
                () -> response.getToolCalls().add(new LlmTransportToolCall(1, "d", "w", "{}")));
    }

    @Test
    void usage_should_default_total_and_clamp_negative_cache_counts() {
        LlmTransportUsage usage = new LlmTransportUsage(10, 5, 0, -1, -2);

        assertEquals(15, usage.getTotalTokens());
        assertEquals(0, usage.getCacheReadTokens());
        assertEquals(0, usage.getCacheWriteTokens());
    }

    @Test
    void usage_should_keep_explicit_total() {
        assertEquals(100, new LlmTransportUsage(10, 5, 100, 1, 2).getTotalTokens());
    }

    @Test
    void message_listener_should_have_empty_defaults() {
        // 插件只需覆盖关心的事件：默认实现全部存在，才不会逼它写一堆空方法
        LlmTransportListener listener = new LlmTransportListener() {
        };

        listener.onOpen();
        listener.onText("a");
        listener.onReasoning("b");
        listener.onToolCall(new LlmTransportToolCall(0, "c", "read", "{}"));
        listener.onComplete(LlmTransportResponse.text("ok"));
        listener.onCancelled();
        listener.onError(new IllegalStateException("x"));
    }

    @Test
    void tool_call_should_tolerate_null_fields() {
        LlmTransportToolCall call = new LlmTransportToolCall(null, null, null, null);

        assertNull(call.getIndex());
        assertNull(call.getId());
        assertNull(call.getArguments());
    }

    @Test
    void default_message_role_constants_should_match_vendor_spelling() {
        assertEquals(Arrays.asList("system", "user", "assistant", "tool"),
                Arrays.asList(LlmTransportMessage.ROLE_SYSTEM, LlmTransportMessage.ROLE_USER,
                        LlmTransportMessage.ROLE_ASSISTANT, LlmTransportMessage.ROLE_TOOL));
    }

    /**
     * 记录取消注册与触发的令牌。
     *
     * @author zcd
     */
    private static final class CountingToken implements CancellationToken {

        /** 是否已取消。 */
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onCancel(Runnable callback) {
            if (cancelled) {
                callback.run();
            }
        }
    }
}
