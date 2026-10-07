package zcd.jellyfish.core.conversation;

import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.core.input.InputDirectiveRun;

/**
 * 一次用户提交的处理结果：判别式值对象，外壳只 {@code switch} 它，不再自己判顺序。
 * <p>
 * <b>它为什么是判别式的</b>：一次提交可能落进四条完全不同的路——执行了命令、被插件接过去、
 * 起了输入指令、起了 ReAct 回合，还有第五种「什么都没发生」（空输入 / 无会话）。
 * 若把这些压成「有没有输出」加几个可空字段，外壳就要靠猜（{@code output != null && turn != null}）
 * 判断自己拿到了什么，而判断错的表现各不相同（把命令结果当模型的回答渲染、把被拦下的输入当成回合）。
 * 判别式把它变成一个编译器能查的 {@code switch}。
 * <p>
 * <b>它不管渲染</b>：本类型只回答「发生了什么」，不回答「界面长什么样」。
 * 命令的候选清单怎么显示、回合的事件怎么排布仍然归外壳。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class Submission {

    /** 本次提交落进了哪条路。 */
    public enum Kind {

        /** 原文是一条命令，命令域已执行完毕（结果见 {@link #getCommandResult()}）。 */
        EXECUTED_COMMAND,

        /** 输入被插件改写链接了过去，本次不进对话（说明见 {@link #getNotice()}）。 */
        HANDLED_INPUT,

        /** 输入是一条输入指令（{@code !} / {@code @}），已在专用线程上推进（句柄见 {@link #getDirectiveRun()}）。 */
        STARTED_DIRECTIVE,

        /** 起了 ReAct 回合（句柄见 {@link #getTurnId()}）。 */
        STARTED_TURN,

        /** 什么都没起（空输入 / 无会话）。 */
        REJECTED
    }

    /** {@link Kind#REJECTED} 的原因。 */
    public enum RejectReason {

        /** 输入是空白。 */
        BLANK_INPUT,

        /** 策略要求会话必须已存在，但实际没有。 */
        NO_SESSION
    }

    /** 落点。 */
    private final Kind kind;

    /** 处理之后的会话标识：可能为本轮新建的会话；{@link RejectReason#NO_SESSION} 时为 {@code null}。 */
    private final String sessionId;

    /** 命令执行结果，仅 {@link Kind#EXECUTED_COMMAND} 非 {@code null}。 */
    private final CommandResult commandResult;

    /** 插件给出的说明，仅 {@link Kind#HANDLED_INPUT} 可能非 {@code null}。 */
    private final String notice;

    /** 输入指令句柄，仅 {@link Kind#STARTED_DIRECTIVE} 非 {@code null}。 */
    private final InputDirectiveRun directiveRun;

    /** 回合标识（内核生成），仅 {@link Kind#STARTED_TURN} 与 {@link Kind#STARTED_DIRECTIVE} 非 {@code null}。 */
    private final String turnId;

    /** 拒绝原因，仅 {@link Kind#REJECTED} 非 {@code null}。 */
    private final RejectReason rejectReason;

    /**
     * 构造结果。
     *
     * @param kind          落点，不可为 {@code null}
     * @param sessionId     处理之后的会话标识，可为 {@code null}
     * @param commandResult 命令结果，可为 {@code null}
     * @param notice        插件说明，可为 {@code null}
     * @param directiveRun  指令句柄，可为 {@code null}
     * @param turnId        回合标识，可为 {@code null}
     * @param rejectReason  拒绝原因，可为 {@code null}
     */
    private Submission(Kind kind, String sessionId, CommandResult commandResult, String notice,
                       InputDirectiveRun directiveRun, String turnId, RejectReason rejectReason) {
        this.kind = kind;
        this.sessionId = sessionId;
        this.commandResult = commandResult;
        this.notice = notice;
        this.directiveRun = directiveRun;
        this.turnId = turnId;
        this.rejectReason = rejectReason;
    }

    /**
     * 构造「命令已执行」。
     *
     * @param sessionId 会话标识，可为 {@code null}（首页上执行不依赖会话的命令）
     * @param result    命令结果，不可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static Submission command(String sessionId, CommandResult result) {
        return new Submission(Kind.EXECUTED_COMMAND, sessionId, result, null, null, null, null);
    }

    /**
     * 构造「被输入改写接过去」。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param notice    插件说明，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static Submission handled(String sessionId, String notice) {
        return new Submission(Kind.HANDLED_INPUT, sessionId, null, notice, null, null, null);
    }

    /**
     * 构造「起了输入指令」。
     *
     * @param sessionId    会话标识，不可为空白
     * @param directiveRun 指令句柄，不可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static Submission directive(String sessionId, String turnId, InputDirectiveRun directiveRun) {
        return new Submission(Kind.STARTED_DIRECTIVE, sessionId, null, null, directiveRun, turnId, null);
    }

    /**
     * 构造「起了回合」。
     *
     * @param sessionId 会话标识，不可为空白
     * @param turnId    回合标识（内核生成），不可为空白
     * @return 结果，保证非 {@code null}
     */
    public static Submission turn(String sessionId, String turnId) {
        return new Submission(Kind.STARTED_TURN, sessionId, null, null, null, turnId, null);
    }

    /**
     * 构造「被拒绝」。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param reason    拒绝原因，不可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static Submission rejected(String sessionId, RejectReason reason) {
        return new Submission(Kind.REJECTED, sessionId, null, null, null, null, reason);
    }

    /**
     * 获取落点。
     *
     * @return 落点，保证非 {@code null}
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取处理之后的会话标识。
     * <p>
     * 与调用方传入的标识可能不同：{@code CREATE_IF_NEEDED} 策略下这一轮可能刚建了一个会话，
     * 外壳应当用这个值（而不是自己那份）去读会话。
     *
     * @return 会话标识；{@link RejectReason#NO_SESSION} 时为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取命令执行结果。
     *
     * @return 命令结果；非 {@link Kind#EXECUTED_COMMAND} 时为 {@code null}
     */
    public CommandResult getCommandResult() {
        return commandResult;
    }

    /**
     * 获取插件给出的说明。
     *
     * @return 说明；未提供时为 {@code null}
     */
    public String getNotice() {
        return notice;
    }

    /**
     * 获取输入指令句柄。
     *
     * @return 指令句柄；非 {@link Kind#STARTED_DIRECTIVE} 时为 {@code null}
     */
    public InputDirectiveRun getDirectiveRun() {
        return directiveRun;
    }

    /**
     * 获取回合标识。
     *
     * @return 回合标识；非 {@link Kind#STARTED_TURN} / {@link Kind#STARTED_DIRECTIVE} 时为 {@code null}
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * 获取拒绝原因。
     *
     * @return 拒绝原因；非 {@link Kind#REJECTED} 时为 {@code null}
     */
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    @Override
    public String toString() {
        return "Submission{" + kind
                + (sessionId == null ? "" : ", sessionId=" + sessionId)
                + (rejectReason == null ? "" : ", rejectReason=" + rejectReason)
                + '}';
    }
}
