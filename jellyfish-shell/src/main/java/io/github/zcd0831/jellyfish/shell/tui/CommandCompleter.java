package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.Arrays;
import java.util.List;

/**
 * 只补全 `/` 命令：候选是固定表，不做动态查询（D20-4）。
 */
public final class CommandCompleter implements Completer {

    static final List<String> COMMANDS = Arrays.asList(
            "/cancel", "/archive", "/delete", "/policy",
            "/tools", "/agents", "/models", "/providers", "/skills", "/help", "/exit");

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word() == null ? "" : line.word();
        if (!word.startsWith("/")) {
            return;
        }
        for (String command : COMMANDS) {
            if (command.startsWith(word)) {
                candidates.add(new Candidate(command));
            }
        }
    }
}
