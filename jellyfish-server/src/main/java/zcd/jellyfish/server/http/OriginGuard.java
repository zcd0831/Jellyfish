package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * 跨站请求守门人：拒绝来源与自身不一致的写请求。
 * <p>
 * <b>它挡的是什么</b>：浏览器对「简单请求」（{@code text/plain} 这类不触发预检的 {@code POST}）
 * 会照常发出并执行副作用，只是响应不可读。于是一个恶意网页可以用
 * {@code fetch('http://127.0.0.1:9069/sessions', {method:'POST', mode:'no-cors', ...})}
 * 驱动你本机正在跑的 agent——建会话、发消息、跑命令。缺省只绑回环并不挡这个：请求确实来自本机，
 * 只是由别人的页面发起。
 * <p>
 * <b>判据是 {@code Origin}（其次 {@code Referer}）与自身是否一致</b>：
 * <ul>
 *     <li>两个头都没有 → <b>放过</b>。非浏览器客户端（{@code curl}、SDK）不带这两个头，
 *     而它们也不受同源策略约束，拦下来只会让命令行用不了；</li>
 *     <li>有 {@code Origin} → 它的 host 必须与请求的 {@code Host} 一致；</li>
 *     <li>没有 {@code Origin} 但有 {@code Referer} → 同上用 referer 的 host 判（覆盖旧浏览器）；</li>
 *     <li>{@code Origin} 为字面量 {@code null}（沙箱 iframe、{@code data:} 页面）→ 拒绝。</li>
 * </ul>
 * <b>只作用于写方法</b>（{@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}）：{@code GET} 无副作用，
 * 而浏览器的跨站读本来就被同源策略挡住（本服务也不发 CORS 头，因此不提供任何跨源读取）。
 * <p>
 * <b>为什么不按 {@code Content-Type} 拦</b>：要求 {@code application/json} 同样能挡住简单请求，
 * 但它会误伤 {@code curl -d}（它默认发 {@code application/x-www-form-urlencoded}），
 * 而来源校验本来就更贴近要防的那件事。
 * <p>
 * 无状态（只持有下游处理器），可安全跨线程复用。
 *
 * @author zcd
 */
public final class OriginGuard implements HttpHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OriginGuard.class);

    /** 来源头。 */
    private static final String ORIGIN = "Origin";

    /** 引用页头，{@code Origin} 缺席时的退路。 */
    private static final String REFERER = "Referer";

    /** 有副作用、需要校验来源的方法。 */
    private static final String[] STATE_CHANGING_METHODS = {"POST", "PUT", "PATCH", "DELETE"};

    /** 下游处理器。 */
    private final HttpHandler next;

    /**
     * 构造守门人。
     *
     * @param next 下游处理器，不可为 {@code null}
     */
    public OriginGuard(HttpHandler next) {
        this.next = Objects.requireNonNull(next, "next must not be null");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        if (!isStateChanging(exchange) || isSameOrigin(exchange)) {
            next.handleRequest(exchange);
            return;
        }
        LOG.warn("拒绝跨站写请求: method={} path={} origin={} host={}",
                exchange.getRequestMethod(), exchange.getRequestPath(),
                exchange.getRequestHeaders().getFirst(ORIGIN), hostOf(exchange));
        exchange.startBlocking();
        Responses.writeError(exchange, Responses.FORBIDDEN, Responses.CODE_FORBIDDEN,
                "拒绝跨站请求：本服务只接受同源的写请求（浏览器发起时参考 Origin/Referer）");
    }

    /**
     * 判断请求方法是否有副作用。
     *
     * @param exchange HTTP 交换对象
     * @return 需要校验来源返回 {@code true}
     */
    private static boolean isStateChanging(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod() == null
                ? "" : exchange.getRequestMethod().toString().toUpperCase(Locale.ROOT);
        for (String candidate : STATE_CHANGING_METHODS) {
            if (candidate.equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断请求来源是否与自身同源。
     *
     * @param exchange HTTP 交换对象
     * @return 同源（或无法判定来源）返回 {@code true}
     */
    private static boolean isSameOrigin(HttpServerExchange exchange) {
        String source = exchange.getRequestHeaders().getFirst(ORIGIN);
        if (source == null || source.trim().isEmpty()) {
            source = exchange.getRequestHeaders().getFirst(REFERER);
        }
        if (source == null || source.trim().isEmpty()) {
            // 非浏览器客户端不带这两个头，且不受同源策略约束
            return true;
        }
        String sourceHost = hostOf(source.trim());
        if (sourceHost == null) {
            // 字面量 "null"（沙箱 iframe、data: 页面）或非法值：判不出同源就不放行
            return false;
        }
        return sourceHost.equalsIgnoreCase(hostOf(exchange));
    }

    /**
     * 取请求自身的 host（含端口，缺省端口视为无端口）。
     *
     * @param exchange HTTP 交换对象
     * @return host 文本；取不到时返回空串
     */
    private static String hostOf(HttpServerExchange exchange) {
        String host = exchange.getHostAndPort();
        if (host == null || host.trim().isEmpty()) {
            host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        }
        return host == null ? "" : host.trim();
    }

    /**
     * 从一个绝对 URL 里取出 host 与端口。
     *
     * @param url 绝对 URL 文本，可为 {@code null}
     * @return host（含端口）；取不到或不是绝对 URL 时返回 {@code null}
     */
    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            return uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
