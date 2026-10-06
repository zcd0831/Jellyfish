package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.server.AskBridge;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.dto.AskDto;
import zcd.jellyfish.server.dto.AskOptionDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AskHandlers} 的状态码与「两种作答形状」的收敛规则。
 * <p>
 * 形态与 {@code ApprovalHandlersTest} 对称，多出来的部分是「optionId 与 text 至少给一个」这条校验——
 * 把它俩都缺静默当成「用户放弃了」，会让一次写错的请求看起来像用户自己的决定。
 *
 * @author zcd
 */
class AskHandlersTest {

    /** 提问桥。 */
    private AskBridge bridge;

    /** 被测试的处理器。 */
    private AskHandlers handlers;

    @BeforeEach
    void setUp() {
        bridge = Mockito.mock(AskBridge.class);
        handlers = new AskHandlers(bridge, ServerConfig.builder("127.0.0.1", 9096).build());
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
    void get_should_return_204_when_no_pending_ask() {
        when(bridge.head()).thenReturn(Optional.empty());
        Fixture fixture = fixture("");

        handlers.get(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(204);
        assertEquals("", fixture.body());
    }

    @Test
    void get_should_return_pending_when_present() {
        AskDto dto = new AskDto("r1", "s1", "ask_user", "先做通道还是先做 UI？",
                Arrays.asList(new AskOptionDto("a", "先做通道", "内核与插件先落地")), 1L);
        when(bridge.head()).thenReturn(Optional.of(dto));
        Fixture fixture = fixture("");

        handlers.get(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"requestId\":\"r1\""), fixture.body());
        assertTrue(fixture.body().contains("\"question\""), fixture.body());
        assertTrue(fixture.body().contains("\"optionId\":\"a\""), fixture.body());
    }

    @Test
    void answer_should_resolve_with_option_when_option_id_given() {
        Fixture fixture = fixture("{\"optionId\":\"b\"}");

        handlers.answer(fixture.exchange, requestIdParam("r1"));

        ArgumentCaptor<AskAnswer> answer = ArgumentCaptor.forClass(AskAnswer.class);
        verify(bridge).resolve(anyString(), answer.capture());
        assertEquals(AskAnswer.Status.ANSWERED, answer.getValue().getStatus());
        assertEquals("b", answer.getValue().getOptionId());
        assertNull(answer.getValue().getText());
        verify(fixture.exchange).setStatusCode(204);
    }

    @Test
    void answer_should_resolve_with_text_when_only_text_given() {
        Fixture fixture = fixture("{\"text\":\"都不合适，改成先做 Server\"}");

        handlers.answer(fixture.exchange, requestIdParam("r1"));

        ArgumentCaptor<AskAnswer> answer = ArgumentCaptor.forClass(AskAnswer.class);
        verify(bridge).resolve(anyString(), answer.capture());
        assertEquals("都不合适，改成先做 Server", answer.getValue().getText());
        assertNull(answer.getValue().getOptionId());
    }

    @Test
    void answer_should_prefer_option_id_when_both_given() {
        Fixture fixture = fixture("{\"optionId\":\"a\",\"text\":\"随便写点\"}");

        handlers.answer(fixture.exchange, requestIdParam("r1"));

        ArgumentCaptor<AskAnswer> answer = ArgumentCaptor.forClass(AskAnswer.class);
        verify(bridge).resolve(anyString(), answer.capture());
        assertEquals("a", answer.getValue().getOptionId(), "点选表达的是更明确的意图");
        assertNull(answer.getValue().getText());
    }

    @Test
    void answer_should_return_400_when_both_shapes_missing() {
        Fixture fixture = fixture("{}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.answer(fixture.exchange, requestIdParam("r1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void answer_should_return_400_when_text_is_blank() {
        // 空文本是「按了提交但什么也没写」，不是「用户放弃了」——后者有自己的入口
        Fixture fixture = fixture("{\"text\":\"   \"}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.answer(fixture.exchange, requestIdParam("r1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void answer_should_return_400_when_body_missing() {
        Fixture fixture = fixture("");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.answer(fixture.exchange, requestIdParam("r1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void answer_should_trim_whitespace_around_answer() {
        Fixture fixture = fixture("{\"text\":\"  先做 Server  \"}");

        handlers.answer(fixture.exchange, requestIdParam("r1"));

        ArgumentCaptor<AskAnswer> answer = ArgumentCaptor.forClass(AskAnswer.class);
        verify(bridge).resolve(anyString(), answer.capture());
        assertEquals("先做 Server", answer.getValue().getText());
    }

    @Test
    void answer_should_propagate_404_when_bridge_rejects() {
        doThrow(new ApiException(Responses.NOT_FOUND, "ASK_NOT_FOUND", "x"))
                .when(bridge).resolve(anyString(), Mockito.any(AskAnswer.class));
        Fixture fixture = fixture("{\"optionId\":\"a\"}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.answer(fixture.exchange, requestIdParam("missing")));

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
