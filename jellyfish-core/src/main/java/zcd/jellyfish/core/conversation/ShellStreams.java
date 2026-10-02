package zcd.jellyfish.core.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.infra.shell.ShellIngress;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 外壳通道门面：内核与外壳之间<b>两条 lane</b> 的唯一订阅入口。
 * <p>
 * <b>它取代了什么</b>：此前每个外壳各自用一个 {@link ReActListener} 实现接收回调
 * （{@code CliReActListener} / {@code TuiReActListener} / {@code SseReActListener}），
 * 于是「怎么收事件」在三个地方各写一遍。现在内核发布一条<b>会话级</b>的
 * {@link ShellTurnEvent} 流，外壳只做订阅者。
 * <p>
 * <b>「可靠」是什么意思</b>：没有队列、没有丢弃、没有乱序——发布线程同步扇出，订阅者在返回前
 * 就收到事件。这与 {@code EventChannel}（有界队列、可丢、允许乱序）是<b>刻意相反</b>的两条路：
 * 正文增量丢了就是错，而统计通知丢了只是少个数字。两者不能合成一条。
 * <p>
 * <b>订阅必须先于提交</b>：没有 per-turn 回调参数之后，回合一启动就会开始产出事件；
 * 晚于回合开始的订阅者只能收到后续增量（要完整历史请拉会话快照）。这条是硬契约。
 * <p>
 * <b>两种订阅粒度</b>：
 * <ul>
 *     <li>{@link #subscribe(String, ShellTurnListener)}：按会话——Server 的 SSE 流用它，
 *     避免把别的会话的事件写进自己的响应；</li>
 *     <li>{@link #subscribeAll(ShellTurnListener)}：全部会话——TUI 用它。TUI 从首页进入，
 *     会话是 {@code submit} 内部才建的，因此它在提交之前<b>不可能</b>知道会话标识；
 *     而进程内它是唯一消费者，共享同一个暂存区，按会话过滤没有意义。</li>
 * </ul>
 * <p>
 * <b>异常隔离</b>：单个订阅者抛错只记 WARN 并跳过，不影响其它订阅者与回合本身——
 * 一个坏订阅者不该让整轮推理失败。注意这<b>不同于</b> {@code ReActListener} 的旧语义
 * （回调抛错会穿透到 {@code react} 线程）。
 * <p>
 * <p>
 * <b>两条 lane，各自的可丢性挂在通道上</b>：
 * <ul>
 *     <li><b>可靠 lane</b>（{@link #subscribe(String, ShellTurnListener)} /
 *     {@link #subscribeAll(ShellTurnListener)}）：内核回合事件。没有队列，同步扇出，不丢不乱序。</li>
 *     <li><b>尽力 lane</b>（{@link #subscribeShell(ShellContributionListener)}）：插件贡献。
 *     队列在 {@link ShellIngress} 里，每 owner 有界、同 key 可合并、满即丢。</li>
 * </ul>
 * 两者共用一套机制（同一个门面、同族的订阅 API、同一套生命周期），但<b>可丢性在通道级就确定</b>，
 * 不靠逐事件判断。因此「可丢与不可丢不能合成一条路」这条架构纪律仍然成立，
 * 而外壳也不必各自去订阅 {@code EventChannel} 再过滤合并——内核是唯一的合并点。
 * <p>
 * <b>尽力 lane 要外壳自己来取</b>：{@link #drainShell()} 在<b>调用者线程</b>上把信箱里积压的贡献
 * 交给订阅者。这不是缺陷而是刻意的——交付必须发生在渲染线程上（TUI）或 SSE 写循环里（Server），
 * 否则界面状态就会被插件的任意线程改到。同理，插件推得再多也拖不住任何线程：
 * 它的队列满即丢。
 * <p>
 * 线程安全：订阅表用写时复制列表；发布为同步扇出；信箱自带锁。
 *
 * @author zcd
 */
@Singleton
public class ShellStreams {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ShellStreams.class);

    /** 按会话订阅：会话标识 → 订阅者。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ShellTurnListener>> bySession =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<ShellTurnListener>>();

    /** 全量订阅：不区分会话。 */
    private final CopyOnWriteArrayList<ShellTurnListener> all =
            new CopyOnWriteArrayList<ShellTurnListener>();

    /** 尽力 lane 的订阅者（插件贡献）。 */
    private final CopyOnWriteArrayList<ShellContributionListener> shellListeners =
            new CopyOnWriteArrayList<ShellContributionListener>();

    /** 插件贡献信箱：只进不出，等外壳来 {@link #drainShell()}。 */
    private final ShellIngress shellIngress;

    /**
     * 构造通道门面。
     *
     * @param shellIngress 插件贡献信箱，不可为 {@code null}
     */
    @Inject
    public ShellStreams(ShellIngress shellIngress) {
        this.shellIngress = Objects.requireNonNull(shellIngress, "shellIngress must not be null");
    }

    /**
     * 订阅一个会话的回合事件。
     *
     * @param sessionId 会话标识，不可为空白
     * @param listener  订阅者，不可为 {@code null}
     * @return 退订句柄，保证非 {@code null}
     * @throws JellyfishException 会话标识为空白或订阅者为 {@code null} 时抛出
     */
    public Subscription subscribe(String sessionId, final ShellTurnListener listener) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("sessionId must not be blank");
        }
        Objects.requireNonNull(listener, "listener must not be null");
        final String key = sessionId;
        final CopyOnWriteArrayList<ShellTurnListener> listeners =
                bySession.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<ShellTurnListener>());
        listeners.add(listener);
        return new Subscription() {
            @Override
            public void close() {
                listeners.remove(listener);
            }
        };
    }

    /**
     * 订阅全部会话的回合事件。
     *
     * @param listener 订阅者，不可为 {@code null}
     * @return 退订句柄，保证非 {@code null}
     */
    public Subscription subscribeAll(final ShellTurnListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        all.add(listener);
        return new Subscription() {
            @Override
            public void close() {
                all.remove(listener);
            }
        };
    }

    /**
     * 订阅尽力 lane（插件贡献）。
     * <p>
     * <b>订阅本身不交付任何东西</b>：交付发生在 {@link #drainShell()} 里，由订阅者自己的线程驱动。
     * 因此本方法与可靠 lane 的订阅不同——它没有「必须先于某个动作」的时序要求
     * （贡献不等回合，队列会把它存住直到有人来取）。
     *
     * @param listener 订阅者，不可为 {@code null}
     * @return 退订句柄，保证非 {@code null}
     * @throws NullPointerException 订阅者为 {@code null} 时抛出
     */
    public Subscription subscribeShell(final ShellContributionListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        shellListeners.add(listener);
        return new Subscription() {
            @Override
            public void close() {
                shellListeners.remove(listener);
            }
        };
    }

    /**
     * 把信箱里积压的插件贡献交付给全部尽力 lane 订阅者。
     * <p>
     * <b>在调用者线程上同步扇出</b>：外壳从自己的渲染帧 / SSE 写循环里调它，
     * 因此订阅者可以直接改界面状态，不需要再做线程切换。
     * <p>
     * <b>单订阅者抛错只记 WARN 并跳过</b>：与 {@link #publish} 同口径。
     * 一个坏订阅者不该让别的订阅者收不到，也不该让这一帧渲染失败。
     * <p>
     * <b>没有订阅者时照样把信箱清空</b>：否则 {@code -cli} 这类不会来取的外壳会让队列一直积压
     * （虽然 {@code present} 在无渲染面时已被挡下，但「订阅者全部退订」与「没有渲染面」不是同一件事）。
     *
     * @return 本次交付的条目数（可能为 {@code 0}）
     */
    public int drainShell() {
        List<ShellIngress.Entry> entries = shellIngress.drain();
        if (entries.isEmpty()) {
            return 0;
        }
        if (shellListeners.isEmpty()) {
            return 0;
        }
        for (ShellIngress.Entry entry : entries) {
            for (ShellContributionListener listener : shellListeners) {
                try {
                    listener.onContribution(entry.getOwner(), entry.getContribution());
                } catch (RuntimeException e) {
                    LOG.warn("外壳贡献订阅者抛错，已跳过本条（owner={}）：{}", entry.getOwner(), e.getMessage());
                }
            }
        }
        return entries.size();
    }

    /**
     * 发布一条事件：同步扇出给命中该会话的订阅者与全量订阅者。
     * <p>
     * 单个订阅者抛错被隔离并记 WARN：它不该让别的订阅者收不到、更不该让回合失败。
     *
     * @param event 事件，不可为 {@code null}
     */
    public void publish(ShellTurnEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Set<ShellTurnListener> targets = new LinkedHashSet<ShellTurnListener>();
        CopyOnWriteArrayList<ShellTurnListener> sessionListeners = event.getSessionId() == null
                ? null : bySession.get(event.getSessionId());
        if (sessionListeners != null) {
            targets.addAll(sessionListeners);
        }
        targets.addAll(all);
        if (targets.isEmpty()) {
            return;
        }
        List<ShellTurnListener> snapshot = new ArrayList<ShellTurnListener>(targets);
        for (ShellTurnListener listener : snapshot) {
            try {
                listener.onTurnEvent(event);
            } catch (RuntimeException e) {
                LOG.warn("外壳事件订阅者抛错，已跳过本条（kind={} sessionId={}）：{}",
                        event.getKind(), event.getSessionId(), e.getMessage());
            }
        }
    }

    /**
     * 取一个把 {@link ReActListener} 回调翻译成本通道事件的发布器。
     * <p>
     * 内核的回合执行体（{@code ReActLooper}）只认 {@link ReActListener}；本方法提供的适配器把它的
     * 回调逐条翻成 {@link ShellTurnEvent} 并发布。转换只做「搬运」，不改写任何文本。
     *
     * @param sessionId 会话标识
     * @param turnId    回合标识（内核生成，整个回合一致）
     * @return 发布器，保证非 {@code null}
     */
    public ReActListener publisher(final String sessionId, final String turnId) {
        return new TurnPublisher(sessionId, turnId);
    }

    /**
     * 把 {@link ReActListener} 回调翻译成 {@link ShellTurnEvent} 并发布的适配器。
     * <p>
     * <b>它只翻译，不缓冲</b>：任何缓冲都会把「可靠」这条性质变成「有条件可靠」。
     * 慢的订阅者由契约（见 {@link ShellTurnListener}）保证不存在。
     *
     * @author zcd
     */
    private final class TurnPublisher implements ReActListener {

        /** 会话标识。 */
        private final String sessionId;

        /** 回合标识。 */
        private final String turnId;

        /**
         * 构造适配器。
         *
         * @param sessionId 会话标识
         * @param turnId    回合标识
         */
        private TurnPublisher(String sessionId, String turnId) {
            this.sessionId = sessionId;
            this.turnId = turnId;
        }

        @Override
        public void onText(String delta) {
            if (delta != null && !delta.isEmpty()) {
                publish(ShellTurnEvent.text(sessionId, turnId, delta));
            }
        }

        @Override
        public void onThinking(String delta) {
            if (delta != null && !delta.isEmpty()) {
                publish(ShellTurnEvent.thinking(sessionId, turnId, delta));
            }
        }

        @Override
        public void onToolCallStarted(String toolCallId, String toolName) {
            publish(ShellTurnEvent.toolStarted(sessionId, turnId, toolCallId, toolName, null));
        }

        @Override
        public void onToolCallStarted(String toolCallId, String toolName, Map<String, Object> arguments) {
            publish(ShellTurnEvent.toolStarted(sessionId, turnId, toolCallId, toolName, arguments));
        }

        @Override
        public void onToolCallOutput(String toolCallId, String toolName, String chunk) {
            if (chunk != null && !chunk.isEmpty()) {
                publish(ShellTurnEvent.toolOutput(sessionId, turnId, toolCallId, toolName, chunk));
            }
        }

        @Override
        public void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                        Map<String, Object> metadata) {
            publish(ShellTurnEvent.toolCompleted(sessionId, turnId, toolCallId, toolName, success, output,
                    metadata));
        }

        @Override
        public void onComplete(ReActResult result) {
            boolean truncated = result != null && result.isTruncated();
            String content = result == null ? null : result.getContent();
            int rounds = result == null ? 0 : result.getRounds();
            publish(ShellTurnEvent.completed(sessionId, turnId, content, rounds, truncated));
        }

        @Override
        public void onCancelled() {
            publish(ShellTurnEvent.cancelled(sessionId, turnId));
        }

        @Override
        public void onBlocked(String reason) {
            publish(ShellTurnEvent.blocked(sessionId, turnId, reason));
        }

        @Override
        public void onError(Throwable error) {
            publish(ShellTurnEvent.error(sessionId, turnId, error));
        }
    }
}
