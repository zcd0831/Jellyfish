package zcd.jellyfish.infra.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作队列：插件主动动作的入站队列，同时也是「这个会话有没有在途回合」的唯一判据。
 * <p>
 * <b>为什么在 {@code infra} 而不是 {@code core}</b>：入队入口是 {@code PluginContextImpl.submit}，
 * 而插件上下文属于插件运行时（{@code infra.plugin}）。core 依赖 infra，反过来不成立，
 * 所以队列本身必须落在 infra。执行动作需要会话与压缩器（都在 core 视野里），那部分由
 * {@code core.action.ActionDispatcher} 承担——本类只负责「存、限、取、丢」，
 * 取出的 {@link Pending} 交给 core 执行并回填结果。
 * <p>
 * <b>队列的存活期恰好是一个回合</b>：窗口由 {@link #beginTurn} 打开、{@link #endTurn} 关闭。
 * 这个内核里「一次 {@code chat} 调用 = 一个回合」，回合的边界由外壳决定，因此没有在途回合就没有窗口，
 * 入队一律失败。窗口随回合一起消失，残留的待排空动作在回合结束时被标为失败——
 * 它们已经不可能再被排空了。
 * <p>
 * <b>为什么必须有个队列</b>：{@code submit} 与排空点不在同一时刻，甚至不在同一线程
 * （插件可以从它自己的线程投递）。因此「投了但还没轮到」是一个真实存在的状态，必须有地方承接它。
 * <p>
 * <b>它不是事件总线</b>：没有订阅、没有广播、没有处理器注册，只有一条有界的单向入站队列。
 * 内核与插件之间的能力注册与通知仍然只走 {@code ExtensionRegistry} / {@code EventChannel}。
 * <p>
 * <b>归属按 owner 命名空间回收</b>（与注册表同一套规则、同一时刻）：插件停止时，它在途排队的动作
 * 整批丢弃并标为 {@link ActionStatus#DROPPED}，插件轮询得到「没投出去」，而不是以为投出去了。
 * <p>
 * 线程安全：窗口表用 {@link ConcurrentHashMap}，窗口内部在窗口对象上加锁——
 * 提交线程、react 线程与插件停止线程会同时碰到它。
 *
 * @author zcd
 */
@Singleton
public final class ActionQueue {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ActionQueue.class);

    /**
     * 每会话待排空动作的缺省上界。
     * <p>
     * 有界是硬要求：队列是内核自有的内存结构，插件写得再多、再快，也不该把内核吃干。
     * 满了就丢并记 WARN——动作是「建议内核做事」，不是「必须完成的事实」。
     */
    public static final int DEFAULT_CAPACITY = 16;

    /** 每会话待排空上界。 */
    private final int capacity;

    /** {@code sessionId} → 该会话的投递窗口；有在途回合才有窗口。 */
    private final Map<String, Window> windows = new ConcurrentHashMap<String, Window>();

    /**
     * 构造动作队列。
     */
    @Inject
    public ActionQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造动作队列并指定容量，供单元测试使用。
     *
     * @param capacity 每会话待排空上界，必须为正数
     */
    ActionQueue(int capacity) {
        if (capacity <= 0) {
            throw new JellyfishException("action queue capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 投递一条动作。
     * <p>
     * <b>它只做「校验 + 入队」</b>，绝不在调用者的栈上执行动作本身——只有
     * {@link PluginAction.Kind#ABORT_TURN} 例外：它的语义就是「置一个取消标志」这样一个快动作，
     * 入队再等排空反而会让「中止」错过它想中止的那个回合。
     * <p>
     * <b>失败一律回报而不抛异常</b>：拿不到在途回合、本内核尚未提供该能力，都是插件可以预期的
     * 正常结果（见 {@link ActionStatus#FAILED}）。抛异常会把一条「这次没赶上」变成必须 try/catch
     * 的错误路径。
     *
     * @param owner  投递者的 owner 命名空间，不可为空白
     * @param action 动作，不可为 {@code null}
     * @return 动作句柄，保证非 {@code null}
     */
    public ActionHandle submit(String owner, PluginAction action) {
        Objects.requireNonNull(action, "action must not be null");
        Handle handle = new Handle(action);
        String sessionId = action.getSessionId();
        switch (action.getKind()) {
            case REBUILD_TOOL_CATALOG:
                // 明说「尚未提供」而不是静默无效：插件据此可以不做重试，也不会以为自己重建了清单
                return handle.onFailed("本内核尚未提供工具清单缓存：rebuildToolCatalog 将在工具激活落地后可用");
            case ABORT_TURN:
                return abort(handle, sessionId);
            default:
                return enqueue(owner, handle);
        }
    }

    /**
     * 打开一个会话的投递窗口：此后该会话有在途回合，动作可以投进来。
     * <p>
     * <b>必须在起回合之前调用</b>：与 {@code SessionTurns} 的占位同理。「先起回合、再登记」会让
     * 起回合与第一次 {@code submit} 之间的动作白跑一趟，而那个窗口在真实使用里正好是
     * 「插件收到回合开始事件」那一刻。
     *
     * @param sessionId    会话标识，不可为空白
     * @param cancelHandle 取消该回合的回调，不可为 {@code null}
     */
    public void beginTurn(String sessionId, Runnable cancelHandle) {
        Objects.requireNonNull(cancelHandle, "cancelHandle must not be null");
        if (windows.containsKey(sessionId)) {
            // 同一会话两个顶层回合：内核不禁止（那由外壳的闸门负责，Server 是 SessionTurns），
            // 但动作通道只能认一个。记 WARN 而不是抛错——重开窗口比让新回合完全失去动作能力要好
            LOG.warn("会话已有在途回合窗口，动作通道改认新回合: sessionId={}", sessionId);
        }
        windows.put(sessionId, new Window(cancelHandle));
    }

    /**
     * 关闭一个会话的投递窗口，并把还没排空的动作标为失败。
     * <p>
     * <b>残留动作是失败而不是丢弃</b>：它们不是被容量或插件停止淘汰的，而是「回合结束了，
     * 再也不会有人来取」。插件需要知道这次没赶上——典型来源是插件从事件订阅回调投递，
     * 而事件是异步投递的，可能落在回合刚结束之后。
     *
     * @param sessionId 会话标识，不可为空白
     */
    public void endTurn(String sessionId) {
        Window window = windows.remove(sessionId);
        if (window == null) {
            return;
        }
        for (Pending leftover : window.takeAllWithoutStateChange()) {
            leftover.fail("回合已结束，动作未能在本回合内排空");
        }
    }

    /**
     * 取走本回合边界上的动作：压缩、切换模型，以及插入点为「工具批次之后」的用户消息。
     * <p>
     * 取走的动作状态转 {@link ActionStatus#EXECUTING}：调用方接管执行，执行完<b>必须</b>回填终态，
     * 否则插件会一直看到「执行中」。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 待执行动作，没有时返回空列表
     */
    public List<Pending> takeTurnBoundary(String sessionId) {
        Window window = windows.get(sessionId);
        return window == null ? new ArrayList<Pending>() : window.take(true);
    }

    /**
     * 取走本回合剩余的动作（插入点为「模型本要收敛那一刻」的用户消息）。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 待执行动作，没有时返回空列表
     */
    public List<Pending> takeConvergence(String sessionId) {
        Window window = windows.get(sessionId);
        return window == null ? new ArrayList<Pending>() : window.take(false);
    }

    /**
     * 按 owner 命名空间丢弃在途动作：{@code owner} 自身与 {@code owner::*} 一并清掉。
     * <p>
     * 与注册表的按 owner 回收同一套规则、同一时刻（插件停止时）。已经在执行的动作不打断——
     * 它已经不在队列里了。
     *
     * @param owner owner 命名空间根，可为 {@code null}（此时不做任何事）
     * @return 被丢弃的动作数量
     */
    public int dropByOwner(String owner) {
        if (owner == null) {
            return 0;
        }
        int dropped = 0;
        for (Window window : windows.values()) {
            for (Handle handle : window.dropOwnedBy(owner)) {
                dropped++;
                handle.onDropped("插件已停止，在途动作被丢弃");
            }
        }
        if (dropped > 0) {
            LOG.info("已丢弃插件在途动作: owner={} actions={}", owner, dropped);
        }
        return dropped;
    }

    /**
     * 立刻中止目标会话的在途回合。
     * <p>
     * <b>没有在途回合也算成功</b>：「已经没有回合可中止了」与「中止成功」的结果相同，
     * 为一个已经达成的目标报错，只会让插件多写一个永远走不到的分支。
     *
     * @param handle    动作句柄
     * @param sessionId 会话标识
     * @return 已落终态的句柄
     */
    private Handle abort(Handle handle, String sessionId) {
        Window window = windows.get(sessionId);
        if (window == null) {
            return handle.onSucceeded("没有在途回合，无需中止");
        }
        try {
            window.cancelHandle.run();
            return handle.onSucceeded("已请求中止在途回合");
        } catch (RuntimeException e) {
            return handle.onFailed("中止在途回合失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 把动作排进窗口。
     *
     * @param owner  投递者的 owner 命名空间
     * @param handle 动作句柄
     * @return 入队后的句柄，或已落终态的句柄
     */
    private Handle enqueue(String owner, Handle handle) {
        Window window = windows.get(handle.getAction().getSessionId());
        if (window == null) {
            // 最典型的失败，也是最容易被误当 bug 的一条：插件从事件订阅回调或自己的线程投递，
            // 而那一刻没有回合在跑。会话不存在、以及该会话只有子代理（嵌套）回合时，也会走到这里——
            // 后两者都没有顶层回合，因此归在同一处失败，把三种情形在原因里说清
            return handle.onFailed("当前没有在途回合：插件动作只能投进正在跑的顶层回合"
                    + "（会话不存在、或该会话只有子代理回合时同样如此）");
        }
        handle.owner = owner;
        if (!window.offer(handle)) {
            LOG.warn("动作队列已满，丢弃: owner={} sessionId={} capacity={}",
                    owner, handle.getAction().getSessionId(), capacity);
            return handle.onDropped("动作队列已满（每会话上限 " + capacity + "），本条被丢弃");
        }
        return handle;
    }

    /**
     * 一个会话的投递窗口：存活期恰好是一个顶层回合。
     * <p>
     * 所有读写都在本对象上加锁——提交线程、react 线程与插件停止线程会同时碰到它。
     */
    private final class Window {

        /** 取消该回合的回调。 */
        private final Runnable cancelHandle;

        /** 待排空动作，按投递顺序。 */
        private final Deque<Handle> pending = new ArrayDeque<Handle>();

        /**
         * 构造。
         *
         * @param cancelHandle 取消回调
         */
        private Window(Runnable cancelHandle) {
            this.cancelHandle = cancelHandle;
        }

        /**
         * 入队。
         *
         * @param handle 动作句柄
         * @return 入队成功返回 {@code true}；已满返回 {@code false}
         */
        private synchronized boolean offer(Handle handle) {
            if (pending.size() >= capacity) {
                return false;
            }
            pending.addLast(handle);
            return true;
        }

        /**
         * 取走待排空动作并置为「执行中」。
         *
         * @param boundaryOnly {@code true} 只取回合边界上该执行的那些；{@code false} 取全部
         * @return 待执行动作，按投递顺序
         */
        private synchronized List<Pending> take(boolean boundaryOnly) {
            List<Pending> taken = new ArrayList<Pending>();
            Iterator<Handle> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Handle next = iterator.next();
                if (boundaryOnly && !atTurnBoundary(next.getAction())) {
                    continue;
                }
                taken.add(next);
                iterator.remove();
                next.execute();
            }
            return taken;
        }

        /**
         * 取走全部剩余动作，<b>不改状态</b>：调用方（回合结束）要的是「还没被任何人取走的那些」。
         *
         * @return 剩余动作
         */
        private synchronized List<Pending> takeAllWithoutStateChange() {
            List<Pending> taken = new ArrayList<Pending>(pending);
            pending.clear();
            return taken;
        }

        /**
         * 丢弃属于某个 owner 命名空间的动作。
         *
         * @param owner owner 命名空间根
         * @return 被丢弃的动作
         */
        private synchronized List<Handle> dropOwnedBy(String owner) {
            List<Handle> dropped = new ArrayList<Handle>();
            Iterator<Handle> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Handle next = iterator.next();
                String candidate = next.getOwner();
                if (candidate != null && (candidate.equals(owner)
                        || candidate.startsWith(owner + PluginOwnerNamespace.SEPARATOR))) {
                    dropped.add(next);
                    iterator.remove();
                }
            }
            return dropped;
        }

        /**
         * 判断一条动作是否属于「回合边界」那一档。
         * <p>
         * 压缩与切换模型都在回合边界做（两者都改缓存前缀，中途换掉会让本回合前后几轮的上下文不同源），
         * 用户消息则按 {@link DeliverAs} 分两档：{@code STEER} 在回合边界，
         * {@code FOLLOW_UP} 要留到收敛点。
         *
         * @param action 动作
         * @return 属于回合边界返回 {@code true}
         */
        private boolean atTurnBoundary(PluginAction action) {
            if (action instanceof PluginAction.SendUserMessage) {
                return ((PluginAction.SendUserMessage) action).getDeliverAs() == DeliverAs.STEER;
            }
            return true;
        }
    }

    /**
     * 已出队、等待调用方执行的动作。
     * <p>
     * 调用方<b>必须</b>回填终态（成功或失败），否则插件会一直看到 {@link ActionStatus#EXECUTING}。
     *
     * @author zcd
     */
    public interface Pending {

        /**
         * 获取动作。
         *
         * @return 动作，保证非 {@code null}
         */
        PluginAction getAction();

        /**
         * 获取投递者的 owner 命名空间。
         *
         * @return owner，可为 {@code null}
         */
        String getOwner();

        /**
         * 回填成功。
         *
         * @param result 执行摘要，可为 {@code null}
         */
        void succeed(String result);

        /**
         * 回填失败。
         *
         * @param reason 失败原因
         */
        void fail(String reason);
    }

    /**
     * 动作句柄实现：状态与结果都对插件可见，因此写入顺序有要求。
     * <p>
     * 先写 {@code result} 再写 {@code status}：两者都是 volatile，读方看到新状态时也必然看到
     * 与之配套的结果，不会出现「状态是 FAILED、原因还是 null」这样的半成品。
     */
    private static final class Handle implements Pending, ActionHandle {

        /** 被投递的动作。 */
        private final PluginAction action;

        /** 投递者的 owner 命名空间，未入队时为 {@code null}。 */
        private volatile String owner;

        /** 当前状态。 */
        private volatile ActionStatus status = ActionStatus.QUEUED;

        /** 结果说明，未结束时为 {@code null}。 */
        private volatile String result;

        /**
         * 构造。
         *
         * @param action 动作
         */
        private Handle(PluginAction action) {
            this.action = action;
        }

        @Override
        public PluginAction getAction() {
            return action;
        }

        @Override
        public String getOwner() {
            return owner;
        }

        @Override
        public ActionStatus getStatus() {
            return status;
        }

        @Override
        public String getResult() {
            return result;
        }

        @Override
        public void succeed(String text) {
            finish(ActionStatus.DONE, text);
        }

        @Override
        public void fail(String reason) {
            finish(ActionStatus.FAILED, reason);
        }

        /**
         * 落终态为成功并返回自身，供内部链式返回。
         *
         * @param text 执行摘要，可为 {@code null}
         * @return 本句柄
         */
        private Handle onSucceeded(String text) {
            finish(ActionStatus.DONE, text);
            return this;
        }

        /**
         * 落终态为失败并返回自身，供内部链式返回。
         *
         * @param reason 失败原因
         * @return 本句柄
         */
        private Handle onFailed(String reason) {
            finish(ActionStatus.FAILED, reason);
            return this;
        }

        /**
         * 标记为执行中：出队即执行中，两者之间没有可观察的间隔。
         */
        private void execute() {
            finish(ActionStatus.EXECUTING, null);
        }

        /**
         * 标记为被丢弃。
         *
         * @param reason 丢弃原因
         * @return 本句柄，便于链式返回
         */
        private Handle onDropped(String reason) {
            finish(ActionStatus.DROPPED, reason);
            return this;
        }

        /**
         * 落终态。
         *
         * @param next   目标状态
         * @param reason 结果说明
         * @return 本句柄
         */
        private Handle finish(ActionStatus next, String reason) {
            this.result = reason;
            this.status = next;
            return this;
        }
    }
}
