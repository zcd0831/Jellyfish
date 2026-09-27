package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommandHandlers} 的三态映射与请求校验。
 *
 * @author zcd
 */
class CommandHandlersTest {

    /** 命令域服务。 */
    private CommandManager commands;

    /** 被测试的处理器。 */
    private CommandHandlers handlers;

    @BeforeEach
    void setUp() {
        commands = Mockito.mock(CommandManager.class);
        handlers = new CommandHandlers(commands, ServerConfig.builder("127.0.0.1", 9096).build());
    }

    /**
     * 造一个请求夹具。
     *
     * @param body        请求体
     * @param queryParams 查询参数，可为 {@code null}
     * @return 夹具
     */
    private static Fixture fixture(String body, Map<String, Deque<String>> queryParams) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestContentLength()).thenReturn((long) body.length());
        when(exchange.getInputStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        when(exchange.isResponseStarted()).thenReturn(false);
        when(exchange.getQueryParameters())
                .thenReturn(queryParams == null ? Collections.<String, Deque<String>>emptyMap() : queryParams);
        return new Fixture(exchange, out);
    }

    /**
     * 构造路径参数。
     *
     * @param name  参数名
     * @param value 取值
     * @return 路径参数
     */
    private static PathParams param(String name, String value) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(name, value);
        return new PathParams(values);
    }

    /**
     * 构造单值查询参数。
     *
     * @param name  参数名
     * @param value 取值
     * @return 查询参数映射
     */
    private static Map<String, Deque<String>> query(String name, String value) {
        Deque<String> values = new ArrayDeque<String>();
        values.add(value);
        Map<String, Deque<String>> map = new LinkedHashMap<String, Deque<String>>();
        map.put(name, values);
        return map;
    }

    @Test
    void list_should_map_commands_to_dtos_when_present() {
        when(commands.commands()).thenReturn(Collections.singletonList(
                new CommandInfo("help", new CommandDescriptor("显示帮助", null, Arrays.asList("h")))));
        Fixture fixture = fixture("", null);

        handlers.list(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"name\":\"help\""), fixture.body());
        assertTrue(fixture.body().contains("\"aliases\":[\"h\"]"), fixture.body());
    }

    @Test
    void options_should_pass_session_id_from_query_when_given() {
        when(commands.options("model", "s1")).thenReturn(Collections.singletonList(
                new CommandChoice("openai/gpt-4o", "gpt-4o", null, true)));
        Fixture fixture = fixture("", query("sessionId", "s1"));

        handlers.options(fixture.exchange, param("name", "model"));

        verify(commands).options("model", "s1");
        assertTrue(fixture.body().contains("\"value\":\"openai/gpt-4o\""), fixture.body());
    }

    @Test
    void execute_should_return_200_when_command_ok() {
        when(commands.isCommand("/help")).thenReturn(true);
        when(commands.execute(eq("/help"), eq("s1"))).thenReturn(CommandResult.ok("帮助文本"));
        Fixture fixture = fixture("{\"input\":\"/help\"}", null);

        handlers.execute(fixture.exchange, param("id", "s1"));

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"kind\":\"OK\""), fixture.body());
    }

    @Test
    void execute_should_return_404_when_command_unknown() {
        when(commands.isCommand("/nope")).thenReturn(true);
        when(commands.execute(eq("/nope"), eq("s1"))).thenReturn(CommandResult.unknown("不是命令：/nope"));
        Fixture fixture = fixture("{\"input\":\"/nope\"}", null);

        handlers.execute(fixture.exchange, param("id", "s1"));

        verify(fixture.exchange).setStatusCode(404);
        assertTrue(fixture.body().contains("\"kind\":\"UNKNOWN\""), fixture.body());
    }

    @Test
    void execute_should_return_400_when_input_is_not_command() {
        when(commands.isCommand("hello")).thenReturn(false);
        Fixture fixture = fixture("{\"input\":\"hello\"}", null);

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.execute(fixture.exchange, param("id", "s1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
        assertEquals("NOT_A_COMMAND", error.getCode());
    }

    @Test
    void execute_should_return_400_when_neither_input_nor_name_given() {
        Fixture fixture = fixture("{}", null);

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.execute(fixture.exchange, param("id", "s1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void execute_should_return_400_when_both_input_and_name_given() {
        Fixture fixture = fixture("{\"input\":\"/help\",\"name\":\"help\"}", null);

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.execute(fixture.exchange, param("id", "s1")));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
    }

    @Test
    void execute_should_dispatch_structured_entry_when_name_given() {
        when(commands.execute(eq("model"), any(CommandArguments.class), eq("s1")))
                .thenReturn(CommandResult.ok("已切换"));
        Fixture fixture = fixture("{\"name\":\"model\",\"args\":[\"openai/gpt-4o\"]}", null);

        handlers.execute(fixture.exchange, param("id", "s1"));

        verify(commands).execute(eq("model"), any(CommandArguments.class), eq("s1"));
        verify(fixture.exchange).setStatusCode(200);
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
