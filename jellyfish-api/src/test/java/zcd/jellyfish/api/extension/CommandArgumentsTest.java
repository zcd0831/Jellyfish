package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandArguments} 的单元测试：验证两种视图、不可变性与 {@code null} 归一化。
 *
 * @author zcd
 */
class CommandArgumentsTest {

    @Test
    void empty_should_have_no_tokens_and_blank_raw() {
        // When / Then
        assertTrue(CommandArguments.EMPTY.isEmpty());
        assertEquals(0, CommandArguments.EMPTY.size());
        assertTrue(CommandArguments.EMPTY.getTokens().isEmpty());
        assertEquals("", CommandArguments.EMPTY.getRaw());
    }

    @Test
    void getters_should_return_constructed_values() {
        // Given
        CommandArguments arguments = new CommandArguments(Arrays.asList("coder", "extra"), "coder  extra");

        // Then
        assertEquals(Arrays.asList("coder", "extra"), arguments.getTokens());
        assertEquals("coder  extra", arguments.getRaw());
        assertEquals(2, arguments.size());
        assertFalse(arguments.isEmpty());
    }

    @Test
    void constructor_should_normalize_null_tokens_and_raw() {
        // When
        CommandArguments arguments = new CommandArguments(null, null);

        // Then
        assertTrue(arguments.isEmpty());
        assertEquals("", arguments.getRaw());
    }

    @Test
    void constructor_should_keep_empty_token_element() {
        // Given：结构化入口可以显式传一个空参数（原文入口的 "" 也产出空 token）
        List<String> tokens = new ArrayList<String>();
        tokens.add("");

        // When
        CommandArguments arguments = new CommandArguments(tokens, "\"\"");

        // Then
        assertEquals(1, arguments.size());
        assertEquals("", arguments.getTokens().get(0));
    }

    @Test
    void tokens_should_be_defensive_copy_and_unmodifiable() {
        // Given
        List<String> tokens = new ArrayList<String>();
        tokens.add("coder");
        CommandArguments arguments = new CommandArguments(tokens, "coder");

        // When：改动入参不应影响参数对象
        tokens.add("extra");

        // Then
        assertEquals(1, arguments.size());
        assertThrows(UnsupportedOperationException.class, () -> arguments.getTokens().add("extra"));
    }

    @Test
    void constructor_should_throw_when_token_is_null() {
        // Given：null 元素只可能来自结构化入口的调用方，属于编程错误
        List<String> tokens = new ArrayList<String>();
        tokens.add(null);

        // When / Then
        assertThrows(JellyfishException.class, () -> new CommandArguments(tokens, ""));
    }

    @Test
    void toString_should_render_tokens_and_raw() {
        // When / Then
        assertEquals("CommandArguments{tokens=[a], raw=a b}",
                new CommandArguments(Arrays.asList("a"), "a b").toString());
    }
}
