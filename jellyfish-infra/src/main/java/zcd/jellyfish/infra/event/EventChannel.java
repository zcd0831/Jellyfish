package zcd.jellyfish.infra.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.infra.registry.HandlerRegistration;
import zcd.jellyfish.infra.registry.RegistrySnapshot;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 异步派发策略：把通知投入有界队列，由订阅者线程广播给全部命中者。
 * <p>
 * 与 {@code ExtensionRegistry} 共用同一份 {@link TypeRegistry}，区别只在派发策略：无返回值、允许丢弃、
 * 允许乱序，单个订阅者异常被隔离不影响其它订阅者。
 * <p>
 * <b>只有通知语义，没有调用语义</b>：没有超时、没有异常上抛、没有「必须送达」；选择本通道的判据是
 * 「这条消息丢了能不能接受」，而不是「有没有返回值」。
 * <p>
 * <b>生命周期</b>：{@link #start()} 之前发布的通知先进入启动期缓冲（有界），{@code start()} 时回放，
 * 保证「配置加载阶段发出的告警」不会因为订阅者还没注册而丢；{@link #close()} 幂等，只收敛自己的线程池与缓冲，
 * <b>不清空共用注册表</b>（那是 {@code ExtensionRegistry} 与插件回收的职责，避免一边关闭通道一边抹掉同步侧注册）。
 *
 * @author zcd
 */
public final class EventChannel implements EventPublisher, AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventChannel.class);

    /** 通道参数。 */
    private final EventChannelOptions options;

    /** 共用注册表。 */
    private final TypeRegistry registry;

    /** 指标。 */
    private final EventChannelStats stats;

    /** 广播线程池。 */
    private final Executor notifier;

    /** start 之前发布的通知先入队，start 后回放。 */
    private final Queue<JellyfishEvent> pending = new ArrayDeque<>();

    /** 缓冲队列的锁。 */
    private final Object pendingLock = new Object();

    /** 是否已启动。 */
    private volatile boolean started;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /**
     * 以默认线程池构造事件通道。
     *
     * @param options  通道参数，不可为 {@code null}
     * @param registry 共用注册表，不可为 {@code null}
     */
    public EventChannel(EventChannelOptions options, TypeRegistry registry) {
        this(options, registry, new EventChannelStats(), null);
    }

    /**
     * 构造事件通道，便于测试注入同步执行器与自备指标。
     *
     * @param options  通道参数，不可为 {@code null}
     * @param registry 共用注册表，不可为 {@code null}
     * @param stats    指标，不可为 {@code null}
     * @param notifier 广播执行器，为 {@code null} 时按参数自建线程池
     */
    EventChannel(EventChannelOptions options, TypeRegistry registry, EventChannelStats stats, Executor notifier) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.stats = Objects.requireNonNull(stats, "stats must not be null");
        this.notifier = notifier != null ? notifier : createExecutor(options, stats);
        if (this.notifier instanceof ThreadPoolExecutor) {
            stats.bindExecutor((ThreadPoolExecutor) this.notifier);
        }
    }

    /**
     * 标记启动：回放启动期缓冲，之后 {@link #publish(JellyfishEvent)} 直接提交线程池。
     */
    public void start() {
        List<JellyfishEvent> replay;
        synchronized (pendingLock) {
            if (started || closed) {
                return;
            }
            started = true;
            replay = new ArrayList<>(pending);
            pending.clear();
        }
        for (JellyfishEvent event : replay) {
            stats.pendingReplayed.increment();
            enqueue(event);
        }
    }

    /**
     * 异步广播一条通知，立即返回。
     * <p>
     * 未 {@link #start()} 时先进入启动期缓冲；已启动时提交线程池，队列满按
     * {@link EventRejectedExecutionHandler} 的策略丢弃并记账；已 {@link #close()} 时直接丢弃并记账。
     *
     * @param event 通知事件，不可为 {@code null}
     */
    @Override
    public void publish(JellyfishEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        stats.publishedEvents.increment();
        if (closed) {
            stats.droppedEvents.increment();
            LOG.debug("事件通道已关闭，通知被丢弃: {}", event.getClass().getName());
            return;
        }
        if (!started) {
            offerPending(event);
            return;
        }
        enqueue(event);
    }

    /**
     * 订阅内核通知。
     * <p>
     * 订阅写入共用注册表的「类型级 0..N」位置，因此与扩展点处理器同表但不冲突；
     * 类型按 {@code isAssignableFrom} 匹配，订阅父类型即可收到子类型事件。
     *
     * @param owner     来源（内核组件名或 pluginId），不可为空白
     * @param eventType 通知类型，不可为 {@code null}
     * @param filter    过滤谓词，{@code null} 表示接收全部
     * @param listener  监听器，不可为 {@code null}
     * @param <E>       通知类型
     * @return 订阅句柄，关闭后解除本次订阅
     */
    public <E extends JellyfishEvent> Subscription subscribe(String owner, Class<E> eventType, Predicate<E> filter,
                                                             Consumer<E> listener) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Predicate<JellyfishEvent> safeFilter = filter == null
                ? event -> true
                : event -> eventType.isInstance(event) && filter.test(eventType.cast(event));
        Consumer<JellyfishEvent> safeListener = event -> listener.accept(eventType.cast(event));
        EventSubscriber subscriber = new EventSubscriber(owner, eventType, safeFilter, safeListener);
        HandlerRegistration registration = registry.registerShared(owner, eventType, null, subscriber, null, 0);
        return new EventSubscription(registry, registration);
    }

    /**
     * 订阅内核通知，接收该类型的全部通知。
     *
     * @param owner     来源（内核组件名或 pluginId），不可为空白
     * @param eventType 通知类型，不可为 {@code null}
     * @param listener  监听器，不可为 {@code null}
     * @param <E>       通知类型
     * @return 订阅句柄
     */
    public <E extends JellyfishEvent> Subscription subscribe(String owner, Class<E> eventType, Consumer<E> listener) {
        return subscribe(owner, eventType, null, listener);
    }

    /**
     * 按来源回收该 owner 在共用表上的全部注册。
     * <p>
     * <b>一份表意味着按 owner 回收是一次操作</b>：同步处理器与事件订阅落在同一张表上，
     * 因此本方法回收的不只是订阅。这不是缺陷而是简化——插件释放时本来就要把两者一起清干净，
     * 「回收一半」的幽灵注册在这一份表里天然不存在。
     *
     * @param owner 来源标识
     * @return 回收的注册数量
     */
    public int unsubscribeAll(String owner) {
        return registry.removeAll(owner);
    }

    /**
     * 获取指标快照。
     *
     * @return 指标
     */
    public EventChannelStats stats() {
        return stats;
    }

    /**
     * 获取注册表诊断快照。
     *
     * @return 诊断快照
     */
    public RegistrySnapshot snapshot() {
        return registry.snapshot();
    }

    /**
     * 优雅关闭：停止接收、丢弃启动期缓冲、排空线程池。幂等。
     */
    @Override
    public void close() {
        synchronized (pendingLock) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;
            pending.clear();
        }
        shutdownNotifier();
        LOG.info("事件通道已关闭: droppedEvents={}", stats.getDroppedEvents());
    }

    /**
     * 把通知放入启动期缓冲。
     *
     * @param event 通知事件
     */
    private void offerPending(JellyfishEvent event) {
        synchronized (pendingLock) {
            if (pending.size() >= options.getPendingCapacity()) {
                stats.pendingOverflow.increment();
                stats.droppedEvents.increment();
                LOG.warn("启动期缓冲已满，通知被丢弃: {}", event.getClass().getName());
                return;
            }
            pending.add(event);
        }
    }

    /**
     * 提交通知到广播线程池。
     * <p>
     * 不做调用线程上下文（如 MDC）的透传：通知自带 {@code sessionId} 等元信息，上下文属于事件数据而不是线程状态。
     *
     * @param event 通知事件
     */
    private void enqueue(JellyfishEvent event) {
        try {
            notifier.execute(() -> deliver(event));
        } catch (RejectedExecutionException e) {
            stats.droppedEvents.increment();
            LOG.warn("通知提交被拒绝: {}", event.getClass().getName(), e);
        }
    }

    /**
     * 在广播线程上派发通知：单个订阅者异常被隔离并计数，不影响其它订阅者。
     *
     * @param event 通知事件
     */
    private void deliver(JellyfishEvent event) {
        int matched = 0;
        int errors = 0;
        for (HandlerRegistration registration : registry.resolve(event.getClass(), null)) {
            Object handler = registration.getHandler();
            if (!(handler instanceof EventSubscriber)) {
                continue;
            }
            EventSubscriber subscriber = (EventSubscriber) handler;
            if (!subscriber.accepts(event)) {
                continue;
            }
            matched++;
            try {
                subscriber.deliver(event);
            } catch (RuntimeException e) {
                errors++;
                LOG.warn("通知订阅者异常: owner={} event={}", subscriber.getOwner(),
                        event.getClass().getName(), e);
            }
        }
        if (matched == 0) {
            stats.unmatchedNotifications.increment();
            LOG.debug("通知无订阅者命中: {}", event.getClass().getName());
        }
        if (errors > 0) {
            stats.subscriberErrors.add(errors);
        }
    }

    /**
     * 关闭广播线程池并等待排空。
     */
    private void shutdownNotifier() {
        if (!(notifier instanceof ExecutorService)) {
            return;
        }
        ExecutorService executorService = (ExecutorService) notifier;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(options.getShutdownAwaitMillis(), TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }

    /**
     * 创建默认广播线程池：有界队列 + 丢弃并记账的拒绝策略 + 守护线程。
     *
     * @param options 通道参数
     * @param stats   指标
     * @return 广播线程池
     */
    private static Executor createExecutor(EventChannelOptions options, EventChannelStats stats) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                options.getCorePoolSize(),
                options.getMaxPoolSize(),
                options.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(options.getQueueCapacity()),
                new EventThreadFactory(),
                new EventRejectedExecutionHandler(stats));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
