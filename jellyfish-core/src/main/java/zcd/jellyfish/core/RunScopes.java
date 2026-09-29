package zcd.jellyfish.core;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 委派作用域的持有者：每条线程一份 {@link RunScope}，由顶层回合的开闭决定其生命周期。
 * <p>
 * <b>为什么是 {@code ThreadLocal} 而不是显式传参</b>：作用域的两个使用点分别在调用链的两端——
 * 循环器在回合一进一出时开闭它，而委派发生在工具处理器内部（{@code ToolCallRequest} 只携带会话标识，
 * 不能携带内核类型）。把作用域塞进请求对象会逼着 api 认识一个纯内核概念；改成显式传参则要穿过
 * 整条工具执行链，而中间每一层都不需要它。
 * <p>
 * <b>为什么放在循环器而不是让委派方自建</b>：回合的边界只有循环器知道。放在这里，
 * 「回合一开始就有作用域、回合一结束就没有」是一条不需要任何人记得维护的事实；
 * 派发方只管读它、用它。
 * <p>
 * <b>嵌套回合无需新作用域</b>：{@code ReActLooper.runNested} 与父回合同线程内联执行，
 * 因此读到的是同一个作用域——正是想要的语义（同一次顶层回合的累计账）。
 *
 * @author zcd
 */
@Singleton
public final class RunScopes {

    /** 当前线程的作用域；不在回合内时为 {@code null}。 */
    private final ThreadLocal<RunScope> current = new ThreadLocal<RunScope>();

    /**
     * 构造作用域持有者。
     */
    @Inject
    public RunScopes() {
    }

    /**
     * 开启一个作用域，供顶层回合开始时调用。
     * <p>
     * <b>覆盖而非报错</b>：正常路径上一条线程不会在回合未结束时再开一个回合（react 池一条线程
     * 一次只跑一个任务，嵌套回合走的是内联入口）。因此出现旧值只可能是上一次遗漏了
     * {@link #close()}，而「覆盖」让这种遗漏的后果止于一次多余的对象滞留，不会让后续所有回合失败——
     * 诊断信息远不如可用性重要。
     *
     * @param maxDepth         允许的最大委派层数
     * @param maxSpawnsPerTurn 本次回合允许派生的子代理总数
     */
    public void open(int maxDepth, int maxSpawnsPerTurn) {
        current.set(new RunScope(maxDepth, maxSpawnsPerTurn));
    }

    /**
     * 取当前线程的作用域。
     *
     * @return 作用域；不在顶层回合内时返回 {@code null}
     */
    public RunScope current() {
        return current.get();
    }

    /**
     * 关闭当前线程的作用域，供顶层回合的 {@code finally} 调用。
     * <p>
     * 必须清掉而不是留着：react 池的线程会被复用，留着就等于下一个回合继承上一个回合的计数。
     */
    public void close() {
        current.remove();
    }
}
