package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * {@link SessionManager} 的持久化与恢复测试：锁住「哪些变更必须落盘」「失败怎么办」「恢复怎么导入」。
 * <p>
 * 用真实的 {@link ExtensionRegistry} 而不是 mock：这里要验证的正是「同步派发的顺序与失败传播」，
 * 把注册表换成 mock 等于把被测行为重写一遍。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionManager 会话持久化")
class SessionManagerPersistenceTest {

    /** agent 门面：本类不关心 agent 解析，未打桩时按「没有默认 agent」处理。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 被测会话域服务。 */
    private SessionManager manager;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        manager = new SessionManager(agentManager, events, extensions);
    }

    @Test
    @DisplayName("创建会话应把新会话落盘")
    void create_should_persistNewSession() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();

        Session session = manager.create(null, null, null, null);

        assertEquals(1, persisted.size());
        assertEquals(session.getSessionId(), persisted.get(0).getSessionId());
    }

    @Test
    @DisplayName("没有持久化插件时一切照旧：没有插件是合法状态")
    void create_should_workWithoutAnyPersistPlugin() {
        Session session = manager.create(null, null, null, null);

        assertEquals(1, manager.all().size());
        assertSame(session, manager.require(session.getSessionId()));
    }

    @Test
    @DisplayName("追加消息、改标题、绑 agent、切模型、切模式都要落盘")
    void everyMutation_should_persist() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        Session session = manager.create(null, null, null, null);
        String sessionId = session.getSessionId();

        manager.appendMessage(sessionId, LlmMessage.user("你好"), null);
        manager.updateTitle(sessionId, "标题");
        manager.bindAgent(sessionId, "coder");
        manager.switchModel(sessionId, "openai", "gpt-4o");
        manager.setPermissionMode(sessionId, PermissionMode.PLAN);

        // 1 次创建 + 5 次变更
        assertEquals(6, persisted.size());
        SessionSnapshot last = persisted.get(persisted.size() - 1);
        assertEquals("标题", last.getTitle());
        assertEquals("coder", last.getAgentId());
        assertEquals("openai", last.getProvider());
        assertEquals("gpt-4o", last.getModel());
        assertEquals(PermissionMode.PLAN, last.getPermissionMode());
        assertEquals(1, last.getMessages().size());
    }

    @Test
    @DisplayName("关闭会话前先落盘，且关闭后的快照仍带着最后一轮内容")
    void close_should_persistLastSnapshot() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null, null).getSessionId();
        manager.appendMessage(sessionId, LlmMessage.assistant("再见"), null);

        manager.close(sessionId);

        assertEquals(3, persisted.size());
        assertEquals(1, persisted.get(2).getMessages().size());
    }

    @Test
    @DisplayName("落盘失败必须上抛：静默吞掉等于下一次启动悄悄少一段历史")
    void appendMessage_should_propagate_whenPersistFails() {
        String sessionId = manager.create(null, null, null, null).getSessionId();
        extensions.contribute("broken", SessionPersistRequest.class, null, request -> {
            throw new JellyfishException("磁盘满了");
        }, RegisterOptions.DEFAULT);

        assertThrows(JellyfishException.class, () -> manager.appendMessage(sessionId, LlmMessage.user("你好"), null));
    }

    @Test
    @DisplayName("关闭时落盘失败应保留会话，不制造「已关闭但没存下」")
    void close_should_keepSession_whenPersistFails() {
        // 创建时放行、关闭时失败：用一个开关模拟「写到一半磁盘满了」
        boolean[] failNext = {false};
        extensions.contribute("flaky", SessionPersistRequest.class, null, request -> {
            if (failNext[0]) {
                throw new JellyfishException("磁盘满了");
            }
            return null;
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null, null).getSessionId();
        failNext[0] = true;

        assertThrows(JellyfishException.class, () -> manager.close(sessionId));

        assertSame(sessionId, manager.require(sessionId).getSessionId());
    }

    @Test
    @DisplayName("恢复应把插件交回的会话导入会话表")
    void restore_should_importSnapshots() {
        contributeRestore(SessionRestoreResult.of(Arrays.asList(snapshot("s-1"), snapshot("s-2"))));

        int imported = manager.restore();

        assertEquals(2, imported);
        assertEquals(2, manager.all().size());
        assertEquals(1, manager.messagesOf("s-1").size());
        assertEquals(LlmMessage.ROLE_USER, manager.messagesOf("s-1").get(0).getRole());
    }

    @Test
    @DisplayName("会话已存在时保留内存里那一份，不覆盖正在进行的会话")
    void restore_should_skipExistingSession() {
        Session existing = manager.create(null, null, null, null);
        contributeRestore(SessionRestoreResult.of(Collections.singletonList(snapshot(existing.getSessionId()))));

        int imported = manager.restore();

        assertEquals(0, imported);
        assertTrue(manager.require(existing.getSessionId()).getMessages().isEmpty());
    }

    @Test
    @DisplayName("单个插件恢复失败应跳过，不能让进程起不来")
    void restore_should_continue_whenOnePluginFails() {
        extensions.contribute("broken", SessionRestoreRequest.class, null, request -> {
            throw new JellyfishException("备份读不出来");
        }, RegisterOptions.DEFAULT);
        contributeRestore(SessionRestoreResult.of(Collections.singletonList(snapshot("s-1"))));

        assertEquals(1, manager.restore());
        assertEquals(1, manager.all().size());
    }

    @Test
    @DisplayName("没有插件提供恢复数据时返回 0，不报错")
    void restore_should_returnZero_whenNoPluginRegistered() {
        assertEquals(0, manager.restore());
    }

    @Test
    @DisplayName("恢复导入的会话同样广播创建通知，订阅者的会话集合才完整")
    void restore_should_publishCreatedEvent() {
        contributeRestore(SessionRestoreResult.of(Collections.singletonList(snapshot("s-1"))));

        manager.restore();

        verify(events, atLeastOnce()).publish(any());
    }

    @Test
    @DisplayName("恢复出的会话是可读写的活会话，不是只读快照")
    void restoredSession_should_beFullyUsable() {
        contributeRestore(SessionRestoreResult.of(Collections.singletonList(snapshot("s-1"))));
        manager.restore();

        manager.appendMessage("s-1", LlmMessage.assistant("继续"), null);

        assertEquals(2, manager.messagesOf("s-1").size());
        assertFalse(manager.messagesOf("s-1").isEmpty());
    }

    /**
     * 注册一个记录全部落盘快照的处理器。
     *
     * @return 记录列表
     */
    private List<SessionSnapshot> capturePersistedSnapshots() {
        List<SessionSnapshot> persisted = new ArrayList<SessionSnapshot>();
        extensions.contribute("recorder", SessionPersistRequest.class, null, request -> {
            persisted.add(request.getSnapshot());
            return null;
        }, RegisterOptions.DEFAULT);
        return persisted;
    }

    /**
     * 注册一个返回固定结果的恢复处理器。
     *
     * @param result 恢复结果
     */
    private void contributeRestore(SessionRestoreResult result) {
        extensions.contribute("restorer", SessionRestoreRequest.class, null, request -> result, RegisterOptions.DEFAULT);
    }

    /**
     * 构造一个带一条用户消息的会话快照。
     *
     * @param sessionId 会话标识
     * @return 会话快照
     */
    private static SessionSnapshot snapshot(String sessionId) {
        SessionMessageSnapshot message = new SessionMessageSnapshot("m-1", 1L, LlmMessage.ROLE_USER, "你好",
                null, null, null, null);
        return new SessionSnapshot(sessionId, 1L, 2L, "标题", "coder", "openai", "gpt-4o",
                PermissionMode.NORMAL, Collections.singletonList(message),
                new SessionUsageSnapshot(0L, 0L, 0L, 0L));
    }
}
