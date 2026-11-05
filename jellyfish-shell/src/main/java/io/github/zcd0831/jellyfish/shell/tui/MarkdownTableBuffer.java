package io.github.zcd0831.jellyfish.shell.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格是<b>块级</b>语法：攒够一块再画，画不了就原样输出。
 * <p>
 * 三种状态：行首 `|` 先攒着（{@code holdingLine}）→ 下一行是分隔行才确认成表 → 遇到非表行就整块画出来。
 * 判定全靠 {@link MarkdownTable}，这里只管"攒 / 判 / 出"。
 * <p>
 * 规则出处：08-application §8.7（不得丢 chunk、不得改序）。
 */
final class MarkdownTableBuffer {

    /** 非表格行怎么输出：交回字符分派按行内规则渲染（此处是攒好的整行）。 */
    interface PlainEmitter {
        void emit(String raw);
    }

    private final int width;
    private final boolean color;
    private final PlainEmitter plain;

    private final StringBuilder rawLine = new StringBuilder();
    private final List<String> tableLines = new ArrayList<String>();
    private boolean holdingLine;
    private String pendingHeader;

    MarkdownTableBuffer(int width, boolean color, PlainEmitter plain) {
        this.width = width;
        this.color = color;
        this.plain = plain;
    }

    /** 正在攒"行首 `|` 开头的那一行"。 */
    boolean holdingLine() {
        return holdingLine;
    }

    /** 当前这一行正被表格缓冲攒着（只有这种行才在换行时交给 {@link #onLine}）。 */
    boolean collecting() {
        return holdingLine;
    }

    /**
     * 行首遇到非 `|` 字符：说明表格块到此为止，必须<strong>先</strong>把已攒的吐出来，
     * 否则这一行的字先写进输出，表格反而被排到它后面。
     */
    void closeBlockAtLineStart(StringBuilder out) {
        if (pendingHeader != null) {
            String header = pendingHeader;
            pendingHeader = null;
            renderInto(out);
            plain.emit(header);
            return;
        }
        renderInto(out);
    }

    private boolean inTable() {
        return !tableLines.isEmpty();
    }

    /**
     * 行首遇到 `|`：开始攒这一行（连这个 `|` 一起攒，不能先写出去）。
     * 先写出去的话，这一行后来判定为表格时素材缺了首列竖线、判定为普通文本时又少一个字符。
     */
    void begin(StringBuilder out) {
        holdingLine = true;
        rawLine.setLength(0);
        rawLine.append('|');
    }

    void append(char c) {
        rawLine.append(c);
    }

    /** 取出攒着的一行并退出攒行状态。 */
    String takeLine() {
        String raw = rawLine.toString();
        rawLine.setLength(0);
        holdingLine = false;
        return raw;
    }

    /** 收到一整行：是表就接着攒，不是就把已攒的画出来、本行按普通行输出。 */
    void onLine(String raw, StringBuilder out) {
        if (inTable()) {
            if (MarkdownTable.split(raw) != null) {
                tableLines.add(raw);
                return;
            }
            renderInto(out);
            plain.emit(raw);
            return;
        }
        if (pendingHeader != null) {
            if (MarkdownTable.isSeparator(raw)) {
                tableLines.add(pendingHeader);
                tableLines.add(raw);
                pendingHeader = null;
                return;
            }
            String header = pendingHeader;
            pendingHeader = null;
            plain.emit(header);
            onLine(raw, out);
            return;
        }
        if (MarkdownTable.split(raw) != null) {
            pendingHeader = raw;
            return;
        }
        plain.emit(raw);
    }

    /** 收尾：攒着的行与半张表都要吐出来，不能丢（08-application §8.7 不得丢 chunk）。 */
    void drain(StringBuilder out) {
        if (holdingLine) {
            // 收尾时最后一行还没有换行：先按"一整行"处理，否则表格末行会漏到表外
            onLine(takeLine(), out);
        }
        renderInto(out);
        if (pendingHeader != null) {
            String header = pendingHeader;
            pendingHeader = null;
            plain.emit(header);
        }
    }

    private void renderInto(StringBuilder out) {
        if (tableLines.isEmpty()) {
            return;
        }
        out.append(MarkdownTable.render(tableLines, width, color));
        tableLines.clear();
    }
}
