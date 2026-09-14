package zcd.jellyfish.infra.event;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.notification.EventRegistry;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.RegistrySnapshot;
import zcd.jellyfish.infra.registry.TypeRegistry;

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
 * 交互枢纽门面：内核与插件之间的唯一交互入口，下辖同步扩展点与异步通知两条通道。
 * <p>
 * <b>过渡状态（方案 P3）</b>：同步扩展点已切到 {@link ExtensionRegistry} + {@link TypeRegistry}
 * 的「一份表」，异步通知暂时仍由 Guava EventBus 承载；方案 P4 会把异步侧也换成自有的
 * {@code EventChannel}（同一份 {@link TypeRegistry} + 线程池广播），届时本类整体删除。
 * <p>
 * {@link #invoke(ExtensionRequest)} 是过渡期的调用方：它自行遍历
 * {@link ExtensionRegistry#handlers(Class, String)} 的结果并逐个
 * {@link ExtensionRegistry#invoke(ExtensionHandler, ExtensionRequest)}，
 * 编排逻辑在本类而不是注册表里——这正是方案要求的「组合规则属于调用方」。
 * <p>
 * 生命周期：{@link #start()} 之前发布的异步通知进入缓冲队列，{@code start()} 时回放。
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

    /** 共用注册表：同步扩展点与异步通知最终都会落到这一份表上。 */
    private final TypeRegistry typeRegistry;

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensionRegistry;

    /** 细粒度通知注册表（过渡期仍由 Guava 派发）。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    /** 指标。 */
    private final EventBusStats stats;

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
        this.typeRegistry = new TypeRegistry();
        this.extensionRegistry = new ExtensionRegistry(typeRegistry);
        this.eventDispatcher = new EventDispatcher(eventRegistry, stats);
        this.delegate = new EventBus(new EventDispatchExceptionHandler(stats));
        this.delegate.register(eventDispatcher);
    }

    /**
     * 同步调用请求并返回首个处理器的结果（过渡期便捷入口）。
     * <p>
     * 编排逻辑刻意写在这里而不是注册表里：先有序查找全部命中的处理器，再在调用者线程内联逐个执行；
     * 首个处理器抛出异常即停止后续调用并原样上抛。新代码应直接用 {@link ExtensionRegistry}
     * 自行驱动调用顺序与结果合并，本方法只服务过渡期的既有调用点。
     *
     * @param request 请求对象
     * @param <R>     结果类型
     * @return 首个处理器的结果
     * @throws ExtensionException 没有任何处理器命中时抛出
     * @throws JellyfishException 总线未启动或请求为空时抛出
     */
    public <R> R invoke(ExtensionRequest<R> request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureStarted();
        List<ExtensionHandler<ExtensionRequest<R>, R>> handlers =
                extensionRegistry.handlers(castType(request), request.getRouteKey());
        if (handlers.isEmpty()) {
            stats.noHandlerCallbacks.increment();
            stats.failedCallbacks.increment();
            throw new ExtensionException(ExtensionException.Code.NO_HANDLER,
                    "type=" + request.getClass().getName() + " routeKey=" + request.getRouteKey());
        }
        R first = null;
        boolean answered = false;
        for (ExtensionHandler<ExtensionRequest<R>, R> handler : handlers) {
            R result;
            try {
                result = extensionRegistry.invoke(handler, request);
            } catch (RuntimeException | Error e) {
                stats.failedCallbacks.increment();
                throw e;
            }
            stats.dispatchedCallbacks.increment();
            if (!answered) {
                first = result;
                answered = true;
            }
        }
        return first;
    }

    /**
     * 把请求的运行时类转成带泛型的类型视图。
     * <p>
     * 索引需要 {@code Class<C>} 形态的键，而请求对象只提供 {@code getClass()}，此处做一次受限转换；
     * 类型安全由「同一请求类型的处理器注册时即声明了结果类型」保证。
     *
     * @param request 请求对象
     * @param <R>     结果类型
     * @return 请求的运行时类型
     */
    @SuppressWarnings("unchecked")
    private static <R> Class<ExtensionRequest<R>> castType(ExtensionRequest<R> request) {
        return (Class<ExtensionRequest<R>>) (Class<?>) request.getClass();
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
     * 注册门面内部订阅者（仅核心组件自用，插件请走 {@link #pluginContext(PluginDeclaration)}）。
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
            if (ExtensionRequest.class.isAssignableFrom(parameterType)) {
                handles.add(registerCallbackSubscriber(owner, subscriber, method, parameterType));
            } else if (JellyfishEvent.class.isAssignableFrom(parameterType)) {
                handles.add(registerEventSubscriber(owner, subscriber, method, parameterType));
            } else {
                throw new JellyfishException("subscriber parameter must be a JellyfishEvent or a ExtensionRequest: " + method);
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
     * 优雅关闭：停止接收、排空线程池、清理注册表。
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
        typeRegistry.clear();
        eventRegistry.clear();
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
        return extensionRegistry.snapshot();
    }

    /**
     * 获取插件注册入口，绑定 pluginId 作为 owner。
     * <p>
     * 注册边界由请求类型本身承载：插件拿不到的类型就注册不了，不需要额外的声明清单。
     * <p>
     * 本方法只做装配：上下文实现属于插件运行时（{@link PluginContextImpl} 在 {@code infra/plugin}），
     * 它是 {@code infra.event} 对 {@code infra.plugin} 的唯一反向引用，新增此类引用需先确认归属。
     * （方案 P4 会把这份装配搬进 {@code PluginContextFactory}，届时该反向引用一并消失。）
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @return 插件能力上下文
     * @throws JellyfishException 声明为 {@code null} 时抛出
     */
    public PluginContext pluginContext(PluginDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        return new PluginContextImpl(declaration, extensionRegistry, eventRegistry, this);
    }

    /**
     * 按 owner 批量回收注册：扩展点处理器与事件订阅一次清干净。
     * <p>
     * 插件卸载与停止的唯一回收入口。同步与异步分属两条策略，分两步清是刻意的：
     * 任何一步失败都不应该阻止另一步，否则会留下「一半还在表里」的幽灵注册。
     *
     * @param owner 来源标识，通常是 pluginId，不可为空白
     * @throws JellyfishException owner 为空白时抛出
     */
    public void unregisterAll(String owner) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new JellyfishException("owner must not be blank");
        }
        int handlers = extensionRegistry.unregisterAll(owner);
        int subscriptions = eventRegistry.unsubscribeAll(owner);
        LOG.info("已回收注册: owner={} handlers={} subscriptions={}", owner, handlers, subscriptions);
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
     * 提交通知到线程池。
     * <p>
     * 不做调用线程上下文（如 MDC）的透传：事件自带 {@code sessionId} 等元信息，
     * 上下文属于事件数据而不是线程状态，因此通知线程不得依赖发布线程的 ThreadLocal。
     *
     * @param event 通知事件
     */
    private void enqueue(JellyfishEvent event) {
        try {
            notifier.execute(() -> delegate.post(event));
        } catch (RejectedExecutionException e) {
            stats.droppedEvents.increment();
            LOG.warn("通知提交被拒绝: {}", event.getClass().getName(), e);
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
     * 注册带注解的扩展点处理器。
     * <p>
     * 注解扫描是过渡期能力：新代码应直接持有 {@link ExtensionRegistry} 并注册类型化处理器，
     * 只有 {@code register(Object)} 这条内核组件入口还在用 {@code @Subscribe} 标记。
     *
     * @param owner        来源
     * @param subscriber   订阅者对象
     * @param method       订阅方法
     * @param requestType  请求参数类型
     * @return 注册句柄
     */
    private Subscription registerCallbackSubscriber(String owner, Object subscriber, Method method, Class<?> requestType) {
        if (Modifier.isAbstract(requestType.getModifiers())) {
            throw new JellyfishException("abstract request subscriber is forbidden: " + method);
        }
        method.setAccessible(true);
        ExtensionHandler<ExtensionRequest<Object>, Object> handler =
                request -> invokeCallbackSubscriber(subscriber, method, request);
        @SuppressWarnings("unchecked")
        Class<ExtensionRequest<Object>> type = (Class<ExtensionRequest<Object>>) requestType;
        return extensionRegistry.contribute(owner, type, null, handler, RegisterOptions.DEFAULT);
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
    private static Object invokeCallbackSubscriber(Object subscriber, Method method, ExtensionRequest<?> callback) throws Exception {
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
