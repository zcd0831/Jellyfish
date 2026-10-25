package io.github.zcd0831.jellyfish.shell.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段 Markdown 表格渲染成对齐的文本块（含单元格换行）。
 * <p>
 * 两条退路：任何一行宽度分不过来、或列数不一致 → 原样输出，不硬撑。
 * 列宽按**可见宽度**算（CJK 算 2、忽略 ANSI），否则中文列会歪。
 */
final class MarkdownTable {

    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";
    private static final String GAP = "  ";
    /** 再窄就没法读了：宁可整表原样输出，也不挤成一列一列的单字。 */
    private static final int MIN_COLUMN = 6;

    private MarkdownTable() {
    }

    /** @param lines 含表头与分隔行的原始 Markdown 行 */
    static String render(List<String> lines, int terminalWidth, boolean color) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> aligns = null;
        for (int i = 0; i < lines.size(); i++) {
            List<String> cells = split(lines.get(i));
            if (cells == null) {
                return raw(lines);
            }
            if (i == 1) {
                aligns = alignments(cells);
                if (aligns == null) {
                    return raw(lines);
                }
                continue;
            }
            rows.add(cells);
        }
        if (aligns == null || rows.isEmpty()) {
            return raw(lines);
        }
        int columns = aligns.size();
        for (List<String> row : rows) {
            if (row.size() != columns) {
                return raw(lines);
            }
        }

        // 单元格先按行内 Markdown 渲染，再按可见宽度测宽
        List<List<String>> styled = new ArrayList<List<String>>();
        InlineFormatter inline = new InlineFormatter(color);
        List<int[]> widths = new ArrayList<int[]>();
        for (List<String> row : rows) {
            List<String> out = new ArrayList<String>();
            int[] width = new int[columns];
            for (int c = 0; c < columns; c++) {
                String cell = inline.render(row.get(c));
                out.add(cell);
                width[c] = visibleWidth(cell);
            }
            styled.add(out);
            widths.add(width);
        }

        int[] columnWidth = allocate(widths, columns, terminalWidth);
        if (columnWidth == null) {
            return raw(lines);
        }

        StringBuilder text = new StringBuilder();
        for (int r = 0; r < styled.size(); r++) {
            List<List<String>> cells = new ArrayList<List<String>>();
            int height = 1;
            for (int c = 0; c < columns; c++) {
                List<String> cellLines = wrap(styled.get(r).get(c), columnWidth[c]);
                cells.add(cellLines);
                height = Math.max(height, cellLines.size());
            }
            for (int line = 0; line < height; line++) {
                StringBuilder rowText = new StringBuilder();
                for (int c = 0; c < columns; c++) {
                    if (c > 0) {
                        rowText.append(GAP);
                    }
                    String piece = line < cells.get(c).size() ? cells.get(c).get(line) : "";
                    rowText.append(pad(piece, columnWidth[c], aligns.get(c), r == 0, color));
                }
                text.append(trimRight(rowText.toString())).append('\n');
                if (r == 0 && line == height - 1) {
                    text.append(separator(columnWidth, color)).append('\n');
                }
            }
        }
        return text.toString();
    }

    /** 列宽：先取自然宽度，超了就按比例压，压不下去就退化。 */
    private static int[] allocate(List<int[]> widths, int columns, int terminalWidth) {
        int[] natural = new int[columns];
        for (int[] width : widths) {
            for (int c = 0; c < columns; c++) {
                natural[c] = Math.max(natural[c], width[c]);
            }
        }
        int available = Math.max(20, terminalWidth) - GAP.length() * (columns - 1);
        int total = 0;
        for (int width : natural) {
            total += width;
        }
        if (total <= available) {
            return natural;
        }
        int min = MIN_COLUMN;
        int[] shrunk = natural.clone();
        int excess = total - available;
        while (excess > 0) {
            int widest = widestIndex(shrunk, min);
            if (widest < 0) {
                return null;
            }
            shrunk[widest]--;
            excess--;
        }
        return shrunk;
    }

    private static int widestIndex(int[] widths, int floor) {
        int index = -1;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > floor && (index < 0 || widths[i] > widths[index])) {
                index = i;
            }
        }
        return index;
    }

    private static String separator(int[] widths, boolean color) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                text.append(GAP);
            }
            for (int w = 0; w < widths[i]; w++) {
                text.append('─');
            }
        }
        return color ? DIM + text + RESET : text.toString();
    }

    private static String pad(String cell, int width, String align, boolean header, boolean color) {
        String text = header && color ? BOLD + cell + RESET : cell;
        int gap = width - visibleWidth(cell);
        if (gap <= 0) {
            return text;
        }
        if ("r".equals(align)) {
            return spaces(gap) + text;
        }
        if ("c".equals(align)) {
            int left = gap / 2;
            return spaces(left) + text + spaces(gap - left);
        }
        return text + spaces(gap);
    }

    /** 按可见宽度折行（不拆 ANSI，尽量不拆词）。 */
    static List<String> wrap(String styled, int width) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        int used = 0;
        for (int i = 0; i < styled.length(); i++) {
            char c = styled.charAt(i);
            if (c == '\u001B') {
                int end = styled.indexOf('m', i);
                if (end < 0) {
                    break;
                }
                line.append(styled, i, end + 1);
                i = end;
                continue;
            }
            int w = AnsiRenderer.charWidth(c);
            if (used + w > width && used > 0) {
                lines.add(line.toString());
                line.setLength(0);
                used = 0;
            }
            line.append(c);
            used += w;
        }
        lines.add(line.toString());
        return lines;
    }

    static int visibleWidth(String text) {
        String plain = InlineFormatter.stripAnsi(text);
        int width = 0;
        for (int i = 0; i < plain.length(); i++) {
            width += AnsiRenderer.charWidth(plain.charAt(i));
        }
        return width;
    }

    /** 拆单元格：去掉首尾竖线，`\|` 还原成 `|`。 */
    static List<String> split(String line) {
        String text = line.trim();
        if (!text.startsWith("|")) {
            return null;
        }
        if (text.endsWith("|") && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    /** 分隔行 → 对齐方式；不是分隔行返回 null。 */
    static List<String> alignments(List<String> cells) {
        List<String> aligns = new ArrayList<String>();
        for (String cell : cells) {
            String text = cell.trim();
            if (!text.matches(":?-{3,}:?")) {
                return null;
            }
            String align = "l";
            if (text.startsWith(":") && text.endsWith(":")) {
                align = "c";
            } else if (text.endsWith(":")) {
                align = "r";
            }
            aligns.add(align);
        }
        return aligns;
    }

    static boolean isSeparator(String line) {
        List<String> cells = split(line);
        return cells != null && alignments(cells) != null;
    }

    private static String spaces(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(' ');
        }
        return text.toString();
    }

    private static String trimRight(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String raw(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }
}
