package zcd.jellyfish.core.conversation;

import zcd.jellyfish.api.extension.ShellContribution;

/**
 * 尽力 lane 的订阅者：接收插件推给当前外壳的贡献。
 * <p>
 * <b>它在谁的线程上被调用</b>：在外壳<b>自己</b>调用
 * {@link ShellStreams#drainShell()} 的那条线程上——也就是渲染线程（TUI）或 SSE 写循环
 * （Server）。内核不会自己起线程来投递，因此实现方可以放心地直接改界面状态，
 * 不必再做一层线程切换。
 * <p>
 * <b>它必须快</b>：一个慢订阅者会拖住同一线程上后面所有的交付。契约与
 * {@link ShellTurnListener} 一致。
 * <p>
 * <b>它拿到的东西是可丢的</b>：这条 lane 上的贡献在入队时可能已被合并、也可能因为队列满而被丢掉，
 * 因此实现方不得假设「每一条通知都会到达」，也不得把它当作状态真源——面板与状态栏的内容仍然靠拉取。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ShellContributionListener {

    /**
     * 收到一条外壳贡献。
     *
     * @param owner        贡献者的 owner 命名空间（含 {@code ::} 子命名空间），
     *                     用于归因与「同一插件最多显示几条」的淘汰
     * @param contribution 贡献，保证非 {@code null}
     */
    void onContribution(String owner, ShellContribution contribution);
}
