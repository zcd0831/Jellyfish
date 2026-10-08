package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OriginGuard} 的单元测试：钉住「跨站写请求被拒」与「不带来源头的客户端照常可用」。
 * <p>
 * 后一半与前一半同样重要：把 {@code curl} 也拦下来会让这个防线在真实使用里被直接关掉，
 * 而一个被关掉的防线等于没有。
 *
 * @author zcd
 */
@DisplayName("OriginGuard 跨站请求守门人")
class OriginGuardTest {

    /** 每个交换对象桩自己记的状态码：Mockito 的桩不会真的保存它（与 ApiKeyGuardTest 同做法）。 */
    private static final Map<HttpServerExchange, AtomicInteger> STATUS =
            new ConcurrentHashMap<HttpServerExchange, AtomicInteger>();

    /** 是否已放行到下游。 */
    private final AtomicBoolean passed = new AtomicBoolean();

    /** 下游处理器。 */
    private final HttpHandler next = exchange -> passed.set(true);

    /** 被测试的守门人。 */
    private OriginGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OriginGuard(next);
        passed.set(false);
    }

    @Test
    @DisplayName("不带 Origin / Referer 的 POST 放行：curl 与 SDK 不受同源策略约束")
    void handle_should_allow_when_noOriginAndNoReferer() throws Exception {
        HttpServerExchange exchange = exchange("POST", "/sessions");

        guard.handleRequest(exchange);

        assertTrue(passed.get(), "不带来源头的客户端必须能用");
    }

    @Test
    @DisplayName("同源 POST 放行")
    void handle_should_allow_when_sameOrigin() throws Exception {
        HttpServerExchange exchange = exchange("POST", "/sessions");
        exchange.getRequestHeaders().put(new HttpString("Origin"), "http://127.0.0.1:9096");

        guard.handleRequest(exchange);

        assertTrue(passed.get());
    }

    @Test
    @DisplayName("跨站 POST 拒绝：浏览器会把恶意页面的写请求执行掉，只是响应不可读")
    void handle_should_reject_when_crossOrigin() throws Exception {
        HttpServerExchange exchange = exchange("POST", "/sessions");
        exchange.getRequestHeaders().put(new HttpString("Origin"), "https://evil.example");

        guard.handleRequest(exchange);

        assertFalse(passed.get(), "跨站写请求必须被拦下");
        assertEquals(Responses.FORBIDDEN, status(exchange).get());
    }

    @Test
    @DisplayName("Origin 缺席时退到 Referer 判：覆盖旧浏览器")
    void handle_should_reject_when_refererIsCrossOrigin() throws Exception {
        HttpServerExchange exchange = exchange("POST", "/sessions/1/chat");
        exchange.getRequestHeaders().put(Headers.REFERER, "https://evil.example/page");

        guard.handleRequest(exchange);

        assertFalse(passed.get());
    }

    @Test
    @DisplayName("Origin 为字面量 null（沙箱 iframe、data: 页面）同样拒绝")
    void handle_should_reject_when_originIsLiteralNull() throws Exception {
        HttpServerExchange exchange = exchange("POST", "/sessions");
        exchange.getRequestHeaders().put(new HttpString("Origin"), "null");

        guard.handleRequest(exchange);

        assertFalse(passed.get(), "判不出同源就不该放行");
    }

    @Test
    @DisplayName("GET 不校验来源：它没有副作用，而跨站读本来就被同源策略挡住")
    void handle_should_allow_get_when_crossOrigin() throws Exception {
        HttpServerExchange exchange = exchange("GET", "/sessions");
        exchange.getRequestHeaders().put(new HttpString("Origin"), "https://evil.example");

        guard.handleRequest(exchange);

        assertTrue(passed.get());
    }

    /**
     * 造一个请求夹具。
     * <p>
     * 状态码要自己记：Mockito 的桩对 {@code setStatusCode(int)} 这种「有副作用的 setter」
     * 不会有任何后续效果，直接 {@code getStatusCode()} 永远得到 0，断言就会变成「测不到」。
     *
     * @param method 请求方法
     * @param path   请求路径
     * @return 夹具
     */
    private static HttpServerExchange exchange(String method, String path) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        Mockito.lenient().when(exchange.isInIoThread()).thenReturn(false);
        Mockito.when(exchange.getRequestMethod()).thenReturn(HttpString.tryFromString(method));
        Mockito.lenient().when(exchange.getRequestPath()).thenReturn(path);
        Mockito.lenient().when(exchange.getHostAndPort()).thenReturn("127.0.0.1:9096");
        Mockito.lenient().when(exchange.getRequestHeaders()).thenReturn(new HeaderMap());
        Mockito.lenient().when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        Mockito.lenient().when(exchange.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        Mockito.lenient().when(exchange.setStatusCode(Mockito.anyInt())).thenAnswer(invocation -> {
            status(exchange).set(invocation.<Integer>getArgument(0));
            return exchange;
        });
        return exchange;
    }

    /**
     * 取某个夹具记录的状态码。
     *
     * @param exchange 夹具
     * @return 状态码计数
     */
    private static AtomicInteger status(HttpServerExchange exchange) {
        return STATUS.computeIfAbsent(exchange, ignored -> new AtomicInteger());
    }
}
