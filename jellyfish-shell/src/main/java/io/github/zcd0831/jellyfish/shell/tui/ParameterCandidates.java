package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.Candidate;

import java.util.List;

/**
 * 参数补全的来源：给定命令名，返回它的候选（命令本身不在里面）。
 * 只读列表、现取现用——不在启动时预加载，避免启动变慢。
 */
interface ParameterCandidates {

    /** 该命令没有参数补全时返回空列表。 */
    List<Candidate> candidates(String command);

    ParameterCandidates NONE = command -> java.util.Collections.emptyList();
}
