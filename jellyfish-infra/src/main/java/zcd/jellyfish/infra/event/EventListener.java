package zcd.jellyfish.infra.event;

/**
 * 事件监听器：按事件类型订阅后由 {@link EventBus} 回调。
 *
 * @param <E> 监听的事件类型
 * @author zcd
 */
@FunctionalInterface
public interface EventListener<E> {

    /**
     * 处理事件。
     *
     * @param event 事件对象，不会为 {@code null}
     */
    void onEvent(E event);
}
