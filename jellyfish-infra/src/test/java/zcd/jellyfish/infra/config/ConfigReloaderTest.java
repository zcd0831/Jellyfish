package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginReloadReport;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigReloader} 的单元测试：验证重载顺序、配置段差异判定与事件广播。
 * <p>
 * {@link PluginRuntimeConfig} 是真实的（它是 final，且「快照替换」这件事本身就是被测行为的一部分）：
 * 用例先把它刷成「当前配置」，再让 {@link RuntimeConfig} 给出「重载后的配置」，
 * 于是差异判定与快照刷新都按真实路径发生。
 * <p>
 * 重载顺序是这里唯一真正的契约：配置只能被读一次，且插件运行时快照必须在插件重载之前刷新。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ConfigReloaderTest {

    /** 插件扫描根目录。 */
    private static final List<Path> ROOTS = Collections.singletonList(Paths.get("plugins"));

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 插件运行时门面。 */
    @Mock
    private PF4JPluginManager pluginManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 真实的插件运行时装配输入。 */
    private PluginRuntimeConfig pluginRuntimeConfig;

    @BeforeEach
    void setUp() {
        pluginRuntimeConfig = new PluginRuntimeConfig(ROOTS, null, null, null);
    }

    @Test
    void reload_should_read_config_once_and_let_agent_reuse_the_same_snapshot() {
        // Given
        when(runtimeConfig.getPluginRoots()).thenReturn(ROOTS);
        when(runtimeConfig.getPluginsSettings()).thenReturn(null);
        when(pluginManager.reload(any())).thenReturn(new PluginReloadReport());

        // When
        newReloader().reload();

        // Then：只有 modelManager 带 true（它负责重读文件并清客户端缓存），agent 复用同一份快照
        InOrder order = Mockito.inOrder(modelManager, agentManager, pluginManager);
        order.verify(modelManager).refresh(true);
        order.verify(agentManager).refresh(false);
        order.verify(pluginManager).reload(any());
    }

    @Test
    void reload_should_restart_only_plugins_whose_configuration_changed() {
        // Given：a 的配置段变了，b 没变
        givenCurrentConfigurations(twoConfigurations("a", 1, "b", 1));
        givenReloadedConfigurations(twoConfigurations("a", 2, "b", 1));
        when(pluginManager.reload(any())).thenReturn(new PluginReloadReport());

        // When
        newReloader().reload();

        // Then
        verify(pluginManager).reload(Collections.<String>singleton("a"));
    }

    @Test
    void reload_should_restart_nothing_when_configuration_is_unchanged() {
        // Given：两份内容完全一样
        Map<String, Map<String, Object>> same = twoConfigurations("a", 1, "b", 1);
        givenCurrentConfigurations(same);
        givenReloadedConfigurations(twoConfigurations("a", 1, "b", 1));
        when(pluginManager.reload(any())).thenReturn(new PluginReloadReport());

        // When
        newReloader().reload();

        // Then
        verify(pluginManager).reload(Collections.<String>emptySet());
    }

    @Test
    void reload_should_treat_removed_configuration_as_a_change() {
        // Given：a 的配置段整个被删掉
        givenCurrentConfigurations(twoConfigurations("a", 1, "b", 1));
        givenReloadedConfigurations(oneConfiguration("b", 1));
        when(pluginManager.reload(any())).thenReturn(new PluginReloadReport());

        // When
        newReloader().reload();

        // Then
        verify(pluginManager).reload(Collections.<String>singleton("a"));
    }

    @Test
    void reload_should_publish_config_reloaded_event_with_touched_plugins_and_duration() {
        // Given
        when(runtimeConfig.getPluginRoots()).thenReturn(ROOTS);
        when(runtimeConfig.getPluginsSettings()).thenReturn(null);
        PluginReloadReport report = mock(PluginReloadReport.class);
        when(report.touchedPluginIds()).thenReturn(new LinkedHashSet<String>(Arrays.asList("a", "b")));
        when(pluginManager.reload(any())).thenReturn(report);

        // When
        ReloadOutcome outcome = newReloader().reload();

        // Then
        ArgumentCaptor<ConfigReloadedEvent> captor = ArgumentCaptor.forClass(ConfigReloadedEvent.class);
        verify(events).publish(captor.capture());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("a", "b")),
                captor.getValue().getRestartedPluginIds());
        assertEquals(captor.getValue().getDurationMillis(), outcome.getDurationMillis());
        assertTrue(outcome.getDurationMillis() >= 0L);
        assertEquals(report, outcome.getPluginReport());
    }

    @Test
    void reload_should_abort_before_plugins_when_config_read_fails() {
        // Given：配置读取失败时快照仍是旧的，此时刷新插件运行时只会拿到半新半旧的配置
        Mockito.doThrow(new IllegalStateException("配置炸了")).when(modelManager).refresh(true);

        // When / Then
        assertThrows(IllegalStateException.class, () -> newReloader().reload());
        verify(agentManager, never()).refresh(false);
        verify(pluginManager, never()).reload(any());
        verify(events, never()).publish(any());
    }

    @Test
    void reload_should_not_fail_when_event_publish_fails() {
        // Given：重载已经成功，不可能因为一条通知发不出去而失败
        when(runtimeConfig.getPluginRoots()).thenReturn(ROOTS);
        when(runtimeConfig.getPluginsSettings()).thenReturn(null);
        when(pluginManager.reload(any())).thenReturn(new PluginReloadReport());
        Mockito.doThrow(new IllegalStateException("通道炸了")).when(events).publish(any());

        // When
        ReloadOutcome outcome = newReloader().reload();

        // Then
        assertTrue(outcome.getDurationMillis() >= 0L);
    }

    /**
     * 构造被测重载器。
     *
     * @return 重载器
     */
    private ConfigReloader newReloader() {
        return new ConfigReloader(runtimeConfig, modelManager, agentManager, pluginRuntimeConfig, pluginManager,
                events);
    }

    /**
     * 把真实的插件装配输入刷成「重载前」的配置。
     *
     * @param configurations 配置段
     */
    private void givenCurrentConfigurations(Map<String, Map<String, Object>> configurations) {
        pluginRuntimeConfig.refresh(ROOTS, new PluginsSettings(null, null, configurations));
    }

    /**
     * 打桩「重载后读到的新配置」。
     *
     * @param configurations 配置段
     */
    private void givenReloadedConfigurations(Map<String, Map<String, Object>> configurations) {
        when(runtimeConfig.getPluginRoots()).thenReturn(ROOTS);
        when(runtimeConfig.getPluginsSettings()).thenReturn(new PluginsSettings(null, null, configurations));
    }

    /**
     * 构造含两个插件的配置段映射。
     *
     * @param firstId     第一个插件
     * @param firstValue  第一个插件的标记值
     * @param secondId    第二个插件
     * @param secondValue 第二个插件的标记值
     * @return 配置段映射
     */
    private static Map<String, Map<String, Object>> twoConfigurations(String firstId, int firstValue,
                                                                      String secondId, int secondValue) {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put(firstId, section(firstValue));
        configurations.put(secondId, section(secondValue));
        return configurations;
    }

    /**
     * 构造含单个插件的配置段映射。
     *
     * @param pluginId 插件标识
     * @param value    标记值
     * @return 配置段映射
     */
    private static Map<String, Map<String, Object>> oneConfiguration(String pluginId, int value) {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put(pluginId, section(value));
        return configurations;
    }

    /**
     * 构造单个插件的配置段。
     *
     * @param marker 标记值
     * @return 配置段
     */
    private static Map<String, Object> section(int marker) {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("marker", marker);
        return section;
    }
}
