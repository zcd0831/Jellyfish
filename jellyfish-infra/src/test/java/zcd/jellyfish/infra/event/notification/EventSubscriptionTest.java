package zcd.jellyfish.infra.event.notification;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventSubscription} 的单元测试：验证关闭移除与幂等。
 *
 * @author zcd
 */
class EventSubscriptionTest {

    @Test
    void close_should_remove_subscription_and_be_idempotent() {
        // Given
        EventRegistry registry = new EventRegistry();
        List<ConfigWarningEvent> received = new ArrayList<>();
        Subscription subscription = registry.subscribe("plugin-a", ConfigWarningEvent.class, null, received::add);

        // When
        subscription.close();
        subscription.close();

        // Then
        registry.dispatch(new ConfigWarningEvent("path", "message"));
        assertTrue(received.isEmpty());
        assertTrue(registry.isEmpty());
    }

    @Test
    void close_should_not_fail_when_event_type_not_in_registry() {
        // Given：注册项未真正加入注册表
        EventRegistry registry = new EventRegistry();
        EventRegistration registration = new EventRegistration("plugin-a", ConfigWarningEvent.class,
                event -> true, event -> {
                    // 仅用于构造句柄
                });
        EventSubscription subscription = new EventSubscription(registry, ConfigWarningEvent.class, registration);

        // When
        subscription.close();

        // Then
        assertTrue(registry.isEmpty());
    }
}
