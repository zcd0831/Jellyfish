package zcd.jellyfish.infra.event.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 细粒度通知注册表：按事件类型维护订阅者列表，支持过滤与按来源回收。
 * <p>
 * 与回调的「恰好一个」形成对照，通知是广播语义（0..N）：
 * <ul>
 *     <li>按 {@code isAssignableFrom} 匹配类型，因此订阅父类型即可收到子类型事件；</li>
 *     <li>按注册顺序逐个派发，单个订阅者异常被捕获并计数，不影响其他订阅者；</li>
 *     <li>没有任何订阅者命中时由调用方记 unmatched 指标，过滤不命中属于合法情况。</li>
 * </ul>
 *
 * @author zcd
 */
public final class EventRegistry {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventRegistry.class);

    /** 事件类型 → 订阅项列表，保持注册顺序。 */
    private final Map<Class<? extends JellyfishEvent>, CopyOnWriteArrayList<EventRegistration>> subscriptions
            = new ConcurrentHashMap<>();

    /**
     * 订阅通知。
     *
     * @param owner     来源（内置组件名或 pluginId）
     * @param eventType 通知类型
     * @param filter    过滤条件，可为 {@code null}
     * @param listener  订阅者
     * @param <E>       通知类型
     * @return 订阅句柄
     * @throws NullPointerException 事件类型或订阅者为空时抛出
     */
    public <E extends JellyfishEvent> Subscription subscribe(String owner, Class<E> eventType,
                                                             Predicate<E> filter, Consumer<E> listener) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Predicate<JellyfishEvent> safeFilter = filter == null
                ? event -> true
                : event -> eventType.isInstance(event) && filter.test(eventType.cast(event));
        Consumer<JellyfishEvent> safeListener = event -> listener.accept(eventType.cast(event));
        EventRegistration registration = new EventRegistration(owner, eventType, safeFilter, safeListener);
        subscriptions.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(registration);
        return new EventSubscription(this, eventType, registration);
    }

    /**
     * 按来源批量解除订阅。
     *
     * @param owner 来源标识
     * @return 解除的订阅数量
     */
    public int unsubscribeAll(String owner) {
        int removed = 0;
        for (CopyOnWriteArrayList<EventRegistration> registrations : subscriptions.values()) {
            for (EventRegistration registration : registrations) {
                if (Objects.equals(owner, registration.getOwner()) && registrations.remove(registration)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * 广播事件：单个订阅者异常不影响其他订阅者。
     *
     * @param event 通知事件
     * @return 派发结果
     */
    public EventDispatchResult dispatch(JellyfishEvent event) {
        int matched = 0;
        int errors = 0;
        for (Map.Entry<Class<? extends JellyfishEvent>, CopyOnWriteArrayList<EventRegistration>> entry
                : subscriptions.entrySet()) {
            if (!entry.getKey().isAssignableFrom(event.getClass())) {
                continue;
            }
            for (EventRegistration registration : entry.getValue()) {
                if (!registration.accepts(event)) {
                    continue;
                }
                matched++;
                try {
                    registration.invoke(event);
                } catch (RuntimeException e) {
                    errors++;
                    LOG.warn("通知订阅者异常: owner={} event={}", registration.getOwner(), event.getClass().getName(), e);
                }
            }
        }
        return new EventDispatchResult(matched, errors);
    }

    /**
     * 判断注册表是否为空。
     *
     * @return 无任何订阅返回 {@code true}
     */
    public boolean isEmpty() {
        for (CopyOnWriteArrayList<EventRegistration> registrations : subscriptions.values()) {
            if (!registrations.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清空注册表，用于总线关闭时释放引用。
     */
    public void clear() {
        subscriptions.clear();
    }

    /**
     * 渲染诊断视图。
     *
     * @return 多行文本，无订阅时返回空串
     */
    public String render() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("notifications:\n");
        for (Map.Entry<Class<? extends JellyfishEvent>, CopyOnWriteArrayList<EventRegistration>> entry
                : subscriptions.entrySet()) {
            for (EventRegistration registration : entry.getValue()) {
                builder.append("  ").append(entry.getKey().getSimpleName())
                        .append("  <- ").append(registration.getOwner()).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 解除单条订阅。
     *
     * @param eventType    事件类型
     * @param registration 订阅项
     */
    void remove(Class<? extends JellyfishEvent> eventType, EventRegistration registration) {
        CopyOnWriteArrayList<EventRegistration> registrations = subscriptions.get(eventType);
        if (registrations != null) {
            registrations.remove(registration);
        }
    }
}
