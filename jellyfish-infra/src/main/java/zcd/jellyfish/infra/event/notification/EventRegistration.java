package zcd.jellyfish.infra.event.notification;

import zcd.jellyfish.api.event.JellyfishEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 通知订阅项：来源 + 事件类型 + 过滤条件 + 监听器。
 *
 * @author zcd
 */
final class EventRegistration {

    /** 来源（内置组件名或 pluginId），用于诊断与按来源回收。 */
    private final String owner;

    /** 订阅的事件类型。 */
    private final Class<? extends JellyfishEvent> eventType;

    /** 过滤条件，永不为 {@code null}。 */
    private final Predicate<JellyfishEvent> filter;

    /** 监听器，入参一定是 {@link #eventType} 的实例。 */
    private final Consumer<JellyfishEvent> listener;

    /**
     * 构造订阅项。
     *
     * @param owner     来源
     * @param eventType 事件类型
     * @param filter    过滤条件
     * @param listener  监听器
     */
    EventRegistration(String owner, Class<? extends JellyfishEvent> eventType,
                      Predicate<JellyfishEvent> filter, Consumer<JellyfishEvent> listener) {
        this.owner = owner;
        this.eventType = eventType;
        this.filter = filter;
        this.listener = listener;
    }

    /**
     * 获取来源。
     *
     * @return 来源
     */
    String getOwner() {
        return owner;
    }

    /**
     * 获取订阅的事件类型。
     *
     * @return 事件类型
     */
    Class<? extends JellyfishEvent> getEventType() {
        return eventType;
    }

    /**
     * 判断事件是否通过过滤条件。
     *
     * @param event 事件对象
     * @return 通过返回 {@code true}
     */
    boolean accepts(JellyfishEvent event) {
        return filter.test(event);
    }

    /**
     * 调用监听器。
     *
     * @param event 事件对象
     */
    void invoke(JellyfishEvent event) {
        listener.accept(event);
    }
}
