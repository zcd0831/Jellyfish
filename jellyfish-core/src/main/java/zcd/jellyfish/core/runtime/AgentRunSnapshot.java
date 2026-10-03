package zcd.jellyfish.core.runtime;

/**
 * 一个 agent run 的只读快照：供观测与排障查询，不暴露注册表的内部可变状态。
 * <p>
 * <b>为什么是快照而不是把 {@code RunState} 交出去</b>：注册表的状态会被调度线程持续改写，
 * 把可变对象交出去等于让读方看到中间态，也会引诱读方去改它。快照与 {@code Session} 的
 * 序列化快照同一口径：读的时候拷贝一份。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgentRunSnapshot {

    /** run 标识。 */
    private final String runId;

    /** 父 run 标识，可为 {@code null}。 */
    private final String parentRunId;

    /** 树根 run 标识；它自己是根时为 {@link #runId}。 */
    private final String rootRunId;

    /** 父会话标识。 */
    private final String parentSessionId;

    /** agent 标识。 */
    private final String agentId;

    /** 本 run 的会话标识。 */
    private final String sessionId;

    /** 关联的工具调用标识，可为 {@code null}。 */
    private final String toolCallId;

    /** 当前状态。 */
    private final AgentRunStatus status;

    /** 登记时刻（epoch 毫秒）。 */
    private final long startedAt;

    /** 进入终态的时刻（epoch 毫秒）；未终结时为 0。 */
    private final long finishedAt;

    /** 已完成的轮数；未终结时为 0。 */
    private final int rounds;

    /** 累计 token 用量；未终结时为 0。 */
    private final long totalTokens;

    /**
     * 构造快照。
     *
     * @param runId           run 标识
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       树根 run 标识
     * @param parentSessionId 父会话标识
     * @param agentId         agent 标识
     * @param sessionId       本 run 会话标识
     * @param toolCallId      关联的工具调用标识，可为 {@code null}
     * @param status          当前状态
     * @param startedAt       登记时刻（epoch 毫秒）
     * @param finishedAt      进入终态的时刻，未终结时为 0
     * @param rounds          已完成轮数
     * @param totalTokens     累计 token 用量
     */
    AgentRunSnapshot(String runId, String parentRunId, String rootRunId, String parentSessionId,
                     String agentId, String sessionId, String toolCallId, AgentRunStatus status,
                     long startedAt, long finishedAt, int rounds, long totalTokens) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.rounds = rounds;
        this.totalTokens = totalTokens;
    }

    /**
     * 获取 run 标识。
     *
     * @return run 标识
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取父 run 标识。
     *
     * @return 父 run 标识，可为 {@code null}
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * 获取树根 run 标识。
     *
     * @return 树根 run 标识
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * 获取父会话标识。
     *
     * @return 父会话标识
     */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取 agent 标识。
     *
     * @return agent 标识
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取本 run 的会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取关联的工具调用标识。
     *
     * @return 工具调用标识，可为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取当前状态。
     *
     * @return 当前状态
     */
    public AgentRunStatus getStatus() {
        return status;
    }

    /**
     * 获取登记时刻。
     *
     * @return 登记时刻（epoch 毫秒）
     */
    public long getStartedAt() {
        return startedAt;
    }

    /**
     * 获取进入终态的时刻。
     *
     * @return 进入终态的时刻（epoch 毫秒）；未终结时为 0
     */
    public long getFinishedAt() {
        return finishedAt;
    }

    /**
     * 获取已完成轮数。
     *
     * @return 已完成轮数；未终结时为 0
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取累计 token 用量。
     *
     * @return 累计 token 用量；未终结时为 0
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    @Override
    public String toString() {
        return "AgentRunSnapshot{run=" + runId + ", agent=" + agentId
                + ", status=" + status + ", rounds=" + rounds + '}';
    }
}
