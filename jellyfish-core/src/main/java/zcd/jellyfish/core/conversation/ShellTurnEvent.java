package zcd.jellyfish.core.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可靠 lane 上的一条外壳事件：内核回合（或输入指令）在推进过程中发出的全部内容。
 * <p>
 * <b>为什么是「一个类 + 判别式 kind」而不是一堆积类</b>：外壳要对它做 {@code switch}，而
 * 新增一种事件不应该要求每个外壳都改一遍接口实现（那正是 {@code ReActListener} 的痛点，
 * 它靠 default 方法才勉强向后兼容）。判别式字段把「加了什么事件」变成一个编译期可查的 {@code switch}
 * 缺失分支，与 {@link Submission}、{@code CommandResult} 同一口径。
 * <p>
 * <b>它承载什么、不承载什么</b>：
 * <ul>
 *     <li><b>承载</b>回合的正文 / 思考增量、工具轨迹、四条终态；</li>
 *     <li><b>不承载审批</b>：审批是<b>拉取式</b>的（外壳每帧读 {@code ApprovalChannel.pending(sessionId)}，
 *     Server 在 SSE 循环里同步头槽位）。理由是审批需要「当前是否还挂着一件」这样的状态语义，
 *     而事件是过去式的，用它表达状态会引入两套真源。P1 已把审批槽位按会话隔离，拉取已经足够。</li>
 * </ul>
 * <p>
 * <b>线程语义</b>：由内核在发布线程上同步交给订阅者——回合事件来自 {@code react} / {@code llm-stream}
 * 线程，工具实时输出来自工具自己的输出泵线程（stdout / stderr 两条，会并发）。
 * 因此订阅者必须<b>快且线程安全</b>，只允许写入自己的线程安全缓冲，不得做 I/O。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ShellTurnEvent {

    /** 事件种类。 */
    public enum Kind {

        /** 回合已经开始（本回合的第一条事件）。 */
        STARTED,

        /** 模型文本增量。 */
        TEXT,

        /** 模型思考过程增量。 */
        THINKING,

        /** 一次工具调用开始（携带模型给出的参数）。 */
        TOOL_STARTED,

        /** 工具执行期的实时输出片段（可丢，只服务「让用户看到进展」）。 */
        TOOL_OUTPUT,

        /** 一次工具调用结束（成功或失败都会来）。 */
        TOOL_COMPLETED,

        /** 回合在开始前被插件拦下（用户消息根本没有进会话）。 */
        BLOCKED,

        /** 回合正常收敛（含达到最大轮次）。 */
        COMPLETED,

        /** 回合被调用方取消。 */
        CANCELLED,

        /** 回合因异常终止。 */
        ERROR
    }

    /** 落点。 */
    private final Kind kind;

    /** 会话标识。 */
    private final String sessionId;

    /** 回合标识（内核生成；输入指令用自己的执行标识）。 */
    private final String turnId;

    /** 文本增量 / 工具输出片段 / 收敛时的最终正文；其余种类为 {@code null}。 */
    private final String text;

    /** 工具调用标识；仅工具类事件非 {@code null}。 */
    private final String toolCallId;

    /** 工具名；仅工具类事件非 {@code null}。 */
    private final String toolName;

    /** 工具参数（模型生成的不可信输入）；仅 {@link Kind#TOOL_STARTED} 非 {@code null}。 */
    private final Map<String, Object> toolArguments;

    /** 工具是否成功；仅 {@link Kind#TOOL_COMPLETED} 有意义。 */
    private final boolean success;

    /** 工具结果文本（失败时为错误说明）；仅 {@link Kind#TOOL_COMPLETED} 非 {@code null}。 */
    private final String output;

    /** 工具结果元数据；保证非 {@code null}（无元数据时为空映射）。 */
    private final Map<String, Object> metadata;

    /** 收敛时的轮数；仅 {@link Kind#COMPLETED} 有意义。 */
    private final int rounds;

    /** 是否因达到最大轮次而未收敛；仅 {@link Kind#COMPLETED} 有意义。 */
    private final boolean truncated;

    /** 被拦下的理由；仅 {@link Kind#BLOCKED} 可能非 {@code null}。 */
    private final String reason;

    /** 失败原因；仅 {@link Kind#ERROR} 可能非 {@code null}。 */
    private final Throwable error;

    /**
     * 构造事件。
     *
     * @param kind          落点，不可为 {@code null}
     * @param sessionId     会话标识，可为 {@code null}
     * @param turnId        回合标识，可为 {@code null}
     * @param text          文本，可为 {@code null}
     * @param toolCallId    工具调用标识，可为 {@code null}
     * @param toolName      工具名，可为 {@code null}
     * @param toolArguments 工具参数，可为 {@code null}
     * @param success       工具是否成功
     * @param output        工具结果文本，可为 {@code null}
     * @param metadata      工具结果元数据，可为 {@code null}
     * @param rounds        轮数
     * @param truncated     是否未收敛
     * @param reason        拦下理由，可为 {@code null}
     * @param error         失败原因，可为 {@code null}
     */
    private ShellTurnEvent(Kind kind, String sessionId, String turnId, String text, String toolCallId,
                           String toolName, Map<String, Object> toolArguments, boolean success, String output,
                           Map<String, Object> metadata, int rounds, boolean truncated, String reason,
                           Throwable error) {
        this.kind = kind;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.text = text;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolArguments = toolArguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(toolArguments));
        this.success = success;
        this.output = output;
        this.metadata = metadata == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.rounds = rounds;
        this.truncated = truncated;
        this.reason = reason;
        this.error = error;
    }

    /**
     * 构造「回合已开始」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent started(String sessionId, String turnId) {
        return new ShellTurnEvent(Kind.STARTED, sessionId, turnId, null, null, null, null, false, null, null,
                0, false, null, null);
    }

    /**
     * 构造「文本增量」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @param delta     增量文本
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent text(String sessionId, String turnId, String delta) {
        return new ShellTurnEvent(Kind.TEXT, sessionId, turnId, delta, null, null, null, false, null, null,
                0, false, null, null);
    }

    /**
     * 构造「思考过程增量」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @param delta     增量文本
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent thinking(String sessionId, String turnId, String delta) {
        return new ShellTurnEvent(Kind.THINKING, sessionId, turnId, delta, null, null, null, false, null, null,
                0, false, null, null);
    }

    /**
     * 构造「工具调用开始」。
     *
     * @param sessionId  会话标识
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param arguments  工具参数，可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent toolStarted(String sessionId, String turnId, String toolCallId,
                                             String toolName, Map<String, Object> arguments) {
        return new ShellTurnEvent(Kind.TOOL_STARTED, sessionId, turnId, null, toolCallId, toolName, arguments,
                false, null, null, 0, false, null, null);
    }

    /**
     * 构造「工具执行期输出」。
     *
     * @param sessionId  会话标识
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param chunk      输出片段
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent toolOutput(String sessionId, String turnId, String toolCallId,
                                            String toolName, String chunk) {
        return new ShellTurnEvent(Kind.TOOL_OUTPUT, sessionId, turnId, chunk, toolCallId, toolName, null,
                false, null, null, 0, false, null, null);
    }

    /**
     * 构造「工具调用结束」。
     *
     * @param sessionId  会话标识
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param success    是否成功
     * @param output     结果文本，可为 {@code null}
     * @param metadata   结果元数据，可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent toolCompleted(String sessionId, String turnId, String toolCallId,
                                               String toolName, boolean success, String output,
                                               Map<String, Object> metadata) {
        return new ShellTurnEvent(Kind.TOOL_COMPLETED, sessionId, turnId, null, toolCallId, toolName, null,
                success, output, metadata, 0, false, null, null);
    }

    /**
     * 构造「回合被拦下」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @param reason    拦下理由，可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent blocked(String sessionId, String turnId, String reason) {
        return new ShellTurnEvent(Kind.BLOCKED, sessionId, turnId, null, null, null, null, false, null, null,
                0, false, reason, null);
    }

    /**
     * 构造「回合收敛」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @param content   最终正文，可为 {@code null}
     * @param rounds    轮数
     * @param truncated 是否因达到最大轮次而未收敛
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent completed(String sessionId, String turnId, String content, int rounds,
                                           boolean truncated) {
        return new ShellTurnEvent(Kind.COMPLETED, sessionId, turnId, content, null, null, null, false, null,
                null, rounds, truncated, null, null);
    }

    /**
     * 构造「回合被取消」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent cancelled(String sessionId, String turnId) {
        return new ShellTurnEvent(Kind.CANCELLED, sessionId, turnId, null, null, null, null, false, null, null,
                0, false, null, null);
    }

    /**
     * 构造「回合失败」。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识
     * @param error     失败原因，可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static ShellTurnEvent error(String sessionId, String turnId, Throwable error) {
        return new ShellTurnEvent(Kind.ERROR, sessionId, turnId, null, null, null, null, false, null, null,
                0, false, null, error);
    }

    /**
     * 判断本事件是否为本回合的终态。
     * <p>
     * 四条终态（拦下 / 收敛 / 取消 / 异常）<b>互斥且恰好来一条</b>，因此外壳只需在终态上收尾
     * （清暂存区、解闩锁、关订阅）。
     *
     * @return 终态返回 {@code true}
     */
    public boolean isTerminal() {
        return kind == Kind.BLOCKED || kind == Kind.COMPLETED || kind == Kind.CANCELLED || kind == Kind.ERROR;
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
     * 获取会话标识。
     *
     * @return 会话标识，可能为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取回合标识。
     *
     * @return 回合标识，可能为 {@code null}
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * 获取文本。
     *
     * @return 文本（增量、片段或最终正文），可能为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取工具调用标识。
     *
     * @return 工具调用标识，可能为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，可能为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 不可变参数映射，保证非 {@code null}
     */
    public Map<String, Object> getToolArguments() {
        return toolArguments;
    }

    /**
     * 获取工具是否成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取工具结果文本。
     *
     * @return 结果文本，可能为 {@code null}
     */
    public String getOutput() {
        return output;
    }

    /**
     * 获取工具结果元数据。
     *
     * @return 不可变元数据映射，保证非 {@code null}
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取轮数。
     *
     * @return 轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 判断是否未收敛。
     *
     * @return 达到最大轮次返回 {@code true}
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 获取拦下理由。
     *
     * @return 理由，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取失败原因。
     *
     * @return 失败原因，可能为 {@code null}
     */
    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return "ShellTurnEvent{" + kind + ", sessionId=" + sessionId + ", turnId=" + turnId + '}';
    }
}
