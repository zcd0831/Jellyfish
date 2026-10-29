package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真跑 HTTP（MockWebServer），不联网。
 * 规则出处：09-infrastructure §9.11 第 9 条（请求句柄按调用持有）。
 */
class HttpJsonClientTest {

    private MockWebServer server;
    private final HttpJsonClient client = new HttpJsonClient();
    private final CancellationToken never = CancellationToken.never();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void 成功响应返回状态与响应体并带上请求头() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("{\"answer\":42}"));

        ChatTransport.Response response = client.post(server.url("/chat/completions").toString(),
                Collections.singletonMap("Authorization", "Bearer k"), "{\"model\":\"m\"}", never);

        assertEquals(200, response.status());
        assertEquals("{\"answer\":42}", response.body());
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer k", request.getHeader("Authorization"));
        assertTrue(request.getBody().readUtf8().contains("\"model\""));
    }

    @Test
    void 错误响应保留响应体与RetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                .setBody("{\"error\":\"slow down\"}"));

        ChatTransport.Response response = client.post(server.url("/chat/completions").toString(),
                Collections.<String, String>emptyMap(), "{}", never);

        assertEquals(429, response.status());
        assertEquals("30", response.retryAfter());
        assertTrue(response.body().contains("slow down"));
    }

    @Test
    void 流式逐行回传data负载() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: first\n\ndata: [DONE]\n\n"));
        final List<String> received = new CopyOnWriteArrayList<>();

        client.postStreaming(server.url("/chat/completions").toString(),
                Collections.<String, String>emptyMap(), "{}", never, new ChatTransport.LineSink() {
                    @Override
                    public boolean onData(String data) {
                        received.add(data);
                        return !"[DONE]".equals(data);
                    }
                });

        assertEquals(Arrays.asList("first", "[DONE]"), received);
    }

    @Test
    void 终止信号让在途请求立即失败() {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS));
        final SessionCancellations cancellations = new SessionCancellations();
        SessionId session = SessionId.of("s1");
        final CancellationToken token = cancellations.register(session, null);
        Thread terminator = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancellations.cancel(SessionId.of("s1"));
            }
        });
        terminator.start();

        long started = System.currentTimeMillis();
        assertThrows(java.util.concurrent.CancellationException.class, () -> client.post(
                server.url("/chat/completions").toString(), Collections.<String, String>emptyMap(), "{}", token));
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < 3000, "终止应立刻生效，实际耗时 " + elapsed + "ms");
    }

    @Test
    void 一个会话的终止不波及另一个会话的在途请求() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"answer\":1}").setBodyDelay(1200, TimeUnit.MILLISECONDS));
        final SessionCancellations cancellations = new SessionCancellations();
        final CancellationToken doomed = cancellations.register(SessionId.of("a"), null);
        final CancellationToken healthy = cancellations.register(SessionId.of("b"), null);

        Thread terminator = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancellations.cancel(SessionId.of("a"));
            }
        });
        terminator.start();

        ChatTransport.Response other = client.post(server.url("/chat/completions").toString(),
                Collections.<String, String>emptyMap(), "{}", healthy);
        terminator.join();

        assertEquals(200, other.status(), "另一个会话的在途请求不得被终止");
        assertTrue(doomed.isCancelled(), "被终止的只是发起方那个会话");
        assertFalse(healthy.isCancelled());
    }
}
