package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandChoice} 的单元测试：验证取值校验、显示文本回退与当前标记。
 *
 * @author zcd
 */
class CommandChoiceTest {

    @Test
    void constructor_should_keep_value_label_description_and_current() {
        // When
        CommandChoice choice = new CommandChoice("coder", "编码助手", "负责写代码", true);

        // Then
        assertEquals("coder", choice.getValue());
        assertEquals("编码助手", choice.getLabel());
        assertEquals("负责写代码", choice.getDescription());
        assertTrue(choice.isCurrent());
    }

    @Test
    void constructor_should_fall_back_to_value_when_label_is_null_or_empty() {
        // When / Then
        assertEquals("coder", new CommandChoice("coder", null, null, false).getLabel());
        assertEquals("coder", new CommandChoice("coder", "", null, false).getLabel());
        assertNull(new CommandChoice("coder", null, null, false).getDescription());
        assertFalse(new CommandChoice("coder", null, null, false).isCurrent());
    }

    @Test
    void constructor_should_reject_blank_value() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new CommandChoice(null, "x", null, false));
        assertThrows(JellyfishException.class, () -> new CommandChoice("   ", "x", null, false));
    }
}
