package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Router} 的匹配与异常翻译契约。
 * <p>
 * <b>为什么直接 mock {@code HttpServerExchange}</b>：它是 final 类，因此本模块测试引入了
 * {@code mockito-inline}。选它而不是抽一层「纯匹配器」，是因为路由的正确性一半在「匹配到谁」，
 * 另一半在「没匹配到时回什么状态码」——把状态码那一半抽走，被测对象就只剩下一半。
 *
 * @author zcd
 */
class RouterTest {

    /**
     * 造一个可断言输出的交换对象。
     *
     * @param method 请求方法
     * @param path   请求路径
     * @return 夹具（含 mock 与输出缓冲）
     */
    private static Fixture fixture(String method, String path) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HeaderMap headers = new HeaderMap();
        when(exchange.isInIoThread()).thenReturn(false);
        when(exchange.getRequestMethod()).thenReturn(new HttpString(method));
        when(exchange.getRequestPath()).thenReturn(path);
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getOutputStream()).thenReturn(out);
        when(exchange.isResponseStarted()).thenReturn(false);
        return new Fixture(exchange, out, headers);
    }

    @Test
    void handleRequest_should_invoke_handler_with_path_params_when_path_matches() throws Exception {
        Router router = new Router();
        AtomicReference<String> captured = new AtomicReference<String>();
        router.route("GET", "/sessions/{id}", (exchange, params) -> captured.set(params.get("id")));
        Fixture fixture = fixture("GET", "/sessions/abc");

        router.handleRequest(fixture.exchange);

        assertEquals("abc", captured.get());
        verify(fixture.exchange, never()).setStatusCode(404);
    }

    @Test
    void handleRequest_should_return_404_when_no_path_matches() throws Exception {
        Router router = new Router();
        router.route("GET", "/sessions", (exchange, params) -> {
        });
        Fixture fixture = fixture("GET", "/nope");

        router.handleRequest(fixture.exchange);

        verify(fixture.exchange).setStatusCode(404);
        assertTrue(fixture.body().contains("\"error\":\"NOT_FOUND\""), fixture.body());
    }

    @Test
    void handleRequest_should_return_405_and_allow_header_when_method_not_allowed() throws Exception {
        Router router = new Router();
        router.route("GET", "/sessions", (exchange, params) -> {
        });
        router.route("DELETE", "/sessions/{id}", (exchange, params) -> {
        });
        Fixture fixture = fixture("POST", "/sessions");

        router.handleRequest(fixture.exchange);

        verify(fixture.exchange).setStatusCode(405);
        assertEquals("GET", fixture.headers.getFirst(Headers.ALLOW));
    }

    @Test
    void handleRequest_should_ignore_trailing_slash_when_matching() throws Exception {
        Router router = new Router();
        AtomicReference<Boolean> hit = new AtomicReference<Boolean>(false);
        router.route("GET", "/sessions", (exchange, params) -> hit.set(true));
        Fixture fixture = fixture("GET", "/sessions/");

        router.handleRequest(fixture.exchange);

        assertTrue(hit.get());
    }

    @Test
    void handleRequest_should_translate_api_exception_to_status_when_handler_throws() throws Exception {
        Router router = new Router();
        router.route("GET", "/x", (exchange, params) -> {
            throw new ApiException(409, "TURN_IN_PROGRESS", "该会话已有在途回合");
        });
        Fixture fixture = fixture("GET", "/x");

        router.handleRequest(fixture.exchange);

        verify(fixture.exchange).setStatusCode(409);
        assertTrue(fixture.body().contains("TURN_IN_PROGRESS"), fixture.body());
    }

    @Test
    void handleRequest_should_return_500_when_handler_throws_unexpected() throws Exception {
        Router router = new Router();
        router.route("GET", "/x", (exchange, params) -> {
            throw new IllegalStateException("boom: /Users/someone/secret.json");
        });
        Fixture fixture = fixture("GET", "/x");

        router.handleRequest(fixture.exchange);

        verify(fixture.exchange).setStatusCode(500);
        assertTrue(fixture.body().contains("INTERNAL_ERROR"), fixture.body());
        // 异常消息可能带内部细节（路径、上游地址、请求里的密钥片段），只在日志里出现
        assertFalse(fixture.body().contains("boom"), fixture.body());
        assertFalse(fixture.body().contains("secret.json"), fixture.body());
    }

    @Test
    void handleRequest_should_not_echo_request_path_when_no_route_matches() throws Exception {
        Router router = new Router();
        Fixture fixture = fixture("GET", "/nope/../etc/passwd");

        router.handleRequest(fixture.exchange);

        verify(fixture.exchange).setStatusCode(404);
        assertTrue(fixture.body().contains("NOT_FOUND"), fixture.body());
        // 请求路径来自调用方，回显它只是多一个注入面（换行能伪造日志行、ESC 能改终端显示）
        assertFalse(fixture.body().contains("passwd"), fixture.body());
    }

    @Test
    void handleRequest_should_dispatch_and_not_run_handler_when_in_io_thread() throws Exception {
        Router router = new Router();
        AtomicReference<Boolean> hit = new AtomicReference<Boolean>(false);
        router.route("GET", "/x", (exchange, params) -> hit.set(true));
        Fixture fixture = fixture("GET", "/x");
        when(fixture.exchange.isInIoThread()).thenReturn(true);

        router.handleRequest(fixture.exchange);

        ArgumentCaptor<HttpHandler> captor = ArgumentCaptor.forClass(HttpHandler.class);
        verify(fixture.exchange).dispatch(captor.capture());
        assertEquals(router, captor.getValue());
        assertFalse(hit.get());
        Mockito.verify(fixture.exchange, never()).startBlocking();
    }

    @Test
    void route_should_match_root_path_when_template_is_root() throws Exception {
        Router router = new Router();
        AtomicReference<Boolean> hit = new AtomicReference<Boolean>(false);
        router.route("GET", "/", (exchange, params) -> hit.set(true));
        Fixture fixture = fixture("GET", "/");

        router.handleRequest(fixture.exchange);

        assertTrue(hit.get());
    }

    /**
     * 一次请求的测试夹具：mock 交换对象 + 捕获的输出与响应头。
     *
     * @author zcd
     */
    private static final class Fixture {

        /** mock 的交换对象。 */
        private final HttpServerExchange exchange;

        /** 响应体缓冲。 */
        private final ByteArrayOutputStream out;

        /** 响应头。 */
        private final HeaderMap headers;

        /**
         * 构造夹具。
         *
         * @param exchange mock 交换对象
         * @param out      响应体缓冲
         * @param headers  响应头
         */
        private Fixture(HttpServerExchange exchange, ByteArrayOutputStream out, HeaderMap headers) {
            this.exchange = exchange;
            this.out = out;
            this.headers = headers;
        }

        /**
         * 取响应体文本。
         *
         * @return UTF-8 响应体
         */
        private String body() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
