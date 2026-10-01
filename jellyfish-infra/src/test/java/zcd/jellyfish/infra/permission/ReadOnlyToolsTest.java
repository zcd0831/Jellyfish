package zcd.jellyfish.infra.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.config.PluginsSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ReadOnlyTools} 的单元测试：验证「用户配置是唯一来源」、快照换新后的重算与配置容错。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ReadOnlyToolsTest {

    /** 事件发布入口，用于验证告警。 */
    @Mock
    private EventPublisher events;

    /** 真实的同步扩展点策略：用来锁定「注册表里的工具不影响白名单」这条语义。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    @Test
    void names_should_merge_read_only_tools_of_all_plugins() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Arrays.asList("read_file", "list_dir")));
        configurations.put("plugin-b", declaration(Collections.singletonList("grep")));

        // When
        ReadOnlyTools tools = tools(configurations);

        // Then
        assertEquals(new LinkedHashSet<>(Arrays.asList("read_file", "list_dir", "grep")), tools.names());
        assertTrue(tools.contains("grep"));
        assertFalse(tools.contains("bash"));
        verifyNoInteractions(events);
    }

    @Test
    void names_should_be_empty_when_no_plugin_declares_read_only_tools() {
        // Given：一个插件没有该键，另一个插件整段配置缺失
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", Collections.<String, Object>singletonMap("other", "value"));
        configurations.put("plugin-b", null);

        // When
        ReadOnlyTools tools = tools(configurations);

        // Then
        assertTrue(tools.names().isEmpty());
        assertFalse(tools.contains("read_file"));
        verifyNoInteractions(events);
    }

    @Test
    void contains_should_be_false_for_null_tool_name() {
        // Given
        ReadOnlyTools tools = tools(null);

        // Then
        assertFalse(tools.contains(null));
        verifyNoInteractions(events);
    }

    @Test
    void names_should_skip_blank_and_non_string_entries_and_warn() {
        // Given：混入空白字符串、null 与数字
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Arrays.asList("read_file", "  ", null, 42)));

        // When
        ReadOnlyTools tools = tools(configurations);

        // Then
        assertEquals(Collections.singleton("read_file"), tools.names());
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events, times(3)).publish(captor.capture());
        assertEquals("plugin-a", captor.getValue().getSource());
        assertTrue(captor.getValue().getMessage().contains("readOnlyTools"));
    }

    @Test
    void names_should_warn_and_skip_when_value_is_not_collection() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put(PermissionSettings.READ_ONLY_TOOLS, "read_file");
        configurations.put("plugin-a", pluginConfig);

        // When
        ReadOnlyTools tools = tools(configurations);

        // Then
        assertTrue(tools.names().isEmpty());
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events).publish(captor.capture());
        assertEquals("plugin-a", captor.getValue().getSource());
        assertTrue(captor.getValue().getMessage().contains("数组"));
    }

    @Test
    void names_should_be_unmodifiable() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Collections.singletonList("read_file")));

        // When
        ReadOnlyTools tools = tools(configurations);

        // Then
        assertThrows(UnsupportedOperationException.class, () -> tools.names().add("bash"));
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new ReadOnlyTools(null, events));
        assertThrows(NullPointerException.class, () -> new ReadOnlyTools(pluginRuntimeConfig(null), null));
    }

    @Test
    void names_should_be_recomputed_when_plugin_snapshot_replaced() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Collections.singletonList("read_file")));
        PluginRuntimeConfig config = pluginRuntimeConfig(configurations);
        ReadOnlyTools tools = new ReadOnlyTools(config, events);
        assertEquals(Collections.singleton("read_file"), tools.names());

        // When：配置刷新后快照换新，白名单必须跟着换
        config.refresh(null, new PluginsSettings(null, null,
                Collections.singletonMap("plugin-a", declaration(Collections.singletonList("grep")))));

        // Then
        assertEquals(Collections.singleton("grep"), tools.names());
        assertFalse(tools.contains("read_file"));
    }

    @Test
    void names_should_be_empty_when_plugin_snapshot_refreshed_to_empty() {
        // Given
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Collections.singletonList("read_file")));
        PluginRuntimeConfig config = pluginRuntimeConfig(configurations);
        ReadOnlyTools tools = new ReadOnlyTools(config, events);
        assertEquals(Collections.singleton("read_file"), tools.names());

        // When
        config.refresh(null, null);

        // Then
        assertTrue(tools.names().isEmpty());
    }

    @Test
    void names_should_not_reparse_when_snapshot_unchanged() {
        // Given：空白项会产出一条告警，用它判断「解析发生了几次」
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Arrays.asList("read_file", "  ")));
        ReadOnlyTools tools = tools(configurations);

        // When
        tools.names();
        tools.names();

        // Then：同一快照只解析一次，告警不重复
        verify(events, times(1)).publish(any(ConfigWarningEvent.class));
    }

    @Test
    void names_should_ignore_registered_tools_when_config_is_empty() {
        // Given：注册表里有工具（内核自注册的、插件注册的，都算）
        register("read_file");
        register("todo_write");

        // When：用户没有配置任何白名单
        ReadOnlyTools tools = tools(null);

        // Then：白名单仍为空——注册表不再是判据，这正是本次改造要锁定的语义
        assertTrue(tools.names().isEmpty());
        assertFalse(tools.contains("read_file"));
        assertFalse(tools.contains("todo_write"));
    }

    @Test
    void names_should_not_follow_plugin_hot_deploy() {
        // Given：用户配置了 legacy_read，同时插件注册了一个工具
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", declaration(Collections.singletonList("legacy_read")));
        ReadOnlyTools tools = tools(configurations);
        Subscription subscription = register("read_file");
        assertEquals(Collections.singleton("legacy_read"), tools.names());

        // When：插件下架
        subscription.close();

        // Then：白名单只跟配置走，注册表的变化一点都反映不到这里
        assertEquals(Collections.singleton("legacy_read"), tools.names());
        assertFalse(tools.contains("read_file"));
    }

    /**
     * 注册一个工具处理器，只关心「注册表里确实有它」。
     *
     * @param name 工具名
     * @return 注册句柄，{@code close()} 即卸载该工具
     */
    private Subscription register(String name) {
        return extensions.handle("test-owner", ToolCallRequest.class, name, new ToolDescriptor(name, name),
                request -> new ToolCallResult(name, "ok"), RegisterOptions.DEFAULT);
    }

    /**
     * 构造被测对象。
     *
     * @param configurations 插件配置段
     * @return 只读工具集合
     */
    private ReadOnlyTools tools(Map<String, Map<String, Object>> configurations) {
        return new ReadOnlyTools(pluginRuntimeConfig(configurations), events);
    }

    /**
     * 构造插件配置段：只声明只读工具白名单。
     *
     * @param readOnlyTools 只读工具值
     * @return 配置段
     */
    private static Map<String, Object> declaration(Object readOnlyTools) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put(PermissionSettings.READ_ONLY_TOOLS, readOnlyTools);
        return configuration;
    }

    /**
     * 构造插件运行时装配输入。
     *
     * @param configurations pluginId → 配置段，可为 {@code null}
     * @return 装配输入
     */
    private static PluginRuntimeConfig pluginRuntimeConfig(Map<String, Map<String, Object>> configurations) {
        return new PluginRuntimeConfig(null, null, null, configurations);
    }
}
