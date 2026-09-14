package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.infra.event.EventBusOptions;
import zcd.jellyfish.infra.event.JellyfishEventBus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PF4JPluginManager} 的端到端单元测试：用真实的插件目录跑「加载 → 描述符体检 → 启动」全链路。
 * <p>
 * 测试插件类来自测试源码（父类加载器提供），插件目录里只需一个 {@code plugin.properties}，
 * 因此无需在测试中打包 class 文件。
 *
 * @author zcd
 */
class PF4JPluginManagerTest {

    /** 测试用插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 记录插件生命周期回调，静态共享以便被插件类写入。 */
    private static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    /** 交互枢纽。 */
    private JellyfishEventBus eventBus;

    @BeforeEach
    void setUp() {
        RECORDED.clear();
        eventBus = new JellyfishEventBus(EventBusOptions.defaults());
        eventBus.start();
    }

    @AfterEach
    void tearDown() {
        eventBus.close();
    }

    @Test
    void bootstrap_should_start_plugin_and_register_tool() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));
        assertTrue(RECORDED.contains("start:sample"));
        ToolCallResult result = eventBus.invoke(new ToolCallRequest("echo",
                Collections.<String, Object>emptyMap()));
        assertEquals("ok", result.getOutput());
    }

    @Test
    void bootstrap_should_start_plugin_that_subscribes_notifications() throws IOException {
        // Given：订阅入口不需要任何声明，插件拿到的 PluginContext 就是全部边界
        writePlugin("sample", SubscribingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));
        assertTrue(RECORDED.contains("subscribed"));
    }

    @Test
    void bootstrap_should_mark_failed_when_plugin_class_missing() throws IOException {
        // Given
        writePlugin("sample", null, "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then
        assertEquals(PluginState.FAILED, manager.stateOf("sample"));
    }

    @Test
    void bootstrap_should_keep_other_plugins_running_when_one_start_fails() throws IOException {
        // Given
        writePlugin("broken", FailingPlugin.class.getName(), "");
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then
        assertEquals(PluginState.FAILED, manager.stateOf("broken"));
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));
    }

    @Test
    void bootstrap_should_rollback_partial_registrations_when_start_fails() throws IOException {
        // Given：FailingPlugin 先注册 "half" 再抛异常
        writePlugin("broken", FailingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then：不回滚就会留下“插件已失败、工具还能调”的幽灵注册
        assertEquals(PluginState.FAILED, manager.stateOf("broken"));
        assertThrows(ExtensionException.class, () -> eventBus.invoke(new ToolCallRequest("half",
                Collections.<String, Object>emptyMap())));
    }

    @Test
    void bootstrap_should_block_plugin_whose_dependency_is_rejected() throws IOException {
        // Given：rejected 的描述符不完整（缺 plugin.class），dependent 依赖它
        writePlugin("rejected", null, "");
        writePlugin("dependent", RecordingPlugin.class.getName(), "plugin.dependencies=rejected@1.0.0");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then：必须阻断传递依赖，否则合法插件会把被拒插件拉起来
        assertEquals(PluginState.FAILED, manager.stateOf("rejected"));
        assertEquals(PluginState.FAILED, manager.stateOf("dependent"));
        assertFalse(RECORDED.contains("start:dependent"));
    }

    @Test
    void bootstrap_should_skip_disabled_plugin() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, new LinkedHashSet<>(Collections.singletonList("sample")));

        // When
        manager.bootstrap();

        // Then
        assertEquals(PluginState.DISABLED, manager.stateOf("sample"));
        assertFalse(RECORDED.contains("start:sample"));
    }

    @Test
    void bootstrap_should_throw_when_called_twice() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);
        manager.bootstrap();

        // When / Then
        assertThrows(JellyfishException.class, manager::bootstrap);
    }

    @Test
    void close_should_reclaim_registrations_and_stop_plugins() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);
        manager.bootstrap();

        // When
        manager.close();

        // Then
        assertTrue(RECORDED.contains("stop"));
        assertThrows(ExtensionException.class, () -> eventBus.invoke(new ToolCallRequest("echo",
                Collections.<String, Object>emptyMap())));
    }

    @Test
    void plugins_should_return_empty_list_when_not_bootstrapped() {
        // Given
        PF4JPluginManager manager = newManager(null, null);

        // Then
        assertTrue(manager.plugins().isEmpty());
        assertNull(manager.stateOf("sample"));
    }

    @Test
    void close_should_be_idempotent_when_not_bootstrapped() {
        // Given
        PF4JPluginManager manager = newManager(null, null);

        // When / Then：未启动时关闭不应抛异常
        manager.close();
        manager.close();
    }

    /**
     * 构造门面。
     *
     * @param enabled  启用名单，可为 {@code null}
     * @param disabled 禁用名单，可为 {@code null}
     * @return 插件管理器门面
     */
    private PF4JPluginManager newManager(Set<String> enabled, Set<String> disabled) {
        PluginRuntimeConfig config = new PluginRuntimeConfig(
                Collections.singletonList(pluginsRoot), enabled, disabled, null);
        return new PF4JPluginManager(eventBus, config);
    }

    /**
     * 写入一个插件目录。
     *
     * @param pluginId    插件标识，同时作为目录名
     * @param pluginClass 插件入口类，为 {@code null} 时故意不写该键
     * @param extra       追加的额外描述符内容
     * @throws IOException 写入失败时抛出
     */
    private void writePlugin(String pluginId, String pluginClass, String extra) throws IOException {
        Path dir = pluginsRoot.resolve(pluginId);
        Files.createDirectories(dir);
        StringBuilder properties = new StringBuilder("plugin.id=").append(pluginId).append('\n')
                .append("plugin.version=1.0.0\n");
        if (pluginClass != null) {
            properties.append("plugin.class=").append(pluginClass).append('\n');
        }
        properties.append(extra).append('\n');
        Files.write(dir.resolve(PluginProperties.FILE_NAME),
                properties.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 测试用插件：注册一个工具并记录生命周期。
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
     * 测试用订阅通知的插件。
     *
     * @author zcd
     */
    public static final class SubscribingPlugin implements JellyfishPlugin {

        @Override
        public void start(PluginContext context) {
            // 订阅调用本身即可证明注册入口可用；事件是否到达不在本测试范围内
            context.observe(ConfigWarningEvent.class, event -> RECORDED.add("notified"));
            RECORDED.add("subscribed");
        }
    }

    /**
     * 测试用启动即失败的插件。
     *
     * @author zcd
     */
    public static final class FailingPlugin implements JellyfishPlugin {

        @Override
        public void start(PluginContext context) {
            // 启动途中先注册一半，用于验证失败后按 owner 回滚
            context.handle(ToolCallRequest.class, "half", callback -> new ToolCallResult("half", "half"));
            throw new JellyfishException("boom");
        }
    }
}
