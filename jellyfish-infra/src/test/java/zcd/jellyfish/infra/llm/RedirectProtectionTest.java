package zcd.jellyfish.infra.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.LlmHttpException;
import zcd.jellyfish.infra.support.LlmClients;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;

/**
 * 「上游要重定向时密钥不外流」的回归防线。
 * <p>
 * <b>为什么起真的 HTTP 服务而不是用拦截器桩</b>：这件事发生在 OkHttp 内部的重定向循环里，
 * 拦截器桩把响应直接捏在手上，压根走不到那段逻辑——用桩测等于测了个寂寞。这里用 JDK 自带的
 * {@code com.sun.net.httpserver} 起两个真实端口：一个回 {@code 302}，另一个只负责记录
 * 「有没有人访问过、看到了什么头」。因此「密钥被送到别的主机」是可观察的，而不是靠读代码相信。
 *
 * @author zcd
 */
class RedirectProtectionTest {

    /** 第一台主机收到的 {@code x-api-key}，用来证明密钥确实发出去了。 */
    private final AtomicReference<String> sourceApiKey = new AtomicReference<String>();

    /** 第二台主机被访问的次数。 */
    private final AtomicInteger stolenHits = new AtomicInteger();

    /** 第二台主机收到的 {@code x-api-key}。 */
    private final AtomicReference<String> stolenApiKey = new AtomicReference<String>();

    /** 第一台主机：回一个跨主机重定向。 */
    private HttpServer source;

    /** 被 302 指向的第二台主机。 */
    private HttpServer target;

    /** 期间创建的线程池，测试结束一并关闭。 */
    private ExecutorService httpExecutor;

    /**
     * 起两台本地服务：source 回 302，target 只记录自己被访问过。
     *
     * @throws IOException 绑定端口失败时抛出
     */
    @BeforeEach
    void setUp() throws IOException {
        httpExecutor = Executors.newCachedThreadPool();
        target = startServer(exchange -> {
            stolenHits.incrementAndGet();
            stolenApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            respond(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"stolen\"}]}");
        });
        String targetUrl = "http://127.0.0.1:" + target.getAddress().getPort() + "/steal?token=s3cr3t";
        source = startServer(exchange -> {
            sourceApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            exchange.getResponseHeaders().add("Location", targetUrl);
            respond(exchange, 302, "");
        });
    }

    /** 关闭测试期间起的服务与线程池。 */
    @AfterEach
    void tearDown() {
        stopServer(source);
        stopServer(target);
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    @Test
    void newHttpClient_should_not_follow_redirects() {
        // When
        OkHttpClient client = LlmClients.newHttpClient();

        // Then：两条都要关——只关 followRedirects 仍会跟协议间跳转（http→https）
        assertFalse(client.followRedirects(), "HTTP 重定向必须关闭");
        assertFalse(client.followSslRedirects(), "协议间重定向同样必须关闭");
    }

    @Test
    void chat_should_notSendApiKey_when_upstreamRedirects() {
        // Given
        ClaudeLlmClient client = clientWithKey();

        // When：上游回 302，客户端刻意不跟
        LlmHttpException failure = assertThrows(LlmHttpException.class, () -> client.chat(oneMessage()));

        // Then：密钥确实发给了第一台主机（否则这条用例什么也没证明），但第二台一次都没被访问
        assertEquals(302, failure.getStatusCode());
        assertEquals("sk-ant-secret", sourceApiKey.get(), "前置条件不成立：请求根本没带上密钥");
        assertEquals(0, stolenHits.get(), "重定向目标不该收到任何请求");
        assertNull(stolenApiKey.get(), "重定向目标不该看到密钥");
    }

    @Test
    void chat_should_explain_redirect_without_leaking_locationQuery() {
        // Given
        ClaudeLlmClient client = clientWithKey();

        // When
        LlmHttpException failure = assertThrows(LlmHttpException.class, () -> client.chat(oneMessage()));

        // Then：说清「跳转被拦了、该改哪儿」，但只给主机与路径，不带 query
        String message = failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("重定向被禁止"), "要说清是重定向被拦，而不是一句 HTTP 302：" + message);
        assertTrue(message.contains("127.0.0.1:" + target.getAddress().getPort()), "要给出目标主机：" + message);
        assertTrue(message.contains("/steal"), "要给出目标路径：" + message);
        assertFalse(message.contains("s3cr3t"), "不能把 Location 的 query 抄进异常：" + message);
    }

    /**
     * 构造一个指向第一台主机的 Claude 客户端。
     *
     * @return 客户端
     */
    private ClaudeLlmClient clientWithKey() {
        String baseUrl = "http://127.0.0.1:" + source.getAddress().getPort();
        return new ClaudeLlmClient(provider("claude", "sk-ant-secret", baseUrl),
                LlmClients.newHttpClient(), directExecutor());
    }

    /**
     * 构造一条最简单的请求。
     *
     * @return 请求
     */
    private static LlmRequest oneMessage() {
        return LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build();
    }

    /**
     * 起一个只处理根路径的本地服务。
     *
     * @param handler 处理器
     * @return 已启动的服务
     * @throws IOException 绑定端口失败时抛出
     */
    private HttpServer startServer(HttpHandler handler) throws IOException {
        // 绑回环 + 端口 0：不占固定端口，也不会把测试流量露到局域网上
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(httpExecutor);
        server.start();
        return server;
    }

    /**
     * 写一个响应。
     *
     * @param exchange 交换
     * @param status   状态码
     * @param body     响应文本
     * @throws IOException 写出失败时抛出
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type",
                Collections.singletonList("application/json; charset=utf-8"));
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1L : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * 安静地停掉一个服务。
     *
     * @param server 服务，可为 {@code null}
     */
    private static void stopServer(HttpServer server) {
        if (server != null) {
            server.stop(0);
        }
    }
}
