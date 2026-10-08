package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@link JsonBody} 对媒体类型的判据：声明了就得是 JSON，没声明就不猜。
 *
 * @author zcd
 */
@DisplayName("请求体媒体类型")
class JsonBodyTest {

    /** 一个合法的请求体。 */
    private static final String BODY = "{\"approved\":true}";

    @Test
    @DisplayName("application/json 放行（带 charset 参数也算）")
    void read_should_accept_whenContentTypeIsJson() {
        assertEquals(Boolean.TRUE, JsonBody.read(exchange(BODY, "application/json"), Payload.class,
                1024).getApproved());
        assertEquals(Boolean.TRUE, JsonBody.read(exchange(BODY, "application/json; charset=utf-8"),
                Payload.class, 1024).getApproved());
        assertEquals(Boolean.TRUE, JsonBody.read(exchange(BODY, "Application/JSON"), Payload.class,
                1024).getApproved());
    }

    @Test
    @DisplayName("结构化 JSON 的媒体类型（+json 后缀）也放行")
    void read_should_accept_whenContentTypeHasJsonSuffix() {
        assertEquals(Boolean.TRUE, JsonBody.read(exchange(BODY, "application/vnd.api+json"),
                Payload.class, 1024).getApproved());
    }

    @Test
    @DisplayName("浏览器能跨站发出的那三种内容类型一律 415")
    void read_should_reject_whenContentTypeIsNotJson() {
        String[] browserTypes = {"text/plain", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x"};
        for (String contentType : browserTypes) {
            ApiException error = assertThrows(ApiException.class,
                    () -> JsonBody.read(exchange(BODY, contentType), Payload.class, 1024), contentType);
            assertEquals(Responses.UNSUPPORTED_MEDIA_TYPE, error.getStatus(), contentType);
            assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode(), contentType);
        }
    }

    @Test
    @DisplayName("没有声明 Content-Type 就放行：判断依据只能是调用方声明了什么")
    void read_should_accept_whenContentTypeMissing() {
        assertEquals(Boolean.TRUE, JsonBody.read(exchange(BODY, null), Payload.class, 1024).getApproved());
    }

    @Test
    @DisplayName("空体仍然返回 null（「没有体」与「体非法」是两件事）")
    void read_should_returnNull_whenBodyIsEmpty() {
        assertNull(JsonBody.read(exchange("", "application/json"), Payload.class, 1024));
    }

    /**
     * 造一个请求交换对象。
     *
     * @param body        请求体
     * @param contentType 声明的媒体类型，{@code null} 表示不声明
     * @return 交换对象
     */
    private static HttpServerExchange exchange(String body, String contentType) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        HeaderMap headers = new HeaderMap();
        if (contentType != null) {
            headers.put(Headers.CONTENT_TYPE, contentType);
        }
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getRequestContentLength()).thenReturn((long) body.length());
        when(exchange.getInputStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return exchange;
    }

    /**
     * 读体用的最小 DTO。
     *
     * @author zcd
     */
    public static class Payload {

        /** 一个布尔字段，够用来验证「读回来的是这一份体」。 */
        private Boolean approved;

        /**
         * 取字段值。
         *
         * @return 字段值
         */
        public Boolean getApproved() {
            return approved;
        }

        /**
         * 设置字段值。
         *
         * @param approved 字段值
         */
        public void setApproved(Boolean approved) {
            this.approved = approved;
        }
    }
}
