package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void factories_without_choices_should_have_empty_choices() {
        // When / Then
        assertTrue(CommandResult.ok("文本").getChoices().isEmpty());
        assertFalse(CommandResult.ok("文本").hasChoices());
        assertTrue(CommandResult.error("文本").getChoices().isEmpty());
        assertTrue(CommandResult.unknown("文本").getChoices().isEmpty());
    }

    @Test
    void choices_should_carry_output_and_choices() {
        // When
        CommandResult result = CommandResult.choices("可用 agent：",
                Arrays.asList(new CommandChoice("coder", "coder"), new CommandChoice("writer", "writer")));

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("可用 agent：", result.getOutput());
        assertTrue(result.hasChoices());
        assertEquals(2, result.getChoices().size());
        assertFalse(result.isError());
    }

    @Test
    void choices_should_become_empty_when_null_or_empty() {
        // When / Then
        assertFalse(CommandResult.choices("文本", null).hasChoices());
        assertFalse(CommandResult.choices("文本", Collections.<CommandChoice>emptyList()).hasChoices());
    }

    @Test
    void choices_should_reject_null_element() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> CommandResult.choices("文本", Arrays.asList(new CommandChoice("a", "a"), null)));
    }
}
