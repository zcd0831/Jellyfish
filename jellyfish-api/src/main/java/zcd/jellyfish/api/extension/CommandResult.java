package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令执行结果：三态 + 可渲染文本 + 可选的候选值清单。
 * <p>
 * {@code UNKNOWN} 与 {@code ERROR} 刻意分开：前者是「没有这条命令」（外壳可以提示 {@code /help}，
 * 也可以自行决定要不要把原输入交给 LLM），后者是「命令存在但这次没成」。
 * <p>
 * <b>只承载给人看的文本</b>：命令的副作用（切模型、建会话……）落在对应的域服务里，
 * 需要机器可读结果的调用方在命令返回后去读那个域服务（例如当前会话问 {@code SessionManager}），
 * 而不是从这里抠字段。这样外壳是 CLI、TUI 还是 Web 都不需要从文本里反解状态。
 * <p>
 * <b>候选值是唯一的例外</b>：{@code /agent} 这类命令不带参数时需要用户从若干取值里挑一个，
 * 而「有哪些取值」只有命令自己知道。因此允许命令附带 {@link CommandChoice} 清单，
 * 让 TUI / Web 渲染选择页；不认识候选的外壳（如 CLI）忽略它、照旧打印 {@link #getOutput()} 即可。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandResult {

    /** 结果三态。 */
    public enum Kind {

        /** 命令已执行。 */
        OK,

        /** 命令存在，但执行失败。 */
        ERROR,

        /** 没有这条命令（或输入在语法上不是命令）。 */
        UNKNOWN
    }

    /** 结果状态。 */
    private final Kind kind;

    /** 可渲染文本，命令只做副作用时为 {@code null}。 */
    private final String output;

    /** 候选值清单，无候选时为空列表。 */
    private final List<CommandChoice> choices;

    /**
     * 构造命令结果。
     *
     * @param kind   结果状态，不可为 {@code null}
     * @param output 可渲染文本，可为 {@code null}
     * @param choices 候选值清单，可为 {@code null}（等价空列表）
     */
    private CommandResult(Kind kind, String output, List<CommandChoice> choices) {
        this.kind = kind;
        this.output = output;
        this.choices = copyChoices(choices);
    }

    /**
     * 构造「已执行」结果。
     *
     * @param output 可渲染文本，可为 {@code null}（命令只做副作用，无输出）
     * @return 已执行结果
     */
    public static CommandResult ok(String output) {
        return new CommandResult(Kind.OK, output, null);
    }

    /**
     * 构造「已执行且需要用户选择」结果。
     * <p>
     * 同时给出文本与候选：文本给不认识候选的外壳（CLI 直接打印），候选给能渲染选择页的外壳。
     *
     * @param output  可渲染文本，可为 {@code null}
     * @param choices 候选值清单，可为 {@code null} 或空（等价于普通 {@link #ok(String)}）
     * @return 带候选的已执行结果
     */
    public static CommandResult choices(String output, List<CommandChoice> choices) {
        return new CommandResult(Kind.OK, output, choices);
    }

    /**
     * 构造「执行失败」结果。
     *
     * @param output 失败说明，可为 {@code null}
     * @return 失败结果
     */
    public static CommandResult error(String output) {
        return new CommandResult(Kind.ERROR, output, null);
    }

    /**
     * 构造「没有这条命令」结果。
     *
     * @param output 提示文案（例如建议输入 {@code /help}），可为 {@code null}
     * @return 未命中结果
     */
    public static CommandResult unknown(String output) {
        return new CommandResult(Kind.UNKNOWN, output, null);
    }

    /**
     * 获取结果状态。
     *
     * @return 结果状态
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取可渲染文本。
     *
     * @return 可渲染文本，可能为 {@code null}
     */
    public String getOutput() {
        return output;
    }

    /**
     * 获取候选值清单。
     *
     * @return 不可变候选值清单，保证非 {@code null}；无候选时为空列表
     */
    public List<CommandChoice> getChoices() {
        return choices;
    }

    /**
     * 判断是否带有候选值。
     *
     * @return 有候选返回 {@code true}
     */
    public boolean hasChoices() {
        return !choices.isEmpty();
    }

    /**
     * 判断是否为失败面（{@code ERROR} 或 {@code UNKNOWN}）。
     * <p>
     * 供外壳选择输出流与样式：它不区分「拼错命令」与「命令报错」，那由 {@link #getKind()} 决定。
     *
     * @return {@code ERROR} 或 {@code UNKNOWN} 返回 {@code true}
     */
    public boolean isError() {
        return kind != Kind.OK;
    }

    @Override
    public String toString() {
        // 刻意不打印 output：命令输出可能是整篇帮助文本，混进日志行会很难看
        return "CommandResult{kind=" + kind + '}';
    }

    /**
     * 复制候选清单并拒绝 {@code null} 元素。
     *
     * @param choices 原始候选清单，可为 {@code null}
     * @return 不可变副本，保证非 {@code null}
     */
    private static List<CommandChoice> copyChoices(List<CommandChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return Collections.emptyList();
        }
        List<CommandChoice> copy = new ArrayList<CommandChoice>(choices.size());
        for (CommandChoice choice : choices) {
            if (choice == null) {
                throw new JellyfishException("command choice must not be null");
            }
            copy.add(choice);
        }
        return Collections.unmodifiableList(copy);
    }
}
