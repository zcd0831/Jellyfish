package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.tui.text.VisualLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellOutput} 的单元测试。
 * <p>
 * 守三条口径：只留最近一次、滚动不出界、标题把「按什么键能关掉」说清楚。
 * 这些都不需要终端——本类刻意不碰渲染引擎（切片结果只是一串 {@link VisualLine}）。
 *
 * @author zcd
 */
@DisplayName("命令输出面板")
class ShellOutputTest {

    /** 测试用宽度。 */
    private static final int WIDTH = 60;

    /**
     * 生成指定行数的输出文本。
     *
     * @param rows 行数
     * @return 文本
     */
    private static String textOf(int rows) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= rows; i++) {
            if (i > 1) {
                text.append('\n');
            }
            text.append("第 ").append(i).append(" 行");
        }
        return text.toString();
    }

    /**
     * 把面板内容拼成一段文本（每行一段，便于断言）。
     *
     * @param panel 面板
     * @return 拼接结果
     */
    private static String joined(DockPanel panel) {
        StringBuilder all = new StringBuilder();
        for (VisualLine line : panel.getLines()) {
            all.append(line.text()).append('\n');
        }
        return all.toString();
    }

    /**
     * 生成重复字符。
     *
     * @param ch    字符
     * @param count 次数
     * @return 字符串
     */
    private static String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("初始不可见：没有命令跑过就不该占屏幕")
    void isVisible_should_beFalse_initially() {
        ShellOutput output = new ShellOutput();

        assertFalse(output.isVisible());
        assertEquals(0, output.desiredPanelRows(WIDTH));
        assertTrue(output.render(WIDTH, 5).isEmpty());
    }

    @Test
    @DisplayName("show 之后可见，并按内容高度要行数")
    void show_should_makePanelVisible() {
        ShellOutput output = new ShellOutput();

        output.show("/help", textOf(3), ShellNotice.Kind.INFO);

        assertTrue(output.isVisible());
        assertTrue(output.render(WIDTH, 5).getTitle().contains("/help"), "标题常驻命令原文");
        assertEquals(3 + ChatShell.BORDER_SIZE * 2, output.desiredPanelRows(WIDTH));
    }

    @Test
    @DisplayName("只留最近一次：新命令替换旧命令，而不是追加")
    void show_should_replacePreviousOutput() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(3), ShellNotice.Kind.INFO);

        output.show("/status", "只有这一行", ShellNotice.Kind.INFO);

        assertEquals(1, output.render(WIDTH, 5).getLines().size(),
                "面板里只剩新命令的那一行");
        assertTrue(joined(output.render(WIDTH, 5)).contains("只有这一行"));
        assertTrue(output.render(WIDTH, 5).getTitle().contains("/status"));
    }

    @Test
    @DisplayName("空白输出等价于关闭：一条空面板只会白占行")
    void show_should_close_when_textIsBlank() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(3), ShellNotice.Kind.INFO);

        output.show("/session", "   \n  ", ShellNotice.Kind.INFO);
        assertFalse(output.isVisible());

        output.show("/session", null, ShellNotice.Kind.INFO);
        assertFalse(output.isVisible());
    }

    @Test
    @DisplayName("close 把内容一并丢掉：下次打开不该先看到上一次的输出")
    void close_should_discardContent() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(3), ShellNotice.Kind.INFO);

        output.close();

        assertFalse(output.isVisible());
        assertTrue(output.render(WIDTH, 5).isEmpty());
        assertNull(output.render(WIDTH, 5).getTitle(), "连标题都不留：否则旧命令名会再出现在屏幕上");
    }

    @Test
    @DisplayName("内容行数封顶：面板高度不能由输出长度决定")
    void desiredPanelRows_should_capContentRows() {
        ShellOutput output = new ShellOutput();

        output.show("/help", textOf(200), ShellNotice.Kind.INFO);

        assertEquals(ShellOutput.MAX_CONTENT_ROWS + ChatShell.BORDER_SIZE * 2,
                output.desiredPanelRows(WIDTH));
    }

    @Test
    @DisplayName("内容超过视野时可滚动，且不会滚出内容之外")
    void scrollBy_should_clampWithinContent() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        output.render(WIDTH, 5);

        output.scrollBy(-100);
        assertTrue(joined(output.render(WIDTH, 5)).contains("第 1 行"), "向上滚到头就停在第一行");

        output.scrollBy(100);
        assertTrue(joined(output.render(WIDTH, 5)).contains("第 30 行"), "向下滚到头就停在最后一行");
    }

    @Test
    @DisplayName("翻页幅度与上一次真正画出来的高度一致，并留一行重叠")
    void pageDown_should_stepByViewportRows() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        output.render(WIDTH, 5);

        output.pageDown();

        // 5 行视野、留一行重叠 → 前进 4 行，因此视野首行是第 5 行
        assertTrue(joined(output.render(WIDTH, 5)).contains("第 5 行"));
    }

    @Test
    @DisplayName("内容换成更短的之后旧偏移不越界：不需要每个入口各自维护")
    void render_should_clampOffset_when_contentShrinks() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        output.render(WIDTH, 5);
        output.scrollBy(100);

        output.show("/status", textOf(2), ShellNotice.Kind.INFO);

        assertEquals(2 + ChatShell.BORDER_SIZE * 2, output.desiredPanelRows(WIDTH));
        assertEquals(2, output.render(WIDTH, 5).getLines().size());
        assertTrue(joined(output.render(WIDTH, 5)).contains("第 1 行"), "回落到可见范围内");
    }

    @Test
    @DisplayName("内容换了之后回到顶部：命令输出要从头读")
    void show_should_resetScrollToTop() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        output.render(WIDTH, 5);
        output.scrollBy(10);

        output.show("/status", textOf(30), ShellNotice.Kind.INFO);

        assertTrue(joined(output.render(WIDTH, 5)).contains("第 1 行"));
    }

    @Test
    @DisplayName("标题常驻关闭键位；内容超出视野时带上滚动范围")
    void title_should_carryCloseHintAndRange() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(3), ShellNotice.Kind.INFO);
        assertTrue(output.render(WIDTH, 5).getTitle().contains("Esc 关闭"),
                "面板没有键位提示时，Esc 这个能力只能靠读文档才知道");

        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        String title = output.render(WIDTH, 5).getTitle();
        assertTrue(title.contains("1-5/30"), title);
    }

    @Test
    @DisplayName("标题里的命令原文超宽时截断：标题是索引，不该挤掉内容")
    void title_should_abbreviateLongCommand() {
        ShellOutput output = new ShellOutput();

        output.show("/resume " + repeat('x', 40), textOf(1), ShellNotice.Kind.INFO);

        String title = output.render(WIDTH, 5).getTitle();
        assertTrue(title.contains("\u2026"), title);
        assertTrue(title.endsWith("Esc 关闭 "), title);
    }

    @Test
    @DisplayName("滚没滚到头要如实报出来：标题里的范围提示靠它")
    void hasAbove_should_reportScrollPosition() {
        ShellOutput output = new ShellOutput();
        output.show("/help", textOf(30), ShellNotice.Kind.INFO);
        output.render(WIDTH, 5);

        assertFalse(output.hasAbove());
        assertTrue(output.hasBelow());

        output.scrollBy(5);
        assertTrue(output.hasAbove());

        output.toBottom();
        assertFalse(output.hasBelow(), "End 键落点：滚到内容末尾");
        assertTrue(output.hasAbove());
    }

    @Test
    @DisplayName("ERROR 语义的输出按错误样式做前缀，不因搬到面板而丢语义")
    void render_should_keepKindSemantics() {
        ShellOutput output = new ShellOutput();

        output.show("/delete", "删不掉：文件被占用", ShellNotice.Kind.ERROR);

        assertEquals("\u2717 删不掉：文件被占用", output.render(WIDTH, 5).getLines().get(0).text().trim());
    }
}
