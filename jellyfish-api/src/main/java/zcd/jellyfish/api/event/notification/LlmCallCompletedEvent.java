package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

/**
 * 模型调用完成事件：一次调用已经计入会话用量之后广播，用于指标与审计。
 * <p>
 * <b>为什么需要它</b>：用量此前只写进会话状态，没有任何通知——于是「这个进程一共命中了多少缓存」
 * 这类跨会话的账无从算起（{@code /usage} 只在会话内可读，进程退出就没有了）。
 * 本事件把「一次调用花了多少」放到可订阅的通道上，指标的订阅方因此不必去遍历会话。
 * <p>
 * <b>失败的调用不会走到这里</b>：调用本身失败是上抛的（见 {@code ReActLooper}），没有用量可言。
 * 因此本事件只表达「这一次真的花掉了这些 token」，不携带成功与否。
 * <p>
 * <b>载荷是 api 侧快照而不是内核的 {@code LlmUsage}</b>：跨边界载荷必须是 api 值类型，
 * 这样订阅方（含插件）不需要依赖内核实现。
 * <p>
 * <b>它可能在「没有消息」的路径上发出</b>：{@code /compact} 的摘要调用不产生会话消息，
 * 但它花的是真实的 token，同样计入用量、同样发本事件。
 *
 * @author zcd
 */
public final class LlmCallCompletedEvent extends AbstractJellyfishEvent {

    /** provider 名，可为 {@code null}（会话没配时）。 */
    private final String provider;

    /** 模型标识，可为 {@code null}（会话没配时）。 */
    private final String model;

    /** 这一次调用的用量，保证非 {@code null}。 */
    private final TokenUsageSnapshot usage;

    /**
     * 构造模型调用完成事件。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param provider  provider 名，可为 {@code null}
     * @param model     模型标识，可为 {@code null}
     * @param usage     这一次调用的用量，不可为 {@code null}
     */
    public LlmCallCompletedEvent(String sessionId, String provider, String model, TokenUsageSnapshot usage) {
        super(sessionId);
        this.provider = provider;
        this.model = model;
        this.usage = usage;
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
     * 获取这一次调用的用量。
     *
     * @return 用量快照，保证非 {@code null}
     */
    public TokenUsageSnapshot getUsage() {
        return usage;
    }
}
