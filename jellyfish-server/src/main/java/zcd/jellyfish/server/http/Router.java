package zcd.jellyfish.server.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简路由表：方法 + 路径模板 → 处理器，并把异常统一翻译成响应。
 * <p>
 * <b>为什么手写而不是引框架</b>：接口面只有十来个端点，模板语法只用到「字面段 + {@code {name}} 捕获」，
 * 而一个 REST 框架会带来它自己的异常体系、参数绑定约定与静态资源处理——这些在纯 JSON 接口上一个都用不上。
 * 与 CLI 参数解析「不引 picocli」是同一个取舍。
 * <p>
 * <b>为什么本类同时是 {@link HttpHandler}</b>：它需要做三件所有请求共有的事——把 IO 线程上的请求
 * {@code dispatch} 到工作线程、{@code startBlocking}、把处理器抛出的异常翻译成状态码。让调用方再包一层
 * 「异常处理 handler」只会把这三次必然同时发生的事拆到两处。
 * <p>
 * <b>匹配规则</b>：路径按 {@code /} 切段逐段比较，字面段必须相等，{@code {name}} 段捕获任意单段
 * （不跨 {@code /}）。路径存在但没有同方法的处理器时回 405 并带 {@code Allow} 头；一条都不匹配回 404。
 * <p>
 * 路由表在启动期一次装好，运行期只读，因此匹配不需要加锁。
 *
 * @author zcd
 */
