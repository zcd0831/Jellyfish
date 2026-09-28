package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.InputDirectiveDescriptor;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类
 * 跑一遍「加载 → 描述符体检 → 启动 → 工具可路由 → 权限处理器被登记」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这条插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。
 * <p>
 * <b>为什么还要单独断言权限处理器</b>：它是本插件唯一一个<b>类型级</b>贡献。
 * 类型级注册与路由键注册走的是两条不同的注册路径，而「装了但没挂上」的表现是
 * 「命令不再需要审批」——一个不会报错、只会悄悄降低安全性的故障。
 * <p>
 * 插件根目录是临时目录，插件类由父加载器（测试 classpath）提供，因此无需在测试里打包 jar。
 *
 * @author zcd
 */
@DisplayName("命令行插件加载链路")
class ShellPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-shell";

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
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后 shell 工具可从注册表按名字路由，且声明为可写")
    void bootstrap_should_registerShellTool() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        List<String> names = new ArrayList<String>();
        for (ToolDescriptor descriptor : extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class)) {
            names.add(descriptor.getName());
            if (ShellTool.TOOL_NAME.equals(descriptor.getName())) {
                // 声明成只读会让 PLAN 模式放行它，而命令行能写任何文件
                assertTrue(!descriptor.isReadOnly(), "shell 不能声明为只读工具");
            }
        }
        assertEquals(1, names.size(), names.toString());
        assertTrue(names.contains(ShellTool.TOOL_NAME), names.toString());
    }

    @Test
    @DisplayName("权限分类器应被登记为类型级贡献")
    void bootstrap_should_registerPermissionContribution() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(1, extensions.descriptorBindings(PermissionCheckRequest.class, Object.class).size());
    }

    @Test
    @DisplayName("输入指令 ! 应被登记为路由键注册")
    void bootstrap_should_registerInputDirective() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(1, extensions.descriptorBindings(InputDirectiveRequest.class,
                InputDirectiveDescriptor.class).size());
        assertEquals(1, extensions.handlers(InputDirectiveRequest.class, ShellInputDirective.MARKER).size());
    }

    @Test
    @DisplayName("插件卸载后工具注册应被按 owner 全部回收")
    void close_should_unregisterShellTool() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(ToolCallRequest.class, ShellTool.TOOL_NAME).isEmpty());
        assertTrue(extensions.descriptorBindings(PermissionCheckRequest.class, Object.class).isEmpty());
        assertTrue(extensions.handlers(InputDirectiveRequest.class, ShellInputDirective.MARKER).isEmpty());
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
