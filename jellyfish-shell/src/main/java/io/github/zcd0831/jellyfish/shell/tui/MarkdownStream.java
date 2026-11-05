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
 * <p>
 * 职责分工：样式在 {@link MarkdownStyle}，表格块在 {@link MarkdownTableBuffer}，
 * 这里只做"看清一个字符属于哪一类，然后交给对应的一方"。
 * 规则出处：08-application §8.7（外壳原样呈现正文）。
 */
final class MarkdownStream {

    private static final int FENCE = 3;

    private final int width;
    private final StringBuilder out = new StringBuilder();
    private final MarkdownStyle style;
    private final MarkdownTableBuffer tables;

    private boolean lineStart = true;
    private boolean inFence;
    private boolean skippingFenceLine;

    private int starRun;
    private int backtickRun;
    private int hashRun;
    private char markerCandidate;
    private boolean skipQuoteSpace;
    private final MarkdownLink link = new MarkdownLink();

    /** 正在输出"已判定不是表格"的整行：跳过块级判定，避免自我回收。 */
    private boolean plainLine;

    MarkdownStream(boolean color) {
        this(color, 80);
    }

    MarkdownStream(boolean color, int width) {
        this.width = width <= 0 ? 80 : width;
        this.style = new MarkdownStyle(color);
        this.tables = new MarkdownTableBuffer(this.width, color, new MarkdownTableBuffer.PlainEmitter() {
            @Override
            public void emit(String raw) {
                emitPlainLine(raw);
            }
        });
    }

    /** 是否在围栏内：围栏内不折行，交给终端自己折。 */
    boolean inFence() {
        return inFence;
    }

    /** 一轮结束时收尾：把还没配对的标记按字面吐出，并复位围栏（下一轮从干净状态开始）。 */
    String finish() {
        out.setLength(0);
        tables.drain(out);
        link.flushAsIs(style, out);
        closePendingMarker();
        style.flush(out);
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
        style.flush(out);
        return out.toString();
    }

    // ---------- 字符分派：按"此刻这个字符属于哪一类"分支，每类一个方法 ----------

    private void accept(char c) {
        if (c == '\n') {
            acceptNewline();
            return;
        }
        if (skippingFenceLine) {
            return;
        }
        if (inFence) {
            acceptInFence(c);
            return;
        }
        acceptVisible(c);
    }

    private void acceptNewline() {
        if (tables.collecting()) {
            tables.onLine(tables.takeLine(), out);
            return;
        }
        if (skippingFenceLine) {
            skippingFenceLine = false;
            lineStart = true;
            return;
        }
        closePendingMarker();
        link.flushAsIs(style, out);
        skipLineMarker();
        out.append('\n');
        resetLine();
    }

