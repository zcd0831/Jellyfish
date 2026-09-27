package zcd.jellyfish.server.http;

import zcd.jellyfish.api.JellyfishException;

/**
 * 带 HTTP 语义的接口错误：一次请求应当以哪个状态码、哪个错误码收场。
 * <p>
 * <b>为什么继承 {@link JellyfishException}</b>：仓库约定「异常统一抛 JellyfishException」，
 * 而本类型只多带两个字段——HTTP 状态码与稳定错误码。让它继承既有基类，处理器在需要参数校验失败时
 * 抛的仍然是同一个家族，{@link Router} 只需先捕本类、再捕基类。
 * <p>
 * <b>为什么状态码用 {@code int} 而不是枚举</b>：HTTP 状态码是协议常量，Undertow 的 API 也是 {@code int}；
 * 包一层枚举只会让每个调用点多一次查表。合法的取值由 {@link Responses} 里的常量收口。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ApiException extends JellyfishException {

    /** HTTP 状态码。 */
    private final int status;

    /** 稳定错误码，供前端分支判断（如 {@code SESSION_NOT_FOUND}）。 */
    private final String code;

    /**
     * 构造接口错误。
     *
     * @param status  HTTP 状态码
     * @param code    稳定错误码，不可为空白
     * @param message 可读说明，可为 {@code null}
     */
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 构造带根因的接口错误。
     *
     * @param status  HTTP 状态码
     * @param code    稳定错误码，不可为空白
     * @param message 可读说明，可为 {@code null}
     * @param cause   根因，可为 {@code null}
     */
    public ApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getStatus() {
        return status;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 错误码，保证非 {@code null}
     */
    public String getCode() {
        return code;
    }
}
