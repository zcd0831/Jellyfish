package zcd.jellyfish.cli.di;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionModule} 与 {@link EventModule} 的单元测试：验证两条策略共用同一份注册表。
 * <p>
 * 「唯一一份表」是本次重构的硬约束，因此在 DI 层面也要守住：这里断言同步策略与事件通道拿到的是
 * 同一个 {@link TypeRegistry} 实例，否则又会出现两张表。
 *
 * @author zcd
 */
class ExtensionModuleTest {

    @Test
    void provideTypeRegistry_should_return_empty_registry() {
        // When
        TypeRegistry registry = ExtensionModule.provideTypeRegistry();

        // Then
        assertNotNull(registry);
        assertTrue(registry.isEmpty());
    }

    @Test
    void provideExtensionRegistry_should_wrap_given_registry() {
        // Given
        TypeRegistry registry = ExtensionModule.provideTypeRegistry();

        // When
        ExtensionRegistry extensions = ExtensionModule.provideExtensionRegistry(registry);
        Subscription subscription = extensions.contribute("kernel", ContributionRequest.class, null,
                request -> "ok", RegisterOptions.DEFAULT);

        // Then：注册直接落在传入的那份表上
        assertEquals(1, registry.registrations().size());
        subscription.close();
        assertTrue(registry.isEmpty());
    }

    @Test
    void provideEventChannel_should_use_the_same_registry_instance() {
        // Given
        TypeRegistry registry = ExtensionModule.provideTypeRegistry();
        EventChannelOptions options = EventModule.provideEventChannelOptions();

        // When
        EventChannel channel = EventModule.provideEventChannel(options, registry);
        channel.subscribe("metrics", ConfigWarningEvent.class, event -> {
            // 仅用于产生一条订阅
        });

        // Then：订阅也落在同一份表上，说明两者没有各建一张表
        assertEquals(1, registry.registrations().size());
        assertSame(channel, EventModule.provideEventPublisher(channel));
        channel.close();
    }

    @Test
    void provideEventChannelOptions_should_return_defaults() {
        // When / Then
        assertEquals(EventChannelOptions.defaults().getQueueCapacity(),
                EventModule.provideEventChannelOptions().getQueueCapacity());
    }

    /**
     * 测试用类型级请求。
     *
     * @author zcd
     */
    private static final class ContributionRequest extends ExtensionRequest<String> {

        /**
         * 构造测试请求。
         */
        private ContributionRequest() {
            super(String.class, null);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
