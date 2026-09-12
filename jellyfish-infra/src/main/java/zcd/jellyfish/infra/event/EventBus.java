package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步事件总线：按事件类型订阅与广播。
 * <p>
 * {@link #publish(Object)} 立即返回，实际派发在后台线程执行；订阅方按「订阅的类型可接收该事件实例」匹配，
 * 因此订阅父类型即可接收其子类型事件。监听器异常不会中断同一事件的其他监听器，会在派发结束后以
 * {@link JellyfishException} 抛出（后台线程场景下落到线程的未捕获异常处理器）。
 * <p>
 * 以 {@link Executor} 而非具体线程池为依赖，便于测试时注入「当前线程直接执行」的同步实现。
 *
 * @author zcd
 */
@Singleton
public class EventBus {

    /** 事件类型 → 监听器列表，支持并发读写。 */
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();

    /** 事件派发器。 */
    private final Executor dispatcher;

    /**
     * 构造异步事件总线，使用守护线程池派发事件，不阻止 JVM 退出。
     */
    @Inject
    public EventBus() {
        this(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "jellyfish-event");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /**
     * 使用自定义派发器的事件总线（例如 {@code Runnable::run} 可获得同步语义）。
     *
     * @param dispatcher 事件派发器
     */
    public EventBus(Executor dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 订阅指定类型的事件。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param <E>       事件类型
     * @throws JellyfishException 参数为 {@code null} 时抛出
     */
    public <E> void subscribe(Class<E> eventType, EventListener<E> listener) {
        if (eventType == null || listener == null) {
            throw new JellyfishException("event type and listener must not be null");
        }
        listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 取消订阅。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param <E>       事件类型
     */
    public <E> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        List<EventListener<?>> registered = listeners.get(eventType);
        if (registered != null) {
            registered.remove(listener);
        }
    }

    /**
     * 异步广播事件。
     *
     * @param event 事件对象
     * @throws JellyfishException 事件为 {@code null} 时抛出
     */
    public void publish(Object event) {
        if (event == null) {
            throw new JellyfishException("event must not be null");
        }
        dispatcher.execute(() -> dispatch(event));
    }

    /**
     * 把事件派发给所有匹配的监听器。
     *
     * @param event 事件对象
     */
    private void dispatch(Object event) {
        List<EventListener<?>> matched = new ArrayList<>();
        for (Map.Entry<Class<?>, List<EventListener<?>>> entry : listeners.entrySet()) {
            if (entry.getKey().isInstance(event)) {
                matched.addAll(entry.getValue());
            }
        }
        JellyfishException failure = null;
        for (EventListener<?> listener : matched) {
            try {
                invoke(listener, event);
            } catch (Exception e) {
                if (failure == null) {
                    failure = new JellyfishException("event listener failed for " + event.getClass().getName(), e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 以事件实际类型调用监听器。
     *
     * @param listener 监听器
     * @param event    事件对象
     */
    @SuppressWarnings("unchecked")
    private static void invoke(EventListener<?> listener, Object event) {
        ((EventListener<Object>) listener).onEvent(event);
    }
}
