package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.api.ui.UiSegmentKind;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.LineWrapper;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 插件界面内容的渲染：api 的语义模型 → 外壳的视觉模型 → TamboUI。
 * <p>
 * <b>它是唯一转换点</b>：{@code jellyfish-api} 零依赖，插件给的是「文本 + 语义档位」（{@link UiEmphasis}），
 * 而屏幕上要的是 {@code Style}；把这件事收在一个类里，插件换不了主题、外壳也不用认识插件。
 * 这与 {@code ShellNotice.Kind → TranscriptProjector.styleOf} 是同一套做法。
 * <p>
 * <b>为什么折行与截断在这里、而不是在插件侧</b>：插件看不到终端宽度、也不知道消息区被挤到多窄，
 * 让它自己排版等于让它在没有任何输入的情况下做决定。因此契约只要求「一行 = 一个要读的念头」，
 * 折行、限宽、限行数全部由外壳执行——这也是「一个插件不能撑坏整版」的实现方式。
 *
 * @author zcd
 */
public final class UiRender {

    /** 常规正文样式。 */
    private static final Style NORMAL_STYLE = Style.EMPTY;

    /** 次要信息样式（与工具轨迹同口径：不想细读，但需要在场）。 */
    private static final Style DIM_STYLE = Style.EMPTY.dim();

    /** 强调信息样式（与用户输入前缀同色系：需要被一眼扫到）。 */
    private static final Style ACCENT_STYLE = Style.EMPTY.cyan();

    /** 警示样式（与外壳提示的 WARN 同口径）。 */
    private static final Style WARN_STYLE = Style.EMPTY.yellow();

    /** 错误样式（与外壳提示的 ERROR 同口径）。 */
    private static final Style ERROR_STYLE = Style.EMPTY.red();

    /** 截断提示的前缀行样式。 */
    private static final Style TRUNCATED_STYLE = Style.EMPTY.dim();

    /** 小标题：粗体。 */
    private static final Style KIND_HEADING_STYLE = Style.EMPTY.bold();

    /** 代码 / 命令 / 标识符：反显（终端里最接近「代码块」的普遍支持写法）。 */
    private static final Style KIND_CODE_STYLE = Style.EMPTY.reversed();

    /** 键值对的值段：下划线，把它从相邻的键里划出来。 */
    private static final Style KIND_KEY_VALUE_STYLE = Style.EMPTY.underlined();

    /** 可去取的东西：斜体（终端不支持时退化成普通文本，而它本来就只是提示）。 */
    private static final Style KIND_LINK_STYLE = Style.EMPTY.italic();

    /** 空样式段，用于「不带前缀」的折行调用。 */
    private static final StyledSegment NO_PREFIX = new StyledSegment("", Style.EMPTY);

    private UiRender() {
    }

    /**
     * 把语义强调档位映射成样式。
     * <p>
     * 档位是语义、样式是外观：这里换颜色不影响任何插件，这也是 api 里不放 {@code Style} 的收益。
     * <p>
     * <b>它只管颜色，修饰归 {@link #kindStyle(UiSegmentKind)}</b>：两个维度正交之后，
     * 插件用「强调」表达「多抢眼」、用「种类」表达「是什么」，彼此不会盖掉对方。
     *
     * @param emphasis 强调档位，可为 {@code null}
     * @return 样式，保证非 {@code null}
     */
    public static Style emphasisStyle(UiEmphasis emphasis) {
        if (emphasis == null) {
            return NORMAL_STYLE;
        }
        switch (emphasis) {
            case DIM:
                return DIM_STYLE;
            case ACCENT:
                return ACCENT_STYLE;
            case WARN:
                return WARN_STYLE;
            case ERROR:
                return ERROR_STYLE;
            default:
                return NORMAL_STYLE;
        }
    }

