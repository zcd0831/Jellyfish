package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.support.ControlChars;
import zcd.jellyfish.infra.support.ToolArgumentsText;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.LineWrapper;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 审批浮层：把一条 {@link ApprovalChannel.Pending} 变成屏幕上的视觉行。
 * <p>
 * <b>它要回答的问题</b>：用户要在一两秒内决定「放不放行这次工具调用」。因此屏幕上必须先给出
 * <b>工具名与参数</b>（决定依据），其次才是策略理由与上下文（会话 / 模式）。
 * 参数的呈现优先于一切装饰。
 * <p>
 * <b>纯函数</b>：输入（请求 + 选择状态 + 可用列数）决定输出，不读终端、不改状态。
 * <p>
 * <b>选项与键位复用二级选择页</b>：{@link CommandChoicePicker} 提供「候选 + 选中态」，
 * {@link CommandChoicePickerView} 负责把它画出来。审批与「挑一个参数」在交互上是同一件事
 * ——从两三个选项里挑一个并确认——因此不新写一套选择器，键位（{@code ↑}/{@code ↓}/{@code Enter}）
 * 也就不用新增。
 * <p>
 * <b>为什么参数不脱敏</b>：审批框要回答的是「放不放行这次调用」，而能回答它的只有参数原文——
 * 把 {@code apiKey} 遮成 {@code ***} 既帮不了这个判断，又会漏掉真正要看的尾巴（长命令的后半段）。
 * 口径与其余两个显示面（TUI 轨迹行、{@code -cli} 诊断行）共用，见 {@link ToolArgumentsText}——
 * 同一条参数在屏幕上不能有两种面貌。
 * <p>
 * <b>为什么超长参数是折行 + 限量行数，而不是截断成一行</b>：审批框是「看清楚了再点批准」的地方，
 * 把一条长命令截断会让最危险的尾巴恰好落在看不见的部分。折行能显示多少是多少，
 * 行数到顶时明确写出「已省略 N 行」，让人知道自己在看不完整的内容。
 *
 * @author zcd
 */
public final class ApprovalPrompt {

    /** 浮层标题。 */
    public static final String TITLE = " \u23f8 需要审批 ";

    /** 「允许一次」选项的取值。 */
    public static final String ALLOW = "allow";

    /** 「拒绝」选项的取值。 */
    public static final String DENY = "deny";

    /** 底部键位提示：与二级选择页不同，{@code Esc} 在这里是「拒绝并中断回合」。 */
    private static final String HINT = "\u2191/\u2193 选择 \u00b7 Enter 确认 \u00b7 Esc 拒绝并中断";

    /** 标签列的显示宽度（含前缀空格）。 */
    private static final int LABEL_COLUMNS = 6;

    /** 单个字段最多占用的视觉行数，超出部分明确省略。 */
    private static final int MAX_FIELD_ROWS = 6;

    /** 参数为空时的占位文本。 */
    private static final String NONE = "-";

    /** 标签样式。 */
    private static final Style LABEL_STYLE = Style.EMPTY.cyan().bold();

    /** 值样式。 */
    private static final Style VALUE_STYLE = Style.EMPTY;

    /** 省略提示样式。 */
    private static final Style OMIT_STYLE = Style.EMPTY.dim();

    private ApprovalPrompt() {
    }

    /**
     * 构造审批选项。
     * <p>
     * 首项是「允许一次」并因此默认选中：审批本身的语义是「策略已经放行，等人补一句确认」，
     * 于是最常见的操作应当落在 {@code Enter} 上。这里只做「允许一次」，
     * 不做「本会话始终允许」——那会引入第二处会话级权限状态。
     *
     * @return 不可变选项列表，保证非 {@code null}
     */
    public static List<CommandChoice> choices() {
        return Collections.unmodifiableList(Arrays.asList(
                new CommandChoice(ALLOW, "允许一次", "本次调用放行，下次仍需审批", false),
                new CommandChoice(DENY, "拒绝", "本次调用按拒绝处理，模型会收到拒绝理由", false)));
    }

    /**
     * 把选中的选项翻译成审批结论。
     * <p>
     * <b>未知取值一律按拒绝处理</b>：审批是放行开关，任何「认不出来」的情况都不应该变成放行。
     * 这条也使调用点不必自己判空。
     *
     * @param choice 当前选中项，可为 {@code null}
     * @return 批准返回 {@code true}
     */
    public static boolean isApproved(CommandChoice choice) {
        return choice != null && ALLOW.equals(choice.getValue());
    }

