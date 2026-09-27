package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Responses} 的状态码、内容类型与「响应已开始」处置。
 *
 * @author zcd
 */
class ResponsesTest {

    /**
     * 造一个可写响应的交换对象。
     *
     * @param out 响应体缓冲
     * @return mock 交换对象
     */
    private static HttpServerExchange exchange(ByteArrayOutputStream out) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        return exchange;
    }

    /**
     * 取输出文本。
     *
     * @param out 缓冲
     * @return UTF-8 文本
     */
    private static String text(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void writeJson_should_set_status_content_type_and_body_when_body_given() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServerExchange exchange = exchange(out);

        Responses.writeJson(exchange, Responses.CREATED, Collections.singletonMap("ok", true));

        verify(exchange).setStatusCode(201);
        assertEquals("application/json; charset=utf-8",
                exchange.getResponseHeaders().getFirst(Headers.CONTENT_TYPE));
        assertEquals("{\"ok\":true}", text(out));
    }

    @Test
    void writeError_should_shape_body_as_error_and_message_when_called() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServerExchange exchange = exchange(out);

        Responses.writeError(exchange, Responses.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在");

        verify(exchange).setStatusCode(404);
        assertEquals("{\"error\":\"SESSION_NOT_FOUND\",\"message\":\"会话不存在\"}", text(out));
    }

    @Test
    void writeNoContent_should_set_204_and_write_nothing_when_called() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServerExchange exchange = exchange(out);

        Responses.writeNoContent(exchange);

        verify(exchange).setStatusCode(204);
        assertEquals("", text(out));
        verify(exchange).endExchange();
    }

    @Test
    void writeJson_should_only_end_exchange_when_response_already_started() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServerExchange exchange = exchange(out);
        when(exchange.isResponseStarted()).thenReturn(true);

        Responses.writeJson(exchange, Responses.OK, Collections.singletonMap("late", true));

        verify(exchange, never()).setStatusCode(Mockito.anyInt());
        verify(exchange).endExchange();
        assertEquals("", text(out));
    }

    @Test
    void writeJson_should_not_throw_when_client_disconnected() {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        when(exchange.getOutputStream()).thenReturn(failing);

        assertDoesNotThrow(() -> Responses.writeJson(exchange, Responses.OK, "x"));
        verify(exchange).endExchange();
    }
}
