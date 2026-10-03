package zcd.jellyfish.core.runtime;

import zcd.jellyfish.infra.session.SessionUsage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * agent run 的登记表：谁在跑、跑到哪一步、跑出什么结果。
 * <p>
 * <b>为什么需要它</b>：今天的子代理没有独立身份——只有 {@code task} 的工具调用 id，而且随父回合结束
 * 一切都消失。要支持并行、观测与取消，必须先有「每个 run 一个稳定标识 + 一棵父子树」这件事，
 * 而这件事需要一个在 run 生命周期内持续存在的登记表。本类是那件事的唯一真源。
 * <p>
 * <b>与 {@code TurnRegistry} 不合并</b>：{@code TurnRegistry} 管的是「同一个会话同时只能有一个顶层回合」
 * （互斥），本类管的是「有哪些 run 在跑、它们是什么状态」（登记）。前者回答「该不该拒绝」，
 * 后者回答「现在发生了什么」，判据与消费者都不同，合并会让互斥语义被 run 的多个条目稀释。
 * <p>
 * <b>终态恰好一个</b>：用 {@link AtomicReference} 对状态做 CAS，重复落终态是幂等的（返回 {@code false}
 * 而不改写）。这是调度器与看门狗可能同时想终结同一个 run 时的唯一依据。
 * <p>
 * <b>结果不随条目一起回收</b>：终态条目保留在表里，直到调用方显式 {@link #remove(String)}。
 * 并行场景下父 run 要等孩子，若子 run 一终结就消失，父就取不到它的结果。
 * <p>
 * 线程安全：条目表用 {@link ConcurrentHashMap}，单条状态用 {@link AtomicReference}。
 *
 * @author zcd
 */
@Singleton
public final class RunRegistry {

    /** runId → 该 run 的可变状态。 */
    private final Map<String, RunState> runs = new ConcurrentHashMap<String, RunState>();

    /**
     * 构造空的登记表。
     */
    @Inject
    public RunRegistry() {
    }

    /**
     * 登记一个新的 run。
     * <p>
     * 起点状态是 {@link AgentRunStatus#PENDING}。父子关系由调用方（{@code AgentRuntime}）从
     * 当前执行路径的上下文推出后传入：{@code rootRunId} 为 {@code null} 表示这次 run 就是所在
     * run 树的根。
     *
     * @param request          登记输入，不可为 {@code null}
     * @param parentRunId      父 run 标识，可为 {@code null}（顶层回合直接发起）
     * @param parentRootRunId  父 run 所在树的根标识，可为 {@code null}（父即根或没有父）
     * @return 新 run 的标识，保证非空白
     */
    public String register(AgentRunRequest request, String parentRunId, String parentRootRunId) {
        String runId = UUID.randomUUID().toString();
        String effectiveRoot = parentRootRunId == null ? runId : parentRootRunId;
        runs.put(runId, new RunState(runId, parentRunId, effectiveRoot, request));
        return runId;
    }

    /**
     * 把一个 run 的取消钩子绑到登记表上。
     * <p>
     * 登记发生在句柄创建之前，因此取消钩子只能事后补绑。绑上之后，{@link #cancelTree(String)} 才能
     * 按 runId（而不是靠外层握着句柄）取消一个 run——这是「取消一棵子树」与将来的观测面板取消单个 run
     * 的共同前提。
     *
     * @param runId    run 标识
     * @param canceller 取消动作，不可为 {@code null}
     */
    public void bindCanceller(String runId, Runnable canceller) {
        RunState state = runs.get(runId);
        if (state != null) {
            state.canceller = canceller;
        }
    }

    /**
     * 取消一棵子树：包括它自己与其全部后代。
     * <p>
     * <b>为什么取消要以子树为单位</b>：一个没有父收集者的子 run 继续跑毫无意义——它的结果不会再有人读，
     * 而它在消耗并发许可与 token。因此「取消一个 run」在本内核里的含义就是「取消它及其后代」。
     * <p>
     * <b>幂等</b>：已经终结的 run 重复取消无副作用；未知 run 返回 0。
     *
     * @param runId run 标识
     * @return 实际触发取消动作的 run 数
     */
    public int cancelTree(String runId) {
        return cancelSubtree(runId, true);
    }

    /**
     * 取消一个 run 的全部后代（不含它自己），供调度器在 run 收尾时清理遗留子树。
     *
     * @param runId run 标识
     * @return 实际触发取消动作的 run 数
     */
    int cancelDescendants(String runId) {
        return cancelSubtree(runId, false);
    }

    /**
     * 按父子关系广度优先地取消一棵子树。
     * <p>
     * 子节点靠扫描表的 {@code parentRunId} 找出：本内核的 run 树很小（受 {@code maxSpawnsPerTurn} 约束），
     * 为此维护一张反向索引的复杂度与收益不成比例。
     *
     * @param runId       run 标识
     * @param includeSelf 是否连自己一起取消
     * @return 实际触发取消动作的 run 数
     */
    private int cancelSubtree(String runId, boolean includeSelf) {
        if (runs.get(runId) == null) {
            return 0;
        }
        int cancelled = 0;
        Set<String> visited = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        if (includeSelf) {
            queue.add(runId);
        } else {
            collectChildren(runId, queue);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            RunState state = runs.get(current);
            if (state == null) {
                continue;
            }
            Runnable canceller = state.canceller;
            if (canceller != null) {
                canceller.run();
                cancelled++;
            }
            collectChildren(current, queue);
        }
        return cancelled;
    }

    /**
     * 把某个 run 的直接子 run 收集进队列。
     *
     * @param parentRunId 父 run 标识
     * @param target      目标队列
     */
    private void collectChildren(String parentRunId, Deque<String> target) {
        for (RunState state : runs.values()) {
            if (parentRunId.equals(state.parentRunId)) {
                target.add(state.runId);
            }
        }
    }

    /**
     * 把 run 置为执行中。
     * <p>
     * 只允许从 {@link AgentRunStatus#PENDING} 或 {@link AgentRunStatus#WAITING_CHILDREN} 进入——
     * 后者是并行场景里「父 run 等完孩子继续跑」的路径。终态之后的调用不生效。
     *
     * @param runId run 标识
     * @return 确实发生了转换返回 {@code true}
     */
    public boolean markRunning(String runId) {
        RunState state = runs.get(runId);
        if (state == null) {
            return false;
        }
        return state.status.compareAndSet(AgentRunStatus.PENDING, AgentRunStatus.RUNNING)
                || state.status.compareAndSet(AgentRunStatus.WAITING_CHILDREN, AgentRunStatus.RUNNING);
    }

    /**
     * 把 run 置为等待子 run。
     *
     * @param runId run 标识
     * @return 确实发生了转换返回 {@code true}
     */
    public boolean markWaitingChildren(String runId) {
        RunState state = runs.get(runId);
        return state != null && state.status.compareAndSet(AgentRunStatus.RUNNING, AgentRunStatus.WAITING_CHILDREN);
    }

    /**
     * 落终态并记录进度与用量。
     * <p>
     * <b>幂等</b>：已经是终态时返回 {@code false} 且不改写——调度器与看门狗同时终结同一个 run 时，
     * 先到的那个胜出。
     *
     * @param runId     run 标识
     * @param status    终态，不可为 {@code null} 且必须是终态
     * @param rounds    已完成轮数
     * @param usage     累计用量，可为 {@code null}（按零处理）
     * @return 确实发生了终结返回 {@code true}
     */
    public boolean finish(String runId, AgentRunStatus status, int rounds, SessionUsage usage) {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("finish requires a terminal status: " + status);
        }
        RunState state = runs.get(runId);
        if (state == null) {
            return false;
        }
        AgentRunStatus previous = state.status.get();
        while (!previous.isTerminal()) {
            if (state.status.compareAndSet(previous, status)) {
                state.rounds = rounds;
                state.totalTokens = usage == null ? 0L : usage.getTotalTokens();
                state.finishedAt = System.currentTimeMillis();
                return true;
            }
            previous = state.status.get();
        }
        return false;
    }

    /**
     * 取一个 run 的快照。
     *
     * @param runId run 标识
     * @return 快照；不存在时为空
     */
    public Optional<AgentRunSnapshot> snapshot(String runId) {
        RunState state = runs.get(runId);
        return state == null ? Optional.<AgentRunSnapshot>empty() : Optional.of(state.snapshot());
    }

    /**
     * 取全部未终结的 run 快照。
     *
     * @return 未终结 run 的快照列表，按登记顺序无关；可能为空但不会为 {@code null}
     */
    public List<AgentRunSnapshot> active() {
        List<AgentRunSnapshot> result = new ArrayList<AgentRunSnapshot>();
        for (RunState state : runs.values()) {
            if (!state.status.get().isTerminal()) {
                result.add(state.snapshot());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 移出一个 run 的登记，结果随之不再可查。
     * <p>
     * 调用方取走结果之后调用它：留着终态条目会随进程运行时间线性增长。
     *
     * @param runId run 标识
     * @return 被移出的快照；不存在时为空
     */
    public Optional<AgentRunSnapshot> remove(String runId) {
        RunState state = runs.remove(runId);
        return state == null ? Optional.<AgentRunSnapshot>empty() : Optional.of(state.snapshot());
    }

    /**
     * 单个 run 的可变状态。
     * <p>
     * 终态之后只读；字段用 volatile 是因为读方（快照）可能在另一个线程上。
     */
    private static final class RunState {

        /** run 标识。 */
        private final String runId;

        /** 父 run 标识，可为 {@code null}。 */
        private final String parentRunId;

        /** 树根 run 标识。 */
        private final String rootRunId;

        /** 登记输入。 */
        private final AgentRunRequest request;

        /** 登记时刻。 */
        private final long startedAt;

        /** 当前状态，终态由 CAS 保证恰好一次。 */
        private final AtomicReference<AgentRunStatus> status =
                new AtomicReference<AgentRunStatus>(AgentRunStatus.PENDING);

        /** 取消钩子；句柄创建后由 {@link RunRegistry#bindCanceller(String, Runnable)} 补绑。 */
        private volatile Runnable canceller;

        /** 进入终态的时刻；未终结时为 0。 */
        private volatile long finishedAt;

        /** 已完成轮数。 */
        private volatile int rounds;

        /** 累计 token 用量。 */
        private volatile long totalTokens;

        /**
         * 构造条目。
         *
         * @param runId       run 标识
         * @param parentRunId 父 run 标识，可为 {@code null}
         * @param rootRunId   树根 run 标识
         * @param request     登记输入
         */
        private RunState(String runId, String parentRunId, String rootRunId, AgentRunRequest request) {
            this.runId = runId;
            this.parentRunId = parentRunId;
            this.rootRunId = rootRunId;
            this.request = request;
            this.startedAt = System.currentTimeMillis();
        }

        /**
         * 取当前状态的不可变快照。
         *
         * @return 快照
         */
        private AgentRunSnapshot snapshot() {
            return new AgentRunSnapshot(runId, parentRunId, rootRunId,
                    request.getParentSessionId(), request.getAgentId(), request.getSessionId(),
                    request.getToolCallId(), status.get(), startedAt, finishedAt, rounds, totalTokens);
        }
    }
}
