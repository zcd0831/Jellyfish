package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 补全面板的渲染：把 {@link CommandCompletion} 的候选清单变成视觉行。
 * <p>
 * <b>纯函数</b>：输入（候选 + 选中项 + 可用列数）决定输出，不读终端、不改状态，
 * 因此换行、截断、高亮这些最容易在 CJK 下错位的地方都能在单测里断言。
 * <p>
 * <b>为什么输出 {@link VisualLine} 而不是直接画到缓冲区</b>：与消息区共用同一条转换链
 * （{@code ChatShell} 的视觉行 → 行序列是自有文本模型与 TamboUI 之间唯一的转换点），
 * 于是补全面板的宽度账本与消息区完全一致，不需要第二套换行逻辑。
 * <p>
 * <b>为什么选中项要补满整行</b>：只给文字加反白时，高亮块的右边界会随命令行长短参差不齐，
 * 在一列候选里看起来像随机的色块；补满整行后高亮本身就是「当前行」的指示。
 *
 * @author zcd
 */
public final class CommandCompletionView {

    /** 选中行前缀。 */
    private static final String SELECTED_MARKER = " \u276f ";

    /** 未选中行前缀（与选中前缀等宽，保证左列对齐）。 */
    private static final String PLAIN_MARKER = "   ";

    /** 命令名与用法左列的目标宽度（显示列）。 */
    private static final int LEFT_WIDTH = 24;

    /** 左列与说明之间的间隔。 */
    private static final int GAP = 2;

    /** 无候选时的占位文案。 */
    private static final String NO_MATCH = "\u65e0\u5339\u914d\u547d\u4ee4";

    /** 命令名样式。 */
    private static final Style NAME_STYLE = Style.EMPTY.cyan().bold();

    /** 用法片段样式。 */
    private static final Style USAGE_STYLE = Style.EMPTY.dim();

    /** 说明样式。 */
    private static final Style SUMMARY_STYLE = Style.EMPTY.dim();

    /** 无样式（用于未选中行的前缀与填充）。 */
    private static final Style PLAIN_STYLE = Style.EMPTY;

    private CommandCompletionView() {
    }

    /**
     * 渲染补全面板的内容行。
     *
     * @param completion 补全状态，不可为 {@code null}
     * @param width      面板可用列数，小于 1 时按 1 处理
     * @return 视觉行列表：补全未激活或候选为空时返回空列表（调用方据此决定不显示面板）
     */
    public static List<VisualLine> render(CommandCompletion completion, int width) {
        if (!completion.isActive()) {
            return Collections.emptyList();
        }
        int columns = Math.max(1, width);
        List<CommandInfo> candidates = completion.getCandidates();
        if (candidates.isEmpty()) {
            return Collections.singletonList(VisualLine.of(
                    new StyledSegment(PLAIN_MARKER + NO_MATCH, SUMMARY_STYLE)));
        }
        int visible = Math.min(CommandCompletion.MAX_VISIBLE, candidates.size());
        int from = windowStart(candidates.size(), visible, completion.getSelectedIndex());
        List<VisualLine> lines = new ArrayList<VisualLine>(visible);
        for (int i = from; i < from + visible; i++) {
            lines.add(renderLine(candidates.get(i), i == completion.getSelectedIndex(), columns));
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
     * @param info     候选命令
     * @param selected 是否为选中项
     * @param width    可用列数
     * @return 视觉行
     */
    private static VisualLine renderLine(CommandInfo info, boolean selected, int width) {
        int markerWidth = DisplayWidth.of(SELECTED_MARKER);
        int budget = Math.max(0, width - markerWidth);
        // 先给间隔留位，否则窄屏下左列会连同间隔一起把总宽顶出边界
        int leftCols = Math.min(budget, LEFT_WIDTH);
        if (budget - leftCols < GAP) {
            leftCols = Math.max(0, budget - GAP);
        }
        int summaryCols = Math.max(0, budget - leftCols - GAP);

        // 左列先分给命令名，再分给用法片段：两段样式不同（名字醒目、用法暗色），不能先合并再截断
        String nameText = truncate(CommandCompletion.PREFIX + info.getName(), leftCols);
        int nameWidth = DisplayWidth.of(nameText);
        String usageText = truncate(usage(info), Math.max(0, leftCols - nameWidth));
        int usageWidth = DisplayWidth.of(usageText);
        String summary = truncate(summary(info), summaryCols);
        int leftPad = Math.max(0, leftCols - nameWidth - usageWidth);
        int bodyWidth = markerWidth + nameWidth + usageWidth + leftPad;
        // 说明为空（或窄到放不下）时不再补间隔：多出的两列只是无意义空白
        int gap = Math.min(GAP, Math.max(0, width - bodyWidth));
        int used = bodyWidth + gap + DisplayWidth.of(summary);

        // 选中行的所有片段都带反白（含填充），未选中行则用各自样式
        Style markerStyle = selected ? PLAIN_STYLE.reversed() : PLAIN_STYLE;
        Style nameStyle = selected ? NAME_STYLE.reversed() : NAME_STYLE;
        Style usageStyle = selected ? USAGE_STYLE.reversed() : USAGE_STYLE;
        Style summaryStyle = selected ? SUMMARY_STYLE.reversed() : SUMMARY_STYLE;

        List<StyledSegment> segments = new ArrayList<StyledSegment>(6);
        segments.add(new StyledSegment(selected ? SELECTED_MARKER : PLAIN_MARKER, markerStyle));
        segments.add(new StyledSegment(nameText, nameStyle));
        segments.add(new StyledSegment(usageText, usageStyle));
        // 左列补齐到固定宽度：候选之间说明列对齐，扫读时不必横向找起始位置
        segments.add(new StyledSegment(spaces(leftPad), usageStyle));
        segments.add(new StyledSegment(spaces(gap), usageStyle));
        segments.add(new StyledSegment(summary, summaryStyle));
        int remaining = width - used;
        if (remaining > 0) {
            segments.add(new StyledSegment(spaces(remaining), selected ? PLAIN_STYLE.reversed() : PLAIN_STYLE));
        }
        return new VisualLine(segments);
    }

    /**
     * 拼出用法片段（含前导空格）。
     *
     * @param info 候选命令
     * @return 用法片段，无名片或无用法时为空串
     */
    private static String usage(CommandInfo info) {
        String usage = info.getUsage();
        return usage == null || usage.isEmpty() ? "" : " " + usage;
    }

    /**
     * 取说明文本。
     *
     * @param info 候选命令
     * @return 说明文本，无名片时为空串
     */
    private static String summary(CommandInfo info) {
        String summary = info.getSummary();
        return summary == null ? "" : summary;
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

    /**
     * 按显示宽度截断文本，超宽时以省略号结尾。
     *
     * @param text    文本，可为 {@code null}
     * @param maxCols 允许的最大列数
     * @return 截断后的文本，保证非 {@code null}
     */
    static String truncate(String text, int maxCols) {
        if (text == null || text.isEmpty() || maxCols <= 0) {
            return "";
        }
        if (DisplayWidth.of(text) <= maxCols) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int index = 0;
        // 预留 1 列给省略号：截断后仍需让人看出「这里还有内容」
        int limit = Math.max(0, maxCols - 1);
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int cpWidth = DisplayWidth.ofCodePoint(codePoint);
            if (used + cpWidth > limit) {
                break;
            }
            sb.appendCodePoint(codePoint);
            used += cpWidth;
            index += Character.charCount(codePoint);
        }
        return sb.append('\u2026').toString();
    }
}
