package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 回调派发上下文：当前在途回调栈、嵌套深度与指标引用。
 * <p>
 * 同步回调在调用者线程内联执行，因此用 {@link ThreadLocal} 保存调用栈，
 * 让 {@link CallbackDispatcher} 与 {@link EventDispatchExceptionHandler} 共享上下文，
 * 避免字段在多个协作对象之间互相穿透。栈结构保证「回调嵌套回调」时内层退出不会清掉外层的在途回调。
 *
 * @author zcd
 */
final class DispatchContext {

    /** 当前线程的在途回调栈。 */
    private final ThreadLocal<Deque<ExtensionRequest<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /** 回调嵌套深度上限。 */
    private final int maxDepth;

    /** 指标引用。 */
    private final EventBusStats stats;

    /**
     * 构造派发上下文。
     *
     * @param maxDepth 嵌套深度上限
     * @param stats    指标引用
     */
    DispatchContext(int maxDepth, EventBusStats stats) {
        this.maxDepth = maxDepth;
        this.stats = stats;
    }

    /**
     * 获取当前线程正在派发的回调。
     *
     * @return 当前在途回调，调用栈为空时返回 {@code null}
     */
    ExtensionRequest<?> current() {
        Deque<ExtensionRequest<?>> current = stack.get();
        return current.isEmpty() ? null : current.peek();
    }

    /**
     * 进入一次回调派发。
     *
     * @param callback 回调对象
     * @throws ExtensionException 嵌套深度超过上限时抛出
     */
    void enter(ExtensionRequest<?> callback) {
        Deque<ExtensionRequest<?>> current = stack.get();
        if (current.size() >= maxDepth) {
            stats.nestingRejectedCallbacks.increment();
            throw new JellyfishException("callback nesting too deep: max depth " + maxDepth
                    + " reached at " + callback.getRouteKey());
        }
        current.push(callback);
    }

    /**
     * 退出一次回调派发。
     */
    void exit() {
        Deque<ExtensionRequest<?>> current = stack.get();
        if (!current.isEmpty()) {
            current.pop();
        }
        if (current.isEmpty()) {
            // 线程池线程会被复用，及时清理 ThreadLocal 避免残留引用
            stack.remove();
        }
    }

    /**
     * 获取指标引用。
     *
     * @return 指标引用
     */
    EventBusStats stats() {
        return stats;
    }
}
