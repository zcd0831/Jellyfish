package zcd.jellyfish.core.conversation;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每会话在途回合表：把「同一会话同时只能有一个回合」这条约束钉在一个地方。
 * <p>
 * <b>为什么在内核</b>：这条约束此前只有 Server 有（{@code SessionTurns}），而它是<b>会话的</b>性质，
 * 不是 HTTP 的性质——两个客户端从不同外壳并发写同一个会话，错法完全一样（用户 A 的提问后面跟着
 * 用户 B 的提问，两者的回答各按自己的上下文生成，历史被交错写坏）。
 * 放在内核之后，TUI / CLI / Server 与将来的嵌入式调用方共享同一条保证，也不需要各自重写一遍。
 * <p>
 * <b>它管什么、不管什么</b>：
 * <ul>
 *     <li>管：<b>起回合</b>的互斥、取消入口、以及「这个会话现在有没有在途回合」；</li>
 *     <li>管：把取消<b>报出去</b>（{@link TurnCancelledEvent}）。取消此前只在外壳的可靠 lane 上可见，
 *     而插件看不到那条通道；放在这里是因为「这次是不是第一次上报该会话的取消」只有本类知道
 *     （{@link ReActTurn#cancel()} 幂等，但它不会让回合立刻变成已完成）；</li>
 *     <li>不管：<b>插件动作窗口</b>。那个窗口由 {@code ActionQueue.beginTurn/endTurn} 开闭
 *     （见 {@code constraints/extensions.md}），两者必须都存在——一个决定「插件能不能投递」，
 *     另一个决定「第二个提交该不该被拒」。</li>
 *     <li>不管：输入指令。指令的完成只能靠轮询（{@code InputDirectiveRun.isDone()}），
 *     本类没有它的回调，因此不纳入闸门；当前唯一会起指令的外壳（TUI）用界面自身的
 *     「进行中」状态挡住并发提交。</li>
 * </ul>
 * <p>
 * <b>为什么用 {@link Semaphore}(1) 而不是 {@code ReentrantLock}</b>：后者允许<b>同一线程</b>重入，
 * 于是「同一个线程误占两次」这种真 bug 会被它静默放过。信号量是非重入的，同样的错误当场失败。
 * <p>
 * <b>为什么锁实例不回收</b>：回收需要判断「没有别的线程正准备拿同一个信号量」，而那个判断本身需要
 * 另一把锁，得回的复杂度远高于收益——每个会话一个信号量的开销可以忽略，而会话数由使用规模决定。
 * <p>
 * <b>释放由内核保证</b>：{@link #releasing} 把「终态回调」与「归还槽位」绑在一起，
 * 因此调用方不需要在 {@code try/finally} 里手动归还，也就不会出现「某条终结路径忘了还」——
 * 那种泄漏的表现是「这个会话以后再也起不了回合」，且只在特定路径上复现。
 * <p>
 * 线程安全：槽位表与回合表用 {@link ConcurrentHashMap}，互斥用每会话一个信号量。
 *
 * @author zcd
 */
@Singleton
public final class TurnRegistry {

    /** 每会话槽位：非重入，不回收。 */
    private final ConcurrentHashMap<String, Semaphore> slots =
            new ConcurrentHashMap<String, Semaphore>();

    /** 在途回合表：会话标识 → 当前回合（供取消用）。 */
    private final ConcurrentHashMap<String, ReActTurn> turns =
            new ConcurrentHashMap<String, ReActTurn>();

    /**
     * 已经上报过取消的会话。
     * <p>
     * <b>为什么需要它</b>：{@link ReActTurn#cancel()} 只置标志、掐断当前流，它<b>不会</b>让
     * {@link ReActTurn#isDone()} 立刻变真——回合是在下一个检查点才收敛的。于是在这段窗口里重复按
     * 取消键会走到同一条路径上，把同一次打断报成两次。计数型消费方（「用户打断了几次」）拿到这种
     * 重复值只会得出错误结论，而去重不该是每个消费方各自要写一遍的事。
     * <p>
     * 清理与槽位归还同步（见 {@link #release}）：下一个回合的取消必须能重新上报。
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** 通知发布入口：取消事件走它。 */
    private final EventPublisher events;

    /**
     * 构造空的注册表。
     *
     * @param events 通知发布入口，不可为 {@code null}
     */
    @Inject
    public TurnRegistry(EventPublisher events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 占用一个会话的回合槽位（非阻塞）。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 已占用的槽位，必须交给 {@link #release} 或 {@link #releasing} 归还
     * @throws TurnInProgressException 该会话已有在途回合时抛出
     * @throws zcd.jellyfish.api.JellyfishException 会话标识为空白时抛出
     */
    public Slot acquire(String sessionId) {
        String key = requireSessionId(sessionId);
        Semaphore slot = slots.computeIfAbsent(key, ignored -> new Semaphore(1));
        if (!slot.tryAcquire()) {
            throw new TurnInProgressException(key);
        }
        return new Slot(key);
    }

    /**
     * 把已启动的回合句柄绑定到槽位，供 {@link #cancel} 使用。
     * <p>
     * 允许在「已占位、尚未拿到句柄」的窗口里不绑定：{@link #cancel} 对没有句柄的会话返回
     * {@code false}，而那个窗口只覆盖「调用 {@code chat} 到拿到句柄」这几微秒，
     * 此刻回合还没有可取消的工作。
     *
     * @param sessionId 会话标识，不可为空白
     * @param turn      回合句柄，可为 {@code null}（等价于不绑定）
     */
    public void bind(String sessionId, ReActTurn turn) {
        if (turn == null) {
            return;
        }
        turns.put(requireSessionId(sessionId), turn);
    }

    /**
     * 归还槽位并解绑回合。
     * <p>
     * <b>幂等</b>：同一个 {@link Slot} 重复归还只生效一次。重复归还若被放过，
     * 信号量许可会涨到 2，从而静默破坏互斥——那比抛异常更难查。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param slot      {@link #acquire} 返回的槽位，可为 {@code null}（等价于无事发生）
     */
    public void release(String sessionId, Slot slot) {
        if (slot == null || sessionId == null || !slot.claimRelease()) {
            return;
        }
        turns.remove(sessionId);
        // 与槽位一起清掉「已上报取消」的标记：不清的话，这个会话的下一个回合被取消时就再也报不出来
        reported.remove(sessionId);
        Semaphore semaphore = slots.get(sessionId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /**
     * 判断某会话是否有在途回合。
     * <p>
     * 读到结果的那一刻它就可能已经变了，因此只供诊断与测试断言使用，不参与任何业务判定。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 有在途回合返回 {@code true}
     */
    public boolean isRunning(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        ReActTurn turn = turns.get(sessionId);
        return turn != null && !turn.isDone();
    }

    /**
     * 取消一个会话的在途回合。
     * <p>
     * 在「已占用槽位、尚未绑定回合」的极小窗口里会返回 {@code false}——此刻还没有可取消的工作。
     * <p>
     * <b>取消成功时广播一条 {@link TurnCancelledEvent}</b>：取消此前只在外壳的可靠 lane 上可见
     * （{@code ShellTurnEvent.CANCELLED}），插件看不到那条通道，于是无法区分「用户打断了」与
     * 「回合正常结束」。事件带 {@code turnId}，需要的话可以据此把这次取消与那一轮的全部输出关联起来。
     * <p>
     * <b>同一次取消只广播一次</b>：重复请求（连按取消键、或在回合收敛过程中又按了一次）照旧返回
     * {@code true}（确实取消到了一个在途回合），但不再重复广播——否则「打断了几次」这类计数会被算重。
     * 判据落在<b>本类</b>而不是发起方：{@code ReActTurn.cancel()} 是幂等的，只有这里知道「这次是不是
     * 第一次为这个会话上报取消」。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 真的取消到了在途回合返回 {@code true}；该会话没有在途回合时返回 {@code false}
     */
    public boolean cancel(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        ReActTurn turn = turns.get(sessionId);
        if (turn == null || turn.isDone()) {
            return false;
        }
        turn.cancel();
        if (reported.add(sessionId)) {
            events.publish(new TurnCancelledEvent(sessionId, turn.getTurnId()));
        }
        return true;
    }

    /**
     * 取某会话的在途回合句柄。
     * <p>
     * 供需要直接操作句柄的调用方使用（当前只有测试）；外壳应当走 {@link #cancel}，
     * 以免把「持有句柄」这件事散到各处。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 在途回合；没有时为空
     */
    public Optional<ReActTurn> turnOf(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(turns.get(sessionId));
    }

    /**
     * 把监听器包一层：回合进入终态时自动归还槽位。
     * <p>
     * <b>为什么包在监听器上</b>：{@code ReActLooper} 保证四条终结路径
     * （收敛 / 取消 / 被拦下 / 异常）<b>恰好触发一个</b>终态回调，因此「终态」就是回合结束的
     * 唯一权威信号。把这层包装放在这里而不是让每个调用方自己写 {@code try/finally}，
     * 就没有任何一条路径能漏掉归还。
     * <p>
     * 终态回调先转发给委托者、再归还槽位：外壳在收到终态时看到的是「回合已经结束」，
     * 而不是「回合结束但槽位还占着」（后者会让紧接着发起的提交拿到 409）。
     * 委托者抛错也不会跳过归还（{@code finally}）。
     *
     * @param sessionId 会话标识
     * @param slot      已占用的槽位，不可为 {@code null}
     * @param delegate  真正接收回调的监听器，可为 {@code null}（等价于 {@link ReActListener#NOOP}）
     * @return 包装后的监听器，保证非 {@code null}
     */
    public ReActListener releasing(String sessionId, Slot slot, ReActListener delegate) {
        Objects.requireNonNull(slot, "slot must not be null");
        return new ReleasingListener(sessionId, slot, delegate == null ? ReActListener.NOOP : delegate);
    }

    /**
     * 校验会话标识非空白。
     *
     * @param sessionId 会话标识
     * @return 原值
     * @throws zcd.jellyfish.api.JellyfishException 为空白时抛出
     */
    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new zcd.jellyfish.api.JellyfishException("sessionId must not be blank");
        }
        return sessionId;
    }

    /**
     * 已占用的槽位：一次性归还凭证。
     * <p>
     * 不暴露内部信号量：调用方只需要「拿住它、还回去」，任何直接操作信号量的机会都只会被误用。
     *
     * @author zcd
     */
    public static final class Slot {

        /** 槽位所属的会话标识。 */
        private final String sessionId;

        /** 是否已归还，保证重复归只会生效一次。 */
        private final AtomicBoolean released = new AtomicBoolean(false);

        /**
         * 构造槽位。
         *
         * @param sessionId 会话标识
         */
        private Slot(String sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * 获取槽位所属的会话标识。
         *
         * @return 会话标识，保证非空白
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 抢占「本次归还有效」。
         *
         * @return 本次调用赢得归还返回 {@code true}
         */
        private boolean claimRelease() {
            return released.compareAndSet(false, true);
        }
    }

    /**
     * 终态即归还的监听器包装：逐方法转发，只在四条终结路径上多做一件归还。
     *
     * @author zcd
     */
    private final class ReleasingListener implements ReActListener {

        /** 会话标识。 */
        private final String sessionId;

        /** 待归还的槽位。 */
        private final Slot slot;

        /** 真正的接收者。 */
        private final ReActListener delegate;

        /**
         * 构造包装。
         *
         * @param sessionId 会话标识
         * @param slot      槽位
         * @param delegate  真正的接收者
         */
        private ReleasingListener(String sessionId, Slot slot, ReActListener delegate) {
            this.sessionId = sessionId;
            this.slot = slot;
            this.delegate = delegate;
        }

        @Override
        public void onText(String delta) {
            delegate.onText(delta);
        }

        @Override
        public void onThinking(String delta) {
            delegate.onThinking(delta);
        }

        @Override
        public void onToolCallStarted(String toolCallId, String toolName) {
            delegate.onToolCallStarted(toolCallId, toolName);
        }

        @Override
        public void onToolCallStarted(String toolCallId, String toolName, Map<String, Object> arguments) {
            delegate.onToolCallStarted(toolCallId, toolName, arguments);
        }

        @Override
        public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
            delegate.onToolCallOutput(toolCallId, toolName, chunk);
        }

        @Override
        public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                        Map<String, Object> metadata) {
            delegate.onToolCallCompleted(toolCallId, toolName, success, output, metadata);
        }

        @Override
        public void onComplete(ReActResult result) {
            try {
                delegate.onComplete(result);
            } finally {
                release();
            }
        }

        @Override
        public void onCancelled() {
            try {
                delegate.onCancelled();
            } finally {
                release();
            }
        }

        @Override
        public void onBlocked(String reason) {
            try {
                delegate.onBlocked(reason);
            } finally {
                release();
            }
        }

        @Override
        public void onError(Throwable error) {
            try {
                delegate.onError(error);
            } finally {
                release();
            }
        }

        /**
         * 归还槽位（幂等，由 {@link Slot#claimRelease()} 保证）。
         */
        private void release() {
            // 直接操作外层实例的映射：本类与外层是同一份状态，且不需要对外暴露归还入口
            TurnRegistry.this.release(sessionId, slot);
        }
    }
}
