package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link PromptAssembler} 的单元测试：验证 system prompt 组装、待办注入与请求装配。
 * <p>
 * {@link Session} 用真实实例（经 {@link SessionManager} 创建），只 mock {@link AgentManager}、
 * {@link ToolCatalog} 与 {@link RuntimeConfig} 这类外部协作者。
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

    @Test
    void systemPromptOf_should_return_null_when_agent_and_todos_blank() {
        // Given：无 agent 提示词、无待办
        PromptAssembler assembler = newAssembler();
        Session session = newSession();

        // When / Then
        assertNull(assembler.systemPromptOf(session));
    }

    @Test
    void systemPromptOf_should_append_todo_block_after_agent_prompt() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        PromptAssembler assembler = newAssembler();
        SessionManager manager = new SessionManager(agentManager, events);
        Session session = manager.createDefault();
        manager.addTodo(session.getSessionId(), "写文档");
        manager.addTodo(session.getSessionId(), "跑测试");

        // When
        String prompt = assembler.systemPromptOf(session);

        // Then
        assertTrue(prompt.startsWith("你是助手"));
        assertTrue(prompt.contains("[待办]"));
        assertTrue(prompt.contains("- [ ] 写文档"));
        assertTrue(prompt.contains("- [ ] 跑测试"));
    }

    @Test
    void systemPromptOf_should_render_done_todo_with_checked_mark() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        PromptAssembler assembler = newAssembler();
        SessionManager manager = new SessionManager(agentManager, events);
        Session session = manager.createDefault();
        String todoId = manager.addTodo(session.getSessionId(), "写文档").getId();
        manager.completeTodo(session.getSessionId(), todoId);

        // When
        String prompt = assembler.systemPromptOf(session);

        // Then
        assertTrue(prompt.contains("- [x] 写文档"));
    }

    @Test
    void buildRequest_should_assemble_model_prompt_messages_tools_and_max_tokens() {
        // Given
        when(agentManager.systemPromptOf(null)).thenReturn("你是助手");
        when(toolCatalog.tools()).thenReturn(
                Collections.singletonList(new LlmTool("read", "读文件", null, null)));
        PromptAssembler assembler = newAssembler();
        SessionManager manager = new SessionManager(agentManager, events);
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
        PromptAssembler assembler = newAssembler();
        Session session = newSession();

        // When
        LlmRequest request = assembler.buildRequest(session, resolvedModel(128000, 0));

        // Then
        assertNull(request.getMaxTokens());
        assertNull(request.getSystemPrompt());
    }

    /**
     * 构造被测对象。
     *
     * @return 提示词组装器
     */
    private PromptAssembler newAssembler() {
        return new PromptAssembler(agentManager, toolCatalog, runtimeConfig);
    }

    /**
     * 创建一个空会话。
     *
     * @return 会话运行态
     */
    private Session newSession() {
        return new SessionManager(agentManager, events).createDefault();
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
