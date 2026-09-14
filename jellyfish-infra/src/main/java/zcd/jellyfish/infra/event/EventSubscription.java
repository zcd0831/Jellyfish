package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.infra.registry.HandlerRegistration;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通知订阅句柄：关闭时从共用注册表移除对应注册项，重复关闭幂等。
 *
 * @author zcd
 */
final class EventSubscription implements Subscription {

    /** 共用注册表。 */
    private final TypeRegistry registry;

    /** 对应注册项。 */
    private final HandlerRegistration registration;

    /** 幂等标记。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造订阅句柄。
     *
     * @param registry     共用注册表
     * @param registration 注册项
     */
    EventSubscription(TypeRegistry registry, HandlerRegistration registration) {
        this.registry = registry;
        this.registration = registration;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registry.remove(registration);
        }
    }
}
