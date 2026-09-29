package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;

/**
 * 居中文本行：把一行文本按显示宽度居中放进给定的列数里。
 * <p>
 * <b>为什么抽出来</b>：首页上要居中的不止一处（字标、提示行），而「居中」这件事一旦各写一份，
 * 两处迟早差一列——那种不齐是最难看出根因的观感问题。因此居中的算法只留在这里，
 * 调用方只声明「文本 + 样式 + 可用列数」。
 * <p>
 * <b>超宽时按显示宽度裁切而不是折行</b>：折行的第二行会顶到最左边，看起来像两行不同的东西；
 * 裁掉超出终端边界的那部分至少保证「一行一个东西」。用 {@link DisplayWidth} 而不是
 * {@code length()}，与终端列数保持同一口径。
 * <p>
 * 纯函数，不读终端、不改状态，可单测。
 *
 * @author zcd
 */
public final class CenteredLine {

    private CenteredLine() {
    }

    /**
     * 生成一行居中文本。
     *
     * @param text             文本内容，不可为 {@code null}
     * @param style            文本样式，不可为 {@code null}；无样式请传 {@link Style#EMPTY}
     * @param availableColumns 可用列数，小于 1 时按 1 处理
     * @return 居中后的视觉行，保证非 {@code null}
     */
    public static VisualLine of(String text, Style style, int availableColumns) {
        int columns = Math.max(1, availableColumns);
        String clipped = truncate(text, columns);
        int padding = Math.max(0, (columns - DisplayWidth.of(clipped)) / 2);
        return VisualLine.of(new StyledSegment(spaces(padding) + clipped, style));
    }

    /**
     * 按显示宽度截断文本。
     *
     * @param text     原始文本
     * @param maxWidth 可用列数
     * @return 不超过 {@code maxWidth} 列的前缀
     */
    private static String truncate(String text, int maxWidth) {
        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int charWidth = DisplayWidth.of(String.valueOf(text.charAt(end)));
            if (used + charWidth > maxWidth) {
                break;
            }
            used += charWidth;
            end++;
        }
        return text.substring(0, end);
    }

    /**
     * 生成指定数量的空格。
     *
     * @param count 数量，小于 1 时返回空串
     * @return 空格串
     */
    private static String spaces(int count) {
        StringBuilder padding = new StringBuilder(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            padding.append(' ');
        }
        return padding.toString();
    }
}
