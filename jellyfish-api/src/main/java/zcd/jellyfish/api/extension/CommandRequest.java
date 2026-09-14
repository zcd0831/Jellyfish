package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Objects;

/**
 * 具名命令请求：名称 + 载荷 + 结果类型，由插件或核心组件按命令名注册处理器。
 * <p>
 * 路由键即命令名，因此每个命令对应一个处理器（{@code PluginContext.handle}）。
 * <p>
 * 用「名称 + 载荷」而不是让插件定义新的 Java 请求类型，是因为插件自定义类跨 ClassLoader
 * 传播会导致类型互不可见，内核也无法为未知类型提供调用点。
 * <p>
 * 名字里的「命令」<b>不是「只有插件能用」</b>：核心组件同样可以注册同名处理器，
 * 方向始终是内核在调用点构造本请求、处理器响应。
 *
 * @author zcd
 */
public final class CommandRequest extends ExtensionRequest<Object> {

    /** 命令名，如 {@code "sql:query"}。 */
    private final String name;

    /** 载荷原始类型，用于反序列化与运行时校验。 */
    private final Class<?> payloadType;

    /** 载荷对象，由 {@code ObjectMapperWrapper} 按 payloadType 绑定。 */
    private final Object payload;

    /**
     * 构造命令请求。
     *
     * @param name       命令名，不可为空
     * @param payloadType 载荷类型，不可为 {@code null}
     * @param payload    载荷对象，可为 {@code null}
     * @param sessionId  会话标识，可为 {@code null}
     * @throws JellyfishException 命令名为空、载荷类型为空或载荷与类型不匹配时抛出
     */
    public CommandRequest(String name, Class<?> payloadType, Object payload, String sessionId) {
        super(Object.class, sessionId);
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("command name must not be blank");
        }
        this.name = name;
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType must not be null");
        if (payload != null && !payloadType.isInstance(payload)) {
            throw new JellyfishException("payload type mismatch: expected " + payloadType.getName()
                    + " but got " + payload.getClass().getName());
        }
        this.payload = payload;
    }

    /**
     * 构造进程级命令请求。
     *
     * @param name        命令名，不可为空
     * @param payloadType 载荷类型，不可为 {@code null}
     * @param payload     载荷对象，可为 {@code null}
     * @throws JellyfishException 命令名为空、载荷类型为空或载荷与类型不匹配时抛出
     */
    public CommandRequest(String name, Class<?> payloadType, Object payload) {
        this(name, payloadType, payload, null);
    }

    @Override
    public String getRouteKey() {
        return name;
    }

    /**
     * 获取命令名。
     *
     * @return 命令名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取载荷类型。
     *
     * @return 载荷类型
     */
    public Class<?> getPayloadType() {
        return payloadType;
    }

    /**
     * 获取载荷对象。
     *
     * @return 载荷对象，可能为 {@code null}
     */
    public Object getPayload() {
        return payload;
    }
}
