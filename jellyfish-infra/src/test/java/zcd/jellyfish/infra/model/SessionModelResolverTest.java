package zcd.jellyfish.infra.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@link SessionModelResolver} 的单元测试：验证「会话显式 → agent 偏好 → 全局默认」这条三级回落。
 * <p>
 * 这条规则必须只有一处实现：{@code ReActLooper} 与 {@code ConversationCompactor} 都走它，
 * 否则摘要会按另一个模型的窗口裁剪、花另一个模型的额度。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SessionModelResolverTest {

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口，会话创建时会广播事件。 */
    @Mock
    private EventPublisher events;

    /** 真实会话域服务：Session 的构造器是包级可见的，从这里拿运行态最自然。 */
    private SessionManager sessions;

    /** 被测对象。 */
    private SessionModelResolver resolver;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()),
                new SessionDefaults());
        resolver = new SessionModelResolver(modelManager, agentManager);
    }

    @Test
    void resolve_should_prefer_explicit_session_model() {
        // Given：用户在会话里显式选过模型，它就是唯一答案
        ResolvedModel expected = resolvedModel();
        when(modelManager.resolve("openai", "gpt-4o")).thenReturn(expected);
        Session session = sessions.create("scout", "openai", "gpt-4o", null);

        // When / Then
        assertSame(expected, resolver.resolve(session));
    }

    @Test
    void resolve_should_fall_back_to_agent_model_when_session_has_none() {
        // Given：会话没选过模型，但 agent 配了偏好模型
        ResolvedModel expected = resolvedModel();
        when(agentManager.find("scout")).thenReturn(definitionWithModel("openai/gpt-4o-mini"));
        when(modelManager.resolveReference("openai/gpt-4o-mini")).thenReturn(expected);
        Session session = sessions.create("scout", null, null, null);

        // When / Then
        assertSame(expected, resolver.resolve(session));
    }

    @Test
    void resolve_should_fall_back_to_global_default_when_agent_has_no_model() {
        // Given：agent 存在但没配 model
        ResolvedModel expected = resolvedModel();
        when(agentManager.find("scout")).thenReturn(definitionWithModel(null));
        when(modelManager.resolveDefault()).thenReturn(expected);
        Session session = sessions.create("scout", null, null, null);

        // When / Then
        assertSame(expected, resolver.resolve(session));
    }

    @Test
    void resolve_should_fall_back_to_global_default_when_agent_unknown() {
        // Given：会话未绑定 agent 是合法状态（权限 fail-open），模型解析也不该因此失败
        ResolvedModel expected = resolvedModel();
        when(agentManager.find(null)).thenReturn(null);
        when(modelManager.resolveDefault()).thenReturn(expected);
        Session session = sessions.create(null, null, null, null);

        // When / Then
        assertSame(expected, resolver.resolve(session));
    }

    @Test
    void resolve_should_propagate_when_agent_model_unresolvable() {
        // Given：agent 的 model 写错了——静默降级到全局默认会让「我明明配了」变成一个查不出的疑问
        when(agentManager.find("scout")).thenReturn(definitionWithModel("openai/ghost"));
        when(modelManager.resolveReference("openai/ghost"))
                .thenThrow(new JellyfishException("model not found: openai/ghost"));
        Session session = sessions.create("scout", null, null, null);

        // When / Then
        assertThrows(JellyfishException.class, () -> resolver.resolve(session));
    }

    @Test
    void resolve_should_reject_null_session() {
        // When / Then
        assertThrows(NullPointerException.class, () -> resolver.resolve(null));
    }

    @Test
    void resolveByAgentOrDefault_should_use_agent_model() {
        // Given：委派前的预校验还没有子会话，只能用 agentId
        ResolvedModel expected = resolvedModel();
        when(agentManager.find("scout")).thenReturn(definitionWithModel("openai/gpt-4o-mini"));
        when(modelManager.resolveReference("openai/gpt-4o-mini")).thenReturn(expected);

        // When / Then
        assertSame(expected, resolver.resolveByAgentOrDefault("scout"));
    }

    @Test
    void resolveByAgentOrDefault_should_fall_back_to_default_when_no_agent() {
        // Given
        ResolvedModel expected = resolvedModel();
        when(agentManager.find("ghost")).thenReturn(null);
        when(modelManager.resolveDefault()).thenReturn(expected);

        // When / Then
        assertSame(expected, resolver.resolveByAgentOrDefault("ghost"));
    }

    /**
     * 构造带指定偏好模型的 agent 定义。
     *
     * @param model 模型引用，可为 {@code null}
     * @return agent 定义
     */
    private static AgentDefinition definitionWithModel(String model) {
        return new AgentDefinition("scout", "侦察", null, null, model);
    }

    /**
     * 构造一个解析结果。
     *
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel() {
        return new ResolvedModel(new Provider("openai", "openai", null, null, null),
                new Model("gpt-4o", "gpt-4o", 0, 0));
    }
}
