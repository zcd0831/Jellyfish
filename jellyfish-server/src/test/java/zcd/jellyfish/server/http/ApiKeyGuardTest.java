package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyGuard} 的鉴权判定与拒绝路径。
 * <p>
 * 这里断言的是「放行还是 401」以及**拒绝时下游一定没被调用**：少判一条路径的后果是
 * 「以为配了密钥，其实那条路根本没校验」，而这种漏洞在功能用例里完全看不出来。
 *
 * @author zcd
 */
@DisplayName("ApiKeyGuard：API key 鉴权")
class ApiKeyGuardTest {

    /** 测试用密钥。 */
    private static final String KEY = "s3cret-api-key-0123456789";

    /** 每个交换对象桩自己记的状态码：Mockito 的桩不会真的保存它。 */
    private static final java.util.Map<HttpServerExchange, AtomicInteger> STATUS =
            new java.util.concurrent.ConcurrentHashMap<HttpServerExchange, AtomicInteger>();

    /**
     * 造一个请求交换对象。
     * <p>
     * 状态码与响应体要自己记：Mockito 的桩对 {@code setStatusCode(int)} 这种「有副作用的 setter」
     * 不会有任何后续效果，直接 {@code getStatusCode()} 永远得到 0，断言就会变成「测不到」。
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @param header {@code Authorization} 头的值，可为 {@code null}
     * @return 交换对象桩
     */
    private static HttpServerExchange exchange(String method, String path, String header) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        HeaderMap headers = new HeaderMap();
        if (header != null) {
            headers.put(Headers.AUTHORIZATION, header);
        }
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getRequestMethod()).thenReturn(HttpString.tryFromString(method));
        when(exchange.getRequestPath()).thenReturn(path);
        when(exchange.isInIoThread()).thenReturn(false);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(exchange.setStatusCode(Mockito.anyInt())).thenAnswer(invocation -> {
            status(exchange).set(invocation.<Integer>getArgument(0));
            return exchange;
        });
        return exchange;
    }

    /**
     * 取该交换对象上「被设置过的状态码」。
     *
     * @param exchange 交换对象桩
     * @return 状态码持有者
     */
    private static AtomicInteger status(HttpServerExchange exchange) {
        AtomicInteger holder = STATUS.get(exchange);
        if (holder == null) {
            holder = new AtomicInteger();
            STATUS.put(exchange, holder);
        }
        return holder;
    }

    /**
     * 取该交换对象最终的状态码。
     *
     * @param exchange 交换对象桩
     * @return 状态码；从未设置时返回 0
     */
    private static int statusOf(HttpServerExchange exchange) {
        return status(exchange).get();
    }

    /**
     * 造一个下游处理器，记录被调用次数。
     *
     * @return 计数器；被调用时会自增
     */
    private static AtomicInteger downstream() {
        return new AtomicInteger();
    }

    /**
     * 把下游包成 handler。
     *
     * @param calls 计数器
     * @return handler
     */
    private static HttpHandler handler(AtomicInteger calls) {
        return exchange -> calls.incrementAndGet();
    }

    @Test
    @DisplayName("密钥正确时应放行，且不写任何响应")
    void handleRequest_should_passThrough_when_keyMatches() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));
        HttpServerExchange exchange = exchange("GET", "/sessions", "Bearer " + KEY);

        guard.handleRequest(exchange);

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("方案名大小写不敏感，多余空白应被容忍")
    void handleRequest_should_passThrough_when_schemeCaseAndSpacesDiffer() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));

        guard.handleRequest(exchange("GET", "/sessions", "bearer   " + KEY + " "));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("缺少请求头：401 且下游不被调用")
    void handleRequest_should_reject_when_headerMissing() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));
        HttpServerExchange exchange = exchange("GET", "/sessions", null);

        guard.handleRequest(exchange);

        assertEquals(0, calls.get(), "拒绝时下游绝不能被调用");
        assertEquals(401, statusOf(exchange));
        assertTrue(exchange.getResponseHeaders().getFirst(Headers.WWW_AUTHENTICATE).startsWith("Bearer"));
    }

    @Test
    @DisplayName("密钥错误：401，且响应体形状与其它错误一致")
    void handleRequest_should_reject_when_keyWrong() throws Exception {
        AtomicInteger calls = downstream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));
        HttpServerExchange exchange = exchange("GET", "/sessions", "Bearer wrong-key");
        when(exchange.getOutputStream()).thenReturn(out);

        guard.handleRequest(exchange);

        assertEquals(0, calls.get());
        assertEquals(401, statusOf(exchange));
        assertTrue(out.toString(StandardCharsets.UTF_8.name()).contains("\"error\":\"UNAUTHORIZED\""),
                out.toString());
    }

    @Test
    @DisplayName("方案名不对（裸密钥、Basic）一律拒绝")
    void handleRequest_should_reject_when_schemeMissingOrDifferent() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));

        guard.handleRequest(exchange("GET", "/sessions", KEY));
        guard.handleRequest(exchange("GET", "/sessions", "Basic " + KEY));
        guard.handleRequest(exchange("GET", "/sessions", "Bearer "));

        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("密钥前缀正确但更长（长度不同）必须拒绝：不能用前缀匹配")
    void handleRequest_should_reject_when_keyIsPrefixOfExpected() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));

        guard.handleRequest(exchange("GET", "/sessions", "Bearer " + KEY + "extra"));

        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("GET /health 不校验：探活必须能在没有密钥时工作")
    void handleRequest_should_passThrough_healthProbe() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));

        guard.handleRequest(exchange("GET", "/health", null));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("同一个路径的写方法仍然要校验：例外只给探活那一个方法")
    void handleRequest_should_reject_when_healthPathWithOtherMethod() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));

        guard.handleRequest(exchange("POST", "/health", null));

        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("IO 线程上的请求先派发到工作线程，不在 IO 线程写阻塞响应")
    void handleRequest_should_dispatch_when_inIoThread() throws Exception {
        AtomicInteger calls = downstream();
        ApiKeyGuard guard = new ApiKeyGuard(KEY, handler(calls));
        HttpServerExchange exchange = exchange("GET", "/sessions", null);
        when(exchange.isInIoThread()).thenReturn(true);

        guard.handleRequest(exchange);

        assertEquals(0, calls.get());
        Mockito.verify(exchange).dispatch(guard);
    }

    @Test
    @DisplayName("空密钥必须在构造期拒绝：它应当表达成「不装守门人」")
    void constructor_should_rejectBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyGuard("  ", exchange -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyGuard(null, exchange -> {
        }));
    }
}
