package zcd.jellyfish.infra.event;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.infra.event.notification.EventDispatchResult;
import zcd.jellyfish.infra.event.notification.EventRegistry;

/**
 * 通知分发器：Guava 层的唯一通知入口。
 * <p>
 * 每类核心通知一个 {@code @Subscribe} 方法，转交 {@link EventRegistry} 广播；另订阅 {@link DeadEvent}，
 * 因为「发布了没有任何 dispatcher 订阅的类型」这种类型级死事件只在这里能发现。
 * <p>
 * 新增核心通知类型时，必须在这里显式加一个方法。
 *
 * @author zcd
 */
final class EventDispatcher {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    /** 细粒度通知注册表。 */
    private final EventRegistry eventRegistry;

    /** 指标。 */
    private final EventBusStats stats;

    /**
     * 构造通知分发器。
     *
     * @param eventRegistry 细粒度通知注册表
     * @param stats         指标
     */
    EventDispatcher(EventRegistry eventRegistry, EventBusStats stats) {
        this.eventRegistry = eventRegistry;
        this.stats = stats;
    }

    /**
     * 派发配置告警通知。
     *
     * @param event 配置告警事件
     */
    @Subscribe
    void onConfigWarning(ConfigWarningEvent event) {
        dispatch(event);
    }

    /**
     * 派发工具调用开始通知。
     *
     * @param event 工具调用开始事件
     */
    @Subscribe
    void onToolCallStarted(ToolCallStartedEvent event) {
        dispatch(event);
    }

    /**
     * 派发工具调用完成通知。
     *
     * @param event 工具调用完成事件
     */
    @Subscribe
    void onToolCallCompleted(ToolCallCompletedEvent event) {
        dispatch(event);
    }

    /**
     * 派发会话创建通知。
     *
     * @param event 会话创建事件
     */
    @Subscribe
    void onSessionCreated(SessionCreatedEvent event) {
        dispatch(event);
    }

    /**
     * 派发插件状态变更通知。
     *
     * @param event 插件状态变更事件
     */
    @Subscribe
    void onPluginStateChanged(PluginStateChangedEvent event) {
        dispatch(event);
    }

    /**
     * 派发插件自定义通知。
     *
     * @param event 插件通知事件
     */
    @Subscribe
    void onPluginNotification(PluginNotificationEvent event) {
        dispatch(event);
    }

    /**
     * 处理类型级死事件：发布了没有任何 dispatcher 订阅的类型，属于编程错误。
     *
     * @param event 死事件
     */
    @Subscribe
    void onDeadEvent(DeadEvent event) {
        stats.deadEventTypes.increment();
        LOG.warn("通知类型无 dispatcher 订阅: {}", event.getEvent().getClass().getName());
    }

    /**
     * 按细粒度注册表广播，单个订阅者失败不影响其他订阅者。
     *
     * @param event 通知事件
     * @param <E>   通知类型
     */
    private <E extends JellyfishEvent> void dispatch(E event) {
        EventDispatchResult result = eventRegistry.dispatch(event);
        if (result.getMatched() == 0) {
            stats.unmatchedNotifications.increment();
            LOG.debug("通知无订阅者命中: {}", event.getClass().getName());
        }
        if (result.getErrors() > 0) {
            stats.subscriberErrors.add(result.getErrors());
        }
    }
}
