package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.AbstractJellyfishEvent;
import zcd.jellyfish.api.subagent.DelegationStatus;

/**
 * agent run 进展事件：一个子代理 run 开始执行、或到达终态时广播，供插件把「谁在做什么」画到自己的界面上。
 * <p>
 * <b>为什么需要它</b>：run 的开始与结束发生过很多次，但在插件这一侧完全不可见——
 * 内核的 {@code RunEventBus} 是内核自持的总线，携带的是纯内核类型。于是「任务列表里这一条现在谁在做、
 * 做完没有」只能靠内核把这两件事翻成插件看得懂的通知。
 * <p>
 * <b>为什么不直接复用内核的 run 事件类型</b>：那是 core 的类型，插件看不到；而且它会随内核实现演进
 * （加字段、加种类）。通知事件是<b>对外承诺</b>，只带插件真正用得上的那一小片：身份、结局、用量。
 * 这与 {@code DelegationResult} 不复用内核结果类型是同一条纪律。
 * <p>
 * <b>只广播两端（开始 / 结束），不广播每一步</b>：每一步的推进会带来高频流量，而它对本事件的消费方
 * （把 run 状态画到任务行上）没有增量价值。需要看过程的地方是面板与工具输出，不是通知。
 * <p>
 * <b>可丢</b>：本事件走的是内核的通知通道（异步、队列满即丢、未启动时缓冲）。
 * <b>因此消费方不能假设「收到开始就一定收到结束」</b>——展示层要给条目设过期时限并自行回落，
 * 而不是把一个丢失的结束事件变成永久显示的「正在跑」。可靠性该花在决策路径上，不是花在进度条上。
 * <p>
 * <b>{@code sessionId} 是父会话</b>（也就是「这次协作属于哪个会话」的那个键），
 * 因此订阅方可以用与其他会话级通知完全相同的过滤方式：{@code e -> mySession.equals(e.getSessionId())}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgentRunProgressEvent extends AbstractJellyfishEvent {

    /**
     * 事件种类。
     */
    public enum Kind {

        /** run 开始执行（已拿到并发许可）。 */
        STARTED,

        /** run 到达终态（含被拒未起的 run）。 */
        FINISHED
    }

    /** 事件种类。 */
    private final Kind kind;

    /** 终态分类；仅 {@link Kind#FINISHED} 非 {@code null}。 */
    private final DelegationStatus outcome;

    /** run 标识，与任务列表里记的负责人是同一个键。 */
    private final String runId;

    /** 父 run 标识；顶层回合直接发起的 run 为 {@code null}。 */
    private final String parentRunId;

    /** 所属 run 树的根标识。 */
    private final String rootRunId;

    /** 子代理类型。 */
    private final String agentId;

    /** 已完成轮数；{@link Kind#STARTED} 时恒为 {@code 0}。 */
    private final int rounds;

    /** 累计 token 用量；{@link Kind#STARTED} 时恒为 {@code 0}。 */
    private final long totalTokens;

    /**
     * 构造事件。
     *
     * @param kind            事件种类，不可为 {@code null}
     * @param outcome         终态分类；{@link Kind#STARTED} 时必须为 {@code null}
     * @param runId           run 标识，不可为空白
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       所属 run 树的根标识，可为 {@code null}
     * @param parentSessionId 父会话标识，可为 {@code null}
     * @param agentId         子代理类型，可为 {@code null}
     * @param rounds          已完成轮数
     * @param totalTokens     累计 token 用量
     * @throws JellyfishException run 标识为空白时抛出
     */
    private AgentRunProgressEvent(Kind kind, DelegationStatus outcome, String runId, String parentRunId,
                                 String rootRunId, String parentSessionId, String agentId,
                                 int rounds, long totalTokens) {
        super(parentSessionId);
        if (runId == null || runId.trim().isEmpty()) {
            throw new JellyfishException("agent run id must not be blank");
        }
        this.kind = kind;
        this.outcome = outcome;
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.agentId = agentId;
        this.rounds = rounds;
        this.totalTokens = totalTokens;
    }

    /**
     * 构造「run 已开始」事件。
     *
     * @param runId           run 标识，不可为空白
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       所属 run 树的根标识，可为 {@code null}
     * @param parentSessionId 父会话标识，可为 {@code null}
     * @param agentId         子代理类型，可为 {@code null}
     * @return 事件，保证非 {@code null}
     * @throws JellyfishException run 标识为空白时抛出
     */
    public static AgentRunProgressEvent started(String runId, String parentRunId, String rootRunId,
                                                String parentSessionId, String agentId) {
        return new AgentRunProgressEvent(Kind.STARTED, null, runId, parentRunId, rootRunId,
                parentSessionId, agentId, 0, 0L);
    }

    /**
     * 构造「run 已结束」事件。
     *
     * @param runId           run 标识，不可为空白
     * @param parentRunId     父 run 标识，可为 {@code null}
     * @param rootRunId       所属 run 树的根标识，可为 {@code null}
     * @param parentSessionId 父会话标识，可为 {@code null}
     * @param agentId         子代理类型，可为 {@code null}
     * @param outcome         终态分类，不可为 {@code null}
     * @param rounds          已完成轮数
     * @param totalTokens     累计 token 用量
     * @return 事件，保证非 {@code null}
     * @throws JellyfishException run 标识为空白或终态分类为 {@code null} 时抛出
     */
    public static AgentRunProgressEvent finished(String runId, String parentRunId, String rootRunId,
                                                 String parentSessionId, String agentId,
                                                 DelegationStatus outcome, int rounds, long totalTokens) {
        if (outcome == null) {
            throw new JellyfishException("finished agent run event requires an outcome: runId=" + runId);
        }
        return new AgentRunProgressEvent(Kind.FINISHED, outcome, runId, parentRunId, rootRunId,
                parentSessionId, agentId, rounds, totalTokens);
    }

    /**
     * 获取事件种类。
     *
     * @return 事件种类，保证非 {@code null}
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取终态分类。
     *
     * @return 终态分类；{@link Kind#STARTED} 时返回 {@code null}
     */
    public DelegationStatus getOutcome() {
        return outcome;
    }

    /**
     * 获取 run 标识。
     *
     * @return run 标识，保证非空白
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取父 run 标识。
     *
     * @return 父 run 标识；本 run 就是树根时为 {@code null}
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * 获取所属 run 树的根标识。
     *
     * @return 根 run 标识，可能为 {@code null}
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * 获取父会话标识，等价于 {@link #getSessionId()}。
     * <p>
     * 之所以是父会话：本事件讲的是「这次协作里某个子代理在动」，
     * 而订阅方关心的粒度就是这次协作。
     *
     * @return 父会话标识，可能为 {@code null}
     */
    public String getParentSessionId() {
        return getSessionId();
    }

    /**
     * 获取子代理类型。
     *
     * @return 子代理类型，可能为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取已完成轮数。
     *
     * @return 轮数；{@link Kind#STARTED} 时恒为 {@code 0}
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 获取累计 token 用量。
     *
     * @return token 用量；{@link Kind#STARTED} 时恒为 {@code 0}
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 判断是否已结束。
     *
     * @return 到达终态返回 {@code true}
     */
    public boolean isFinished() {
        return kind == Kind.FINISHED;
    }
}
