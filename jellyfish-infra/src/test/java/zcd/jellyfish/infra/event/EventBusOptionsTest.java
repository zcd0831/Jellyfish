package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import zcd.jellyfish.api.JellyfishException;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EventBusOptions} 的单元测试：验证默认值、构建器取值与参数校验。
 *
 * @author zcd
 */
class EventBusOptionsTest {

    @Test
    void defaults_should_use_documented_values() {
        // When
        EventBusOptions options = EventBusOptions.defaults();

        // Then
        assertEquals(2, options.getCorePoolSize());
        assertEquals(8, options.getMaxPoolSize());
        assertEquals(60L, options.getKeepAliveSeconds());
        assertEquals(1024, options.getQueueCapacity());
        assertEquals(1024, options.getPendingCapacity());
        assertEquals(16, options.getMaxCallbackDepth());
        assertEquals(5000L, options.getShutdownAwaitMillis());
        assertEquals(4, options.getCallbackCorePoolSize());
        assertEquals(32, options.getCallbackMaxPoolSize());
        assertEquals(60L, options.getCallbackKeepAliveSeconds());
        assertEquals(256, options.getCallbackQueueCapacity());
        assertEquals(2000L, options.getCallbackPerHandlerTimeoutMillis());
    }

    @Test
    void builder_should_override_every_parameter() {
        // Given
        EventBusOptions.Builder builder = EventBusOptions.builder()
                .corePoolSize(1)
                .maxPoolSize(4)
                .keepAliveSeconds(30L)
                .queueCapacity(16)
                .pendingCapacity(32)
                .maxCallbackDepth(3)
                .shutdownAwaitMillis(100L)
                .callbackCorePoolSize(2)
                .callbackMaxPoolSize(6)
                .callbackKeepAliveSeconds(10L)
                .callbackQueueCapacity(8)
                .callbackPerHandlerTimeoutMillis(500L);

        // When
        EventBusOptions options = builder.build();

        // Then
        assertEquals(1, options.getCorePoolSize());
        assertEquals(4, options.getMaxPoolSize());
        assertEquals(30L, options.getKeepAliveSeconds());
        assertEquals(16, options.getQueueCapacity());
        assertEquals(32, options.getPendingCapacity());
        assertEquals(3, options.getMaxCallbackDepth());
        assertEquals(100L, options.getShutdownAwaitMillis());
        assertEquals(2, options.getCallbackCorePoolSize());
        assertEquals(6, options.getCallbackMaxPoolSize());
        assertEquals(10L, options.getCallbackKeepAliveSeconds());
        assertEquals(8, options.getCallbackQueueCapacity());
        assertEquals(500L, options.getCallbackPerHandlerTimeoutMillis());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void build_should_throw_when_parameter_invalid(String name, Consumer<EventBusOptions.Builder> configurer) {
        // Given
        EventBusOptions.Builder builder = EventBusOptions.builder();
        configurer.accept(builder);

        // When / Then
        assertThrows(JellyfishException.class, builder::build);
    }

    /**
     * 提供各参数的非法取值构造器。
     *
     * @return 用例名称与配置动作
     */
    private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of("corePoolSize=0", (Consumer<EventBusOptions.Builder>) builder -> builder.corePoolSize(0)),
                Arguments.of("maxPoolSize=0", (Consumer<EventBusOptions.Builder>) builder -> builder.maxPoolSize(0)),
                Arguments.of("keepAliveSeconds=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.keepAliveSeconds(0L)),
                Arguments.of("queueCapacity=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.queueCapacity(0)),
                Arguments.of("pendingCapacity=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.pendingCapacity(0)),
                Arguments.of("maxCallbackDepth=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.maxCallbackDepth(0)),
                Arguments.of("shutdownAwaitMillis=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.shutdownAwaitMillis(0L)),
                Arguments.of("maxPoolSize<corePoolSize",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.corePoolSize(8).maxPoolSize(2)),
                Arguments.of("callbackCorePoolSize=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.callbackCorePoolSize(0)),
                Arguments.of("callbackMaxPoolSize=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.callbackMaxPoolSize(0)),
                Arguments.of("callbackKeepAliveSeconds=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.callbackKeepAliveSeconds(0L)),
                Arguments.of("callbackQueueCapacity=0",
                        (Consumer<EventBusOptions.Builder>) builder -> builder.callbackQueueCapacity(0)),
                Arguments.of("callbackPerHandlerTimeoutMillis=0", (Consumer<EventBusOptions.Builder>) builder ->
                        builder.callbackPerHandlerTimeoutMillis(0L)),
                Arguments.of("callbackMaxPoolSize<callbackCorePoolSize", (Consumer<EventBusOptions.Builder>) builder ->
                        builder.callbackCorePoolSize(8).callbackMaxPoolSize(2)));
    }
}
