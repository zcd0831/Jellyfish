package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp 插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 扩展点可路由 → 卸载回收」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这条插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。这里刻意<b>复用 {@code src/main/resources} 里那份描述符</b>。
 * <p>
 * 配置为空段，因此没有任何启用的 server、不会 fork 子进程。
 *
 * @author zcd
 */
@DisplayName("mcp 插件加载链路")
class McpPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-mcp";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel eventChannel;

    /** 被测插件管理器。 */
    private PF4JPluginManager manager;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        eventChannel.close();
    }

    @Test
    @DisplayName("真实描述符应让插件启动成功")
    void bootstrap_should_startPlugin_when_descriptorIsValid() throws IOException {
        // Given
        installPlugin();

        // When
        manager = newManager();
        manager.bootstrap();

        // Then
        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后台账命令与权限拦截都应可路由")
    void bootstrap_should_registerExtensionPoints() throws IOException {
        // Given
        installPlugin();

        // When
        manager = newManager();
        manager.bootstrap();

        // Then
        assertEquals(1, extensions.handlers(CommandRequest.class, "mcp").size());
        assertEquals(1, extensions.bindings(PermissionCheckRequest.class, null).size());
    }

    @Test
    @DisplayName("插件卸载后全部注册应按 owner 被回收")
    void close_should_unregisterAll() throws IOException {
        // Given
        installPlugin();
        manager = newManager();
        manager.bootstrap();

        // When
        manager.close();
        manager = null;

        // Then
        assertTrue(extensions.handlers(CommandRequest.class, "mcp").isEmpty());
        assertTrue(extensions.bindings(PermissionCheckRequest.class, null).isEmpty());
        assertTrue(registry.snapshot().isEmpty());
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 写入失败时抛出
     */
    private void installPlugin() throws IOException {
        Path pluginDir = pluginsRoot.resolve(PLUGIN_ID);
        Files.createDirectories(pluginDir);
        try (InputStream descriptor = getClass().getResourceAsStream("/plugin.properties")) {
            if (descriptor == null) {
                throw new IllegalStateException("测试类路径上找不到 plugin.properties");
            }
            Files.copy(descriptor, pluginDir.resolve("plugin.properties"));
        }
    }

    /**
     * 构造扫描临时目录的插件管理器。
     *
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry);
        return new PF4JPluginManager(contexts, PluginRuntimeConfig.ofRoots(pluginsRoot));
    }
}
