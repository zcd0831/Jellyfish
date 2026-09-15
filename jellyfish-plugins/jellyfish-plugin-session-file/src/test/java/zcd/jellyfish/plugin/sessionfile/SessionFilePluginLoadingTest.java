package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类
 * 跑一遍「加载 → 描述符体检 → 启动 → 两个扩展点可路由」。
 * <p>
 * 与 tools 插件同一个套路，但这里还多验证一件事：这个插件包里<b>带了 Jackson</b>，
 * 而插件包自带内核契约会被护栏拦下——因此「启动成功」本身也证明了 shade 没有误伤
 * {@code zcd/jellyfish/api}。
 *
 * @author zcd
 */
@DisplayName("会话持久化插件加载链路")
class SessionFilePluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-session-file";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 会话文件目录，通过插件配置段注入。 */
    @TempDir
    Path sessionDir;

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
    void bootstrap_should_startPlugin() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后落盘与恢复两个扩展点都应可路由")
    void bootstrap_should_registerBothExtensionPoints() throws IOException {
        installPlugin();
        manager = newManager();
        manager.bootstrap();

        assertFalse(extensions.handlers(SessionPersistRequest.class, null).isEmpty());
        assertFalse(extensions.handlers(SessionRestoreRequest.class, null).isEmpty());
    }

    @Test
    @DisplayName("插件关闭后两个扩展点的注册都应被回收")
    void close_should_unregisterExtensionPoints() throws IOException {
        installPlugin();
        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(SessionPersistRequest.class, null).isEmpty());
        assertTrue(extensions.handlers(SessionRestoreRequest.class, null).isEmpty());
    }

    @Test
    @DisplayName("加载进来的插件应能用配置段里的目录落盘")
    void loadedPlugin_should_persistIntoConfiguredDirectory() throws IOException {
        installPlugin();
        manager = newManager();
        manager.bootstrap();

        extensions.invoke(extensions.handler(SessionPersistRequest.class, null),
                new SessionPersistRequest(TestSnapshots.full("session-1")));

        assertTrue(Files.exists(sessionDir.resolve("session-1.json")));
    }

    /**
     * 构造插件管理器：配置段把会话目录指到临时目录并关掉 git。
     * <p>
     * <b>必须注入配置段</b>：不注入就会走默认目录 {@code ~/jellyfish/sessions} 并默认开启 git，
     * 测试会跑到用户主目录里去建仓库。
     *
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put(PluginConfig.KEY_SESSION_DIR, sessionDir.toString());
        configuration.put(PluginConfig.KEY_GIT_ENABLED, false);
        Map<String, Map<String, Object>> configurations =
                Collections.<String, Map<String, Object>>singletonMap(PLUGIN_ID, configuration);
        return new PF4JPluginManager(new PluginContextFactory(extensions, eventChannel, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录。
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
}
