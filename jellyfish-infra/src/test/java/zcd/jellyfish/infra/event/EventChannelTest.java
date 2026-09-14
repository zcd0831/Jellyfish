package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventChannel} 的单元测试：验证异步广播、启动期缓冲、过滤与异常隔离。
 * <p>
 * 用「收集任务的执行器」代替真实线程池，使「发布不阻塞调用线程」与「由订阅者线程派发」可以被确定性断言。
 *
 * @author zcd
 */
class EventChannelTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 手动排水的执行器，用于观察消息尚未被派发的中间态。 */
    private final List<Runnable> queued = new ArrayList<>();

    /** 被测通道。 */
    private final EventChannel channel = new EventChannel(EventChannelOptions.defaults(), registry,
            new EventChannelStats(), queued::add);

    @Test
    void publish_should_return_before_dispatch_and_dispatch_on_drain() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        channel.start();
        channel.subscribe("metrics", ConfigWarningEvent.class, received::add);

        // When：发布只入队
        channel.publish(new ConfigWarningEvent("path", "message"));

        // Then：调用线程没有被订阅者占用
        assertEquals(0, received.size());
        assertEquals(1, channel.stats().getPublishedEvents());

        // When：排空队列（相当于订阅者线程接手）
        queued.forEach(Runnable::run);

        // Then
        assertEquals(1, received.size());
        assertEquals("path", received.get(0).getSource());
    }

    @Test
    void publish_should_buffer_before_start_and_replay_after_start() {
        // Given：启动前发布
        String sessionId = "session-1";
        channel.publish(new ConfigWarningEvent("early", "message"));
        channel.publish(new SessionCreatedEvent("agent-a", sessionId));

        // When
        channel.start();

        // Then：缓冲被回放，且未被重复派发
        assertEquals(2, channel.stats().getPendingReplayed());
        assertEquals(2, queued.size());
    }

    @Test
    void publish_should_drop_and_count_when_pending_buffer_overflows() {
        // Given：缓冲容量为 1
        EventChannel small = new EventChannel(EventChannelOptions.builder().pendingCapacity(1).build(), registry,
                new EventChannelStats(), queued::add);

        // When
        small.publish(new ConfigWarningEvent("a", "1"));
        small.publish(new ConfigWarningEvent("b", "2"));

        // Then
        assertEquals(1L, small.stats().getPendingOverflow());
        assertEquals(1L, small.stats().getDroppedEvents());
        small.start();
        assertEquals(1, queued.size());
    }

    @Test
    void subscribe_should_apply_filter() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        channel.start();
        channel.subscribe("metrics", ConfigWarningEvent.class, event -> "keep".equals(event.getSource()),
                received::add);

        // When
        channel.publish(new ConfigWarningEvent("drop", "message"));
        channel.publish(new ConfigWarningEvent("keep", "message"));
        queued.forEach(Runnable::run);

        // Then
        assertEquals(1, received.size());
        assertEquals("keep", received.get(0).getSource());
    }

    @Test
    void subscribe_should_match_subtype_when_parent_type_is_subscribed() {
        // Given：订阅父类型
        List<JellyfishEvent> received = new ArrayList<>();
        channel.start();
        channel.subscribe("collector", JellyfishEvent.class, received::add);

        // When
        channel.publish(new ConfigWarningEvent("path", "message"));
        queued.forEach(Runnable::run);

        // Then
        assertEquals(1, received.size());
        assertTrue(received.get(0) instanceof ConfigWarningEvent);
    }

    @Test
    void deliver_should_isolate_failing_subscriber_and_count_error() {
        // Given：先注册一个会抛异常的订阅者，再注册一个正常的
        List<ConfigWarningEvent> received = new ArrayList<>();
        channel.start();
        channel.subscribe("broken", ConfigWarningEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        channel.subscribe("healthy", ConfigWarningEvent.class, received::add);

        // When
        channel.publish(new ConfigWarningEvent("path", "message"));
        queued.forEach(Runnable::run);

        // Then：失败被隔离并计入指标，正常订阅者照常收到
        assertEquals(1, received.size());
        assertEquals(1L, channel.stats().getSubscriberErrors());
    }

    @Test
    void deliver_should_count_unmatched_notification() {
        // Given
        channel.start();

        // When
        channel.publish(new ConfigWarningEvent("path", "message"));
        queued.forEach(Runnable::run);

        // Then
        assertEquals(1L, channel.stats().getUnmatchedNotifications());
    }

    @Test
    void subscription_close_should_stop_delivery_and_be_idempotent() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        channel.start();
        Subscription subscription = channel.subscribe("metrics", ConfigWarningEvent.class, received::add);

        // When
        subscription.close();
        subscription.close();
        channel.publish(new ConfigWarningEvent("path", "message"));
        queued.forEach(Runnable::run);

        // Then
        assertTrue(received.isEmpty());
    }

    @Test
    void unsubscribeAll_should_drop_every_registration_of_owner() {
        // Given：同一 owner 同时有事件订阅与同步处理器
        ExtensionRegistry extensions = new ExtensionRegistry(registry);
        AtomicInteger extensionCalls = new AtomicInteger();
        extensions.handle("plugin-a", CommandRequest.class, "calc", null, request -> {
            extensionCalls.incrementAndGet();
            return CommandResult.ok("ok");
        }, RegisterOptions.DEFAULT);
        channel.start();
        channel.subscribe("plugin-a", ConfigWarningEvent.class, event -> {
            // 仅用于产生一条订阅
        });

        // When：一份表意味着按 owner 回收是一次操作
        int removed = channel.unsubscribeAll("plugin-a");

        // Then
        assertEquals(2, removed);
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
    }

    @Test
    void close_should_be_idempotent_and_stop_accepting() {
        // Given
        channel.start();

        // When
        channel.close();
        channel.close();
        channel.publish(new ConfigWarningEvent("path", "message"));

        // Then：关闭后发布即丢弃并记账，既不派发也不进入启动期缓冲
        assertFalse(queued.size() > 0);
        assertEquals(1L, channel.stats().getDroppedEvents());
    }

    @Test
    void subscribe_should_reject_null_arguments() {
        // When / Then
        assertThrows(NullPointerException.class, () -> channel.subscribe("owner", null, null, event -> {
        }));
        assertThrows(NullPointerException.class, () -> channel.subscribe("owner", ConfigWarningEvent.class,
                null, null));
    }

    @Test
    void publish_should_reject_null_event() {
        // When / Then
        assertThrows(NullPointerException.class, () -> channel.publish(null));
    }

    @Test
    void snapshot_should_render_subscriptions() {
        // Given
        channel.subscribe("metrics", ConfigWarningEvent.class, event -> {
            // 仅用于产生一条诊断记录
        });

        // Then
        assertTrue(channel.snapshot().render().contains("ConfigWarningEvent"));
        assertTrue(channel.snapshot().render().contains("<- metrics"));
    }
}
