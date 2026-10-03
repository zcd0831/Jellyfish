package zcd.jellyfish.cli.di;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.core.subagent.SubAgentDelegationAdapter;
import zcd.jellyfish.core.subagent.SubAgentLauncher;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.config.AgentPromptLoader;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.BuiltinAgentLoader;
import zcd.jellyfish.infra.config.ConfigLoader;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.PluginPaths;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SettingsBinder;
import zcd.jellyfish.infra.config.SettingsReader;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.infra.plugin.PluginProperties;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginModule} 的单元测试：验证装配契约（插件段由配置驱动、共用注册表、owner 绑定）。
 * <p>
 * Dagger 的 {@code @Provides} 方法都是包私有静态方法，因此可以像普通方法一样直接断言，
 * 不需要启动组件（组件会读取 classpath 配置并创建 HTTP 客户端，不适合放进单元测试）。
 *
 * @author zcd
 */
class PluginModuleTest {

    /** 临时目录，用于构造真实的 jellyfish.json。 */
    @TempDir
    Path tempDir;

    @Test
    void providePluginRuntimeConfig_should_apply_roots_and_plugins_section_from_config() throws IOException {
        // Given：扫描目录在 config.json，名单与插件配置段在 jellyfish.json
        Path jellyfish = tempDir.resolve("jellyfish.json");
        Files.write(jellyfish, ("{\"plugins\":{\"enabled\":[\"plugin-a\"],"
                + "\"disabled\":[\"plugin-b\"],"
                + "\"configurations\":{\"plugin-a\":{\"readOnlyTools\":[\"read_file\"]}}}}")
                .getBytes(StandardCharsets.UTF_8));

        // When
        PluginRuntimeConfig config = PluginModule.providePluginRuntimeConfig(
                runtimeConfigOf(pathsTo(jellyfish), new PluginPaths(Collections.singletonList("custom-plugins"))));

        // Then
        assertEquals(Collections.singletonList(Paths.get("custom-plugins")), config.getPluginsRoots());
        assertTrue(config.getEnabledPluginIds().contains("plugin-a"));
        assertTrue(config.getDisabledPluginIds().contains("plugin-b"));
        assertEquals(Collections.singletonList("read_file"),
                config.configurationOf("plugin-a").get("readOnlyTools"));
    }

    @Test
    void providePluginRuntimeConfig_should_expand_tilde_in_roots() throws IOException {
        // Given
        Path jellyfish = tempDir.resolve("jellyfish.json");
        Files.write(jellyfish, "{}".getBytes(StandardCharsets.UTF_8));

        // When
        PluginRuntimeConfig config = PluginModule.providePluginRuntimeConfig(
                runtimeConfigOf(pathsTo(jellyfish), new PluginPaths(Collections.singletonList("~/extra"))));

        // Then
        assertEquals(Collections.singletonList(Paths.get(System.getProperty("user.home"), "extra")),
                config.getPluginsRoots());
    }

    @Test
    void providePluginRuntimeConfig_should_scan_default_plugins_root_when_config_empty() {
        // When
        PluginRuntimeConfig config = PluginModule.providePluginRuntimeConfig(
                runtimeConfigOf(new ConfigPaths(), new PluginPaths(null)));

        // Then
        assertEquals(1, config.getPluginsRoots().size());
        assertEquals(PluginRuntimeConfig.DEFAULT_PLUGINS_ROOT, config.getPluginsRoots().get(0).toString());
        assertTrue(config.getEnabledPluginIds().isEmpty());
        assertTrue(config.getDisabledPluginIds().isEmpty());
        assertTrue(config.getPluginConfigurations().isEmpty());
    }

    @Test
    void providePluginContextFactory_should_bind_owner_and_share_registry() {
        // Given
        TypeRegistry registry = new TypeRegistry();
        ExtensionRegistry extensions = new ExtensionRegistry(registry);
        EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);

