package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandResult} 的单元测试：验证三态工厂、判错语义与文本可空。
 *
 * @author zcd
 */
class CommandResultTest {

    @Test
    void ok_should_return_ok_kind_and_output() {
        // When
        CommandResult result = CommandResult.ok("已切换模型");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("已切换模型", result.getOutput());
        assertFalse(result.isError());
    }

    @Test
    void error_should_be_error() {
        // When
        CommandResult result = CommandResult.error("参数缺失");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.isError());
    }

    @Test
    void unknown_should_be_error() {
        // When
        CommandResult result = CommandResult.unknown("未知命令：/x");

        // Then
        assertEquals(CommandResult.Kind.UNKNOWN, result.getKind());
        assertTrue(result.isError());
    }

    @Test
    void factories_should_allow_null_output() {
        // When / Then
        assertNull(CommandResult.ok(null).getOutput());
        assertNull(CommandResult.error(null).getOutput());
        assertNull(CommandResult.unknown(null).getOutput());
    }

    @Test
    void toString_should_render_kind_only() {
        // When / Then：整篇帮助文本不该混进日志行
        assertEquals("CommandResult{kind=OK}", CommandResult.ok("第一行\n第二行").toString());
    }
}
