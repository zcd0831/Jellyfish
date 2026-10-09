package zcd.jellyfish.core.conversation;

/**
 * 可靠 lane 的订阅者：接收一个会话（或全部会话）的回合事件。
 * <p>
 * <b>线程语义与硬约束</b>：回调在内核的<b>发布线程</b>上同步触发——回合事件来自 {@code react} /
 * {@code llm-stream} 线程，工具实时输出来自工具自己的输出泵线程（stdout / stderr 两条，会并发）。
 * 因此实现必须：
 * <ol>
 *     <li><b>快</b>：它挡在内核与回合之间，做 I/O 就会拖住整轮推理；</li>
 *     <li><b>线程安全</b>：可能被多条线程同时调用；</li>
 *     <li><b>不阻塞</b>：只允许写入自己的线程安全缓冲（或投进自己的队列），
 *     界面状态只能在渲染线程上变更——这条与 {@code ReActListener} 的既有契约完全一致。</li>
 * </ol>
 * 实现抛出的异常会被内核隔离（记 WARN 并跳过该订阅者），不影响其它订阅者与回合本身。
 * <p>
 * <b>终态恰好一次</b>：每条事件流（回合或输入指令，各有一个标识）会收到
 * {@link ShellTurnEvent#isTerminal()} 为真的<b>恰好一条</b>事件（回合是收敛 / 取消 / 被拦下 / 异常四选一，
 * 指令是收敛 / 取消二选一），订阅者据此收尾，不需要额外超时。
 * <p>
 * <b>指令也是「有始有终」的流</b>：它复用同一条 lane（`!` 的实时输出要走它），因此也必须以恰好一条终态收尾——
 * 少发一条，按上面这条契约实现的订阅者就会一直等下去。指令的终态只说「这条流到此为止」，
 * 失败原因在会话消息里（那是给人看的地方），不在事件里。
 *
 * @author zcd
 */
public interface ShellTurnListener {

    /**
     * 收到一条回合事件。
     *
     * @param event 回合事件，保证非 {@code null}
     */
    void onTurnEvent(ShellTurnEvent event);
}
