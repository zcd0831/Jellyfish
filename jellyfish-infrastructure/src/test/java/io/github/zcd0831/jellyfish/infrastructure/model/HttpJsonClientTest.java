package io.github.zcd0831.jellyfish.infrastructure.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 JDK 自带 HttpServer 验真实 HTTP 行为（不联网、不打桩 HTTP 库）。
 * 规则出处：技术决策 D10.1（超时）、D16-5（JDK HttpURLConnection）。
 */
class HttpJsonClientTest {

    private HttpServer server;
    private final HttpJsonClient client = new HttpJsonClient();
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "{\"answer\":42}", null));
        server.createContext("/bad", exchange -> respond(exchange, 401, "{\"error\":\"auth\"}", "30"));
        server.createContext("/sse", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("data: first\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body, String retryAfter)
            throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void 成功响应返回状态与响应体() {
        ChatTransport.Response response = client.post(baseUrl + "/ok", Collections.<String, String>emptyMap(), "{}");

        assertEquals(200, response.status());
        assertEquals("{\"answer\":42}", response.body());
    }

    @Test
    void 错误响应保留响应体与RetryAfter() {
        ChatTransport.Response response = client.post(baseUrl + "/bad", Collections.<String, String>emptyMap(), "{}");

        assertEquals(401, response.status());
        assertEquals("30", response.retryAfter());
        assertTrue(response.body().contains("auth"));
    }

    @Test
    void 流式逐行回传data负载() {
        final List<String> received = new CopyOnWriteArrayList<>();

        client.postStreaming(baseUrl + "/sse", Collections.<String, String>emptyMap(), "{}", new ChatTransport.LineSink() {
            @Override
            public boolean onData(String data) {
                received.add(data);
                return !"[DONE]".equals(data);
            }
        });

        assertEquals(2, received.size());
        assertEquals("first", received.get(0));
        assertEquals("[DONE]", received.get(1));
    }

    @Test
    void 请求头被送出() {
        final List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/echo", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{}", null);
        });

        client.post(baseUrl + "/echo", Collections.singletonMap("Authorization", "Bearer k"), "{}");

        assertEquals("Bearer k", seen.get(0));
    }
}
