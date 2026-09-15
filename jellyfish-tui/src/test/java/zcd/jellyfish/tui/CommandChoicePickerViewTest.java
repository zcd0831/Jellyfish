package zcd.jellyfish.tui;

import dev.tamboui.style.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandChoicePickerView} 的单元测试。
 * <p>
 * 关注点：行数（含底部提示行）与列宽都会进入消息区高度账本，必须钉死；选中行与「当前」标记是
 * 用户唯一能看懂的状态反馈。
 *
 * @author zcd
 */
@DisplayName("CommandChoicePickerView 二级选择页渲染")
class CommandChoicePickerViewTest {

    /** 测试用列宽。 */
    private static final int WIDTH = 48;

    @Test
    @DisplayName("未激活时不产生任何行")
    void render_should_returnEmpty_when_inactive() {
        CommandChoicePicker picker = new CommandChoicePicker();

        assertTrue(CommandChoicePickerView.render(picker, WIDTH).isEmpty());
        assertTrue(CommandChoicePickerView.render(null, WIDTH).isEmpty());
    }

    @Test
    @DisplayName("激活时行数 = 可见候选数 + 1 行提示")
    void render_should_appendHintRow() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices(3));

        List<VisualLine> lines = CommandChoicePickerView.render(picker, WIDTH);

        assertEquals(4, lines.size());
        assertTrue(lines.get(3).text().contains("Esc"), "最后一行应是键位提示");
    }

    @Test
    @DisplayName("选中行带箭头标记，未选中行用等宽空白对齐")
    void render_should_markSelectedRow() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices(3));

        List<VisualLine> lines = CommandChoicePickerView.render(picker, WIDTH);

        assertTrue(lines.get(0).text().startsWith(" \u276f "), "第一条应是选中行");
        assertTrue(lines.get(1).text().startsWith("   "), "未选中行应用等宽空白对齐");
    }

    @Test
    @DisplayName("选中行整行反白，未选中行不反白")
    void render_should_reverseSelectedRowOnly() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices(3));

        List<VisualLine> lines = CommandChoicePickerView.render(picker, WIDTH);

        assertTrue(hasReversedSegment(lines.get(0)), "选中行应有反白片段");
        assertFalse(hasReversedSegment(lines.get(1)), "未选中行不应反白");
    }

    @Test
    @DisplayName("当前取值带「当前」标记")
    void render_should_markCurrentChoice() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", Arrays.asList(
                new CommandChoice("coder", "coder", "写代码", false),
                new CommandChoice("writer", "writer", null, true)));

        List<VisualLine> lines = CommandChoicePickerView.render(picker, WIDTH);

        assertTrue(lines.get(1).text().contains("\u5f53\u524d"));
        assertFalse(lines.get(0).text().contains("\u5f53\u524d"));
    }

    @Test
    @DisplayName("候选多于上限时最多显示 8 行候选")
    void render_should_limitVisibleRows() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", choices(12));

        List<VisualLine> lines = CommandChoicePickerView.render(picker, WIDTH);

        assertEquals(CommandCompletion.MAX_VISIBLE + 1, lines.size());
    }

    @Test
    @DisplayName("每一行的显示宽度都不超过可用列数")
    void render_should_notExceedWidth() {
        CommandChoicePicker picker = new CommandChoicePicker();
        picker.open("/agent", Arrays.asList(new CommandChoice("coder", "coder",
                "这是一条很长很长很长的说明文案用于验证截断", true)));

        for (VisualLine line : CommandChoicePickerView.render(picker, 20)) {
            assertTrue(line.width() <= 20, "行宽 " + line.width() + " 超出 20");
        }
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
     * 构造若干候选。
     *
     * @param count 条数
     * @return 候选列表
     */
    private static List<CommandChoice> choices(int count) {
        List<CommandChoice> list = new ArrayList<CommandChoice>(count);
        for (int i = 0; i < count; i++) {
            list.add(new CommandChoice("agent" + i, "agent" + i, "说明 " + i, i == 0));
        }
        return list;
    }
}
