package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 二级选择页的渲染：把 {@link CommandChoicePicker} 的候选清单变成视觉行。
 * <p>
 * <b>纯函数</b>：输入（候选 + 选中项 + 可用列数）决定输出，不读终端、不改状态，
 * 因此换行、截断、高亮这些最容易在 CJK 下错位的地方都能在单测里断言。
 * <p>
 * <b>为什么和补全面板长得像</b>：两者都占用输入框上方的浮层位置，用户看到的是同一类界面。
 * 复用同一组样式与同一条「视觉行 → TamboUI 行」的转换链，宽度账本才只有一套。
 * <p>
 * <b>为什么要有底部提示行</b>：选择页是模态的（其它按键都被吞掉，只认 {@code Esc} 退出），
 * 不写明键位用户会以为按任意键都能退出。
 *
 * @author zcd
 */
public final class CommandChoicePickerView {

    /** 选中行前缀。 */
    private static final String SELECTED_MARKER = " \u276f ";

    /** 未选中行前缀（与选中前缀等宽，保证左列对齐）。 */
    private static final String PLAIN_MARKER = "   ";

    /** 标签与说明左列的目标宽度（显示列）。 */
    private static final int LEFT_WIDTH = 30;

    /** 左列与说明之间的间隔。 */
    private static final int GAP = 2;

    /** 当前取值标记。 */
    private static final String CURRENT_MARK = "\uff08\u5f53\u524d\uff09";

    /** 底部键位提示。 */
    private static final String HINT = "\u2191/\u2193 \u9009\u62e9 \u00b7 Enter \u786e\u8ba4 \u00b7 Esc \u53d6\u6d88";

    /** 标签样式。 */
    private static final Style LABEL_STYLE = Style.EMPTY.cyan().bold();

    /** 说明样式。 */
    private static final Style DESCRIPTION_STYLE = Style.EMPTY.dim();

    /** 无样式。 */
    private static final Style PLAIN_STYLE = Style.EMPTY;

    private CommandChoicePickerView() {
    }

    /**
     * 渲染选择页的内容行。
     *
     * @param picker 选择页状态，可为 {@code null}
     * @param width  面板可用列数，小于 1 时按 1 处理
     * @return 视觉行列表：未激活或候选为空时返回空列表（调用方据此决定不显示面板）
     */
    public static List<VisualLine> render(CommandChoicePicker picker, int width) {
        if (picker == null || !picker.isActive()) {
            return Collections.emptyList();
        }
        int columns = Math.max(1, width);
        List<CommandChoice> choices = picker.getChoices();
        if (choices.isEmpty()) {
            return Collections.emptyList();
        }
        int visible = Math.min(CommandCompletion.MAX_VISIBLE, choices.size());
        int from = windowStart(choices.size(), visible, picker.getSelectedIndex());
        List<VisualLine> lines = new ArrayList<VisualLine>(visible + 1);
        for (int i = from; i < from + visible; i++) {
            lines.add(renderLine(choices.get(i), i == picker.getSelectedIndex(), columns));
        }
        lines.add(new VisualLine(Collections.singletonList(
                new StyledSegment(CommandCompletionView.truncate(PLAIN_MARKER + HINT, columns), DESCRIPTION_STYLE))));
        return lines;
    }

    /**
     * 计算显示窗口的起始下标：候选多于可见行数时，让选中项尽量停在中间。
     *
     * @param total    候选总数
     * @param visible  可见行数
     * @param selected 选中项下标
     * @return 起始下标
     */
    private static int windowStart(int total, int visible, int selected) {
        if (total <= visible) {
            return 0;
        }
        int start = selected - visible / 2;
        return Math.max(0, Math.min(total - visible, start));
    }

    /**
     * 渲染单条候选。
     *
     * @param choice   候选
     * @param selected 是否为选中项
     * @param width    可用列数
     * @return 视觉行
     */
    private static VisualLine renderLine(CommandChoice choice, boolean selected, int width) {
        int markerWidth = DisplayWidth.of(SELECTED_MARKER);
        int budget = Math.max(0, width - markerWidth);
        int leftCols = Math.min(budget, LEFT_WIDTH);
        if (budget - leftCols < GAP) {
            leftCols = Math.max(0, budget - GAP);
        }
        int descriptionCols = Math.max(0, budget - leftCols - GAP);

        // 标签先分列，再分给「当前」标记：标记是补充信息，窄屏下应先舍它
        String labelText = CommandCompletionView.truncate(choice.getLabel(), leftCols);
        int labelWidth = DisplayWidth.of(labelText);
        String currentText = truncateCurrent(choice, leftCols - labelWidth);
        int currentWidth = DisplayWidth.of(currentText);
        String description = CommandCompletionView.truncate(choice.getDescription(), descriptionCols);
        int leftPad = Math.max(0, leftCols - labelWidth - currentWidth);
        int bodyWidth = markerWidth + labelWidth + currentWidth + leftPad;
        int gap = Math.min(GAP, Math.max(0, width - bodyWidth));
        int used = bodyWidth + gap + DisplayWidth.of(description);

        Style markerStyle = selected ? PLAIN_STYLE.reversed() : PLAIN_STYLE;
        Style labelStyle = selected ? LABEL_STYLE.reversed() : LABEL_STYLE;
        Style descriptionStyle = selected ? DESCRIPTION_STYLE.reversed() : DESCRIPTION_STYLE;

        List<StyledSegment> segments = new ArrayList<StyledSegment>(6);
        segments.add(new StyledSegment(selected ? SELECTED_MARKER : PLAIN_MARKER, markerStyle));
        segments.add(new StyledSegment(labelText, labelStyle));
        segments.add(new StyledSegment(currentText, descriptionStyle));
        segments.add(new StyledSegment(spaces(leftPad), labelStyle));
        segments.add(new StyledSegment(spaces(gap), labelStyle));
        segments.add(new StyledSegment(description, descriptionStyle));
        int remaining = width - used;
        if (remaining > 0) {
            segments.add(new StyledSegment(spaces(remaining), markerStyle));
        }
        return new VisualLine(segments);
    }

    /**
     * 渲染「当前」标记，剩余列数不足时返回空串。
     *
     * @param choice   候选
     * @param maxCols  允许的最大列数
     * @return 标记文本或空串
     */
    private static String truncateCurrent(CommandChoice choice, int maxCols) {
        if (!choice.isCurrent() || maxCols < DisplayWidth.of(CURRENT_MARK)) {
            return "";
        }
        return CURRENT_MARK;
    }

    /**
     * 生成指定列数的空格。
     *
     * @param count 列数，小于 1 时返回空串
     * @return 空格串
     */
    private static String spaces(int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
