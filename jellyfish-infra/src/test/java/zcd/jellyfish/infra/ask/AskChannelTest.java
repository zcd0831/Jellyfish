package zcd.jellyfish.infra.ask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.infra.config.AskSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskChannel} 的单元测试：钉住每一条终态路径与「只接受第一次结论」。
 * <p>
 * 跨线程交接（一个线程阻塞等待、另一个线程作答）用真实线程验证，因为这里唯一不能靠读代码确认的
 * 事情就是「闩锁真的把答案交接过去了」。形态与 {@code ApprovalChannelTest} 对称，
 * 但有一处口径必须单独钉住：<b>等人不来是「问不到」，不是「拒绝」</b>。
 *
 * @author zcd
 */
@DisplayName("AskChannel 提问通道")
class AskChannelTest {

    /** 等待超时：够长到不会在测试里自然超时，又短到失败时不会挂死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 运行时配置：只有超时值会被读到。 */
    private RuntimeConfig runtimeConfig;

    /** 被测对象。 */
    private AskChannel channel;

    @BeforeEach
    void setUp() {
        runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings());
        channel = new AskChannel(runtimeConfig);
    }

    @Test
    @DisplayName("未挂答复者时立即给出「问不到」，而不是拒绝")
    void request_should_be_unavailable_immediately_when_not_attached() {
        // When
        AskAnswer answer = channel.request(ask(), TIMEOUT);

        // Then：这是与审批最重要的一处口径差异——不涉及权限，因此不能说成「拒绝」
        assertEquals(AskAnswer.Status.UNAVAILABLE, answer.getStatus());
        assertEquals(AskChannel.NO_ASKER, answer.getReason());
        assertFalse(answer.isAnswered());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("无人作答时按超时收敛，并且不留下待答项")
    void request_should_time_out_when_nobody_answers() {
        // Given
        channel.attach();

        // When：1 毫秒的超时，渲染线程不作答
        AskAnswer answer = channel.request(ask(), Duration.ofMillis(1));

        // Then
        assertEquals(AskAnswer.Status.TIMED_OUT, answer.getStatus());
        assertTrue(answer.getReason().contains("没有回答"), answer.getReason());
        assertFalse(channel.pending().isPresent(), "超时的提问必须从当前槽位摘掉，否则界面会一直显示它");
    }

    @Test
    @DisplayName("配置成永不超时（timeoutSeconds=0）时一直等到有人作答，而不是立刻超时")
    void ask_should_wait_forever_when_configured_never_timeout() throws Exception {
        // Given：0 是「无限」而不是「零秒」——若被当成零秒，这次等待早已结束，槽位上不会有待答项
        Mockito.when(runtimeConfig.getAskSettings())
                .thenReturn(new AskSettings(AskSettings.INFINITE_TIMEOUT_SECONDS));
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When
        Thread worker = askInBackgroundForever(ask(), result);
        AskChannel.Pending shown = awaitPending();
        assertNull(result.get(), "没人作答时这条等待不该自己结束");
        channel.resolve(shown.getId(), AskAnswer.answered("1"));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertNotNull(result.get());
        assertTrue(result.get().isAnswered());
        assertEquals("1", result.get().getOptionId());
    }

    @Test
    @DisplayName("永不超时的等待在通道关闭时立刻放开，不会真的挂死")
    void waitForever_should_be_released_when_detached() throws Exception {
        // Given：外壳退出（detach）是无限等待的两条出口之一，另一条是回合被取消
        Mockito.when(runtimeConfig.getAskSettings())
                .thenReturn(new AskSettings(AskSettings.INFINITE_TIMEOUT_SECONDS));
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When
        Thread worker = askInBackgroundForever(ask(), result);
        awaitPending();
        channel.detach();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then：拿到「问不到人」，回合照常往下走
        assertFalse(worker.isAlive(), "通道关闭必须把等待中的提问一起收敛");
        assertNotNull(result.get());
        assertEquals(AskAnswer.Status.UNAVAILABLE, result.get().getStatus());
        assertEquals(AskChannel.DETACHED, result.get().getReason());
    }

    @Test
    @DisplayName("选中候选项时把选项标识回填给等待线程")
    void request_should_return_option_id_when_answered_with_choice() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When
        Thread worker = askInBackground(ask(), result);
        AskChannel.Pending shown = awaitPending();
        channel.resolve(shown.getId(), AskAnswer.answered("2"));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertNotNull(result.get());
        assertTrue(result.get().isAnswered());
        assertEquals("2", result.get().getOptionId());
        assertNull(result.get().getText());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("用户自己输入时把原文回填给等待线程")
    void request_should_return_text_when_answered_with_custom_input() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When
        Thread worker = askInBackground(ask(), result);
        AskChannel.Pending shown = awaitPending();
        channel.resolve(shown.getId(), AskAnswer.custom("都不合适，改成先做 Server"));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertTrue(result.get().isAnswered());
        assertNull(result.get().getOptionId());
        assertEquals("都不合适，改成先做 Server", result.get().getText());
    }

    @Test
    @DisplayName("用户放弃作答时等待线程拿到 CANCELLED，回合据此继续")
    void request_should_return_cancelled_when_user_gives_up() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When
        Thread worker = askInBackground(ask(), result);
        AskChannel.Pending shown = awaitPending();
        channel.resolve(shown.getId(), AskAnswer.cancelled(AskChannel.CANCELLED));
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertEquals(AskAnswer.Status.CANCELLED, result.get().getStatus());
        assertFalse(result.get().isAnswered());
    }

    @Test
    @DisplayName("同一条提问只接受第一次结论：超时之后再作答不会把它改成有答案")
    void resolve_should_be_idempotent() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();

        // When：先超时收敛，再补一次作答
        Thread worker = askInBackground(ask(), Duration.ofMillis(100), result);
        AskChannel.Pending shown = awaitPending();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        boolean late = channel.resolve(shown.getId(), AskAnswer.answered("1"));

        // Then：仍是超时，且补的那一次被当作无事发生
        assertFalse(late);
        assertEquals(AskAnswer.Status.TIMED_OUT, result.get().getStatus());
    }

    @Test
    @DisplayName("未知 id 的答复被忽略，不改动当前待答项")
    void resolve_should_ignore_unknown_id() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(ask(), result);
        AskChannel.Pending shown = awaitPending();

        // When
        boolean resolved = channel.resolve("not-a-real-id", AskAnswer.answered("1"));

        // Then：仍挂着，等待真正的答复
        assertFalse(resolved);
        assertEquals(shown.getId(), channel.pending().orElse(null).getId());
        channel.resolve(shown.getId(), AskAnswer.cancelled(null));
        worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("空 id 与空答复一律不受理")
    void resolve_should_reject_null_arguments() {
        // Given
        channel.attach();

        // When / Then
        assertFalse(channel.resolve(null, AskAnswer.answered("1")));
        assertFalse(channel.resolve("id", null));
    }

    @Test
    @DisplayName("关闭通道时排空未决提问：不等超时就把等待线程放行")
    void detach_should_release_pending_requests() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(ask(), result);
        awaitPending();

        // When
        channel.detach();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertFalse(worker.isAlive(), "关闭通道后等待线程必须立刻收敛，而不是等到超时");
        assertEquals(AskAnswer.Status.UNAVAILABLE, result.get().getStatus());
        assertEquals(AskChannel.DETACHED, result.get().getReason());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("关闭后新提问立即给出「问不到」，不再进入队列")
    void request_should_be_unavailable_when_detached() {
        // Given
        channel.attach();
        channel.detach();

        // When
        AskAnswer answer = channel.request(ask(), TIMEOUT);

        // Then
        assertEquals(AskAnswer.Status.UNAVAILABLE, answer.getStatus());
        assertEquals(AskChannel.NO_ASKER, answer.getReason());
    }

    @Test
    @DisplayName("中断等待时恢复中断位并给出 CANCELLED")
    void request_should_restore_interrupt_flag_when_interrupted() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> result = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(ask(), result);
        awaitPending();

        // When
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertEquals(AskAnswer.Status.CANCELLED, result.get().getStatus());
        assertEquals(AskChannel.INTERRUPTED, result.get().getReason());
    }

    @Test
    @DisplayName("同一会话的第二个提问进排队区，排队中的那一条不能作答")
    void resolve_should_only_affect_head_slot() throws Exception {
        // Given：第一条占住头槽位，第二条排队
        channel.attach();
        AtomicReference<AskAnswer> first = new AtomicReference<AskAnswer>();
        AtomicReference<AskAnswer> second = new AtomicReference<AskAnswer>();
        Thread a = askInBackground(askFor("s1"), first);
        AskChannel.Pending shown = awaitPending("s1");
        Thread b = askInBackground(askFor("s1"), second);
        awaitWaiting("s1", 1);
        AskChannel.Pending queued = channel.pendingAsks("s1").get(1);

        // When：尝试直接答排队中的那一条
        boolean resolved = channel.resolve(queued.getId(), AskAnswer.answered("1"));

        // Then：无效——能作答的始终只有头槽位
        assertFalse(resolved);
        assertEquals(2, channel.pendingAsks("s1").size());
        channel.resolve(shown.getId(), AskAnswer.cancelled(null));
        a.join(TimeUnit.SECONDS.toMillis(5));
        // 头槽位推进后，排队的那一条成为新的头槽位
        AskChannel.Pending promoted = awaitPending("s1");
        assertEquals(queued.getId(), promoted.getId());
        channel.resolve(promoted.getId(), AskAnswer.answered("2"));
        b.join(TimeUnit.SECONDS.toMillis(5));
        assertEquals("2", second.get().getOptionId());
    }

    @Test
    @DisplayName("排队超过上限时新提问给出「问不到」，不无限堆积")
    void request_should_be_unavailable_when_queue_is_full() throws Exception {
        // Given：头槽位 + MAX_WAITING 条排队都占满
        channel.attach();
        List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i <= AskChannel.MAX_WAITING; i++) {
            AtomicReference<AskAnswer> sink = new AtomicReference<AskAnswer>();
            workers.add(askInBackground(askFor("s1"), sink));
        }
        awaitWaiting("s1", AskChannel.MAX_WAITING);

        // When
        AskAnswer answer = channel.request(askFor("s1"), TIMEOUT);

        // Then
        assertEquals(AskAnswer.Status.UNAVAILABLE, answer.getStatus());
        assertEquals(AskChannel.QUEUE_FULL, answer.getReason());
        for (Thread worker : workers) {
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    @DisplayName("会话之间互不排队：A 挂着提问不影响 B 立刻被作答")
    void heads_should_be_isolated_per_session() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> first = new AtomicReference<AskAnswer>();
        AtomicReference<AskAnswer> second = new AtomicReference<AskAnswer>();
        Thread a = askInBackground(askFor("s1"), first);
        awaitPending("s1");
        Thread b = askInBackground(askFor("s2"), second);
        AskChannel.Pending other = awaitPending("s2");

        // Then：B 有自己的头槽位，不必排在 A 后面
        assertEquals(0, channel.waitingCount("s2"));
        channel.resolve(other.getId(), AskAnswer.answered("1"));
        b.join(TimeUnit.SECONDS.toMillis(5));
        assertEquals("1", second.get().getOptionId());
        assertNull(first.get(), "A 仍在等待，不该被 B 的作答唤醒");
        channel.resolve(channel.pending("s1").orElse(null).getId(), AskAnswer.cancelled(null));
        a.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("跨会话取件拿到最早的那一条，供晚到客户端使用")
    void pending_should_return_oldest_across_sessions() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> sink = new AtomicReference<AskAnswer>();
        Thread a = askInBackground(askFor("s1"), sink);
        awaitPending("s1");

        // Then：只有会话 s1 挂着时，跨会话取件就是它
        assertEquals("s1", channel.pending().orElse(null).getSessionId());

        // When：答掉之后跨会话就没有待答项了
        channel.resolve(channel.pending("s1").orElse(null).getId(), AskAnswer.cancelled(null));
        a.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("ask 读的是当前配置的超时值")
    void ask_should_read_timeout_from_configuration() {
        // Given：配置成 1 秒
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings(1));
        channel.attach();

        // When：没人作答
        long started = System.currentTimeMillis();
        AskAnswer answer = channel.ask(ask());

        // Then：按 1 秒（而不是缺省 120 秒）收敛
        long elapsed = System.currentTimeMillis() - started;
        assertEquals(AskAnswer.Status.TIMED_OUT, answer.getStatus());
        assertTrue(elapsed < 10_000L, "应按配置的 1 秒收敛，实际耗时 " + elapsed + " 毫秒");
    }

    @Test
    @DisplayName("空请求一律当场拒绝")
    void request_should_reject_null_request() {
        // Given
        channel.attach();

        // When / Then
        assertThrows(NullPointerException.class, () -> channel.request(null, TIMEOUT));
        assertThrows(NullPointerException.class, () -> channel.ask(null));
    }

    @Test
    @DisplayName("待答项带着提问的全部展示信息")
    void pending_should_carry_request_details() throws Exception {
        // Given
        channel.attach();
        AtomicReference<AskAnswer> sink = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(askFor("s1"), sink);

        // When
        AskChannel.Pending shown = awaitPending("s1");

        // Then
        assertEquals("ask_user", shown.getRequest().getSource());
        assertEquals("先做通道还是先做 UI？", shown.getRequest().getQuestion());
        assertEquals(2, shown.getRequest().getOptions().size());
        assertEquals("a", shown.getRequest().option("a").getOptionId());
        assertEquals("s1", shown.getSessionId());
        assertTrue(shown.getTimestamp() > 0L);
        channel.resolve(shown.getId(), AskAnswer.cancelled(null));
        worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    /**
     * 构造一条待答提问（会话 s1）。
     *
     * @return 提问请求
     */
    private static AskRequest ask() {
        return askFor("s1");
    }

    /**
     * 构造一条指定会话的待答提问。
     *
     * @param sessionId 会话标识
     * @return 提问请求
     */
    private static AskRequest askFor(String sessionId) {
        List<AskOption> options = Arrays.asList(
                AskOption.of("a", "先做通道", "内核与插件先落地"),
                AskOption.of("b", "先做 UI", null));
        return AskRequest.of("ask_user", sessionId, "先做通道还是先做 UI？", options);
    }

    /**
     * 在后台线程发起一次提问并记录答复。
     *
     * @param request 提问请求
     * @param result  答复出口
     * @return 已启动的线程
     */
    private Thread askInBackground(AskRequest request, AtomicReference<AskAnswer> result) {
        return askInBackground(request, TIMEOUT, result);
    }

    /**
     * 在后台线程发起一次提问并记录答复。
     *
     * @param request 提问请求
     * @param timeout 等待超时
     * @param result  答复出口
     * @return 已启动的线程
     */
    private Thread askInBackground(AskRequest request, Duration timeout, AtomicReference<AskAnswer> result) {
        Thread thread = new Thread(() -> result.set(channel.request(request, timeout)), "ask-test-worker");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 在后台线程发起一次「永不超时」的提问并记录答复。
     * <p>
     * 走的是 {@link AskChannel#ask}（读配置决定有没有上限），因此它同时验证了「配置里的 0 被当成
     * 无限而不是零秒」这件事。
     *
     * @param request 提问请求
     * @param result  答复出口
     * @return 已启动的线程
     */
    private Thread askInBackgroundForever(AskRequest request, AtomicReference<AskAnswer> result) {
        Thread thread = new Thread(() -> result.set(channel.ask(request)), "ask-test-worker-forever");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等到提问真的挂到当前槽位上。
     *
     * @return 当前待答提问
     * @throws Exception 超时或中断
     */
    private AskChannel.Pending awaitPending() throws Exception {
        return awaitPending(null);
    }

    /**
     * 等到指定会话真的挂上当前槽位。
     *
     * @param sessionId 会话标识，可为 {@code null}（跨会话最早的那一条）
     * @return 当前待答提问
     * @throws Exception 超时或中断
     */
    private AskChannel.Pending awaitPending(String sessionId) throws Exception {
        for (int i = 0; i < 200; i++) {
            AskChannel.Pending current = sessionId == null
                    ? channel.pending().orElse(null)
                    : channel.pending(sessionId).orElse(null);
            if (current != null) {
                return current;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("提问未在预期时间内挂上: sessionId=" + sessionId);
    }

    /**
     * 等到指定会话的排队数达到预期值。
     *
     * @param sessionId 会话标识
     * @param expected  预期排队数
     * @throws Exception 超时或中断
     */
    private void awaitWaiting(String sessionId, int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (channel.waitingCount(sessionId) >= expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("会话 " + sessionId + " 的排队数未在预期时间内达到 " + expected);
    }
}
