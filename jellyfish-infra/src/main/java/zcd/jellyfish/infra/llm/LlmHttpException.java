package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;

/**
 * 厂商以非 2xx 拒绝了一次请求。
 * <p>
 * <b>为什么要有这个类型，而不是继续只抛 {@link JellyfishException}</b>：状态码是「这次失败该怎么应对」的
 * 第一个判据——400 多半是某个字段不被端点接受（应当降级），429 与 5xx 是暂时性的（应当稍后重试），
 * 而这两条路的处理完全相反。此前状态码只存在于异常消息的文本里，订阅方只能去正则匹配，
 * 那既脆弱、实际上也没人会去做。
 * <p>
 * <b>它仍然是一个 {@link JellyfishException}</b>：既有的捕获点一个都不用改，新的订阅方按需下钻。
 *
 * @author zcd
 */
public class LlmHttpException extends JellyfishException {

    /** 序列化标识。 */
    private static final long serialVersionUID = 1L;

    /** HTTP 状态码。 */
    private final int statusCode;

    /**
     * 构造异常。
     *
     * @param message    异常消息，已包含状态码与响应体摘要
     * @param statusCode HTTP 状态码
     */
    public LlmHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码，保证非 0
     */
    public int getStatusCode() {
        return statusCode;
    }
}
