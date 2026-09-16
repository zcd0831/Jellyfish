package zcd.jellyfish.plugin.compact;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 策略可路由 → 卸载回收」。
 * <p>
 * <b>为什么这条测试对本插件尤其重要</b>：本插件的可用性判据是「注册表里有没有处理器」——内核正是靠它
 * 决定压缩整体开不开启。描述符少一个键、入口类没有公开无参构造器，都会让插件悄无声息地不生效，
 * 而表现是「{@code /compact} 说没有插件」，不是报错。单元测试覆盖不到这一段。
 * <p>
 * <b>顺带验证摘要指令真的读得回来</b>：资源要从插件自己的类加载器取，这是插件与内核在“资源归属”
 * 上的分界；读不到就等于压缩不可用。
 *
 * @author zcd
 */
@DisplayName("压缩插件加载链路")
class CompactPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-compact";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，压缩策略的注册落点。 */
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
    @DisplayName("启动后压缩策略应可路由，且能取到摘要指令")
    void bootstrap_should_registerUsableStrategy() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        List<HandlerBinding<CompactionStrategyRequest, CompactionStrategy>> bindings =
                extensions.bindings(CompactionStrategyRequest.class, null);
        assertEquals(1, bindings.size());

        CompactionStrategy strategy = extensions.invoke(bindings.get(0).getHandler(),
                new CompactionStrategyRequest("s-1", CompactionTrigger.MANUAL, 30, 10, 20, 4000, 100_000L, "gpt-4o"));

        assertFalse(strategy.getSummaryPrompt().trim().isEmpty());
        assertTrue(strategy.getSummaryPrompt().contains(CompactionStrategy.MAX_CHARS_PLACEHOLDER));
        // 未配置插件段 → 两项都不表态，由内核缺省值兜底
        assertEquals(null, strategy.getKeepRecentMessages());
    }

    @Test
    @DisplayName("插件卸载后压缩策略应按 owner 被回收：压缩随之回到「不可用」")
    void close_should_unregisterStrategy() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.bindings(CompactionStrategyRequest.class, null).isEmpty());
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
