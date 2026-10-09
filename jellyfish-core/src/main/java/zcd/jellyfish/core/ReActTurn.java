package zcd.jellyfish.core;

/**
 * 一次 ReAct 回合的句柄：由 {@link AgentHarness#chat} 立即返回，回合在 {@code react} 线程异步推进。
 * <p>
 * 提供两种消费方式，按调用方需要选一种：
 * <ul>
 *     <li>事件式：只看 {@link ReActListener} 回调；</li>
 *     <li>阻塞式：直接 {@link #await()} 拿最终结果（等价于同步调用）。</li>
 * </ul>
 * 取消是协作式的：{@link #cancel()} 置标志并取消进行中的 LLM 流，循环在轮次 / 工具边界退出。
 *
 * @author zcd
 */
public interface ReActTurn {

    /**
     * 获取回合标识。
     *
     * @return 回合标识
     */
    String getTurnId();

    /**
     * 取消本回合。
     * <p>
     * 幂等：重复调用不产生额外效果；已结束的回合取消为无操作。
     */
    void cancel();

    /**
     * 阻塞等待回合结束。
     * <p>
     * <b>回合被丢弃时也走异常</b>：内核关闭会对 {@code react} 池 {@code shutdownNow}，排队中还没开跑的
     * 回合从此不会有人来跑，{@link java.util.concurrent.Future#get()} 抛的是
     * {@link java.util.concurrent.CancellationException}——实现把它一并翻成
     * {@link zcd.jellyfish.api.JellyfishException}，等的人因此只会看到这一种失败形式。
     *
     * @return 回合结果，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 回合失败、被丢弃，或等待被中断时抛出
     */
    ReActResult await();

    /**
     * 判断回合是否已结束。
     *
     * @return 已结束（含成功、失败、取消）返回 {@code true}
     */
    boolean isDone();
}
