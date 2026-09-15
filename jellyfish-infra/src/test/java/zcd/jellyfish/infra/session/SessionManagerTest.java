package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.SessionMessageAppendedEvent;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionManager} 的单元测试：验证默认 agent 绑定、会话表与当前会话语义、唯一变更入口、
 * 事件广播与多会话隔离。
 * <p>
 * 只 mock 外部协作者 {@link AgentManager} 与 {@link EventPublisher}；{@link Session} 与
 * {@link SessionMessage} 用真实实例（mock 它们等于把被测行为重写一遍）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    /** agent 标识。 */
    private static final String CODER = "coder";

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 同步扩展点策略：用真实实例（该类是 final，且无需 mock 行为），本类只关心「有没有插件要落盘」。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    /**
     * 构造被测会话域服务。
     *
     * @return 会话域服务
     */
    private SessionManager manager() {
        return new SessionManager(agentManager, events, extensions);
    }

    @Test
    void create_should_bind_default_agent_when_agent_id_blank() {
        // Given
        when(agentManager.resolveDefault()).thenReturn(definition(CODER));

        // When
        Session session = manager().create(null, null, null, null);

        // Then
        assertEquals(CODER, session.getAgentId());
    }

    @Test
    void create_should_keep_agent_null_when_no_agent_configured() {
        // Given：一个 agent 都没配是合法状态，不能因此抛错
        when(agentManager.resolveDefault()).thenReturn(null);

        // When
        Session session = manager().create("  ", null, null, null);

        // Then
        assertNull(session.getAgentId());
    }

    @Test
    void create_should_prefer_explicit_agent_over_default() {
        // When
        Session session = manager().create("writer", null, null, null);

        // Then
        assertEquals("writer", session.getAgentId());
    }

    @Test
    void create_should_keep_explicit_model_and_permission_mode() {
        // When
        Session session = manager()
                .create(CODER, "openai", "gpt-4o", PermissionMode.PLAN);

        // Then
        assertEquals("openai", session.getProvider());
        assertEquals("gpt-4o", session.getModel());
        assertEquals(PermissionMode.PLAN, session.getPermissionMode());
        assertEquals(CODER, session.getAgentId());
    }

    @Test
    void create_should_generate_session_id_and_register_it() {
        // When
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);

        // Then
        assertNotNull(session.getSessionId());
        assertSame(session, manager.require(session.getSessionId()));
        assertEquals(1, manager.all().size());
    }

    @Test
    void create_should_publish_session_created_event_with_bound_agent() {
        // Given
        when(agentManager.resolveDefault()).thenReturn(definition(CODER));
        SessionManager manager = manager();

        // When
        Session session = manager.create(null, null, null, null);

        // Then
        SessionCreatedEvent event = publishedEvent(SessionCreatedEvent.class);
        assertEquals(CODER, event.getAgentId());
        assertEquals(session.getSessionId(), event.getSessionId());
    }

    @Test
    void create_should_not_change_current_session() {
        // When
        SessionManager manager = manager();
        manager.create(CODER, null, null, null);

        // Then：并发创建不应互相抢占当前指针
        assertNull(manager.current());
    }

    @Test
    void createDefault_should_create_session_with_normal_mode() {
        // When
        Session session = manager().createDefault();

        // Then
        assertEquals(PermissionMode.NORMAL, session.getPermissionMode());
        assertNull(session.getProvider());
        assertNull(session.getModel());
    }

    @Test
    void require_should_throw_when_session_id_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> manager().require(" "));
    }

    @Test
    void require_should_throw_when_session_not_found() {
        // When / Then
        assertThrows(JellyfishException.class, () -> manager().require("missing"));
    }

    @Test
    void current_should_return_null_when_no_session() {
        // When / Then
        assertNull(manager().current());
    }

    @Test
    void switchTo_should_make_session_current() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When
        manager.switchTo(second.getSessionId());

        // Then
        assertSame(second, manager.current());
        assertNotEquals(first.getSessionId(), manager.current().getSessionId());
    }

    @Test
    void switchTo_should_throw_when_session_not_found() {
        // When / Then
        assertThrows(JellyfishException.class, () -> manager().switchTo("missing"));
    }

    @Test
    void close_should_remove_session_and_publish_snapshot() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // When
        Session closed = manager.close(session.getSessionId());

        // Then
        assertSame(session, closed);
        assertThrows(JellyfishException.class, () -> manager.require(session.getSessionId()));
        assertTrue(manager.all().isEmpty());
        SessionClosedEvent event = publishedEvent(SessionClosedEvent.class);
        assertEquals(CODER, event.getAgentId());
        assertEquals(1, event.getMessageCount());
        assertEquals(session.getSessionId(), event.getSessionId());
    }

    @Test
    void close_should_clear_current_when_current_session_closed() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);
        manager.switchTo(session.getSessionId());

        // When
        manager.close(session.getSessionId());

        // Then
        assertNull(manager.current());
    }

    @Test
    void close_should_keep_current_when_other_session_closed() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);
        manager.switchTo(second.getSessionId());

        // When
        manager.close(first.getSessionId());

        // Then
        assertSame(second, manager.current());
    }

    @Test
    void close_should_be_idempotent_when_session_not_found() {
        // Given
        SessionManager manager = manager();

        // When / Then：关闭路径上重复关闭不该抛错
        assertNull(manager.close(null));
        assertNull(manager.close("missing"));
    }

    @Test
    void appendMessage_should_throw_when_session_not_found() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> manager().appendMessage("missing", LlmMessage.user("hi"), null));
    }

    @Test
    void appendMessage_should_accumulate_usage_and_publish_event() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);

        // When
        SessionMessage message = manager.appendMessage(session.getSessionId(),
                LlmMessage.assistant("ok"), new LlmUsage(4, 6, 10));

        // Then
        assertEquals(LlmMessage.ROLE_ASSISTANT, message.getRole());
        assertEquals(10L, session.getUsage().getTotalTokens());
        assertEquals(1L, session.getUsage().getLlmCalls());
        SessionMessageAppendedEvent event = publishedEvent(SessionMessageAppendedEvent.class);
        assertEquals(message.getMessageId(), event.getMessageId());
        assertEquals(LlmMessage.ROLE_ASSISTANT, event.getRole());
        assertEquals(session.getSessionId(), event.getSessionId());
    }

    @Test
    void appendMessage_should_publish_user_role_for_user_message() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);

        // When
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // Then
        assertEquals(LlmMessage.ROLE_USER, publishedEvent(SessionMessageAppendedEvent.class).getRole());
    }

    @Test
    void appendMessage_should_keep_session_change_when_publish_fails() {
        // Given：通知是可丢弃通道，发不出去不该影响会话状态
        doThrow(new IllegalStateException("channel closed")).when(events).publish(any());
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);

        // When
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // Then
        assertEquals(1, session.size());
    }

    @Test
    void messagesOf_should_return_unmodifiable_snapshot() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // When
        List<SessionMessage> messages = manager.messagesOf(session.getSessionId());

        // Then
        assertEquals(1, messages.size());
        assertThrows(UnsupportedOperationException.class,
                () -> messages.add(SessionMessage.of(LlmMessage.user("again"))));
    }

    @Test
    void llmMessagesOf_should_project_message_bodies() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null, null);
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);
        manager.appendMessage(session.getSessionId(), LlmMessage.assistant("hello"), null);

        // When
        List<LlmMessage> projected = manager.llmMessagesOf(session.getSessionId());

        // Then
        assertEquals(2, projected.size());
        assertEquals(LlmMessage.ROLE_USER, projected.get(0).getRole());
        assertEquals("hello", projected.get(1).getContent());
    }

    @Test
    void llmMessagesOf_should_throw_when_session_not_found() {
        // When / Then
        assertThrows(JellyfishException.class, () -> manager().llmMessagesOf("missing"));
    }

    @Test
    void updateTitle_should_only_affect_target_session() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When
        manager.updateTitle(first.getSessionId(), "第一个会话");

        // Then
        assertEquals("第一个会话", first.getTitle());
        assertNull(second.getTitle());
    }

    @Test
    void bindAgent_should_only_affect_target_session() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When
        manager.bindAgent(first.getSessionId(), "writer");

        // Then
        assertEquals("writer", first.getAgentId());
        assertEquals(CODER, second.getAgentId());
    }

    @Test
    void switchModel_should_only_affect_target_session() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When
        manager.switchModel(first.getSessionId(), "ollama", "qwen3");

        // Then
        assertEquals("ollama", first.getProvider());
        assertEquals("qwen3", first.getModel());
        assertNull(second.getProvider());
    }

    @Test
    void setPermissionMode_should_only_affect_target_session() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When
        manager.setPermissionMode(first.getSessionId(), PermissionMode.PLAN);

        // Then
        assertEquals(PermissionMode.PLAN, first.getPermissionMode());
        assertEquals(PermissionMode.NORMAL, second.getPermissionMode());
    }

    @Test
    void sessions_should_be_isolated_from_each_other() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null, null);
        Session second = manager.create(CODER, null, null, null);

        // When：交替追加
        manager.appendMessage(first.getSessionId(), LlmMessage.user("a1"), new LlmUsage(1, 1, 2));
        manager.appendMessage(second.getSessionId(), LlmMessage.user("b1"), null);
        manager.appendMessage(first.getSessionId(), LlmMessage.user("a2"), null);

        // Then
        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertEquals(2L, first.getUsage().getTotalTokens());
        assertEquals(0L, second.getUsage().getTotalTokens());
        assertEquals(2L, first.getUsage().getLlmCalls());
        assertEquals(1L, second.getUsage().getLlmCalls());
        assertEquals("a1", first.getMessages().get(0).getMessage().getContent());
        assertEquals("b1", second.getMessages().get(0).getMessage().getContent());
    }

    @Test
    void all_should_return_unmodifiable_collection() {
        // Given
        SessionManager manager = manager();
        manager.create(CODER, null, null, null);

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
    }

    /**
     * 构造一个只带标识的 agent 定义。
     *
     * @param agentId agent 标识
     * @return agent 定义
     */
    private AgentDefinition definition(String agentId) {
        return new AgentDefinition(agentId, null, null);
    }

    /**
     * 取本次测试中被广播出去的指定类型事件。
     * <p>
     * {@code ArgumentCaptor} 只做捕获、不按类型过滤，因此先收全部再按类型挑。
     *
     * @param type 期望的事件类型
     * @param <T>  事件类型
     * @return 该类型的事件
     */
    private <T extends JellyfishEvent> T publishedEvent(Class<T> type) {
        ArgumentCaptor<JellyfishEvent> captor = ArgumentCaptor.forClass(JellyfishEvent.class);
        verify(events, atLeastOnce()).publish(captor.capture());
        for (JellyfishEvent event : captor.getAllValues()) {
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        throw new AssertionError("event not published: " + type.getSimpleName());
    }
}
