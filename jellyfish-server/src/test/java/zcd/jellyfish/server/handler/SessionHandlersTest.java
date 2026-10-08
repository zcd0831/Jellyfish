package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionHandlers} 的状态码与投影契约。
 * <p>
 * {@code Session} 是 final 且构造器包级可见，测试无法直接造一个真会话，因此这里 mock 它——
 * 被测对象是 HTTP 处理逻辑（状态码、缺省值、排序），不是会话聚合根本身。
 *
 * @author zcd
 */
class SessionHandlersTest {

    /** 会话域服务。 */
    private SessionManager sessions;

    /** agent 门面。 */
    private AgentManager agents;

    /** 模型门面。 */
    private ModelManager models;

    /** 被测试的处理器。 */
    private SessionHandlers handlers;

    @BeforeEach
    void setUp() {
        sessions = Mockito.mock(SessionManager.class);
        agents = Mockito.mock(AgentManager.class);
        models = Mockito.mock(ModelManager.class);
        handlers = new SessionHandlers(ServerConfig.builder("127.0.0.1", 9096).build(), sessions, agents, models,
                new zcd.jellyfish.core.conversation.TurnRegistry(ignored -> { }));
    }

    /**
     * 造一个 mock 会话。
     *
     * @param id        会话标识
     * @param updatedAt 最后变更时间戳
     * @return mock 会话
     */
    private static Session session(String id, long updatedAt) {
        Session session = Mockito.mock(Session.class);
        when(session.getSessionId()).thenReturn(id);
        when(session.getCreatedAt()).thenReturn(1L);
        when(session.getUpdatedAt()).thenReturn(updatedAt);
        when(session.getMessages()).thenReturn(Collections.emptyList());
        when(session.size()).thenReturn(0);
        when(session.getUsage()).thenReturn(SessionUsage.EMPTY);
        return session;
    }

    /**
     * 造一个请求夹具。
     *
     * @param body 请求体（空串表示无体）
     * @return 夹具
     */
    private static Fixture fixture(String body) {
        HttpServerExchange exchange = Mockito.mock(HttpServerExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestContentLength()).thenReturn((long) body.length());
        when(exchange.getInputStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        // 真实交换对象上请求头永远在（JsonBody 会读 Content-Type），mock 也要如实
        when(exchange.getRequestHeaders()).thenReturn(new HeaderMap());
        when(exchange.getOutputStream()).thenReturn(out);
        when(exchange.isResponseStarted()).thenReturn(false);
        return new Fixture(exchange, out);
    }

    /**
     * 构造路径参数。
     *
     * @param id 会话标识
     * @return 路径参数
     */
    private static PathParams idParam(String id) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("id", id);
        return new PathParams(values);
    }

    @Test
    void create_should_return_201_when_body_empty() {
        Session created = session("s1", 1L);
        when(sessions.create(eq(null), eq(null), eq(null))).thenReturn(created);
        Fixture fixture = fixture("");

        handlers.create(fixture.exchange, PathParams.empty());

        verify(fixture.exchange).setStatusCode(201);
        assertTrue(fixture.body().contains("\"sessionId\":\"s1\""), fixture.body());
    }

    @Test
    void create_should_return_400_when_agent_not_exists() {
        when(agents.require("ghost")).thenThrow(new JellyfishException("agent not found"));
        Fixture fixture = fixture("{\"agentId\":\"ghost\"}");

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.create(fixture.exchange, PathParams.empty()));

        assertEquals(Responses.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("ghost"));
    }

    @Test
    void get_should_return_404_when_session_missing() {
        when(sessions.require("nope")).thenThrow(new JellyfishException("session not found"));

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.get(fixture("").exchange, idParam("nope")));

        assertEquals(Responses.NOT_FOUND, error.getStatus());
        assertEquals("SESSION_NOT_FOUND", error.getCode());
    }

    @Test
    void get_should_return_snapshot_when_session_exists() {
        Session existing = session("s1", 5L);
        when(sessions.require("s1")).thenReturn(existing);
        Fixture fixture = fixture("");

        handlers.get(fixture.exchange, idParam("s1"));

        verify(fixture.exchange).setStatusCode(200);
        assertTrue(fixture.body().contains("\"sessionId\":\"s1\""), fixture.body());
    }

    @Test
    void delete_should_return_404_when_session_missing() {
        when(sessions.delete("nope")).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> handlers.delete(fixture("").exchange, idParam("nope")));

        assertEquals(Responses.NOT_FOUND, error.getStatus());
    }

    @Test
    void delete_should_return_204_when_session_deleted() {
        Session deleted = session("s1", 1L);
        when(sessions.delete("s1")).thenReturn(deleted);
        Fixture fixture = fixture("");

        handlers.delete(fixture.exchange, idParam("s1"));

        verify(fixture.exchange).setStatusCode(204);
        assertEquals("", fixture.body());
    }

    @Test
    void list_should_sort_by_updated_at_desc_when_multiple_sessions() {
        Session old = session("old", 1L);
        Session latest = session("new", 9L);
        when(sessions.all()).thenReturn(Arrays.asList(old, latest));
        Fixture fixture = fixture("");

        handlers.list(fixture.exchange, PathParams.empty());

        String body = fixture.body();
        assertTrue(body.indexOf("\"sessionId\":\"new\"") < body.indexOf("\"sessionId\":\"old\""), body);
    }

    @Test
    void create_should_pass_body_values_through_without_server_defaults() {
        Session created = session("s1", 1L);
        when(sessions.create(eq("coder"), eq("openai"), eq("gpt-4o")))
                .thenReturn(created);
        Fixture fixture = fixture("{\"agentId\":\"coder\",\"provider\":\"openai\","
                + "\"model\":\"gpt-4o\"}");

        handlers.create(fixture.exchange, PathParams.empty());

        // 服务端不再另有「启动参数默认值」这一层，请求体的取值原样交给内核
        verify(sessions).create(eq("coder"), eq("openai"), eq("gpt-4o"));
        verify(fixture.exchange).setStatusCode(201);
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
