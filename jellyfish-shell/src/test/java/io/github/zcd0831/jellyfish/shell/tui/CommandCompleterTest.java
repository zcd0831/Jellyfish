package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 只补全 `/` 命令，且候选来自固定表。 */
class CommandCompleterTest {

    private static ParsedLine word(String value) {
        ParsedLine line = mock(ParsedLine.class);
        when(line.word()).thenReturn(value);
        when(line.line()).thenReturn(value);
        when(line.wordIndex()).thenReturn(0);
        return line;
    }

    /** 模拟"命令 + 空格 + 正在补的参数"。 */
    private static ParsedLine argument(String full) {
        ParsedLine line = mock(ParsedLine.class);
        String word = full.substring(full.lastIndexOf(' ') + 1);
        when(line.word()).thenReturn(word);
        when(line.line()).thenReturn(full);
        when(line.wordIndex()).thenReturn(1);
        return line;
    }

    @Test
    void 斜杠前缀给出匹配命令() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        new CommandCompleter().complete(null, word("/ar"), candidates);

        assertEquals(1, candidates.size());
        assertEquals("/archive", candidates.get(0).value());
    }

    @Test
    void 非斜杠输入不补全() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        new CommandCompleter().complete(null, word("你好"), candidates);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void 单个斜杠列出全部命令() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        new CommandCompleter().complete(null, word("/"), candidates);

        assertEquals(Commands.NAMES.size(), candidates.size(), "补全候选必须与 /help 同一份清单");
    }

    @Test
    void 命令表包含会话与插件命令() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        new CommandCompleter().complete(null, word("/s"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> "/sessions".equals(c.value())), "应有 /sessions");
        assertTrue(Commands.NAMES.contains("/open"), "应有 /open");
        assertTrue(Commands.NAMES.contains("/plugins"), "应有 /plugins");
        assertTrue(Commands.NAMES.contains("/model"), "应有 /model");
    }

    @Test
    void 参数补全只给该命令的候选() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        CommandCompleter completer = new CommandCompleter(command -> "/open".equals(command)
                ? java.util.Arrays.asList(new Candidate("abc-123"), new Candidate("def-456"))
                : java.util.Collections.<Candidate>emptyList());

        completer.complete(null, argument("/open ab"), candidates);

        assertEquals(1, candidates.size());
        assertEquals("abc-123", candidates.get(0).value());
    }

    @Test
    void 没有参数候选的命令不退回命令名() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        CommandCompleter completer = new CommandCompleter(command -> java.util.Collections.<Candidate>emptyList());

        completer.complete(null, argument("/tools x"), candidates);

        assertTrue(candidates.isEmpty(), "/tools 不吃参数，也不该补出命令名");
    }

    @Test
    void 策略键给了值就不再补() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        CommandCompleter completer = new CommandCompleter(command ->
                java.util.Collections.singletonList(new Candidate("maxReActRounds=")));

        completer.complete(null, argument("/policy maxReActRounds=10"), candidates);

        assertTrue(candidates.isEmpty());
    }
}
