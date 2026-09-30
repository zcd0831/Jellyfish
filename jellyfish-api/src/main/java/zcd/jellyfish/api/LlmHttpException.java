package zcd.jellyfish.api;

/**
 * 厂商以非 2xx 拒绝了一次请求。
 * <p>
 * <b>它住在 {@code api} 而不是 {@code infra}</b>：插件提供的传输实现要把同一种失败报回内核，而插件
 * 看不到 {@code infra}。状态码是「这次失败该怎么应对」的唯一判据，插件 provider 不能因为拿不到
 * 这个类型就把它降级成一条无状态码的普通异常——否则内核就只能对插件 provider 走一条更差的失败路径。
 * 放在这里之后，两侧用的是<b>同一个类</b>，不存在「插件自己造一套、再靠适配器翻译」的分叉。
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
