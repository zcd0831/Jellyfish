package zcd.jellyfish.infra.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 人工审批通道：同步判定的 {@code ASK} 分支与外壳界面之间唯一的交接点。
 * <p>
 * <b>它解决什么问题</b>：权限判定发生在 {@code react} 线程上并且是同步的（判定结果要立刻决定
 * 这次工具调用执行不执行），而能回答「批准吗」的人只在<b>渲染线程</b>里存在。两侧因此需要一次
 * 跨线程交接：写侧阻塞等待，读侧每帧取件——形态与 {@code InflightTurn}（react 线程写、渲染线程读）
 * 恰好互为镜像，只是方向相反。
 * <p>
 * <b>为什么不开扩展点</b>：审批需要独占终端的模态交互，而插件在架构上「碰不到界面、也拿不到布局」。
 * 因此审批者只能是外壳（{@link #attach()}），插件无法参与——它仍然可以拦（{@code PermissionVerdict}），
 * 但拦完之后的放行与否不归它管。
 * <p>
 * <b>fail-closed</b>：未挂审批者、超时、排队超出上限、通道已关闭、线程被中断，<b>一律拒绝</b>。
 * 策略已经明确表示「这个工具要人看一眼」，审批者缺席时放行等于静默放宽权限；而拒绝的代价只是
 * 工具执行失败、可被用户察觉。这条同时也是 {@code -cli} / {@code -server} 的现状保持路径：
 * 它们不 {@code attach()}，因此行为与审批通道落地前完全一致。
 * <p>
 * <b>为什么一次只交接一个</b>：审批在界面上是一个模态选择框，同屏只能显示一个。因此「当前待审批项」
 * 只有一个槽位（读侧每帧读它），其余请求按到达顺序排队；排队数封顶 {@link #MAX_WAITING}，
 * 超出即拒绝——{@code react} 池并发上限有限，真排到上限说明审批者已经不在了，
 * 让请求无限堆积只会把内存与线程一起拖住。
 * <p>
 * <b>「只接受第一次结论」怎么保证</b>：裁决、超时、通道关闭三方都可能给出结论，它们全部经
 * {@link #finish} 在<b>同一把锁</b>内完成「查等待者 → 摘掉 → 推进当前槽位」，
 * 因此只有一个调用方能在映射里找到那个等待者。先到者胜出，后到者当作无事发生——
 * 若不做这一步，「超时已按拒绝收敛、界面随后又点下批准」会把一个已经结束的等待改成放行。
 * <p>
 * 线程安全：读侧（{@link #pending()}）走 {@code volatile} 无锁读；写侧状态变更全部在一把锁内完成。
 *
 * @author zcd
 */
@Singleton
public class ApprovalChannel {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalChannel.class);

    /** 排队等待审批的最大请求数（不含当前展示的那一条）。 */
    public static final int MAX_WAITING = 8;

    /** 无审批者时的拒绝理由。 */
    public static final String NO_APPROVER = "无审批者（当前外壳不提供审批界面），按拒绝处理";

    /** 审批通道已关闭时的拒绝理由。 */
    public static final String DETACHED = "审批通道已关闭，按拒绝处理";

    /** 排队超出上限时的拒绝理由。 */
    public static final String QUEUE_FULL = "待审批请求过多，按拒绝处理";

    /** 线程被中断时的拒绝理由。 */
    public static final String INTERRUPTED = "审批等待被中断，按拒绝处理";

    /** 批准理由。 */
    public static final String APPROVED = "用户已批准";

    /** 拒绝理由。 */
    public static final String REJECTED = "用户已拒绝";

    /** 是否已挂上审批者（外壳启动时置位）。 */
    private volatile boolean attached;

    /** 当前展示给审批者的那一条，{@code null} 表示没有待审批项。读侧无锁读。 */
    private final AtomicReference<Pending> current = new AtomicReference<Pending>();

    /** 等待中的请求（先进先出），与 {@link #waiters} 同增同减。 */
    private final Deque<Pending> waiting = new ArrayDeque<Pending>();

    /** 全部在途请求的等待者，键为请求 id：当前那条与排队中的都记在这里，供裁决与关闭时定位。 */
    private final Map<String, Waiter> waiters = new LinkedHashMap<String, Waiter>();

    /** 状态变更锁：入队、裁决、摘除三处共用。 */
    private final Object lock = new Object();

    /**
     * 构造审批通道。初始未挂载，因此默认行为是「无审批者一律拒绝」。
     */
    @Inject
    public ApprovalChannel() {
    }

    /**
     * 挂上审批者：此后的 {@link #request} 会真的等待答复。外壳在启动时调用。
     */
    public void attach() {
        attached = true;
    }

    /**
     * 摘下审批者并排空未决请求。
     * <p>
     * <b>排空是必须的</b>：外壳退出时若不把等待中的请求一并裁决，那些 {@code react} 线程会一直阻塞到
     * 超时；排空让它们立刻拿到拒绝并收敛。
     */
    public void detach() {
        attached = false;
        List<String> abandoned;
        synchronized (lock) {
            abandoned = new ArrayList<String>(waiters.keySet());
        }
        for (String id : abandoned) {
            finish(id, PermissionDecision.deny(DETACHED));
        }
        if (!abandoned.isEmpty()) {
            LOG.info("审批通道关闭，已拒绝 {} 条未决审批请求", abandoned.size());
        }
    }

    /**
     * 取当前待审批请求，供外壳每帧绘制审批浮层。
     *
     * @return 当前请求；没有待审批项时为 {@link Optional#empty()}
     */
    public Optional<Pending> pending() {
        return Optional.ofNullable(current.get());
    }

    /**
     * 取当前排队等待数（不含当前展示的那一条）。
     * <p>
     * 只供诊断与测试断言使用，不参与任何判定：排队上限是在 {@link #enqueue} 里现算的，
     * 拿到这个数字之后它就可能已经变了。
     *
     * @return 排队中的请求数
     */
    int waitingCount() {
        synchronized (lock) {
            return waiting.size();
        }
    }

    /**
     * 给出审批结论（渲染线程调用）。
     * <p>
     * 只对<b>当前展示的那一条</b>生效：排队中的请求审批者根本看不到，也就无从裁决；
     * 对它调用等于无事发生。同一条请求的第一次结论胜出，
     * 之后的调用（超时后用户才点下、重复按键）静默丢弃。
     *
     * @param id       请求 id，可为 {@code null}
     * @param approved 是否批准
     */
    public void resolve(String id, boolean approved) {
        if (id == null) {
            return;
        }
        synchronized (lock) {
            Pending head = current.get();
            if (head == null || !head.getId().equals(id)) {
                return;
            }
        }
        finish(id, approved ? PermissionDecision.allow(APPROVED)
                : PermissionDecision.deny(REJECTED));
    }

    /**
     * 请求人工审批并阻塞等待结论（{@code react} 线程调用）。
     *
     * @param request 审批请求，不可为 {@code null}
     * @param timeout 等待超时，不可为 {@code null} 且必须为正
     * @return 判定结果：批准为 ALLOW，其余一切情况（无审批者 / 超时 / 排队满 / 通道关闭 / 中断）均为 DENY
     */
    public PermissionDecision request(Pending request, Duration timeout) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!attached) {
            return PermissionDecision.deny(NO_APPROVER);
        }
        Waiter waiter = new Waiter();
        if (!enqueue(request, waiter)) {
            // 入队失败的原因在锁内定下，但这里重读一次 attached 就足够区分：关闭是一次性事件
            return PermissionDecision.deny(attached ? QUEUE_FULL : DETACHED);
        }
        String unanswered = null;
        try {
            if (!waiter.latch.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                unanswered = timeoutReason(timeout);
            }
        } catch (InterruptedException e) {
            // 中断在此处只有一种来源：回合被取消 / 进程在退出。恢复中断位让上层也能看到
            Thread.currentThread().interrupt();
            unanswered = INTERRUPTED;
        }
        if (unanswered != null) {
            // 落定失败说明裁决方抢先给出了结论，此时按它的结论返回
            finish(request.getId(), PermissionDecision.deny(unanswered));
        }
        return waiter.decision;
    }

    /**
     * 落定一条在途请求：摘掉等待者、推进当前槽位、放行等待线程。
     * <p>
     * 「查找 + 摘除」在同一把锁内完成，因此同一条请求的多个结论来源只有一个能成功；
     * 返回 {@code false} 表示这条请求已被别人裁决（或早已超时）。
     *
     * @param id       请求 id
     * @param decision 判定结果
     * @return 本次调用真正落定返回 {@code true}
     */
    private boolean finish(String id, PermissionDecision decision) {
        Waiter waiter;
        synchronized (lock) {
            waiter = waiters.remove(id);
            if (waiter == null) {
                return false;
            }
            Pending head = current.get();
            if (head != null && id.equals(head.getId())) {
                current.set(waiting.isEmpty() ? null : waiting.pollFirst());
            } else {
                removeWaiting(id);
            }
        }
        waiter.decision = decision;
        waiter.latch.countDown();
        return true;
    }

    /**
     * 入队一条审批请求：当前槽位空闲则直接占用，否则排队（超出上限返回失败）。
     *
     * @param request 审批请求
     * @param waiter  等待者
     * @return 入队成功返回 {@code true}
     */
    private boolean enqueue(Pending request, Waiter waiter) {
        synchronized (lock) {
            if (!attached) {
                // 与 request 开头的检查之间可能刚好发生了一次 detach，这里重查一次：
                // 不重查的话这条请求会进了队列却没人看（审批者已经走了），白等到超时
                return false;
            }
            if (current.get() == null) {
                current.set(request);
                waiters.put(request.getId(), waiter);
                return true;
            }
            if (waiting.size() >= MAX_WAITING) {
                return false;
            }
            waiting.addLast(request);
            waiters.put(request.getId(), waiter);
            return true;
        }
    }

    /**
     * 把一条请求从排队区摘掉（仅限于已经拿到等待者的调用方）。
     *
     * @param id 请求 id
     */
    private void removeWaiting(String id) {
        Iterator<Pending> iterator = waiting.iterator();
        while (iterator.hasNext()) {
            if (id.equals(iterator.next().getId())) {
                iterator.remove();
                return;
            }
        }
    }

    /**
     * 生成超时拒绝理由。
     *
     * @param timeout 等待超时
     * @return 理由文本
     */
    private static String timeoutReason(Duration timeout) {
        long millis = Math.max(1L, timeout.toMillis());
        String amount = millis < TimeUnit.SECONDS.toMillis(1) ? millis + " 毫秒" : timeout.getSeconds() + " 秒";
        return "审批超时（" + amount + "内未响应），按拒绝处理";
    }

    /**
     * 审批请求：一次「需要人工审批」的工具调用的全部展示信息。
     * <p>
     * 不可变，可安全跨线程传递（由 {@code react} 线程构造、渲染线程读取）。
     *
     * @author zcd
     */
    public static final class Pending {

        /** 请求标识，供渲染线程裁决时回填。 */
        private final String id;

        /** 会话标识，可为 {@code null}。 */
        private final String sessionId;

        /** 发起调用的 agentId，可为 {@code null}。 */
        private final String agentId;

        /** 待审批的工具名。 */
        private final String toolName;

        /** 工具参数，只读。 */
        private final Map<String, Object> arguments;

        /** 当时的会话权限模式。 */
        private final PermissionMode mode;

        /** 策略给出的审批理由原文。 */
        private final String reason;

        /** 请求发生时刻（毫秒）。 */
        private final long timestamp;

        /**
         * 构造审批请求。
         *
         * @param sessionId 会话标识，可为 {@code null}
         * @param agentId   发起调用的 agentId，可为 {@code null}
         * @param toolName  待审批的工具名，不可为空白
         * @param arguments 工具参数，可为 {@code null}
         * @param mode      会话权限模式，可为 {@code null}
         * @param reason    策略给出的审批理由，可为 {@code null}
         */
        public Pending(String sessionId, String agentId, String toolName, Map<String, Object> arguments,
                       PermissionMode mode, String reason) {
            this.id = UUID.randomUUID().toString();
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.toolName = toolName;
            this.arguments = arguments == null
                    ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
            this.mode = mode;
            this.reason = reason;
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * 获取请求标识。
         *
         * @return 请求标识，保证非 {@code null}
         */
        public String getId() {
            return id;
        }

        /**
         * 获取会话标识。
         *
         * @return 会话标识，可能为 {@code null}
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 获取发起调用的 agentId。
         *
         * @return agentId，未绑定时为 {@code null}
         */
        public String getAgentId() {
            return agentId;
        }

        /**
         * 获取待审批的工具名。
         *
         * @return 工具名
         */
        public String getToolName() {
            return toolName;
        }

        /**
         * 获取工具参数。
         *
         * @return 只读参数映射，保证非 {@code null}
         */
        public Map<String, Object> getArguments() {
            return arguments;
        }

        /**
         * 获取会话权限模式。
         *
         * @return 权限模式，可能为 {@code null}
         */
        public PermissionMode getMode() {
            return mode;
        }

        /**
         * 获取策略给出的审批理由。
         *
         * @return 理由原文，可能为 {@code null}
         */
        public String getReason() {
            return reason;
        }

        /**
         * 获取请求发生时刻。
         *
         * @return 毫秒时间戳
         */
        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * 等待者：一次阻塞等待的闩锁与结论。
     * <p>
     * 只会被 {@link #finish} 写入一次，且写入发生在闩锁放行之前，因此等待线程读到的必然是自己那次的结论。
     */
    private static final class Waiter {

        /** 结论闩锁。 */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** 裁决结果，由 {@link #finish} 在放行前写入。 */
        private volatile PermissionDecision decision;
    }
}
