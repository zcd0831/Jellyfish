package zcd.jellyfish.infra.event;

import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.PermissionCheckCommand;
import zcd.jellyfish.api.event.command.PermissionDecision;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JellyfishEventBus} 的单元测试：使用「当前线程直接执行」的通知派发器获得同步语义。
 * <p>
 * 覆盖同步异步分流、错误码、异常回传、启动期缓冲、死事件、订阅者异常隔离与注解注册校验。
 *
 * @author zcd
 */
class JellyfishEventBusTest {

    /**
     * 构造同步语义的事件总线。
     *
     * @return 已构造但未启动的事件总线
     */
    private static JellyfishEventBus newBus() {
        return new JellyfishEventBus(EventBusOptions.defaults(), Runnable::run);
    }

    @Test
    void dispatch_should_return_result_when_handler_registered() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.pluginContext("plugin-a").commands().register("calculator", command -> "42", RegisterOptions.DEFAULT);

        // When
        Object result = bus.dispatch(new PluginCommand("calculator", Object.class, null));

        // Then
        assertEquals("42", result);
        assertEquals(1L, bus.stats().getDispatchedCommands());
    }

    @Test
    void dispatch_should_throw_no_handler_when_not_registered() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();

        // When
        PluginCommand command = new PluginCommand("missing", Object.class, null);
        CommandException exception = assertThrows(CommandException.class, () -> bus.dispatch(command));

        // Then
        assertEquals(CommandException.Code.NO_HANDLER, exception.getCode());
        assertEquals(1L, bus.stats().getNoHandlerCommands());
    }

    @Test
    void dispatch_should_throw_ambiguous_when_multiple_handlers_match() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        PluginContext context = bus.pluginContext("plugin-a");
        context.commands().register(PluginCommand.class, null, command -> "any", RegisterOptions.DEFAULT);
        context.commands().register("calculator", command -> "one", RegisterOptions.DEFAULT);

        // When
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        CommandException exception = assertThrows(CommandException.class, () -> bus.dispatch(command));

        // Then
        assertEquals(CommandException.Code.AMBIGUOUS_HANDLER, exception.getCode());
    }

    @Test
    void dispatch_should_rethrow_handler_runtime_exception() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.pluginContext("plugin-a").commands().register("calculator", command -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.DEFAULT);

        // When / Then
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        assertThrows(IllegalStateException.class, () -> bus.dispatch(command));
        assertEquals(1L, bus.stats().getFailedCommands());
    }

    @Test
    void dispatch_should_wrap_handler_checked_exception() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.pluginContext("plugin-a").commands().register("calculator", command -> {
            throw new Exception("checked");
        }, RegisterOptions.DEFAULT);

        // When
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        JellyfishException exception = assertThrows(JellyfishException.class, () -> bus.dispatch(command));

        // Then
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void dispatch_should_throw_when_not_started() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        assertThrows(JellyfishException.class, () -> bus.dispatch(command));
    }

    @Test
    void dispatch_should_reject_nesting_too_deep() {
        // Given
        EventBusOptions options = EventBusOptions.builder().maxCommandDepth(1).build();
        JellyfishEventBus bus = new JellyfishEventBus(options, Runnable::run);
        bus.start();
        bus.pluginContext("plugin-a").commands().register("outer",
                command -> bus.dispatch(new PluginCommand("inner", Object.class, null)), RegisterOptions.DEFAULT);

        // When
        PluginCommand command = new PluginCommand("outer", Object.class, null);
        CommandException exception = assertThrows(CommandException.class, () -> bus.dispatch(command));

        // Then
        assertEquals(CommandException.Code.NESTING_TOO_DEEP, exception.getCode());
        assertEquals(1L, bus.stats().getNestingRejectedCommands());
    }

    @Test
    void publish_should_buffer_before_start_and_replay_after_start() {
        // Given
        JellyfishEventBus bus = newBus();
        List<ConfigWarningEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, received::add);

        // When
        bus.publish(new ConfigWarningEvent("path", "message"));

        // Then：启动前不派发
        assertTrue(received.isEmpty());

        // When
        bus.start();

        // Then：启动后回放
        assertEquals(1, received.size());
        assertEquals(1L, bus.stats().getPendingReplayed());
    }

    @Test
    void publishSync_should_deliver_immediately_without_start() {
        // Given
        JellyfishEventBus bus = newBus();
        List<ConfigWarningEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, received::add);

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void publish_should_broadcast_and_isolate_subscriber_errors() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        List<ConfigWarningEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-b").events().subscribe(ConfigWarningEvent.class, received::add);

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
        assertEquals(1L, bus.stats().getSubscriberErrors());
    }

    @Test
    void publish_should_match_parent_type_subscription() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        List<JellyfishEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-a").events().subscribe(JellyfishEvent.class, received::add);

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void publishSync_should_count_dead_event_type_when_no_dispatcher_subscribes() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();

        // When
        bus.publishSync(new UnknownEvent());

        // Then
        assertEquals(1L, bus.stats().getDeadEventTypes());
    }

    @Test
    void register_should_reject_object_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.register(new ObjectSubscriber()));
    }

    @Test
    void register_should_reject_abstract_command_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.register(new CommandBaseSubscriber()));
    }

    @Test
    void register_should_support_annotated_command_and_notification_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        AnnotatedSubscriber subscriber = new AnnotatedSubscriber();
        bus.register(subscriber);

        // When
        bus.dispatch(new PluginCommand("calculator", Object.class, null));
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertTrue(subscriber.received.contains("command:calculator"));
        assertTrue(subscriber.received.contains("warning:path"));
    }

    @Test
    void pluginContext_should_reject_blank_plugin_id() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.pluginContext(" "));
        assertThrows(JellyfishException.class, () -> bus.pluginContext(null));
    }

    @Test
    void register_should_reuse_cached_subscribe_methods_for_same_class() {
        // Given
        JellyfishEventBus bus = newBus();
        RecorderSubscriber first = new RecorderSubscriber();
        RecorderSubscriber second = new RecorderSubscriber();
        bus.register(first);
        bus.register(second);

        // When：第二次注册命中订阅方法缓存
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, first.received.size());
        assertEquals(1, second.received.size());
    }

    @Test
    void close_should_force_shutdown_when_await_termination_times_out() throws Exception {
        // Given：应答超时的线程池，验证降级为 shutdownNow
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        JellyfishEventBus bus = new JellyfishEventBus(EventBusOptions.defaults(), executor);

        // When
        bus.close();

        // Then
        verify(executor).shutdown();
        verify(executor).shutdownNow();
    }

    @Test
    void pluginContext_should_reject_non_extensible_command_type() {
        // Given
        JellyfishEventBus bus = newBus();
        PluginContext context = bus.pluginContext("plugin-a");

        // When / Then
        assertThrows(JellyfishException.class, () -> context.commands().register(PermissionCheckCommand.class, null,
                command -> PermissionDecision.allow("ok"), RegisterOptions.DEFAULT));
    }

    @Test
    void plugin_override_should_be_visible_in_snapshot() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.pluginContext("plugin-a").commands().register("calculator", command -> "one", RegisterOptions.DEFAULT);

        // When
        bus.pluginContext("plugin-b").commands().register("calculator", command -> "two",
                RegisterOptions.override(true));

        // Then
        assertTrue(bus.snapshot().render().contains("overrides plugin-a"));
        assertTrue(bus.snapshot().render().contains("plugin-b"));
    }

    @Test
    void close_should_clear_registries_and_reject_dispatch() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.pluginContext("plugin-a").commands().register("calculator", command -> "one", RegisterOptions.DEFAULT);

        // When
        bus.close();

        // Then
        assertTrue(bus.snapshot().isEmpty());
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        assertThrows(JellyfishException.class, () -> bus.dispatch(command));
    }

    @Test
    void publish_should_drop_and_count_when_pending_buffer_full() {
        // Given
        EventBusOptions options = EventBusOptions.builder().pendingCapacity(1).build();
        JellyfishEventBus bus = new JellyfishEventBus(options, Runnable::run);
        List<ConfigWarningEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, received::add);

        // When
        bus.publish(new ConfigWarningEvent("first", "message"));
        bus.publish(new ConfigWarningEvent("second", "message"));
        bus.start();

        // Then：第二个通知溢出被丢弃，只有第一个被回放
        assertEquals(1L, bus.stats().getPendingOverflow());
        assertEquals(1L, bus.stats().getDroppedEvents());
        assertEquals(1L, bus.stats().getPendingReplayed());
        assertEquals("first", received.get(0).getSource());
    }

    @Test
    void register_should_collect_interface_subscribe_methods() {
        // Given
        JellyfishEventBus bus = newBus();
        RecorderSubscriber subscriber = new RecorderSubscriber();
        Subscription handle = bus.register(subscriber);

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, subscriber.received.size());

        // When：关闭句柄后不再收到通知
        handle.close();
        handle.close();
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, subscriber.received.size());
    }

    @Test
    void register_should_reject_subscribe_method_with_wrong_arity() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.register(new WrongAritySubscriber()));
    }

    @Test
    void register_should_reject_unsupported_subscriber_parameter_type() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.register(new UnsupportedSubscriber()));
    }

    @Test
    void close_should_be_idempotent() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();

        // When
        bus.close();
        bus.close();

        // Then
        assertTrue(bus.snapshot().isEmpty());
    }

    @Test
    void start_should_be_idempotent() {
        // Given
        JellyfishEventBus bus = newBus();
        List<ConfigWarningEvent> received = new ArrayList<>();
        bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, received::add);
        bus.publish(new ConfigWarningEvent("path", "message"));

        // When
        bus.start();
        bus.start();

        // Then：只回放一次
        assertEquals(1, received.size());
        assertEquals(1L, bus.stats().getPendingReplayed());
    }

    @Test
    void publish_should_drop_and_count_when_notifier_rejects() {
        // Given
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        JellyfishEventBus bus = new JellyfishEventBus(EventBusOptions.defaults(), rejecting);
        bus.start();

        // When
        bus.publish(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1L, bus.stats().getDroppedEvents());
    }

    @Test
    void publish_should_deliver_asynchronously_with_default_executor() throws Exception {
        // Given：默认构造器使用真实的事件线程池
        EventBusOptions options = EventBusOptions.builder().corePoolSize(1).maxPoolSize(1)
                .keepAliveSeconds(1L).queueCapacity(8).shutdownAwaitMillis(1000L).build();
        JellyfishEventBus bus = new JellyfishEventBus(options);
        try {
            List<ConfigWarningEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(1);
            bus.start();
            bus.pluginContext("plugin-a").events().subscribe(ConfigWarningEvent.class, event -> {
                received.add(event);
                delivered.countDown();
            });

            // When
            bus.publish(new ConfigWarningEvent("path", "message"));

            // Then
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.size());
        } finally {
            bus.close();
        }
    }

    @Test
    void dispatch_should_reject_null_command() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(NullPointerException.class, () -> bus.dispatch(null));
    }

    @Test
    void publish_should_reject_null_event() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(NullPointerException.class, () -> bus.publish(null));
        assertThrows(NullPointerException.class, () -> bus.publishSync(null));
    }

    @Test
    void register_should_reject_null_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(NullPointerException.class, () -> bus.register(null));
    }

    @Test
    void dispatch_should_wrap_checked_exception_from_annotated_command_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new CheckedThrowingCommandSubscriber());

        // When
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        JellyfishException exception = assertThrows(JellyfishException.class, () -> bus.dispatch(command));

        // Then
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void dispatch_should_propagate_error_from_annotated_command_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new ErrorThrowingCommandSubscriber());

        // When
        PluginCommand command = new PluginCommand("calculator", Object.class, null);

        // Then
        assertThrows(AssertionError.class, () -> bus.dispatch(command));
    }

    @Test
    void publishSync_should_isolate_runtime_exception_from_annotated_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new RuntimeThrowingNotificationSubscriber());

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1L, bus.stats().getSubscriberErrors());
    }

    @Test
    void publishSync_should_isolate_checked_exception_from_annotated_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new CheckedThrowingNotificationSubscriber());

        // When
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1L, bus.stats().getSubscriberErrors());
    }

    /**
     * 未登记 dispatcher 的通知类型，用于触发死事件。
     *
     * @author zcd
     */
    private static final class UnknownEvent extends zcd.jellyfish.api.event.AbstractJellyfishEvent {

        /**
         * 构造死事件。
         */
        private UnknownEvent() {
            super(null);
        }
    }

    /**
     * 非法订阅者：订阅 {@code Object} 会吞掉死事件。
     *
     * @author zcd
     */
    private static final class ObjectSubscriber {

        /**
         * 订阅所有事件。
         *
         * @param event 事件对象
         */
        @Subscribe
        void onEvent(Object event) {
            // 仅用于校验注册期拒绝
        }
    }

    /**
     * 非法订阅者：订阅命令基类会收下所有子类命令。
     *
     * @author zcd
     */
    private static final class CommandBaseSubscriber {

        /**
         * 订阅命令基类。
         *
         * @param command 命令对象
         */
        @Subscribe
        void onCommand(Command<?> command) {
            // 仅用于校验注册期拒绝
        }
    }

    /**
     * 合法的注解式订阅者：同时提供命令处理器与通知订阅者。
     *
     * @author zcd
     */
    private static final class AnnotatedSubscriber {

        /** 收到的通知记录。 */
        private final List<String> received = new ArrayList<>();

        /**
         * 处理插件命令。
         *
         * @param command 插件命令
         */
        @Subscribe
        void onPluginCommand(PluginCommand command) {
            received.add("command:" + command.getName());
        }

        /**
         * 处理配置告警。
         *
         * @param event 配置告警事件
         */
        @Subscribe
        void onConfigWarning(ConfigWarningEvent event) {
            received.add("warning:" + event.getSource());
        }
    }

    /**
     * 通过接口默认方法声明订阅的订阅者，用于验证接口方法收集。
     *
     * @author zcd
     */
    private interface DefaultSubscriber {

        /**
         * 记录收到的配置告警。
         *
         * @param event 配置告警事件
         */
        void record(ConfigWarningEvent event);

        /**
         * 处理配置告警。
         *
         * @param event 配置告警事件
         */
        @Subscribe
        default void onConfigWarning(ConfigWarningEvent event) {
            record(event);
        }
    }

    /**
     * 未覆写订阅方法、仅实现记录动作的订阅者。
     *
     * @author zcd
     */
    private static final class RecorderSubscriber implements DefaultSubscriber {

        /** 收到的通知记录。 */
        private final List<ConfigWarningEvent> received = new ArrayList<>();

        @Override
        public void record(ConfigWarningEvent event) {
            received.add(event);
        }
    }

    /**
     * 非法订阅者：订阅方法参数个数不为 1。
     *
     * @author zcd
     */
    private static final class WrongAritySubscriber {

        /**
         * 参数个数非法。
         *
         * @param event 配置告警事件
         * @param extra 多余参数
         */
        @Subscribe
        void onConfigWarning(ConfigWarningEvent event, String extra) {
            // 仅用于校验注册期拒绝
        }
    }

    /**
     * 非法订阅者：参数既不是通知也不是命令。
     *
     * @author zcd
     */
    private static final class UnsupportedSubscriber {

        /**
         * 参数类型不受支持。
         *
         * @param text 任意文本
         */
        @Subscribe
        void onText(String text) {
            // 仅用于校验注册期拒绝
        }
    }

    /**
     * 注解式命令订阅者：抛出受检异常。
     *
     * @author zcd
     */
    private static final class CheckedThrowingCommandSubscriber {

        /**
         * 抛出受检异常。
         *
         * @param command 插件命令
         * @throws Exception 固定抛出，用于验证异常解包
         */
        @Subscribe
        void onPluginCommand(PluginCommand command) throws Exception {
            throw new Exception("checked");
        }
    }

    /**
     * 注解式命令订阅者：抛出 Error。
     *
     * @author zcd
     */
    private static final class ErrorThrowingCommandSubscriber {

        /**
         * 抛出 Error。
         *
         * @param command 插件命令
         */
        @Subscribe
        void onPluginCommand(PluginCommand command) {
            throw new AssertionError("fatal");
        }
    }

    /**
     * 注解式通知订阅者：抛出运行时异常。
     *
     * @author zcd
     */
    private static final class RuntimeThrowingNotificationSubscriber {

        /**
         * 抛出运行时异常。
         *
         * @param event 配置告警事件
         */
        @Subscribe
        void onConfigWarning(ConfigWarningEvent event) {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * 注解式通知订阅者：抛出受检异常。
     *
     * @author zcd
     */
    private static final class CheckedThrowingNotificationSubscriber {

        /**
         * 抛出受检异常。
         *
         * @param event 配置告警事件
         * @throws Exception 固定抛出，用于验证异常包装
         */
        @Subscribe
        void onConfigWarning(ConfigWarningEvent event) throws Exception {
            throw new Exception("checked");
        }
    }
}
