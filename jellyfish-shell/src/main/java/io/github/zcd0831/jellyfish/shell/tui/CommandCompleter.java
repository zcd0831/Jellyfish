package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * 只补全 `/` 命令：候选来自 {@link Commands}（唯一清单），不做动态查询。
 */
public final class CommandCompleter implements Completer {

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word() == null ? "" : line.word();
        if (!word.startsWith("/")) {
            return;
        }
        for (String command : Commands.NAMES) {
            if (command.startsWith(word)) {
                candidates.add(new Candidate(command));
            }
        }
    }
}
