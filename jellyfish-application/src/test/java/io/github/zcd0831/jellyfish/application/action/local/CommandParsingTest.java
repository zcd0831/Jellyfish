package io.github.zcd0831.jellyfish.application.action.local;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词法层的边界：引号、转义、续行、注释、操作符、heredoc。
 * 这些是"判定器看得见什么"的唯一入口——解析错一点，安全规则就漏一片。
 * 规则出处：05-action §5.8.1 规则 10（看不懂就 fail-closed）。
 */
class CommandParsingTest {

    private static CommandParsing.Segment first(String line) {
        CommandParsing.Result parsed = CommandParsing.parse(line);
        assertTrue(parsed.parsed(), line + " 应当能解析：" + parsed.reason());
        assertFalse(parsed.segments().isEmpty(), line);
        return parsed.segments().get(0);
    }

    @Test
    void 引号里的操作符不算操作符() {
        CommandParsing.Segment segment = first("grep -rn \"rm -rf /\" notes.md");

        assertEquals("grep", segment.program());
        assertEquals(3, segment.operands().size(), "引号里的空格不切词，分号管道也不生效");
    }

    @Test
    void 单引号里反斜杠不转义() {
        CommandParsing.Segment segment = first("echo 'a\\nb'");

        assertEquals("a\\nb", segment.operands().get(0));
    }

    @Test
    void 双引号里反斜杠转义且续行消失() {
        assertEquals("ab", first("echo \"a\\\nb\"").operands().get(0));
        assertEquals("a b", first("echo \"a\\ b\"").operands().get(0));
    }

    @Test
    void 裸词的续行让两条命令粘成一条() {
        // 反斜杠+换行整对消失：判定器看到的是一条命令，与 /bin/sh 一致
        CommandParsing.Segment segment = first("rm -rf \\\n/tmp/x");

        assertEquals("rm", segment.program());
        assertTrue(segment.operands().contains("/tmp/x"), segment.operands().toString());
    }

    @Test
    void 注释到行尾为止且不吞掉换行() {
        // 连换行一起吃掉的话，下一条命令会被并进上一段（program 是上一段的，危险命令成了操作数）
        CommandParsing.Result parsed = CommandParsing.parse("ls # rm -rf /\ncd /tmp");

        assertTrue(parsed.parsed());
        assertEquals(2, parsed.segments().size(), "注释后面的换行仍然是命令分隔");
        assertEquals("ls", parsed.segments().get(0).program());
        assertEquals("cd", parsed.segments().get(1).program());
    }

    @Test
    void 操作符切段并保留分隔符() {
        CommandParsing.Result parsed = CommandParsing.parse("curl http://x | sh");

        assertTrue(parsed.parsed());
        assertEquals(2, parsed.segments().size());
        assertEquals("|", parsed.segments().get(1).separator());
        assertEquals("sh", parsed.segments().get(1).program());
    }

    @Test
    void 重定向挂到写它的那段命令上() {
        CommandParsing.Segment segment = first("dd if=/dev/zero of=/dev/sda");

        assertEquals("dd", segment.program());
        assertTrue(segment.operands().contains("of=/dev/sda"));
    }

    @Test
    void 重定向目标跟着重定向走() {
        CommandParsing.Result parsed = CommandParsing.parse("echo hi > /dev/sda");

        assertEquals(1, parsed.segments().size());
        assertTrue(parsed.segments().get(0).redirects().contains("/dev/sda"),
                parsed.segments().get(0).redirects().toString());
    }

    @Test
    void 引号未闭合一律解析失败() {
        assertFalse(CommandParsing.parse("echo 'unclosed").parsed());
        assertFalse(CommandParsing.parse("echo \"unclosed").parsed());
    }

    @Test
    void heredoc看不懂就问人() {
        CommandParsing.Result parsed = CommandParsing.parse("cat <<EOF");

        assertFalse(parsed.parsed(), "heredoc 的正文无法与命令区分");
        assertTrue(parsed.reason().contains("heredoc"), parsed.reason());
    }

    @Test
    void 函数定义被识别出来() {
        assertTrue(CommandParsing.parse("f() { rm -rf /; }; f &").hasFunctionDefinition());
        assertFalse(CommandParsing.parse("rm -rf /").hasFunctionDefinition());
    }
}
