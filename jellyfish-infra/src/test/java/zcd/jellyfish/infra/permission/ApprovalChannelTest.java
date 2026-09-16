package zcd.jellyfish.infra.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalChannel} 的单元测试：钉住 fail-closed 的每一条路径与「只接受第一次结论」。
 * <p>
 * 跨线程交接用真实的两个线程验证（一个阻塞等待、一个裁决），因为这里唯一不能靠读代码确认的事
 * 就是「闩锁真的把结果交接过去了」。
 *
 * @author zcd
 */
@DisplayName("ApprovalChannel 审批通道")
class ApprovalChannelTest {

    /** 等待超时：够长到不会在测试里自然超时，又短到失败时不会挂死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 被测对象。 */
    private ApprovalChannel channel;

    @BeforeEach
    void setUp() {
        channel = new ApprovalChannel();
    }

    @Test
    @DisplayName("未挂审批者时立即拒绝，不阻塞等待")
    void request_should_deny_immediately_when_not_attached() {
        // When
        PermissionDecision decision = channel.request(pending(), TIMEOUT);

        // Then
        assertTrue(decision.isDenied());
        assertEquals(ApprovalChannel.NO_APPROVER, decision.getReason());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("无人取件时按超时拒绝，并且不留下待审批项")
    void request_should_deny_when_nobody_resolves_before_timeout() {
        // Given
        channel.attach();
        ApprovalChannel.Pending request = pending();

        // When：1 毫秒的超时，渲染线程不作答
        PermissionDecision decision = channel.request(request, Duration.ofMillis(1));

        // Then
        assertTrue(decision.isDenied());
        assertTrue(decision.getReason().contains("审批超时"), decision.getReason());
        assertFalse(channel.pending().isPresent(), "超时的请求必须从当前槽位摘掉，否则界面会一直显示它");
    }

    @Test
    @DisplayName("渲染线程批准后放行，且驳回结果随理由一起回填")
    void request_should_allow_when_approved() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();

        // When
        Thread worker = requestInBackground(pending(), result);
        ApprovalChannel.Pending shown = awaitPending();
        channel.resolve(shown.getId(), true);
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertNotNull(result.get());
        assertTrue(result.get().isAllowed());
        assertEquals(ApprovalChannel.APPROVED, result.get().getReason());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("渲染线程拒绝后判定为拒绝")
    void request_should_deny_when_rejected() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();

        // When
        Thread worker = requestInBackground(pending(), result);
        ApprovalChannel.Pending shown = awaitPending();
        channel.resolve(shown.getId(), false);
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertTrue(result.get().isDenied());
        assertEquals(ApprovalChannel.REJECTED, result.get().getReason());
    }

    @Test
    @DisplayName("同一条请求只接受第一次结论：超时之后再点批准不会把它改成放行")
    void resolve_should_be_idempotent() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();

