package zcd.jellyfish.core.runtime;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 委派上下文的持有者：每条执行线程一份 {@link RunContext}。
 * <p>
 * <b>为什么是 {@code ThreadLocal} 而不是显式传参</b>：上下文的使用点分布在调用链的两端——
 * 循环器在回合一进一出时开闭它，而委派发生在工具处理器内部（{@code ToolCallRequest} 只携带会话标识，
 * 不能携带内核类型）。把上下文塞进请求对象会逼着 api 认识一个纯内核概念；改成显式传参则要穿过
 * 整条工具执行链，而中间每一层都不需要它。
 * <p>
 * <b>保留 {@code ThreadLocal} 不等于保留「线程封闭语义」</b>：承载的 {@link RunContext} 里，
 * 共享的派生账本（{@link RunTree}）是跨线程按引用共享的——并行执行时，每个执行线程各自持有一个
 * {@code RunContext}，但它们指向同一棵树。真正需要随线程隔离的只有「当前这条路径的深度」。
 * <p>
 * <b>两个开入口的分工</b>：{@link #open(int, int)} 给顶层回合开根上下文；
 * {@link #set(RunContext)} 给调度器在执行线程上装载某个 run 的上下文。两者不合并，
 * 因为前者的参数是配置、后者的参数已经是一个建好的上下文。
 *
 * @author zcd
 */
@Singleton
public final class RunContextHolder {

    /** 当前线程的上下文；不在回合内时为 {@code null}。 */
    private final ThreadLocal<RunContext> current = new ThreadLocal<RunContext>();

    /**
     * 构造上下文持有者。
     */
    @Inject
    public RunContextHolder() {
    }

    /**
     * 开启一个根上下文，供顶层回合开始时调用。
     * <p>
     * <b>覆盖而非报错</b>：正常路径上一条线程不会在回合未结束时再开一个回合（执行池一条线程
     * 一次只跑一个任务）。因此出现旧值只可能是上一次遗漏了 {@link #close()}，而「覆盖」让这种遗漏的
     * 后果止于一次多余的对象滞留，不会让后续所有回合失败——诊断信息远不如可用性重要。
     * <p>
     * 不带 token 预算的重载等价于两个预算都为 {@code 0}（不限制），供不关心预算的调用方使用。
     *
     * @param maxDepth         允许的最大委派层数
     * @param maxSpawnsPerTurn 本次回合允许派生的子代理总数
     */
    public void open(int maxDepth, int maxSpawnsPerTurn) {
        open(maxDepth, maxSpawnsPerTurn, 0L, 0L);
    }

    /**
     * 开启一个根上下文，并带上 token 预算。
     *
     * @param maxDepth         允许的最大委派层数
     * @param maxSpawnsPerTurn 本次回合允许派生的子代理总数
     * @param runTokenBudget   单个 run 的累计 token 上限，{@code 0} 表示不限制
     * @param treeTokenBudget  一棵 run 树的累计 token 上限，{@code 0} 表示不限制
     */
    public void open(int maxDepth, int maxSpawnsPerTurn, long runTokenBudget, long treeTokenBudget) {
        current.set(new RunContext(new RunTree(maxDepth, maxSpawnsPerTurn, runTokenBudget, treeTokenBudget),
                0, null, null, null));
    }

    /**
     * 装载一个已建好的上下文，供调度器在 agent-run 线程上执行某个 run 之前调用。
     *
     * @param context 该 run 的上下文，不可为 {@code null}
     */
    void set(RunContext context) {
        current.set(context);
    }

    /**
     * 取当前线程的上下文。
     *
     * @return 上下文；不在回合内时返回 {@code null}
     */
    public RunContext current() {
        return current.get();
    }

    /**
     * 关闭当前线程的上下文，供回合终结的 {@code finally} 调用。
     * <p>
     * 必须清掉而不是留着：执行池的线程会被复用，留着就等于下一个任务继承上一个回合的计数。
     */
    public void close() {
        current.remove();
    }
}
