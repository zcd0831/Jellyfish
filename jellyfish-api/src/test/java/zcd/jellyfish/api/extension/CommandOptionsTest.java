package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandOptions} 的单元测试：验证候选复制、空值与不可变性。
 *
 * @author zcd
 */
class CommandOptionsTest {

    @Test
    void of_should_keep_choices() {
        // When
        CommandOptions options = CommandOptions.of(Arrays.asList(
                new CommandChoice("coder", "coder"), new CommandChoice("writer", "writer")));

        // Then
        assertEquals(2, options.getChoices().size());
        assertFalse(options.isEmpty());
    }

    @Test
    void of_should_return_empty_when_null_or_empty() {
        // When / Then
        assertTrue(CommandOptions.of(null).isEmpty());
        assertTrue(CommandOptions.of(Collections.<CommandChoice>emptyList()).isEmpty());
        assertTrue(CommandOptions.empty().isEmpty());
        assertTrue(CommandOptions.empty().getChoices().isEmpty());
    }

    @Test
    void of_should_reject_null_element() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> CommandOptions.of(Arrays.asList(new CommandChoice("a", "a"), null)));
    }

    @Test
    void getChoices_should_be_unmodifiable() {
        // Given
        CommandOptions options = CommandOptions.of(Collections.singletonList(new CommandChoice("a", "a")));

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> options.getChoices().add(null));
    }

    @Test
    void toString_should_render_choice_count_only() {
        // When / Then
        assertEquals("CommandOptions{choices=1}",
                CommandOptions.of(Collections.singletonList(new CommandChoice("a", "a"))).toString());
    }
}
