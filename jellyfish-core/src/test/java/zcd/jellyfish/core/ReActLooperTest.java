package zcd.jellyfish.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.prompt.ToolCatalog;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.llm.LlmStreamListener;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReActLooper} 的单元测试：验证循环收敛、工具执行、失败回灌、轮次上限与取消。
 * <p>
 * 用真实 {@code SessionManager} / {@code ExtensionRegistry} / {@code PromptAssembler}，
 * 只 mock 外部协作者（模型、权限、事件、配置）；{@code chatStream} 用同步触发回调解的桩，
 * 回合仍经专用单线程执行器异步推进，由 {@link ReActTurn#await()} 汇合。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ReActLooperTest {

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** 权限管理器。 */
    @Mock
    private PermissionManager permissionManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** LLM 客户端。 */
    @Mock
    private LlmClient client;

    /** 专用执行器。 */
    private ExecutorService executor;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 真实提示词组装器。 */
    private PromptAssembler promptAssembler;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions);
        promptAssembler = new PromptAssembler(agentManager, new ToolCatalog(extensions), runtimeConfig);
        // 这两个桩是共享前置条件：个别用例（会话不存在 / 提前取消）走不到这两步，用 lenient 避免误报
        lenient().when(modelManager.resolveDefault()).thenReturn(resolvedModel());
        lenient().when(modelManager.getClient(any(ResolvedModel.class))).thenReturn(client);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void chat_should_return_final_answer_when_no_tool_calls() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text("最终答复"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "你好", listener).await();

        // Then：结果收敛、消息成对落会话、回调齐全
        assertEquals("最终答复", result.getContent());
        assertFalse(result.isTruncated());
        assertFalse(result.isCancelled());
        assertEquals(1, result.getRounds());
        assertEquals(2, session.size());
        assertEquals("你好", session.getMessages().get(0).getMessage().getContent());
        assertEquals("最终答复", session.getMessages().get(1).getMessage().getContent());
        assertEquals(Collections.singletonList(result), listener.completed);
    }

    @Test
    void chat_should_execute_tool_then_return() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> new ToolCallResult("read", "文件内容"));
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("读完了"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", listener).await();

        // Then：assistant(toolCalls) → tool 结果 → assistant 的顺序与配对正确
        assertEquals("读完了", result.getContent());
        assertEquals(2, result.getRounds());
        assertEquals(4, session.size());
        assertTrue(session.getMessages().get(1).getMessage().hasToolCalls());
        assertEquals("call_1", session.getMessages().get(2).getMessage().getToolCallId());
        assertEquals("文件内容", session.getMessages().get(2).getMessage().getContent());
        assertEquals(Collections.singletonList("read"), listener.toolStarted);
        assertEquals(Collections.singletonList("read:true"), listener.toolCompleted);
    }

    @Test
    void chat_should_feed_permission_deny_back_to_model() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.deny("危险操作"));
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("知道了"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertEquals("知道了", result.getContent());
        assertTrue(session.getMessages().get(2).getMessage().getContent().startsWith("权限拒绝"));
    }

    @Test
    void chat_should_feed_unknown_tool_back_to_model() {
        // Given：未注册 read 工具
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("知道了"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertEquals("知道了", result.getContent());
        assertTrue(session.getMessages().get(2).getMessage().getContent().startsWith("未知工具"));
    }

    @Test
    void chat_should_feed_tool_exception_back_and_emit_failed_event() {
        // Given：工具处理器抛异常
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> {
            throw new IllegalStateException("磁盘坏了");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("换一种方式"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertEquals("换一种方式", result.getContent());
        assertTrue(session.getMessages().get(2).getMessage().getContent().startsWith("工具执行失败"));
        ToolCallCompletedEvent completed = publishedEvent(ToolCallCompletedEvent.class);
        assertFalse(completed.isSuccess());
    }

    @Test
    void chat_should_truncate_when_max_rounds_reached() {
        // Given：每轮都请求工具，轮次上限为 1
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(1, 0, 20000));
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> new ToolCallResult("read", "ok"));
        stubResponses(toolCallResponse("call_1", "read"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertTrue(result.isTruncated());
        assertEquals(1, result.getRounds());
        assertNotNull(result.getContent());
    }

    @Test
    void chat_should_accumulate_usage_into_session() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(new LlmResponse("答复", null, null, new LlmUsage(2, 3, 5), "stop"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then
        assertEquals(5L, session.getUsage().getTotalTokens());
        // 追加用户消息（无用量）与 assistant 消息各计一次调用
        assertEquals(2L, session.getUsage().getLlmCalls());
    }

    @Test
    void chat_should_report_error_when_stream_fails() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(client.chatStream(any(LlmRequest.class), any(LlmStreamListener.class))).thenAnswer(invocation -> {
            LlmStreamListener listener = invocation.getArgument(1);
            listener.onError(new IllegalStateException("网络断了"));
            return (LlmStreamHandle) () -> {
            };
        });
        Session session = sessionManager.createDefault();
        RecordingListener recording = new RecordingListener();

        // When
        ReActTurn turn = newLooper().chat(session.getSessionId(), "你好", recording);

        // Then
        assertThrows(JellyfishException.class, turn::await);
        assertEquals(1, recording.errors.size());
    }

    @Test
    void chat_should_throw_when_session_missing() {
        // Given
        RecordingListener recording = new RecordingListener();

        // When
        ReActTurn turn = newLooper().chat("missing", "你好", recording);

        // Then
        assertThrows(JellyfishException.class, turn::await);
        assertEquals(1, recording.errors.size());
    }

    @Test
    void cancel_should_return_cancelled_result() throws InterruptedException {
        // Given：流一直不完成，直到被取消才回调 onCancelled
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        CountDownLatch streamStarted = new CountDownLatch(1);
        when(client.chatStream(any(LlmRequest.class), any(LlmStreamListener.class))).thenAnswer(invocation -> {
            LlmStreamListener listener = invocation.getArgument(1);
            streamStarted.countDown();
            return (LlmStreamHandle) listener::onCancelled;
        });
        Session session = sessionManager.createDefault();
        RecordingListener recording = new RecordingListener();

        // When：等流真正开始后再取消，保证走「掐断进行中的流」这条路径
        ReActTurn turn = newLooper().chat(session.getSessionId(), "你好", recording);
        assertTrue(streamStarted.await(5, TimeUnit.SECONDS));
        turn.cancel();
        ReActResult result = turn.await();

        // Then
        assertTrue(result.isCancelled());
        assertEquals(1, recording.cancelledCount);
        assertTrue(turn.isDone());
    }

    /**
     * 构造被测对象。
     *
     * @return ReAct 循环器
     */
    private ReActLooper newLooper() {
        return new ReActLooper(sessionManager, modelManager, permissionManager, extensions, events,
                promptAssembler, runtimeConfig, executor);
    }

    /**
     * 桩：让每次流式调用按顺序同步回调一个响应。
     *
     * @param responses 响应序列，用完后重复最后一个
     */
    private void stubResponses(LlmResponse... responses) {
        AtomicInteger index = new AtomicInteger();
        when(client.chatStream(any(LlmRequest.class), any(LlmStreamListener.class))).thenAnswer(invocation -> {
            LlmStreamListener listener = invocation.getArgument(1);
            LlmResponse response = responses[Math.min(index.getAndIncrement(), responses.length - 1)];
            listener.onComplete(response);
            return (LlmStreamHandle) () -> {
            };
        });
    }

    /**
     * 注册一个工具处理器。
     *
     * @param name    工具名
     * @param handler 处理器
     */
    private void registerTool(String name, ExtensionHandler<ToolCallRequest, ToolCallResult> handler) {
        extensions.handle("test", ToolCallRequest.class, name, new ToolDescriptor(name, name), handler,
                RegisterOptions.DEFAULT);
    }

    /**
     * 构造一个带工具调用的模型响应。
     *
     * @param callId   调用标识
     * @param toolName 工具名
     * @return 响应
     */
    private static LlmResponse toolCallResponse(String callId, String toolName) {
        LlmToolCall toolCall = new LlmToolCall(0, callId, toolName, "{\"path\":\"a.txt\"}");
        return new LlmResponse(null, null, Collections.singletonList(toolCall), null, "tool_calls");
    }

    /**
     * 构造解析后的模型。
     *
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel() {
        Provider provider = new Provider("openai", "openai", null, null, null);
        Model model = new Model("gpt-4o", "gpt-4o", 0, 0);
        return new ResolvedModel(provider, model);
    }

    /**
     * 取本次测试中广播出去的指定类型事件。
     *
     * @param type 事件类型
     * @param <T>  事件类型
     * @return 事件
     */
    private <T extends JellyfishEvent> T publishedEvent(Class<T> type) {
        ArgumentCaptor<JellyfishEvent> captor = ArgumentCaptor.forClass(JellyfishEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publish(captor.capture());
        for (JellyfishEvent event : captor.getAllValues()) {
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        throw new AssertionError("event not published: " + type.getSimpleName());
    }

    /**
     * 录制型监听器：把回调结果收集到内存，供断言使用。
     *
     * @author zcd
     */
    private static final class RecordingListener implements ReActListener {

        /** 完成结果。 */
        private final List<ReActResult> completed = new ArrayList<ReActResult>();

        /** 错误。 */
        private final List<Throwable> errors = new ArrayList<Throwable>();

        /** 工具开始名称。 */
        private final List<String> toolStarted = new ArrayList<String>();

        /** 工具结束（名称:成功）记录。 */
        private final List<String> toolCompleted = new ArrayList<String>();

        /** 取消次数。 */
        private int cancelledCount;

        @Override
        public void onToolCallStarted(String toolCallId, String toolName) {
            toolStarted.add(toolName);
        }

        @Override
        public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output) {
            toolCompleted.add(toolName + ":" + success);
        }

        @Override
        public void onComplete(ReActResult result) {
            completed.add(result);
        }

        @Override
        public void onCancelled() {
            cancelledCount++;
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }
}
