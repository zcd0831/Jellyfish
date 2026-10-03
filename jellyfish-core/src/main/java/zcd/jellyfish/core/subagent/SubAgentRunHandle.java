package zcd.jellyfish.core.subagent;

import zcd.jellyfish.core.runtime.AgentRunHandle;
import zcd.jellyfish.infra.session.Session;

/**
 * 一次已派生的委派：等待与收尾所需的全部信息。
 * <p>
 * <b>为什么把「派生」与「等待」拆开</b>：并发扇出要求「先全部派生、再逐个等」。若派生自己就阻塞，
 * 扇出会退化成串行——而串行正是编排最不该有的性质。拆开之后，同一份准入与收尾逻辑可以同时服务
 * 两条调用路径：
 * <ul>
 *     <li>{@code task} 工具：{@code await(spawn(...))}——对外语义与拆分前逐字段一致；</li>
 *     <li>面向插件的委派端口（P2）：连发 N 个 {@code spawn}，再逐个 {@code await}。</li>
 * </ul>
 * <p>
 * <b>它可能已经「有结果」</b>：准入被拒、或建会话 / 派生途中失败时，句柄直接带着终态结果返回，
 * 此时 {@link #getRunHandle()} 为 {@code null}。这让两条调用路径不需要为「早失败」各写一条分支——
 * 一律 {@code spawn → await}。
 * <p>
 * <b>{@code await} 的幂等由句柄承担</b>：收尾（归集用量、归档、关会话、摘登记）只能发生一次，
 * 因此结果在句柄上缓存；重复等待返回同一个结果而不是第二次收尾。
 * <p>
 * 单线程使用：一个句柄只应由派生它的那条线程等待。
 *
 * @author zcd
 */
public final class SubAgentRunHandle {

    /** 父会话标识；准入被拒时为 {@code null}。 */
    private final String parentSessionId;

    /** 子会话运行态；从未创建时为 {@code null}。 */
    private final Session child;

    /** run 标识；未登记成功时为 {@code null}。 */
    private final String runId;

    /** 运行时句柄；被拒或派生失败时为 {@code null}。 */
    private final AgentRunHandle runHandle;

    /** 早失败 / 早拒绝时携带的终态结果；正常待等时为 {@code null}。 */
    private final SubAgentOutcome settled;

    /** 首次等待的结果，用于让 {@link SubAgentLauncher#await(SubAgentRunHandle)} 幂等。 */
    private SubAgentOutcome outcome;

    /**
     * 构造句柄。
     *
     * @param parentSessionId 父会话标识，可为 {@code null}
     * @param child           子会话运行态，可为 {@code null}
     * @param runId           run 标识，可为 {@code null}
     * @param runHandle       运行时句柄，可为 {@code null}
     * @param settled         早失败 / 早拒绝的终态结果，可为 {@code null}
     */
    private SubAgentRunHandle(String parentSessionId, Session child, String runId, AgentRunHandle runHandle,
                              SubAgentOutcome settled) {
        this.parentSessionId = parentSessionId;
        this.child = child;
        this.runId = runId;
        this.runHandle = runHandle;
        this.settled = settled;
    }

    /**
     * 构造一个已派生的句柄。
     *
     * @param parentSessionId 父会话标识
     * @param child           子会话运行态
     * @param runHandle       运行时句柄，不可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    static SubAgentRunHandle of(String parentSessionId, Session child, AgentRunHandle runHandle) {
        return new SubAgentRunHandle(parentSessionId, child, runHandle.getRunId(), runHandle, null);
    }

    /**
     * 构造一个「已带终态结果」的句柄：准入被拒，没有任何副作用需要收尾。
     *
     * @param outcome 终态结果，不可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    static SubAgentRunHandle settled(SubAgentOutcome outcome) {
        return new SubAgentRunHandle(null, null, null, null, outcome);
    }

    /**
     * 构造一个「派生途中失败」的句柄：可能已经建了子会话、甚至已经登记了 run，
     * 因此必须走与正常路径相同的收尾出口。
     *
     * @param parentSessionId 父会话标识，可为 {@code null}
     * @param child           子会话运行态，可为 {@code null}
     * @param runId           run 标识，可为 {@code null}
     * @param outcome         终态结果，不可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    static SubAgentRunHandle failed(String parentSessionId, Session child, String runId,
                                    SubAgentOutcome outcome) {
        return new SubAgentRunHandle(parentSessionId, child, runId, null, outcome);
    }

    /**
     * 获取父会话标识。
     *
     * @return 父会话标识，可能为 {@code null}
     */
    String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取子会话运行态。
     *
     * @return 子会话，可能为 {@code null}
     */
    Session getChild() {
        return child;
    }

    /**
     * 获取 run 标识。
     *
     * @return run 标识，可能为 {@code null}
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取运行时句柄。
     *
     * @return 运行时句柄；被拒或派生失败时为 {@code null}
     */
    AgentRunHandle getRunHandle() {
        return runHandle;
    }

    /**
     * 获取早失败 / 早拒绝携带的终态结果。
     *
     * @return 终态结果；正常待等时为 {@code null}
     */
    SubAgentOutcome getSettled() {
        return settled;
    }

    /**
     * 判断这个句柄是否已经可以立即给出结果（没有真正在跑的 run）。
     *
     * @return 没有在跑的 run 时返回 {@code true}
     */
    public boolean isSettled() {
        return runHandle == null;
    }

    /**
     * 取消这个 run（幂等）。
     * <p>
     * 已经带终态结果的句柄上没有可取消的东西：那是「还没开始就已经结束」。
     */
    public void cancel() {
        if (runHandle != null) {
            runHandle.cancel();
        }
    }

    /**
     * 取已缓存的等待结果。
     *
     * @return 结果；尚未等待过时为 {@code null}
     */
    SubAgentOutcome getOutcome() {
        return outcome;
    }

    /**
     * 缓存等待结果，使重复等待不再触发第二次收尾。
     *
     * @param value 结果，不可为 {@code null}
     */
    void settle(SubAgentOutcome value) {
        this.outcome = value;
    }

    @Override
    public String toString() {
        return "SubAgentRunHandle{runId=" + runId + ", settled=" + (settled != null) + '}';
    }
}
