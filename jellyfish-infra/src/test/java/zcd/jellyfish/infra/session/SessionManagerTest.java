package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.LlmHttpException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.SessionMessageAppendedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.LifecycleVerdict;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.api.extension.SessionBeforeForkRequest;
import zcd.jellyfish.api.extension.SessionKind;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        return new SessionManager(agentManager, events, extensions, new SessionDefaults());
    }

    @Test
    void create_should_bind_default_agent_when_agent_id_blank() {
        // Given
        when(agentManager.resolveDefault()).thenReturn(definition(CODER));

        // When
        Session session = manager().create(null, null, null);

        // Then
        assertEquals(CODER, session.getAgentId());
    }

    @Test
    void create_should_keep_agent_null_when_no_agent_configured() {
        // Given：一个 agent 都没配是合法状态，不能因此抛错
        when(agentManager.resolveDefault()).thenReturn(null);

        // When
        Session session = manager().create("  ", null, null);

        // Then
        assertNull(session.getAgentId());
    }

    @Test
    void create_should_prefer_explicit_agent_over_default() {
        // When
        Session session = manager().create("writer", null, null);

        // Then
        assertEquals("writer", session.getAgentId());
    }

    @Test
    void createEphemeral_should_throw_when_parent_blank() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> manager().createEphemeral("  ", CODER, null, null));
    }

    @Test
    void createEphemeral_should_keep_parent_link_and_explicit_selections() {
        // When
        Session session = manager()
                .createEphemeral("parent-1", "scout", "openai", "gpt-4o");

        // Then
        assertEquals("parent-1", session.getParentSessionId());
        assertEquals("scout", session.getAgentId());
        assertEquals("openai", session.getProvider());
        assertEquals("gpt-4o", session.getModel());
    }

    @Test
    void createEphemeral_should_fall_back_to_default_agent_when_agent_blank() {
        // Given
        when(agentManager.resolveDefault()).thenReturn(definition(CODER));

        // When
        Session session = manager().createEphemeral("parent-1", "  ", null, null);

        // Then
        assertEquals(CODER, session.getAgentId());
    }

    @Test
    void createEphemeral_should_ignore_pending_defaults() {
        // Given：待生效默认值是「我接下来这次对话要用它」，与子代理的一次委派无关
        when(agentManager.resolveDefault()).thenReturn(definition(CODER));
        SessionDefaults defaults = new SessionDefaults();
        defaults.setAgentId("pending-agent");
        defaults.setModel("openai", "pending-model");
        SessionManager manager = new SessionManager(agentManager, events, extensions, defaults);

        // When
        Session session = manager.createEphemeral("parent-1", null, null, null);

        // Then
        assertEquals(CODER, session.getAgentId());
        assertNull(session.getModel());
    }

    @Test
    void createEphemeral_should_publish_created_event_with_parent() {
        // When
        manager().createEphemeral("parent-1", CODER, null, null);

        // Then
        SessionCreatedEvent event = publishedEvent(SessionCreatedEvent.class);
        assertEquals("parent-1", event.getParentSessionId());
    }

    @Test
    void createEphemeral_should_not_appear_in_all() {
        // Given
        SessionManager manager = manager();
        manager.create(CODER, null, null);

        // When
        manager.createEphemeral("parent-1", CODER, null, null);

        // Then
        assertEquals(1, manager.all().size());
        assertEquals(CODER, manager.all().iterator().next().getAgentId());
    }

    @Test
    void appendMessage_should_not_persist_when_session_ephemeral() {
        // Given
        AtomicInteger persists = countingPersistHandler();
        SessionManager manager = manager();
        Session session = manager.createEphemeral("parent-1", CODER, null, null);

        // When
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hello"), null);

        // Then
        assertEquals(0, persists.get());
    }

    @Test
    void appendMessage_should_persist_when_session_root() {
        // Given：同一处理器下普通会话仍然即时落盘，证明计数手段本身有效
        AtomicInteger persists = countingPersistHandler();
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        // When
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hello"), null);

        // Then
        assertEquals(1, persists.get());
    }

    @Test
    void appendMessage_should_publishLlmCallEvent_withTokenAndCacheCounts() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        // When：一次「总输入 100、其中命中 80」的调用随 assistant 消息落会话
        manager.appendMessage(session.getSessionId(), LlmMessage.assistant("ok"),
                new LlmUsage(100, 7, 107, 80, 0));

        // Then：用量除了进会话状态（/usage 读它），还要能在进程级被订阅到——
        // 后者是「这台机器一共命中了多少缓存」唯一可能的来源
        LlmCallCompletedEvent event = publishedEvent(LlmCallCompletedEvent.class);
        assertEquals(session.getSessionId(), event.getSessionId());
        assertEquals(100, event.getUsage().getPromptTokens().intValue());
        assertEquals(80, event.getUsage().getCacheReadTokens().intValue());
        assertEquals(107L, session.getUsage().getTotalTokens());
    }

    @Test
    void publishCallFailure_should_carryStatusCode_andSessionAttribution() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, "openai", "gpt-4o");

        // When：端点以 400 拒收某个字段
        manager.publishCallFailure(session.getSessionId(), "gpt-4o",
                new LlmHttpException("chat request failed (HTTP 400): unknown field", 400));

        // Then：状态码必须留下来——订阅方要靠它区分「字段被拒该降级」与「限流该重试」
        LlmCallFailedEvent event = publishedEvent(LlmCallFailedEvent.class);
        assertEquals(400, event.getStatusCode());
        assertTrue(event.isRejected());
        assertEquals(session.getSessionId(), event.getSessionId());
        assertEquals("openai", event.getProvider());
        assertEquals("gpt-4o", event.getModel());
    }

    @Test
    void publishCallFailure_should_notTreatRateLimitAsRejected() {
        // Given：429 是暂时性的，与 400 的处理完全相反
        SessionManager manager = manager();
        Session session = manager.create(CODER, "openai", "gpt-4o");

        // When
        manager.publishCallFailure(session.getSessionId(), null,
                new LlmHttpException("chat request failed (HTTP 429)", 429));

        // Then
        LlmCallFailedEvent event = publishedEvent(LlmCallFailedEvent.class);
        assertEquals(429, event.getStatusCode());
        assertFalse(event.isRejected());
    }

    @Test
    void publishCallFailure_should_useZeroStatusAndSessionModel_when_notHttpFailure() {
        // Given：网络异常不是 HTTP 层面的失败，而模型标识可以由会话补上
        SessionManager manager = manager();
        Session session = manager.create(CODER, "openai", "gpt-4o");

        // When
        manager.publishCallFailure(session.getSessionId(), null,
                new JellyfishException("chat request failed for provider: openai"));

        // Then
        LlmCallFailedEvent event = publishedEvent(LlmCallFailedEvent.class);
        assertEquals(0, event.getStatusCode());
        assertFalse(event.isRejected());
        assertEquals("gpt-4o", event.getModel());
    }

    @Test
    void publishCallFailure_should_notThrow_when_sessionIsGone() {
        // Given：调用失败可能发生在会话已经被关掉之后
        SessionManager manager = manager();

        // When
        manager.publishCallFailure("s-不存在", "gpt-4o", new JellyfishException("boom"));

        // Then：报一条事件不该因为「找不到会话」而抛出来盖掉原来的失败
        LlmCallFailedEvent event = publishedEvent(LlmCallFailedEvent.class);
        assertNull(event.getProvider());
        assertEquals("gpt-4o", event.getModel());
    }

    @Test
    void appendMessage_should_notPublishLlmCallEvent_when_usageIsAbsent() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        // When：工具结果等消息不带用量
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // Then：「厂商没返回用量」不是一次可计量的调用，发出去只会让订阅方多一堆要过滤的零
        verify(events, never()).publish(any(LlmCallCompletedEvent.class));
    }

    @Test
    void recordUsage_should_publishLlmCallEvent_forCallsWithoutMessage() {
        // Given：/compact 的摘要调用不产生会话消息
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        // When
        manager.recordUsage(session.getSessionId(), new LlmUsage(200, 5, 205, 150, 0));

        // Then：这条路径不产生消息，不发事件的话它在进程级彻底不可见
        LlmCallCompletedEvent event = publishedEvent(LlmCallCompletedEvent.class);
        assertEquals(150, event.getUsage().getCacheReadTokens().intValue());
        assertEquals(205L, session.getUsage().getTotalTokens());
    }

    @Test
    void recordUsage_should_merge_session_usage_with_call_count() {
        // Given：一次嵌套回合的累计用量（多次调用）
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        SessionUsage nested = new SessionUsage(5L, 7L, 12L, 3L);

        // When
        manager.recordUsage(session.getSessionId(), nested);

        // Then：父会话的账要把子代理的调用次数一并算上
        assertEquals(12L, session.getUsage().getTotalTokens());
        assertEquals(3L, session.getUsage().getLlmCalls());
    }

    @Test
    void close_should_ask_hook_before_persisting_when_user_requested() {
        // Given：关闭前钩子排在落盘之前——排在之后就没有「拦下」可言，收尾动作也只会晚于持久化
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        List<String> order = new ArrayList<String>();
        extensions.contribute("guard", SessionBeforeCloseRequest.class, null, request -> {
            order.add("hook:" + request.getReason() + ":" + request.isVetoSupported());
            return LifecycleVerdict.proceed();
        }, RegisterOptions.DEFAULT);
        extensions.contribute("persist", SessionPersistRequest.class, null, request -> {
            order.add("persist");
            return null;
        }, RegisterOptions.DEFAULT);

        // When
        manager.close(session.getSessionId(), SessionBeforeCloseRequest.Reason.USER_REQUEST);

        // Then
        assertEquals(Arrays.asList("hook:USER_REQUEST:true", "persist"), order);
    }

    @Test
    void close_should_reject_when_hook_cancels_user_request() {
        // Given：用户主动关闭是唯一有意义的否决场景
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        extensions.contribute("guard", SessionBeforeCloseRequest.class, null,
                request -> LifecycleVerdict.cancel("还有未保存的改动"), RegisterOptions.DEFAULT);

        // When / Then：fail-loud 而不是静默不关——后者与「会话不存在」无法区分
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> manager.close(session.getSessionId(), SessionBeforeCloseRequest.Reason.USER_REQUEST));
        assertTrue(error.getMessage().contains("还有未保存的改动"), error.getMessage());
        // 会话仍在：拦下必须真的拦住
        assertSame(session, manager.require(session.getSessionId()));
    }

    @Test
    void close_should_ignore_veto_when_shutdown() {
        // Given：关机路径不允许被插件拖住——否则结果是「本该关掉的会话留在了表里」
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        extensions.contribute("guard", SessionBeforeCloseRequest.class, null,
                request -> LifecycleVerdict.cancel("等等我"), RegisterOptions.DEFAULT);

        // When
        manager.close(session.getSessionId(), SessionBeforeCloseRequest.Reason.SHUTDOWN);

        // Then
        assertTrue(manager.all().isEmpty());
    }

    @Test
    void close_should_ignore_veto_when_internal() {
        // Given：内部收尾（瞬时子代理会话跑完）同样不由用户发起，也不允许被拖住
        SessionManager manager = manager();
        Session session = manager.createEphemeral("parent-1", CODER, null, null);
        extensions.contribute("guard", SessionBeforeCloseRequest.class, null,
                request -> LifecycleVerdict.cancel("等等我"), RegisterOptions.DEFAULT);

        // When：无原因的重载就是这一档
        manager.close(session.getSessionId());

        // Then：会话已经不在表里（瞬时会话不进 all()，因此用 require 反证）
        assertThrows(JellyfishException.class, () -> manager.require(session.getSessionId()));
    }

    @Test
    void close_should_proceed_when_hook_throws() {
        // Given：钩子坏掉不该把关不掉的会话留在进程里
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        extensions.contribute("broken", SessionBeforeCloseRequest.class, null, request -> {
            throw new IllegalStateException("插件崩了");
        }, RegisterOptions.DEFAULT);

        // When
        manager.close(session.getSessionId(), SessionBeforeCloseRequest.Reason.USER_REQUEST);

        // Then
        assertTrue(manager.all().isEmpty());
    }

    @Test
    void close_should_not_ask_hook_when_no_handler_registered() {
        // Given：0 handler 是兼容性承诺
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        // When
        Session closed = manager.close(session.getSessionId(), SessionBeforeCloseRequest.Reason.USER_REQUEST);

        // Then
        assertSame(session, closed);
        assertTrue(manager.all().isEmpty());
    }

    @Test
    void close_should_publish_closed_event_with_parent_when_ephemeral() {
        // Given
        SessionManager manager = manager();
        Session session = manager.createEphemeral("parent-1", CODER, null, null);

        // When
        manager.close(session.getSessionId());

        // Then
        SessionClosedEvent event = publishedEvent(SessionClosedEvent.class);
        assertEquals("parent-1", event.getParentSessionId());
        assertThrows(JellyfishException.class, () -> manager.require(session.getSessionId()));
    }

    @Test
    void create_should_keep_explicit_model_and_agent() {
        // When
        Session session = manager()
                .create(CODER, "openai", "gpt-4o");

        // Then
        assertEquals("openai", session.getProvider());
        assertEquals("gpt-4o", session.getModel());
        assertEquals(CODER, session.getAgentId());
    }

    @Test
    void create_should_generate_session_id_and_register_it() {
        // When
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

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
        Session session = manager.create(null, null, null);

        // Then
        SessionCreatedEvent event = publishedEvent(SessionCreatedEvent.class);
        assertEquals(CODER, event.getAgentId());
        assertEquals(session.getSessionId(), event.getSessionId());
    }

    @Test
    void create_should_not_change_current_session() {
        // When
        SessionManager manager = manager();
        manager.create(CODER, null, null);

        // Then：并发创建不应互相抢占当前指针
        assertNull(manager.current());
    }

    @Test
    void createDefault_should_leave_model_unset() {
        // When
        Session session = manager().createDefault();

        // Then：没有任何待生效默认值时，模型留空（由调用点按配置默认解析）
        assertNull(session.getProvider());
        assertNull(session.getModel());
    }

    @Test
    void create_should_fill_unspecified_fields_from_pendingDefaults() {
        // Given：首页上设过待生效默认值
        SessionDefaults pending = new SessionDefaults();
        pending.setAgentId(CODER);
        pending.setModel("openai", "gpt-4o");
        SessionManager manager = new SessionManager(agentManager, events, extensions, pending);

        // When
        Session session = manager.createDefault();

        // Then：几项都从待生效默认值填进来——这就是「首页设了 /model，下一条消息就真的用它」
        assertEquals(CODER, session.getAgentId());
        assertEquals("openai", session.getProvider());
        assertEquals("gpt-4o", session.getModel());
    }

    @Test
    void create_should_prefer_explicitArgument_over_pendingDefaults() {
        // Given
        SessionDefaults pending = new SessionDefaults();
        pending.setAgentId(CODER);
        pending.setModel("openai", "gpt-4o");
        SessionManager manager = new SessionManager(agentManager, events, extensions, pending);

        // When：调用方显式指定了全部项（如 CLI 的 --agent / --model）
        Session session = manager.create("writer", "ollama", "llama3");

        // Then：参数优先，待生效默认值只在「没指定」时才管用
        assertEquals("writer", session.getAgentId());
        assertEquals("ollama", session.getProvider());
        assertEquals("llama3", session.getModel());
    }

    @Test
    void create_should_keep_modelNull_when_neitherArgumentNorDefaultGiven() {
        // Given：待生效默认值里只设了 agent
        SessionDefaults pending = new SessionDefaults();
        pending.setAgentId(CODER);
        SessionManager manager = new SessionManager(agentManager, events, extensions, pending);

        // When
        Session session = manager.create(null, null, null);

        // Then：provider / model 留 null = 「跟随配置默认」，不能在创建期就把它们解析掉
        assertNull(session.getProvider());
        assertNull(session.getModel());
        assertEquals(CODER, session.getAgentId());
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
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

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
        Session session = manager.create(CODER, null, null);
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
        Session session = manager.create(CODER, null, null);
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
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);
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
        Session session = manager.create(CODER, null, null);

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
        Session session = manager.create(CODER, null, null);

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
        Session session = manager.create(CODER, null, null);

        // When
        manager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), null);

        // Then
        assertEquals(1, session.size());
    }

    @Test
    void messagesOf_should_return_unmodifiable_snapshot() {
        // Given
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
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
        Session session = manager.create(CODER, null, null);
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
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

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
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

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
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

        // When
        manager.switchModel(first.getSessionId(), "ollama", "qwen3");

        // Then
        assertEquals("ollama", first.getProvider());
        assertEquals("qwen3", first.getModel());
        assertNull(second.getProvider());
    }

    @Test
    void switchModel_should_leave_other_session_untouched() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

        // When
        manager.switchModel(first.getSessionId(), "openai", "gpt-4o");

        // Then
        assertEquals("gpt-4o", first.getModel());
        assertNull(second.getModel());
    }

    @Test
    void sessions_should_be_isolated_from_each_other() {
        // Given
        SessionManager manager = manager();
        Session first = manager.create(CODER, null, null);
        Session second = manager.create(CODER, null, null);

        // When：交替追加（用 assistant 承载用量——调用次数只认模型响应）
        manager.appendMessage(first.getSessionId(), LlmMessage.assistant("a1"), new LlmUsage(1, 1, 2));
        manager.appendMessage(second.getSessionId(), LlmMessage.assistant("b1"), null);
        manager.appendMessage(first.getSessionId(), LlmMessage.assistant("a2"), null);

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
        manager.create(CODER, null, null);

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
    }

    @Test
    void fork_should_copy_messages_up_to_cut_and_mark_origin() {
        // Given：user / assistant(tool_use) / tool / assistant
        SessionManager manager = manager();
        Session source = conversation(manager);
        String cutMessageId = source.getMessages().get(3).getMessageId();

        // When
        Session forked = manager.fork(source.getSessionId(), cutMessageId, "换条路走");

        // Then
        assertEquals(4, forked.size());
        assertEquals("换条路走", forked.getTitle());
        assertEquals(SessionKind.FORKED, forked.getKind());
        assertEquals(source.getSessionId(), forked.getParentSessionId());
        assertEquals(cutMessageId, forked.getForkPointMessageId());
        assertNotEquals(source.getSessionId(), forked.getSessionId());
    }

    @Test
    void fork_should_align_cut_forward_when_pointing_at_tool_group_member() {
        // Given：指定 assistant(tool_use)（下标 1），而它的工具结果在后面
        SessionManager manager = manager();
        Session source = conversation(manager);

        // When
        Session forked = manager.fork(source.getSessionId(),
                source.getMessages().get(1).getMessageId(), null);

        // Then：切点推到这一组工具结果的末尾（下标 2），而不是停在 1
        // ——停在 1 会得到一条没有结果的 assistant(tool_use)，而内核在组装请求时会把那种结尾丢掉，
        // 于是那条消息「在历史里在、模型永远看不到」
        assertEquals(3, forked.size());
        assertEquals("call_1", forked.getMessages().get(2).getMessage().getToolCallId());
    }

    @Test
    void fork_should_keep_cut_when_pointing_at_last_tool_result_already() {
        SessionManager manager = manager();
        Session source = conversation(manager);

        Session forked = manager.fork(source.getSessionId(),
                source.getMessages().get(2).getMessageId(), null);

        assertEquals(3, forked.size());
    }

    @Test
    void fork_should_not_copy_usage() {
        // 那是源会话花掉的钱；带过去会把成本重复计入
        SessionManager manager = manager();
        Session source = conversation(manager);
        manager.recordUsage(source.getSessionId(), new LlmUsage(100, 50, 150, 10, 20));

        Session forked = manager.fork(source.getSessionId(), null, null);

        assertEquals(0, forked.getUsage().getTotalTokens());
        assertEquals(0, forked.getUsage().getLlmCalls());
    }

    @Test
    void fork_should_carry_compaction_when_boundary_is_within_copied_range() {
        SessionManager manager = manager();
        Session source = conversation(manager);
        String boundary = source.getMessages().get(0).getMessageId();
        manager.applyCompaction(source.getSessionId(), "摘要", boundary, 1);

        Session forked = manager.fork(source.getSessionId(), null, null);

        // 边界在复制范围内：摘要仍代表被丢出上下文的那一段
        assertNotNull(forked.getCompaction());
        assertEquals("摘要", forked.getCompaction().getSummary());
    }

    @Test
    void fork_should_drop_compaction_when_boundary_is_beyond_the_cut() {
        SessionManager manager = manager();
        Session source = conversation(manager);
        // 边界指向最后一条，而切点落在它之前
        String boundary = source.getMessages().get(3).getMessageId();
        manager.applyCompaction(source.getSessionId(), "摘要", boundary, 1);
        String cut = source.getMessages().get(0).getMessageId();

        Session forked = manager.fork(source.getSessionId(), cut, null);

        // 那一刻本来还没压过，不带才是准确复原
        assertNull(forked.getCompaction());
    }

    @Test
    void fork_should_persist_and_list_forked_session() {
        // fork 出来的是用户的正常会话，不是子代理的临时工作区
        SessionManager manager = manager();
        AtomicInteger persists = countingPersistHandler();
        Session source = conversation(manager);
        int before = persists.get();

        Session forked = manager.fork(source.getSessionId(), null, null);

        assertTrue(persists.get() > before);
        assertTrue(manager.all().stream().anyMatch(s -> s.getSessionId().equals(forked.getSessionId())));
    }

    @Test
    void fork_should_throw_when_plugin_vetoes() {
        SessionManager manager = manager();
        Session source = conversation(manager);
        extensions.contribute("guard", SessionBeforeForkRequest.class, null,
                request -> LifecycleVerdict.cancel("工作区有未提交的改动"), RegisterOptions.DEFAULT);

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> manager.fork(source.getSessionId(), null, null));

        // 否决一定被采纳：拦下了就什么都不复制
        assertTrue(error.getMessage().contains("工作区有未提交的改动"), error.getMessage());
        assertEquals(1, manager.all().size());
    }

    @Test
    void fork_should_pass_aligned_cut_to_hook() {
        // 插件看到的必须是内核真正要复制的范围，而不是调用方原始指定的那一条
        SessionManager manager = manager();
        Session source = conversation(manager);
        List<Integer> cuts = new ArrayList<Integer>();
        extensions.contribute("probe", SessionBeforeForkRequest.class, null,
                (ExtensionHandler<SessionBeforeForkRequest, LifecycleVerdict>) request -> {
                    cuts.add(request.getCutIndex());
                    cuts.add(request.getMessageCount());
                    return LifecycleVerdict.proceed();
                }, RegisterOptions.DEFAULT);

        manager.fork(source.getSessionId(), source.getMessages().get(1).getMessageId(), null);

        assertEquals(Arrays.asList(2, 3), cuts);
    }

    @Test
    void fork_should_throw_when_cut_message_not_found() {
        SessionManager manager = manager();
        Session source = conversation(manager);

        assertThrows(JellyfishException.class,
                () -> manager.fork(source.getSessionId(), "ghost", null));
    }

    @Test
    void fork_should_throw_when_no_history() {
        SessionManager manager = manager();
        Session source = manager.create(CODER, null, null);

        assertThrows(JellyfishException.class, () -> manager.fork(source.getSessionId(), null, null));
    }

    @Test
    void fork_should_default_title_when_absent() {
        SessionManager manager = manager();
        Session source = conversation(manager);
        manager.updateTitle(source.getSessionId(), "重构登录");

        Session forked = manager.fork(source.getSessionId(), null, null);

        assertTrue(forked.getTitle().contains("重构登录"), forked.getTitle());
    }

    @Test
    void putExtensionEntry_should_write_and_read_back_under_owner_namespace() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::checked",
                java.util.Collections.singletonMap("files", 3));

        assertEquals(1, manager.extensionEntries(session.getSessionId()).size());
        assertEquals(1, manager.extensionEntriesOf(session.getSessionId(), "plugin-a").size());
        // 前缀匹配必须带分隔符：plugin-a 不能看到 plugin-ab 的东西
        assertEquals(0, manager.extensionEntriesOf(session.getSessionId(), "plugin-ab").size());
        assertEquals(3, manager.extensionEntries(session.getSessionId()).get(0).getValue().get("files"));
    }

    @Test
    void putExtensionEntry_should_replace_existing_without_growing_count() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k", null);

        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k",
                java.util.Collections.singletonMap("v", 2));

        assertEquals(1, manager.extensionEntries(session.getSessionId()).size());
    }

    @Test
    void putExtensionEntry_should_reject_value_over_limit() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < SessionManager.EXTENSION_VALUE_MAX_BYTES + 100; index++) {
            huge.append('x');
        }

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::big",
                        java.util.Collections.singletonMap("payload", huge.toString())));

        // 超限拒写而不截断：截断会留下「看起来完整、实际缺字段」的数据
        assertTrue(error.getMessage().contains("plugin-a"), error.getMessage());
        assertTrue(manager.extensionEntries(session.getSessionId()).isEmpty());
    }

    @Test
    void putExtensionEntry_should_reject_too_many_entries() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        for (int index = 0; index < SessionManager.EXTENSION_ENTRY_MAX_COUNT; index++) {
            manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k" + index, null);
        }

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::extra", null));

        assertTrue(error.getMessage().contains("上限"), error.getMessage());
        assertEquals(SessionManager.EXTENSION_ENTRY_MAX_COUNT,
                manager.extensionEntries(session.getSessionId()).size());
    }

    @Test
    void putExtensionEntry_should_reject_too_long_key() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        StringBuilder key = new StringBuilder("plugin-a::");
        while (key.length() <= SessionManager.EXTENSION_KEY_MAX_CHARS) {
            key.append('k');
        }

        assertThrows(JellyfishException.class, () -> manager.putExtensionEntry(
                session.getSessionId(), "plugin-a", key.toString(), null));
    }

    @Test
    void removeExtensionEntry_should_be_noop_when_absent() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);

        manager.removeExtensionEntry(session.getSessionId(), "plugin-a::ghost");

        assertTrue(manager.extensionEntries(session.getSessionId()).isEmpty());
    }

    @Test
    void removeExtensionEntry_should_drop_entry() {
        SessionManager manager = manager();
        Session session = manager.create(CODER, null, null);
        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k", null);

        manager.removeExtensionEntry(session.getSessionId(), "plugin-a::k");

        assertTrue(manager.extensionEntries(session.getSessionId()).isEmpty());
    }

    @Test
    void putExtensionEntry_should_persist_immediately_outside_turn() {
        SessionManager manager = manager();
        AtomicInteger persists = countingPersistHandler();
        Session session = manager.create(CODER, null, null);
        int before = persists.get();

        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k", null);

        assertTrue(persists.get() > before);
    }

    @Test
    void putExtensionEntry_should_defer_persist_inside_turn() {
        // 回合内只标脏：与消息追加同一纪律，不新增第三条落盘路径
        SessionManager manager = manager();
        AtomicInteger persists = countingPersistHandler();
        Session session = manager.create(CODER, null, null);
        manager.beginTurn(session.getSessionId());
        int before = persists.get();

        manager.putExtensionEntry(session.getSessionId(), "plugin-a", "plugin-a::k", null);
        int duringTurn = persists.get();
        manager.flush(session.getSessionId());

        assertEquals(before, duringTurn);
        assertTrue(persists.get() > duringTurn);
    }

    @Test
    void fork_should_copy_extension_entries() {
        SessionManager manager = manager();
        Session source = conversation(manager);
        manager.putExtensionEntry(source.getSessionId(), "plugin-a", "plugin-a::k",
                java.util.Collections.singletonMap("v", 1));

        Session forked = manager.fork(source.getSessionId(), null, null);

        assertEquals(1, forked.getExtensionEntries().size());
        assertEquals("plugin-a::k", forked.getExtensionEntries().get(0).getKey());
    }

    @Test
    void snapshot_roundTrip_should_keep_kind_origin_and_extension_entries() {
        // 快照往返是「漏了一个字段」唯一能被自动发现的地方
        SessionManager manager = manager();
        Session source = conversation(manager);
        manager.putExtensionEntry(source.getSessionId(), "plugin-a", "plugin-a::k",
                java.util.Collections.singletonMap("v", 1));
        Session forked = manager.fork(source.getSessionId(), null, "分支");

        SessionSnapshot first = SessionSnapshots.capture(forked);
        Session restored = Session.restore(first);
        SessionSnapshot second = SessionSnapshots.capture(restored);

        assertEquals(SessionKind.FORKED, restored.getKind());
        assertEquals(source.getSessionId(), restored.getParentSessionId());
        assertNotNull(restored.getForkPointMessageId());
        assertEquals(1, restored.getExtensionEntries().size());
        assertEquals(first.getKind(), second.getKind());
        assertEquals(first.getParentSessionId(), second.getParentSessionId());
        assertEquals(first.getForkPointMessageId(), second.getForkPointMessageId());
        assertEquals(first.getExtensionEntries().get(0).getKey(),
                second.getExtensionEntries().get(0).getKey());
    }

    @Test
    void restore_should_map_legacy_parent_to_ephemeral() {
        // 老快照没有 kind 字段：那时 parentSessionId 非空只可能是子代理会话。
        // 当成普通会话会让它被落盘并进列表——那正是「一字段两用」带来的静默数据丢失
        SessionSnapshot legacy = new SessionSnapshot("s-legacy", 1L, 1L, null, CODER, null, null,
                null, null, null, null, "parent-1", null, null);

        assertEquals(SessionKind.EPHEMERAL, legacy.getKind());
        assertTrue(Session.restore(legacy).isEphemeral());
    }

    /**
     * 造一段带工具调用的会话：user / assistant(tool_use) / tool / assistant。
     *
     * @param manager 会话域服务
     * @return 源会话
     */
    private Session conversation(SessionManager manager) {
        Session session = manager.create(CODER, "openai", "gpt-4o");
        manager.appendMessage(session.getSessionId(), LlmMessage.user("读文件"), null);
        manager.appendMessage(session.getSessionId(), LlmMessage.assistant("好的",
                java.util.Collections.singletonList(new zcd.jellyfish.infra.llm.LlmToolCall(0,
                        "call_1", "read", "{}"))), null);
        manager.appendMessage(session.getSessionId(), LlmMessage.tool("call_1", "read", "内容"), null);
        manager.appendMessage(session.getSessionId(), LlmMessage.assistant("读完了"), null);
        return session;
    }

    /**
     * 注册一个只计数、不写盘的会话持久化处理器。
     * <p>
     * 「子代理会话不落盘」这条不变式只能这样验证：真实注册表里没有处理器时，普通会话也不会落盘，
     * 两者观察不到差异。
     *
     * @return 记录调用次数的计数器
     */
    private AtomicInteger countingPersistHandler() {
        AtomicInteger persists = new AtomicInteger();
        extensions.contribute("test", SessionPersistRequest.class, null,
                (ExtensionHandler<SessionPersistRequest, Void>) request -> {
                    persists.incrementAndGet();
                    return null;
                }, RegisterOptions.DEFAULT);
        return persists;
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
