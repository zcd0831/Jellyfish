package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;

/**
 * `Esc` 的键位绑定。<strong>`Esc` 同时是 `Alt+Enter`（`ESC CR`）的前缀</strong>，
 * 所以两条绑定必须分别指向不同的 widget：长序列优先匹配，剩下的单个 `ESC` 才是终止。
 * 规则出处：08-application §8.9.4.1（用户终止 = 按一次 Esc）。
 */
final class TerminateKey {

    static final String TERMINATE = "jellyfish-terminate";
    static final String ESC = "\u001B";
    /**
     * `Esc` 一旦被绑成完整按键，JLine 就<strong>立刻</strong>分派它：它不会为等 `ESC CR` 而停留
     * （`KeyMap.setAmbiguousTimeout` 只对「未绑定前缀」生效，实测 ESC CR 仍先触发终止）。
     * 所以多行输入交给行尾 `\\` 续行，不再绑 `Alt+Enter`。
     * 规则出处：08-application §8.9.4.1（用户终止 = 按一次 Esc）。
     */
    static final long AMBIGUOUS_TIMEOUT_MILLIS = 100L;

    private TerminateKey() {
    }

    /** 绑定失败（键位表不支持）不影响行尾 `\` 续行，只记录在调用方。 */
    static void bind(LineReader reader, Runnable onTerminate) {
        KeyMap main = reader.getKeyMaps().get(LineReader.MAIN);
        reader.getWidgets().put(TERMINATE, new TerminateWidget(onTerminate));
        main.setAmbiguousTimeout(AMBIGUOUS_TIMEOUT_MILLIS);
        main.bind(new Reference(TERMINATE), ESC);
    }
}
