package zcd.jellyfish.cli.di;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginProperties;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginModule} 的单元测试：验证装配契约（默认装配输入、共用注册表、owner 绑定）。
 * <p>
 * Dagger 的 {@code @Provides} 方法都是包私有静态方法，因此可以像普通方法一样直接断言，
 * 不需要启动组件（组件会读取 classpath 配置并创建 HTTP 客户端，不适合放进单元测试）。
 *
 * @author zcd
 */
class PluginModuleTest {

    @Test
    void providePluginRuntimeConfig_should_scan_default_plugins_root() {
        // When
        PluginRuntimeConfig config = PluginModule.providePluginRuntimeConfig();

        // Then
        assertEquals(1, config.getPluginsRoots().size());
        assertEquals(PluginRuntimeConfig.DEFAULT_PLUGINS_ROOT, config.getPluginsRoots().get(0).toString());
        assertTrue(config.getEnabledPluginIds().isEmpty());
        assertTrue(config.getDisabledPluginIds().isEmpty());
    }

    @Test
    void providePluginContextFactory_should_bind_owner_and_share_registry() {
        // Given
        TypeRegistry registry = new TypeRegistry();
        ExtensionRegistry extensions = new ExtensionRegistry(registry);
        EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);

        // When
        PluginContextFactory factory = PluginModule.providePluginContextFactory(extensions, events, registry);
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        Subscription subscription = context.observe(ConfigWarningEvent.class, event -> {
                    // 仅用于产生一条订阅
                });

        // Then
        assertTrue(registry.snapshot().render().contains("<- plugin-a"));
        subscription.close();
        assertEquals(0, factory.release("plugin-a"));
    }

    @Test
    void providePluginManager_should_expose_no_plugins_before_bootstrap() {
        // Given
        TypeRegistry registry = new TypeRegistry();
        PluginContextFactory factory = PluginModule.providePluginContextFactory(
                new ExtensionRegistry(registry),
                new EventChannel(EventChannelOptions.defaults(), registry),
                registry);

        // When
        PF4JPluginManager manager = PluginModule.providePluginManager(factory,
                PluginModule.providePluginRuntimeConfig());

        // Then：未 bootstrap 时不触发任何插件扫描
        assertNotNull(manager);
        assertTrue(manager.plugins().isEmpty());
    }

    @Test
    void plugin_properties_should_expose_expected_file_name() {
        // Then：插件运行时按该文件名在根目录下找描述符
        assertEquals("plugin.properties", PluginProperties.FILE_NAME);
    }
}
