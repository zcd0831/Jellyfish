package zcd.jellyfish.api.extension;

/**
 * 调用级取消令牌：把「这次调用被取消了」这件事从内核送到可能长时间阻塞的工具手里。
 * <p>
 * <b>解决什么问题</b>：同步派发没有超时，取消是协作式的——循环只在「每轮开始」与「每个工具之前」
 * 检查标志，因此一个正在进行中的工具调用（一条跑了几分钟的 shell 命令）在检查点之外无法被打断。
 * 工具要自己实现截止时间，但「用户按了 Esc」这个信号必须有人送过来，本接口就是那个通道。
 * <p>
 * <b>为什么不用事件</b>：{@code EventChannel} 是异步、有界、<b>明确可丢</b>的通道，而取消是用户在界面上
 * 按下 Esc 之后必须成立的同步事实。用可丢通道承载不可丢语义，表现就是「偶尔取消不掉」。
 * <p>
 * <b>为什么不是「内核统一给工具调用加超时」</b>：同步派发没有安全的中断方式——
 * {@code Thread.interrupt} 不会中断 {@code Process.getInputStream().read()}，{@code Thread.stop} 已废弃。
 * 内核能做且应该做的只有把信号送到工具手里，超时由工具自己实现。
 * <p>
 * <b>实现方必须遵守</b>：
 * <ul>
 *     <li>{@link #onCancel(Runnable)} 的回调<b>可能在渲染线程上执行</b>（{@code -tui} 的 Esc 路径），
 *     因此回调必须快、不得阻塞、不得等待子进程退出，只允许「发信号」这类非阻塞动作；
 *     收尾（关闭流、回收资源）要放回工具自己的执行线程；</li>
 *     <li>同一个回调最多被执行一次（注册时已取消则立即执行）。</li>
 * </ul>
 *
 * @author zcd
 */
public interface CancellationToken {

    /**
     * 永不取消的令牌：供进程级调用点与测试使用。
     * <p>
     * 工具拿不到真实令牌时按「本次调用不会取消」处理，而不是按「已取消」——后者会让工具
     * 一启动就自杀。注册的回调不会被保存，因为永远不会触发。
     */
    CancellationToken NONE = new CancellationToken() {

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable callback) {
            // 永不取消：不保存也不执行
        }
    };

    /**
     * 判断本次调用是否已被取消。
     * <p>
     * 拉模型：调用方可在阻塞前或循环中自查。注意它只能回答「此刻是否已取消」，
     * 因此阻塞中的工具应当用 {@link #onCancel(Runnable)} 而不是轮询本方法。
     *
     * @return 已取消返回 {@code true}
     */
    boolean isCancelled();

    /**
     * 注册取消回调；若注册时已取消，立即执行。
     *
     * @param callback 取消回调，不可为 {@code null}
     */
    void onCancel(Runnable callback);
}
