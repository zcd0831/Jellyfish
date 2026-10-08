package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.SubmissionPolicy;
import zcd.jellyfish.core.conversation.TurnInProgressException;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.config.AskSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.AskBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHandler} 的流式契约、并发拒绝与断连取消。
 * <p>
 * 「事件什么时候产生」由被 mock 的 {@code ConversationService} 在 {@code submit} 里同步触发，
 * 因此循环是确定性的：队列里先有事件，处理器再消费——不需要等待线程也没用 sleep。
 * <p>
 * <b>本测试不覆盖分流与并发闸门</b>：命令 / 改写 / 指令的顺序与「一会话一在途回合」都是
 * {@code ConversationService} + {@code TurnRegistry} 的职责，在 core 模块测；这里只钉住
 * 「处理器拿到 {@code STARTED_TURN} 之后怎么把事件写成 SSE」以及「各种拒绝怎么映射状态码」。
 *
 * @author zcd
 */
class ChatHandlerTest {

    /** 会话提交服务（mock）。 */
    private ConversationService conversations;

    /** 可靠 lane（真实现：订阅者在 handle 内部建立）。 */
    private ShellStreams streams;

    /** 在途回合表（真实现）。 */
    private TurnRegistry turns;

    /** 会话域服务。 */
    private SessionManager sessions;

    /** 运行参数。 */
    private ServerConfig config;

    /** 审批桥（真 ApprovalChannel + 真桥）。 */
    private ApprovalBridge approvals;

    /** 提问桥（真 AskChannel + 真桥）。 */
    private AskBridge asks;

    /** 被测试的处理器。 */
    private ChatHandler handler;

    @BeforeEach
    void setUp() {
        conversations = Mockito.mock(ConversationService.class);
        streams = new ShellStreams(new ShellIngress(new MetricsRegistry()));
        turns = new TurnRegistry(ignored -> { });
        sessions = Mockito.mock(SessionManager.class);
        config = ServerConfig.builder("127.0.0.1", 9096).build();
        approvals = new ApprovalBridge(new ApprovalChannel());
        asks = askBridge();
        handler = new ChatHandler(conversations, streams, turns, new RunEventBus(), sessions, config, approvals,
                asks);
    }

