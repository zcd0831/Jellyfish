package zcd.jellyfish.infra.metrics;

import java.util.Objects;

import zcd.jellyfish.infra.event.EventChannel;

/**
 * 事件通道健康检查：未运行时判为不可用。
 * <p>
 * 通道一旦关闭，之后所有内核通知都会被静默丢弃（订阅者再也收不到东西），而这种失败从业务结果上
 * 完全看不出来——「界面不刷新」「插件不响应」都会表现为别的问题。因此它值得一条明确的健康项。
 * <p>
 * 顺带报告丢弃与订阅者异常数：它们不影响档位（best-effort 通道本就允许丢弃），
 * 但放在详情里能让「为什么某条通知没到」当场有据可查。
 *
 * @author zcd
 */
public final class EventChannelHealthIndicator implements HealthIndicator {

    /** 检查项名称。 */
    private static final String NAME = "eventChannel";

    /** 事件通道。 */
    private final EventChannel eventChannel;

    /**
     * 构造检查项。
     *
     * @param eventChannel 事件通道，不可为 {@code null}
     */
    public EventChannelHealthIndicator(EventChannel eventChannel) {
        this.eventChannel = Objects.requireNonNull(eventChannel, "eventChannel must not be null");
    }

    @Override
    public HealthResult check() {
        String detail = "dropped=" + eventChannel.stats().getDroppedEvents()
                + ", subscriberErrors=" + eventChannel.stats().getSubscriberErrors()
                + ", queueSize=" + eventChannel.stats().getQueueSize();
        if (!eventChannel.isRunning()) {
            return new HealthResult(NAME, HealthLevel.DOWN, "通道未运行；" + detail);
        }
        return new HealthResult(NAME, HealthLevel.UP, detail);
    }
}
