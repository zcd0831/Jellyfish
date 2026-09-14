package zcd.jellyfish.api.extension;

import java.util.Objects;

/**
 * 扩展点请求基类：只承载请求数据，是纯只读的请求消息。
 * <p>
 * <b>没有 ID</b>：请求类型本身就是那层身份（类型即地址），内核在调用点构造具体子类交给注册表，
 * 注册表按「类型 + 路由键」找出插件注册的处理器。因此这里既没有需要事前声明的清单，
 * 也没有用于关联应答的标识——结果由处理器直接返回，不经框架中转。
 * <p>
 * <b>没有调用语义参数</b>：唯一性、顺序、执行位置、超时与失败语义都由插件调用的注册入口和内核的
 * 调用点决定，不在这里表达。{@link #getResultType()} 仅是抵消泛型擦除后的运行时校验依据。
 *
 * @param <R> 请求结果类型
 * @author zcd
 */
public abstract class ExtensionRequest<R> {

    /** 请求结果类型，用于抵消泛型擦除后的运行时校验。 */
    private final Class<R> resultType;

    /** 会话标识，进程级请求为 {@code null}。 */
    private final String sessionId;

    /**
     * 构造请求。
     *
     * @param resultType 结果类型，不可为 {@code null}
     * @param sessionId  会话标识，可为 {@code null}
     */
    protected ExtensionRequest(Class<R> resultType, String sessionId) {
        this.resultType = Objects.requireNonNull(resultType, "resultType must not be null");
        this.sessionId = sessionId;
    }

    /**
     * 获取请求的路由键。
     * <p>
     * 由内核按路由键分发的请求返回该键（工具名、命令名……）；
     * 类型级请求没有路由语义，恒返回 {@code null}。
     *
     * @return 路由键，可为 {@code null}
     */
    public abstract String getRouteKey();

    /**
     * 获取结果类型，用于抵消泛型擦除后的运行时校验。
     *
     * @return 结果类型
     */
    public final Class<R> getResultType() {
        return resultType;
    }

    /**
     * 获取会话标识。
     * <p>
     * 会话标识放在请求对象上而不是依赖调用线程的 ThreadLocal，因为处理器可能被内核在任意线程调用。
     *
     * @return 会话标识，进程级请求为 {@code null}
     */
    public final String getSessionId() {
        return sessionId;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{routeKey=" + getRouteKey() + '}';
    }
}
