package io.github.zcd0831.jellyfish.infrastructure.model;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真跑 HTTP（MockWebServer），不联网。。
 */
class HttpJsonClientTest {

    private MockWebServer server;
    private final HttpJsonClient client = new HttpJsonClient();

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
                Collections.singletonMap("Authorization", "Bearer k"), "{\"model\":\"m\"}");

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
                Collections.<String, String>emptyMap(), "{}");

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
                Collections.<String, String>emptyMap(), "{}", new ChatTransport.LineSink() {
                    @Override
                    public boolean onData(String data) {
                        received.add(data);
                        return !"[DONE]".equals(data);
                    }
                });

        assertEquals(Arrays.asList("first", "[DONE]"), received);
    }

    @Test
    void 取消在途请求立即失败() {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS));
        Thread canceller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                client.cancelActive();
            }
        });
        canceller.start();

        long started = System.currentTimeMillis();
        assertThrows(UncheckedIOException.class, () -> client.post(
                server.url("/chat/completions").toString(), Collections.<String, String>emptyMap(), "{}"));
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < 3000, "取消应立刻生效，实际耗时 " + elapsed + "ms");
    }
}
