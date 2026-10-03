package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code run_started} 的载荷：一个子代理 run 开始执行。
 * <p>
 * <b>为什么 SSE 上要有它</b>：SSE 客户端在另一个进程，拉不到服务端内存里的 run 登记表；
 * 没有事件，它就只能看到 run 结束后父回合里那一行工具结果，看不到「现在有几个子代理在跑、分别是什么」。
 * <p>
 * 本事件不是终态：一条流可以在一个回合里收到多条 {@code run_started}（并发 / 串行都算），
 * 与之配对的是 {@code run_finished}，两者以 {@code runId} 对应。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class RunStartedEvent {

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

    /** 关联的工具调用标识，可为 {@code null}。 */
    private final String toolCallId;

    /** 开始时刻（epoch 毫秒）。 */
    private final long startedAt;

    /**
     * 构造事件。
     *
     * @param runId           run 标识
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       树根 run 标识
     * @param parentSessionId 父会话标识
     * @param agentId         agent 标识
     * @param sessionId       本 run 的子会话标识
     * @param toolCallId      关联的工具调用标识，可为 {@code null}
     * @param startedAt       开始时刻（epoch 毫秒）
     */
    public RunStartedEvent(String runId, String parentRunId, String rootRunId, String parentSessionId,
                           String agentId, String sessionId, String toolCallId, long startedAt) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
        this.startedAt = startedAt;
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
     * 获取关联的工具调用标识。
     *
     * @return 工具调用标识，可为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取开始时刻。
     *
     * @return 开始时刻（epoch 毫秒）
     */
    public long getStartedAt() {
        return startedAt;
    }
}
