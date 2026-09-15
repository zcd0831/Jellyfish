package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.config.PluginsSettings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginRuntimeConfig} 的单元测试：验证缺省语义、快照替换与「同一快照期内引用稳定」这条
 * 供 {@code ReadOnlyTools} 依赖的约定。
 * <p>
 * 扫描目录与名单来自两个不同来源（{@code config.json} 与 {@code jellyfish.json}），
 * 因此 {@code refresh} 是两个入参；目录条目的清洗（展开 {@code ~}、丢弃空白）归 {@code RuntimeConfig}，
 * 本类只按给定顺序原样发布。
 *
 * @author zcd
 */
class PluginRuntimeConfigTest {

    @Test
    void defaults_should_scan_default_root_without_lists_and_configurations() {
        // When
        PluginRuntimeConfig config = PluginRuntimeConfig.defaults();

        // Then
        assertEquals(1, config.getPluginsRoots().size());
        assertEquals(PluginRuntimeConfig.DEFAULT_PLUGINS_ROOT, config.getPluginsRoots().get(0).toString());
        assertTrue(config.getEnabledPluginIds().isEmpty());
        assertTrue(config.getDisabledPluginIds().isEmpty());
        assertTrue(config.getPluginConfigurations().isEmpty());
    }

    @Test
    void getPluginsRoots_should_return_unmodifiable_list() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.ofRoots(Paths.get("plugins"));

        // When / Then
        List<Path> roots = config.getPluginsRoots();
        assertThrows(UnsupportedOperationException.class, () -> roots.add(Paths.get("other")));
    }

    @Test
    void configurationOf_should_return_empty_map_when_absent() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.defaults();

        // Then
        assertTrue(config.configurationOf("plugin-a").isEmpty());
        assertTrue(config.configurationOf(null).isEmpty());
    }

    @Test
    void refresh_should_replace_all_sections_when_settings_given() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.defaults();
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("plugin-a", Collections.<String, Object>singletonMap("k", "v"));

        // When
        config.refresh(Arrays.asList(Paths.get("root-a"), Paths.get("root-b")),
                new PluginsSettings(Arrays.asList("enabled-a"), Arrays.asList("disabled-a"), configurations));

        // Then
        assertEquals(Arrays.asList(Paths.get("root-a"), Paths.get("root-b")), config.getPluginsRoots());
        assertEquals(new LinkedHashSet<>(Collections.singletonList("enabled-a")), config.getEnabledPluginIds());
        assertEquals(new LinkedHashSet<>(Collections.singletonList("disabled-a")), config.getDisabledPluginIds());
        assertEquals(Collections.singletonMap("k", "v"), config.configurationOf("plugin-a"));
    }

    @Test
    void refresh_should_fall_back_to_defaults_when_both_inputs_null() {
        // Given
        PluginRuntimeConfig config = new PluginRuntimeConfig(Collections.singletonList(Paths.get("custom")),
                Collections.singleton("enabled-a"), Collections.singleton("disabled-a"),
                Collections.singletonMap("plugin-a", Collections.<String, Object>emptyMap()));

        // When
        config.refresh(null, null);

        // Then
        assertEquals(PluginRuntimeConfig.DEFAULT_PLUGINS_ROOT, config.getPluginsRoots().get(0).toString());
        assertTrue(config.getEnabledPluginIds().isEmpty());
        assertTrue(config.getDisabledPluginIds().isEmpty());
        assertTrue(config.getPluginConfigurations().isEmpty());
    }

    @Test
    void refresh_should_fall_back_to_default_root_when_roots_empty() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.ofRoots(Paths.get("custom"));

        // When：目录为空但名单仍给出，目录必须回退、名单必须生效
        config.refresh(Collections.<Path>emptyList(),
                new PluginsSettings(Collections.singletonList("enabled-a"), null, null));

        // Then
        assertEquals(PluginRuntimeConfig.DEFAULT_PLUGINS_ROOT, config.getPluginsRoots().get(0).toString());
        assertEquals(new LinkedHashSet<>(Collections.singletonList("enabled-a")), config.getEnabledPluginIds());
    }

    @Test
    void refresh_should_keep_roots_when_settings_null() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.defaults();

        // When：名单缺省不应把已给出的扫描目录一起清掉
        config.refresh(Collections.singletonList(Paths.get("root-a")), null);

        // Then
        assertEquals(Collections.singletonList(Paths.get("root-a")), config.getPluginsRoots());
    }

    @Test
    void getPluginConfigurations_should_be_reference_stable_until_refreshed() {
        // Given
        PluginRuntimeConfig config = PluginRuntimeConfig.defaults();

        // When
        Map<String, Map<String, Object>> first = config.getPluginConfigurations();
        Map<String, Map<String, Object>> second = config.getPluginConfigurations();
        config.refresh(null, new PluginsSettings(null, null,
                Collections.singletonMap("plugin-a", Collections.<String, Object>emptyMap())));
        Map<String, Map<String, Object>> third = config.getPluginConfigurations();

        // Then：同一快照期内必须引用相等，刷新后必须换新实例（消费方靠它判断是否需要重算）
        assertSame(first, second);
        assertNotSame(first, third);
    }

    @Test
    void constructor_should_copy_collections_when_source_changed_afterwards() {
        // Given
        Set<String> enabled = new LinkedHashSet<>(Collections.singletonList("plugin-a"));
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();

        // When
        PluginRuntimeConfig config = new PluginRuntimeConfig(null, enabled, null, configurations);
        enabled.add("plugin-b");
        configurations.put("plugin-b", Collections.<String, Object>emptyMap());

        // Then
        assertEquals(new LinkedHashSet<>(Collections.singletonList("plugin-a")), config.getEnabledPluginIds());
        assertTrue(config.getPluginConfigurations().isEmpty());
    }
}
