package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行内引用补全面板的渲染：把候选清单变成视觉行。
 * <p>
 * <b>纯函数</b>：输入（候选 + 选中项 + 可用列数）决定输出，不读终端、不改状态。与
 * {@link CommandCompletionView} 一样输出 {@link VisualLine}，因此补全面板与消息区共用同一条
 * 换行与宽度账本。
 * <p>
 * <b>为什么右列放说明而不是用法</b>：文件的说明是「目录 / 字节数」，左列是名字。给左列一个固定宽度
 * 会让长路径被过早截断，因此这里不设左列宽度——名字占多少算多少，剩余空间留给说明，
 * 说明放不下就整块丢弃（宁可少一列信息，也不要把名字截成认不出来）。
 *
 * @author zcd
 */
public final class InputReferenceCompletionView {

    /** 选中行前缀。 */
    private static final String SELECTED_MARKER = " \u276f ";

    /** 未选中行前缀（与选中前缀等宽，保证左列对齐）。 */
    private static final String PLAIN_MARKER = "   ";

    /** 名字与说明之间的最小间隔。 */
    private static final int GAP = 2;

    /** 无候选时的占位文案。 */
    private static final String NO_MATCH = "\u65e0\u5339\u914d\u6587\u4ef6";

    /** 名字样式。 */
    private static final Style NAME_STYLE = Style.EMPTY.cyan().bold();

    /** 说明样式。 */
    private static final Style DETAIL_STYLE = Style.EMPTY.dim();

    /** 无样式（用于未选中行的前缀与填充）。 */
    private static final Style PLAIN_STYLE = Style.EMPTY;

    private InputReferenceCompletionView() {
    }

    /**
     * 渲染补全面板的内容行。
     *
     * @param state 补全状态，不可为 {@code null}
     * @param width 面板可用列数，小于 1 时按 1 处理
     * @return 视觉行列表：补全未激活时返回空列表（调用方据此决定不显示面板）
     */
    public static List<VisualLine> render(InputReferenceCompletionState state, int width) {
        if (!state.isActive()) {
            return Collections.emptyList();
        }
        int columns = Math.max(1, width);
        List<InputReferenceChoice> candidates = state.getCandidates();
        if (candidates.isEmpty()) {
            return Collections.singletonList(VisualLine.of(
                    new StyledSegment(PLAIN_MARKER + NO_MATCH, DETAIL_STYLE)));
        }
        int visible = Math.min(InputReferenceCompletionState.MAX_VISIBLE, candidates.size());
        int from = windowStart(candidates.size(), visible, state.getSelectedIndex());
        List<VisualLine> lines = new ArrayList<VisualLine>(visible);
        for (int i = from; i < from + visible; i++) {
            lines.add(renderLine(candidates.get(i), i == state.getSelectedIndex(), columns));
        }
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
    private static VisualLine renderLine(InputReferenceChoice choice, boolean selected, int width) {
        int markerWidth = DisplayWidth.of(SELECTED_MARKER);
        int budget = Math.max(0, width - markerWidth);
        String detail = choice.getDetail() == null ? "" : choice.getDetail();
        int detailColumns = DisplayWidth.of(detail);
        // 说明只在「名字 + 间隔 + 说明」装得下时保留，否则整块丢弃
        int nameBudget = budget;
        int gap = 0;
        if (!detail.isEmpty() && detailColumns + GAP < budget) {
            nameBudget = budget - detailColumns - GAP;
            gap = GAP;
        } else {
            detail = "";
        }
        String name = CommandCompletionView.truncate(choice.getLabel(), nameBudget);
        int used = markerWidth + DisplayWidth.of(name) + gap + DisplayWidth.of(detail);

        Style markerStyle = selected ? PLAIN_STYLE.reversed() : PLAIN_STYLE;
        Style nameStyle = selected ? NAME_STYLE.reversed() : NAME_STYLE;
        Style detailStyle = selected ? DETAIL_STYLE.reversed() : DETAIL_STYLE;

        List<StyledSegment> segments = new ArrayList<StyledSegment>(5);
        segments.add(new StyledSegment(selected ? SELECTED_MARKER : PLAIN_MARKER, markerStyle));
        segments.add(new StyledSegment(name, nameStyle));
        segments.add(new StyledSegment(spaces(gap), nameStyle));
        segments.add(new StyledSegment(detail, detailStyle));
        int remaining = width - used;
        if (remaining > 0) {
            segments.add(new StyledSegment(spaces(remaining), markerStyle));
        }
        return new VisualLine(segments);
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
