package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令执行结果：三态 + 可渲染文本 + 可选的候选值清单与接力文本。
 * <p>
 * {@code UNKNOWN} 与 {@code ERROR} 刻意分开：前者是「没有这条命令」（外壳可以提示 {@code /help}，
 * 也可以自行决定要不要把原输入交给 LLM），后者是「命令存在但这次没成」。
 * <p>
 * <b>只承载给人看的文本</b>：命令的副作用（切模型、建会话……）落在对应的域服务里，
 * 需要机器可读结果的调用方在命令返回后去读那个域服务（例如当前会话问 {@code SessionManager}），
 * 而不是从这里抠字段。这样外壳是 CLI、TUI 还是 Web 都不需要从文本里反解状态。
 * <p>
 * <b>结构化负载只有两处例外</b>，两者都是「只有命令自己知道、别处问不出来」的事实：
 * <ul>
 *     <li><b>候选值</b>：{@code /agent} 这类命令不带参数时需要用户从若干取值里挑一个，
 *     因此允许附带 {@link CommandChoice} 清单，让 TUI / Web 渲染选择页；
 *     不认识候选的外壳（如 CLI）忽略它、照旧打印 {@link #getOutput()} 即可。</li>
 *     <li><b>接力文本</b>（见 {@link #handoff(String)}）：命令声明「这次输入还没完，
 *     请把这段文本当作用户输入接着跑」。它进的是提交管线，不是界面。</li>
 * </ul>
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

    /** 接力文本，非接力命令为 {@code null}。 */
    private final String handoff;

    /**
     * 构造命令结果。
     *
     * @param kind    结果状态，不可为 {@code null}
     * @param output  可渲染文本，可为 {@code null}
     * @param choices 候选值清单，可为 {@code null}（等价空列表）
     * @param handoff 接力文本，可为 {@code null}
     */
    private CommandResult(Kind kind, String output, List<CommandChoice> choices, String handoff) {
        this.kind = kind;
        this.output = output;
        this.choices = copyChoices(choices);
        this.handoff = handoff;
    }

    /**
     * 构造「已执行」结果。
     *
     * @param output 可渲染文本，可为 {@code null}（命令只做副作用，无输出）
     * @return 已执行结果
     */
    public static CommandResult ok(String output) {
        return new CommandResult(Kind.OK, output, null, null);
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
        return new CommandResult(Kind.OK, output, choices, null);
    }

    /**
     * 构造「接力」结果：命令声明把这段文本当作用户输入继续走提交管线。
     * <p>
     * <b>它只声明，不执行</b>：命令处理器仍然碰不到模型，也起不了回合——接力文本会被交回
     * {@code ConversationService} 的分流点，用它替换掉本次的用户输入，后面的输入改写、
     * 输入指令解析、建会话与起回合<b>全部走与用户手敲时逐字段一致的那条路</b>。
     * 于是回合边界仍归外壳（是它发起的那一次提交里继续），而命令获得了一种
     * 「替用户说一句话」的表达力。
     * <p>
     * <b>典型用法是「初始化」类命令</b>：{@code /init} 把一份「阅读本仓库、写出约定文件」的指令
     * 交给模型，随后的读写工具调用照旧过完整的权限与审批链——命令没有借此取得任何新权限。
     * <p>
     * <b>没有 output</b>：这两个字段刻意互斥。接力之后屏幕上该出现的是「接力文本被当作你的输入」
     * 与模型的回答，再叠一句命令自己的输出只会让人分不清哪句是模型说的、哪句是命令说的；
     * 命令若确实想留一句反馈，走 {@code PluginContext.present}（那是内核为「回合之外说话」留的通道）。
     *
     * @param text 接力文本，不可为空白
     * @return 接力结果，保证非 {@code null}
     * @throws JellyfishException 文本为空白时抛出
     */
    public static CommandResult handoff(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new JellyfishException("command handoff text must not be blank");
        }
        return new CommandResult(Kind.OK, null, null, text);
    }

    /**
     * 构造「执行失败」结果。
     *
     * @param output 失败说明，可为 {@code null}
     * @return 失败结果
     */
    public static CommandResult error(String output) {
        return new CommandResult(Kind.ERROR, output, null, null);
    }

    /**
     * 构造「没有这条命令」结果。
     *
     * @param output 提示文案（例如建议输入 {@code /help}），可为 {@code null}
     * @return 未命中结果
     */
    public static CommandResult unknown(String output) {
        return new CommandResult(Kind.UNKNOWN, output, null, null);
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
     * 判断是否要求接力。
     *
     * @return 带接力文本返回 {@code true}
     */
    public boolean hasHandoff() {
        return handoff != null;
    }

    /**
     * 获取接力文本。
     * <p>
     * 调用方是提交管线的分流点（{@code ConversationService}），不是渲染层：
     * 外壳只按 {@link #getKind()} 与 {@link #getOutput()} 渲染，
     * 接力文本由内核换成用户输入后自然出现在会话里。
     *
     * @return 接力文本；非接力命令为 {@code null}
     */
    public String getHandoffText() {
        return handoff;
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
