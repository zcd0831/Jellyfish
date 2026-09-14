package zcd.jellyfish.infra.event;

import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /**
     * 构造插件上下文，供各用例直接注册处理器与订阅。
     *
     * @param bus      事件总线
     * @param pluginId 插件标识
     * @return 插件上下文
     */
    private static PluginContext pluginContext(JellyfishEventBus bus, String pluginId) {
        return bus.pluginContext(PluginDeclaration.of(pluginId));
    }

    @Test
    void invoke_should_return_result_when_handler_registered() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> "42", RegisterOptions.DEFAULT);

        // When
        Object result = bus.invoke(new CommandRequest("calculator", Object.class, null));

        // Then
        assertEquals("42", result);
        assertEquals(1L, bus.stats().getDispatchedCallbacks());
    }

    @Test
    void invoke_should_call_every_matching_handler_in_order_and_return_first_result() {
        // Given：两个插件都贡献同一回调类型，order 决定先后
        JellyfishEventBus bus = newBus();
        bus.start();
        List<String> invoked = new ArrayList<>();
        pluginContext(bus, "plugin-b").contribute(CommandRequest.class, callback -> {
            invoked.add("plugin-b");
            return "b";
        }, RegisterOptions.order(2));
        pluginContext(bus, "plugin-a").contribute(CommandRequest.class, callback -> {
            invoked.add("plugin-a");
            return "a";
        }, RegisterOptions.order(1));

        // When
        Object result = bus.invoke(new CommandRequest("calculator", Object.class, null));

        // Then：全部处理器按 order 依次执行，返回值取首个
        assertEquals(Arrays.asList("plugin-a", "plugin-b"), invoked);
        assertEquals("a", result);
    }

    @Test
    void invoke_should_throw_no_handler_when_not_registered() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();

        // When
        CommandRequest callback = new CommandRequest("missing", Object.class, null);
        ExtensionException exception = assertThrows(ExtensionException.class, () -> bus.invoke(callback));

        // Then
        assertEquals(ExtensionException.Code.NO_HANDLER, exception.getCode());
        assertEquals(1L, bus.stats().getNoHandlerCallbacks());
    }

    @Test
    void tools_should_register_tool_handler_bound_to_plugin_owner() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        pluginContext(bus, "plugin-a").handle(ToolCallRequest.class, "calculator",
                callback -> new ToolCallResult("calculator", 42));

        // When
        ToolCallResult result = bus.invoke(new ToolCallRequest("calculator",
                Collections.<String, Object>emptyMap()));

        // Then
        assertEquals(42, result.getOutput());
        assertTrue(bus.snapshot().render().contains("plugin-a"));
    }

    @Test
    void invoke_should_rethrow_handler_runtime_exception() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.DEFAULT);

        // When / Then
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        assertThrows(IllegalStateException.class, () -> bus.invoke(callback));
        assertEquals(1L, bus.stats().getFailedCallbacks());
    }

    @Test
    void invoke_should_wrap_handler_checked_exception() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> {
            throw new Exception("checked");
        }, RegisterOptions.DEFAULT);

        // When
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        JellyfishException exception = assertThrows(JellyfishException.class, () -> bus.invoke(callback));

        // Then
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void invoke_should_throw_when_not_started() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        assertThrows(JellyfishException.class, () -> bus.invoke(callback));
    }

    @Test
    void invoke_should_nest_when_handler_invokes_another_request() {
        // Given：嵌套深度护栏已移除，嵌套回调必须正常返回（护栏质量归调用方）
        JellyfishEventBus bus = newBus();
        bus.start();
        PluginContext context = pluginContext(bus, "plugin-a");
        context.handle(CommandRequest.class, "inner", request -> "inner-result", RegisterOptions.DEFAULT);
        context.handle(CommandRequest.class, "outer",
                request -> bus.invoke(new CommandRequest("inner", Object.class, null)), RegisterOptions.DEFAULT);

        // When
        Object result = bus.invoke(new CommandRequest("outer", Object.class, null));

        // Then
        assertEquals("inner-result", result);
    }

    @Test
    void publish_should_buffer_before_start_and_replay_after_start() {
        // Given
        JellyfishEventBus bus = newBus();
        List<ConfigWarningEvent> received = new ArrayList<>();
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, received::add);

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
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, received::add);

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
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        List<ConfigWarningEvent> received = new ArrayList<>();
        pluginContext(bus, "plugin-b").observe(ConfigWarningEvent.class, received::add);

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
        pluginContext(bus, "plugin-a").observe(JellyfishEvent.class, received::add);

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
        assertThrows(JellyfishException.class, () -> bus.register(new CallbackBaseSubscriber()));
    }

    @Test
    void register_should_support_annotated_command_and_notification_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        AnnotatedSubscriber subscriber = new AnnotatedSubscriber();
        bus.register(subscriber);

        // When
        bus.invoke(new CommandRequest("calculator", Object.class, null));
        bus.publishSync(new ConfigWarningEvent("path", "message"));

        // Then
        assertTrue(subscriber.received.contains("callback:calculator"));
        assertTrue(subscriber.received.contains("warning:path"));
    }

    @Test
    void unregisterAll_should_remove_callbacks_and_subscriptions_of_owner() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> "42", RegisterOptions.DEFAULT);
        List<ConfigWarningEvent> received = new ArrayList<>();
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, received::add);
        bus.publishSync(new ConfigWarningEvent("path", "message"));
        assertEquals(1, received.size());

        // When
        bus.unregisterAll("plugin-a");

        // Then
        assertThrows(ExtensionException.class,
                () -> bus.invoke(new CommandRequest("calculator", Object.class, null)));
        bus.publishSync(new ConfigWarningEvent("path", "message"));
        assertEquals(1, received.size());
    }

    @Test
    void unregisterAll_should_reject_blank_owner() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(JellyfishException.class, () -> bus.unregisterAll(" "));
        assertThrows(JellyfishException.class, () -> bus.unregisterAll(null));
    }

    @Test
    void pluginContext_should_reject_blank_plugin_id() {
        // When / Then
        assertThrows(JellyfishException.class, () -> PluginDeclaration.of(" "));
        assertThrows(JellyfishException.class, () -> PluginDeclaration.of(null));
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
    void plugin_override_should_be_visible_in_snapshot() {
        // Given
        JellyfishEventBus bus = newBus();
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> "one", RegisterOptions.DEFAULT);

        // When
        pluginContext(bus, "plugin-b").handle(CommandRequest.class, "calculator", callback -> "two",
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
        pluginContext(bus, "plugin-a").handle(CommandRequest.class, "calculator", callback -> "one", RegisterOptions.DEFAULT);

        // When
        bus.close();

        // Then
        assertTrue(bus.snapshot().isEmpty());
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        assertThrows(JellyfishException.class, () -> bus.invoke(callback));
    }

    @Test
    void publish_should_drop_and_count_when_pending_buffer_full() {
        // Given
        EventBusOptions options = EventBusOptions.builder().pendingCapacity(1).build();
        JellyfishEventBus bus = new JellyfishEventBus(options, Runnable::run);
        List<ConfigWarningEvent> received = new ArrayList<>();
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, received::add);

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
        pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, received::add);
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
        Executor rejecting = callback -> {
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
            pluginContext(bus, "plugin-a").observe(ConfigWarningEvent.class, event -> {
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
    void invoke_should_reject_null_command() {
        // Given
        JellyfishEventBus bus = newBus();

        // When / Then
        assertThrows(NullPointerException.class, () -> bus.invoke(null));
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
    void invoke_should_wrap_checked_exception_from_annotated_command_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new CheckedThrowingCallbackSubscriber());

        // When
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        JellyfishException exception = assertThrows(JellyfishException.class, () -> bus.invoke(callback));

        // Then
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void invoke_should_propagate_error_from_annotated_command_subscriber() {
        // Given
        JellyfishEventBus bus = newBus();
        bus.start();
        bus.register(new ErrorThrowingCallbackSubscriber());

        // When
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);

        // Then
        assertThrows(AssertionError.class, () -> bus.invoke(callback));
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
    private static final class CallbackBaseSubscriber {

        /**
         * 订阅命令基类。
         *
         * @param callback 命令对象
         */
        @Subscribe
        void onCallback(ExtensionRequest<?> callback) {
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
         * @param callback 插件命令
         */
        @Subscribe
        void onCommandRequest(CommandRequest callback) {
            received.add("callback:" + callback.getName());
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
    private static final class CheckedThrowingCallbackSubscriber {

        /**
         * 抛出受检异常。
         *
         * @param callback 插件命令
         * @throws Exception 固定抛出，用于验证异常解包
         */
        @Subscribe
        void onCommandRequest(CommandRequest callback) throws Exception {
            throw new Exception("checked");
        }
    }

    /**
     * 注解式命令订阅者：抛出 Error。
     *
     * @author zcd
     */
    private static final class ErrorThrowingCallbackSubscriber {

        /**
         * 抛出 Error。
         *
         * @param callback 插件命令
         */
        @Subscribe
        void onCommandRequest(CommandRequest callback) {
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
