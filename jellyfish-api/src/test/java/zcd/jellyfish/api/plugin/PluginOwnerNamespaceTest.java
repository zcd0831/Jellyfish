package zcd.jellyfish.api.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件 owner 命名空间约定的单元测试。
 * <p>
 * {@code requireChildId} 被两处使用：插件自己拼来源（脚本清单里的脚本标识）与框架派生命名空间。
 * 它存在的意义就是「规则只有一份」，因此这里逐条钉住规则本身——
 * 一旦被改宽或改窄，另一侧的调用方会以「启动时才失败」的形式暴露出来，而现场离原因很远。
 *
 * @author zcd
 */
@DisplayName("插件 owner 命名空间")
class PluginOwnerNamespaceTest {

    @Test
    @DisplayName("分隔符应是双冒号")
    void separator_should_beDoubleColon() {
        assertEquals("::", PluginOwnerNamespace.SEPARATOR);
    }

    @Test
    @DisplayName("合法子标识应原样返回")
    void requireChildId_should_returnValueAsIs_when_childIdIsValid() {
        assertEquals("jira", PluginOwnerNamespace.requireChildId("jira"));
        assertEquals("a-b_c.d", PluginOwnerNamespace.requireChildId("a-b_c.d"));
    }

    @Test
    @DisplayName("空白子标识应报错")
    void requireChildId_should_rejectBlank_when_childIdIsNullOrBlank() {
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId(null));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId("   "));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId(""));
    }

    @Test
    @DisplayName("含空白字符或路径分隔符的子标识应报错，且不静默 trim")
    void requireChildId_should_rejectWhitespaceAndPathSeparators() {
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId("a b"));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId("a/b"));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId("a\\b"));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId("jira "));
        assertThrows(JellyfishException.class, () -> PluginOwnerNamespace.requireChildId(" jira"));
    }

    @Test
    @DisplayName("自带命名空间分隔符的子标识应报错，避免在命名空间内再造层级")
    void requireChildId_should_rejectNamespaceSeparator() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> PluginOwnerNamespace.requireChildId("a::b"));

        assertTrue(failure.getMessage().contains("::"), failure.getMessage());
    }
}
