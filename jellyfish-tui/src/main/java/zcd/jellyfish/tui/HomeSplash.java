package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页标识：没有当前会话时，消息区居中显示的 {@code Jellyfish} 方块字字标。
 * <p>
 * <b>为什么用方块字图案</b>：首页内容不足一屏，一行文本之外全是空白，显得单薄。方块字符
 * （U+2588 等）不属于 East Asian Wide / Fullwidth 区间，{@link DisplayWidth} 按 1 列计，
 * 因此居中与裁切的口径与单行文本完全一致；图案本身也没有中英混排的宽度问题——
 * 当初拒绝 ASCII 图案的两条理由，对「纯半角块字符」只剩「按终端宽度自适应」这一条。
 * <p>
 * <b>为什么宽度不足时整块退回单行文本，而不是裁切</b>：图案裁掉一半就是乱码，比没有字标更难看，
 * 所以按列数整块替换：够宽就画图案，不够就退回一行 {@link #LOGO}。退回的那行仍按显示宽度裁切——
 * 一行东西在任何宽度下都读得通。
 * <p>
 * <b>为什么本类不做垂直居中</b>：首页上还有外壳提示（{@link ShellNotice}）要一起排，
 * 垂直留白必须按「字标 + 提示」的合计行数算，那是 {@link TranscriptProjector#home} 的职责。
 * 本类只吐字标自己的行，不吐空白行。
 * <p>
 * 纯函数，不读终端、不改状态，可单测。
 *
 * @author zcd
 */
public final class HomeSplash {

    /** 退回单行文本时使用的字标文本。 */
    static final String LOGO = "Jellyfish";

    /** 实心块字符：U+2588 FULL BLOCK，显示宽度 1 列。 */
    private static final char FILLED = '\u2588';

    /** 字形里的实心占位符。 */
    private static final char INK = '#';

    /** 单个字母的列宽。 */
    private static final int GLYPH_WIDTH = 5;

    /** 字母之间的间隔列数。 */
    private static final int GLYPH_GAP = 1;

    /** 每个字母的字形：{@link #GLYPH_WIDTH} 列 × 5 行，{@link #INK} 为实心。 */
    private static final String[][] GLYPHS = {
            // J：底行左收，第四行左侧带竖笔——不要写成 H
            {"#####",
             "    #",
             "    #",
             "#   #",
             " ### "},
            // E
            {"#####",
             "#    ",
             "#### ",
             "#    ",
             "#####"},
            // L
            {"#    ",
             "#    ",
             "#    ",
             "#    ",
             "#####"},
            // L
            {"#    ",
             "#    ",
             "#    ",
             "#    ",
             "#####"},
            // Y
            {"#   #",
             "#   #",
             " # # ",
             "  #  ",
             "  #  "},
            // F
            {"#####",
             "#    ",
             "#### ",
             "#    ",
             "#    "},
            // I
            {"#####",
             "  #  ",
             "  #  ",
             "  #  ",
             "#####"},
            // S
            {" ####",
             "#    ",
             " ### ",
             "    #",
             "#### "},
            // H
            {"#   #",
             "#   #",
             "#####",
             "#   #",
             "#   #"}};

    /** 图案每行的显示宽度：{@code 9 个字母 × 5 列 + 8 个间隔}，恰为 53 列。 */
    static final int BLOCK_WIDTH = GLYPHS.length * GLYPH_WIDTH + (GLYPHS.length - 1) * GLYPH_GAP;

    /** 图案的 5 行文本，类加载时由字形拼出。 */
    private static final String[] BLOCK_ROWS = buildRows();

    /** 图案样式：加粗，突出「这是首页」而不使用颜色（颜色档位归语义强调，这里没有语义）。 */
    private static final Style LOGO_STYLE = Style.EMPTY.bold();

    private HomeSplash() {
    }

    /**
     * 生成首页字标行。
     * <p>
     * 可用列数够就返回整块图案，不够就返回一行文本：两种结果的<b>行数不同</b>，
     * 但都是「字标自身的内容」，不含任何垂直留白（留白见类注释）。
     *
     * @param width 消息区可用列数，小于 1 时按 1 处理
     * @return 视觉行列表，保证非 {@code null} 且非空
     */
    public static List<VisualLine> lines(int width) {
        int available = Math.max(1, width);
        return available < BLOCK_WIDTH ? singleLine(available) : block(available);
    }

    /**
     * 拼出图案的每一行文本。
     *
     * @return 行文本数组，保证每行等宽（{@link #BLOCK_WIDTH} 列）
     */
    private static String[] buildRows() {
        int rows = GLYPHS[0].length;
        String[] result = new String[rows];
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder(BLOCK_WIDTH);
            for (int glyph = 0; glyph < GLYPHS.length; glyph++) {
                if (glyph > 0) {
                    appendSpaces(line, GLYPH_GAP);
                }
                line.append(GLYPHS[glyph][row].replace(INK, FILLED));
            }
            result[row] = line.toString();
        }
        return result;
    }

    /**
     * 生成整块图案。
     * <p>
     * 逐行加同样的前导空格：各行等宽，因此一个 padding 就能让整块居中，不必逐行重算。
     *
     * @param available 可用列数，保证不小于 {@link #BLOCK_WIDTH}
     * @return 视觉行列表
     */
    private static List<VisualLine> block(int available) {
        int padding = (available - BLOCK_WIDTH) / 2;
        String lead = spaces(padding);
        List<VisualLine> lines = new ArrayList<VisualLine>(BLOCK_ROWS.length);
        for (String row : BLOCK_ROWS) {
            lines.add(VisualLine.of(new StyledSegment(lead + row, LOGO_STYLE)));
        }
        return lines;
    }

    /**
     * 生成单行文本字标（宽度不足时的退回形态）。
     *
     * @param available 可用列数，保证小于 {@link #BLOCK_WIDTH}
     * @return 只含一个视觉行的列表
     */
    private static List<VisualLine> singleLine(int available) {
        String text = truncate(LOGO, available);
        int padding = Math.max(0, (available - DisplayWidth.of(text)) / 2);
        List<VisualLine> lines = new ArrayList<VisualLine>(1);
        lines.add(VisualLine.of(new StyledSegment(spaces(padding) + text, LOGO_STYLE)));
        return lines;
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
     * @param count 数量
     * @return 空格串
     */
    private static String spaces(int count) {
        StringBuilder padding = new StringBuilder(Math.max(0, count));
        appendSpaces(padding, count);
        return padding.toString();
    }

    /**
     * 向缓冲追加指定数量的空格。
     *
     * @param target 目标缓冲
     * @param count  数量，小于 1 时不追加
     */
    private static void appendSpaces(StringBuilder target, int count) {
        for (int i = 0; i < count; i++) {
            target.append(' ');
        }
    }
}
