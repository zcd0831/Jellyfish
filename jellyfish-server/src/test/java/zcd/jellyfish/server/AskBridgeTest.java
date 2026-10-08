package zcd.jellyfish.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.config.AskSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.server.dto.AskDto;
import zcd.jellyfish.server.http.ApiException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskBridge} 的头槽位过滤与作答契约。
 * <p>
 * 用真的 {@link AskChannel}（infra，除配置外无外部依赖）：提问的难点全在「每会话一个头槽位 +
 * FIFO 队列 + 只对头生效」这套既有语义上，mock 掉通道等于把被测对象赖以成立的约束抽走。
 * 形态与 {@code ApprovalBridgeTest} 对称，差别只在载荷与「两种作答形状」。
 *
 * @author zcd
 */
class AskBridgeTest {

    /** 真提问通道。 */
    private AskChannel channel;

    /** 提问桥。 */
    private AskBridge bridge;

    @BeforeEach
    void setUp() {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings());
        channel = new AskChannel(runtimeConfig);
        channel.attach();
        bridge = new AskBridge(channel);
    }

    /**
     * 在后台线程发起一次提问，让头槽位被占上。
     *
     * @param sessionId 会话标识
     * @param question  问题原文
     * @throws InterruptedException 等待头槽位就位时被中断
     */
    private void occupyHead(String sessionId, String question) throws InterruptedException {
        AskRequest request = AskRequest.of("ask_user", sessionId, question, Arrays.asList(
                AskOption.of("a", "先做通道", "内核与插件先落地"),
                AskOption.of("b", "先做 UI", null)));
        Thread requester = new Thread(() -> channel.request(request, Duration.ofSeconds(5)), "ask-requester");
        requester.setDaemon(true);
        requester.start();
        for (int i = 0; i < 100 && !channel.pending(sessionId).isPresent(); i++) {
            Thread.sleep(10);
        }
        assertTrue(channel.pending(sessionId).isPresent(), "头槽位未就位");
    }

    /**
     * 取当前头槽位的请求标识。
     *
     * @param sessionId 会话标识
     * @return 请求标识
     */
    private String headId(String sessionId) {
        return channel.pending(sessionId).orElse(null).getId();
    }

    @Test
    void headFor_should_return_dto_when_head_belongs_to_session() throws InterruptedException {
        occupyHead("s1", "先做通道还是先做 UI？");

        Optional<AskDto> head = bridge.headFor("s1");

        assertTrue(head.isPresent());
        assertEquals("先做通道还是先做 UI？", head.get().getQuestion());
        assertEquals("ask_user", head.get().getSource());
        assertEquals("s1", head.get().getSessionId());
        assertEquals(2, head.get().getOptions().size());
        assertEquals("a", head.get().getOptions().get(0).getOptionId());
        assertEquals("先做通道", head.get().getOptions().get(0).getLabel());
        assertEquals("内核与插件先落地", head.get().getOptions().get(0).getDescription());
    }

    @Test
    void headFor_should_return_empty_when_head_belongs_to_other_session() throws InterruptedException {
        occupyHead("s1", "问题？");

        assertFalse(bridge.headFor("s2").isPresent());
    }

    @Test
    void headFor_should_return_each_session_head_independently() throws InterruptedException {
        occupyHead("s1", "第一个问题？");
        occupyHead("s2", "第二个问题？");

        // 两个会话各有一个头槽位，互不阻塞（全局单槽位时代第二个只能排队）
        assertEquals("第一个问题？", bridge.headFor("s1").get().getQuestion());
        assertEquals("第二个问题？", bridge.headFor("s2").get().getQuestion());
        assertNotEquals(headId("s1"), headId("s2"));
    }

    @Test
    void resolveFor_should_answer_when_request_isHeadOfThatSession() throws InterruptedException {
        occupyHead("s1", "唯一的问题？");

        bridge.resolveFor("s1", headId("s1"), AskAnswer.answered("a"));

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void resolveFor_should_reject_when_request_belongsToAnotherSession() throws InterruptedException {
        occupyHead("s1", "问题？");

        // 拿别人的 requestId 配自己的会话：内核会照办，而答案会成为那个会话的工具结果原文
        ApiException error = assertThrows(ApiException.class,
                () -> bridge.resolveFor("s2", headId("s1"), AskAnswer.custom("注入的文本")));

        assertEquals(404, error.getStatus());
        assertEquals("ASK_NOT_FOUND", error.getCode());
        assertTrue(bridge.headFor("s1").isPresent(), "别的会话的提问不该被这次调用答掉");
    }

    @Test
    void resolve_should_throw_404_when_request_id_is_not_head() throws InterruptedException {
        occupyHead("s1", "问题？");

        ApiException error = assertThrows(ApiException.class,
                () -> bridge.resolve("missing", AskAnswer.answered("a")));

        assertEquals(404, error.getStatus());
        assertEquals("ASK_NOT_FOUND", error.getCode());
    }

    @Test
    void resolve_should_clear_head_and_hand_answer_to_waiter() throws Exception {
        // Given：把答复出口接出来，验证桥确实把答案交给了等待线程
        AtomicReference<AskAnswer> answered = new AtomicReference<AskAnswer>();
        AskRequest request = AskRequest.of("ask_user", "s1", "问题？", Arrays.asList(
                AskOption.of("a", "先做通道", null), AskOption.of("b", "先做 UI", null)));
        Thread worker = new Thread(() -> answered.set(channel.request(request, Duration.ofSeconds(5))),
                "ask-worker");
        worker.setDaemon(true);
        worker.start();
        for (int i = 0; i < 100 && !channel.pending("s1").isPresent(); i++) {
            Thread.sleep(10);
        }

        // When
        bridge.resolve(headId("s1"), AskAnswer.custom("换一个做法"));
        worker.join(2000L);

        // Then
        assertFalse(bridge.headFor("s1").isPresent());
        assertEquals("换一个做法", answered.get().getText());
    }

    @Test
    void cancelIfPending_should_converge_when_request_still_pending() throws InterruptedException {
        occupyHead("s1", "问题？");

        bridge.cancelIfPending(headId("s1"));

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void cancelIfPending_should_do_nothing_when_request_not_head() throws InterruptedException {
        occupyHead("s1", "问题？");

        bridge.cancelIfPending("missing");

        assertTrue(bridge.headFor("s1").isPresent());
    }

    @Test
    void cancelIfPending_should_ignore_null_id() throws InterruptedException {
        occupyHead("s1", "问题？");

        // 断开路径上拿不到 id 是正常情形，不该抛异常
        bridge.cancelIfPending(null);

        assertTrue(bridge.headFor("s1").isPresent());
    }

    @Test
    void detach_should_clear_head_when_called() throws InterruptedException {
        occupyHead("s1", "问题？");

        bridge.detach();

        assertFalse(bridge.headFor("s1").isPresent());
    }

    @Test
    void attach_should_be_required_before_any_question_lands() throws InterruptedException {
        // Given：未挂答复者
        AskBridge detached = new AskBridge(new AskChannel(Mockito.mock(RuntimeConfig.class)));

        // When / Then：取件永远是空，桥不做任何假设
        assertFalse(detached.headFor("s1").isPresent());
    }
}
