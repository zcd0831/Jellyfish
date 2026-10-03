package zcd.jellyfish.core.runtime;

import java.util.Objects;

/**
 * 一条 agent run 事件：一个 run 在生命周期里对外广播的事实。
 * <p>
 * <b>为什么单独立类型，而不是塞进 {@code ShellTurnEvent}</b>：那是「外壳回合」的契约，已经跨三个外壳；
 * 而 agent 运行时的观测面还要继续长（P3 的任务列表、代理间消息）。挤进同一个判别式意味着每加一个
 * agent 概念，三个外壳的订阅者都要各重新处理一遍。运行时的可观测性应当有自己的词汇表。
 * <p>
 * <b>三档事件分开走</b>（见 {@code design/subagent-runtime-p1.md} D-P1-5）：
 * <ul>
 *     <li>{@link Kind#STARTED} / {@link Kind#FINISHED}：生命周期，量小，走**可靠**总线；</li>
 *     <li>{@link Kind#STEP}：每轮 / 每次工具调用的推进，同样走可靠总线（它是「它在推进」的骨干）；</li>
 *     <li>输出流（每段工具输出）**不在本类型里**：它量大且必须可丢，待真需要时另设可丢通道。</li>
 * </ul>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgentRunEvent {

    /** 事件种类。 */
    public enum Kind {

        /** run 开始执行（已拿到并发许可）。 */
        STARTED,

        /** run 推进一步（一轮模型调用或一次工具调用）。 */
        STEP,

        /** run 到达终态（含被拒未起的 run）。 */
        FINISHED
    }

    /** 事件种类。 */
    private final Kind kind;

    /** 事件发生时的 run 快照，保证非 {@code null}。 */
    private final AgentRunSnapshot run;

    /** 推进描述；仅 {@link Kind#STEP} 非 {@code null}。 */
    private final String step;

    /**
     * 构造事件。
     *
     * @param kind 事件种类，不可为 {@code null}
     * @param run  run 快照，不可为 {@code null}
     * @param step 推进描述，可为 {@code null}
     */
    private AgentRunEvent(Kind kind, AgentRunSnapshot run, String step) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.step = step;
    }

    /**
     * 构造「run 开始执行」事件。
     *
     * @param run run 快照，不可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static AgentRunEvent started(AgentRunSnapshot run) {
        return new AgentRunEvent(Kind.STARTED, run, null);
    }

    /**
     * 构造「run 推进一步」事件。
     *
     * @param run  run 快照，不可为 {@code null}
     * @param step 推进描述，可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static AgentRunEvent step(AgentRunSnapshot run, String step) {
        return new AgentRunEvent(Kind.STEP, run, step);
    }

    /**
     * 构造「run 到达终态」事件。
     *
     * @param run run 快照，不可为 {@code null}
     * @return 事件，保证非 {@code null}
     */
    public static AgentRunEvent finished(AgentRunSnapshot run) {
        return new AgentRunEvent(Kind.FINISHED, run, null);
    }

    /**
     * 获取事件种类。
     *
     * @return 事件种类
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取 run 快照。
     *
     * @return run 快照，保证非 {@code null}
     */
    public AgentRunSnapshot getRun() {
        return run;
    }

    /**
     * 获取 run 标识（便捷入口，等价于 {@code getRun().getRunId()}）。
     *
     * @return run 标识
     */
    public String getRunId() {
        return run.getRunId();
    }

    /**
     * 获取推进描述。
     *
     * @return 推进描述；非 {@link Kind#STEP} 时为 {@code null}
     */
    public String getStep() {
        return step;
    }

    /**
     * 判断是否为终态事件。
     *
     * @return {@link Kind#FINISHED} 返回 {@code true}
     */
    public boolean isTerminal() {
        return kind == Kind.FINISHED;
    }

    @Override
    public String toString() {
        return "AgentRunEvent{kind=" + kind + ", run=" + run.getRunId()
                + ", agent=" + run.getAgentId() + '}';
    }
}