    /**
     * 把审批请求渲染成视觉行：字段区 + 空行 + 选项区。
     *
     * @param pending 待审批请求，不可为 {@code null}
     * @param picker  选项状态，可为 {@code null}（选项区为空）
     * @param width   可用列数，小于 1 时按 1 处理
     * @return 视觉行列表；请求为空时返回空列表
     */
    public static List<VisualLine> render(ApprovalChannel.Pending pending, CommandChoicePicker picker,
                                          int width) {
        if (pending == null) {
            return Collections.emptyList();
        }
        int columns = Math.max(1, width);
        List<VisualLine> lines = new ArrayList<VisualLine>();
        lines.addAll(field("工具", text(pending.getToolName()), columns));
        lines.addAll(field("参数", argumentsOf(pending.getArguments()), columns));
        if (pending.getReason() != null && !pending.getReason().trim().isEmpty()) {
            lines.addAll(field("理由", text(pending.getReason()), columns));
        }
        lines.addAll(field("会话", contextOf(pending), columns));
        List<VisualLine> options = CommandChoicePickerView.render(picker, columns, HINT);
        if (!options.isEmpty()) {
            lines.add(VisualLine.EMPTY);
            lines.addAll(options);
        }
        return lines;
    }

    /**
     * 渲染一个「标签 + 值」字段，值过长时折行并限量行数。
     *
     * @param label   标签文本（不含冒号），保证非 {@code null}
     * @param value   值文本，保证非 {@code null}
     * @param width   可用列数
     * @return 视觉行列表，至少一行
     */
    private static List<VisualLine> field(String label, String value, int width) {
        StyledSegment prefix = new StyledSegment(pad(label), LABEL_STYLE);
        List<VisualLine> wrapped = LineWrapper.wrap(prefix,
                Collections.singletonList(new StyledSegment(value, VALUE_STYLE)), width);
        if (wrapped.size() <= MAX_FIELD_ROWS) {
            return wrapped;
        }
        List<VisualLine> kept = new ArrayList<VisualLine>(wrapped.subList(0, MAX_FIELD_ROWS));
        int omitted = wrapped.size() - MAX_FIELD_ROWS;
        kept.add(new VisualLine(Collections.singletonList(
                new StyledSegment(spaces(LABEL_COLUMNS) + "\u2026 已省略 " + omitted + " 行", OMIT_STYLE))));
        return kept;
    }

    /**
     * 把参数映射渲染成一行紧凑的 JSON 形态文本（控制字符过滤，值照原样）。
     * <p>
     * 口径归 {@link ToolArgumentsText}：轨迹行与 {@code -cli} 诊断行用的是同一个函数，
     * 否则同一条参数会在不同界面上有不同面貌。
     *
     * @param arguments 参数映射，可为 {@code null}
     * @return 展示文本，保证非 {@code null}
     */
    static String argumentsOf(Map<String, Object> arguments) {
        return ToolArgumentsText.text(arguments);
    }

    /**
     * 拼出「会话 + 模式」上下文文本。
     *
     * @param pending 待审批请求
     * @return 展示文本，保证非 {@code null}
     */
    private static String contextOf(ApprovalChannel.Pending pending) {
        String sessionId = text(pending.getSessionId());
        String session = sessionId == null || sessionId.isEmpty() ? NONE : shorten(sessionId);
        String mode = pending.getMode() == null ? NONE : pending.getMode().name().toLowerCase();
        return session + " \u00b7 模式 " + mode;
    }

    /**
     * 缩短会话标识：只留前 8 位，让人能对上号又不占满一行。
     *
     * @param sessionId 会话标识
     * @return 缩短后的标识
     */
    private static String shorten(String sessionId) {
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8) + "\u2026";
    }

    /**
     * 过滤控制字符。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 过滤后的文本，可为 {@code null}
     */
    private static String text(String text) {
        return ControlChars.strip(text);
    }

    /**
     * 把标签补齐到固定列宽，使各字段的值左对齐。
     *
     * @param label 标签文本
     * @return 补齐后的前缀，保证非 {@code null}
     */
    private static String pad(String label) {
        int labelWidth = DisplayWidth.of(label);
        return " " + label + spaces(Math.max(1, LABEL_COLUMNS - 1 - labelWidth));
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