    /**
     * 把语义种类映射成样式修饰。
     * <p>
     * <b>只用终端普遍支持的修饰</b>：粗体、反显、下划线、斜体。不写超链接转义序列——终端对
     * OSC 8 的支持参差不齐，而 {@link UiSegmentKind#LINK} 的语义只是「这段可以去取」，不是「点它」。
     * <p>
     * <b>为什么每一条都是「修饰」而不是「颜色」</b>：种类与强调档位会被同时用上，
     * 若种类也改颜色，插件就无法在「这是一行小标题」的同时说「这行是错误」——两者必有一个被吞掉。
     *
     * @param kind 语义种类，可为 {@code null}
     * @return 只含修饰的样式，保证非 {@code null}
     */
    public static Style kindStyle(UiSegmentKind kind) {
        if (kind == null) {
            return NORMAL_STYLE;
        }
        switch (kind) {
            case HEADING:
                return KIND_HEADING_STYLE;
            case CODE:
                return KIND_CODE_STYLE;
            case KEY_VALUE:
                return KIND_KEY_VALUE_STYLE;
            case LINK:
                return KIND_LINK_STYLE;
            default:
                return NORMAL_STYLE;
        }
    }

    /**
     * 把一段文本的两个维度合起来。
     *
     * @param segment 文本段，可为 {@code null}
     * @return 样式，保证非 {@code null}
     */
    public static Style segmentStyle(UiSegment segment) {
        if (segment == null) {
            return NORMAL_STYLE;
        }
        // patch 的语义正是「按字段合并」：颜色取自强调档位，修饰取自种类，两边都不丢
        return emphasisStyle(segment.getEmphasis()).patch(kindStyle(segment.getKind()));
    }

    /**
     * 计算面板内容的显示宽度（不含边框）。
     * <p>
     * 侧栏宽度预算要用它：插件无法预知自己会被给多少列，所以外壳按「它<b>想</b>要的宽度」向上取整，
     * 再夹进预算区间。按显示宽度（中日韩占两列）而不是字符数算，否则全中文面板会被算窄一半。
     *
     * @param contribution 面板内容，可为 {@code null}
     * @return 最长一行的显示宽度；无内容时为 0
     */
    public static int contentWidth(PanelContribution contribution) {
        if (contribution == null) {
            return 0;
        }
        int max = 0;
        for (UiLine line : contribution.getLines()) {
            max = Math.max(max, DisplayWidth.of(line.text()));
        }
        return max;
    }

    /**
     * 计算面板内容行数（不含边框）。
     *
     * @param contribution 面板内容，可为 {@code null}
     * @return 行数；无内容时为 0
     */
    public static int contentRows(PanelContribution contribution) {
        return contribution == null ? 0 : contribution.getLines().size();
    }

    /**
     * 把面板内容折成视觉行，并按行数上限截断。
     * <p>
     * <b>截断留一行给提示</b>：直接砍掉超出部分会让人以为插件坏了（面板看起来是完整的），
     * 因此末行换成「还有 N 行」。字数上限是外壳的权利：插件无权靠内容多来撑高自己。
     *
     * @param contribution 面板内容，可为 {@code null}
     * @param width        可用内容列数（不含边框）
     * @param maxRows      内容行数上限（不含边框）；小于 1 时按 1 处理
     * @return 视觉行列表，保证非 {@code null}
     */
    public static List<VisualLine> toVisualLines(PanelContribution contribution, int width, int maxRows) {
        if (contribution == null || contribution.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, maxRows);
        List<VisualLine> wrapped = new ArrayList<VisualLine>();
        for (UiLine line : contribution.getLines()) {
            wrapped.addAll(wrapLine(line, width));
        }
        if (wrapped.size() <= limit) {
            return wrapped;
        }
        int hidden = wrapped.size() - limit + 1;
        List<VisualLine> result = new ArrayList<VisualLine>(limit);
        result.addAll(wrapped.subList(0, limit - 1));
        result.add(VisualLine.of(new StyledSegment("\u2026 还有 " + hidden + " 行", TRUNCATED_STYLE)));
        return result;
    }

    /**
     * 折行一个逻辑行。
     *
     * @param line  逻辑行
     * @param width 可用内容列数
     * @return 视觉行列表，保证非 {@code null}
     */
    private static List<VisualLine> wrapLine(UiLine line, int width) {
        if (line.isEmpty()) {
            return Collections.singletonList(VisualLine.EMPTY);
        }
        List<StyledSegment> body = new ArrayList<StyledSegment>(line.getSegments().size());
        for (UiSegment segment : line.getSegments()) {
            body.add(new StyledSegment(segment.getText(), segmentStyle(segment)));
        }
        // 空前缀：插件面板没有「角色标记」这种概念，续行也不需要悬挂缩进
        return LineWrapper.wrap(NO_PREFIX, body, width);
    }
}
