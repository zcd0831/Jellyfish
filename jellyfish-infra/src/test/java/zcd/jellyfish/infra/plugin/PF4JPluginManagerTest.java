package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.config.PluginsSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** 等待异步通知到达的超时（毫秒）。 */
    private static final long AWAIT_TIMEOUT_MILLIS = 3000L;

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MILLIS = 5L;

    /** 会话域服务：桩，只为满足插件上下文的构造。 */
    private final SessionManager sessions = Mockito.mock(SessionManager.class);

    /** 测试用插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 记录插件生命周期回调，静态共享以便被插件类写入。 */
    private static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    /** 收到的插件状态变更通知，形如 {@code pluginId=STATE}；由事件通道异步写入。 */
    private final List<String> stateChanges = new CopyOnWriteArrayList<>();

    /** 共用注册表：同步处理器与事件订阅都落在这里。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel eventChannel;

    /** 插件上下文工厂。 */
    private PluginContextFactory contexts;

    @BeforeEach
    void setUp() {
        RECORDED.clear();
        stateChanges.clear();
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
        eventChannel.subscribe("test", PluginStateChangedEvent.class,
                event -> stateChanges.add(event.getPluginId() + "=" + event.getState()));
        contexts = new PluginContextFactory(extensions, eventChannel, registry, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()), SubAgentPort.unavailable(), AskPort.unavailable());
    }

    @AfterEach
    void tearDown() {
        eventChannel.close();
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
        ToolCallResult result = callTool("echo");
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
        assertThrows(ExtensionException.class, () -> callTool("half"));
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
        assertThrows(ExtensionException.class, () -> callTool("echo"));
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

    @Test
    void reload_should_be_noop_when_not_bootstrapped() throws IOException {
        // Given：还没启动就重载，插件集合为空，重载只能是什么都不做
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        PluginReloadReport report = manager.reload(Collections.singleton("sample"));

        // Then
        assertTrue(report.isEmpty());
    }

    @Test
    void reload_should_restart_reconfigured_plugin_with_new_configuration() throws IOException {
        // Given：插件在 start 时记录自己的配置段
        writePlugin("sample", ConfigEchoPlugin.class.getName(), "");
        PluginRuntimeConfig config = newConfig(null, null, configurations("sample", "v1"));
        PF4JPluginManager manager = newManager(config);
        manager.bootstrap();
        assertTrue(RECORDED.contains("config:v1"));

        // When：配置段变了，重载后插件必须拿到新值
        config.refresh(Collections.singletonList(pluginsRoot),
                new PluginsSettings(null, null, configurations("sample", "v2")));
        PluginReloadReport report = manager.reload(Collections.singleton("sample"));

        // Then
        assertTrue(RECORDED.contains("stop"), "重启应先停止旧实例");
        assertTrue(RECORDED.contains("config:v2"), "重启后的上下文必须读到新配置段");
        assertEquals(Collections.singletonList("sample"), report.getRestarted());
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));
        // 重启后注册仍可用：旧注册已按 owner 回收，新注册已建立
        assertEquals("ok", callTool("echo").getOutput());
    }

    @Test
    void reload_should_not_restart_plugin_whose_configuration_is_unchanged() throws IOException {
        // Given
        writePlugin("sample", ConfigEchoPlugin.class.getName(), "");
        PluginRuntimeConfig config = newConfig(null, null, configurations("sample", "v1"));
        PF4JPluginManager manager = newManager(config);
        manager.bootstrap();

        // When：配置段一模一样，重载不应重启它
        config.refresh(Collections.singletonList(pluginsRoot),
                new PluginsSettings(null, null, configurations("sample", "v1")));
        PluginReloadReport report = manager.reload(Collections.<String>emptySet());

        // Then
        assertFalse(report.touchedPluginIds().contains("sample"));
    }

    @Test
    void reload_should_stop_plugin_that_became_disabled_and_start_it_again() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PluginRuntimeConfig config = newConfig(null, null, null);
        PF4JPluginManager manager = newManager(config);
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));

        // When：配置把它禁用了
        config.refresh(Collections.singletonList(pluginsRoot),
                new PluginsSettings(null, Collections.singletonList("sample"), null));
        PluginReloadReport disabling = manager.reload(Collections.<String>emptySet());

        // Then：不仅要不在运行，注册也必须回收干净
        assertFalse(manager.stateOf("sample").isStarted());
        assertEquals(Collections.singletonList("sample"), disabling.getStopped());
        assertThrows(ExtensionException.class, () -> callTool("echo"));

        // When：又启用了
        config.refresh(Collections.singletonList(pluginsRoot), new PluginsSettings(null, null, null));
        PluginReloadReport enabling = manager.reload(Collections.<String>emptySet());

        // Then
        assertEquals(PluginState.STARTED, manager.stateOf("sample"));
        assertEquals(Collections.singletonList("sample"), enabling.getStarted());
        assertEquals("ok", callTool("echo").getOutput());
    }

    @Test
    void bootstrap_should_publish_started_state_when_plugin_starts() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then：指标与外壳的 UI 失效都依赖这条通知
        awaitStateChange("sample=STARTED");
    }

    @Test
    void bootstrap_should_publish_failed_state_when_plugin_start_fails() throws IOException {
        // Given：FailingPlugin 在 start 里抛异常
        writePlugin("broken", FailingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then：失败态是本项目自己补的（markFailed），必须同样广播出去——否则“插件启动失败”在指标里是空白
        awaitStateChange("broken=FAILED");
    }

    @Test
    void bootstrap_should_publish_failed_state_when_descriptor_is_rejected() throws IOException {
        // Given：缺少 plugin.class，在描述符体检阶段就被拒
        writePlugin("rejected", null, "");
        PF4JPluginManager manager = newManager(null, null);

        // When
        manager.bootstrap();

        // Then
        awaitStateChange("rejected=FAILED");
    }

    @Test
    void close_should_publish_stopped_state_when_plugins_stop() throws IOException {
        // Given
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, null);
        manager.bootstrap();
        awaitStateChange("sample=STARTED");

        // When
        manager.close();

        // Then
        awaitStateChange("sample=STOPPED");
    }

    @Test
    void bootstrap_should_not_publish_started_state_for_disabled_plugin() throws IOException {
        // Given：sample 启用、off 被禁用
        writePlugin("sample", RecordingPlugin.class.getName(), "");
        writePlugin("off", RecordingPlugin.class.getName(), "");
        PF4JPluginManager manager = newManager(null, new LinkedHashSet<>(Collections.singletonList("off")));

        // When
        manager.bootstrap();

        // Then：先等 enabled 那条到达（证明确实有一次派发发生），再断言被禁用的那个没有通知——
        // 没有发生状态变化就没有通知，否则外壳会为一次不存在的变化重收集
        awaitStateChange("sample=STARTED");
        assertFalse(stateChanges.contains("off=STARTED"), "被禁用的插件不该广播启动: " + stateChanges);
    }

    /**
     * 以调用点的方式调用工具：先查找处理器，再执行它。
     *
     * @param toolName 工具名
     * @return 工具调用结果
     */
    private ToolCallResult callTool(String toolName) {
        ToolCallRequest request = new ToolCallRequest(toolName, Collections.<String, Object>emptyMap());
        return extensions.invoke(extensions.handler(ToolCallRequest.class, toolName), request);
    }

    /**
     * 等待一条插件状态变更通知到达，超时即失败。
     *
     * @param expected 期望的通知文本，形如 {@code pluginId=STATE}
     */
    private void awaitStateChange(String expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (!stateChanges.contains(expected) && System.currentTimeMillis() < deadline) {
            sleep();
        }
        assertTrue(stateChanges.contains(expected),
                "未在超时内收到状态变更通知: " + expected + "，实收 " + stateChanges);
    }

    /**
     * 短暂休眠，等待异步派发。
     */
    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构造门面。
     *
     * @param enabled  启用名单，可为 {@code null}
     * @param disabled 禁用名单，可为 {@code null}
     * @return 插件管理器门面
     */
    private PF4JPluginManager newManager(Set<String> enabled, Set<String> disabled) {
        return newManager(newConfig(enabled, disabled, null));
    }

    /**
     * 构造可运行期刷新的装配输入。
     *
     * @param enabled        启用名单，可为 {@code null}
     * @param disabled       禁用名单，可为 {@code null}
     * @param configurations 插件配置段，可为 {@code null}
     * @return 装配输入
     */
    private PluginRuntimeConfig newConfig(Set<String> enabled, Set<String> disabled,
                                          Map<String, Map<String, Object>> configurations) {
        return new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), enabled, disabled, configurations);
    }

    /**
     * 以装配输入构造门面。
     *
     * @param config 装配输入
     * @return 插件管理器门面
     */
    private PF4JPluginManager newManager(PluginRuntimeConfig config) {
        return new PF4JPluginManager(contexts, config, eventChannel);
    }

    /**
     * 构造单个插件的配置段。
     *
     * @param pluginId 插件标识
     * @param marker   标记值
     * @return 配置段映射
     */
    private static Map<String, Map<String, Object>> configurations(String pluginId, String marker) {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("marker", marker);
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put(pluginId, section);
        return configurations;
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
     * 测试用插件：把启动时拿到的配置段记录进共享列表。
     * <p>
     * 存在的意义只有一个：证明「上下文在每次启动时重建」——配置段变了，重启后的插件必须看到新值。
     *
     * @author zcd
     */
    public static final class ConfigEchoPlugin implements JellyfishPlugin {

        @Override
        public void start(PluginContext context) {
            Object marker = context.configuration().get("marker");
            RECORDED.add("config:" + marker);
            context.handle(ToolCallRequest.class, "echo", callback -> new ToolCallResult("echo", "ok"));
        }

        @Override
        public void stop() {
            RECORDED.add("stop");
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
