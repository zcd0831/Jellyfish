package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.support.ControlChars;
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
 * <b>为什么选项区复用二级选择页</b>：{@link CommandChoicePicker} 提供「候选 + 选中态」，
 * {@link CommandChoicePickerView} 负责画。提问与「从几个取值里挑一个」在交互上是同一件事，
 * 因此不新写一套选择器，键位（{@code ↑}/{@code ↓}/{@code Enter}）也就不用新增。
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

    /** 问题行前缀：一个空格，让问题与面板边框之间留出气口。 */
    private static final String QUESTION_PREFIX = " ";

    /** 问题样式。 */
    private static final Style QUESTION_STYLE = Style.EMPTY.bold();

    /** 省略提示样式。 */
    private static final Style OMIT_STYLE = Style.EMPTY.dim();

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
        List<VisualLine> options = CommandChoicePickerView.render(picker, columns, editing ? HINT_EDITING : HINT);
        if (!options.isEmpty()) {
            lines.add(VisualLine.EMPTY);
            lines.addAll(options);
        }
        return lines;
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
