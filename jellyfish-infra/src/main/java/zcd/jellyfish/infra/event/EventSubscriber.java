package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.event.JellyfishEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 通知订阅者：来源 + 事件类型 + 过滤条件 + 监听器。
 * <p>
 * 包私有且作为不透明 handler 存进共用的注册表：注册表只保管它，本类负责把类型安全的
 * 过滤谓词与监听器包装成「任何事件进来都能安全调用」的形态。
 *
 * @author zcd
 */
final class EventSubscriber {

    /** 来源（内核组件名或 pluginId），用于诊断与按来源回收。 */
    private final String owner;

    /** 订阅的事件类型。 */
    private final Class<? extends JellyfishEvent> eventType;

    /** 过滤条件，永不为 {@code null}。 */
    private final Predicate<JellyfishEvent> filter;

    /** 监听器，入参一定是 {@link #eventType} 的实例。 */
    private final Consumer<JellyfishEvent> listener;

    /**
     * 构造订阅者。
     *
     * @param owner     来源
     * @param eventType 事件类型
     * @param filter    过滤条件，永不为 {@code null}
     * @param listener  监听器，永不为 {@code null}
     */
    EventSubscriber(String owner, Class<? extends JellyfishEvent> eventType,
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
     * 判断事件是否应投递给本订阅者。
     *
     * @param event 事件对象
     * @return 应投递返回 {@code true}
     */
    boolean accepts(JellyfishEvent event) {
        return filter.test(event);
    }

    /**
     * 投递事件。
     *
     * @param event 事件对象
     */
    void deliver(JellyfishEvent event) {
        listener.accept(event);
    }

    @Override
    public String toString() {
        return eventType.getSimpleName() + "<- " + owner;
    }
}
