package io.github.zcd0831.jellyfish.shell.tui;

/**
 * 流式行内 Markdown：边收边转 ANSI，不攒整行。
 * <p>
 * 只认最常见的几种：`**粗体**`、`*斜体*`、`` `行内代码` ``、行首 `#` 标题、
 * `- * +` 列表、`> ` 引用，以及 ``` 围栏（围栏内原样保留、不折行）。
 * 链接、表格、嵌套不处理——用不到的语法保持原样，比"猜错"安全。
 * <p>
 * 未闭合的标记：开启后一直生效到行尾（流式中途本来就可能是半截），不吐多余字符；
 * 没开启过的（比如 `2 * 3`、列表外的 `-`）按字面输出。换行时复位所有行内状态。
 */
final class MarkdownStream {

    private static final String BOLD = "\u001B[1m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CODE = "\u001B[36m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";
    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final int MAX_LINK = 200;

    private final boolean color;
    private final StringBuilder out = new StringBuilder();

    private boolean lineStart = true;
    private boolean inFence;
    private boolean skippingFenceLine;
    private boolean heading;
    private boolean quote;

    private boolean bold;
    private boolean italic;
    private boolean code;
    private String emitted = "";

    private int starRun;
    private int backtickRun;
    private int hashRun;
    private char markerCandidate;
    private boolean skipQuoteSpace;
    /** 正在攒一个 `[文字](url)`：跨分块时先存着，凑齐 `)` 再决定渲染还是原样吐出。 */
    private StringBuilder linkBuffer;

    MarkdownStream(boolean color) {
        this.color = color;
    }

    /** 是否在围栏内：围栏内不折行，交给终端自己折。 */
    boolean inFence() {
        return inFence;
    }

    /** 一轮结束时收尾：把还没配对的标记按字面吐出，并复位围栏（下一轮从干净状态开始）。 */
    String finish() {
        out.setLength(0);
        if (linkBuffer != null) {
            emitLinkBufferAsIs();
        }
        closePendingMarker();
        flushStyle();
        lineStart = true;
        inFence = false;
        skippingFenceLine = false;
        return out.toString();
    }

    /** 喂一段增量，返回可以直接打印的带样式文本（可能为空）。 */
    String feed(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        out.setLength(0);
        for (int i = 0; i < delta.length(); i++) {
            accept(delta.charAt(i));
        }
        flushStyle();
        return out.toString();
    }

    private void accept(char c) {
        if (c == '\n') {
            if (skippingFenceLine) {
                skippingFenceLine = false;
                lineStart = true;
                return;
            }
            closePendingMarker();
            if (linkBuffer != null) {
                emitLinkBufferAsIs();
            }
            skipLineMarker();
            out.append('\n');
            resetLine();
            return;
        }
        if (skippingFenceLine) {
            return;
        }
        if (inFence) {
            // 围栏内原样保留，但要能识别收尾的那一行 ```
            if (lineStart && c == '`') {
                backtickRun++;
                if (backtickRun == 3) {
                    inFence = false;
                    skippingFenceLine = true;
                    backtickRun = 0;
                    lineStart = false;
                }
                return;
            }
            if (backtickRun > 0) {
                for (int i = 0; i < backtickRun; i++) {
                    out.append('`');
                }
                backtickRun = 0;
            }
            out.append(c);
            return;
        }
        if (skipQuoteSpace) {
            skipQuoteSpace = false;
            if (c == ' ') {
                return;
            }
        }
        if (lineStart) {
            lineStart(c);
            return;
        }
        if (linkBuffer != null) {
            linkBuffer.append(c);
            if (c == ')') {
                emitLink();
            } else if (linkBuffer.length() > MAX_LINK) {
                emitLinkBufferAsIs();
            }
            return;
        }
        if (c == '[') {
            linkBuffer = new StringBuilder("[").append("");
            linkBuffer.setLength(0);
            linkBuffer.append('[');
            return;
        }
        if (c == '`') {
            backtickRun++;
            return;
        }
        if (backtickRun > 0) {
            toggleCode(backtickRun);
            backtickRun = 0;
        }
        if (c == '*') {
            starRun++;
            return;
        }
        if (starRun > 0) {
            if (c == ' ' || starRun > 2) {
                for (int i = 0; i < starRun; i++) {
                    emit('*');
                }
            } else {
                toggleEmphasis(starRun);
            }
            starRun = 0;
        }
        emit(c);
    }

    private void lineStart(char c) {
        // 围栏：行首连续三个反引号（可带语言名）——整行吞掉，只切换模式
        if (c == '`') {
            backtickRun++;
            if (backtickRun == 3) {
                inFence = !inFence;
                skippingFenceLine = true;
                backtickRun = 0;
                lineStart = false;
            }
            return;
        }
        if (backtickRun > 0) {
            int run = backtickRun;
            backtickRun = 0;
            if (run < 3) {
                for (int i = 0; i < run; i++) {
                    emit('`');
                }
            }
        }
        // 标题：`#` 序列 + 空格
        if (c == '#') {
            hashRun++;
            return;
        }
        if (hashRun > 0) {
            int run = hashRun;
            hashRun = 0;
            if (c == ' ') {
                heading = true;
                lineStart = false;
                ensureStyle();
                return;
            }
            for (int i = 0; i < run; i++) {
                emit('#');
            }
        }
        // 列表：`-` / `*` / `+` + 空格；`**` 不是列表，而是强调开头
        if (markerCandidate != 0) {
            char marker = markerCandidate;
            if (marker == '*' && c == '*') {
                markerCandidate = 0;
                lineStart = false;
                starRun = 2;
                return;
            }
            markerCandidate = 0;
            if (c == ' ') {
                emit('•');
                emit(' ');
                lineStart = false;
                return;
            }
            if (marker == '*') {
                lineStart = false;
                starRun = 1;
                accept(c);
                return;
            }
            emit(marker);
            lineStart = false;
            accept(c);
            return;
        }
        if (c == '-' || c == '*' || c == '+') {
            markerCandidate = c;
            return;
        }
        // 引用：`>` + 可选空格
        if (c == '>') {
            quote = true;
            emit('│');
            emit(' ');
            lineStart = false;
            skipQuoteSpace = true;
            return;
        }
        lineStart = false;
        accept(c);
    }

    /** 行结束/收尾时把"看到了但没配对的标记"按字面吐出来。 */
    /** 语法成立就渲染成 `文字（url）`，否则原样吐出（不猜）。 */
    private void emitLink() {
        String raw = linkBuffer.toString();
        linkBuffer = null;
        java.util.regex.Matcher matcher = LINK.matcher(raw);
        if (!matcher.matches()) {
            for (int i = 0; i < raw.length(); i++) {
                emit(raw.charAt(i));
            }
            return;
        }
        String text = matcher.group(1);
        String url = matcher.group(2);
        for (int i = 0; i < text.length(); i++) {
            emit(text.charAt(i));
        }
        emit('（');
        String before = styleOf();
        emitted = before;
        boolean codeWas = code;
        code = false;
        bold = false;
        italic = false;
        quote = false;
        heading = false;
        ensureStyle();
        for (int i = 0; i < url.length(); i++) {
            out.append(url.charAt(i));
        }
        if (color) {
            out.append(RESET);
        }
        emitted = "";
        out.append('）');
        code = codeWas;
    }

    private void emitLinkBufferAsIs() {
        String raw = linkBuffer.toString();
        linkBuffer = null;
        for (int i = 0; i < raw.length(); i++) {
            emit(raw.charAt(i));
        }
    }

    private void closePendingMarker() {
        if (backtickRun > 0) {
            for (int i = 0; i < backtickRun; i++) {
                emit('`');
            }
            backtickRun = 0;
            code = false;
        }
        if (starRun > 0) {
            if (bold || italic) {
                // 已经按样式渲染到行尾：直接收尾，别吐出多余的星号
                starRun = 0;
            } else {
                for (int i = 0; i < starRun; i++) {
                    emit('*');
                }
                starRun = 0;
            }
            bold = false;
            italic = false;
        }
        if (hashRun > 0) {
            for (int i = 0; i < hashRun; i++) {
                emit('#');
            }
            hashRun = 0;
        }
    }

    private void skipLineMarker() {
        if (markerCandidate != 0) {
            char marker = markerCandidate;
            markerCandidate = 0;
            emit(marker);
        }
    }

    private void resetLine() {
        lineStart = true;
        skippingFenceLine = false;
        heading = false;
        quote = false;
        bold = false;
        italic = false;
        code = false;
    }

    private void toggleCode(int run) {
        if (run % 2 == 1) {
            code = !code;
            if (code) {
                italic = false;
                bold = false;
            }
        }
    }

    private void toggleEmphasis(int run) {
        if (run >= 2) {
            bold = !bold;
        } else {
            italic = !italic;
        }
    }

    private void emit(char c) {
        ensureStyle();
        out.append(c);
    }

    /** 只在样式变化时插 ANSI，避免每个字符都套一层。 */
    private void ensureStyle() {
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

    private void flushStyle() {
        if (!emitted.isEmpty()) {
            out.append(color ? RESET : "");
            emitted = "";
        }
    }

    private String styleOf() {
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
}
