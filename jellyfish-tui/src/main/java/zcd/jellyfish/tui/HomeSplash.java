package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页标识：没有当前会话时，消息区顶部居中显示的 {@code Jellyfish} 字标。
 * <p>
 * <b>为什么是纯文本字标而不是 ASCII 图案</b>：图案要按终端宽度自适应裁切、还要处理中英混排的
 * 显示宽度，收益只是好看一点；而一行加粗字标在任何宽度下都读得通，也让首页的投影保持简单。
 * <p>
 * <b>为什么要按显示宽度居中、超宽时裁切而不是折行</b>：居中靠前导空格，一旦折行，第二行会顶到
 * 最左边，看起来像两行不同的东西；裁掉超出终端边界的那部分至少保证「一行一个标识」。
 * 这里用 {@link DisplayWidth} 而不是 {@code length()}，与终端列数保持同一口径。
 * <p>
 * 纯函数，不读终端、不改状态，可单测。
 *
 * @author zcd
 */
public final class HomeSplash {

    /** 字标文本。 */
    static final String LOGO = "Jellyfish";

    /** 字标样式：加粗，突出「这是首页」而不使用颜色（颜色档位归语义强调，这里没有语义）。 */
    private static final Style LOGO_STYLE = Style.EMPTY.bold();

    private HomeSplash() {
    }

    /**
     * 生成首页字标行。
     *
     * @param width 消息区可用列数，小于 1 时按 1 处理
     * @return 视觉行列表（首个为空行，用于与顶部边框留出间距），保证非 {@code null}
     */
    public static List<VisualLine> lines(int width) {
        int available = Math.max(1, width);
        String text = truncate(LOGO, available);
        int padding = Math.max(0, (available - DisplayWidth.of(text)) / 2);
        List<VisualLine> lines = new ArrayList<VisualLine>(2);
        lines.add(VisualLine.EMPTY);
        lines.add(VisualLine.of(new StyledSegment(spaces(padding) + text, LOGO_STYLE)));
        return lines;
    }

    /**
     * 按显示宽度截断文本。
     *
     * @param text      原始文本
     * @param maxWidth  可用列数
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
     * @param count 数量
     * @return 空格串
     */
    private static String spaces(int count) {
        StringBuilder padding = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            padding.append(' ');
        }
        return padding.toString();
    }
}
