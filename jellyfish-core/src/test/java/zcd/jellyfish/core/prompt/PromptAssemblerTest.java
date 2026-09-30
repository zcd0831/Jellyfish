package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.PromptPlacement;
import zcd.jellyfish.api.extension.RequestTuning;
import zcd.jellyfish.api.extension.RequestTuningRequest;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.extension.SessionCompactionSnapshot;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionKind;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ProviderCacheSettings;
import zcd.jellyfish.infra.config.ReactCacheSettings;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.tooloutput.ToolOutputEnvelope;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PromptAssembler} 的单元测试：验证 system prompt 组装、插件上下文注入与请求装配。
 * <p>
 * {@link Session} 用真实实例（经 {@link SessionManager} 创建），只 mock {@link AgentManager}、
 * {@link ToolCatalog} 与 {@link RuntimeConfig} 这类外部协作者；提示词贡献用真实的
 * {@link ExtensionRegistry}，因为要验证的正是「按 order 拼接与失败跳过」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class PromptAssemblerTest {

    /** 同构负载的轮数。 */
    private static final int AGING_TURNS = 10;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口，仅用于构造真实 SessionManager。 */
    @Mock
    private EventPublisher events;

    /** 工具目录。 */
    @Mock
    private ToolCatalog toolCatalog;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 真实同步扩展点策略：提示词贡献的来源。 */
    private ExtensionRegistry extensions;

    /** 被测提示词组装器。 */
    private PromptAssembler assembler;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        // 工具结果老化器在有些用例里不会被走到，用 lenient 预置缺省 React 段，避免严格桩误报
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        assembler = new PromptAssembler(agentManager, toolCatalog, runtimeConfig, extensions,
                new ToolResultAger(runtimeConfig, extensions), new CacheBreakWatcher(events));
    }

    @Test
    void systemPromptOf_should_return_null_when_agent_and_contributions_blank() {
        // Given：无 agent 提示词、无插件贡献
        Session session = newSession();

        // When / Then
        assertNull(assembler.systemPromptOf(session));
    }

    @Test
    void systemPromptOf_should_return_contribution_when_agent_prompt_blank() {
        // Given：只有插件贡献，agent 没配提示词
        contribute("todo", 0, "[待办]\n- [ ] 写文档");

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then
        assertEquals("[待办]\n- [ ] 写文档", prompt);
    }

    @Test
    void systemPromptOf_should_append_contributions_after_agent_prompt_in_order() {
        // Given：两个贡献，order 小的排前面
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        contribute("second", 5, "第二块");
        contribute("first", 1, "第一块");

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then
        assertEquals("你是助手\n\n第一块\n\n第二块", prompt);
    }

    @Test
    void systemPromptOf_should_skip_empty_contribution() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        contribute("empty", 0, null);
        contribute("blank", 1, "   ");

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then
        assertEquals("你是助手", prompt);
    }

    @Test
    void systemPromptOf_should_skip_failing_handler_and_keep_others() {
        // Given：一个坏插件不该让整段提示词拼不出来
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        extensions.contribute("broken", PromptContributionRequest.class, null, request -> {
            throw new JellyfishException("记忆库不可用");
        }, RegisterOptions.DEFAULT);
        contribute("ok", 0, "好插件的内容");

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then
        assertTrue(prompt.contains("你是助手"));
        assertTrue(prompt.contains("好插件的内容"));
    }

    @Test
    void systemPromptOf_should_passSessionIdToContribution() {
        // Given：贡献处理器按 sessionId 找回自己的状态
        SessionManager manager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()), new SessionDefaults());
        Session session = manager.createDefault();
        final String[] seen = new String[1];
        extensions.contribute("recorder", PromptContributionRequest.class, null, request -> {
            seen[0] = request.getSessionId();
            return PromptContribution.empty();
        }, RegisterOptions.DEFAULT);

        // When
        assembler.systemPromptOf(session);

        // Then
        assertEquals(session.getSessionId(), seen[0]);
    }

    @Test
    void buildRequest_should_assemble_model_prompt_messages_tools_and_max_tokens() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        when(toolCatalog.tools(any(Session.class), any(ToolFilter.class))).thenReturn(
                Collections.singletonList(new LlmTool("read", "读文件", null, null)));
        SessionManager manager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()), new SessionDefaults());
        Session session = manager.createDefault();
        manager.appendMessage(session.getSessionId(), LlmMessage.user("你好"), null);
        ResolvedModel resolvedModel = resolvedModel(0, 4096);

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel);

        // Then
        assertEquals("gpt-4o", request.getModel());
        assertEquals("你是助手", request.getSystemPrompt());
        assertEquals(1, request.getMessages().size());
        assertEquals(1, request.getTools().size());
        assertEquals(4096, request.getMaxTokens());
    }

    @Test
    void buildRequest_should_leave_max_tokens_unset_when_model_does_not_declare() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn(null);
        when(toolCatalog.tools(any(Session.class), any(ToolFilter.class))).thenReturn(Collections.<LlmTool>emptyList());
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128000, 0));

        // Then
        assertNull(request.getMaxTokens());
        assertNull(request.getSystemPrompt());
    }

    @Test
    void assemble_should_pass_tool_filter_to_catalog() {
        // Given
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        when(toolCatalog.tools(any(Session.class), any(ToolFilter.class))).thenReturn(Collections.<LlmTool>emptyList());
        ToolFilter filter = ToolFilter.of("read_file"::equals);
        Session session = newSession();

        // When
        assembler.assemble(session, resolvedModel(128000, 0), filter);

        // Then：过滤必须一路传到目录，否则子代理依旧会看到全部工具
        verify(toolCatalog).tools(session, filter);
    }

    @Test
    void assemble_should_use_none_filter_when_not_given() {
        // Given
        when(toolCatalog.tools(any(Session.class), any(ToolFilter.class))).thenReturn(Collections.<LlmTool>emptyList());
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();

        // When
        assembler.assemble(session, resolvedModel(128000, 0));

        // Then：两参重载（主会话路径）必须传全放行，不能把 null 漏下去；
        // 而且必须带上会话标识——工具目录靠它给这个会话冻结一份清单
        verify(toolCatalog).tools(session, ToolFilter.none());
    }

    @Test
    void systemPromptOf_should_orderContributions_byPlacementBeforeOrder() {
        // Given：易变块抢到了最小的 order（本应排最前），但缓存要求它排最后——
        // 它是唯一会在会话内变的块，排在前面会让它的每次变化作废后面的全部内容
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        contributeWithPlacement("volatile", 0, "易变块", PromptPlacement.VOLATILE);
        contributeWithPlacement("session", 5, "会话块", PromptPlacement.SESSION);
        contributeWithPlacement("static", 10, "恒定块", PromptPlacement.STATIC);

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then：按 STATIC → SESSION → VOLATILE，order 只在同一层内生效
        assertEquals("你是助手\n\n恒定块\n\n会话块\n\n易变块", prompt);
    }

    @Test
    void systemPromptOf_should_keepOrderWithinSamePlacement() {
        // Given：同一层内仍然按 order 升序
        contributeWithPlacement("later", 9, "后", PromptPlacement.STATIC);
        contributeWithPlacement("earlier", 1, "先", PromptPlacement.STATIC);

        // When
        String prompt = assembler.systemPromptOf(newSession());

        // Then
        assertEquals("先\n\n后", prompt);
    }

    @Test
    void turnContextOf_should_joinContributions_inOrder() {
        // Given
        contributeTurnContext("second", 5, "第二段");
        contributeTurnContext("first", 1, "第一段");

        // When
        String context = assembler.turnContextOf("s-1", "你好", false);

        // Then
        assertEquals("第一段\n\n第二段", context);
    }

    @Test
    void turnContextOf_should_returnNull_when_nobodyAnswers() {
        // When / Then：没有这类插件时，用户消息不该多出任何东西
        assertNull(assembler.turnContextOf("s-1", "你好", false));
    }

    @Test
    void turnContextOf_should_passSessionInputAndNestedFlag() {
        // Given：插件靠这三项判断「这一轮到底要不要说、怎么说」
        final String[] seenSession = new String[1];
        final String[] seenInput = new String[1];
        final boolean[] seenNested = new boolean[1];
        extensions.contribute("probe", TurnContextRequest.class, null, request -> {
            seenSession[0] = request.getSessionId();
            seenInput[0] = request.getUserInput();
            seenNested[0] = request.isNested();
            return TurnContext.empty();
        }, RegisterOptions.DEFAULT);

        // When
        assembler.turnContextOf("s-9", "实现 P2", true);

        // Then
        assertEquals("s-9", seenSession[0]);
        assertEquals("实现 P2", seenInput[0]);
        assertTrue(seenNested[0]);
    }

    @Test
    void turnContextOf_should_skipFailingHandler_and_keepOthers() {
        // Given：一个坏插件不该让整个回合发不出去
        extensions.contribute("broken", TurnContextRequest.class, null, request -> {
            throw new JellyfishException("记忆库不可用");
        }, RegisterOptions.DEFAULT);
        contributeTurnContext("ok", 0, "好插件的内容");

        // When
        String context = assembler.turnContextOf("s-1", "你好", false);

        // Then
        assertEquals("好插件的内容", context);
    }

    @Test
    void turnContextOf_should_ignoreBlankContribution() {
        // Given
        contributeTurnContext("blank", 0, "   ");

        // When / Then
        assertNull(assembler.turnContextOf("s-1", "你好", false));
    }

    /**
     * 注册一个带分层的提示词贡献处理器。
     *
     * @param owner     来源标识
     * @param order     调用顺序
     * @param text      贡献文本，可为 {@code null}
     * @param placement 稳定性分层
     */
    private void contributeWithPlacement(String owner, int order, String text, PromptPlacement placement) {
        extensions.contribute(owner, PromptContributionRequest.class, null,
                request -> PromptContribution.of(text, placement), RegisterOptions.order(order));
    }

    /**
     * 注册一个回合上下文处理器。
     *
     * @param owner 来源标识
     * @param order 调用顺序
     * @param text  上下文文本，可为 {@code null}
     */
    private void contributeTurnContext(String owner, int order, String text) {
        extensions.contribute(owner, TurnContextRequest.class, null,
                request -> TurnContext.of(text), RegisterOptions.order(order));
    }

    /**
     * 注册一个提示词贡献处理器。
     *
     * @param owner 来源标识
     * @param order 调用顺序
     * @param text  贡献文本，可为 {@code null}
     */
    private void contribute(String owner, int order, String text) {
        extensions.contribute(owner, PromptContributionRequest.class, null,
                request -> PromptContribution.of(text), RegisterOptions.order(order));
    }

    /**
     * 构造一条「带工具调用的 assistant」消息。
     *
     * @param callId 工具调用标识
     * @return assistant 消息
     */
    private static LlmMessage assistantCalling(String callId) {
        return LlmMessage.assistant("正在读取", Collections.singletonList(
                new LlmToolCall(0, callId, "read_file", "{\"path\":\"a.txt\"}")));
    }

    /**
     * 创建一个空会话。
     *
     * @return 会话运行态
     */
    private Session newSession() {
        return newSessionManager().createDefault();
    }

    /**
     * 创建一个会话域服务：需要「建会话 + 改会话」两步时才用它。
     *
     * @return 会话域服务
     */
    private SessionManager newSessionManager() {
        return new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()), new SessionDefaults());
    }

    @Test
    void buildRequest_should_sendOnlyMessagesAfterBoundary_when_compacted() {
        // Given：三条消息，压缩边界落在第二条
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("一"), null);
        sessions.appendMessage(sessionId, LlmMessage.user("二"), null);
        sessions.appendMessage(sessionId, LlmMessage.user("三"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(1).getMessageId(), 0);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：被压掉的原文不再进请求，只剩边界之后的那一条；摘要并进它（而不是回到 system prompt）
        assertEquals(1, request.getMessages().size());
        assertNull(request.getSystemPrompt());
        String content = request.getMessages().get(0).getContent();
        assertTrue(content.startsWith("[历史摘要] 更早的 2 条消息已不在上下文中"), content);
        assertTrue(content.contains("早前对话的摘要"), content);
        assertTrue(content.endsWith("\n\n三"), content);
    }

    @Test
    void buildRequest_should_skipLeadingOrphanToolResult_when_boundarySplitsToolGroup() {
        // Given：边界正好落在 assistant(toolCalls) 上，于是切出来的序列以孤儿的工具结果开头——
        // 这种边界只可能来自旧版本写下的、或手工改过的会话文件
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, assistantCalling("call-1"), null);
        sessions.appendMessage(sessionId, LlmMessage.tool("call-1", "read_file", "A 的内容"), null);
        sessions.appendMessage(sessionId, LlmMessage.user("接着来"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：孤儿工具结果被跳过，序列不以未配对的 tool 消息开头
        assertEquals(1, request.getMessages().size());
        assertEquals(LlmMessage.ROLE_USER, request.getMessages().get(0).getRole());
    }

    @Test
    void buildRequest_should_dropTrailingDanglingToolCalls_when_resultsNeverLanded() {
        // Given：会话以一条「一条结果都没落盘」的工具调用结尾（进程崩在落 assistant 与落结果之间）
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("读一下 A"), null);
        sessions.appendMessage(sessionId, assistantCalling("call-1"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：悬空的工具调用被丢弃；留着它会让厂商以 400 拒掉之后每一次请求
        assertEquals(1, request.getMessages().size());
        assertEquals(LlmMessage.ROLE_USER, request.getMessages().get(0).getRole());
    }

    @Test
    void buildRequest_should_putSummaryInMessages_notSystemPrompt() {
        // Given
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("一"), null);
        sessions.appendMessage(sessionId, LlmMessage.user("二"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        contribute("plugin-a", 0, "插件贡献");
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：system prompt 只剩 agent 与插件贡献。摘要曾经拼在它末尾，也是它让 system prompt
        // 随压缩变化；挪到消息区之后那条「逐字节恒定」的不变量才成立
        assertEquals("agent 提示词\n\n插件贡献", request.getSystemPrompt());
        // 摘要在消息区，且与紧随其后的 user 消息合并——两条连续的 user 会被 Anthropic 拒掉
        assertEquals(1, request.getMessages().size());
        assertEquals("[历史摘要] 更早的 1 条消息已不在上下文中。摘要如下：\n早前对话的摘要\n\n二",
                request.getMessages().get(0).getContent());
    }

    @Test
    void systemPromptOf_should_stayByteIdentical_acrossCompactions() {
        // Given：一个既会压缩、也会继续往下聊的会话
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("一"), null);
        sessions.appendMessage(sessionId, LlmMessage.user("二"), null);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        contribute("plugin-a", 0, "插件贡献");

        // When / Then：摘要与边界怎么变，system prompt 都逐字节不动。它是缓存前缀的第 0 个
        // token，它一变后面全部内容（连同整个历史）都要按未命中价重发
        String expected = "agent 提示词\n\n插件贡献";
        assertEquals(expected, assembler.systemPromptOf(session));
        sessions.applyCompaction(sessionId, "摘要 v1", session.getMessages().get(0).getMessageId(), 0);
        assertEquals(expected, assembler.systemPromptOf(session));
        sessions.appendMessage(sessionId, LlmMessage.user("三"), null);
        sessions.applyCompaction(sessionId, "摘要 v2", session.getMessages().get(1).getMessageId(), 3);
        assertEquals(expected, assembler.systemPromptOf(session));
    }

    @Test
    void buildRequest_should_keepSummaryStandalone_when_nextHistoryMessageIsAssistant() {
        // Given：边界之后紧接着一条 assistant(toolCalls) 而不是 user——合并就无从谈起
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("做点事"), null);
        sessions.appendMessage(sessionId, assistantCalling("call-1"), null);
        sessions.appendMessage(sessionId, LlmMessage.tool("call-1", "read_file", "A 的内容"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：摘要单独成一条 user 消息，历史原样跟在后面，全程没有连续同角色
        assertEquals(3, request.getMessages().size());
        assertEquals(LlmMessage.ROLE_USER, request.getMessages().get(0).getRole());
        assertTrue(request.getMessages().get(0).getContent().startsWith("[历史摘要]"),
                request.getMessages().get(0).getContent());
        assertEquals(LlmMessage.ROLE_ASSISTANT, request.getMessages().get(1).getRole());
        assertEquals(LlmMessage.ROLE_TOOL, request.getMessages().get(2).getRole());
    }

    @Test
    void buildRequest_should_ignoreCompaction_when_boundaryMessageMissing() {
        // Given：恢复一份「手工改过、边界指向不存在的消息」的会话——
        // 这是该状态唯一可能的来源，正常路径下 SessionManager 会拦住它
        ExtensionRegistry registry = new ExtensionRegistry(new TypeRegistry());
        SessionManager sessions = new SessionManager(agentManager, events, registry, new SessionDefaults());
        registry.contribute("restorer", SessionRestoreRequest.class, null,
                request -> SessionRestoreResult.of(Collections.singletonList(orphanSnapshot())),
                RegisterOptions.DEFAULT);
        sessions.restore();
        Session session = sessions.require("s-1");
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：按未压缩处理，既不截消息也不注入那段无人认领的摘要
        assertEquals(1, request.getMessages().size());
        assertEquals("一", request.getMessages().get(0).getContent());
        assertNull(request.getSystemPrompt());
    }

    @Test
    void assemble_should_reportUsage_matchingWhatWillBeSent() {
        // Given：一条历史 + 一段 system prompt
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("你好"), null);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        PromptAssembly assembly = assembler.assemble(session, resolvedModel(128_000, 4096));

        // Then：用量 = system prompt + 实际发出的历史；分母是扣掉输出与预留之后的预算
        ContextUsage usage = assembly.getUsage();
        int expectedUsed = TokenEstimator.estimate("agent 提示词")
                + TokenEstimator.estimateMessages(assembly.getRequest().getMessages());
        assertEquals(expectedUsed, usage.getUsedTokens());
        assertEquals(128_000 - 4096 - ReactSettings.DEFAULT_CONTEXT_RESERVE_TOKENS,
                usage.getBudgetTokens());
        assertTrue(usage.getUsedTokens() > 0);
    }

    @Test
    void assemble_should_flagTruncation_when_historyOverBudget() {
        // Given：窗口小到装不下两条历史
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("第一条很长的历史内容"), null);
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("第二条很长的历史内容"), null);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When：预算设得极小（上下文窗口 8、输出 0）
        PromptAssembly assembly = assembler.assemble(session, resolvedModel(8, 0));

        // Then：已裁剪这个事实必须传出来——它就是「该压缩了」的报警信号
        assertTrue(assembly.getUsage().isTruncated());
    }

    @Test
    void assemble_should_reportUnknownUsage_when_contextLengthMissing() {
        // Given：模型没配上下文窗口（这条路径不需要读 react 配置，因此不桩它）
        Session session = newSession();
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");

        // When
        PromptAssembly assembly = assembler.assemble(session, resolvedModel(0, 0));

        // Then：比例无从判断（分母为 0），但请求照发
        assertEquals(0, assembly.getUsage().getBudgetTokens());
        assertFalse(assembly.getUsage().exceeds(80));
    }

    @Test
    void buildRequest_should_keepSummary_when_historyGetsCropped() {
        // Given：一条已经被压掉的开头 + 一长串历史，窗口小到必须裁剪
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("很久以前的那一句"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        for (int index = 0; index < 30; index++) {
            sessions.appendMessage(sessionId, LlmMessage.user("很长的历史内容 " + index), null);
        }
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        PromptAssembly assembly = assembler.assemble(session, resolvedModel(60, 0));

        // Then：裁剪确实发生了
        assertTrue(assembly.getUsage().isTruncated());
        // 而摘要还在：它是被裁掉那一段的精华，让 ContextWindow.crop 有机会丢掉它等于白压一次。
        // 它拼在裁剪之后而不是作为历史的一部分参与裁剪，正是为了这个
        assertTrue(containsSummary(assembly.getRequest().getMessages()), "摘要不能被裁剪丢掉");
    }

    /**
     * 判断实际发出的消息里是否还带着那段摘要。
     *
     * @param messages 实际发出的消息
     * @return 带着返回 {@code true}
     */
    private static boolean containsSummary(List<LlmMessage> messages) {
        for (LlmMessage message : messages) {
            String content = message.getContent();
            if (content != null && content.contains("早前对话的摘要")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aging_should_breakPrefixAtMostOnce_whereLegacyBreaksEveryTurn() {
        // Given：先跑一遍「从不老化」的参照，拿到这段负载的用量增长曲线，再把水位取成
        // 「第一轮用量」与「最后一轮用量」的中点百分比。不写死数字，因此以后调整负载
        // （轮数、信封大小、窗口）不会让这个用例悄悄变成一句空话
        AgingRun reference = runAging(100);
        int watermarkPercent = (reference.percentAt(0) + reference.percentAt(AGING_TURNS - 1)) / 2;
        assertTrue(watermarkPercent > reference.percentAt(0),
                "水位必须高于第一轮用量，否则老化会在第 0 轮就发生，用例观察不到那次断裂");
        assertTrue(watermarkPercent < reference.percentAt(AGING_TURNS - 1),
                "水位必须低于最后一轮用量，否则它永远不会被跨过");

        // When
        int legacy = runAging(0).getBreaks();
        int watermark = runAging(watermarkPercent).getBreaks();

        // Then：不老化时本应是纯追加——每一步都是上一步的真前缀。这条同时给了前两条一个基准
        assertEquals(0, reference.getBreaks(), "不老化时不该有任何断裂");

        // 旧口径下「本轮新老化的那条」恰好落在上一轮已经发过、且刚被缓存的位置，于是每轮都断。
        // 这就是 R2，也是命中率上不去的头号原因
        assertTrue(legacy >= 5, "旧口径本就该每轮都断，实际 " + legacy + " 次");

        // 而水位口径把一个压缩周期内的断裂压到恰好一次。窗口远大于这 10 轮的总量，
        // 系统 prompt 恒定、不发生压缩也不会裁剪，因此能改写前缀的只剩下老化本身——
        // 这一条同时也证明了「老化确实发生过」
        assertEquals(1, watermark, "水位口径应当恰好断一次（且必须断过），实际 " + watermark + " 次");
    }

    /**
     * 用指定的老化口径跑一段同构负载。
     * <p>
     * 每次都重建组装器与会话：老化边界是按会话记住的，复用会让结果依赖执行顺序。
     *
     * @param agingPercent 老化触发水位线，{@code 0} 表示旧口径（按距尾部条数）
     * @return 本次实验的结果
     */
    private AgingRun runAging(int agingPercent) {
        configureAging(2, agingPercent);
        PromptAssembler fresh = new PromptAssembler(agentManager, toolCatalog, runtimeConfig, extensions,
                new ToolResultAger(runtimeConfig, extensions), new CacheBreakWatcher(events));
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        List<LlmMessage> previous = null;
        int[] usedTokens = new int[AGING_TURNS];
        int breaks = 0;
        int budgetTokens = 0;
        for (int turn = 0; turn < AGING_TURNS; turn++) {
            sessions.appendMessage(sessionId, LlmMessage.user("第 " + turn + " 轮提问"), null);
            sessions.appendMessage(sessionId, assistantCalling("call-" + turn), null);
            sessions.appendMessage(sessionId, LlmMessage.tool("call-" + turn, "read_file",
                    envelopeOf("第 " + turn + " 轮的读取结果")), null);
            PromptAssembly assembly = fresh.assemble(session, resolvedModel(4000, 100));
            List<LlmMessage> current = assembly.getRequest().getMessages();
            usedTokens[turn] = assembly.getUsage().getUsedTokens();
            budgetTokens = assembly.getUsage().getBudgetTokens();
            if (previous != null && !isPrefixOf(previous, current)) {
                breaks++;
            }
            previous = current;
        }
        return new AgingRun(breaks, usedTokens, budgetTokens);
    }

    /**
     * 一次老化实验的结果。
     */
    private static final class AgingRun {

        /** 相邻两轮之间前缀被改写的次数。 */
        private final int breaks;

        /** 每轮组装出的上下文用量。 */
        private final int[] usedTokens;

        /** 可用 token 预算，各轮相同。 */
        private final int budgetTokens;

        /**
         * 构造实验结果。
         *
         * @param breaks       前缀断裂次数
         * @param usedTokens   每轮用量
         * @param budgetTokens 可用预算
         */
        AgingRun(int breaks, int[] usedTokens, int budgetTokens) {
            this.breaks = breaks;
            this.usedTokens = usedTokens;
            this.budgetTokens = budgetTokens;
        }

        /**
         * 获取前缀断裂次数。
         *
         * @return 次数
         */
        int getBreaks() {
            return breaks;
        }

        /**
         * 取某一轮的用量占预算的百分比（向下取整）。
         *
         * @param turn 轮次下标
         * @return 百分比
         */
        int percentAt(int turn) {
            return budgetTokens <= 0 ? 0 : usedTokens[turn] * 100 / budgetTokens;
        }
    }

    /**
     * 判断 {@code previous} 是否逐字节等于 {@code current} 的前缀。
     * <p>
     * 逐字段比较而不是用 {@code equals}：要断言的正是「厂商看到的那串字节一模一样」，
     * 因此把角色、正文、工具调用都拍成字符串再比，失败时也能直接看出差在哪。
     *
     * @param previous 上一轮实际发出的消息
     * @param current  本轮实际发出的消息
     * @return 是前缀返回 {@code true}
     */
    private static boolean isPrefixOf(List<LlmMessage> previous, List<LlmMessage> current) {
        if (previous.size() > current.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            if (!wireOf(previous.get(index)).equals(wireOf(current.get(index)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一条消息拍成「厂商会看到的样子」。
     *
     * @param message 消息
     * @return 可比较、可读的字符串
     */
    private static String wireOf(LlmMessage message) {
        StringBuilder text = new StringBuilder(message.getRole()).append('|')
                .append(message.getContent()).append('|')
                .append(message.getToolCallId()).append('|')
                .append(message.getName());
        for (LlmToolCall call : message.getToolCalls()) {
            text.append('|').append(call.getId()).append(':').append(call.getName())
                    .append(':').append(call.getArguments());
        }
        return text.toString();
    }

    @Test
    void buildFork_should_reuseParentPrefix_andAppendInstructionOnly() {
        // Given：一段带工具调用的历史 + 一个真实工具
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("读一下 A"), null);
        sessions.appendMessage(sessionId, assistantCalling("call-1"), null);
        sessions.appendMessage(sessionId, LlmMessage.tool("call-1", "read_file", envelopeOf("A 的内容")), null);
        sessions.appendMessage(sessionId, LlmMessage.user("继续"), null);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        givenTools();
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When：保留末尾 1 条，其余都压
        LlmRequest parent = assembler.buildRequest(session, resolvedModel(128_000, 4096));
        LlmRequest fork = assembler.buildFork(session, resolvedModel(128_000, 4096), 0, 1, "把历史压成摘要");

        // Then：整条前缀逐字节相同。这正是压缩调用从「整段按未命中价重发」变成「整段命中」的原因
        assertEquals(parent.getSystemPrompt(), fork.getSystemPrompt());
        assertEquals(parent.getMaxTokens(), fork.getMaxTokens());
        int keep = parent.getMessages().size() - 1;
        for (int index = 0; index < keep; index++) {
            assertEquals(wireOf(parent.getMessages().get(index)), wireOf(fork.getMessages().get(index)),
                    "第 " + index + " 条与父请求不同，公共前缀会在那里断开");
        }
        // 而末尾只多出那条指令
        assertEquals(keep + 1, fork.getMessages().size());
        assertEquals("把历史压成摘要", fork.getMessages().get(keep).getContent());
    }

    @Test
    void buildFork_should_keepTools_andDisableToolCalls() {
        // Given
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("一"), null);
        givenTools();
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest fork = assembler.buildFork(session, resolvedModel(128_000, 4096), 0, 0, "写摘要");

        // Then：工具必须原样带上——它在多数厂商的模板里排在 messages 之前，省掉它等于把整条前缀
        // 从工具那一段起全部作废；带上之后又必须把工具调用关掉，否则模型很可能去调工具而不是写摘要
        assertEquals(1, fork.getTools().size());
        assertEquals("read_file", fork.getTools().get(0).getName());
        assertEquals("none", fork.getToolChoice());
    }

    @Test
    void buildFork_should_carryPreviousSummary_forFree() {
        // Given：会话已经压过一次，摘要作为合成消息挂在消息区开头（P2b）
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("一"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        sessions.appendMessage(sessionId, LlmMessage.user("二"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest fork = assembler.buildFork(session, resolvedModel(128_000, 4096), 1, 0, "把历史压成摘要");

        // Then：旧摘要不需要再单独拼一遍——它本来就在父请求的前缀里。P2b 把摘要挪出 system prompt、
        // 挪进消息区，在这里顺带把「滚动摘要」的旧摘要传递一并免了
        assertTrue(fork.getMessages().get(0).getContent().contains("早前对话的摘要"),
                fork.getMessages().get(0).getContent());
    }

    @Test
    void buildFork_should_returnNull_when_compactionRangeIsAlreadyCroppedAway() {
        // Given：窗口小到机械裁剪从最旧侧把待压范围整段吞掉
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        for (int index = 0; index < 5; index++) {
            sessions.appendMessage(sessionId, LlmMessage.user("第 " + index + " 条的正文"), null);
        }
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When：待压范围从头开始，但父请求的第一条历史已经不在下标 0
        LlmRequest fork = assembler.buildFork(session, resolvedModel(40, 0), 0, 0, "写摘要");

        // Then：fork 出来的前缀会缺一段内容，摘要将毫无依据，因此宁可回退到旧路径
        assertNull(fork);
    }

    @Test
    void buildRequest_should_carryCacheKey_whenProviderEnablesIt() {
        // Given：provider 打开了缓存路由键
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("你好"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session,
                resolvedModel(128_000, 4096, new ProviderCacheSettings(true, 0)));

        // Then：取会话标识，且同一会话必须一直用同一个值——否则请求会被散到不同机器上各建一份缓存
        assertEquals(session.getSessionId(), request.getCacheKey());
    }

    @Test
    void buildRequest_should_omitCacheKey_byDefault() {
        // Given：provider 没配这一项（也是缺省）
        Session session = newSession();
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：不下发——老模型/老端点收到不认识的字段可能直接报错
        assertNull(request.getCacheKey());
    }

    @Test
    void buildFork_should_carryCacheKey_fromParent() {
        // Given：provider 打开了缓存路由键
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("一"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        ResolvedModel model = resolvedModel(128_000, 4096, new ProviderCacheSettings(true, 0));

        // When
        LlmRequest fork = assembler.buildFork(session, model, 0, 0, "写摘要");

        // Then：路由键必须跟着父请求——fork 的全部意义就是命中父请求建立的缓存，
        // 而路由键决定它落到哪台机器上
        assertEquals(assembler.buildRequest(session, model).getCacheKey(), fork.getCacheKey());
    }

    @Test
    void buildRequest_should_applyTuning_fromPlugin() {
        // Given：插件表态三个缓存旋钮
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();
        extensions.contribute("tuner", RequestTuningRequest.class, null,
                request -> new RequestTuning("自定义键", "1h", Integer.valueOf(1)),
                RegisterOptions.DEFAULT);

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then
        assertEquals("自定义键", request.getCacheKey());
        assertEquals("1h", request.getCacheRetention());
        assertEquals(Integer.valueOf(1), request.getCacheBreakpoints());
    }

    @Test
    void buildRequest_should_mergeTuning_fieldWiseByOrder() {
        // Given：两个插件各表一部分态度，注册顺序即 order
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();
        extensions.contribute("先", RequestTuningRequest.class, null,
                request -> new RequestTuning("键甲", null, null), RegisterOptions.DEFAULT);
        extensions.contribute("后", RequestTuningRequest.class, null,
                request -> new RequestTuning("键乙", "24h", Integer.valueOf(2)), RegisterOptions.DEFAULT);

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：逐字段取 order 最小的非空值，而不是拼接或取极值
        assertEquals("键甲", request.getCacheKey());
        assertEquals("24h", request.getCacheRetention());
        assertEquals(Integer.valueOf(2), request.getCacheBreakpoints());
    }

    @Test
    void buildRequest_should_clampBreakpoints_fromPlugin() {
        // Given：插件写出荒谬的断点数
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();
        extensions.contribute("越界", RequestTuningRequest.class, null,
                request -> new RequestTuning(null, null, Integer.valueOf(99)), RegisterOptions.DEFAULT);

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：钳到上限——插件写出荒谬的值不该让整个请求发不出去
        assertEquals(Integer.valueOf(RequestTuning.MAX_CACHE_BREAKPOINTS), request.getCacheBreakpoints());
    }

    @Test
    void buildRequest_should_clampNegativeBreakpoints_toZero() {
        // Given：0 是「关闭该厂商的缓存」这个有意义的取值，负数则只可能是笔误
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();
        extensions.contribute("负", RequestTuningRequest.class, null,
                request -> new RequestTuning(null, null, Integer.valueOf(-5)), RegisterOptions.DEFAULT);

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then
        assertEquals(Integer.valueOf(0), request.getCacheBreakpoints());
    }

    @Test
    void buildRequest_should_fallBackToDefaults_whenTuningHandlerFails() {
        // Given：调优是锦上添花，一个坏插件不该让整轮对话发不出去
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("你好"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.contribute("坏", RequestTuningRequest.class, null, request -> {
            throw new JellyfishException("调优器挂了");
        }, RegisterOptions.DEFAULT);

        // When
        LlmRequest request = assembler.buildRequest(session,
                resolvedModel(128_000, 4096, new ProviderCacheSettings(true, 0)));

        // Then：退回内核缺省——路由键仍按 provider 配置取会话标识，其余不表态
        assertEquals(session.getSessionId(), request.getCacheKey());
        assertNull(request.getCacheRetention());
        assertNull(request.getCacheBreakpoints());
    }

    @Test
    void buildRequest_should_passProviderAndCounts_toTuningHandler() {
        // Given：插件要靠 provider 类型与模型判断该厂商认哪些字段
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();
        final RequestTuningRequest[] seen = new RequestTuningRequest[1];
        extensions.contribute("记", RequestTuningRequest.class, null, request -> {
            seen[0] = request;
            return RequestTuning.empty();
        }, RegisterOptions.DEFAULT);

        // When
        assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then
        assertNotNull(seen[0]);
        assertEquals("openai", seen[0].getProviderType());
        assertEquals("gpt-4o", seen[0].getModelId());
        assertEquals(session.getSessionId(), seen[0].getSessionId());
    }

    @Test
    void buildFork_should_carryAllCacheKnobs_fromParent() {
        // Given：插件让请求带上了全部三个缓存旋钮
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("一"), null);
        extensions.contribute("调优", RequestTuningRequest.class, null,
                request -> new RequestTuning("键", "1h", Integer.valueOf(1)), RegisterOptions.DEFAULT);
        ResolvedModel model = resolvedModel(128_000, 4096);

        // When
        LlmRequest parent = assembler.buildRequest(session, model);
        LlmRequest fork = assembler.buildFork(session, model, 0, 0, "写摘要");

        // Then：三个旋钮都得跟着走——它们决定缓存落在哪、能不能写、写多久，
        // 丢掉任何一个，这次 fork 可能就白花了
        assertEquals(parent.getCacheKey(), fork.getCacheKey());
        assertEquals(parent.getCacheRetention(), fork.getCacheRetention());
        assertEquals(parent.getCacheBreakpoints(), fork.getCacheBreakpoints());
    }

    @Test
    void buildKeepAlive_should_declareMinimalOutput_insteadOfPinningOneToken() {
        // Given：保活要的不是内容，而是「碰一下缓存、把 TTL 续上」
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("一"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest keepAlive = assembler.buildKeepAlive(session, resolvedModel(128_000, 4096), "保活");

        // Then：只声明意图。最省写法各家不同（Anthropic 是 max_tokens: 0，OpenAI 系下限是 1），
        // 由客户端换算——内核写死一个数字就等于把它自己绑在某一家的协议上
        assertTrue(keepAlive.isMinimalOutput());
        assertNull(keepAlive.getMaxTokens());
    }

    @Test
    void buildFork_should_keepParentMaxTokens_andNotBeMinimalOutput() {
        // Given：fork 要的是真的摘要正文，不是「不需要输出」
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        sessions.appendMessage(session.getSessionId(), LlmMessage.user("一"), null);
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        ResolvedModel model = resolvedModel(128_000, 4096);

        // When
        LlmRequest parent = assembler.buildRequest(session, model);
        LlmRequest fork = assembler.buildFork(session, model, 0, 0, "写摘要");

        // Then
        assertFalse(fork.isMinimalOutput());
        assertEquals(parent.getMaxTokens(), fork.getMaxTokens());
    }

    /**
     * 给工具目录桩上一个工具。
     */
    private void givenTools() {
        when(toolCatalog.tools(any(Session.class), any())).thenReturn(Collections.singletonList(
                new LlmTool("read_file", "读文件", null, null)));
    }

    /**
     * 构造一份示例截断信封。
     *
     * @param preview 预览正文
     * @return 信封文本
     */
    private static String envelopeOf(String preview) {
        return ToolOutputEnvelope.text("read_file", 1000, 1, "/tmp/spill.txt", "hint", preview).render();
    }

    /**
     * 把老化口径写进配置桩，并把上下文预留置为 {@code 0} 以便预算可预测。
     *
     * @param keepRecent   保留完整内容的最近消息条数
     * @param agingPercent 老化触发水位线，{@code 0} 表示旧口径
     */
    private void configureAging(int keepRecent, int agingPercent) {
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(
                null, 0, null, null, null, null,
                new ToolOutputSettings(null, null, null, null, keepRecent),
                new ReactCacheSettings(agingPercent)));
    }

    /**
     * 构造一份压缩边界指向不存在消息的会话快照。
     *
     * @return 会话快照
     */
    private static SessionSnapshot orphanSnapshot() {
        SessionMessageSnapshot message = SessionMessageSnapshot.of("m-1", 1L, LlmMessage.ROLE_USER, "一",
                null, null, null, null);
        return new SessionSnapshot("s-1", 1L, 2L, null, null, null, null, PermissionMode.NORMAL,
                Collections.singletonList(message), null,
                SessionCompactionSnapshot.of("孤儿摘要", "ghost", 3L),
                SessionKind.NORMAL, null, null, null);
    }

    /**
     * 构造解析后的模型。
     *
     * @param contextLength  上下文窗口长度
     * @param maxOutputTokens 最大输出 token 数
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(int contextLength, int maxOutputTokens) {
        return resolvedModel(contextLength, maxOutputTokens, new ProviderCacheSettings());
    }

    /**
     * 构造带缓存设置的解析后模型。
     *
     * @param contextLength  上下文窗口长度
     * @param maxOutputTokens 最大输出 token 数
     * @param cache          缓存治理段
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(int contextLength, int maxOutputTokens,
                                               ProviderCacheSettings cache) {
        Provider provider = new Provider("openai", "openai", null, null, null, cache);
        Model model = new Model("gpt-4o", "gpt-4o", contextLength, maxOutputTokens);
        return new ResolvedModel(provider, model);
    }
}
