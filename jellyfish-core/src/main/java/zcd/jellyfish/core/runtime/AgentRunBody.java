package zcd.jellyfish.core.runtime;

/**
 * 一次 agent run 的执行体：由调度器在 agent-run 线程上调用，返回终态结果。
 * <p>
 * <b>为什么是回调而不是让运行时认识 ReAct</b>：{@code core.runtime} 不能依赖 {@code core} 的
 * 循环实现（依赖方向是 {@code core → core.runtime}）。运行时只负责「什么时候、在哪个线程上、
 * 用什么许可跑」，具体怎么跑由调用方以本回调交出——这样运行时保持对执行方式无感，
 * 也让「换成真调度」这件事不需要动执行体本身。
 * <p>
 * <b>异常语义</b>：回调抛出的 {@code RuntimeException} 由调度器捕获并转成
 * {@link AgentRunResult#failed(String)}，因此调用方不必自己兜底；但回调内部仍应尽量给出具体原因。
 *
 * @author zcd
 */
@FunctionalInterface
public interface AgentRunBody {

    /**
     * 执行一次 run。
     *
     * @param handle 本次 run 的句柄：可读 runId，也可当作取消令牌传给执行体
     * @return 终态结果，不可为 {@code null}
     */
    AgentRunResult run(AgentRunHandle handle);
}
