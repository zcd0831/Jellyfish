package zcd.jellyfish.core.runtime;

import zcd.jellyfish.infra.session.SessionUsage;

/**
 * 一个 agent run 的终态结果：状态、最终文本、轮数、用量与失败原因。
 * <p>
 * <b>为什么不直接复用 {@code SubAgentOutcome}</b>：{@code core.runtime} 不能反向依赖
 * {@code core.subagent}（依赖方向是 {@code core.subagent → core.runtime}）。运行时需要在
 * 「与工具结果如何渲染」无关的层面上表达一次 run 的结果，因此这里是一个自带视角的不可变值类型；
 * 把它翻译成 {@code SubAgentOutcome}（含「截断时附最后一段正文」这类展示策略）是适配层的职责。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgentRunResult {

    /** 终态。 */
    private final AgentRunStatus status;

    /** 最终文本；取消与失败时通常为 {@code null}。 */
    private final String text;

    /** 实际发生的轮数。 */
    private final int rounds;

    /** 累计用量，保证非 {@code null}。 */
    private final SessionUsage usage;

    /** 失败原因；成功时为 {@code null}。 */
    private final String error;

    /**
     * 构造结果。
     *
     * @param status 终态
     * @param text   最终文本，可为 {@code null}
     * @param rounds 实际轮数
     * @param usage  累计用量，可为 {@code null}（按零用量处理）
     * @param error  失败原因，可为 {@code null}
     */
    private AgentRunResult(AgentRunStatus status, String text, int rounds, SessionUsage usage, String error) {
        this.status = status;
        this.text = text;
        this.rounds = rounds;
        this.usage = usage == null ? SessionUsage.EMPTY : usage;
        this.error = error;
    }

    /**
     * 按给定终态构造结果。
     *
     * @param status 终态，不可为 {@code null}
     * @param text   最终文本，可为 {@code null}
     * @param rounds 实际轮数
     * @param usage  累计用量，可为 {@code null}
     * @param error  失败原因，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static AgentRunResult of(AgentRunStatus status, String text, int rounds, SessionUsage usage,
                                    String error) {
        return new AgentRunResult(status, text, rounds, usage, error);
    }

    /**
     * 构造「执行失败」结果。
     *
     * @param error 失败原因，可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    public static AgentRunResult failed(String error) {
        return new AgentRunResult(AgentRunStatus.FAILED, null, 0, null, error);
    }

    /**
     * 把一次已结束的 run 结果改标为截断（例如墙钟到点）。
     * <p>
     * 只在调用方判定「这不是正常收敛」时使用；正文、轮数与用量原样保留。
     *
     * @param error 截断原因，可为 {@code null}
     * @return 截断结果，保证非 {@code null}
     */
    public AgentRunResult asTruncated(String error) {
        return new AgentRunResult(AgentRunStatus.TRUNCATED, text, rounds, usage, error);
    }

    /**
     * 获取终态。
     *
     * @return 终态
     */
    public AgentRunStatus getStatus() {
        return status;
    }

    /**
     * 获取最终文本。
     *
     * @return 最终文本，可为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取实际轮数。
     *
     * @return 实际轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取累计用量。
     *
     * @return 累计用量，保证非 {@code null}
     */
    public SessionUsage getUsage() {
        return usage;
    }

    /**
     * 获取失败原因。
     *
     * @return 失败原因，成功时为 {@code null}
     */
    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "AgentRunResult{status=" + status + ", rounds=" + rounds + '}';
    }
}
