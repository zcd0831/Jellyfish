package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandException;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 命令派发上下文：当前在途命令栈、嵌套深度与指标引用。
 * <p>
 * 同步命令在调用者线程内联执行，因此用 {@link ThreadLocal} 保存调用栈，
 * 让 {@link CommandDispatcher} 与 {@link EventDispatchExceptionHandler} 共享上下文，
 * 避免字段在多个协作对象之间互相穿透。栈结构保证「命令嵌套命令」时内层退出不会清掉外层的在途命令。
 *
 * @author zcd
 */
final class DispatchContext {

    /** 当前线程的在途命令栈。 */
    private final ThreadLocal<Deque<Command<?>>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /** 命令嵌套深度上限。 */
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
     * 获取当前线程正在派发的命令。
     *
     * @return 当前在途命令，调用栈为空时返回 {@code null}
     */
    Command<?> current() {
        Deque<Command<?>> current = stack.get();
        return current.isEmpty() ? null : current.peek();
    }

    /**
     * 进入一次命令派发。
     *
     * @param command 命令对象
     * @throws CommandException 嵌套深度超过上限时抛出
     */
    void enter(Command<?> command) {
        Deque<Command<?>> current = stack.get();
        if (current.size() >= maxDepth) {
            stats.nestingRejectedCommands.increment();
            throw new CommandException(CommandException.Code.NESTING_TOO_DEEP,
                    "max depth " + maxDepth + " reached at " + command.getRouteKey());
        }
        current.push(command);
    }

    /**
     * 退出一次命令派发。
     */
    void exit() {
        Deque<Command<?>> current = stack.get();
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