    /** 围栏内：原样保留，但要能识别收尾的那一行 ```。 */
    private void acceptInFence(char c) {
        if (lineStart && c == '`') {
            backtickRun++;
            if (backtickRun == FENCE) {
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
    }

    private void acceptVisible(char c) {
        if (skipQuoteSpace) {
            skipQuoteSpace = false;
            if (c == ' ') {
                return;
            }
        }
        if (tables.holdingLine()) {
            tables.append(c);
            return;
        }
        if (lineStart && !plainLine && c == '|') {
            tables.begin(out);
            return;
        }
        if (lineStart) {
            if (plainLine) {
                lineStart = false;
                acceptInline(c);
                return;
            }
            tables.closeBlockAtLineStart(out);   // 表格块到这一行为止，先吐出来
            lineStart = true;
            acceptLineStart(c);
            return;
        }
        acceptInline(c);
    }

    /** 行首：围栏 / 标题 / 列表 / 引用，都不是才回到普通字符。 */
    private void acceptLineStart(char c) {
        if (acceptFenceOpen(c) || acceptHeading(c) || acceptListMarker(c) || acceptQuote(c)) {
            return;
        }
        lineStart = false;
        accept(c);
    }

    private boolean acceptFenceOpen(char c) {
        if (c == '`') {
            backtickRun++;
            if (backtickRun == FENCE) {
                inFence = !inFence;
                skippingFenceLine = true;
                backtickRun = 0;
                lineStart = false;
            }
            return true;
        }
        if (backtickRun > 0) {
            int run = backtickRun;
            backtickRun = 0;
            if (run < FENCE) {
                emitBackticks(run);
            }
        }
        return false;
    }

    private boolean acceptHeading(char c) {
        if (c == '#') {
            hashRun++;
            return true;
        }
        if (hashRun == 0) {
            return false;
        }
        int run = hashRun;
        hashRun = 0;
        if (c == ' ') {
            style.setHeading(true);
            lineStart = false;
            style.ensureStyle(out);
            return true;
        }
        for (int i = 0; i < run; i++) {
            style.emit(out, '#');
        }
        return false;
    }

    private boolean acceptListMarker(char c) {
        if (markerCandidate != 0) {
            return completeListMarker(c);
        }
        if (c == '-' || c == '*' || c == '+') {
            markerCandidate = c;
            return true;
        }
        return false;
    }

    private boolean completeListMarker(char c) {
        char marker = markerCandidate;
        if (marker == '*' && c == '*') {
            markerCandidate = 0;
            lineStart = false;
            starRun = 2;
            return true;
        }
        markerCandidate = 0;
        if (c == ' ') {
            style.emit(out, '•');
            style.emit(out, ' ');
            lineStart = false;
            return true;
        }
        if (marker == '*') {
            lineStart = false;
            starRun = 1;
            accept(c);
            return true;
        }
        style.emit(out, marker);
        lineStart = false;
        accept(c);
        return true;
    }

    private boolean acceptQuote(char c) {
        if (c != '>') {
            return false;
        }
        style.setQuote(true);
        style.emit(out, '│');
        style.emit(out, ' ');
        lineStart = false;
        skipQuoteSpace = true;
        return true;
    }

    /** 行内：链接、行内代码、强调，其余按当前样式直出。 */
    private void acceptInline(char c) {
        if (link.active()) {
            link.accept(c, style, out);
            return;
        }
        if (c == '[') {
            link.start();
            return;
        }
        if (c == '`') {
            backtickRun++;
            return;
        }
        closeBacktickRun();
        if (c == '*') {
            starRun++;
            return;
        }
        closeStarRun(c);
        style.emit(out, c);
    }

    private void closeBacktickRun() {
        if (backtickRun > 0) {
            style.toggleCode(backtickRun);
            backtickRun = 0;
        }
    }

    /** 星号不是成对出现（后面跟空格，或超过两个）就按字面吐出来。 */
    private void closeStarRun(char c) {
        if (starRun == 0) {
            return;
        }
        if (c == ' ' || starRun > 2) {
            for (int i = 0; i < starRun; i++) {
                style.emit(out, '*');
            }
        } else {
            style.toggleEmphasis(starRun);
        }
        starRun = 0;
    }

    // ---------- 成行的输出 ----------

    /** 非表格行：按行内规则渲染并换行（此时字符是攒着的，可以整行处理）。 */
    /**
     * 已经被判定为"不是表格"的一整行：按行内规则渲染自己，但<strong>不能再走块级判定</strong>——
     * 否则以 `|` 开头的普通文本会被表格缓冲重新收走，这行就消失了。
     */
    private void emitPlainLine(String raw) {
        plainLine = true;
        try {
            for (int i = 0; i < raw.length(); i++) {
                accept(raw.charAt(i));
            }
            out.append('\n');
        } finally {
            plainLine = false;
            resetLine();
        }
    }

    // ---------- 未配对的标记 ----------

    /** 行结束/收尾时把"看到了但没配对的标记"按字面吐出来。 */
    private void closePendingMarker() {
        if (backtickRun > 0) {
            emitBackticks(backtickRun);
            backtickRun = 0;
            style.setCode(false);
        }
        if (starRun > 0) {
            if (!style.isBold() && !style.isItalic()) {
                for (int i = 0; i < starRun; i++) {
                    style.emit(out, '*');
                }
            }
            // 已按样式渲染到行尾的：直接收尾，别吐出多余的星号
            starRun = 0;
        }
        style.clearEmphasis();
        if (hashRun > 0) {
            for (int i = 0; i < hashRun; i++) {
                style.emit(out, '#');
            }
            hashRun = 0;
        }
    }

    private void skipLineMarker() {
        if (markerCandidate != 0) {
            char marker = markerCandidate;
            markerCandidate = 0;
            style.emit(out, marker);
        }
    }

    private void emitBackticks(int count) {
        for (int i = 0; i < count; i++) {
            out.append('`');
        }
    }

    private void resetLine() {
        lineStart = true;
        skippingFenceLine = false;
        style.resetLine();
    }
}