public final class Router implements HttpHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(Router.class);

    /** 路由表，按注册顺序匹配。 */
    private final List<Route> routes = new ArrayList<Route>();

    /**
     * 注册一条路由。
     *
     * @param method   HTTP 方法（如 {@code "GET"}），大小写不敏感
     * @param template 路径模板（如 {@code "/sessions/{id}/chat"}），必须以 {@code /} 开头
     * @param handler  处理器，不可为 {@code null}
     * @return 本路由器，便于链式注册
     */
    public Router route(String method, String template, RouteHandler handler) {
        routes.add(new Route(method, template, handler));
        return this;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            // 处理器会阻塞（读请求体、等 LLM 回合、写 SSE），必须先离开 IO 线程
            exchange.dispatch(this);
            return;
        }
        exchange.startBlocking();
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestPath();
        List<String> pathSegments = split(path);
        try {
            Match match = match(method, pathSegments);
            if (match == null) {
                LOG.info("没有匹配的接口: {} {}", LogText.singleLine(method), LogText.singleLine(path));
                Responses.writeError(exchange, Responses.NOT_FOUND, "NOT_FOUND", "没有这个接口");
                return;
            }
            if (match.isWrongMethod()) {
                String allowed = String.join(", ", match.getAllowedMethods());
                LOG.info("接口不支持该请求方法: {} {}（允许 {}）", LogText.singleLine(method),
                        LogText.singleLine(path), allowed);
                exchange.getResponseHeaders().put(Headers.ALLOW, allowed);
                Responses.writeError(exchange, Responses.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                        "接口不支持该请求方法，允许：" + allowed);
                return;
            }
            match.getRoute().handler.handle(exchange, match.getParams());
        } catch (ApiException e) {
            Responses.writeError(exchange, e.getStatus(), e.getCode(), e.getMessage());
        } catch (Exception e) {
            // 同步侧刻意没有护栏，异常处置是调用点（这里）的责任；已开始的响应由 Responses 兜底
            LOG.error("接口处理失败: {} {}", LogText.singleLine(method), LogText.singleLine(path), e);
            // 文案固定：异常消息可能带内部细节（路径、上游地址、甚至请求里的密钥片段），只进日志
            Responses.writeError(exchange, Responses.INTERNAL_ERROR, Responses.CODE_INTERNAL_ERROR,
                    "服务内部错误");
        }
    }

    /**
     * 在路由表里找一条匹配。
     *
     * @param method       请求方法
     * @param pathSegments 请求路径切成的段
     * @return 命中结果；路径完全不匹配时为 {@code null}
     */
    private Match match(String method, List<String> pathSegments) {
        Set<String> allowed = new LinkedHashSet<String>();
        for (Route route : routes) {
            PathParams params = route.match(pathSegments);
            if (params == null) {
                continue;
            }
            if (route.getMethod().equalsIgnoreCase(method)) {
                return Match.hit(route, params);
            }
            allowed.add(route.getMethod().toUpperCase());
        }
        return allowed.isEmpty() ? null : Match.wrongMethod(allowed);
    }

    /**
     * 把请求路径切成段。
     * <p>
     * 末尾的 {@code /} 被忽略（{@code /sessions/} 与 {@code /sessions} 等价），根路径切成空列表。
     *
     * @param path 请求路径
     * @return 段列表，保证非 {@code null}
     */
    private static List<String> split(String path) {
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("/".equals(normalized) || normalized.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = normalized.substring(1).split("/");
        List<String> segments = new ArrayList<String>(parts.length);
        for (String part : parts) {
            segments.add(part);
        }
        return segments;
    }

    /**
     * 一条注册好的路由。
     *
     * @author zcd
     */
    private static final class Route {

        /** HTTP 方法。 */
        private final String method;

        /** 路径模板（仅用于诊断信息）。 */
        private final String template;

        /** 模板切成的段。 */
        private final List<String> segments;

        /** 处理器。 */
        private final RouteHandler handler;

        /**
         * 构造路由。
         *
         * @param method   HTTP 方法
         * @param template 路径模板
         * @param handler  处理器
         */
        private Route(String method, String template, RouteHandler handler) {
            this.method = method;
            this.template = template;
            this.segments = split(template);
            this.handler = handler;
        }

        /**
         * 获取 HTTP 方法。
         *
         * @return HTTP 方法
         */
        private String getMethod() {
            return method;
        }

        /**
         * 尝试用本路由匹配路径。
         *
         * @param pathSegments 请求路径段
         * @return 匹配成功时返回捕获到的参数（可能为空集）；不匹配返回 {@code null}
         */
        private PathParams match(List<String> pathSegments) {
            if (segments.size() != pathSegments.size()) {
                return null;
            }
            Map<String, String> captured = new LinkedHashMap<String, String>();
            for (int i = 0; i < segments.size(); i++) {
                String template = segments.get(i);
                String actual = pathSegments.get(i);
                if (template.length() >= 2 && template.charAt(0) == '{' && template.charAt(template.length() - 1) == '}') {
                    captured.put(template.substring(1, template.length() - 1), actual);
                } else if (!template.equals(actual)) {
                    return null;
                }
            }
            return new PathParams(captured);
        }

        /**
         * 渲染诊断用的路由描述。
         *
         * @return 描述文本
         */
        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    /**
     * 一次匹配的结果：要么命中某条路由，要么「路径在但方法不对」。
     *
     * @author zcd
     */
    private static final class Match {

        /** 命中的路由；方法不对时为 {@code null}。 */
        private final Route route;

        /** 路径参数；方法不对时为空。 */
        private final PathParams params;

        /** 路径在但方法不对时，该路径允许的方法集合。 */
        private final Set<String> allowedMethods;

        /**
         * 构造匹配结果。
         *
         * @param route          命中的路由，可为 {@code null}
         * @param params         路径参数
         * @param allowedMethods 允许的方法集合
         */
        private Match(Route route, PathParams params, Set<String> allowedMethods) {
            this.route = route;
            this.params = params;
            this.allowedMethods = allowedMethods;
        }

        /**
         * 构造「命中」结果。
         *
         * @param route  路由
         * @param params 路径参数
         * @return 命中结果
         */
        private static Match hit(Route route, PathParams params) {
            return new Match(route, params, Collections.<String>emptySet());
        }

        /**
         * 构造「方法不对」结果。
         *
         * @param allowedMethods 允许的方法集合
         * @return 方法不对结果
         */
        private static Match wrongMethod(Set<String> allowedMethods) {
            return new Match(null, PathParams.empty(), allowedMethods);
        }

        /**
         * 获取命中的路由。
         *
         * @return 路由
         */
        private Route getRoute() {
            return route;
        }

        /**
         * 获取路径参数。
         *
         * @return 路径参数
         */
        private PathParams getParams() {
            return params;
        }

        /**
         * 判断是否为「方法不对」。
         *
         * @return 路径存在但没有该方法时返回 {@code true}
         */
        private boolean isWrongMethod() {
            return route == null;
        }

        /**
         * 获取允许的方法集合。
         *
         * @return 方法集合
         */
        private Set<String> getAllowedMethods() {
            return allowedMethods;
        }
    }
}
