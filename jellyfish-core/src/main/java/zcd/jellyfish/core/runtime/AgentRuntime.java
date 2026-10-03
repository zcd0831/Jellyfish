package zcd.jellyfish.core.runtime;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * agent run 的原语门面：内核里「派生 / 等待 / 取消 / 查询一个 run」的唯一边界。
 * <p>
 * <b>为什么要有这一层而不是让调用方直接用登记表与调度器</b>：派生一次 run 需要把三件事按固定顺序
 * 串起来——从当前执行路径的上下文推出父子关系、登记、交给调度器；等待时还要处理「让出并发许可」。
 * 把这些收在一个门面后面，调用方（今天的 {@code task} 路径，将来的编排插件）看到的是一个稳定的能力面，
 * 而不是一摞会随实现演进的组件。
 * <p>
 * <b>派生不阻塞、等待才阻塞</b>：{@link #spawn} 只登记并交给调度器，立即返回句柄——这样同一段编排里
 * 可以先把多个 run 都发起、再一起等。{@link #await(AgentRunHandle)} 才阻塞，且<b>等待期间让出当前
 * run 的并发许可</b>：父 run 占着许可等孩子会让深度大于 1 时自锁死（推理见 {@code RunScheduler}）。
 * <p>
 * <b>不在关键路径回调上层</b>：本门面的方法不同步回调插件、不等待外部完成——与 {@code ActionQueue}
 * 的「不产生同步环」同源。终态通过句柄交给调用方，没有反向调用的环。
 *
 * @author zcd
 */
@Singleton
public final class AgentRuntime {

    /** run 登记表。 */
    private final RunRegistry registry;

    /** 上下文持有者：读当前执行路径的上下文。 */
    private final RunContextHolder contexts;

    /** 调度器：把 run 放到 agent-run 线程上执行。 */
    private final RunScheduler scheduler;

    /**
     * 构造运行时门面。
     *
     * @param registry  run 登记表，不可为 {@code null}
     * @param contexts  上下文持有者，不可为 {@code null}
     * @param scheduler 调度器，不可为 {@code null}
     */
    @Inject
    public AgentRuntime(RunRegistry registry, RunContextHolder contexts, RunScheduler scheduler) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.contexts = Objects.requireNonNull(contexts, "contexts must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /**
     * 派生一个 run：登记身份与父子关系，交给调度器执行，立即返回句柄。
     * <p>
     * 父子关系从当前执行路径的上下文推出，而不是由调用方填写：并行之后父子树由调度器维护，
     * 让调用方各说一遍必然会漂移。
     *
     * @param request          登记输入，不可为 {@code null}
     * @param parentCancellation 父回合的取消令牌，可为 {@code null}（不级联取消）
     * @param body             执行体，不可为 {@code null}
     * @return run 句柄，保证非 {@code null}
     * @throws JellyfishException 当前不在任何委派上下文内时抛出（编程错误）
     */
    public AgentRunHandle spawn(AgentRunRequest request, CancellationToken parentCancellation,
                                AgentRunBody body) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(body, "body must not be null");
        RunContext parent = contexts.current();
        if (parent == null) {
            throw new JellyfishException("agent run requires an active run context");
        }
        String parentRunId = parent.getRunId();
        String parentRootRunId = parent.getRootRunId();
        String runId = registry.register(request, parentRunId, parentRootRunId);
        String rootRunId = parentRunId == null ? runId : parentRootRunId;
        AgentRunHandle handle = new AgentRunHandle(runId);
        // 把取消钩子绑进登记表：这样「按 runId 取消一棵子树」不依赖外层还握着句柄
        registry.bindCanceller(runId, handle::cancel);
        if (parentCancellation != null) {
            parentCancellation.onCancel(handle::cancel);
        }
        // 树引用与进入深度在提交时同步取快照：父线程随后还会改动它自己的上下文对象
        scheduler.submit(runId, body, parent.tree(), parent.getDepth(), rootRunId, handle);
        return handle;
    }

    /**
     * 等待一个 run 终结并返回结果；等待期间让出当前 run 的并发许可。
     * <p>
     * 顶层回合的上下文没有许可，此时等价于直接等句柄。
     *
     * @param handle run 句柄，不可为 {@code null}
     * @return 终态结果，保证非 {@code null}
     */
    public AgentRunResult await(AgentRunHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        RunContext current = contexts.current();
        RunPermit permit = current == null ? null : current.getPermit();
        if (permit == null) {
            return handle.await();
        }
        permit.suspend();
        try {
            return handle.await();
        } finally {
            permit.resume();
        }
    }

    /**
     * 取消一个 run 及其全部后代。
     * <p>
     * <b>为什么以子树为单位</b>：一个没有父收集者的子 run 继续跑毫无意义——结果不会再有人读，
     * 而它仍占着并发许可与 token。这也是与「父取消 ⇒ 后代一并取消」同一条语义的显式入口。
     *
     * @param runId run 标识
     * @return 实际触发取消动作的 run 数
     */
    public int cancelTree(String runId) {
        return registry.cancelTree(runId);
    }

    /**
     * 取一个 run 的快照。
     *
     * @param runId run 标识
     * @return 快照；不存在时为空
     */
    public Optional<AgentRunSnapshot> snapshot(String runId) {
        return registry.snapshot(runId);
    }

    /**
     * 取全部未终结的 run 快照。
     *
     * @return 未终结 run 的快照列表，可能为空但不会为 {@code null}
     */
    public List<AgentRunSnapshot> activeRuns() {
        return registry.active();
    }

    /**
     * 移出一个 run 的登记。
     *
     * @param runId run 标识
     * @return 被移出的快照；不存在时为空
     */
    public Optional<AgentRunSnapshot> remove(String runId) {
        return registry.remove(runId);
    }
}
