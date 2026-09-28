package zcd.jellyfish.tui;

import dev.tamboui.style.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.core.input.InputReferenceCompletion;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputReferenceCompletionView} 的单元测试。
 * <p>
 * 与命令补全同口径：面板行数与列宽不准会直接顶坏消息区，因此把行数上限、列宽不超界、
 * 选中行高亮钉成断言。
 *
 * @author zcd
 */
@DisplayName("InputReferenceCompletionView 引用补全面板渲染")
class InputReferenceCompletionViewTest {

    /** 测试用列宽。 */
    private static final int WIDTH = 48;

    @Test
    @DisplayName("未激活时不产生任何行")
    void render_should_returnEmpty_when_inactive() {
        InputReferenceCompletionState state = new InputReferenceCompletionState();

        assertTrue(InputReferenceCompletionView.render(state, WIDTH).isEmpty());
    }

    @Test
    @DisplayName("激活但无候选时给一行占位")
    void render_should_showPlaceholder_when_noMatch() {
        InputReferenceCompletionState state = new InputReferenceCompletionState();
        state.refresh("@zzz", InputReferenceCompletion.of(0, 4, "@",
                Collections.<InputReferenceChoice>emptyList()));

        List<VisualLine> lines = InputReferenceCompletionView.render(state, WIDTH);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).text().contains("\u65e0\u5339\u914d\u6587\u4ef6"));
    }

    @Test
    @DisplayName("选中行带箭头标记，未选中行用等宽空白对齐且不反白")
    void render_should_markSelectedRow() {
        InputReferenceCompletionState state = new InputReferenceCompletionState();
        state.refresh("@", completion(choice("src/"), choice("readme.md")));

        List<VisualLine> lines = InputReferenceCompletionView.render(state, WIDTH);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).text().startsWith(" \u276f "), "第一条应是选中行");
        assertTrue(lines.get(1).text().startsWith("   "), "未选中行应用等宽空白对齐");
        assertTrue(hasReversedSegment(lines.get(0)), "选中行应有反白片段");
        assertFalse(hasReversedSegment(lines.get(1)), "未选中行不应反白");
    }

    @Test
    @DisplayName("面板最多显示 8 行，且选中项停在可见窗口内")
    void render_should_capVisibleRows() {
        InputReferenceChoice[] choices = new InputReferenceChoice[20];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = choice("file" + i);
        }
        InputReferenceCompletionState state = new InputReferenceCompletionState();
        state.refresh("@", completion(choices));
        for (int i = 0; i < 15; i++) {
            state.moveDown();
        }

        List<VisualLine> lines = InputReferenceCompletionView.render(state, WIDTH);

        assertEquals(InputReferenceCompletionState.MAX_VISIBLE, lines.size());
        assertTrue(lines.stream().anyMatch(InputReferenceCompletionViewTest::hasReversedSegment),
                "选中行必须在可见窗口内");
    }

    @Test
    @DisplayName("每一行的显示宽度都不超过可用列数")
    void render_should_not_exceedWidth() {
        InputReferenceCompletionState state = new InputReferenceCompletionState();
        state.refresh("@", completion(choice("一个非常长的中文文件名用来验证截断.txt"),
                choice("short.txt")));

        for (VisualLine line : InputReferenceCompletionView.render(state, 20)) {
            assertTrue(DisplayWidth.of(line.text()) <= 20, line.text());
        }
    }

    @Test
    @DisplayName("说明放不下时被整块丢弃，名字仍然完整可见")
    void render_should_dropDetail_when_tooNarrow() {
        InputReferenceCompletionState state = new InputReferenceCompletionState();
        state.refresh("@", InputReferenceCompletion.of(0, 1, "@",
                Arrays.asList(new InputReferenceChoice("long-name.txt", null, "1234567890123 字节"))));

        List<VisualLine> lines = InputReferenceCompletionView.render(state, 16);

        assertTrue(lines.get(0).text().contains("long-name"));
        assertFalse(lines.get(0).text().contains("\u5b57\u8282"));
    }

    /**
     * 构造补全结果。
     *
     * @param choices 候选
     * @return 补全结果
     */
    private static InputReferenceCompletion completion(InputReferenceChoice... choices) {
        return InputReferenceCompletion.of(0, 1, "@", Arrays.asList(choices));
    }

    /**
     * 构造候选。
     *
     * @param label 标签
     * @return 候选
     */
    private static InputReferenceChoice choice(String label) {
        return new InputReferenceChoice(label, label, null);
    }

    /**
     * 判断一行里是否有反白片段。
     *
     * @param line 视觉行
     * @return 有返回 {@code true}
     */
    private static boolean hasReversedSegment(VisualLine line) {
        for (StyledSegment segment : line.getSegments()) {
            if (!segment.isEmpty() && segment.getStyle().effectiveModifiers().contains(Modifier.REVERSED)) {
                return true;
            }
        }
        return false;
    }
}
