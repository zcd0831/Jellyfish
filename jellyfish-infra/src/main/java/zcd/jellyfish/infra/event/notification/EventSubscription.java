package zcd.jellyfish.infra.event.notification;

import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通知订阅句柄：关闭时从注册表移除对应订阅项，重复关闭幂等。
 *
 * @author zcd
 */
final class EventSubscription implements Subscription {

    /** 所属注册表。 */
    private final EventRegistry registry;

    /** 订阅的事件类型。 */
    private final Class<? extends JellyfishEvent> eventType;

    /** 对应订阅项。 */
    private final EventRegistration registration;

    /** 幂等标记。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造订阅句柄。
     *
     * @param registry     所属注册表
     * @param eventType    事件类型
     * @param registration 订阅项
     */
    EventSubscription(EventRegistry registry, Class<? extends JellyfishEvent> eventType,
                      EventRegistration registration) {
        this.registry = registry;
        this.eventType = eventType;
        this.registration = registration;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registry.remove(eventType, registration);
        }
    }
}
