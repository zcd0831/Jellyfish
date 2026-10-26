package io.github.zcd0831.jellyfish.shell.tui;

import org.jline.reader.LineReader;
import org.jline.reader.Widget;

/**
 * `Alt+Enter`：在缓冲区里插入换行而不是提交（C1 乙）。
 * 终端只要把 Alt+Enter 发成 `ESC CR`，这段就生效；不生效的终端还有行尾 `\` 兜底。
 */
final class InsertNewlineWidget implements Widget {

    private final LineReader reader;

    InsertNewlineWidget(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean apply() {
        reader.getBuffer().write("\n");
        return true;
    }
}
