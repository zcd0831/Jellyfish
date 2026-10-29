package io.github.zcd0831.jellyfish.shell.tui;

/**
 * 多行输入的判定：
 * <ul>
 *   <li>行尾 `\` —— 不提交，拼到下一行（跨终端都靠得住）；</li>
 *   <li>行内已带换行（粘贴多行）—— 按"整段提交"处理，不再看行尾反斜杠。</li>
 * </ul>
 * `Alt+Enter` 不再绑定：`Esc` 已被占成终止键，而终端把 `Alt+X` 发成 `ESC X`，
 * 两者在键位表里无法共存（见 TerminateKey）。
 */
final class DraftInput {

    private static final String CONTINUATION = "\\";

    private final StringBuilder text = new StringBuilder();

    /** 接受一行；返回 true 表示"还没完，继续读下一行"。 */
    boolean accept(String line) {
        String value = line == null ? "" : line;
        if (value.indexOf('\n') >= 0) {
            text.append(value);
            return false;
        }
        String trimmed = value.endsWith(" ") ? value.substring(0, value.lastIndexOf(' ')) : value;
        if (trimmed.endsWith(CONTINUATION)) {
            text.append(trimmed, 0, trimmed.length() - CONTINUATION.length()).append('\n');
            return true;
        }
        text.append(value);
        return false;
    }

    /** 取出完整输入并清空（提交时调用）。 */
    String take() {
        String value = text.toString();
        text.setLength(0);
        return value;
    }

    /** Ctrl-C 或放弃时清空。 */
    void clear() {
        text.setLength(0);
    }

    boolean isContinuing() {
        return text.length() > 0;
    }
}