    /**
     * 造一个真的提问桥。
     * <p>
     * 提问通道与审批通道不同：它要读 {@code ask.timeoutSeconds} 才能算超时，因此需要一份配置。
     * 本用例只关心「事件推没推出去」，所以给缺省值即可。
     *
     * @return 提问桥，保证非 {@code null}
     */
    private static AskBridge askBridge() {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings());
        return new AskBridge(new AskChannel(runtimeConfig));
    }

    /**
     * 让 {@code /chat} 进入一次回合，并在提交时同步触发监听器回调。
     *
     * @param sessionId 会话标识
     * @param message   消息
     * @param emit      在 {@code submit} 内同步发布的事件序列（STARTED 已自动发布），可为 {@code null}
     * @return 回合句柄
     */
    private FakeTurn stubTurn(String sessionId, String message, Consumer<ShellStreams> emit) {
        FakeTurn turn = new FakeTurn("t1");
        when(conversations.submit(eq(sessionId), eq(message), eq(InputTransformRequest.Source.SERVER),
                any(SubmissionPolicy.class))).thenAnswer(invocation -> {
                    // 内核会把句柄绑进 TurnRegistry（断连取消要靠它）；STARTED 也由内核发布
                    turns.bind(sessionId, turn);
                    streams.publish(ShellTurnEvent.started(sessionId, "t1"));
                    if (emit != null) {
                        emit.accept(streams);
                    }
                    return Submission.turn(sessionId, "t1");
                });
        return turn;
    }

    /**
     * 造一个请求夹具。
     *
     * @param body 请求体
     * @return 夹具
     */
    private static Fixture fixture(String body) {
        return fixture(body, new ByteArrayOutputStream());
    }

    /**
     * 造一个请求夹具，可替换输出流。
     *
     * @param body 请求体
     * @param out  输出流
     * @return 夹具
     */
    private static Fixture fixture(String body, OutputStream out) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        when(exchange.getRequestContentLength()).thenReturn((long) body.length());
        when(exchange.getInputStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        // 真实交换对象上请求头永远在（JsonBody 会读 Content-Type），mock 也要如实
        when(exchange.getRequestHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        return new Fixture(exchange, out);
    }

    /**
     * 构造路径参数。
     *
     * @param id 会话标识
     * @return 路径参数
     */
    private static PathParams idParam(String id) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("id", id);
        return new PathParams(values);
    }

    @Test
    void handle_should_stream_all_events_and_finish_stream_when_turn_completes() {
        stubTurn("s1", "hi", lane -> {
            lane.publish(ShellTurnEvent.text("s1", "t1", "hello"));
            lane.publish(ShellTurnEvent.toolStarted("s1", "t1", "c1", "read_file", null));
            lane.publish(ShellTurnEvent.toolOutput("s1", "t1", "c1", "shell", "building..."));
            lane.publish(ShellTurnEvent.toolCompleted("s1", "t1", "c1", "read_file", true, "ok", null));
            lane.publish(ShellTurnEvent.completed("s1", "t1", "hello", 1, false));
        });
        Fixture fixture = fixture("{\"message\":\"hi\"}");

        handler.handle(fixture.exchange, idParam("s1"));

        String body = fixture.body();
        assertTrue(body.contains("event: turn_start"), body);
        assertTrue(body.contains("event: text"), body);
        assertTrue(body.contains("event: tool_start"), body);
        assertTrue(body.contains("event: tool_output"), body);
        assertTrue(body.contains("\"chunk\":\"building...\""), body);
        assertTrue(body.contains("event: tool_done"), body);
        assertTrue(body.contains("event: done\ndata: {\"turnId\":"), body);
        assertTrue(body.contains("\"content\":\"hello\",\"rounds\":1,\"truncated\":false,\"notice\":null}"),
                body);
    }

    @Test
    void handle_should_release_stream_permit_when_turn_completes() {
        // 并发流许可是外壳自己的资源（内核管的是会话槽位）。最大 1 条：第一条跑完必须能跑第二条
        ChatHandler limited = new ChatHandler(conversations, streams, turns, new RunEventBus(), sessions,
                ServerConfig.builder("127.0.0.1", 9096).maxStreams(1).build(), approvals, asks);
        stubTurn("s1", "hi", lane -> lane.publish(ShellTurnEvent.completed("s1", "t1", "x", 1, false)));

        limited.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1"));
        limited.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1"));
    }

    @Test
    void handle_should_write_input_handled_and_skip_turn_when_input_handled() {
        // 被插件接过去的输入不产生回合，也不往会话里 append 用户消息（submit 保证）
        when(conversations.submit(eq("s1"), eq("?help"), eq(InputTransformRequest.Source.SERVER),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.handled("s1", "先看看这份清单"));
        Fixture fixture = fixture("{\"message\":\"?help\"}");

        handler.handle(fixture.exchange, idParam("s1"));

        String body = fixture.body();
        assertTrue(body.contains("event: input_handled"), body);
        assertTrue(body.contains("\"notice\":\"先看看这份清单\""), body);
        assertTrue(!body.contains("event: turn_start"), body);
    }

    @Test
    void handle_should_return_400_when_message_blank() {
        ApiException error = assertThrows(ApiException.class,
                () -> handler.handle(fixture("{\"message\":\"   \"}").exchange, idParam("s1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void handle_should_return_404_when_session_missing() {
        when(sessions.require("ghost")).thenThrow(new JellyfishException("session not found"));

        ApiException error = assertThrows(ApiException.class,
                () -> handler.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("ghost")));

        assertEquals(Responses.NOT_FOUND, error.getStatus());
    }

    @Test
    void handle_should_return_409_when_session_already_has_turn() {
        // 「一会话一在途回合」由内核的 TurnRegistry 在 submit 内部判定；外壳只做状态码映射
        when(conversations.submit(eq("s1"), eq("hi"), eq(InputTransformRequest.Source.SERVER),
                any(SubmissionPolicy.class)))
                .thenThrow(new TurnInProgressException("s1"));

        ApiException error = assertThrows(ApiException.class,
                () -> handler.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1")));

        assertEquals(Responses.CONFLICT, error.getStatus());
    }

    @Test
    void handle_should_return_503_when_stream_limit_reached() {
        ChatHandler limited = new ChatHandler(conversations, streams, turns, new RunEventBus(), sessions,
                ServerConfig.builder("127.0.0.1", 9096).maxStreams(0).build(), approvals, asks);

        ApiException error = assertThrows(ApiException.class,
                () -> limited.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1")));

        assertEquals(Responses.SERVICE_UNAVAILABLE, error.getStatus());
    }

    @Test
    void handle_should_cancel_turn_when_client_disconnects() {
        FakeTurn turn = stubTurn("s1", "hi", null);
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        };

        handler.handle(fixture("{\"message\":\"hi\"}", failing).exchange, idParam("s1"));

        assertTrue(turn.isCancelled());
    }

    /**
     * 一次请求的测试夹具。
     *
     * @author zcd
     */
    private static final class Fixture {

        /** mock 交换对象。 */
        private final HttpServerExchange exchange;

        /** 输出流。 */
        private final OutputStream out;

        /**
         * 构造夹具。
         *
         * @param exchange mock 交换对象
         * @param out      输出流
         */
        private Fixture(HttpServerExchange exchange, OutputStream out) {
            this.exchange = exchange;
            this.out = out;
        }

        /**
         * 取写出文本。
         *
         * @return UTF-8 文本
         */
        private String body() {
            return out instanceof ByteArrayOutputStream
                    ? new String(((ByteArrayOutputStream) out).toByteArray(), StandardCharsets.UTF_8) : "";
        }
    }

    /**
     * 测试用的回合句柄。
     *
     * @author zcd
     */
    private static final class FakeTurn implements ReActTurn {

        /** 回合标识。 */
        private final String turnId;

        /** 是否已取消。 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /**
         * 构造回合。
         *
         * @param turnId 回合标识
         */
        private FakeTurn(String turnId) {
            this.turnId = turnId;
        }

        @Override
        public String getTurnId() {
            return turnId;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public ReActResult await() {
            return ReActResult.cancelled("s1", 0);
        }

        @Override
        public boolean isDone() {
            return false;
        }

        /**
         * 判断是否已取消。
         *
         * @return 已取消返回 {@code true}
         */
        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
