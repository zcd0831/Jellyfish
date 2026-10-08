package zcd.jellyfish.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.http.ApiException;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalBridge} 的头槽位过滤与裁决契约。
 * <p>
 * 用真的 {@link ApprovalChannel}（infra，无外部依赖）：审批的难点全在「每会话一个头槽位 + FIFO 队列 +
 * 只对头生效」这套既有语义上，mock 掉通道等于把被测对象赖以成立的约束抽走。
 *
 * @author zcd
 */
class ApprovalBridgeTest {

    /** 真审批通道。 */
    private ApprovalChannel channel;

    /** 审批桥。 */
    private ApprovalBridge bridge;

    @BeforeEach
    void setUp() {
        channel = new ApprovalChannel();
        channel.attach();
        bridge = new ApprovalBridge(channel);
    }

    /**
     * 在后台线程发起一次审批请求，让头槽位被占上。
     *
     * @param sessionId 会话标识
     * @param toolName  工具名
     * @return 请求标识
     * @throws InterruptedException 等待头槽位就位时被中断
     */
    private String occupyHead(String sessionId, String toolName) throws InterruptedException {
        ApprovalChannel.Pending pending = new ApprovalChannel.Pending(sessionId, "coder", toolName,
                java.util.Collections.<String, Object>singletonMap("path", "/a"), "需要确认");
        Thread requester = new Thread(() -> channel.request(pending, Duration.ofSeconds(5)), "approval-requester");
        requester.setDaemon(true);
        requester.start();
        for (int i = 0; i < 100 && !channel.pending(sessionId).isPresent(); i++) {
            Thread.sleep(10);
        }
        assertTrue(channel.pending(sessionId).isPresent(), "头槽位未就位");
        return pending.getId();
    }

    @Test
    void headFor_should_return_dto_when_head_belongs_to_session() throws InterruptedException {
        String requestId = occupyHead("s1", "write_file");

        Optional<ApprovalDto> head = bridge.headFor("s1");

        assertTrue(head.isPresent());
        assertEquals(requestId, head.get().getRequestId());
        assertEquals("write_file", head.get().getToolName());
    }

    @Test
    void headFor_should_return_empty_when_head_belongs_to_other_session() throws InterruptedException {
        occupyHead("s1", "write_file");

        assertFalse(bridge.headFor("s2").isPresent());
    }

    @Test
    void headFor_should_return_each_session_head_independently() throws InterruptedException {
        String first = occupyHead("s1", "write_file");
        String second = occupyHead("s2", "bash");

        // 两个会话各有一个头槽位，互不阻塞（全局单槽位时代第二个只能排队）
        assertEquals(first, bridge.headFor("s1").get().getRequestId());
        assertEquals(second, bridge.headFor("s2").get().getRequestId());
    }

    @Test
    void resolve_should_throw_404_when_request_id_is_not_head() throws InterruptedException {
        occupyHead("s1", "write_file");

        ApiException error = assertThrows(ApiException.class, () -> bridge.resolve("missing", true));

        assertEquals(404, error.getStatus());
        assertEquals("APPROVAL_NOT_FOUND", error.getCode());
    }

    @Test
    void resolve_should_clear_head_when_approved() throws InterruptedException {
        String requestId = occupyHead("s1", "write_file");

        bridge.resolve(requestId, true);

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void resolveFor_should_resolve_when_request_isHeadOfThatSession() throws InterruptedException {
        String requestId = occupyHead("s1", "write_file");

        bridge.resolveFor("s1", requestId, true);

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void resolveFor_should_reject_when_request_belongsToAnotherSession() throws InterruptedException {
        String requestId = occupyHead("s1", "write_file");

        // 拿别人的 requestId 配自己的会话：内核会照办，因此这一步必须在桥里拦住
        ApiException error = assertThrows(ApiException.class,
                () -> bridge.resolveFor("s2", requestId, true));

        assertEquals(404, error.getStatus());
        assertEquals("APPROVAL_NOT_FOUND", error.getCode());
        assertTrue(bridge.headFor("s1").isPresent(), "别的会话的裁决不该被这次调用落定");
    }

    @Test
    void rejectIfPending_should_deny_when_request_still_pending() throws InterruptedException {
        String requestId = occupyHead("s1", "write_file");

        bridge.rejectIfPending(requestId);

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void rejectIfPending_should_do_nothing_when_request_not_head() throws InterruptedException {
        occupyHead("s1", "write_file");

        bridge.rejectIfPending("missing");

        assertTrue(bridge.headFor("s1").isPresent());
    }

    @Test
    void detach_should_clear_head_when_called() throws InterruptedException {
        occupyHead("s1", "write_file");

        bridge.detach();

        assertFalse(bridge.headFor("s1").isPresent());
    }
}
