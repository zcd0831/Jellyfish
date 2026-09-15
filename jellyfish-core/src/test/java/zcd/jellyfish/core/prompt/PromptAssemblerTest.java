package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assembler = new PromptAssembler(agentManager, toolCatalog, runtimeConfig, extensions);
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
        return new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry())).createDefault();
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
