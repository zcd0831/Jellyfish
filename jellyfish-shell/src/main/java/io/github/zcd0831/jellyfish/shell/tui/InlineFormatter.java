package io.github.zcd0831.jellyfish.shell.tui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单元格/整行的一次性行内 Markdown（与流式版 {@link MarkdownStream} 同口径）。
 * 表格单元格没有"跨块"问题，用正则一次替换比状态机简单得多。
 */
final class InlineFormatter {

    private static final String BOLD = "\u001B[1m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CODE = "\u001B[36m";
    private static final String RESET = "\u001B[0m";

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]*)`");
    private static final Pattern BOLD_SPAN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_SPAN = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");

    private final boolean color;

    InlineFormatter(boolean color) {
        this.color = color;
    }

    String render(String text) {
        String out = text == null ? "" : text;
        out = replace(out, LINK, null, false);
        out = replace(out, CODE_SPAN, CODE, true);
        out = replace(out, BOLD_SPAN, BOLD, true);
        out = replace(out, ITALIC_SPAN, ITALIC, true);
        return out;
    }

    private String replace(String text, Pattern pattern, String style, boolean keepMarkers) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String body;
            if (pattern == LINK) {
                String plain = matcher.group(1) + "（" + matcher.group(2) + "）";
                body = color ? plain : plain;
            } else {
                body = matcher.group(1);
            }
            String painted = color && style != null ? style + body + RESET : body;
            matcher.appendReplacement(result, Matcher.quoteReplacement(painted));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 去掉 ANSI，只留可见字符——算列宽用。 */
    static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
