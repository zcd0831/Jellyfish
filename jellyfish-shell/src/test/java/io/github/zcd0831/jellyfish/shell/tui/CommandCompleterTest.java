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

/** 只补全 `/` 命令，且候选来自固定表（D20-4）。 */
class CommandCompleterTest {

    private static ParsedLine word(String value) {
        ParsedLine line = mock(ParsedLine.class);
        when(line.word()).thenReturn(value);
        return line;
    }

    @Test
    void 斜杠前缀给出匹配命令() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        new CommandCompleter().complete(null, word("/ca"), candidates);

        assertEquals(1, candidates.size());
        assertEquals("/cancel", candidates.get(0).value());
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
}
