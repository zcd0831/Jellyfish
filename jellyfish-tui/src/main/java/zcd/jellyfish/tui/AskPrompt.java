package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.support.ControlChars;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.LineWrapper;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提问浮层：把一条 {@link AskChannel.Pending} 变成屏幕上的视觉行。
 * <p>
 * <b>它要回答的问题</b>：模型在问用户一件事，用户要看清「问的是什么」再从几个选项里挑一个。
 * 因此问题原文优先，其次才是候选项——这是与审批浮层（先给工具名与参数）唯一的结构差异。
 * <p>
 * <b>纯函数</b>：输入（提问 + 选择状态 + 可用列数 + 是否在编辑答案）决定输出，不读终端、不改状态。
 * <p>
 * <b>为什么总是多出一项「其它」</b>：预设选项穷不尽用户的真实答案。只能从给定的几项里选，
 * 用户要么挑一个最接近的（模型收到一个错的答案，且无从察觉），要么放弃作答。给他一个自己写的出口，
 * 这两条都不必走。自由文本本身由外壳的输入框承接（见 {@code TuiApp} 的编辑态），
 * 本类只负责把它作为一个选项呈现出来。
 * <p>
 * <b>选项状态复用二级选择页、渲染不用</b>：{@link CommandChoicePicker} 提供「候选 + 选中态 + 窗口」，
 * 键位（{@code ↑}/{@code ↓}/{@code Enter}）因此不用新增；但绘制由本类自己做——
 * {@link CommandChoicePickerView} 是「短标签（硬限 30 列）+ 长说明」的双列布局，为命令候选设计，
 * 而提问的标签就是内容本身（详见 {@link #optionLines}）。
 * <p>
 * <b>为什么这里的 {@code Esc} 与审批不同</b>：审批的 {@code Esc} 是「拒绝并中断回合」，
 * 提问的 {@code Esc} 只是「放弃作答」，回合照常继续。这是两类交互的分内之事——
 * 审批卡的是权限，放弃作答只是这次没问到答案。
 *
 * @author zcd
 */
public final class AskPrompt {

    /** 浮层标题。 */
    public static final String TITLE = " \u2753 需要你的选择 ";

    /** 「其它（自己输入）」这一项的取值。 */
    public static final String OTHER = "__other__";

    /** 选项态底部键位提示。 */
    private static final String HINT = "\u2191/\u2193 选择 \u00b7 Enter 确认 \u00b7 Esc 放弃作答";

    /** 编辑态底部键位提示：此时按键放行给输入框。 */
    private static final String HINT_EDITING = "\u2328 输入你的答案 \u00b7 Enter 提交 \u00b7 Esc 返回选项";

    /** 问题文本最多占用的视觉行数，超出部分明确省略。 */
    private static final int MAX_QUESTION_ROWS = 6;

    /**
     * 选中项最多展开的视觉行数，超出部分明确省略。
     * <p>
     * 与 {@code ApprovalPrompt.MAX_FIELD_ROWS} 同口径：一个字段/选项再长也不能把整个浮层
     * 顶到吃掉消息区。区别是这里只对<b>选中项</b>设限——未选中的那些本来就只占一行。
     */
    private static final int MAX_OPTION_ROWS = 6;

    /** 一次最多显示多少个选项（与命令候选的可见上限同量级）。 */
    private static final int MAX_VISIBLE_OPTIONS = 8;

    /** 选中行前缀。 */
    private static final String SELECTED_MARKER = " \u276f ";

    /** 未选中行前缀：与选中前缀等宽（按显示列算，中文/符号宽度不影响对齐）。 */
    private static final String PLAIN_MARKER = spaces(DisplayWidth.of(SELECTED_MARKER));

    /** 前缀占用的显示列数。 */
    private static final int MARKER_WIDTH = DisplayWidth.of(SELECTED_MARKER);

    /** 标签与说明之间的间隔列数。 */
    private static final int OPTION_GAP = 2;

    /** 问题行前缀：一个空格，让问题与面板边框之间留出气口。 */
    private static final String QUESTION_PREFIX = " ";

    /** 问题样式。 */
    private static final Style QUESTION_STYLE = Style.EMPTY.bold();

    /** 省略提示样式。 */
    private static final Style OMIT_STYLE = Style.EMPTY.dim();

    /** 选项标签样式（与命令候选同一组配色，浮层看起来才是一套东西）。 */
    private static final Style LABEL_STYLE = Style.EMPTY.cyan().bold();

    /** 选项说明样式。 */
    private static final Style DESCRIPTION_STYLE = Style.EMPTY.dim();

    /** 无强调样式。 */
    private static final Style PLAIN_STYLE = Style.EMPTY;

    private AskPrompt() {
    }

    /**
     * 由提问请求构造候选项：用户给的选项在前，「其它（自己输入）」固定在最后。
     * <p>
     * 「其它」放最后而不是最前：默认选中项应当落在用户提出的第一个选项上，
     * 让「直接回车」对应最常见的意图，而不是一上来就把他推进编辑态。
     *
     * @param request 提问请求，不可为 {@code null}
     * @return 不可变选项列表，保证非 {@code null}
     */
    public static List<CommandChoice> choices(AskRequest request) {
        List<CommandChoice> choices = new ArrayList<CommandChoice>(request.getOptions().size() + 1);
        for (AskOption option : request.getOptions()) {
            choices.add(new CommandChoice(option.getOptionId(), option.getLabel(), option.getDescription(), false));
        }
        choices.add(new CommandChoice(OTHER, "\u270e 其它（自己输入）", "选项都不合适时，自己写一个答案", false));
        return Collections.unmodifiableList(choices);
    }

    /**
     * 判断选中项是否为「其它（自己输入）」。
     *
     * @param choice 当前选中项，可为 {@code null}
     * @return 是「其它」返回 {@code true}
     */
    public static boolean isCustom(CommandChoice choice) {
        return choice != null && OTHER.equals(choice.getValue());
    }

    /**
     * 把提问渲染成视觉行：问题区 + 空行 + 选项区。
     *
     * @param pending 待答提问，不可为 {@code null}
     * @param picker  选项状态，可为 {@code null}（选项区为空）
     * @param width   可用列数，小于 1 时按 1 处理
     * @param editing 是否处于「自己输入答案」的编辑态（只影响底部提示）
     * @return 视觉行列表；提问为空时返回空列表
     */
    public static List<VisualLine> render(AskChannel.Pending pending, CommandChoicePicker picker, int width,
                                          boolean editing) {
        if (pending == null) {
            return Collections.emptyList();
        }
        int columns = Math.max(1, width);
        List<VisualLine> lines = new ArrayList<VisualLine>();
        lines.addAll(question(text(pending.getRequest().getQuestion()), columns));
        List<VisualLine> options = optionLines(picker, columns);
        if (!options.isEmpty()) {
            lines.add(VisualLine.EMPTY);
            lines.addAll(options);
            // 键位提示挂在选项区末尾：没有选项就不显示提示（浮层整块不出现）
            lines.add(hintLine(editing ? HINT_EDITING : HINT, columns));
        }
        return lines;
    }

    /**
     * 渲染选项区：**选中的那一条完整折行展开，其余各占一行**。
     * <p>
     * <b>为什么不复用命令候选的渲染（{@code CommandChoicePickerView}）</b>：那是为「短标签 +
     * 长说明」的左说明两列设计的（{@code /model} 那种），它的标签列被硬性限在 30 列。
     * 对提问来说 {@code label} 才是内容本身，塞进 30 列必然被截断——而用户看不到选项全文就
     * 无从选择（浮层再宽也一样，30 列是与总宽无关的常量）。
     * <p>
     * <b>为什么只展开选中项</b>：全部展开会让浮层高度随选项数×文本长度膨胀，把消息区挤到下限
     * 以下、甚至把键位提示顶出屏幕（{@code ChatLayout} 的纵向分配注释里警告过这种底部被裁掉）。
     * 只展开选中项则给出这样一条保证：<b>你准备按 Enter 的那一项，一定是完整的</b>——
     * 而其余各项移动光标即可读全。代价是移动光标时浮层高度会变化一两行。
     *
     * @param picker 选项状态，可为 {@code null}（未激活）
     * @param columns 可用列数
     * @return 视觉行列表；未激活或没有候选项时为空列表
     */
    private static List<VisualLine> optionLines(CommandChoicePicker picker, int columns) {
        if (picker == null || !picker.isActive()) {
            return Collections.emptyList();
        }
        List<CommandChoice> choices = picker.getChoices();
        if (choices.isEmpty()) {
            return Collections.emptyList();
        }
        int visible = Math.min(MAX_VISIBLE_OPTIONS, choices.size());
        int from = windowStart(choices.size(), visible, picker.getSelectedIndex());
        List<VisualLine> lines = new ArrayList<VisualLine>();
        for (int i = from; i < from + visible; i++) {
            if (i == picker.getSelectedIndex()) {
                lines.addAll(selectedOption(choices.get(i), columns));
            } else {
                lines.add(plainOption(choices.get(i), columns));
            }
        }
        return lines;
    }

    /**
     * 渲染一条未选中的选项：**只占一行**，放不下时截断。
     * <p>
     * 说明只在标签**完整放下**时才附上：标签已经被截断时再挤进半句说明，两条信息都读不完整，
     * 还不如把宽度全给标签（选中它就能看到全文）。
     *
     * @param choice  候选项
     * @param columns 可用列数
     * @return 视觉行
     */
    private static VisualLine plainOption(CommandChoice choice, int columns) {
        int budget = Math.max(1, columns - MARKER_WIDTH);
        String fullLabel = text(choice.getLabel());
        String label = CommandCompletionView.truncate(fullLabel, budget);
        boolean labelComplete = DisplayWidth.of(label) >= DisplayWidth.of(fullLabel);
        String description = "";
        if (labelComplete) {
            int remaining = budget - DisplayWidth.of(label) - OPTION_GAP;
            if (remaining > 0) {
                description = CommandCompletionView.truncate(text(choice.getDescription()), remaining);
            }
        }
        List<StyledSegment> segments = new ArrayList<StyledSegment>();
        segments.add(new StyledSegment(PLAIN_MARKER, PLAIN_STYLE));
        segments.add(new StyledSegment(label, LABEL_STYLE));
        if (!description.isEmpty()) {
            segments.add(new StyledSegment(spaces(OPTION_GAP), PLAIN_STYLE));
            segments.add(new StyledSegment(description, DESCRIPTION_STYLE));
        }
        return new VisualLine(segments);
    }

    /**
     * 渲染选中的选项：标签与说明一起**完整折行**，并把高亮铺满整行。
     * <p>
     * 高亮必须铺满：这是「当前选中」唯一的视觉信号，只覆盖文字本身的话，长选项换行之后
     * 高亮块会断成两截，看起来像两个条目。
     *
     * @param choice  选中的候选项
     * @param columns 可用列数
     * @return 视觉行列表，至少一行
     */
    private static List<VisualLine> selectedOption(CommandChoice choice, int columns) {
        Style markerStyle = PLAIN_STYLE.reversed();
        Style labelStyle = LABEL_STYLE.reversed();
        List<StyledSegment> body = new ArrayList<StyledSegment>(3);
        body.add(new StyledSegment(text(choice.getLabel()), labelStyle));
        String description = text(choice.getDescription());
        if (description != null && !description.isEmpty()) {
            body.add(new StyledSegment(spaces(OPTION_GAP), markerStyle));
            body.add(new StyledSegment(description, DESCRIPTION_STYLE.reversed()));
        }
        // 续行缩进用同一个高亮样式：否则第二行的高亮会从文字处才开始
        List<VisualLine> wrapped = LineWrapper.wrap(new StyledSegment(SELECTED_MARKER, markerStyle), body,
                columns, markerStyle);
        List<VisualLine> kept = wrapped.size() <= MAX_OPTION_ROWS
                ? wrapped
                : truncateOption(wrapped, markerStyle);
        List<VisualLine> padded = new ArrayList<VisualLine>(kept.size());
        for (VisualLine line : kept) {
            padded.add(padToWidth(line, columns, markerStyle));
        }
        return padded;
    }

    /**
     * 把超长的选中项截断，并明确写出省略了几行。
     * <p>
     * 省略提示本身占一行，因此保留的行数要比上限少一——{@link #MAX_OPTION_ROWS} 说的是
     * 「这个选项最多占几行」，把提示算在外面会让实际占用比声明多一行。
     *
     * @param wrapped 完整折行结果
     * @param style   截断提示的样式
     * @return 截断后的视觉行列表，至多 {@link #MAX_OPTION_ROWS} 行
     */
    private static List<VisualLine> truncateOption(List<VisualLine> wrapped, Style style) {
        int keep = MAX_OPTION_ROWS - 1;
        List<VisualLine> kept = new ArrayList<VisualLine>(wrapped.subList(0, keep));
        int omitted = wrapped.size() - keep;
        kept.add(new VisualLine(Collections.singletonList(new StyledSegment(
                PLAIN_MARKER + "\u2026 已省略 " + omitted + " 行", style))));
        return kept;
    }

    /**
     * 把一行补齐到指定列数，让高亮铺满整行。
     *
     * @param line    视觉行
     * @param columns 目标列数
     * @param style   补白样式
     * @return 补齐后的视觉行；已经够宽时原样返回
     */
    private static VisualLine padToWidth(VisualLine line, int columns, Style style) {
        int gap = columns - line.width();
        if (gap <= 0) {
            return line;
        }
        List<StyledSegment> segments = new ArrayList<StyledSegment>(line.getSegments());
        segments.add(new StyledSegment(spaces(gap), style));
        return new VisualLine(segments);
    }

    /**
     * 渲染底部键位提示。
     *
     * @param hint    提示文本，保证非 {@code null}
     * @param columns 可用列数
     * @return 视觉行
     */
    private static VisualLine hintLine(String hint, int columns) {
        return new VisualLine(Collections.singletonList(
                new StyledSegment(CommandCompletionView.truncate(PLAIN_MARKER + hint, columns),
                        DESCRIPTION_STYLE)));
    }

    /**
     * 计算显示窗口的起始下标：选项多于可见行数时，让选中项尽量停在中间。
     *
     * @param total    选项总数
     * @param visible  可见条数
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
     * 渲染问题文本，过长时折行并限量行数。
     *
     * @param question 问题原文，保证非 {@code null}
     * @param width    可用列数
     * @return 视觉行列表，至少一行
     */
    private static List<VisualLine> question(String question, int width) {
        List<VisualLine> wrapped = LineWrapper.wrap(StyledSegment.of(QUESTION_PREFIX),
                Collections.singletonList(new StyledSegment(question, QUESTION_STYLE)), width);
        if (wrapped.size() <= MAX_QUESTION_ROWS) {
            return wrapped;
        }
        List<VisualLine> kept = new ArrayList<VisualLine>(wrapped.subList(0, MAX_QUESTION_ROWS));
        int omitted = wrapped.size() - MAX_QUESTION_ROWS;
        kept.add(new VisualLine(Collections.singletonList(
                new StyledSegment(QUESTION_PREFIX + "\u2026 已省略 " + omitted + " 行", OMIT_STYLE))));
        return kept;
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
     * 过滤控制字符。
     * <p>
     * 问题原文来自模型，可能带换行与转义序列；它会直接进终端，因此必须与其它显示面同口径地过滤。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 过滤后的文本，可为 {@code null}
     */
    static String text(String text) {
        return ControlChars.strip(text);
    }
}
