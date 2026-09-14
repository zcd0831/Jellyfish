package zcd.jellyfish.api.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link PluginContext} 默认方法的单元测试：验证不带描述符/选项的重载按约定补齐默认值。
 * <p>
 * 默认方法属于对外契约的一部分，插件作者直接使用它们，因此这里用记录型实现逐个断言透传参数。
 *
 * @author zcd
 */
class PluginContextTest {

    /** 记录每次调用的被测上下文。 */
    private final RecordingContext context = new RecordingContext();

    /** 占位处理器。 */
    private final ExtensionHandler<CommandRequest, Object> handler = request -> "ok";

    @Test
    void handle_without_descriptor_should_delegate_with_null_descriptor_and_default_options() {
        // When
        context.handle(CommandRequest.class, "calc", handler);

        // Then
        assertEquals(CommandRequest.class, context.requestType);
        assertEquals("calc", context.routeKey);
        assertNull(context.descriptor);
        assertSame(handler, context.handler);
        assertSame(RegisterOptions.DEFAULT, context.options);
    }

    @Test
    void handle_with_descriptor_should_use_default_options() {
        // When
        context.handle(CommandRequest.class, "calc", "descriptor", handler);

        // Then
        assertEquals("descriptor", context.descriptor);
        assertSame(RegisterOptions.DEFAULT, context.options);
    }

    @Test
    void handle_with_options_should_delegate_with_null_descriptor() {
        // Given
        RegisterOptions options = RegisterOptions.override(true);

        // When
        context.handle(CommandRequest.class, "calc", handler, options);

        // Then
        assertNull(context.descriptor);
        assertSame(options, context.options);
    }

    @Test
    void contribute_without_descriptor_should_delegate_with_defaults() {
        // When
        context.contribute(CommandRequest.class, handler);

        // Then
        assertNull(context.descriptor);
        assertSame(RegisterOptions.DEFAULT, context.options);
    }

    @Test
    void contribute_with_descriptor_should_use_default_options() {
        // When
        context.contribute(CommandRequest.class, "descriptor", handler);

        // Then
        assertEquals("descriptor", context.descriptor);
        assertSame(RegisterOptions.DEFAULT, context.options);
    }

    @Test
    void contribute_with_options_should_delegate_with_null_descriptor() {
        // Given
        RegisterOptions options = RegisterOptions.order(3);

        // When
        context.contribute(CommandRequest.class, handler, options);

        // Then
        assertNull(context.descriptor);
        assertSame(options, context.options);
    }

    @Test
    void observe_without_filter_should_delegate_with_null_filter() {
        // Given
        List<JellyfishEvent> received = new ArrayList<>();

        // When
        context.observe(ConfigWarningEvent.class, received::add);

        // Then
        assertEquals(ConfigWarningEvent.class, context.eventType);
        assertNull(context.filter);
        assertEquals(1, context.listenerCount);
    }

    @Test
    void emit_should_delegate_to_publisher() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent("source", "message");

        // When
        context.emit(event);

        // Then
        assertSame(event, context.emitted);
    }

    /**
     * 记录型插件上下文：只记录参数，不做任何注册。
     *
     * @author zcd
     */
    private static final class RecordingContext implements PluginContext {

        /** 最后一次注册的请求类型。 */
        private Class<?> requestType;

        /** 最后一次注册的路由键。 */
        private String routeKey;

        /** 最后一次注册的描述符。 */
        private Object descriptor;

        /** 最后一次注册的处理器。 */
        private Object handler;

        /** 最后一次注册的选项。 */
        private RegisterOptions options;

        /** 最后一次订阅的事件类型。 */
        private Class<? extends JellyfishEvent> eventType;

        /** 最后一次订阅的过滤谓词。 */
        private Predicate<?> filter;

        /** 订阅监听器数量。 */
        private int listenerCount;

        /** 最后一次发布的事件。 */
        private JellyfishEvent emitted;

        @Override
        public String pluginId() {
            return "test";
        }

        @Override
        public Map<String, Object> configuration() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public <C extends ExtensionRequest<R>, R> Subscription handle(
                Class<C> requestType, String routeKey, Object descriptor,
                ExtensionHandler<C, R> handler, RegisterOptions options) {
            this.requestType = requestType;
            this.routeKey = routeKey;
            this.descriptor = descriptor;
            this.handler = handler;
            this.options = options;
            return Subscription.noop();
        }

        @Override
        public <C extends ExtensionRequest<R>, R> Subscription contribute(
                Class<C> requestType, Object descriptor, ExtensionHandler<C, R> handler, RegisterOptions options) {
            this.requestType = requestType;
            this.descriptor = descriptor;
            this.handler = handler;
            this.options = options;
            return Subscription.noop();
        }

        @Override
        public <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Predicate<E> filter,
                                                               Consumer<E> listener) {
            this.eventType = eventType;
            this.filter = filter;
            this.listenerCount++;
            return Subscription.noop();
        }

        @Override
        public void emit(JellyfishEvent event) {
            this.emitted = event;
        }
    }
}
