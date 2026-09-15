package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类
 * 跑一遍「加载 → 描述符体检 → 启动 → 工具可路由」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这条插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。这里刻意<b>复用 {@code src/main/resources} 里那份描述符</b>，
 * 而不是在测试里另写一份，否则测的就不是要发出去的那个插件了。
 * <p>
 * 插件根目录是临时目录，插件类由父加载器（测试 classpath）提供，因此无需在测试里打包 jar。
 *
 * @author zcd
 */
@DisplayName("工具插件加载链路")
class ToolsPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-tools";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，工具注册的落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道，插件上下文装配需要。 */
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
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后五个工具都应可从注册表按名字路由")
    void bootstrap_should_registerAllFiveTools() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        List<String> names = new ArrayList<String>();
        for (ToolDescriptor descriptor : extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class)) {
            names.add(descriptor.getName());
        }
        assertEquals(5, names.size(), names.toString());
        assertTrue(names.containsAll(Arrays.asList(
                "read_file", "write_file", "edit_file", "list_dir", "grep_files")), names.toString());
    }

    @Test
    @DisplayName("插件卸载后工具注册应被按 owner 全部回收")
    void close_should_unregisterAllTools() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(ToolCallRequest.class, "read_file").isEmpty());
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
