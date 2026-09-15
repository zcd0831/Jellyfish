package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HomeSplash} 的单元测试。
 * <p>
 * 首页字标最容易出错的两点都在这里锁住：一是<b>居中</b>（靠显示宽度算前导空格，用字符数算会在含中文的
 * 终端里偏），二是<b>不折行</b>（宽度不足时裁切，而不是让第二行顶到最左边）。
 *
 * @author zcd
 */
@DisplayName("首页字标")
class HomeSplashTest {

    @Test
    @DisplayName("输出两行：一个空行 + 字标")
    void lines_should_renderBlankThenLogo() {
        List<VisualLine> lines = HomeSplash.lines(40);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).isEmpty(), "首行必须是空行");
        assertEquals(HomeSplash.LOGO, lines.get(1).text().trim());
    }

    @Test
    @DisplayName("字标按显示宽度居中")
    void lines_should_centerLogo() {
        int width = 40;
        String logo = HomeSplash.lines(width).get(1).text();

        int leading = 0;
        while (leading < logo.length() && logo.charAt(leading) == ' ') {
            leading++;
        }
        // 行尾不补空格，因此右侧留白要按可用宽度算，而不是按本行实际宽度
        int trailing = width - leading - DisplayWidth.of(HomeSplash.LOGO);
        assertTrue(Math.abs(leading - trailing) <= 1, "必须居中，实际前导 " + leading + " 尾部 " + trailing);
    }

    @Test
    @DisplayName("宽度不足时裁切而不是折行，且不超过可用列数")
    void lines_should_clipWhenNarrow() {
        List<VisualLine> lines = HomeSplash.lines(4);

        assertEquals(2, lines.size(), "始终只有空行 + 一行字标");
        assertTrue(lines.get(1).width() <= 4, "不得超过可用列数");
    }

    @Test
    @DisplayName("宽度为 0 或负数时按 1 列处理，不抛异常")
    void lines_should_tolerateNonPositiveWidth() {
        assertEquals(2, HomeSplash.lines(0).size());
        assertEquals(2, HomeSplash.lines(-5).size());
    }
}
