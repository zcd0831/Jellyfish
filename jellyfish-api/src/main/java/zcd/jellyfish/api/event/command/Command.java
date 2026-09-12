package zcd.jellyfish.api.event.command;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 同步命令基类：只承载请求数据，是纯只读的请求消息。
 * <p>
 * 命令是「请求-响应」语义，但应答槽不在本类上：结果由框架内部的
 * {@code zcd.jellyfish.infra.event.CommandReplies} 按 {@link #getCommandId()} 保管。
 * 这样插件作者拿到命令对象时只看到请求数据，看不到、也无法误用框架内部的应答机制；
 * 处理器的正向契约是 {@link CommandHandler}——直接返回结果或抛出异常。
 *
 * @param <R> 命令结果类型
 * @author zcd
 */
public abstract class Command<R> {

    /** 命令唯一标识，用于日志关联与应答槽索引。 */
    private final String commandId;

    /** 命令结果类型，用于抵消泛型擦除后的运行时校验。 */
    private final Class<R> resultType;

    /** 会话标识，进程级命令为 {@code null}。 */
    private final String sessionId;

    /** 截止时间（{@code System.nanoTime()} 基准），{@code 0} 表示无截止时间，供处理器自律超时。 */
    private final long deadlineNanos;

    /**
     * 构造命令。
     *
     * @param resultType    结果类型，不可为 {@code null}
     * @param sessionId     会话标识，可为 {@code null}
     * @param timeoutMillis 建议超时（毫秒），不大于 {@code 0} 表示不设截止时间
     */
    protected Command(Class<R> resultType, String sessionId, long timeoutMillis) {
        this.commandId = UUID.randomUUID().toString();
        this.resultType = Objects.requireNonNull(resultType, "resultType must not be null");
        this.sessionId = sessionId;
        this.deadlineNanos = timeoutMillis > 0L
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                : 0L;
    }

    /**
     * 获取命令类型级的路由键。同一命令类型下，路由键相同的处理器至多一个。
     *
     * @return 路由键；返回 {@code null} 表示该命令类型全局唯一
     */
    public abstract String getRouteKey();

    /**
     * 获取命令唯一标识。
     *
     * @return 命令唯一标识
     */
    public final String getCommandId() {
        return commandId;
    }

    /**
     * 获取命令结果类型，用于抵消泛型擦除后的运行时校验。
     *
     * @return 结果类型
     */
    public final Class<R> getResultType() {
        return resultType;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识，进程级命令为 {@code null}
     */
    public final String getSessionId() {
        return sessionId;
    }

    /**
     * 获取截止时间（{@code System.nanoTime()} 基准）。
     *
     * @return 截止时间；{@code 0} 表示未设置
     */
    public final long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 判断是否已过截止时间。
     * <p>
     * 同步命令在调用者线程内联执行，框架无法从外部打断处理器，因此该判定只作为处理器自律超时的参考值。
     *
     * @return 已过期返回 {@code true}；未设置截止时间时恒为 {@code false}
     */
    public final boolean isExpired() {
        return deadlineNanos != 0L && System.nanoTime() > deadlineNanos;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + commandId + ", routeKey=" + getRouteKey() + '}';
    }
}
