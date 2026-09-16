package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
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
 * Python 桥接插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类
 * 跑一遍「加载 → 描述符体检 → 启动 → 停止」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「{@code start()} 里的逻辑对」，证明不了这个 jar 真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约（{@code zcd/jellyfish/api} 或 {@code org/pf4j}）、
 * 入口类没有公开无参构造器，任何一项出错都会让插件在运行时悄无声息地不生效，而单元测试全绿。
 * <p>
 * <b>这里顺带钉住本方案最重要的一条性质</b>：启动期<b>不要求 Python 存在、也不拉起任何进程</b>。
 * 测试环境里 {@code scripts/python} 目录并不存在，插件依然必须启动成功——这正是
 * 「Python 环境损坏不阻塞内核启动」的机器可验证形式。
 *
 * @author zcd
 */
@DisplayName("Python 桥接插件加载链路")
class PythonPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-python";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
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
    @DisplayName("脚本目录不存在时插件仍应启动成功，不拉起任何 Python 进程")
    void bootstrap_should_startPlugin_when_scriptsRootIsMissing() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
        assertTrue(Files.notExists(PythonConfig.from(null).scriptsRoot()),
                "测试前提不成立：默认脚本根目录意外存在");
    }

    @Test
    @DisplayName("关闭插件运行时后应不再报告任何已启动插件")
    void close_should_releaseStartedPlugins_when_pluginRuntimeIsClosed() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));

        // 先接管引用再置空，避免 tearDown 对同一个管理器重复关闭
        PF4JPluginManager closing = manager;
        manager = null;
        closing.close();

        assertTrue(closing.plugins().isEmpty());
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
