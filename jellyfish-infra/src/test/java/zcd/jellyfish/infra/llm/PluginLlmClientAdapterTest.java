package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.LlmHttpException;
import zcd.jellyfish.api.extension.ProviderContribution;
import zcd.jellyfish.api.llm.LlmTransport;
import zcd.jellyfish.api.llm.LlmTransportListener;
import zcd.jellyfish.api.llm.LlmTransportMessage;
import zcd.jellyfish.api.llm.LlmTransportRequest;
import zcd.jellyfish.api.llm.LlmTransportResponse;
import zcd.jellyfish.api.llm.LlmTransportToolCall;
import zcd.jellyfish.api.llm.LlmTransportUsage;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginLlmClientAdapter} 的单元测试：覆盖两个方向的字段映射、事件桥接、取消与违约处理。
 *
 * @author zcd
 */
class PluginLlmClientAdapterTest {

    /** 哨兵密钥：任何内核自己产生的字符串里出现它，都是一次凭据泄漏。 */
    private static final String SENTINEL_KEY = "sk-SENTINEL-DO-NOT-LEAK";

    /** 测试中记录到的传输请求。 */
    private LlmTransportRequest captured;

    /** 同步执行、不新起线程的线程池。 */
    private final ExecutorService directExecutor = new DirectExecutorService();

