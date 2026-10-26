package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * `/` 命令与参数的补全：命令名来自 {@link Commands}（唯一清单），
 * 参数来自 {@link ParameterCandidates}（只读列表，现取现用）。
 */
public final class CommandCompleter implements Completer {

    private final ParameterCandidates parameters;

    public CommandCompleter() {
        this(ParameterCandidates.NONE);
    }

    public CommandCompleter(ParameterCandidates parameters) {
        this.parameters = parameters;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word() == null ? "" : line.word();
        String command = commandOf(line);
        if (command == null) {
            // 还在敲命令名
            if (word.startsWith("/")) {
                for (String name : Commands.NAMES) {
                    if (name.startsWith(word)) {
                        candidates.add(new Candidate(name));
                    }
                }
            }
            return;
        }
        if (word.indexOf('=') >= 0) {
            return;   // `/policy k=v` 已经给了值，不再补
        }
        for (Candidate candidate : parameters.candidates(command)) {
            if (candidate.value().startsWith(word)) {
                candidates.add(candidate);
            }
        }
    }

    /** 行首第一个词是不是已识别的命令：是就返回命令名（此时在补参数），否则 null。 */
    private static String commandOf(ParsedLine line) {
        String full = line.line() == null ? "" : line.line();
        int space = full.indexOf(' ');
        if (space < 0 || line.wordIndex() == 0) {
            return null;
        }
        String first = full.substring(0, space).trim();
        return Commands.NAMES.contains(first) ? first : null;
    }
}
