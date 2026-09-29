package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HomeHints} 的单元测试。
 * <p>
 * 这里锁住两件事：一是<b>dim 且居中</b>（提示要明显弱于字标，又不能歪在一边），
 * 二是<b>放不下就整行丢弃</b>（不能吐出半截提示，那比没有更让人困惑）。
 *
 * @author zcd
 */
@DisplayName("首页引导提示")
class HomeHintsTest {

    @Test
    @DisplayName("宽度足够时输出空行 + 各行提示")
    void lines_should_renderBlankThenHints() {
        List<VisualLine> lines = HomeHints.lines(80);

        assertEquals(1 + HomeHints.HINTS.length, lines.size());
        assertTrue(lines.get(0).isEmpty(), "首行必须是空行，与字标隔开");
        for (int i = 0; i < HomeHints.HINTS.length; i++) {
            assertEquals(HomeHints.HINTS[i], lines.get(i + 1).text().trim());
        }
    }

    @Test
    @DisplayName("提示按显示宽度居中")
    void lines_should_centerHints() {
        int width = 80;
        List<VisualLine> lines = HomeHints.lines(width);

        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).text();
            int leading = 0;
            while (leading < text.length() && text.charAt(leading) == ' ') {
                leading++;
            }
            // 行尾不补空格，因此右侧留白要按可用宽度算，而不是按本行实际宽度
            int trailing = width - leading - DisplayWidth.of(HomeHints.HINTS[i - 1]);
            assertTrue(Math.abs(leading - trailing) <= 1,
                    "必须居中，实际前导 " + leading + " 尾部 " + trailing);
        }
    }

    @Test
    @DisplayName("提示是 dim 样式，明显弱于字标")
    void lines_should_useDimStyle() {
        List<VisualLine> lines = HomeHints.lines(80);

        for (int i = 1; i < lines.size(); i++) {
            assertEquals(Style.EMPTY.dim(), lines.get(i).getSegments().get(0).getStyle(),
                    "提示必须是 dim，实际：" + lines.get(i).text());
        }
    }

    @Test
    @DisplayName("只丢弃放不下的那一行，其余照常显示")
    void lines_should_dropOnlyUnfittingHint() {
        // 第一行 37 列、第二行 36 列：36 列宽时只剩第二行
        int width = DisplayWidth.of(HomeHints.HINTS[1]);

        List<VisualLine> lines = HomeHints.lines(width);

        assertEquals(1 + 1, lines.size(), "空行 + 放得下的那一行，实际：" + lines);
        assertTrue(lines.get(0).isEmpty());
        assertEquals(HomeHints.HINTS[1], lines.get(1).text().trim());
    }

    @Test
    @DisplayName("一行都放不下时返回空列表，连空行也不吐")
    void lines_should_returnEmpty_when_noHintFits() {
        List<VisualLine> lines = HomeHints.lines(10);

        assertTrue(lines.isEmpty(), "容不下任何提示时不该留下空白行，实际：" + lines);
    }

    @Test
    @DisplayName("宽度为 0 或负数时不抛异常")
    void lines_should_tolerateNonPositiveWidth() {
        assertTrue(HomeHints.lines(0).isEmpty());
        assertTrue(HomeHints.lines(-5).isEmpty());
    }
}
