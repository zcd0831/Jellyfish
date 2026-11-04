package io.github.zcd0831.jellyfish.shell.tui;

/**
 * 行内样式的状态与 ANSI 输出：只在样式变化处插控制序列，不逐字符套壳。
 * <p>
 * 只认最常见的几种：粗体、斜体、行内代码、标题、引用。样式的<b>开关</b>由调用方（字符分派）
 * 决定，这里只负责"当前是什么样式、怎么把一段字符按它写出去"。
 * <p>
 * 规则出处：08-application §8.7（外壳原样呈现正文）。
 */
final class MarkdownStyle {

    private static final String BOLD = "\u001B[1m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CODE = "\u001B[36m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";

    private final boolean color;

    private boolean bold;
    private boolean italic;
    private boolean code;
    private boolean heading;
    private boolean quote;
    /** 已经写出去的样式序列：相邻字符同样式时不再重复插。 */
    private String emitted = "";

    MarkdownStyle(boolean color) {
        this.color = color;
    }

    boolean isBold() {
        return bold;
    }

    boolean isItalic() {
        return italic;
    }

    boolean isCode() {
        return code;
    }

    void setHeading(boolean value) {
        this.heading = value;
    }

    void setQuote(boolean value) {
        this.quote = value;
    }

    /** 行尾/收尾：把还开着的样式收掉，避免染到下一行。 */
    String styleOf() {
        if (!color) {
            return "";
        }
        if (code) {
            return CODE;
        }
        if (bold) {
            return BOLD;
        }
        if (italic) {
            return ITALIC;
        }
        if (quote) {
            return DIM;
        }
        if (heading) {
            return BOLD;
        }
        return "";
    }

    /** 按当前样式写一个字符。 */
    void emit(StringBuilder out, char c) {
        ensureStyle(out);
        out.append(c);
    }

    /** 按当前样式写一段字符（行内代码、普通文本都走这里）。 */
    void emitText(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            emit(out, text.charAt(i));
        }
    }

    /**
     * 写 URL：链接的地址一律不着色，写完补一个复位（与原实现逐字节一致）。
     * 规则出处：08-application §8.7（正文原样呈现，样式不得污染 URL）。
     */
    void emitUrl(StringBuilder out, String url) {
        boolean codeWas = code;
        emitted = styleOf();
        code = false;
        bold = false;
        italic = false;
        quote = false;
        heading = false;
        ensureStyle(out);          // want="" 且已写出过样式 ⇒ 这里补 RESET
        for (int i = 0; i < url.length(); i++) {
            out.append(url.charAt(i));
        }
        if (color) {
            out.append(RESET);
        }
        code = codeWas;
    }

    /** 只在样式变化时插 ANSI，避免每个字符都套一层。 */
    void ensureStyle(StringBuilder out) {
        String want = styleOf();
        if (want.equals(emitted)) {
            return;
        }
        if (!emitted.isEmpty()) {
            out.append(color ? RESET : "");
        }
        if (!want.isEmpty()) {
            out.append(want);
        }
        emitted = want;
    }

    void flush(StringBuilder out) {
        if (!emitted.isEmpty()) {
            out.append(color ? RESET : "");
            emitted = "";
        }
    }

    /** 单反引号成对才切换：`` `` `` 这种多反引号序列不当作行内代码。 */
    void toggleCode(int run) {
        if (run % 2 == 1) {
            code = !code;
            if (code) {
                italic = false;
                bold = false;
            }
        }
    }

    /** `**` 是粗体，单个 `*` 是斜体。 */
    void toggleEmphasis(int run) {
        if (run >= 2) {
            bold = !bold;
        } else {
            italic = !italic;
        }
    }

    void setCode(boolean value) {
        this.code = value;
    }

    void clearEmphasis() {
        bold = false;
        italic = false;
    }

    /** 换行时复位所有行内状态。 */
    void resetLine() {
        heading = false;
        quote = false;
        bold = false;
        italic = false;
        code = false;
    }
}