        // When
        PluginContextFactory factory = PluginModule.providePluginContextFactory(extensions, events, registry,
                new RuntimeInfoHolder(), new ActionQueue(),
                org.mockito.Mockito.mock(zcd.jellyfish.infra.session.SessionManager.class), new ShellIngress(new MetricsRegistry()), SubAgentPort.unavailable());
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
    @DisplayName("委派端口绑定的是 core 里的真实现，而不是能力缺失的占位")
    void provideSubAgentPort_should_bindTheRealAdapter() {
        // Given：一个真的适配器（它的委派器用 mock——本用例只验装配，不验委派本身）
        SubAgentDelegationAdapter adapter = new SubAgentDelegationAdapter(
                org.mockito.Mockito.mock(SubAgentLauncher.class));

        // When
        SubAgentPort port = PluginModule.provideSubAgentPort(adapter);

        // Then：绑的是它，不是 SubAgentPort.unavailable()
        assertSame(adapter, port);
        assertNotSame(SubAgentPort.unavailable(), port);
    }

    @Test
    @DisplayName("装配出来的插件上下文拿到的就是绑定的那个端口")
    void providePluginContextFactory_shouldHandThePortToPlugins() {
        // Given：一个可辨认的端口实现
        SubAgentPort port = request -> DelegationHandle.settled(
                DelegationResult.completed("run-1", "x", 1, 1L));
        PluginContextFactory factory = PluginModule.providePluginContextFactory(
                new ExtensionRegistry(new TypeRegistry()),
                new EventChannel(EventChannelOptions.defaults(), new TypeRegistry()),
                new TypeRegistry(), new RuntimeInfoHolder(), new ActionQueue(),
                org.mockito.Mockito.mock(zcd.jellyfish.infra.session.SessionManager.class),
                new ShellIngress(new MetricsRegistry()), port);

        // When
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));

        // Then：插件看到的必须是它——否则插件拿到的会是「永远拒绝」的占位
        assertSame(port, context.delegations());
    }

    @Test
    void providePluginManager_should_expose_no_plugins_before_bootstrap() {
        // Given
        TypeRegistry registry = new TypeRegistry();
        EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);
        PluginContextFactory factory = PluginModule.providePluginContextFactory(
                new ExtensionRegistry(registry),
                events,
                registry,
                new RuntimeInfoHolder(), new ActionQueue(),
                org.mockito.Mockito.mock(zcd.jellyfish.infra.session.SessionManager.class), new ShellIngress(new MetricsRegistry()), SubAgentPort.unavailable());

        // When
        PF4JPluginManager manager = PluginModule.providePluginManager(factory,
                PluginModule.providePluginRuntimeConfig(
                        runtimeConfigOf(new ConfigPaths(), new PluginPaths(null))),
                events);

        // Then：未 bootstrap 时不触发任何插件扫描
        assertNotNull(manager);
        assertTrue(manager.plugins().isEmpty());
    }

    @Test
    void plugin_properties_should_expose_expected_file_name() {
        // Then：插件运行时按该文件名在根目录下找描述符
        assertEquals("plugin.properties", PluginProperties.FILE_NAME);
    }

    /**
     * 构造已加载一次配置的 {@link RuntimeConfig}（只配置插件段路径与扫描目录）。
     *
     * @param jellyfishPaths 运行期设置段的双源路径
     * @param plugins        插件扫描目录，可为 {@code null}
     * @return 运行时配置门面
     */
    private static RuntimeConfig runtimeConfigOf(ConfigPaths jellyfishPaths, PluginPaths plugins) {
        AppConfig appConfig = new AppConfig(null, new ConfigPaths(), new ConfigPaths(), jellyfishPaths, plugins);
        ConfigLoader configLoader = new ConfigLoader(new SettingsReader(), new SettingsBinder());
        AgentPromptLoader promptLoader = new AgentPromptLoader(new SettingsReader());
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig, configLoader, event -> {
        }, new BuiltinAgentLoader(configLoader, promptLoader), promptLoader);
        runtimeConfig.refresh();
        return runtimeConfig;
    }

    /**
     * 构造只指向项目级文件的双源路径。
     *
     * @param project 项目级文件路径
     * @return 双源路径
     */
    private static ConfigPaths pathsTo(Path project) {
        ConfigPaths paths = new ConfigPaths();
        paths.setProjectPath(project.toString());
        return paths;
    }
}