    @Test
    void chat_should_aggregate_text_reasoning_and_tool_calls() {
        // Given：文本与思考分片到达，工具调用按 index 分两片
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            listener.onOpen();
            listener.onText("你");
            listener.onText("好");
            listener.onReasoning("先想");
            listener.onToolCall(new LlmTransportToolCall(0, "call_1", "read", "{\"a\""));
            listener.onToolCall(new LlmTransportToolCall(0, "call_1", "read", "{\"a\":1}"));
            listener.onToolCall(new LlmTransportToolCall(1, "call_2", "write", "{}"));
            listener.onComplete(new LlmTransportResponse("好", "先想", null,
                    new LlmTransportUsage(10, 5), "stop"));
        });

        // When
        LlmResponse response = adapter.chat(request());

        // Then
        assertEquals("你好", response.getContent());
        assertEquals("先想", response.getThinking());
        assertEquals(2, response.getToolCalls().size());
        assertEquals("{\"a\":1}", response.getToolCalls().get(0).getArguments());
        assertEquals("call_2", response.getToolCalls().get(1).getId());
        assertEquals(10, response.getUsage().getPromptTokens());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void chat_should_fall_back_to_complete_payload_when_no_delta_arrived() {
        // 只报完整结果的插件（非流式实现）也要能跑
        PluginLlmClientAdapter adapter = adapter((request, listener) -> listener.onComplete(
                new LlmTransportResponse("整段", "思考", Arrays.asList(
                        new LlmTransportToolCall(0, "c", "read", "{}")), null, "end")));

        LlmResponse response = adapter.chat(request());

        assertEquals("整段", response.getContent());
        assertEquals("思考", response.getThinking());
        assertEquals(1, response.getToolCalls().size());
    }

    @Test
    void chat_should_map_request_fields_to_transport_contract() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        adapter.chat(request());

        assertEquals("my-gateway", captured.getProviderName());
        assertEquals("my-type", captured.getProviderType());
        assertEquals("gpt-4o", captured.getModel());
        assertEquals("system", captured.getSystemPrompt());
        assertEquals(2, captured.getMessages().size());
        assertEquals("user", captured.getMessages().get(0).getRole());
        assertEquals(1, captured.getTools().size());
        assertEquals("read", captured.getTools().get(0).getName());
        assertEquals("auto", captured.getToolChoice());
        assertEquals(0.2d, captured.getTemperature());
        assertEquals(1024, captured.getMaxTokens());
        assertTrue(captured.isMinimalOutput());
        assertEquals(3, captured.getCacheBreakpoints());
        assertTrue(captured.hasTools());
    }

    @Test
    void chat_should_pass_api_key_and_base_url_through_to_transport() {
        // 插件要拿它去发请求，因此必须给；不给等于让它自己去读配置（明令禁止）
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        adapter.chat(request());

        assertEquals(SENTINEL_KEY, captured.getApiKey());
        assertEquals("https://gateway.example.com", captured.getBaseUrl());
    }

    @Test
    void transport_request_toString_should_not_leak_api_key() {
        // 请求对象是插件最容易顺手打日志的东西，脱敏必须是它自己的责任
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        adapter.chat(request());

        assertFalse(captured.toString().contains(SENTINEL_KEY), captured.toString());
        assertTrue(captured.toString().contains("***"), captured.toString());
    }

    @Test
    void adapter_errors_should_not_leak_api_key() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            throw new IllegalStateException("boom");
        });

        JellyfishException error = assertThrows(JellyfishException.class, () -> adapter.chat(request()));

        assertFalse(error.getMessage().contains(SENTINEL_KEY), error.getMessage());
    }

    @Test
    void chat_should_rethrow_llm_http_exception_with_status_code() {
        // 状态码是「降级」与「重试」的分界，包一层就没了
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            throw new LlmHttpException("rejected", 400);
        });

        LlmHttpException error = assertThrows(LlmHttpException.class, () -> adapter.chat(request()));

        assertEquals(400, error.getStatusCode());
    }

    @Test
    void chat_should_fail_when_transport_returns_without_terminal_event() {
        // 把违约当成「空响应」会让用户看到一次莫名其妙的空回合，如实报错才是对的做法
        PluginLlmClientAdapter adapter = adapter((request, listener) -> listener.onText("半句话"));

        JellyfishException error = assertThrows(JellyfishException.class, () -> adapter.chat(request()));

        assertTrue(error.getMessage().contains("terminal"), error.getMessage());
    }

    @Test
    void chat_should_fail_when_transport_reports_cancelled() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            listener.onText("半句话");
            listener.onCancelled();
        });

        JellyfishException error = assertThrows(JellyfishException.class, () -> adapter.chat(request()));

        assertTrue(error.getMessage().contains("cancelled"), error.getMessage());
    }

    @Test
    void chat_should_report_error_when_transport_fails() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> listener.onError(
                new JellyfishException("端点拒绝")));

        JellyfishException error = assertThrows(JellyfishException.class, () -> adapter.chat(request()));

        assertEquals("端点拒绝", error.getMessage());
    }

    @Test
    void chat_should_use_never_cancelled_token_when_sync() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        adapter.chat(request());

        assertFalse(captured.getCancellationToken().isCancelled());
    }

    @Test
    void chatStream_should_forward_events_to_listener() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            listener.onOpen();
            listener.onText("增");
            listener.onReasoning("想");
            listener.onToolCall(new LlmTransportToolCall(0, "c", "read", "{}"));
            listener.onComplete(new LlmTransportResponse("增", "想",
                    Collections.<LlmTransportToolCall>emptyList(), null, "stop"));
        });
        RecordingListener listener = new RecordingListener();

        adapter.chatStream(request(), listener);

        assertTrue(listener.opened);
        assertEquals("增", listener.text.toString());
        assertEquals("想", listener.thinking.toString());
        assertEquals(1, listener.toolCalls.size());
        assertNotNull(listener.completed);
        assertEquals("增", listener.completed.getContent());
        assertEquals("stop", listener.completed.getFinishReason());
    }

    @Test
    void chatStream_should_pass_a_cancellable_token_to_transport() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        LlmStreamHandle handle = adapter.chatStream(request(), new RecordingListener());
        boolean before = captured.getCancellationToken().isCancelled();
        handle.cancel();

        assertFalse(before);
        assertTrue(captured.getCancellationToken().isCancelled());
    }

    @Test
    void chatStream_should_register_cancel_callback_for_transport() {
        // 插件靠这个回调中断自家 HTTP 调用；只置标志救不了一个正在读流的长调用
        AtomicInteger cancellations = new AtomicInteger();
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            request.getCancellationToken().onCancel(cancellations::incrementAndGet);
            listener.onComplete(LlmTransportResponse.text("ok"));
        });

        LlmStreamHandle handle = adapter.chatStream(request(), new RecordingListener());
        handle.cancel();

        assertEquals(1, cancellations.get());
    }

    @Test
    void chatStream_should_stop_forwarding_after_cancelled() throws Exception {
        // 取消之后仍在回吐增量的插件，不能把内容冲进已经收尾的回合。
        // 用真线程：传输必须「还没返回」才谈得上「取消发生在回合中间」
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            PluginLlmClientAdapter adapter = new PluginLlmClientAdapter(provider(),
                    contribution((request, listener) -> {
                        listener.onText("第一段");
                        request.getCancellationToken().onCancel(listener::onCancelled);
                        request.getCancellationToken().onCancel(cancelled::countDown);
                        started.countDown();
                        awaitQuietly(cancelled);
                        listener.onText("多余");
                    }), pool);
            RecordingListener listener = new RecordingListener();
            LlmStreamHandle handle = adapter.chatStream(request(), listener);

            started.await(5, TimeUnit.SECONDS);
            handle.cancel();
            cancelled.await(5, TimeUnit.SECONDS);

            // Then：取消已上报，且取消之后的增量被挡住
            assertTrue(listener.cancelled);
            assertEquals("第一段", listener.text.toString());
            assertNull(listener.error);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 等待一个闩锁，被中断时直接返回。
     *
     * @param latch 闩锁
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void chatStream_should_report_error_when_transport_returns_without_terminal_event() {
        // 少一个终止事件就是一次永久挂住的回合：ReAct 循环在等它收尾
        PluginLlmClientAdapter adapter = adapter((request, listener) -> listener.onText("半句话"));
        RecordingListener listener = new RecordingListener();

        adapter.chatStream(request(), listener);

        assertNotNull(listener.error);
        assertTrue(listener.error.getMessage().contains("terminal"), listener.error.getMessage());
    }

    @Test
    void chatStream_should_report_error_when_transport_throws() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            throw new LlmHttpException("rejected", 429);
        });
        RecordingListener listener = new RecordingListener();

        adapter.chatStream(request(), listener);

        assertTrue(listener.error instanceof LlmHttpException);
        assertEquals(429, ((LlmHttpException) listener.error).getStatusCode());
    }

    @Test
    void chatStream_should_report_error_when_executor_rejects() {
        ExecutorService rejecting = new DirectExecutorService() {

            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("full");
            }
        };
        PluginLlmClientAdapter adapter = new PluginLlmClientAdapter(provider(),
                contribution((request, listener) -> listener.onComplete(LlmTransportResponse.text("ok"))),
                rejecting);
        RecordingListener listener = new RecordingListener();

        adapter.chatStream(request(), listener);

        assertNotNull(listener.error);
        assertTrue(listener.error.getMessage().contains("rejected"), listener.error.getMessage());
    }

    @Test
    void chatStream_should_ignore_events_after_complete() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            listener.onComplete(LlmTransportResponse.text("完"));
            listener.onText("多余");
            listener.onError(new JellyfishException("多余"));
        });
        RecordingListener listener = new RecordingListener();

        adapter.chatStream(request(), listener);

        assertEquals("", listener.text.toString());
        assertNotNull(listener.completed);
        assertNull(listener.error);
    }

    @Test
    void constructor_should_reject_unsupported_contribution() {
        assertThrows(JellyfishException.class,
                () -> new PluginLlmClientAdapter(provider(), ProviderContribution.unsupported(), directExecutor));
    }

    @Test
    void constructor_should_reject_null_provider() {
        assertThrows(JellyfishException.class, () -> new PluginLlmClientAdapter(
                null, contribution((request, listener) -> { }), directExecutor));
    }

    @Test
    void constructor_should_reject_null_executor() {
        assertThrows(JellyfishException.class, () -> new PluginLlmClientAdapter(
                provider(), contribution((request, listener) -> { }), null));
    }

    @Test
    void getProvider_should_return_bound_provider() {
        PluginLlmClientAdapter adapter = adapter((request, listener) ->
                listener.onComplete(LlmTransportResponse.text("ok")));

        assertEquals("my-gateway", adapter.getProvider().getName());
    }

    /**
     * 构造被测适配器。
     *
     * @param transport 插件传输实现
     * @return 适配器
     */
    private PluginLlmClientAdapter adapter(LlmTransport transport) {
        return new PluginLlmClientAdapter(provider(), contribution(transport), directExecutor);
    }

    /**
     * 构造绑定指定 provider 的被测适配器。
     *
     * @param provider  provider 配置
     * @param transport 插件传输实现
     * @return 适配器
     */
    private PluginLlmClientAdapter adapter(Provider provider, LlmTransport transport) {
        return new PluginLlmClientAdapter(provider, contribution(transport), directExecutor);
    }

    /**
     * 包装传输实现为「接管」的贡献。
     *
     * @param transport 传输实现
     * @return 贡献
     */
    private static ProviderContribution contribution(LlmTransport transport) {
        return ProviderContribution.of("测试插件", transport);
    }

    @Test
    void chat_should_pass_vendorBody_and_vendorHeaders_to_transport() {
        // Given：provider 配了自定义头，请求里带上直通字段
        PluginLlmClientAdapter pluginAdapter = adapter(providerWithPassthrough(), (request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });
        LlmRequest passthrough = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("你好"))
                .vendorBody(Collections.<String, Object>singletonMap("service_tier", "flex"))
                .build();

        // When
        pluginAdapter.chat(passthrough);

        // Then：两项都要交到插件手上——否则用户会遇到「换了 type 之后配的字段就不生效」
        assertEquals("flex", captured.getVendorBody().get("service_tier"));
        assertEquals("t-1", captured.getVendorHeaders().get("x-tenant"));
    }

    @Test
    void chat_should_pass_maxTokensField_to_transport() {
        // Given：插件接管时也要看得见输出上限的字段名——否则同一个配置换个 type 就 400
        PluginLlmClientAdapter pluginAdapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });
        LlmRequest withField = LlmRequest.builder("gpt-5")
                .message(LlmMessage.user("你好"))
                .maxTokens(8192)
                .maxTokensField(Model.COMPLETION_MAX_TOKENS_FIELD)
                .build();

        // When
        pluginAdapter.chat(withField);

        // Then
        assertEquals(Model.COMPLETION_MAX_TOKENS_FIELD, captured.getMaxTokensField());
        assertEquals(8192, captured.getMaxTokens());
    }

    @Test
    void chat_should_pass_sampling_extras_to_transport() {
        // Given：插件接管时也要看得见这些采样参数——插件自己决定自家协议认不认
        PluginLlmClientAdapter pluginAdapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });
        LlmRequest withSampling = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("你好"))
                .topK(40)
                .seed(7L)
                .frequencyPenalty(0.5d)
                .presencePenalty(-0.5d)
                .build();

        // When
        pluginAdapter.chat(withSampling);

        // Then
        assertEquals(40, captured.getTopK());
        assertEquals(7L, captured.getSeed());
        assertEquals(0.5d, captured.getFrequencyPenalty());
        assertEquals(-0.5d, captured.getPresencePenalty());
    }

    /**
     * 构造带哨兵密钥的 provider。
     *
     * @return provider
     */
    private static Provider provider() {
        return new Provider("my-gateway", "my-type", SENTINEL_KEY, "https://gateway.example.com",
                Arrays.asList(new Model("m-id", "m", 1000, 100)));
    }

    /**
     * 构造同时带自定义请求头的 provider。
     *
     * @return provider
     */
    private static Provider providerWithPassthrough() {
        return new Provider("my-gateway", "my-type", SENTINEL_KEY, "https://gateway.example.com",
                Arrays.asList(new Model("m-id", "m", 1000, 100)), null, null, null,
                Collections.singletonMap("x-tenant", "t-1"));
    }

    /**
     * 构造一份填满了各类字段的请求。
     *
     * @return 内核请求
     */
    private static LlmRequest request() {
        List<LlmTool> tools = new ArrayList<LlmTool>();
        tools.add(new LlmTool("read", "读文件", Collections.<String, Object>emptyMap(),
                Collections.<String>emptyList()));
        return LlmRequest.builder("gpt-4o")
                .systemPrompt("system")
                .message(LlmMessage.user("你好"))
                .message(LlmMessage.assistant("在"))
                .tools(tools)
                .toolChoice("auto")
                .temperature(0.2d)
                .maxTokens(1024)
                .cacheBreakpoints(3)
                .minimalOutput()
                .build();
    }

    /**
     * 记录全部事件的监听器。
     *
     * @author zcd
     */
    private static final class RecordingListener implements LlmStreamListener {

        /** 是否收到 onOpen。 */
        private boolean opened;

        /** 文本增量。 */
        private final StringBuilder text = new StringBuilder();

        /** 思考增量。 */
        private final StringBuilder thinking = new StringBuilder();

        /** 工具调用快照。 */
        private final List<LlmToolCall> toolCalls = new ArrayList<LlmToolCall>();

        /** 完成结果。 */
        private LlmResponse completed;

        /** 取消标志。 */
        private boolean cancelled;

        /** 失败原因。 */
        private Throwable error;

        @Override
        public void onOpen() {
            opened = true;
        }

        @Override
        public void onText(String delta) {
            text.append(delta);
        }

        @Override
        public void onThinking(String delta) {
            thinking.append(delta);
        }

        @Override
        public void onToolCall(LlmToolCall toolCall) {
            toolCalls.add(toolCall);
        }

        @Override
        public void onComplete(LlmResponse response) {
            completed = response;
        }

        @Override
        public void onCancelled() {
            cancelled = true;
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }
    }

    /**
     * 传输层消息的一个小断言入口：保证翻译后的消息保留角色与内容。
     * <p>
     * 单独立一个用例而不是散在别处，是因为「消息翻译」是插件最容易踩的一处：
     * 角色写错不会报错，只会让模型表现变差。
     */
    @Test
    void chat_should_translate_tool_result_messages() {
        PluginLlmClientAdapter adapter = adapter((request, listener) -> {
            captured = request;
            listener.onComplete(LlmTransportResponse.text("ok"));
        });
        LlmRequest withTool = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.tool("call_1", "read", "内容"))
                .build();

        adapter.chat(withTool);

        LlmTransportMessage message = captured.getMessages().get(0);
        assertEquals(LlmTransportMessage.ROLE_TOOL, message.getRole());
        assertEquals("call_1", message.getToolCallId());
        assertEquals("read", message.getToolName());
        assertTrue(message.isToolResult());
    }
}
