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
    /** 状态行是否占着当前行：占着就不能在它上面打正文（D20-10）。 */
    private boolean statusActive;

    public AnsiRenderer(PrintStream out, int width) {
        this.out = out;
        this.width = width <= 0 ? 80 : width;
    }

    /** 流式增量：不回显的换行交给渲染器，保证 CJK 宽度与折行正确。 */
    public synchronized void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        clearStatusLine();
        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            if (c == '\n') {
                out.println();
                column = 0;
                continue;
            }
            int w = charWidth(c);
            if (column + w > width) {
                out.println();
                column = 0;
            }
            out.print(c);
            column += w;
        }
        out.flush();
    }

    /** 一轮结束：确保光标在行首，问答与提示不会粘在一起。 */
    public synchronized void endTurn() {
        clearStatusLine();
        if (column > 0) {
            out.println();
            column = 0;
        }
    }

    public synchronized void assistantPrefix() {
        clearStatusLine();
        out.print(CYAN + "助手 " + RESET);
        column = 3;
    }

    public synchronized void tool(String name, String outcome) {
        out.println(DIM + "  · " + name + "：" + firstLine(outcome) + RESET);
        column = 0;
    }

    /** 提问就地暂停：先断开当前行，再打印来源与问题。 */
    public synchronized void question(String sourceLabel, String prompt) {
        endTurn();
        out.println(YELLOW + "[" + sourceLabel + "] " + prompt + RESET);
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
        out.print(ERASE_LINE + DIM + text + RESET);
        out.flush();
        column = 0;
        statusActive = true;
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
        out.println(RED + "错误：" + text + RESET);
        column = 0;
    }

    public synchronized void info(String text) {
        endTurn();
        out.println(DIM + text + RESET);
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
