package zcd.jellyfish.infra.event;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.callback.ExtensionPointRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交互枢纽门面：内核与插件之间的唯一交互入口，下辖回调通道与事件通道。
 * <p>
 * 不再自称「事件总线」：加上泛化后的回调通道，它同时承载三张表（回调、事件、事件注册表），
 * 是内核主动回头找插件（扩展点）与插件单向观察内核（事件）的共同落点。
 * <p>
 * 内部持有单个 Guava {@code EventBus} 作为唯一注册表，两条通道的差异只在发布端体现：
 * {@link #invoke(Callback)} / {@link #invokeAll(Callback)} 直接内联 {@code post}，
 * {@link #publish(JellyfishEvent)} 先提交线程池再 {@code post}。
 * Guava 的订阅者只有门面自己的 {@link CallbackDispatcher} / {@link EventDispatcher}，
 * 插件与核心组件一律只注册到 Level 2 细粒度注册表。
 * <p>
 * 生命周期：{@link #start()} 之前发布的异步通知进入缓冲队列，{@code start()} 时回放；回调必须先 {@code start()}，
 * 因为回调必须有处理器、不能缓冲。
 *
 * @author zcd
 */
public final class JellyfishEventBus implements EventPublisher, AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(JellyfishEventBus.class);

    /** 总线参数。 */
    private final EventBusOptions options;

    /** Guava 同步总线，dispatcher 为 inline；必须全限定名以避免与本类同名。 */
    private final EventBus delegate;

    /** 通知通道线程池。 */
    private final Executor notifier;

    /** 扩展点定义注册表，承载形状预设并驱动回调通道语义。 */
    private final ExtensionPointRegistry extensionPointRegistry = ExtensionPointRegistry.withBuiltIns();

    /** 细粒度回调注册表。 */
    private final CallbackRegistry callbackRegistry;

    /** 回调应答槽。 */
    private final CallbackReplies callbackReplies = new CallbackReplies();

    /** 细粒度通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    /** 指标。 */
    private final EventBusStats stats;

    /** 回调派发上下文。 */
    private final DispatchContext dispatchContext;

    /** 回调分发器，Guava 订阅者之一。 */
    private final CallbackDispatcher callbackDispatcher;

    /** 通知分发器，Guava 订阅者之一。 */
    private final EventDispatcher eventDispatcher;

    /** start 之前发布的通知先入队，start 后回放。 */
    private final Queue<JellyfishEvent> pending = new ArrayDeque<>();

    /** 注释扫描缓存：订阅者类 → @Subscribe 方法列表。 */
    private final Map<Class<?>, List<Method>> subscribeMethodCache = new ConcurrentHashMap<>();

    /** 缓冲队列的锁。 */
    private final Object pendingLock = new Object();

    /** 是否已启动。 */
    private volatile boolean started;

    /** 是否已关闭。 */
    private volatile boolean closed;

    /**
     * 以默认线程池构造交互枢纽。
     *
     * @param options 总线参数
     */
    public JellyfishEventBus(EventBusOptions options) {
        this(options, new EventBusStats(), null);
    }

    /**
     * 以自定义派发器构造交互枢纽，便于测试注入同步 Executor。
     *
     * @param options  总线参数
     * @param notifier 通知派发器
     */
    JellyfishEventBus(EventBusOptions options, Executor notifier) {
        this(options, new EventBusStats(), notifier);
    }

    /**
     * 构造交互枢纽。
     *
     * @param options  总线参数
     * @param stats    指标
     * @param notifier 通知派发器，为 {@code null} 时按参数自建线程池
     */
    private JellyfishEventBus(EventBusOptions options, EventBusStats stats, Executor notifier) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.stats = stats;
        this.notifier = notifier != null ? notifier : createExecutor(this.options, stats);
        if (this.notifier instanceof ThreadPoolExecutor) {
            stats.bindExecutor((ThreadPoolExecutor) this.notifier);
        }
        this.callbackRegistry = new CallbackRegistry(extensionPointRegistry);
        this.dispatchContext = new DispatchContext(options.getMaxCallbackDepth(), stats);
        this.callbackDispatcher = new CallbackDispatcher(callbackRegistry, callbackReplies, stats);
        this.eventDispatcher = new EventDispatcher(eventRegistry, stats);
        this.delegate = new EventBus(new EventDispatchExceptionHandler(dispatchContext, callbackReplies));
        this.delegate.register(callbackDispatcher);
        this.delegate.register(eventDispatcher);
    }

    /**
     * 同步调用回调并返回单个结果。
     * <p>
     * 回调在调用者线程内联执行，因此同一线程内天然有序；处理器异常原样回传给调用者。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     * @return 回调结果
     * @throws JellyfishException 总线未启动或回调为空时抛出
     */
    public <R> R invoke(Callback<R> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        ensureStarted();
        dispatchContext.enter(callback);
        callbackReplies.open(callback);
        try {
            delegate.post(callback);
            return callbackReplies.await(callback);
        } finally {
            callbackReplies.close(callback);
            dispatchContext.exit();
        }
    }

    /**
     * 同步调用回调并收集全部成功结果，按处理器调用顺序排列。
     *
     * @param callback 回调对象
     * @param <R>      结果类型
     * @return 结果列表，可能为空
     * @throws JellyfishException 总线未启动或回调为空时抛出
     */
    public <R> List<R> invokeAll(Callback<R> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        ensureStarted();
        dispatchContext.enter(callback);
        callbackReplies.open(callback);
        try {
            delegate.post(callback);
            return callbackReplies.awaitAll(callback);
        } finally {
            callbackReplies.close(callback);
            dispatchContext.exit();
        }
    }

    /**
     * 异步广播通知，立即返回。
     * <p>
     * 未 {@link #start()} 时先进入启动期缓冲队列；已启动时提交通知线程池，队列满
     * 按 {@link EventRejectedExecutionHandler} 的策略处理。
     *
     * @param event 通知事件
     */
    @Override
    public void publish(JellyfishEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        stats.publishedEvents.increment();
        if (!started) {
            offerPending(event);
            return;
        }
        enqueue(event);
    }

    /**
     * 同步广播通知，用于必须立即产生可见副作用的场景。
     * <p>
     * 无论是否 {@code start()} 都直接在当前线程派发，结果只取决于当时的订阅者。
     *
     * @param event 通知事件
     */
    public void publishSync(JellyfishEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        stats.publishedEvents.increment();
        delegate.post(event);
    }

    /**
     * 注册门面内部订阅者（仅核心组件自用，插件请走 {@link #pluginContext(String)}）。
     * <p>
     * 扫描对象的 {@code @Subscribe} 方法并分类注册到 Level 2：具体回调类型视为回调处理器，
     * 通知类型视为订阅者。禁止 {@code Object} 订阅者（会吞掉死事件）与回调基类订阅（会收下所有子类回调）。
     *
     * @param subscriber 订阅者对象
     * @return 注册句柄，关闭后解除本次注册
     * @throws JellyfishException 订阅方法非法时抛出
     */
    public Subscription register(Object subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        String owner = subscriber.getClass().getName();
        List<Subscription> handles = new ArrayList<>();
        for (Method method : subscribeMethods(subscriber.getClass())) {
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType == Object.class) {
                throw new JellyfishException("Object subscriber is forbidden: " + method);
            }
            if (Callback.class.isAssignableFrom(parameterType)) {
                handles.add(registerCallbackSubscriber(owner, subscriber, method, parameterType));
            } else if (JellyfishEvent.class.isAssignableFrom(parameterType)) {
                handles.add(registerEventSubscriber(owner, subscriber, method, parameterType));
            } else {
                throw new JellyfishException("subscriber parameter must be a JellyfishEvent or a Callback: " + method);
            }
        }
        return new CompositeSubscription(handles);
    }

    /**
     * 标记启动：回放缓冲的通知，之后 {@link #publish(JellyfishEvent)} 直接提交线程池。
     */
    public void start() {
        List<JellyfishEvent> replay;
        synchronized (pendingLock) {
            if (started) {
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
     * 优雅关闭：停止接收、排空线程池、清理两层注册表与扩展点定义。
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
        callbackRegistry.clear();
        eventRegistry.clear();
        extensionPointRegistry.clear();
        LOG.info("交互枢纽已关闭: droppedEvents={}", stats.getDroppedEvents());
    }

    /**
     * 获取指标快照。
     *
     * @return 指标
     */
    public EventBusStats stats() {
        return stats;
    }

    /**
     * 获取注册表诊断快照。
     *
     * @return 诊断快照
     */
    public RegistrySnapshot snapshot() {
        return RegistrySnapshot.of(callbackRegistry, eventRegistry, extensionPointRegistry);
    }

    /**
     * 获取插件注册入口，绑定 pluginId 作为 owner。
     *
     * @param pluginId 插件标识，不可为空
     * @return 插件能力上下文
     * @throws JellyfishException pluginId 为空时抛出
     */
    public PluginContext pluginContext(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            throw new JellyfishException("pluginId must not be blank");
        }
        return new PluginContextImpl(pluginId, callbackRegistry, eventRegistry, this);
    }

    /**
     * 创建默认通知线程池：有界队列 + 自定义拒绝策略 + 守护线程。
     *
     * @param options 总线参数
     * @param stats   指标
     * @return 通知线程池
     */
    private static Executor createExecutor(EventBusOptions options, EventBusStats stats) {
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

    /**
     * 校验总线已启动。
     */
    private void ensureStarted() {
        if (!started) {
            throw new JellyfishException("event bus is not started, call start() before invoke");
        }
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
     * 提交通知到线程池，并做 MDC 透传。
     *
     * @param event 通知事件
     */
    private void enqueue(JellyfishEvent event) {
        try {
            notifier.execute(wrap(event));
        } catch (RejectedExecutionException e) {
            stats.droppedEvents.increment();
            LOG.warn("通知提交被拒绝: {}", event.getClass().getName(), e);
        }
    }

    /**
     * 包装通知派发任务：快照并透传 MDC。
     *
     * @param event 通知事件
     * @return 可在通知线程执行的任务
     */
    private Runnable wrap(JellyfishEvent event) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> dispatchNotification(event, context);
    }

    /**
     * 在通知线程恢复 MDC 后派发事件，派发结束恢复原上下文。
     *
     * @param event   通知事件
     * @param context 发布线程的 MDC 快照，可为 {@code null}
     */
    private void dispatchNotification(JellyfishEvent event, Map<String, String> context) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        applyMdc(context);
        try {
            delegate.post(event);
        } finally {
            applyMdc(previous);
        }
    }

    /**
     * 应用 MDC 上下文。
     *
     * @param context MDC 快照，为空表示清空
     */
    private static void applyMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * 关闭通知线程池并等待排空。
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
     * 注册带注解的回调订阅者。
     *
     * @param owner        来源
     * @param subscriber   订阅者对象
     * @param method       订阅方法
     * @param callbackType 回调参数类型
     * @return 注册句柄
     */
    private Subscription registerCallbackSubscriber(String owner, Object subscriber, Method method, Class<?> callbackType) {
        if (Modifier.isAbstract(callbackType.getModifiers())) {
            throw new JellyfishException("abstract callback subscriber is forbidden: " + method);
        }
        method.setAccessible(true);
        CallbackHandler<Callback<Object>, Object> handler = callback -> invokeCallbackSubscriber(subscriber, method, callback);
        @SuppressWarnings("unchecked")
        Class<Callback<Object>> type = (Class<Callback<Object>>) callbackType;
        return callbackRegistry.register(owner, false, type, null, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册带注解的通知订阅者。
     *
     * @param owner     来源
     * @param subscriber 订阅者对象
     * @param method    订阅方法
     * @param eventType 通知参数类型
     * @return 注册句柄
     */
    private Subscription registerEventSubscriber(String owner, Object subscriber, Method method, Class<?> eventType) {
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Class<JellyfishEvent> type = (Class<JellyfishEvent>) eventType;
        return eventRegistry.subscribe(owner, type, null,
                event -> invokeEventSubscriber(subscriber, method, event));
    }

    /**
     * 反射调用回调订阅方法，解包 InvocationTargetException 以保留原始异常。
     *
     * @param subscriber 订阅者对象
     * @param method     订阅方法
     * @param callback   回调对象
     * @return 回调结果
     * @throws Exception 订阅方法抛出的原始异常
     */
    private static Object invokeCallbackSubscriber(Object subscriber, Method method, Callback<?> callback) throws Exception {
        try {
            return method.invoke(subscriber, callback);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    /**
     * 反射调用通知订阅方法，解包 InvocationTargetException 以保留原始异常。
     *
     * @param subscriber 订阅者对象
     * @param method     订阅方法
     * @param event      通知事件
     */
    private static void invokeEventSubscriber(Object subscriber, Method method, Object event) {
        try {
            method.invoke(subscriber, event);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new JellyfishException("subscriber method failed: " + method, cause);
        } catch (IllegalAccessException e) {
            throw new JellyfishException("cannot access subscriber method: " + method, e);
        }
    }

    /**
     * 收集并缓存订阅者类的 {@code @Subscribe} 方法。
     *
     * @param subscriberClass 订阅者类
     * @return 订阅方法列表，不可修改
     * @throws JellyfishException 订阅方法参数个数不为 1 时抛出
     */
    private List<Method> subscribeMethods(Class<?> subscriberClass) {
        List<Method> cached = subscribeMethodCache.get(subscriberClass);
        if (cached != null) {
            return cached;
        }
        Map<String, Method> collected = new LinkedHashMap<>();
        for (Class<?> current = subscriberClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            collectSubscribeMethods(current, collected);
        }
        collectInterfaceMethods(subscriberClass, collected);
        List<Method> methods = new ArrayList<>(collected.values());
        for (Method method : methods) {
            if (method.getParameterTypes().length != 1) {
                throw new JellyfishException("subscribe method must have exactly one parameter: " + method);
            }
        }
        List<Method> immutable = Collections.unmodifiableList(methods);
        List<Method> raced = subscribeMethodCache.putIfAbsent(subscriberClass, immutable);
        return raced == null ? immutable : raced;
    }

    /**
     * 收集单个类上声明的订阅方法，按签名去重以处理覆写。
     *
     * @param type   类或接口
     * @param target 收集目标
     */
    private static void collectSubscribeMethods(Class<?> type, Map<String, Method> target) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || !method.isAnnotationPresent(Subscribe.class)) {
                continue;
            }
            String signature = method.getName() + Arrays.toString(method.getParameterTypes());
            target.putIfAbsent(signature, method);
        }
    }

    /**
     * 递归收集接口上声明的订阅方法。
     *
     * @param type   类或接口
     * @param target 收集目标
     */
    private static void collectInterfaceMethods(Class<?> type, Map<String, Method> target) {
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectSubscribeMethods(interfaceType, target);
            collectInterfaceMethods(interfaceType, target);
        }
        if (type.getSuperclass() != null) {
            collectInterfaceMethods(type.getSuperclass(), target);
        }
    }

    /**
     * 复合订阅句柄：关闭时依次解除全部子注册，幂等。
     *
     * @author zcd
     */
    private static final class CompositeSubscription implements Subscription {

        /** 子句柄。 */
        private final List<Subscription> handles;

        /** 幂等标记。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 构造复合句柄。
         *
         * @param handles 子句柄
         */
        CompositeSubscription(List<Subscription> handles) {
            this.handles = handles;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                for (Subscription handle : handles) {
                    handle.close();
                }
            }
        }
    }
}
