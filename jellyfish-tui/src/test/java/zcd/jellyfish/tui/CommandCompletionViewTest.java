package zcd.jellyfish.tui;

import dev.tamboui.style.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandCompletionView} 的单元测试。
 * <p>
 * 关注点：面板行数与宽度不准会直接顶坏消息区（CJK 下尤其容易），因此把「行数上限」「列宽不超界」
 * 「选中行高亮」都钉成断言。
 *
 * @author zcd
 */
@DisplayName("CommandCompletionView 补全面板渲染")
class CommandCompletionViewTest {

    /** 测试用列宽。 */
    private static final int WIDTH = 48;

    @Test
    @DisplayName("补全未激活时不产生任何行（调用方据此不显示面板）")
    void render_should_returnEmpty_when_inactive() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("hello", commands(3));

        assertTrue(CommandCompletionView.render(completion, WIDTH).isEmpty());
    }

    @Test
    @DisplayName("激活但无候选时给一行占位，而不是空白面板")
    void render_should_showPlaceholder_when_noMatch() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/zzz", commands(3));

        List<VisualLine> lines = CommandCompletionView.render(completion, WIDTH);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).text().contains("\u65e0\u5339\u914d\u547d\u4ee4"));
    }

    @Test
    @DisplayName("选中行带箭头标记，未选中行用等宽空白对齐")
    void render_should_markSelectedRow_when_active() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/", commands(3));

        List<VisualLine> lines = CommandCompletionView.render(completion, WIDTH);

        assertTrue(lines.get(0).text().startsWith(" \u276f "), "第一条应是选中行");
        assertTrue(lines.get(1).text().startsWith("   "), "未选中行应用等宽空白对齐");
        assertFalse(lines.get(1).text().contains("\u276f"));
    }

    @Test
    @DisplayName("选中行整行反白，未选中行不反白")
    void render_should_reverseSelectedRowOnly() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/", commands(3));
        completion.moveDown();

        List<VisualLine> lines = CommandCompletionView.render(completion, WIDTH);

        assertTrue(hasReversedSegment(lines.get(1)), "选中行应有反白片段");
        assertFalse(hasReversedSegment(lines.get(0)), "未选中行不应反白");
    }

    @Test
    @DisplayName("候选多于上限时最多显示 8 行")
    void render_should_limitVisibleRows() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/", commands(12));

        List<VisualLine> lines = CommandCompletionView.render(completion, WIDTH);

        assertEquals(CommandCompletion.MAX_VISIBLE, lines.size());
    }

    @Test
    @DisplayName("选中项下移超过窗口时窗口跟随滚动")
    void render_should_scrollWindow_when_selectionMovesDown() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/", commands(12));
        for (int i = 0; i < 9; i++) {
            completion.moveDown();
        }

        List<VisualLine> lines = CommandCompletionView.render(completion, WIDTH);

        assertEquals(CommandCompletion.MAX_VISIBLE, lines.size());
        assertTrue(lines.get(5).text().contains("cmd9"), "窗口应以选中项为中心");
    }

    @Test
    @DisplayName("每一行的显示宽度都不超过可用列数")
    void render_should_notExceedWidth() {
        List<CommandInfo> available = Collections.singletonList(new CommandInfo("mode",
                new CommandDescriptor("\u8fd9\u662f\u4e00\u6761\u5f88\u957f\u5f88\u957f\u7684\u8bf4\u660e\u6587\u6848\u7528\u4e8e\u9a8c\u8bc1\u622a\u65ad", "<plan|default>", null)));
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/mo", available);

        for (VisualLine line : CommandCompletionView.render(completion, 20)) {
            assertTrue(line.width() <= 20, "行宽 " + line.width() + " 超出 20");
        }
    }

    @Test
    @DisplayName("截断在中文下按显示宽度而不是字符数计算")
    void truncate_should_countDisplayWidth_when_cjk() {
        String truncated = CommandCompletionView.truncate("\u4e2d\u6587\u4e2d\u6587", 3);

        assertEquals(3, DisplayWidth.of(truncated));
        assertEquals("\u4e2d\u2026", truncated);
    }

    @Test
    @DisplayName("未超宽时原样返回")
    void truncate_should_keepText_when_fits() {
        assertEquals("/help", CommandCompletionView.truncate("/help", 10));
    }

    @Test
    @DisplayName("宽度不足一列时返回空串，不抛异常")
    void truncate_should_returnEmpty_when_noRoom() {
        assertEquals("", CommandCompletionView.truncate("/help", 0));
        assertEquals("", CommandCompletionView.truncate(null, 10));
    }

    /**
     * 判断一行里是否存在反白样式片段。
     *
     * @param line 视觉行
     * @return 存在返回 {@code true}
     */
    private static boolean hasReversedSegment(VisualLine line) {
        for (StyledSegment segment : line.getSegments()) {
            if (!segment.isEmpty() && segment.getStyle().effectiveModifiers().contains(Modifier.REVERSED)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造若干条带名片的测试命令。
     *
     * @param count 条数
     * @return 命令清单
     */
    private static List<CommandInfo> commands(int count) {
        List<CommandInfo> list = new ArrayList<CommandInfo>(count);
        for (int i = 0; i < count; i++) {
            list.add(new CommandInfo("cmd" + i, new CommandDescriptor("\u8bf4\u660e " + i, null, null)));
        }
        return list;
    }

    /**
     * 断言用的固定清单。
     *
     * @return 命令清单
     */
    private static List<CommandInfo> fixedCommands() {
        return Collections.singletonList(
                new CommandInfo("model", new CommandDescriptor("\u5207\u6362\u6a21\u578b", "<provider/model>", null)));
    }

    @Test
    @DisplayName("带用法片段的命令在左列同时展示命令名与用法")
    void render_should_showUsage_when_present() {
        CommandCompletion completion = new CommandCompletion();
        completion.refresh("/mo", fixedCommands());

        String text = CommandCompletionView.render(completion, WIDTH).get(0).text();

        assertTrue(text.contains("/model <provider/model>"));
    }
}