        // When：先超时收敛，再补一次「批准」
        Thread worker = requestInBackground(pending(), Duration.ofMillis(100), result);
        ApprovalChannel.Pending shown = awaitPending();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        channel.resolve(shown.getId(), true);
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then：仍是超时拒绝
        assertTrue(result.get().isDenied());
        assertTrue(result.get().getReason().contains("审批超时"), result.get().getReason());
    }

    @Test
    @DisplayName("未知 id 的结论被忽略，不改动当前待审批项")
    void resolve_should_ignore_unknown_id() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();
        Thread worker = requestInBackground(pending(), result);
        ApprovalChannel.Pending shown = awaitPending();

        // When
        channel.resolve("not-a-real-id", true);

        // Then：仍然挂着，等待真正的裁决
        assertEquals(shown.getId(), channel.pending().orElse(null).getId());
        channel.resolve(shown.getId(), false);
        worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("关闭通道时排空未决请求：不等超时就把等待线程放行")
    void detach_should_release_pending_requests() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();
        Thread worker = requestInBackground(pending(), result);
        awaitPending();

        // When
        channel.detach();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertFalse(worker.isAlive(), "关闭通道后等待线程必须立刻收敛，而不是等到超时");
        assertTrue(result.get().isDenied());
        assertEquals(ApprovalChannel.DETACHED, result.get().getReason());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("关闭后新请求立即拒绝，不再进入队列")
    void request_should_deny_when_detached() {
        // Given
        channel.attach();
        channel.detach();

        // When
        PermissionDecision decision = channel.request(pending(), TIMEOUT);

        // Then
        assertTrue(decision.isDenied());
        assertEquals(ApprovalChannel.NO_APPROVER, decision.getReason());
    }

    @Test
    @DisplayName("中断等待时恢复中断位并拒绝")
    void request_should_restore_interrupt_flag_when_interrupted() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> result = new AtomicReference<PermissionDecision>();
        Thread worker = requestInBackground(pending(), result);
        awaitPending();

        // When
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // Then
        assertTrue(result.get().isDenied());
        assertEquals(ApprovalChannel.INTERRUPTED, result.get().getReason());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    @DisplayName("一次只展示一个：并发请求后来的排队，前一个裁决后才轮到它")
    void request_should_queue_second_request_until_first_resolved() throws Exception {
        // Given
        channel.attach();
        AtomicReference<PermissionDecision> first = new AtomicReference<PermissionDecision>();
        AtomicReference<PermissionDecision> second = new AtomicReference<PermissionDecision>();
        Thread firstThread = requestInBackground(pending(), first);
        ApprovalChannel.Pending shown = awaitPending();
        Thread secondThread = requestInBackground(pending(), second);
        // 给第二个线程一点时间进入排队（而不是立刻被拒绝）
        Thread.sleep(50L);

        // Then：界面仍然只看到第一条
        assertEquals(shown.getId(), channel.pending().orElse(null).getId());

        // When：裁决第一条，第二条自动顶上来
        channel.resolve(shown.getId(), true);
        ApprovalChannel.Pending next = awaitPending();
        assertFalse(shown.getId().equals(next.getId()));
        channel.resolve(next.getId(), false);

        firstThread.join(TimeUnit.SECONDS.toMillis(5));
        secondThread.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(first.get().isAllowed());
        assertTrue(second.get().isDenied());
    }

    @Test
    @DisplayName("排队超出上限立即拒绝，不无限堆积")
    void request_should_deny_when_queue_is_full() throws Exception {
        // Given：先占住当前槽位，再逐个填满排队区。
        // 每一步都等到真的入队再放下一个——若只是并发启动一堆线程，先到的探测请求会临时占掉一个位，
        // 把本该排队的请求挤成拒绝，测试结果就不再由被测逻辑决定
        channel.attach();
        AtomicReference<PermissionDecision> ignored = new AtomicReference<PermissionDecision>();
        List<Thread> holders = new ArrayList<Thread>();
        holders.add(requestInBackground(pending(), TIMEOUT, ignored));
        awaitPending();
        for (int i = 0; i < ApprovalChannel.MAX_WAITING; i++) {
            holders.add(requestInBackground(pending(), TIMEOUT, ignored));
            awaitWaiting(i + 1);
        }

        // When
        PermissionDecision decision = channel.request(pending(), TIMEOUT);

        // Then
        assertTrue(decision.isDenied());
        assertEquals(ApprovalChannel.QUEUE_FULL, decision.getReason());

        // 收尾：关闭通道把所有等待线程放行，避免测试进程挂住
        channel.detach();
        for (Thread holder : holders) {
            holder.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    @DisplayName("Pending 防御性拷贝参数，外部改动不影响已挂起的请求")
    void pending_should_copy_arguments_defensively() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a.txt");
        ApprovalChannel.Pending request = new ApprovalChannel.Pending("session-1", "agent-a", "write_file",
                arguments, PermissionMode.PLAN, "策略要求审批");

        // When
        arguments.put("path", "b.txt");

        // Then
        assertEquals("a.txt", request.getArguments().get("path"));
        assertEquals("write_file", request.getToolName());
        assertEquals("agent-a", request.getAgentId());
        assertEquals("session-1", request.getSessionId());
        assertEquals(PermissionMode.PLAN, request.getMode());
        assertEquals("策略要求审批", request.getReason());
        assertNotNull(request.getId());
        assertThrows(UnsupportedOperationException.class, () -> request.getArguments().put("x", "y"));
    }

    @Test
    @DisplayName("request 与 timeout 为空时快速失败")
    void request_should_reject_null_arguments() {
        // When / Then
        assertThrows(NullPointerException.class, () -> channel.request(null, TIMEOUT));
        assertThrows(NullPointerException.class, () -> channel.request(pending(), null));
    }

    /**
     * 构造一条待审批请求。
     *
     * @return 请求
     */
    private static ApprovalChannel.Pending pending() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "a.txt");
        return new ApprovalChannel.Pending("session-1", "agent-a", "write_file", arguments,
                PermissionMode.NORMAL, "agent 策略要求人工审批该工具");
    }

    /**
     * 在后台线程发起一次审批请求并记录结论（使用测试缺省超时）。
     *
     * @param request 请求
     * @param result  结论出口
     * @return 已启动的线程
     */
    private Thread requestInBackground(ApprovalChannel.Pending request,
                                      AtomicReference<PermissionDecision> result) {
        return requestInBackground(request, TIMEOUT, result);
    }

    /**
     * 在后台线程发起一次审批请求并记录结论。
     *
     * @param request 请求
     * @param timeout 等待超时
     * @param result  结论出口
     * @return 已启动的线程
     */
    private Thread requestInBackground(ApprovalChannel.Pending request, Duration timeout,
                                      AtomicReference<PermissionDecision> result) {
        Thread thread = new Thread(() -> result.set(channel.request(request, timeout)),
                "approval-test-worker");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等到请求真的挂到当前槽位上。
     *
     * @return 当前待审批请求
     * @throws Exception 超时或中断
     */
    private ApprovalChannel.Pending awaitPending() throws Exception {
        for (int i = 0; i < 200; i++) {
            ApprovalChannel.Pending current = channel.pending().orElse(null);
            if (current != null) {
                return current;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("审批请求未在预期时间内挂上");
    }

    /**
     * 等到排队数达到预期值。
     *
     * @param expected 预期排队数
     * @throws Exception 超时或中断
     */
    private void awaitWaiting(int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (channel.waitingCount() >= expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("排队数未在预期时间内达到 " + expected);
    }
}
