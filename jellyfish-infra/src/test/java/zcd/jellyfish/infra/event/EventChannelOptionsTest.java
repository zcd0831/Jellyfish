package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EventChannelOptions} 的单元测试：验证默认值、构建器覆盖与参数校验。
 *
 * @author zcd
 */
class EventChannelOptionsTest {

    @Test
    void defaults_should_expose_documented_values() {
        // When
        EventChannelOptions options = EventChannelOptions.defaults();

        // Then
        assertEquals(2, options.getCorePoolSize());
        assertEquals(8, options.getMaxPoolSize());
        assertEquals(60L, options.getKeepAliveSeconds());
        assertEquals(1024, options.getQueueCapacity());
        assertEquals(1024, options.getPendingCapacity());
        assertEquals(5000L, options.getShutdownAwaitMillis());
    }

    @Test
    void builder_should_override_every_parameter() {
        // When
        EventChannelOptions options = EventChannelOptions.builder()
                .corePoolSize(1)
                .maxPoolSize(4)
                .keepAliveSeconds(30L)
                .queueCapacity(16)
                .pendingCapacity(32)
                .shutdownAwaitMillis(100L)
                .build();

        // Then
        assertEquals(1, options.getCorePoolSize());
        assertEquals(4, options.getMaxPoolSize());
        assertEquals(30L, options.getKeepAliveSeconds());
        assertEquals(16, options.getQueueCapacity());
        assertEquals(32, options.getPendingCapacity());
        assertEquals(100L, options.getShutdownAwaitMillis());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void build_should_throw_when_parameter_invalid(String name, Consumer<EventChannelOptions.Builder> configurer) {
        // Given
        EventChannelOptions.Builder builder = EventChannelOptions.builder();
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
                Arguments.of("corePoolSize=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.corePoolSize(0)),
                Arguments.of("maxPoolSize=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.maxPoolSize(0)),
                Arguments.of("keepAliveSeconds=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.keepAliveSeconds(0L)),
                Arguments.of("queueCapacity=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.queueCapacity(0)),
                Arguments.of("pendingCapacity=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.pendingCapacity(0)),
                Arguments.of("shutdownAwaitMillis=0",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.shutdownAwaitMillis(0L)),
                Arguments.of("maxPoolSize<corePoolSize",
                        (Consumer<EventChannelOptions.Builder>) builder -> builder.corePoolSize(8).maxPoolSize(2)));
    }
}
