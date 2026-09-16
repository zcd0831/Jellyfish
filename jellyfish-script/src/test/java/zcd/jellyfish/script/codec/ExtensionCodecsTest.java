package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扩展点编解码器注册表的单元测试。
 * <p>
 * 这里钉住的最重要一条是「名单只有一份」：11 个扩展点的类型名同时被注册、清单校验与调用路由三处使用，
 * 数量或名字对不上就会出现「清单里能声明、调用时找不到」这类静默失效。
 *
 * @author zcd
 */
@DisplayName("扩展点编解码器注册表")
class ExtensionCodecsTest {

    @Test
    @DisplayName("默认注册表应覆盖全部 11 个扩展点")
    void defaults_should_coverAllElevenExtensionPoints() {
        assertEquals(11, ExtensionCodecs.DEFAULTS.names().size());
        assertTrue(ExtensionCodecs.DEFAULTS.names().containsAll(Arrays.asList(
                "tool", "command", "command_options", "prompt", "status_line", "panel",
                "permission", "session_persist", "session_restore", "session_delete", "compaction")));
    }

    @Test
    @DisplayName("只有三个扩展点带路由键，其余都是类型级")
    void typeLevelNames_should_containOnlyTypeLevelExtensionPoints() {
        assertFalse(ExtensionCodecs.DEFAULTS.isTypeLevel("tool"));
        assertFalse(ExtensionCodecs.DEFAULTS.isTypeLevel("command"));
        assertFalse(ExtensionCodecs.DEFAULTS.isTypeLevel("command_options"));
        assertTrue(ExtensionCodecs.DEFAULTS.isTypeLevel("prompt"));
        assertTrue(ExtensionCodecs.DEFAULTS.isTypeLevel("compaction"));
        assertEquals(8, ExtensionCodecs.DEFAULTS.typeLevelNames().size());
    }

    @Test
    @DisplayName("按类型名应能查到对应 codec，未知类型名返回 null")
    void byName_should_returnNull_when_typeNameIsUnknown() {
        assertEquals(ToolCodec.TYPE_NAME, ExtensionCodecs.DEFAULTS.byName("tool").typeName());
        assertNull(ExtensionCodecs.DEFAULTS.byName("no_such_type"));
        assertNull(ExtensionCodecs.DEFAULTS.byName(null));
        assertFalse(ExtensionCodecs.DEFAULTS.isKnown("no_such_type"));
        assertFalse(ExtensionCodecs.DEFAULTS.isTypeLevel(null));
    }

    @Test
    @DisplayName("类型名重复应在构造期报错")
    void construction_should_rejectDuplicateTypeName() {
        assertThrows(JellyfishException.class, () -> new ExtensionCodecs(
                new ArrayList<ExtensionCodec<?, ?>>(Arrays.asList(new ToolCodec(), new ToolCodec()))));
    }

    @Test
    @DisplayName("空元素应在构造期报错")
    void construction_should_rejectNullElement() {
        assertThrows(JellyfishException.class, () -> new ExtensionCodecs(
                new ArrayList<ExtensionCodec<?, ?>>(Arrays.asList(new ToolCodec(), null))));
    }
}
