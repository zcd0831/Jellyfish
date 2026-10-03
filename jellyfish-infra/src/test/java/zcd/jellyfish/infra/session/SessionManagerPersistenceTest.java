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
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        manager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
    }

    @Test
    @DisplayName("创建会话不落盘：建了却没用过的会话不该在磁盘上留文件与提交")
    void create_should_notPersistNewSession() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();

        Session session = manager.create(null, null, null);

        assertTrue(persisted.isEmpty(), "空会话在进程退出后自然消失");
        assertSame(session, manager.require(session.getSessionId()));
    }

    @Test
    @DisplayName("没有持久化插件时一切照旧：没有插件是合法状态")
    void create_should_workWithoutAnyPersistPlugin() {
        Session session = manager.create(null, null, null);

        assertEquals(1, manager.all().size());
        assertSame(session, manager.require(session.getSessionId()));
    }

    @Test
    @DisplayName("追加消息、改标题、绑 agent、切模型都要落盘")
    void everyMutation_should_persist() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        Session session = manager.create(null, null, null);
        String sessionId = session.getSessionId();

        manager.appendMessage(sessionId, LlmMessage.user("你好"), null);
        manager.updateTitle(sessionId, "标题");
        manager.bindAgent(sessionId, "coder");
        manager.switchModel(sessionId, "openai", "gpt-4o");

        // 4 次变更，各落一次（创建本身不落盘，回合外也走即时落盘）
        assertEquals(4, persisted.size());
        SessionSnapshot last = persisted.get(persisted.size() - 1);
        assertEquals("标题", last.getTitle());
        assertEquals("coder", last.getAgentId());
        assertEquals("openai", last.getProvider());
        assertEquals("gpt-4o", last.getModel());
        assertEquals(1, last.getMessages().size());
    }

    @Test
    @DisplayName("关闭会话前先落盘，且关闭后的快照仍带着最后一轮内容")
    void close_should_persistLastSnapshot() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();
        manager.appendMessage(sessionId, LlmMessage.assistant("再见"), null);

        manager.close(sessionId);

        // appendMessage 与 close 各一次（创建不落盘）
        assertEquals(2, persisted.size());
        assertEquals(1, persisted.get(1).getMessages().size());
    }

    @Test
    @DisplayName("落盘失败必须上抛：静默吞掉等于下一次启动悄悄少一段历史")
    void appendMessage_should_propagate_whenPersistFails() {
        String sessionId = manager.create(null, null, null).getSessionId();
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
        String sessionId = manager.create(null, null, null).getSessionId();
        failNext[0] = true;

        assertThrows(JellyfishException.class, () -> manager.close(sessionId));

        assertSame(sessionId, manager.require(sessionId).getSessionId());
    }

    @Test
    @DisplayName("删除会话应先派发删除请求，再从会话表移除")
    void delete_should_dispatchDeleteRequestAndRemoveSession() {
        List<String> deleted = new ArrayList<String>();
        extensions.contribute("eraser", SessionDeleteRequest.class, null, request -> {
            deleted.add(request.getSessionId());
            return null;
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null).getSessionId();

        Session removed = manager.delete(sessionId);

        assertEquals(Collections.singletonList(sessionId), deleted);
        assertEquals(sessionId, removed.getSessionId());
        assertTrue(manager.all().isEmpty());
        assertThrows(JellyfishException.class, () -> manager.require(sessionId));
    }

    @Test
    @DisplayName("删除不落盘最后快照：close 才落盘，delete 是「不要了」")
    void delete_should_notPersistSnapshot() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();

        manager.delete(sessionId);

        assertEquals(0, persisted.size(), "一次都不落：创建不落，delete 也不需要落最后快照");
    }

    @Test
    @DisplayName("删除失败应保留会话：不能让「界面说删了、文件还在」")
    void delete_should_keepSession_whenDeleteFails() {
        extensions.contribute("broken", SessionDeleteRequest.class, null, request -> {
            throw new JellyfishException("文件删不掉");
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null).getSessionId();

        assertThrows(JellyfishException.class, () -> manager.delete(sessionId));

        assertSame(sessionId, manager.require(sessionId).getSessionId());
    }

    @Test
    @DisplayName("删除当前会话后当前指针置空，外壳据此回首页")
    void delete_should_clearCurrent_whenCurrentDeleted() {
        Session session = manager.create(null, null, null);
        manager.switchTo(session.getSessionId());

        manager.delete(session.getSessionId());

        assertNull(manager.current());
    }

    @Test
    @DisplayName("删除不存在的会话是幂等的，也不派发删除请求")
    void delete_should_beIdempotent_whenSessionMissing() {
        List<String> deleted = new ArrayList<String>();
        extensions.contribute("eraser", SessionDeleteRequest.class, null, request -> {
            deleted.add(request.getSessionId());
            return null;
        }, RegisterOptions.DEFAULT);

        assertNull(manager.delete("missing"));
        assertNull(manager.delete(null));
        assertTrue(deleted.isEmpty());
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
        Session existing = manager.create(null, null, null);
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
    @DisplayName("应用压缩要落盘，且落盘的那一份已经带着摘要与边界")
    void applyCompaction_should_persist() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        Session session = manager.create(null, null, null);
        String sessionId = session.getSessionId();
        manager.appendMessage(sessionId, LlmMessage.user("一"), null);
        manager.appendMessage(sessionId, LlmMessage.user("二"), null);
        String boundary = manager.messagesOf(sessionId).get(0).getMessageId();

        manager.applyCompaction(sessionId, "摘要正文", boundary, 0);

        SessionSnapshot last = persisted.get(persisted.size() - 1);
        assertTrue(last.getCompaction() != null);
        assertEquals("摘要正文", last.getCompaction().getSummary());
        assertEquals(boundary, last.getCompaction().getBoundaryMessageId());
        assertEquals(2, last.getMessages().size(), "压缩是非破坏式的：消息一条都不删");
    }

    @Test
    @DisplayName("压缩边界必须指向会话里真实存在的消息，否则当场抛错")
    void applyCompaction_should_reject_unknownBoundary() {
        String sessionId = manager.create(null, null, null).getSessionId();

        assertThrows(JellyfishException.class,
                () -> manager.applyCompaction(sessionId, "摘要", "ghost", 0));
    }

    @Test
    @DisplayName("压缩落盘失败必须上抛：内存里已推进的边界会等下一次落盘补上")
    void applyCompaction_should_propagate_whenPersistFails() {
        String sessionId = manager.create(null, null, null).getSessionId();
        manager.appendMessage(sessionId, LlmMessage.user("一"), null);
        String boundary = manager.messagesOf(sessionId).get(0).getMessageId();
        extensions.contribute("broken", SessionPersistRequest.class, null, request -> {
            throw new JellyfishException("磁盘满了");
        }, RegisterOptions.DEFAULT);

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> manager.applyCompaction(sessionId, "摘要", boundary, 0));

        assertEquals("磁盘满了", error.getMessage());
    }

    @Test
    @DisplayName("不产生消息的用量也要落盘：压缩的 token 花在会话之外")
    void recordUsage_should_persistWithoutAddingMessage() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();

        manager.recordUsage(sessionId, new zcd.jellyfish.infra.llm.LlmUsage(10, 5, 15));

        SessionSnapshot last = persisted.get(persisted.size() - 1);
        assertEquals(15L, last.getUsage().getTotalTokens());
        assertEquals(0, last.getMessages().size());
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

    @Test
    @DisplayName("回合内的消息追加只标脏，一条都不落盘")
    void appendMessage_should_defer_whenTurnInProgress() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();

        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("一"), null);
        manager.appendMessage(sessionId, LlmMessage.assistant("二"), null);

        assertTrue(persisted.isEmpty(), "回合内不逐条落盘，结束后才落一次");
    }

    @Test
    @DisplayName("整个回合只落一次，快照带着回合内的全部消息")
    void flush_should_persistOnceForWholeTurn() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();

        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("一"), null);
        manager.appendMessage(sessionId, LlmMessage.assistant("二"), null);
        manager.flush(sessionId);

        assertEquals(1, persisted.size());
        assertEquals(2, persisted.get(0).getMessages().size());
    }

    @Test
    @DisplayName("flush 之后的追加回到即时落盘，不会一直攒着")
    void appendMessage_should_persistImmediately_afterFlush() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();
        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("一"), null);
        manager.flush(sessionId);

        manager.appendMessage(sessionId, LlmMessage.assistant("二"), null);

        assertEquals(2, persisted.size());
    }

    @Test
    @DisplayName("flush 落盘失败只记 WARN，不上抛：回合已收敛，失败补救不了")
    void flush_should_notPropagate_whenPersistFails() {
        String sessionId = manager.create(null, null, null).getSessionId();
        extensions.contribute("broken", SessionPersistRequest.class, null, request -> {
            throw new JellyfishException("磁盘满了");
        }, RegisterOptions.DEFAULT);
        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("你好"), null);

        // 回合内追加不上抛（它本来就不落盘），真正会失败的是回合终结的 flush
        assertDoesNotThrow(() -> manager.flush(sessionId));
    }

    @Test
    @DisplayName("flush 失败后保留脏标记，关停时 flushAll 还能补一次")
    void flushAll_should_retry_whenFlushFailed() {
        boolean[] broken = {true};
        List<SessionSnapshot> persisted = new ArrayList<SessionSnapshot>();
        extensions.contribute("flaky", SessionPersistRequest.class, null, request -> {
            if (broken[0]) {
                throw new JellyfishException("磁盘满了");
            }
            persisted.add(request.getSnapshot());
            return null;
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null).getSessionId();
        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("你好"), null);
        manager.flush(sessionId);
        assertTrue(persisted.isEmpty(), "第一次 flush 失败了");

        broken[0] = false;
        manager.flushAll();

        assertEquals(1, persisted.size());
        assertEquals(1, persisted.get(0).getMessages().size());
    }

    @Test
    @DisplayName("flushAll 也能落下来的回合正在进行的会话")
    void flushAll_should_persistDirtySession_whenTurnStillInProgress() {
        List<SessionSnapshot> persisted = capturePersistedSnapshots();
        String sessionId = manager.create(null, null, null).getSessionId();
        manager.beginTurn(sessionId);
        manager.appendMessage(sessionId, LlmMessage.user("你好"), null);

        manager.flushAll();

        assertEquals(1, persisted.size());
    }

    @Test
    @DisplayName("flush 对不存在的会话与 null 都是幂等的，不抛错")
    void flush_should_beIdempotent_whenSessionMissing() {
        assertDoesNotThrow(() -> manager.flush("missing"));
        assertDoesNotThrow(() -> manager.flush(null));
    }

    @Test
    @DisplayName("同一会话的两次并发落盘必须串行：最后写下的必须是最新那份快照")
    void persist_should_serializeConcurrentWritesOnSameSession() throws InterruptedException {
        // Given：第一个落盘进到处理器里就停住，把并发窗口留给第二个线程
        List<Integer> written = Collections.synchronizedList(new ArrayList<Integer>());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        extensions.contribute("recorder", SessionPersistRequest.class, null, request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitQuietly(releaseFirst);
            }
            written.add(request.getSnapshot().getMessages().size());
            return null;
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null).getSessionId();

        // When：两个线程各自追加一条消息，走的都是即时落盘
        Thread first = new Thread(() -> manager.appendMessage(sessionId, LlmMessage.user("一"), null));
        first.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "第一个落盘没有进入处理器");
        Thread second = new Thread(() -> manager.appendMessage(sessionId, LlmMessage.user("二"), null));
        second.start();
        second.join(200L);

        // Then：第二个应当卡在同一会话的落盘锁上（连快照都还没捕获）
        assertTrue(second.isAlive(), "同一会话的第二次落盘应当被串行化");
        releaseFirst.countDown();
        first.join(5000L);
        second.join(5000L);

        // 落盘顺序 = 状态推进顺序，因此先写 1 条、再写 2 条；反过来就是「旧快照后写」丢更新
        assertEquals(Arrays.asList(1, 2), written);
    }

    @Test
    @DisplayName("删除与落盘共锁：在途的落盘不该在删除之后把会话又写回来")
    void delete_should_notBeOvertakenByInFlightPersist() throws InterruptedException {
        // Given
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        extensions.contribute("recorder", SessionPersistRequest.class, null, request -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitQuietly(releaseFirst);
            }
            order.add("persist");
            return null;
        }, RegisterOptions.DEFAULT);
        extensions.contribute("eraser", SessionDeleteRequest.class, null, request -> {
            order.add("delete");
            return null;
        }, RegisterOptions.DEFAULT);
        String sessionId = manager.create(null, null, null).getSessionId();

        // When：一次落盘卡在途中时删这个会话
        Thread persist = new Thread(() -> manager.appendMessage(sessionId, LlmMessage.user("一"), null));
        persist.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "落盘没有进入处理器");
        Thread delete = new Thread(() -> manager.delete(sessionId));
        delete.start();
        delete.join(200L);
        releaseFirst.countDown();
        persist.join(5000L);
        delete.join(5000L);

        // Then：删除排在落盘之后，不会出现「删完又被写回来」
        assertEquals(Arrays.asList("persist", "delete"), order);
    }

    /**
     * 等待一个闩锁；中断时恢复中断位，不让检查型异常泄漏到处理器签名里。
     *
     * @param latch 闩锁
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        SessionMessageSnapshot message = SessionMessageSnapshot.of("m-1", 1L, LlmMessage.ROLE_USER, "你好",
                null, null, null, null);
        return SessionSnapshot.of(sessionId, 1L, 2L, "标题", "coder", "openai", "gpt-4o",
                Collections.singletonList(message),
                new SessionUsageSnapshot(0L, 0L, 0L, 0L, 0L, 0L));
    }
}
