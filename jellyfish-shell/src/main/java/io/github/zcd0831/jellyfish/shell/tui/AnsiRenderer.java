package io.github.zcd0831.jellyfish.shell.tui;

import java.io.PrintStream;

/**
 * 单栏流式渲染：流式打字机、就地暂停、状态行覆盖。
 * 只写 ANSI，不接管屏幕（D20）：不清屏、不 alternate screen，历史留在终端回滚区。
 */
public final class AnsiRenderer {

    static final String RESET = "\u001B[0m";
    static final String DIM = "\u001B[2m";
    static final String CYAN = "\u001B[36m";
    static final String YELLOW = "\u001B[33m";
    static final String RED = "\u001B[31m";
    private static final String ERASE_LINE = "\r\u001B[2K";

    private final PrintStream out;
    private final int width;
    private int column;
    private final boolean color;
    private final MarkdownStream markdown;
    /** 状态行是否占着当前行：占着就不能在它上面打正文（D20-10）。 */
    private boolean statusActive;

    public AnsiRenderer(PrintStream out, int width) {
        this(out, width, true);
    }

    public AnsiRenderer(PrintStream out, int width, boolean color) {
        this.out = out;
        this.width = width <= 0 ? 80 : width;
        this.color = color;
        this.markdown = new MarkdownStream(color);
    }

    /** 流式增量：先过 Markdown 行内解析，再按可见宽度折行（围栏内不折，交给终端）。 */
    public synchronized void append(String delta) {
        String styled = markdown.feed(delta);
        if (styled.isEmpty()) {
            return;
        }
        clearStatusLine();
        emit(styled, !markdown.inFence());
        out.flush();
    }

    /** 逐个字符输出：ANSI 序列不计宽度，围栏内不主动换行（终端自己折）。 */
    private void emit(String text, boolean wrap) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u001B') {
                int end = text.indexOf('m', i);
                if (end < 0) {
                    return;
                }
                out.print(text.substring(i, end + 1));
                i = end;
                continue;
            }
            if (c == '\n') {
                out.println();
                column = 0;
                continue;
            }
            int w = charWidth(c);
            if (wrap && column + w > width) {
                out.println();
                column = 0;
            }
            out.print(c);
            column += w;
        }
    }

    /** 一轮结束：确保光标在行首，问答与提示不会粘在一起。 */
    public synchronized void endTurn() {
        String tail = markdown.finish();
        if (!tail.isEmpty()) {
            emit(tail, true);
        }
        clearStatusLine();
        if (column > 0) {
            out.println();
            column = 0;
        }
    }

    public synchronized void assistantPrefix() {
        clearStatusLine();
        out.print(paint(CYAN) + "助手 " + paint(RESET));
        column = 3;
    }

    /** 工具开始：`  ↳ read_file（path=…）`，常驻一行，不覆盖正文。 */
    public synchronized void tool(String name, String detail) {
        endTurn();
        out.println(paint(DIM) + "  ↳ " + name + (detail.isEmpty() ? "" : "：" + detail) + paint(RESET));
        column = 0;
        out.flush();
    }

    /** 工具结束：`  ✓ read_file 完成 12ms：…`。 */
    public synchronized void toolDone(String text) {
        endTurn();
        out.println(paint(DIM) + "  ✓ " + text + paint(RESET));
        column = 0;
        out.flush();
    }

    /** 提问就地暂停：先断开当前行，再打印来源与问题。 */
    public synchronized void question(String sourceLabel, String prompt) {
        endTurn();
        out.println(paint(YELLOW) + "[" + sourceLabel + "] " + prompt + paint(RESET));
        column = 0;
        out.flush();
    }

    /**
     * 状态行：`\r` 原地覆盖；光标在正文行中间时先另起一行，避免擦掉正文（D20-10）。
     * 传空串即清除。
     */
    public synchronized void status(String text) {
        if (text == null || text.isEmpty()) {
            clearStatusLine();
            return;
        }
        if (!statusActive && column > 0) {
            out.println();
        }
        out.print(ERASE_LINE + paint(DIM) + text + paint(RESET));
        out.flush();
        column = 0;
        statusActive = true;
    }

    /** 关掉颜色后，所有 ANSI 前缀都退化成空串。 */
    private String paint(String code) {
        return color ? code : "";
    }

    /** 只在状态行确实占着当前行时才擦，绝不误伤正文。 */
    private void clearStatusLine() {
        if (statusActive) {
            out.print(ERASE_LINE);
            out.flush();
            statusActive = false;
            column = 0;
        }
    }

    public synchronized void error(String text) {
        endTurn();
        out.println(paint(RED) + "✗ 错误：" + text + paint(RESET));
        column = 0;
    }

    /** 分隔线（回合小结等）：不缩进，和正文分开。 */
    public synchronized void divider(String text) {
        endTurn();
        out.println(paint(DIM) + text + paint(RESET));
        column = 0;
    }

    public synchronized void info(String text) {
        endTurn();
        out.println(paint(DIM) + "  " + text + paint(RESET));
        column = 0;
    }

    /** 控制台宽度（boot 传入），交给渲染与补全共用。 */
    public int width() {
        return width;
    }

    /** CJK / 全角算两列；其余算一列（终端里这是最常见的近似）。 */
    static int charWidth(char c) {
        if (c < 0x1100) {
            return 1;
        }
        boolean wide = (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
        return wide ? 2 : 1;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }
}
