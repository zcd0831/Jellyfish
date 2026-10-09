package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API key 鉴权的守门人：把「谁都能调」变成「带着正确密钥才能调」。
 * <p>
 * <b>为什么是包在路由外面的一个 handler 而不是逐个处理器里加检查</b>：漏掉一个处理器就是漏掉一条
 * 攻击面，而「新加接口时忘了加校验」这种疏漏没有任何测试能可靠拦住。包在外面则默认全保护——
 * 新接口不需要做任何事就已经被覆盖，例外只能是显式声明的（见下）。
 * <p>
 * <b>为什么必须自己先 dispatch</b>：写 401 响应体要用阻塞输出流，而阻塞 I/O 不允许发生在 IO 线程上。
 * 本类因此照抄 {@link Router} 的形状——先 {@code dispatch} 到工作线程，再决定放行还是拒绝；
 * 放行时交给下游的 {@link Router}，它会看到自己已经不在 IO 线程上。
 * <p>
 * <b>{@code GET /health} 不校验</b>：它是探活接口，而探活必须能在「还没有密钥」的场景下工作
 * （容器编排的 liveness probe、起服务后的第一条 curl）。它返回的只有 UP/WARN/DOWN 与检查项名字，
 * 没有会话正文、没有路径、没有密钥。
 * <p>
 * <b>比较用 {@link MessageDigest#isEqual}</b>：它按常时比较，避免用 {@code equals} 的短路语义
 * 把密钥逐字节泄露给能反复试探的调用方。这不需要引入任何依赖。
 * <p>
 * <b>不接受 query 参数传密钥</b>：URL 会进访问日志、浏览器历史与 Referer。而本服务的对话入口是
 * {@code POST}，浏览器的 {@code EventSource} 本来就用不了（它只能发 GET），因此客户端无论如何都要用
 * {@code fetch} 流式读取，而它能带请求头——没有「必须靠 query 传密钥」的正当场景。
 * <p>
 * 无状态（密钥与下游 handler 都是 {@code final}），可安全跨线程复用。
 *
 * @author zcd
 */
public final class ApiKeyGuard implements HttpHandler {

    /** 鉴权方案名。 */
    private static final String SCHEME = "Bearer";

    /** 探活路径：不校验。 */
    private static final String HEALTH_PATH = "/health";

    /** {@code Authorization} 头的方案前缀（含空格）。 */
    private static final String SCHEME_PREFIX = SCHEME + " ";

    /** 期望的密钥字节，构造期算一次。 */
    private final byte[] expected;

    /** 下游处理器。 */
    private final HttpHandler next;

    /**
     * 构造守门人。
     *
     * @param apiKey 期望的 API key，不可为空白（空密钥应当表达成「不装守门人」）
     * @param next   下游处理器，不可为 {@code null}
     */
    public ApiKeyGuard(String apiKey, HttpHandler next) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.expected = apiKey.trim().getBytes(StandardCharsets.UTF_8);
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        if (isHealthProbe(exchange) || isAuthorized(exchange)) {
            next.handleRequest(exchange);
            return;
        }
        exchange.startBlocking();
        exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, SCHEME + " realm=\"jellyfish\"");
        Responses.writeError(exchange, Responses.UNAUTHORIZED, Responses.CODE_UNAUTHORIZED,
                "缺少或错误的 API key：请带 Authorization: Bearer <key> 请求头");
    }

    /**
     * 判断本次请求是否鉴权通过。
     *
     * @param exchange HTTP 交换对象
     * @return 通过返回 {@code true}
     */
    private boolean isAuthorized(HttpServerExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (header == null) {
            return false;
        }
        String value = header.trim();
        if (value.length() <= SCHEME_PREFIX.length()
                || !value.regionMatches(true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length())) {
            return false;
        }
        byte[] actual = value.substring(SCHEME_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 判断是否为探活请求。
     * <p>
     * 只放行 {@code GET /health}：方法也一起判，免得将来某天有人在同一个路径上挂一个写操作。
     * <p>
     * 路径走 {@link RequestPath} 归一化后再比：路由按段匹配、本来就忽略尾斜杠，
     * 这里若按请求原文精确比对，{@code GET /health/} 会先被 401 挡下——路由明明认得那个地址。
     *
     * @param exchange HTTP 交换对象
     * @return 是探活请求返回 {@code true}
     */
    private static boolean isHealthProbe(HttpServerExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod().toString())
                && HEALTH_PATH.equals(RequestPath.normalize(exchange.getRequestPath()));
    }
}
