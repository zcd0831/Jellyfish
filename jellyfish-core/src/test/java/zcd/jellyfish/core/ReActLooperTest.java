package zcd.jellyfish.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.action.ActionFailureReason;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.extension.TurnBeforeRequest;
import zcd.jellyfish.api.extension.TurnDirective;
import zcd.jellyfish.core.action.ActionDispatcher;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.CacheBreakWatcher;
import zcd.jellyfish.core.prompt.ContextUsage;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.prompt.ToolCatalog;
import zcd.jellyfish.core.prompt.ToolFilter;
import zcd.jellyfish.core.prompt.ToolResultAger;
import zcd.jellyfish.core.runtime.RunContext;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.llm.LlmStreamListener;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.support.CancellationTokenSource;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** 会话压缩器：本轮只验证它被按用量询问过，因此用 mock。 */
    @Mock
    private ConversationCompactor conversationCompactor;

    /** 工具输出中间件：用真实实现，用例里的输出都很小，不会真的落盘。 */
    private ToolOutputLimiter outputLimiter;

    /** 委派作用域持有者：用真实实现，嵌套回合依赖它。 */
    private RunContextHolder runContexts;

    /** 动作队列：用真实实现，用例直接往里投递插件动作。 */
    private ActionQueue actionQueue;

    /** 动作执行体：用真实实现，它才是被验证的那一层接线。 */
    private ActionDispatcher actionDispatcher;

    /** 工具目录：用真实实现，重建工具清单的动作要落到它头上。 */
    private ToolCatalog toolCatalog;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        toolCatalog = new ToolCatalog(extensions);
        promptAssembler = new PromptAssembler(agentManager, toolCatalog, runtimeConfig, extensions,
                new ToolResultAger(runtimeConfig, extensions), new CacheBreakWatcher(events));
        outputLimiter = new ToolOutputLimiter(runtimeConfig, new ToolOutputStore(runtimeConfig));
        runContexts = new RunContextHolder();
        actionQueue = new ActionQueue();
        actionDispatcher = new ActionDispatcher(actionQueue, sessionManager, conversationCompactor, toolCatalog);
        // 这两个桩是共享前置条件：个别用例（会话不存在 / 提前取消）走不到这两步，用 lenient 避免误报
        lenient().when(modelManager.resolveDefault()).thenReturn(resolvedModel());
        lenient().when(modelManager.getClient(any(ResolvedModel.class))).thenReturn(client);
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void chat_should_keepSystemPromptStable_acrossTurns_when_turnContextChanges() {
        // Given：待办状态在两次用户回合之间变了，但它走的是回合上下文而不是贡献块
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        AtomicInteger revision = new AtomicInteger();
        extensions.contribute("todo", TurnContextRequest.class, null,
                request -> TurnContext.of("[待办] 第 " + revision.get() + " 版"), RegisterOptions.DEFAULT);
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "第一步", new RecordingListener()).await();
        revision.incrementAndGet();
        newLooper().chat(session.getSessionId(), "第二步", new RecordingListener()).await();

        // Then：system prompt 逐字节相同——这就是 P2 要拿到的不变量。
        // 待办若还在 system prompt 里，这里两段会不同，而代价是整个请求（连同全部历史）作废
        ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client, times(2)).chatStream(requests.capture(), any(LlmStreamListener.class));
        assertEquals("你是助手", requests.getAllValues().get(0).getSystemPrompt());
        assertEquals(requests.getAllValues().get(0).getSystemPrompt(),
                requests.getAllValues().get(1).getSystemPrompt());
        // 而待办确实变了，并且确实随消息走（append-only）
        assertTrue(session.getMessages().get(0).getMessage().getContent().contains("第 0 版"));
        assertTrue(session.getMessages().get(2).getMessage().getContent().contains("第 1 版"));
    }

    @Test
    void chat_should_prependTurnContext_toUserMessage() {
        // Given：插件把即时状态交给回合上下文，而不是塞进 system prompt
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.contribute("todo", TurnContextRequest.class, null,
                request -> TurnContext.of("[待办]\n- [ ] 写文档"), RegisterOptions.DEFAULT);
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "继续", new RecordingListener()).await();

        // Then：上下文随用户消息落盘——append-only，因此只影响本轮新产生的 token；
        // 放进 system prompt 则会让「待办变了一次」作废整个请求
        assertEquals("[待办]\n- [ ] 写文档\n\n继续",
                session.getMessages().get(0).getMessage().getContent());
    }

    @Test
    void chat_should_notAlterUserMessage_when_noTurnContextPlugin() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then：引入这个扩展点不该让没有这类插件的会话多出一个换行
        assertEquals("你好", session.getMessages().get(0).getMessage().getContent());
    }

    @Test
    void chat_should_passNestedFlag_toTurnContext() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        List<Boolean> nestedFlags = new ArrayList<>();
        extensions.contribute("probe", TurnContextRequest.class, null, request -> {
            nestedFlags.add(Boolean.valueOf(request.isNested()));
            return TurnContext.empty();
        }, RegisterOptions.DEFAULT);
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then：主会话必须是 false——子代理与主会话的措辞可能要区别对待
        assertEquals(Collections.singletonList(Boolean.FALSE), nestedFlags);
    }

    @Test
    void chat_should_keepToolPairing_when_turnContextInjected() {
        // Given：注入上下文之后，工具调用与其结果的配对不能受影响
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        extensions.contribute("todo", TurnContextRequest.class, null,
                request -> TurnContext.of("[待办]\n- [ ] 写文档"), RegisterOptions.DEFAULT);
        registerTool("read", request -> new ToolCallResult("read", "文件内容"));
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("读完了"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertEquals("读完了", result.getContent());
        assertEquals(4, session.size());
        assertTrue(session.getMessages().get(1).getMessage().hasToolCalls());
        assertEquals("call_1", session.getMessages().get(2).getMessage().getToolCallId());
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
    void chat_should_askCompactorWithContextUsage_beforeEachRound() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text("最终答复"));
        Session session = sessionManager.createDefault();
        ArgumentCaptor<ContextUsage> usage = ArgumentCaptor.forClass(ContextUsage.class);

        // When
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then：压缩的触发判据必须来自组装请求的那一次计算，否则两处判据会漂移
        verify(conversationCompactor).autoCompactIfNeeded(eq(session.getSessionId()), usage.capture());
        // 共享桩里的模型没配上下文窗口 → 比例无从判断（预算 0），但用量本身仍如实带出
        assertEquals(0, usage.getValue().getBudgetTokens());
        assertTrue(usage.getValue().getUsedTokens() >= 0);
    }

    @Test
    void chat_should_persist_thinking_when_model_returns_it() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(new LlmResponse("最终答复", "先想一下", null, new LlmUsage(1, 2, 3), "stop"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then：思考过程与消息同时落库，否则“展开思考”在历史里无内容可展
        assertEquals("先想一下", session.getMessages().get(1).getThinking());
        assertEquals("最终答复", session.getMessages().get(1).getMessage().getContent());
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
    void chat_should_deliver_tool_metadata_to_listener_and_session() {
        // Given：工具报告「命令跑了但失败」——元数据是界面渲染失败标记的唯一依据
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1));
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_COMPLETED);
        registerTool("shell", request -> new ToolCallResult("shell", "cwd: /x · exit: 1", metadata));
        stubResponses(toolCallResponse("call_1", "shell"), LlmResponse.text("失败了"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        newLooper().chat(session.getSessionId(), "跑一下", listener).await();

        // Then：两条路都要拿到（监听器管当前帧，会话管重投影与重启后的历史）
        assertEquals(1, listener.toolMetadata.size());
        assertEquals(Integer.valueOf(1), listener.toolMetadata.get(0).get(ToolMetadata.KEY_EXIT_CODE));
        Map<String, Object> persisted = session.getMessages().get(2).getMetadata();
        assertEquals(Integer.valueOf(1), persisted.get(ToolMetadata.KEY_EXIT_CODE));
        assertEquals("cwd: /x · exit: 1", session.getMessages().get(2).getMessage().getContent());
    }

    @Test
    void chat_should_passEmptyMetadata_when_toolGivesNone() {
        // Given：普通工具没有元数据，监听器不该收到 null（否则每个实现都要判空）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> new ToolCallResult("read", "文件内容"));
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("读完了"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        newLooper().chat(session.getSessionId(), "读文件", listener).await();

        // Then
        assertTrue(listener.toolMetadata.get(0).isEmpty());
        assertTrue(session.getMessages().get(2).getMetadata().isEmpty());
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
    void chat_should_pass_cancellation_token_and_sink_to_tool() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        List<CancellationToken> tokens = new ArrayList<>();
        List<ToolOutputSink> sinks = new ArrayList<>();
        registerTool("read", request -> {
            tokens.add(request.getCancellationToken());
            sinks.add(request.getOutputSink());
            return new ToolCallResult("read", "ok");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：两者都必须真的送到工具手里，而不是只存在于接口上
        assertEquals(1, tokens.size());
        assertNotNull(tokens.get(0));
        assertFalse(tokens.get(0).isCancelled());
        assertEquals(1, sinks.size());
        assertNotSame(ToolOutputSink.NOOP, sinks.get(0));
    }

    @Test
    void chat_should_keepCapturedOutput_when_tool_fails() {
        // Given：工具已经产出了一部分输出才失败
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> {
            request.getOutputSink().write("已捕获的输出");
            throw new IllegalStateException("磁盘坏了");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("换一种方式"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：现场不能被「工具执行失败」这一句话盖掉
        String fedBack = session.getMessages().get(2).getMessage().getContent();
        assertTrue(fedBack.startsWith("已捕获的输出"), fedBack);
        assertTrue(fedBack.contains("工具执行失败"), fedBack);
    }

    @Test
    void chat_should_teeToolOutputToListener() {
        // Given：工具一边跑一边把输出写进 sink
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> {
            request.getOutputSink().write("第一段\n");
            request.getOutputSink().write("第二段");
            return new ToolCallResult("read", "ok");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("好"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        newLooper().chat(session.getSessionId(), "读文件", listener).await();

        // Then：片段按原样、按顺序到达外壳，并且带上工具调用标识与工具名
        assertEquals(Arrays.asList("第一段\n", "第二段"), listener.toolOutput);
        assertEquals("call_1|read", listener.toolOutputMeta.get(0));
    }

    @Test
    void chat_should_notFailToolCall_when_listenerThrowsOnOutput() {
        // Given：外壳的实时渲染抛错（终端断了、缓冲满了）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> {
            request.getOutputSink().write("输出");
            return new ToolCallResult("read", "ok");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When：显示通道可丢，它的故障不得把一次工具调用升级成失败
        ReActResult result = newLooper()
                .chat(session.getSessionId(), "读文件", new ThrowingOutputListener()).await();

        // Then
        assertFalse(result.isTruncated());
        assertEquals("好", result.getContent());
    }

    @Test
    void chat_should_fire_cancel_callback_registered_by_running_tool() throws InterruptedException {
        // Given：工具阻塞在自持的闸门上，并在开始后注册取消回调
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        registerTool("read", request -> {
            request.getCancellationToken().onCancel(cancelled::countDown);
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new ToolCallResult("read", "ok");
        });
        stubResponses(toolCallResponse("call_1", "read"));
        Session session = sessionManager.createDefault();
        ReActTurn turn = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener());

        // When：工具正在跑时取消回合（真实形态是渲染线程按 Esc）
        assertTrue(started.await(5, TimeUnit.SECONDS));
        turn.cancel();

        // Then：回调必须被触发——这是「Esc 能打断在跑的命令」的全部依据
        assertTrue(cancelled.await(5, TimeUnit.SECONDS), "取消回调未被执行");
        release.countDown();
        turn.await();
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
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(1, 0, 20000, null, null, null));
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        registerTool("read", request -> new ToolCallResult("read", "ok"));
        stubResponses(toolCallResponse("call_1", "read"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：提示要指向顶层回合自己那个配置键——调 react.maxRounds 才是有效动作
        assertTrue(result.isTruncated());
        assertEquals(1, result.getRounds());
        assertNotNull(result.getContent());
        assertTrue(result.getContent().contains("react.maxRounds"));
    }

    @Test
    void chat_should_retryOnce_then_return_when_model_givesEmptyReply() {
        // Given：第一次一个字都没回，第二次正常答复
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text(""), LlmResponse.text("补上了"));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "你好", listener).await();

        // Then：重试拿到答复后照常收敛，且不给用户任何提示（这次没有例外）
        assertEquals("补上了", result.getContent());
        assertNull(result.getNotice());
        assertEquals(2, result.getRounds());
        // 空的那一次不落库：一条空的 assistant 消息对界面与模型都没有信息量，
        // 落进历史反而会跟着之后的每一次请求发出去
        assertEquals(2, session.size());
        assertEquals("补上了", session.getMessages().get(1).getMessage().getContent());
    }

    @Test
    void chat_should_convergeWithNotice_when_model_keepsGivingEmptyReply() {
        // Given：每次都空回复——重试用尽后必须如实收敛，而不是让用户对着空屏幕以为还在跑
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text(""));
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "你好", listener).await();

        // Then
        assertNull(result.getContent());
        assertNotNull(result.getNotice());
        assertTrue(result.getNotice().contains("没有给出任何回复"), result.getNotice());
        assertFalse(result.isCancelled());
        // 1 次首答 + 1 次重试，都空
        assertEquals(2, result.getRounds());
        // 两次都不落库：会话里只有用户输入那条
        assertEquals(1, session.size());
        assertEquals(Collections.singletonList(result), listener.completed);
    }

    @Test
    void chat_should_notice_when_replyTruncatedByOutputLimit() {
        // Given：模型答到一半被输出上限截断——正文看着像答完了，形状与正常收敛完全一样
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(new LlmResponse("半句话", null, null, new LlmUsage(1, 1, 2), "length"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "写篇长文", new RecordingListener()).await();

        // Then：正文照旧交给外壳，另附一句可执行的提示（提示不是正文的一部分）
        assertEquals("半句话", result.getContent());
        assertNotNull(result.getNotice());
        assertTrue(result.getNotice().contains("maxOutputTokens"), result.getNotice());
        assertTrue(result.getNotice().contains("length"), result.getNotice());
        // 它不是「达到最大轮次」那种未收敛，两者必须分得开
        assertFalse(result.isTruncated());
    }

    @Test
    void chat_should_notNotice_when_finishReasonIsNormalStop() {
        // Given：正常答完（stop / 无结束原因都不该被当成截断）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(new LlmResponse("完整的答复", null, null, new LlmUsage(1, 1, 2), "stop"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // Then：宁可少提示，也不要把一次正常回复说成截断
        assertNull(result.getNotice());
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
        // 只算 assistant 那一条真实调用：随之落库的用户输入不是模型调用
        assertEquals(1L, session.getUsage().getLlmCalls());
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
    void chat_should_reportExactlyOnce_when_preTurnStatementFails() {
        // Given：配置读取发生在 runTurn 之前——它抛错时 runTurn 从未进入，因此没有任何 listener 回调
        when(runtimeConfig.getSubAgentSettings()).thenThrow(new IllegalStateException("配置坏了"));
        Session session = sessionManager.createDefault();
        RecordingListener recording = new RecordingListener();

        // When
        ReActTurn turn = newLooper().chat(session.getSessionId(), "你好", recording);

        // Then：恰好一条终态。0 条会让外壳一直等一个永不到来的终态（-cli 连超时都没有），
        // 2 条会破坏「每个回合恰好一条 isTerminal」的契约——两个方向都不能容忍
        assertThrows(JellyfishException.class, turn::await);
        assertEquals(1, recording.errors.size(), "前置失败必须补一条终态，且只能是一条");
        assertTrue(recording.completed.isEmpty());
        assertEquals(0, recording.cancelledCount);
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

    @Test
    @Timeout(30)
    void chat_should_appendSyntheticResults_when_cancelledBeforeRemainingToolsRun()
            throws InterruptedException {
        // Given：模型一次返回两个工具调用，第一个工具执行期间回合被取消（真实形态是渲染线程按 Esc）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registerTool("read", request -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new ToolCallResult("read", "第一个跑完了");
        });
        stubResponses(twoToolCallResponse(), LlmResponse.text("结束"));
        Session session = sessionManager.createDefault();

        // When
        ReActTurn turn = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener());
        assertTrue(running.await(5, TimeUnit.SECONDS));
        turn.cancel();
        release.countDown();
        ReActResult result = turn.await();

        // Then：回合被取消，但两条工具调用都有结果落盘——悬空的 assistant(toolCalls)
        // 会让厂商以 400 拒掉之后每一次请求，而该会话在边界下一次推进前都好不了
        assertTrue(result.isCancelled());
        assertEquals(4, session.size());
        assertEquals("call_1", session.getMessages().get(2).getMessage().getToolCallId());
        assertEquals("第一个跑完了", session.getMessages().get(2).getMessage().getContent());
        LlmMessage synthetic = session.getMessages().get(3).getMessage();
        assertEquals(LlmMessage.ROLE_TOOL, synthetic.getRole());
        assertEquals("call_2", synthetic.getToolCallId());
        assertEquals("已取消：该工具调用未执行", synthetic.getContent());
        // 「缺省 = 正常跑完」：不标 CANCELLED 的话，界面会把一条根本没跑过的工具渲染成正常完成
        assertTrue(ToolMetadata.failed(session.getMessages().get(3).getMetadata()));
    }

    @Test
    void chat_should_expose_run_scope_to_tools() {
        // Given：委派方只能在工具处理器里读到作用域（顶层回合边界只有循环器知道）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        List<RunContext> scopes = new ArrayList<>();
        registerTool("read", request -> {
            scopes.add(runContexts.current());
            return new ToolCallResult("read", "ok");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：作用域已开启、深度为 0、上限来自配置
        assertEquals(1, scopes.size());
        assertNotNull(scopes.get(0));
        assertEquals(0, scopes.get(0).getDepth());
        assertEquals(SubAgentSettings.DEFAULT_MAX_DEPTH, scopes.get(0).getMaxDepth());
    }

    @Test
    @Timeout(30)
    void runNested_should_run_inline_on_calling_thread() {
        // Given：父回合的工具里发起一次嵌套回合
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        ReActLooper looper = newLooper();
        Session child = sessionManager.createEphemeral("parent-1", "scout", null, null);
        List<String> nestedThreads = new ArrayList<String>();
        List<String> parentThreads = new ArrayList<String>();
        ReActListener nestedListener = new ReActListener() {
            @Override
            public void onComplete(ReActResult result) {
                nestedThreads.add(Thread.currentThread().getName());
            }
        };
        registerTool("delegate", request -> {
            parentThreads.add(Thread.currentThread().getName());
            ReActResult nested = looper.runNested(child, "子任务", nestedListener,
                    request.getCancellationToken(), 4, ToolFilter.none());
            parentThreads.add(Thread.currentThread().getName());
            return new ToolCallResult("delegate", nested.getContent());
        });
        stubResponses(toolCallResponse("call_1", "delegate"), LlmResponse.text("子代理答复"),
                LlmResponse.text("父回合结束"));
        Session parent = sessionManager.createDefault();

        // When
        ReActResult result = looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：`runNested` 是一个同步执行体——在哪个线程调用它，就在哪个线程跑完。
        // 本用例直接把它当工具体调用，因此与父回合同线程；生产路径由调度器在 agent-run 线程上调用它
        // （见 AgentRuntimeTest#spawn_should_execute_body_on_agent_run_thread）。
        // 若它自己改成提交 react 池，工具会堵着唯一那条线程等一个永远排不上的任务，本用例的 @Timeout 就是那个哨兵。
        assertEquals("父回合结束", result.getContent());
        assertEquals(1, nestedThreads.size());
        assertEquals(2, parentThreads.size());
        assertEquals(parentThreads.get(0), nestedThreads.get(0));
        assertEquals(parentThreads.get(0), parentThreads.get(1));
        // 子代理的对话落在子会话里，父会话只拿到一行工具结果
        assertEquals(2, child.size());
        assertEquals("子任务", child.getMessages().get(0).getMessage().getContent());
        assertEquals(4, parent.size());
        assertEquals("子代理答复", parent.getMessages().get(2).getMessage().getContent());
    }

    @Test
    @Timeout(30)
    void runNested_should_raise_depth_only_inside_nested_turn() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        ReActLooper looper = newLooper();
        Session child = sessionManager.createEphemeral("parent-1", "scout", null, null);
        List<Integer> depthInside = new ArrayList<Integer>();
        List<Integer> depthAfter = new ArrayList<Integer>();
        ReActListener nestedListener = new ReActListener() {
            @Override
            public void onComplete(ReActResult result) {
                depthInside.add(runContexts.current().getDepth());
            }
        };
        registerTool("delegate", request -> {
            looper.runNested(child, "子任务", nestedListener, request.getCancellationToken(), 4,
                    ToolFilter.none());
            depthAfter.add(runContexts.current().getDepth());
            return new ToolCallResult("delegate", "done");
        });
        stubResponses(toolCallResponse("call_1", "delegate"), LlmResponse.text("子代理答复"),
                LlmResponse.text("父回合结束"));
        Session parent = sessionManager.createDefault();

        // When
        looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：回合内深度为 1，返回后回到 0（leave 必须在 finally 里）
        assertEquals(Collections.singletonList(1), depthInside);
        assertEquals(Collections.singletonList(0), depthAfter);
    }

    @Test
    @Timeout(30)
    void runNested_should_use_given_max_rounds_instead_of_react_settings() {
        // Given：主会话允许 5 轮，但嵌套回合只给 1 轮，且子代理每轮都请求工具
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(5, 0, 20000, null, null, null));
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        ReActLooper looper = newLooper();
        Session child = sessionManager.createEphemeral("parent-1", "scout", null, null);
        List<ReActResult> nestedResults = new ArrayList<ReActResult>();
        registerTool("read", request -> new ToolCallResult("read", "ok"));
        registerTool("delegate", request -> {
            nestedResults.add(looper.runNested(child, "子任务", null, request.getCancellationToken(), 1, ToolFilter.none()));
            return new ToolCallResult("delegate", "done");
        });
        stubResponses(toolCallResponse("call_1", "delegate"), toolCallResponse("call_2", "read"),
                LlmResponse.text("父回合结束"));
        Session parent = sessionManager.createDefault();

        // When
        looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：子代理的轮数上限不跟随 react.maxRounds
        assertEquals(1, nestedResults.size());
        assertTrue(nestedResults.get(0).isTruncated());
        assertEquals(1, nestedResults.get(0).getRounds());
        // 提示也要按语境走：指向 react.maxRounds 的话，调用方照着调是白费力气
        assertTrue(nestedResults.get(0).getContent().contains("subAgent.maxRounds"));
        assertFalse(nestedResults.get(0).getContent().contains("react.maxRounds"));
    }

    @Test
    @Timeout(30)
    void runNested_should_be_cancelled_when_parent_token_already_cancelled() {
        // Given：父令牌已取消（模拟「按 Esc」与「发起委派」撞在一起）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        ReActLooper looper = newLooper();
        Session child = sessionManager.createEphemeral("parent-1", "scout", null, null);
        CancellationTokenSource cancelled = new CancellationTokenSource();
        cancelled.cancel();
        List<ReActResult> nestedResults = new ArrayList<ReActResult>();
        registerTool("delegate", request -> {
            nestedResults.add(looper.runNested(child, "子任务", null, cancelled, 4, ToolFilter.none()));
            return new ToolCallResult("delegate", "done");
        });
        stubResponses(toolCallResponse("call_1", "delegate"), LlmResponse.text("父回合结束"));
        Session parent = sessionManager.createDefault();

        // When
        looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：嵌套回合在第一个检查点就退出，一次模型调用都没发起（响应序列里没有为它预留的那一段）
        assertEquals(1, nestedResults.size());
        assertTrue(nestedResults.get(0).isCancelled());
        assertEquals(1, child.size());
    }

    @Test
    void runNested_should_throw_when_no_active_scope() {
        // Given：没有任何顶层回合在跑，因此没有作用域
        Session child = sessionManager.createEphemeral("parent-1", "scout", null, null);

        // When / Then：不在回合作用域内的嵌套回合不受深度与预算约束，因此必须当场报错
        assertThrows(JellyfishException.class,
                () -> newLooper().runNested(child, "子任务", null, null, 4, ToolFilter.none()));
    }

    @Test
    void chat_should_inject_steer_message_after_tool_batch() {
        // Given：插件在工具处理器里投递一条 STEER——「刚跑完一批工具」正是插件最可能知道
        // 「还有一步没做」的时刻，也是它唯一稳定可用的投递位置
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        Session session = sessionManager.createDefault();
        List<ActionHandle> handles = new ArrayList<ActionHandle>();
        registerTool("read", request -> {
            handles.add(actionQueue.submit("plugin-a", PluginAction.sendUserMessage(
                    session.getSessionId(), "顺便把日志也改了", DeliverAs.STEER)));
            return new ToolCallResult("read", "文件内容");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("好了"));

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：消息排在工具结果之后、下一次模型调用之前，因此下一轮请求就带着它
        assertEquals(ActionStatus.DONE, handles.get(0).getStatus());
        assertEquals(5, session.size());
        assertEquals(LlmMessage.ROLE_USER, session.getMessages().get(3).getMessage().getRole());
        assertEquals("顺便把日志也改了", session.getMessages().get(3).getMessage().getContent());
        assertEquals("好了", result.getContent());
        assertEquals(2, result.getRounds());
    }

    @Test
    void chat_should_continue_same_turn_when_follow_up_pending() {
        // Given：插件要求「干完这件接着干那件」。它不能让回合收敛，而是让**本回合**多跑一轮——
        // 不开新回合是刻意的：内核起的回合外壳不知道，在途状态、取消入口与并发写历史会一起变坏
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        Session session = sessionManager.createDefault();
        List<ActionHandle> handles = new ArrayList<ActionHandle>();
        registerTool("read", request -> {
            handles.add(actionQueue.submit("plugin-a", PluginAction.sendUserMessage(
                    session.getSessionId(), "接着把测试补了", DeliverAs.FOLLOW_UP)));
            return new ToolCallResult("read", "文件内容");
        });
        // 第 2 轮模型已经不再要求工具（本来就要收敛），第 3 轮才是收敛点之后那一轮
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("读完了"),
                LlmResponse.text("测试也补了"));

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then
        assertEquals(ActionStatus.DONE, handles.get(0).getStatus());
        assertEquals("测试也补了", result.getContent());
        assertEquals(3, result.getRounds());
    }

    @Test
    void chat_should_fail_follow_up_when_turn_has_no_round_left() {
        // Given：轮次上限为 2，收敛发生在最后一轮——插入的消息已经没有渠道发给模型
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(2, 0, 20000, null, null, null));
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        Session session = sessionManager.createDefault();
        List<ActionHandle> handles = new ArrayList<ActionHandle>();
        registerTool("read", request -> {
            handles.add(actionQueue.submit("plugin-a", PluginAction.sendUserMessage(
                    session.getSessionId(), "没轮次了", DeliverAs.FOLLOW_UP)));
            return new ToolCallResult("read", "文件内容");
        });
        stubResponses(toolCallResponse("call_1", "read"), LlmResponse.text("读完了"));

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：回合本身照常收敛，失败只在动作句柄上——投了也发不出去的消息不进历史，
        // 否则它会变成一条永远没人回答的提问，并与用户的下一次输入连成两条 user 消息
        assertEquals(ActionStatus.FAILED, handles.get(0).getStatus());
        assertEquals(ActionFailureReason.NO_REMAINING_ROUNDS, handles.get(0).getFailureReason());
        assertTrue(handles.get(0).getResult().contains("轮次已用尽"), handles.get(0).getResult());
        assertEquals("读完了", result.getContent());
        // user + assistant(tool_use) + tool + assistant：插件的消息没进历史
        assertEquals(4, session.size());
    }

    @Test
    void chat_should_fail_queued_action_when_turn_ends_before_drain() {
        // Given：轮次上限为 1，工具批次之后就直接收敛——FOLLOW_UP 等不到任何排空点
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(1, 0, 20000, null, null, null));
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        Session session = sessionManager.createDefault();
        List<ActionHandle> handles = new ArrayList<ActionHandle>();
        registerTool("read", request -> {
            handles.add(actionQueue.submit("plugin-a", PluginAction.sendUserMessage(
                    session.getSessionId(), "来不及了", DeliverAs.FOLLOW_UP)));
            return new ToolCallResult("read", "文件内容");
        });
        stubResponses(toolCallResponse("call_1", "read"));

        // When
        newLooper().chat(session.getSessionId(), "读文件", new RecordingListener()).await();

        // Then：回合结束时残留的动作被标失败，而不是镀成一个永不兑现的 QUEUED
        assertEquals(ActionStatus.FAILED, handles.get(0).getStatus());
        assertEquals(ActionFailureReason.TURN_ENDED_UNREACHED, handles.get(0).getFailureReason());
        assertTrue(handles.get(0).getResult().contains("回合已结束"), handles.get(0).getResult());
    }

    @Test
    void chat_should_fail_actions_submitted_after_turn_ended() {
        // Given：回合已经跑完（窗口已关闭）
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text("你好"));
        Session session = sessionManager.createDefault();
        newLooper().chat(session.getSessionId(), "你好", new RecordingListener()).await();

        // When：插件此刻才想说话（典型来源：它从事件回调里投递，而事件是异步投递的）
        ActionHandle handle = actionQueue.submit("plugin-a",
                PluginAction.sendUserMessage(session.getSessionId(), "太晚了", DeliverAs.STEER));

        // Then：明确失败，而不是静默丢掉
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.NO_TURN_IN_FLIGHT, handle.getFailureReason());
        assertTrue(handle.getResult().contains("没有在途回合"), handle.getResult());
    }

    /**
     * 构造被测对象。
     *
     * @return ReAct 循环器
     */
    private ReActLooper newLooper() {
        return new ReActLooper(sessionManager, modelManager,
                new ToolExecutor(permissionManager, extensions, events, outputLimiter, runContexts),
                events, promptAssembler, runtimeConfig, conversationCompactor, runContexts,
                new SessionModelResolver(modelManager, agentManager), extensions, actionDispatcher, executor);
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

    @Test
    void chat_should_block_turn_and_skip_model_when_plugin_cancels() {
        // Given：回合开始前钩子拦下——一句都不发给模型
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.contribute("guard", TurnBeforeRequest.class, null,
                request -> TurnDirective.cancel("工作区有未提交的改动"), RegisterOptions.DEFAULT);
        Session session = sessionManager.createDefault();
        RecordingListener listener = new RecordingListener();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "提交吧", listener).await();

        // Then：它不是失败也不是取消，而是一档独立的终态（-cli 据此给独立退出码）
        assertTrue(result.isBlocked());
        assertFalse(result.isCancelled());
        assertFalse(result.isTruncated());
        assertEquals("工作区有未提交的改动", result.getBlockedReason());
        // 会话一条消息都没多：拦下发生在追加用户消息之前，历史因此保持干净
        assertEquals(0, session.size());
        assertEquals(Collections.singletonList("工作区有未提交的改动"), listener.blocked);
        // 模型一次都没被调用——这是「拦截在花钱之前」的直接断言
        verifyNoInteractions(client);
    }

    @Test
    void chat_should_replace_input_when_plugin_asks() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.contribute("ctx", TurnBeforeRequest.class, null,
                request -> TurnDirective.replaceInput("附上分支：main\n" + request.getInput()),
                RegisterOptions.DEFAULT);
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        newLooper().chat(session.getSessionId(), "继续", new RecordingListener()).await();

        // Then：落进会话的是替换后的文本（与提示词注入不同，它只影响这一次输入）
        assertEquals("附上分支：main\n继续", session.getMessages().get(0).getMessage().getContent());
    }

    @Test
    void chat_should_notAlterInput_when_noTurnHookRegistered() {
        // Given：0 handler 是兼容性承诺
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "原文", new RecordingListener()).await();

        // Then
        assertEquals("原文", session.getMessages().get(0).getMessage().getContent());
        assertFalse(result.isBlocked());
    }

    @Test
    void chat_should_ignoreInputReplacement_for_nested_turn() {
        // Given：嵌套回合的输入是模型写出来的任务描述，插件改不动它
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        extensions.contribute("ctx", TurnBeforeRequest.class, null,
                request -> TurnDirective.replaceInput("被改写的任务描述"), RegisterOptions.DEFAULT);
        ReActLooper looper = newLooper();
        Session parent = sessionManager.createDefault();
        Session child = sessionManager.createDefault();
        registerTool("delegate", request -> {
            ReActResult nested = looper.runNested(child, "原始任务描述", new RecordingListener(),
                    request.getCancellationToken(), 2, ToolFilter.none());
            return new ToolCallResult("delegate", nested.getContent());
        });
        stubResponses(toolCallResponse("call_1", "delegate"), LlmResponse.text("子代理答复"),
                LlmResponse.text("父回合结束"));

        // When
        looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：嵌套回合用的是模型给出的任务描述；顶层那一次仍然按指令替换了
        assertEquals("原始任务描述", child.getMessages().get(0).getMessage().getContent());
        assertEquals("被改写的任务描述", parent.getMessages().get(0).getMessage().getContent());
    }

    @Test
    void chat_should_report_depth_and_nested_flag_to_turn_hook() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
        List<String> seen = new ArrayList<String>();
        extensions.contribute("probe", TurnBeforeRequest.class, null, request -> {
            seen.add(request.isNested() + "/" + request.getDepth());
            return TurnDirective.proceed();
        }, RegisterOptions.DEFAULT);
        ReActLooper looper = newLooper();
        Session parent = sessionManager.createDefault();
        Session child = sessionManager.createDefault();
        registerTool("delegate", request -> {
            looper.runNested(child, "子任务", new RecordingListener(), request.getCancellationToken(), 2,
                    ToolFilter.none());
            return new ToolCallResult("delegate", "done");
        });
        stubResponses(toolCallResponse("call_1", "delegate"), LlmResponse.text("子代理答复"),
                LlmResponse.text("父回合结束"));

        // When
        looper.chat(parent.getSessionId(), "委派一下", new RecordingListener()).await();

        // Then：顶层深度 0；嵌套回合已进入作用域，因此深度为 1 且 nested 为真
        assertEquals(Arrays.asList("false/0", "true/1"), seen);
    }

    @Test
    void chat_should_treat_turn_hook_failure_as_proceed() {
        // Given：拦截点坏掉时宁可放行，也不要让会话彻底不能用
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.contribute("broken", TurnBeforeRequest.class, null, request -> {
            throw new IllegalStateException("插件崩了");
        }, RegisterOptions.DEFAULT);
        stubResponses(LlmResponse.text("好"));
        Session session = sessionManager.createDefault();

        // When
        ReActResult result = newLooper().chat(session.getSessionId(), "原文", new RecordingListener()).await();

        // Then
        assertEquals("好", result.getContent());
        assertEquals("原文", session.getMessages().get(0).getMessage().getContent());
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
     * 构造一个「一次返回两个工具调用」的模型响应。
     * <p>
     * 两个而不是一个：要验证的正是「后面那个从未执行」时会不会被补上结果。
     *
     * @return 响应
     */
    private static LlmResponse twoToolCallResponse() {
        List<LlmToolCall> toolCalls = new ArrayList<LlmToolCall>();
        toolCalls.add(new LlmToolCall(0, "call_1", "read", "{\"path\":\"a.txt\"}"));
        toolCalls.add(new LlmToolCall(1, "call_2", "read", "{\"path\":\"b.txt\"}"));
        return new LlmResponse(null, null, toolCalls, null, "tool_calls");
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
    private static class RecordingListener implements ReActListener {

        /** 完成结果。 */
        private final List<ReActResult> completed = new ArrayList<ReActResult>();

        /** 错误。 */
        private final List<Throwable> errors = new ArrayList<Throwable>();

        /** 工具开始名称。 */
        private final List<String> toolStarted = new ArrayList<String>();

        /** 工具结束（名称:成功）记录。 */
        private final List<String> toolCompleted = new ArrayList<String>();

        /** 工具结束时的元数据（按调用顺序）。 */
        private final List<Map<String, Object>> toolMetadata = new ArrayList<Map<String, Object>>();

        /** 工具执行期输出片段。 */
        private final List<String> toolOutput = new ArrayList<String>();

        /** 工具执行期输出携带的工具调用标识与工具名。 */
        private final List<String> toolOutputMeta = new ArrayList<String>();

        /** 取消次数。 */
        private int cancelledCount;

        /** 被拦下的理由（按发生顺序）。 */
        private final List<String> blocked = new ArrayList<String>();

        @Override
        public void onBlocked(String reason) {
            blocked.add(reason);
        }

        @Override
        public void onToolCallStarted(String toolCallId, String toolName) {
            toolStarted.add(toolName);
        }

        @Override
        public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
            toolOutput.add(chunk);
            toolOutputMeta.add(toolCallId + "|" + toolName);
        }

        @Override
        public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                       Map<String, Object> metadata) {
            toolCompleted.add(toolName + ":" + success);
            toolMetadata.add(metadata);
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

    /**
     * 实时输出回调必抛错的监听器，用于验证显示通道的故障不会传递给工具调用。
     *
     * @author zcd
     */
    private static final class ThrowingOutputListener extends RecordingListener {

        @Override
        public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
            throw new IllegalStateException("终端已断开");
        }
    }
}
