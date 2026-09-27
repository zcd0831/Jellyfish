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
import zcd.jellyfish.api.extension.SessionCompactionSnapshot;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
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
                new ToolResultAger(runtimeConfig));
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
        SessionManager manager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()));
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
        when(toolCatalog.tools()).thenReturn(
                Collections.singletonList(new LlmTool("read", "读文件", null, null)));
        SessionManager manager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()));
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
        when(toolCatalog.tools()).thenReturn(Collections.<LlmTool>emptyList());
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        Session session = newSession();

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128000, 0));

        // Then
        assertNull(request.getMaxTokens());
        assertNull(request.getSystemPrompt());
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
        return new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()));
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

        // Then：只剩边界之后的那一条；被压掉的原文不再进请求
        assertEquals(1, request.getMessages().size());
        assertEquals("三", request.getMessages().get(0).getContent());
    }

    @Test
    void buildRequest_should_putSummaryInSystemPrompt_afterPluginContributions() {
        // Given
        SessionManager sessions = newSessionManager();
        Session session = sessions.createDefault();
        String sessionId = session.getSessionId();
        sessions.appendMessage(sessionId, LlmMessage.user("一"), null);
        sessions.applyCompaction(sessionId, "早前对话的摘要", session.getMessages().get(0).getMessageId(), 0);
        when(agentManager.systemPromptOf(null)).thenReturn("agent 提示词");
        contribute("plugin-a", 0, "插件贡献");
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128_000, 4096));

        // Then：agent → 插件 → 历史摘要，摘要必须在最后
        String systemPrompt = request.getSystemPrompt();
        assertTrue(systemPrompt.contains("agent 提示词"), systemPrompt);
        assertTrue(systemPrompt.contains("插件贡献"), systemPrompt);
        assertTrue(systemPrompt.contains("早前对话的摘要"), systemPrompt);
        assertTrue(systemPrompt.indexOf("插件贡献") < systemPrompt.indexOf("早前对话的摘要"), systemPrompt);
        assertTrue(systemPrompt.contains("更早的 1 条消息已不在上下文中"), systemPrompt);
    }

    @Test
    void buildRequest_should_ignoreCompaction_when_boundaryMessageMissing() {
        // Given：恢复一份「手工改过、边界指向不存在的消息」的会话——
        // 这是该状态唯一可能的来源，正常路径下 SessionManager 会拦住它
        ExtensionRegistry registry = new ExtensionRegistry(new TypeRegistry());
        SessionManager sessions = new SessionManager(agentManager, events, registry);
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
                SessionCompactionSnapshot.of("孤儿摘要", "ghost", 3L));
    }

    /**
     * 构造解析后的模型。
     *
     * @param contextLength  上下文窗口长度
     * @param maxOutputTokens 最大输出 token 数
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(int contextLength, int maxOutputTokens) {
        Provider provider = new Provider("openai", "openai", null, null, null);
        Model model = new Model("gpt-4o", "gpt-4o", contextLength, maxOutputTokens);
        return new ResolvedModel(provider, model);
    }
}
