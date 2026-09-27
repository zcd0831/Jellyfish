package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.metrics.HealthIndicator;
import zcd.jellyfish.infra.metrics.HealthLevel;
import zcd.jellyfish.infra.metrics.HealthResult;
import zcd.jellyfish.server.http.PathParams;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HealthHandler} 的档位汇总与状态码契约。
 *
 * @author zcd
 */
class HealthHandlerTest {

    /**
     * 造一个请求夹具。
     *
     * @return 夹具
     */
    private static Fixture fixture() {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        when(exchange.isResponseStarted()).thenReturn(false);
        return new Fixture(exchange, out);
    }

    @Test
    void handle_should_report_up_when_all_indicator_up() {
        HealthCheck check = new HealthCheck(Collections.singletonList(
                () -> new HealthResult("models", HealthLevel.UP, null)));
        HealthHandler handler = new HealthHandler(check);
        Fixture fixture = fixture();

        handler.handle(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"status\":\"UP\""), fixture.body());
    }

    @Test
    void handle_should_report_warn_when_any_indicator_warn() {
        HealthCheck check = new HealthCheck(Arrays.<HealthIndicator>asList(
                () -> new HealthResult("models", HealthLevel.UP, null),
                () -> new HealthResult("plugins", HealthLevel.WARN, "没有插件")));
        HealthHandler handler = new HealthHandler(check);
        Fixture fixture = fixture();

        handler.handle(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"status\":\"WARN\""), fixture.body());
        assertEquals(true, fixture.body().contains("\"healthy\":true"));
    }

    @Test
    void handle_should_report_down_when_any_indicator_down() {
        HealthCheck check = new HealthCheck(Collections.singletonList(
                () -> new HealthResult("eventChannel", HealthLevel.DOWN, "已关闭")));
        HealthHandler handler = new HealthHandler(check);
        Fixture fixture = fixture();

        handler.handle(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"status\":\"DOWN\""), fixture.body());
        assertTrue(fixture.body().contains("\"healthy\":false"), fixture.body());
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
