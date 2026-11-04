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

    /** 新行到来时要先处理表格（攒行中或已在表里）。 */
    boolean collecting() {
        return holdingLine || inTable();
    }

    private boolean inTable() {
        return !tableLines.isEmpty();
    }

    /** 行首遇到 `|`：开始攒这一行。 */
    void begin(StringBuilder out) {
        holdingLine = true;
        rawLine.setLength(0);
        out.append('|');
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
        renderInto(out);
        if (holdingLine) {
            plain.emit(takeLine());
        }
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
