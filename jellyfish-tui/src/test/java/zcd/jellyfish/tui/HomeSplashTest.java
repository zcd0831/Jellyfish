package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HomeSplash} 的单元测试。
 * <p>
 * 首页字标最容易出错的三点都在这里锁住：一是<b>居中</b>（靠显示宽度算前导空格，用字符数算会在含中文的
 * 终端里偏），二是<b>倒退</b>（宽度不足时整块换成单行文本，而不是把图案裁成乱码），
 * 三是<b>字形</b>（首字母是 {@code J} 而不是 {@code H}——两者只差左上角两格，肉眼一瞟很难发现）。
 *
 * @author zcd
 */
@DisplayName("首页字标")
class HomeSplashTest {

    /** 实心块字符，与 {@link HomeSplash} 画出来的同一个码点。 */
    private static final char BLOCK = '\u2588';

    /** 单个字形的列宽，与 {@link HomeSplash} 的 {@code GLYPH_WIDTH} 同口径。 */
    private static final int GLYPH_WIDTH = 5;

    @Test
    @DisplayName("宽度足够时画方块字图案，各行等宽")
    void lines_should_renderBlockArt_when_widthEnough() {
        List<VisualLine> lines = HomeSplash.lines(80);

        assertEquals(5, lines.size(), "图案是 5 行");
        int padding = leadingSpaces(lines.get(0).text());
        for (VisualLine line : lines) {
            assertEquals(padding + HomeSplash.BLOCK_WIDTH, line.width(),
                    "每行都应当是「同样的前导空格 + 等宽图案」，实际：" + line.text());
        }
    }

    @Test
    @DisplayName("字标按显示宽度居中")
    void lines_should_centerLogo() {
        int width = 80;
        List<VisualLine> lines = HomeSplash.lines(width);

        int leading = leadingSpaces(lines.get(0).text());
        // 行尾不补空格，因此右侧留白要按可用宽度算，而不是按本行实际宽度
        int trailing = width - leading - HomeSplash.BLOCK_WIDTH;
        assertTrue(Math.abs(leading - trailing) <= 1, "必须居中，实际前导 " + leading + " 尾部 " + trailing);
    }

    @Test
    @DisplayName("首字母是 J 而不是 H：底行左收、第四行左侧带竖笔")
    void lines_should_renderFirstLetterAsJ() {
        String[] expected = {
                glyph("#####"),
                glyph("    #"),
                glyph("    #"),
                glyph("#   #"),
                glyph(" ### ")};

        assertArrayEquals(expected, firstGlyph(80), "首字母必须是 J，不能是 H");
    }

    @Test
    @DisplayName("宽度不足 53 列时整块退回单行文本，而不是裁切图案")
    void lines_should_fallBackToSingleLine_when_tooNarrow() {
        List<VisualLine> lines = HomeSplash.lines(40);

        assertEquals(1, lines.size(), "退回形态只有一行");
        assertEquals(HomeSplash.LOGO, lines.get(0).text().trim());
        assertTrue(lines.get(0).text().indexOf(BLOCK) < 0, "窄终端上不应出现图案残片");
    }

    @Test
    @DisplayName("极窄时退回的那行按显示宽度裁切，且不超过可用列数")
    void lines_should_clipFallbackText_when_extremelyNarrow() {
        List<VisualLine> lines = HomeSplash.lines(4);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).width() <= 4, "不得超过可用列数");
    }

    @Test
    @DisplayName("宽度为 0 或负数时按 1 列处理，不抛异常")
    void lines_should_tolerateNonPositiveWidth() {
        assertEquals(1, HomeSplash.lines(0).size());
        assertEquals(1, HomeSplash.lines(-5).size());
    }

    /**
     * 把字形模板里的 {@code #} 换成实心块字符。
     *
     * @param row 字形模板的一行
     * @return 替换后的文本
     */
    private static String glyph(String row) {
        return row.replace('#', BLOCK);
    }

    /**
     * 取出图案里第一个字母的字形。
     *
     * @param width 可用列数
     * @return 自上而下的 5 行字形
     */
    private static String[] firstGlyph(int width) {
        List<VisualLine> lines = HomeSplash.lines(width);
        int padding = leadingSpaces(lines.get(0).text());
        String[] glyph = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            glyph[i] = lines.get(i).text().substring(padding, padding + GLYPH_WIDTH);
        }
        return glyph;
    }

    /**
     * 数一行开头的空格数。
     *
     * @param text 行文本
     * @return 前导空格数
     */
    private static int leadingSpaces(String text) {
        int index = 0;
        while (index < text.length() && text.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    /**
     * 断言字标各行等宽时使用的显示宽度口径。
     * <p>
     * 单独放一个测试方法会与上面的等宽断言重复，因此这里只做一次显式声明：
     * 图案宽度是 53 列（9 个字母 × 5 列 + 8 个间隔）。
     */
    @Test
    @DisplayName("图案宽度固定为 53 列")
    void blockWidth_should_be_53() {
        assertEquals(53, HomeSplash.BLOCK_WIDTH);
        assertEquals(53, DisplayWidth.of(HomeSplash.lines(60).get(0).text().trim()));
    }
}
