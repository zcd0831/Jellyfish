package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 模型调用失败事件：一次对话调用被厂商拒绝，或根本没发出去（网络层失败）。
 * <p>
 * <b>为什么需要它</b>：{@link LlmCallCompletedEvent} 只覆盖成功的调用，而「被拒绝」这件事此前只在
 * 异常消息里可见——插件既看不到，也就无法据此自我修正。最具体的例子是缓存字段：
 * 某些端点不认 {@code prompt_cache_retention} 这类字段并<b>直接以 400 拒绝</b>，
 * 而「被拒了就别再发」这条规则只有插件才做得对（它掌握厂商与模型的知识）。
 * 没有本事件，那条规则就只能等到用户手动改配置。
 * <p>
 * <b>状态码是载荷里最有用的一个字段</b>：400 多半是某个字段不被接受（应当降级），
 * 429 与 5xx 是暂时性的（应当稍后重试），两者的处理完全相反。因此这里给出结构化状态码，
 * 而不是把状态码留在消息文本里让订阅方去解析。{@code 0} 表示不是 HTTP 层面的失败。
 * <p>
 * <b>它与完成的调用互斥</b>：一次调用要么成功（发完成事件）、要么失败（发本事件），
 * 不会有用量可言。压缩与缓存保活这两条不产生会话消息的路径同样适用。
 * <p>
 * <b>载荷是 api 侧值类型</b>：订阅方（含插件）不需要依赖内核实现。
 *
 * @author zcd
 */
public final class LlmCallFailedEvent extends AbstractJellyfishEvent {

    /** provider 名，可为 {@code null}。 */
    private final String provider;

    /** 模型标识，可为 {@code null}。 */
    private final String model;

    /** HTTP 状态码；{@code 0} 表示不是 HTTP 层面的失败（网络异常、超时等）。 */
    private final int statusCode;

    /** 失败原因摘要，已截断。 */
    private final String message;

    /**
     * 构造模型调用失败事件。
     *
     * @param sessionId  会话标识，可为 {@code null}
     * @param provider   provider 名，可为 {@code null}
     * @param model      模型标识，可为 {@code null}
     * @param statusCode HTTP 状态码，{@code 0} 表示非 HTTP 失败
     * @param message    失败原因摘要，可为 {@code null}
     */
    public LlmCallFailedEvent(String sessionId, String provider, String model, int statusCode, String message) {
        super(sessionId);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.message = message;
    }

    /**
     * 获取 provider 名。
     *
     * @return provider 名，可能为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识，可能为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return 状态码，{@code 0} 表示非 HTTP 失败
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 获取失败原因摘要。
     *
     * @return 摘要，可能为 {@code null}
     */
    public String getMessage() {
        return message;
    }

    /**
     * 判断这是不是一次「请求本身有问题」的失败（4xx 且不是限流）。
     * <p>
     * <b>为什么把它单独拎出来</b>：这是插件最需要的一个判据——「字段不被接受」属于这一类
     * （该降级），而限流与对端故障不属于（该重试）。把它留给每个订阅方自己判断，
     * 就会各自写出略有出入的区间。
     *
     * @return 请求被拒返回 {@code true}
     */
    public boolean isRejected() {
        return statusCode >= 400 && statusCode < 500 && statusCode != 429;
    }

    @Override
    public String toString() {
        return "LlmCallFailedEvent{provider=" + provider + ", model=" + model + ", status=" + statusCode
                + ", message=" + message + '}';
    }
}
