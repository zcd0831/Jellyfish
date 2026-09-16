package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigPluginStatusProvider} 的单元测试：验证配置种子、启用名单语义与运行期开关优先级。
 *
 * @author zcd
 */
class ConfigPluginStatusProviderTest {

    @Test
    void isPluginDisabled_should_return_true_when_plugin_in_disabled_list() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(null, setOf("bad")));

        // Then
        assertTrue(provider.isPluginDisabled("bad"));
        assertFalse(provider.isPluginDisabled("good"));
    }

    @Test
    void isPluginDisabled_should_return_true_when_plugin_not_in_enabled_list() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(setOf("good"), null));

        // Then
        assertTrue(provider.isPluginDisabled("other"));
        assertFalse(provider.isPluginDisabled("good"));
    }

    @Test
    void isPluginDisabled_should_return_false_when_enabled_list_empty() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(config(null, null));

        // Then
        assertFalse(provider.isPluginDisabled("anything"));
    }

    @Test
    void isPluginDisabled_should_return_true_when_enabled_list_declared_empty() {
        // Given：显式写 "enabled": [] 表示一个都不启用，而不是「不额外限定」
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(Collections.<String>emptySet(), null));

        // Then
        assertTrue(provider.isPluginDisabled("anything"));
    }

    @Test
    void isPluginDisabled_should_return_false_when_runtime_enable_overrides_declared_empty_list() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(Collections.<String>emptySet(), null));

        // When
        provider.enablePlugin("a");

        // Then
        assertFalse(provider.isPluginDisabled("a"));
    }

    @Test
    void isPluginDisabled_should_return_true_when_runtime_disabled() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(setOf("good"), null));

        // When
        provider.disablePlugin("good");

        // Then
        assertTrue(provider.isPluginDisabled("good"));
    }

    @Test
    void isPluginDisabled_should_return_false_when_runtime_enabled_overrides_config() {
        // Given：配置里被禁用，但运行期显式启用应优先
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(null, setOf("bad")));

        // When
        provider.enablePlugin("bad");

        // Then
        assertFalse(provider.isPluginDisabled("bad"));
    }

    @Test
    void isPluginDisabled_should_return_false_when_runtime_enable_overrides_enabled_list() {
        // Given：不在启用名单内，运行期显式启用后应放行
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(
                config(setOf("a"), null));

        // When
        provider.enablePlugin("b");

        // Then
        assertFalse(provider.isPluginDisabled("b"));
    }

    @Test
    void attach_should_reset_runtime_overrides_to_config() {
        // Given：先有运行期开关（比如运行期启用、又运行期禁用）
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(config(setOf("a"), null));
        provider.enablePlugin("b");
        provider.disablePlugin("a");
        assertFalse(provider.isPluginDisabled("b"));
        assertTrue(provider.isPluginDisabled("a"));

        // When：配置重载——语义是「回到配置说的样子」
        provider.attach(config(setOf("a"), null));

        // Then：运行期开关被清掉，一切以配置为准
        assertFalse(provider.isPluginDisabled("a"));
        assertTrue(provider.isPluginDisabled("b"));
    }

    @Test
    void attach_should_take_effect_immediately_for_readers() {
        // Given
        ConfigPluginStatusProvider provider = new ConfigPluginStatusProvider(config(null, null));
        assertFalse(provider.isPluginDisabled("a"));

        // When：名单改成白名单
        provider.attach(config(setOf("b"), null));

        // Then
        assertTrue(provider.isPluginDisabled("a"));
        assertFalse(provider.isPluginDisabled("b"));
    }

    /**
     * 构造插件运行时配置。
     *
     * @param enabled  启用名单
     * @param disabled 禁用名单
     * @return 装配输入
     */
    private static PluginRuntimeConfig config(Set<String> enabled, Set<String> disabled) {
        return new PluginRuntimeConfig(null, enabled, disabled, null);
    }

    /**
     * 构造测试用集合。
     *
     * @param items 元素
     * @return 集合
     */
    private static Set<String> setOf(String... items) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, items);
        return set;
    }
}
