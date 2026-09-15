package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.DescriptorBinding;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 三个面（命令 / 工具 / 提示词贡献）都可路由」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这条插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。这里刻意<b>复用 {@code src/main/resources} 里那份描述符</b>，
 * 而不是在测试里另写一份，否则测的就不是要发出去的那个插件了。
 *
 * @author zcd
 */
@DisplayName("待办插件加载链路")
class TodoPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-todo";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，三个面的注册落点。 */
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
    @DisplayName("启动后 /todo 命令、todo_write 工具、提示词贡献与状态栏贡献都应可路由")
    void bootstrap_should_registerAllThreeCapabilities() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        List<String> commands = new ArrayList<String>();
        for (DescriptorBinding<CommandDescriptor> binding
                : extensions.descriptorBindings(CommandRequest.class, CommandDescriptor.class)) {
            if (binding.getRouteKey() != null) {
                commands.add(binding.getRouteKey());
            }
        }
        assertTrue(commands.contains("todo"), commands.toString());

        assertEquals(1, extensions.handlers(StatusLineContributionRequest.class, null).size());

        List<String> tools = new ArrayList<String>();
        for (ToolDescriptor descriptor : extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class)) {
            tools.add(descriptor.getName());
        }
        assertTrue(tools.contains(TodoWriteTool.NAME), tools.toString());

        assertEquals(1, extensions.handlers(PromptContributionRequest.class, null).size());
    }

    @Test
    @DisplayName("插件卸载后三个面的注册都应被按 owner 全部回收")
    void close_should_unregisterAllCapabilities() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(ToolCallRequest.class, TodoWriteTool.NAME).isEmpty());
        assertTrue(extensions.handlers(CommandRequest.class, "todo").isEmpty());
        assertTrue(extensions.handlers(PromptContributionRequest.class, null).isEmpty());
        assertTrue(extensions.handlers(StatusLineContributionRequest.class, null).isEmpty());
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
