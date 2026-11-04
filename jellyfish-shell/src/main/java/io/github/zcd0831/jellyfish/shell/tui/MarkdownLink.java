package io.github.zcd0831.jellyfish.shell.tui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行内链接 `[文字](url)` 的攒检：跨分块时先攒着，凑齐 `)` 再决定渲染还是原样吐出。
 * <p>
 * 语法成立就渲染成 `文字（url）`，否则一个字符都不改地吐回去 —— 用不到的语法保持原样，
 * 比"猜错"安全。攒得太长（超过 {@link #MAX_LINK}）也直接按原样吐出，避免把整段文本憋住。
 * <p>
 * 规则出处：08-application §8.7（外壳原样呈现正文）。
 */
final class MarkdownLink {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final int MAX_LINK = 200;

    private StringBuilder buffer;

    /** 是否正在攒一个链接。 */
    boolean active() {
        return buffer != null;
    }

    void start() {
        buffer = new StringBuilder("[");
    }

    /** 攒一个字符；凑齐就落地。 */
    void accept(char c, MarkdownStyle style, StringBuilder out) {
        buffer.append(c);
        if (c == ')') {
            emit(style, out);
        } else if (buffer.length() > MAX_LINK) {
            flushAsIs(style, out);
        }
    }

    /** 没凑成链接（换行、收尾、超长）：原样吐出已攒的部分。 */
    void flushAsIs(MarkdownStyle style, StringBuilder out) {
        if (buffer == null) {
            return;   // 没有攒着的东西：一行里没出现过 `[`
        }
        style.emitText(out, take());
    }

    private void emit(MarkdownStyle style, StringBuilder out) {
        String raw = take();
        Matcher matcher = LINK.matcher(raw);
        if (!matcher.matches()) {
            style.emitText(out, raw);
            return;
        }
        style.emitText(out, matcher.group(1));
        out.append('（');
        style.emitUrl(out, matcher.group(2));
        out.append('）');
    }

    private String take() {
        String raw = buffer.toString();
        buffer = null;
        return raw;
    }
}
