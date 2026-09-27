package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;

/**
 * 一条路由的处理器。
 * <p>
 * 与 {@code io.undertow.server.HttpHandler} 的差别只有一个：多收一份路径参数。
 * 路径模板里的 {@code {name}} 由 {@link Router} 解析成 {@link PathParams} 再传进来，
 * 处理器因此不必自己从原始路径上抠段。
 * <p>
 * 处理器直接往 {@link HttpServerExchange} 上写响应；抛出的 {@link ApiException} 由 {@link Router}
 * 统一翻译成状态码，其它异常一律 500。处理器运行在 Undertow 的工作线程（{@link Router} 已做过
 * {@code dispatch} 与 {@code startBlocking}），因此可以阻塞。
 *
 * @author zcd
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * 处理一次请求。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @param params   路径参数，保证非 {@code null}（无参数时为空集）
     * @throws Exception 处理失败；{@link ApiException} 会被翻译成对应状态码，其余归 500
     */
    void handle(HttpServerExchange exchange, PathParams params) throws Exception;
}
