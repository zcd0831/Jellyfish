package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code run_finished} 的载荷：一个子代理 run 到达终态。
 * <p>
 * <b>为什么被拒的 run 也发它</b>：并发池满时 run 没有起跑就被拒（只落终态、没有 {@code run_started}）。
 * 若不发这条，客户端见到的那次「提交」就永远悬着——它无从判断「还没开始」与「已经被拒」。
 * 因此消费方必须以 {@code runId} 配对，而不能假定每条 {@code run_finished} 都有前置的
 * {@code run_started}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class RunFinishedEvent {

    /** run 标识。 */
    private final String runId;

    /** 父 run 标识，可为 {@code null}。 */
    private final String parentRunId;

    /** 树根 run 标识。 */
    private final String rootRunId;

    /** 父会话标识（即本 SSE 流所属的会话）。 */
    private final String parentSessionId;

    /** agent 标识。 */
    private final String agentId;

    /** 本 run 的子会话标识。 */
    private final String sessionId;

    /** 终态名，见 {@code AgentRunStatus}。 */
    private final String status;

    /** 已完成的轮数。 */
    private final int rounds;

    /** 累计 token 用量。 */
    private final long totalTokens;

    /** 开始时刻（epoch 毫秒）。 */
    private final long startedAt;

    /** 进入终态的时刻（epoch 毫秒）。 */
    private final long finishedAt;

    /**
     * 构造事件。
     *
     * @param runId           run 标识
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       树根 run 标识
     * @param parentSessionId 父会话标识
     * @param agentId         agent 标识
     * @param sessionId       本 run 的子会话标识
     * @param status          终态名
     * @param rounds          已完成轮数
     * @param totalTokens     累计 token 用量
     * @param startedAt       开始时刻（epoch 毫秒）
     * @param finishedAt      进入终态的时刻（epoch 毫秒）
     */
    public RunFinishedEvent(String runId, String parentRunId, String rootRunId, String parentSessionId,
                            String agentId, String sessionId, String status, int rounds, long totalTokens,
                            long startedAt, long finishedAt) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.status = status;
        this.rounds = rounds;
        this.totalTokens = totalTokens;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
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
     * 获取本 run 的子会话标识。
     *
     * @return 子会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取终态名。
     *
     * @return 终态名，见 {@code AgentRunStatus}
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取已完成轮数。
     *
     * @return 轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取累计 token 用量。
     *
     * @return token 用量
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取开始时刻。
     *
     * @return 开始时刻（epoch 毫秒）
     */
    public long getStartedAt() {
        return startedAt;
    }

    /**
     * 获取进入终态的时刻。
     *
     * @return 终态时刻（epoch 毫秒）
     */
    public long getFinishedAt() {
        return finishedAt;
    }
}
