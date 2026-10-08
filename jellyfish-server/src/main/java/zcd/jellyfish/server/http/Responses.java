package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.server.dto.ApiErrorDto;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 响应写出的唯一入口：把「状态码 + JSON」这件事收在一处。
 * <p>
 * <b>为什么要收口</b>：状态码写在哪、{@code Content-Type} 是什么、响应体形状长什么样，
 * 如果散在十几个处理器里，就会出现「同一种错误两种状态码、两个字段名」。这里同时保证两件事：
 * <ul>
 *     <li>成功响应恒为 {@code application/json; charset=utf-8}；</li>
 *     <li>失败响应恒为 {@code {"error": "CODE", "message": "..."}}。</li>
 * </ul>
 * <b>响应已开始时的处置</b>：SSE 流一旦写出首帧，HTTP 头就已经发出，此时再写 JSON 错误体是无效的。
 * 因此所有写出方法都先看 {@link HttpServerExchange#isResponseStarted()}——已开始就只结束交换，
 * 由 SSE 流自己的终态帧负责把错误告诉客户端。
 * <p>
 * 本类无状态，全部为静态方法。
 *
 * @author zcd
 */
public final class Responses {

    /** 成功：带响应体。 */
    public static final int OK = 200;

    /** 成功：已创建。 */
    public static final int CREATED = 201;

    /** 成功：无响应体。 */
    public static final int NO_CONTENT = 204;

    /** 未通过鉴权（缺少或错误的 API key）。 */
    public static final int UNAUTHORIZED = 401;

    /** 请求体或参数不合法。 */
    public static final int BAD_REQUEST = 400;

    /** 资源不存在。 */
    public static final int NOT_FOUND = 404;

    /** 路径存在但方法不对。 */
    public static final int METHOD_NOT_ALLOWED = 405;

    /** 状态冲突（如该会话已有在途回合）。 */
    public static final int CONFLICT = 409;

    /** 请求体过大。 */
    public static final int PAYLOAD_TOO_LARGE = 413;

    /** 拒绝跨站请求。 */
    public static final int FORBIDDEN = 403;

    /** 服务内部错误。 */
    public static final int INTERNAL_ERROR = 500;

    /** 并发流超限。 */
    public static final int SERVICE_UNAVAILABLE = 503;

    /** 成功错误码常量。 */
    public static final String CODE_BAD_REQUEST = "BAD_REQUEST";

    /** 内部错误码常量。 */
    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    /** 鉴权失败错误码常量。 */
    public static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";

    /** 跨站请求被拒错误码常量。 */
    public static final String CODE_FORBIDDEN = "FORBIDDEN";

    /** JSON 内容类型。 */
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    /**
     * 工具类，禁止实例化。
     */
    private Responses() {
    }

    /**
     * 写一个 JSON 成功响应。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @param status   HTTP 状态码
     * @param body     响应体对象，可为 {@code null}（写为 JSON {@code null}）
     */
    public static void writeJson(HttpServerExchange exchange, int status, Object body) {
        writeBytes(exchange, status, ObjectMapperWrapper.writeValueAsBytes(body));
    }

    /**
     * 写一个错误响应。
     * <p>
     * 错误体形状由 {@link ApiErrorDto} 定义，前端只认 {@code error} 字段做分支，{@code message}
     * 只用于展示。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @param status   HTTP 状态码
     * @param code     稳定错误码，不可为空白
     * @param message  可读说明，可为 {@code null}
     */
    public static void writeError(HttpServerExchange exchange, int status, String code, String message) {
        writeBytes(exchange, status, ObjectMapperWrapper.writeValueAsBytes(new ApiErrorDto(code, message)));
    }

    /**
     * 写一个无响应体的成功响应。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     */
    public static void writeNoContent(HttpServerExchange exchange) {
        if (exchange.isResponseStarted()) {
            exchange.endExchange();
            return;
        }
        exchange.setStatusCode(NO_CONTENT);
        exchange.endExchange();
    }

    /**
     * 写字节响应。
     * <p>
     * 响应已开始（SSE 已出帧）时只结束交换：此时状态码与头都已发出，写任何体都不会到达客户端，
     * 而那正是「错误已经由流内终态帧报过」的情形。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @param status   HTTP 状态码
     * @param bytes    响应体字节，保证非 {@code null}
     */
    private static void writeBytes(HttpServerExchange exchange, int status, byte[] bytes) {
        if (exchange.isResponseStarted()) {
            exchange.endExchange();
            return;
        }
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, JSON_CONTENT_TYPE);
        try {
            OutputStream out = exchange.getOutputStream();
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            // 客户端提前断开：这不是服务端故障，无需再报，也不能再报（头已发出）
            exchange.endExchange();
            return;
        }
        exchange.endExchange();
    }
}
