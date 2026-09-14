package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.pf4j.RuntimeMode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JellyfishPluginFactory} 的单元测试：验证用插件类加载器实例化 SPI 实现并包装成 PF4J 插件。
 * <p>
 * 测试插件类放在测试源码里、由父类加载器提供，因此无需真的打包 class 文件；
 * 插件目录只需一个 {@code plugin.properties}。
 *
 * @author zcd
 */
class JellyfishPluginFactoryTest {

    /** 测试用插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 插件上下文工厂，用于创建插件上下文。 */
    private final PluginContextFactory contexts = new PluginContextFactory(
            new ExtensionRegistry(new TypeRegistry()),
            new EventChannel(EventChannelOptions.defaults(), new TypeRegistry()),
            new TypeRegistry());

    /** 记录插件生命周期回调。 */
    private static final List<String> RECORDED = new ArrayList<>();

    /** 被测插件工厂。 */
    private JellyfishPluginFactory factory;

    @BeforeEach
    void setUp() {
        RECORDED.clear();
        JellyfishPluginManager manager = new JellyfishPluginManager(contexts,
                PluginRuntimeConfig.ofRoots(pluginsRoot));
        factory = new JellyfishPluginFactory(manager);
    }

    @Test
    void create_should_wrap_plugin_and_delegate_lifecycle() throws IOException {
        // Given
        PluginWrapper wrapper = wrapperOf("sample", RecordingPlugin.class.getName());

        // When
        Plugin plugin = factory.create(wrapper);
        plugin.start();
        plugin.stop();

        // Then
        assertEquals(2, RECORDED.size());
        assertTrue(RECORDED.get(0).startsWith("start:sample"));
        assertEquals("stop", RECORDED.get(1));
    }

    @Test
    void create_should_throw_when_class_does_not_implement_spi() throws IOException {
        // Given
        PluginWrapper wrapper = wrapperOf("sample", NotAPlugin.class.getName());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.create(wrapper));
    }

    @Test
    void create_should_throw_when_no_public_no_arg_constructor() throws IOException {
        // Given
        PluginWrapper wrapper = wrapperOf("sample", NoDefaultConstructorPlugin.class.getName());

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.create(wrapper));
    }

    @Test
    void create_should_throw_when_plugin_class_not_found() throws IOException {
        // Given
        PluginWrapper wrapper = wrapperOf("sample", "com.acme.MissingPlugin");

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.create(wrapper));
    }

    @Test
    void create_should_throw_when_plugin_class_missing_in_descriptor() throws IOException {
        // Given：漏写 plugin.class 时父类会兜底为 org.pf4j.Plugin，工厂必须明确拒绝
        PluginWrapper wrapper = wrapperOf("sample", null);

        // When / Then
        assertThrows(JellyfishException.class, () -> factory.create(wrapper));
    }

    /**
     * 落盘描述符并构造插件包装器。
     *
     * @param pluginId    插件标识
     * @param pluginClass 插件入口类；为 {@code null} 时故意不写该键
     * @return 插件包装器
     * @throws IOException 写入失败时抛出
     */
    private PluginWrapper wrapperOf(String pluginId, String pluginClass) throws IOException {
        Path dir = pluginsRoot.resolve(pluginId);
        Files.createDirectories(dir);
        StringBuilder properties = new StringBuilder("plugin.id=").append(pluginId).append('\n');
        if (pluginClass != null) {
            properties.append("plugin.class=").append(pluginClass).append('\n');
        }
        Files.write(dir.resolve(PluginProperties.FILE_NAME),
                properties.toString().getBytes(StandardCharsets.UTF_8));
        JellyfishPluginDescriptor descriptor =
                (JellyfishPluginDescriptor) new JellyfishPluginDescriptorFinder().find(dir);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.getRuntimeMode()).thenReturn(RuntimeMode.DEPLOYMENT);
        return new PluginWrapper(pluginManager, descriptor, dir, getClass().getClassLoader());
    }

    /**
     * 测试用插件：注册一个工具，便于验证上下文确实透传。
     *
     * @author zcd
     */
    public static final class RecordingPlugin implements JellyfishPlugin {

        @Override
        public void start(PluginContext context) {
            RECORDED.add("start:" + context.pluginId());
            context.handle(ToolCallRequest.class, "echo", callback -> new ToolCallResult("echo", "ok"));
        }

        @Override
        public void stop() {
            RECORDED.add("stop");
        }
    }

    /**
     * 测试用非插件类。
     *
     * @author zcd
     */
    public static final class NotAPlugin {
    }

    /**
     * 测试用无公开无参构造器的插件。
     *
     * @author zcd
     */
    public static final class NoDefaultConstructorPlugin implements JellyfishPlugin {

        /**
         * 私有构造器：插件实例化必须使用公开无参构造器。
         *
         * @param ignored 占位参数，用于避免隐式无参构造器
         */
        private NoDefaultConstructorPlugin(String ignored) {
            // 仅为阻止隐式公开无参构造器
        }

        @Override
        public void start(PluginContext context) {
            RECORDED.add("start:" + context.pluginId());
        }
    }
}
