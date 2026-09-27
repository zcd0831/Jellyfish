package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.server.ApprovalBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.dto.ApprovalDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalHandlers} 的状态码与请求校验。
 *
 * @author zcd
 */
class ApprovalHandlersTest {

    /** 审批桥。 */
    private ApprovalBridge bridge;

    /** 被测试的处理器。 */
    private ApprovalHandlers handlers;

    @BeforeEach
    void setUp() {
        bridge = Mockito.mock(ApprovalBridge.class);
        handlers = new ApprovalHandlers(bridge, ServerConfig.builder("127.0.0.1", 9096).build());
    }

    /**
     * 造一个请求夹具。
     *
     * @param body 请求体
     * @return 夹具
     */
    private static Fixture fixture(String body) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestContentLength()).thenReturn((long) body.length());
        when(exchange.getInputStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        when(exchange.isResponseStarted()).thenReturn(false);
        return new Fixture(exchange, out);
    }

    /**
     * 构造路径参数。
     *
     * @param requestId 请求标识
     * @return 路径参数
     */
    private static PathParams requestIdParam(String requestId) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("requestId", requestId);
        return new PathParams(values);
    }

    @Test
    void get_should_return_204_when_no_pending_approval() {
        when(bridge.head()).thenReturn(Optional.empty());
        Fixture fixture = fixture("");

        handlers.get(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(204);
        assertEquals("", fixture.body());
    }

    @Test
    void get_should_return_pending_when_present() {
        ApprovalDto dto = new ApprovalDto("r1", "s1", "coder", "write_file",
                Collections.singletonMap("path", "/a"), PermissionMode.NORMAL, "需要确认", 1L);
        when(bridge.head()).thenReturn(Optional.of(dto));
        Fixture fixture = fixture("");

        handlers.get(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"requestId\":\"r1\""), fixture.body());
    }

    @Test
    void decide_should_return_400_when_approved_missing() {
        Fixture fixture = fixture("{}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.decide(fixture.exchange, requestIdParam("r1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void decide_should_resolve_and_return_204_when_approved_given() {
        Fixture fixture = fixture("{\"approved\":true}");

        handlers.decide(fixture.exchange, requestIdParam("r1"));

        verify(bridge).resolve("r1", true);
        verify(fixture.exchange).setStatusCode(204);
    }

    @Test
    void decide_should_propagate_404_when_bridge_rejects() {
        doThrow(new ApiException(Responses.NOT_FOUND, "APPROVAL_NOT_FOUND", "x"))
                .when(bridge).resolve(anyString(), Mockito.anyBoolean());
        Fixture fixture = fixture("{\"approved\":false}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.decide(fixture.exchange, requestIdParam("missing")));

        assertEquals(Responses.NOT_FOUND, error.getStatus());
    }

    /**
     * 一次请求的测试夹具。
     *
     * @author zcd
     */
    private static final class Fixture {

        /** mock 交换对象。 */
        private final HttpServerExchange exchange;

        /** 响应体缓冲。 */
        private final ByteArrayOutputStream out;

        /**
         * 构造夹具。
         *
         * @param exchange mock 交换对象
         * @param out      响应体缓冲
         */
        private Fixture(HttpServerExchange exchange, ByteArrayOutputStream out) {
            this.exchange = exchange;
            this.out = out;
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
