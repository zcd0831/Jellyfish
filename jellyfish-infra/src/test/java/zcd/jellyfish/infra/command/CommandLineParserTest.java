package zcd.jellyfish.infra.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.extension.CommandArguments;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandLineParser} 的单元测试：验证前缀判定、命令名提取、引号感知切分与原文保留。
 *
 * @author zcd
 */
class CommandLineParserTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "help", "/", "/   ", "help /help"})
    void parse_should_report_not_command(String input) {
        // When
        ParsedCommand parsed = CommandLineParser.parse(input);

        // Then
        assertFalse(parsed.isCommand());
        assertNull(parsed.name());
        assertTrue(parsed.arguments().isEmpty());
        assertNull(parsed.error());
    }

    @Test
    void parse_should_extract_name_ignoring_surrounding_whitespace() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("  /sql:query  select 1  ");

        // Then
        assertTrue(parsed.isCommand());
        assertEquals("sql:query", parsed.name());
    }

    @Test
    void parse_should_keep_raw_text_but_not_leading_whitespace() {
        // When：原文只去掉紧随命令名的那段空白，内部空白原样保留
        ParsedCommand parsed = CommandLineParser.parse("/note   a  b");

        // Then
        assertEquals("a  b", parsed.arguments().getRaw());
        assertEquals(Arrays.asList("a", "b"), parsed.arguments().getTokens());
    }

    @Test
    void parse_should_return_empty_arguments_when_only_name_given() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/help");

        // Then
        assertEquals("help", parsed.name());
        assertTrue(parsed.arguments().isEmpty());
        assertEquals("", parsed.arguments().getRaw());
    }

    @Test
    void parse_should_split_on_tabs_as_well() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/m\ta\tb");

        // Then
        assertEquals(Arrays.asList("a", "b"), parsed.arguments().getTokens());
    }

    @Test
    void parse_should_keep_quoted_whitespace_in_single_token() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/m a \"b c\" d");

        // Then：引号内空白不切分，引号字符本身不进入 token，但 raw 保留原样
        assertEquals(Arrays.asList("a", "b c", "d"), parsed.arguments().getTokens());
        assertEquals("a \"b c\" d", parsed.arguments().getRaw());
    }

    @Test
    void parse_should_support_single_quotes() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/m 'b c'");

        // Then
        assertEquals(Arrays.asList("b c"), parsed.arguments().getTokens());
    }

    @Test
    void parse_should_produce_one_empty_token_for_empty_quotes() {
        // When：显式空参数
        ParsedCommand parsed = CommandLineParser.parse("/m \"\"");

        // Then
        assertEquals(1, parsed.arguments().size());
        assertEquals("", parsed.arguments().getTokens().get(0));
    }

    @Test
    void parse_should_unescape_backslash_and_quote_inside_double_quotes() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/m \"a\\\\b\" \"q\\\"q\"");

        // Then
        assertEquals(Arrays.asList("a\\b", "q\"q"), parsed.arguments().getTokens());
    }

    @Test
    void parse_should_keep_backslash_literal_outside_double_quotes() {
        // When：转义只在双引号内生效
        ParsedCommand parsed = CommandLineParser.parse("/m a\\\\b");

        // Then
        assertEquals(Arrays.asList("a\\\\b"), parsed.arguments().getTokens());
    }

    @Test
    void parse_should_report_error_when_quote_is_unterminated() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/m \"abc");

        // Then：仍是命令（前缀与名字都在），只是参数读不出来
        assertTrue(parsed.isCommand());
        assertEquals("m", parsed.name());
        assertTrue(parsed.error().contains("引号未闭合"));
        assertTrue(parsed.arguments().isEmpty());
    }

    @Test
    void parse_should_be_case_sensitive() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("/Help");

        // Then
        assertEquals("Help", parsed.name());
    }

    @Test
    void parse_should_strip_only_one_prefix() {
        // When
        ParsedCommand parsed = CommandLineParser.parse("//x");

        // Then
        assertEquals("/x", parsed.name());
        assertEquals(CommandArguments.EMPTY.getRaw(), parsed.arguments().getRaw());
    }
}
