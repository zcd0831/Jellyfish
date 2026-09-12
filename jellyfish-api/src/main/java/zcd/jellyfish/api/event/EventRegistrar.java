package zcd.jellyfish.api.event;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 通知订阅入口：对插件暴露的细粒度通知订阅能力。
 * <p>
 * 通知是广播语义（0..N 个订阅者），重复订阅同一类型是合法行为。
 *
 * @author zcd
 */
public interface EventRegistrar {

    /**
     * 订阅指定类型的通知，广播语义（0..N）。
     *
     * @param eventType 通知类型
     * @param filter    过滤条件，为 {@code null} 时不过滤
     * @param listener  订阅者
     * @param <E>       通知类型
     * @return 订阅句柄
     */
    <E extends JellyfishEvent> Subscription subscribe(Class<E> eventType, Predicate<E> filter, Consumer<E> listener);

    /**
     * 订阅指定类型的通知，不做过滤。
     *
     * @param eventType 通知类型
     * @param listener  订阅者
     * @param <E>       通知类型
     * @return 订阅句柄
     */
    default <E extends JellyfishEvent> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
        return subscribe(eventType, null, listener);
    }
}
