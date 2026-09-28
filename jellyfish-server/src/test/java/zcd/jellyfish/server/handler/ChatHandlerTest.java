package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.SessionTurns;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHandler} 的流式契约、并发拒绝与断连取消。
 * <p>
 * 「事件什么时候产生」由被 mock 的 {@code AgentHarness} 在 {@code chat} 里同步触发，因此循环是确定性的：
 * 队列里先有事件，处理器再消费——不需要等待线程也没用 sleep。
 *
 * @author zcd
 */
class ChatHandlerTest {

    /** 智能入口。 */
    private AgentHarness harness;

    /** 会话域服务。 */
    private SessionManager sessions;

    /** 在途回合表。 */
    private SessionTurns turns;

    /** 运行参数。 */
    private ServerConfig config;

    /** 审批桥（真 ApprovalChannel + 真桥）。 */
    private ApprovalBridge approvals;

    /** 被测试的处理器。 */
    private ChatHandler handler;

    @BeforeEach
    void setUp() {
        harness = Mockito.mock(AgentHarness.class);
        sessions = Mockito.mock(SessionManager.class);
        turns = new SessionTurns();
        config = ServerConfig.builder("127.0.0.1", 9096).build();
        approvals = new ApprovalBridge(new ApprovalChannel());
        handler = new ChatHandler(harness, sessions, turns, config, approvals);
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
        when(harness.chat(eq("s1"), eq("hi"), any(ReActListener.class))).thenAnswer(invocation -> {
            ReActListener listener = invocation.getArgument(2);
            listener.onText("hello");
            listener.onToolCallStarted("c1", "read_file");
            listener.onToolCallOutput("c1", "shell", "building...");
            listener.onToolCallCompleted("c1", "read_file", true, "ok", null);
            listener.onComplete(ReActResult.completed("s1", "hello", 1));
            return new FakeTurn("t1");
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
        assertTrue(body.contains("\"content\":\"hello\",\"rounds\":1,\"truncated\":false}"), body);
    }

    @Test
    void handle_should_release_slot_when_turn_completes() {
        when(harness.chat(eq("s1"), eq("hi"), any(ReActListener.class))).thenAnswer(invocation -> {
            ((ReActListener) invocation.getArgument(2)).onComplete(ReActResult.completed("s1", "x", 1));
            return new FakeTurn("t1");
        });

        handler.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1"));

        Semaphore slot = turns.acquire("s1");
        turns.release("s1", slot);
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
    void handle_should_return_409_when_session_already_has_turn() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> {
            Semaphore slot = turns.acquire("s1");
            acquired.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            turns.release("s1", slot);
        });
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        try {
            ApiException error = assertThrows(ApiException.class,
                    () -> handler.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1")));
            assertEquals(Responses.CONFLICT, error.getStatus());
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void handle_should_return_503_when_stream_limit_reached() {
        ChatHandler limited = new ChatHandler(harness, sessions, turns,
                ServerConfig.builder("127.0.0.1", 9096).maxStreams(0).build(), approvals);

        ApiException error = assertThrows(ApiException.class,
                () -> limited.handle(fixture("{\"message\":\"hi\"}").exchange, idParam("s1")));

        assertEquals(Responses.SERVICE_UNAVAILABLE, error.getStatus());
    }

    @Test
    void handle_should_cancel_turn_when_client_disconnects() {
        FakeTurn turn = new FakeTurn("t1");
        when(harness.chat(eq("s1"), eq("hi"), any(ReActListener.class))).thenReturn(turn);
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
