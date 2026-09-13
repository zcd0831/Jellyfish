package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.JellyfishException;

import java.util.Objects;

/**
 * 插件自定义回调的通用载体：名称 + 载荷 + 结果类型。
 * <p>
 * 插件不定义新的 Java 回调类型，而是注册新的具名命令，避免插件自定义类跨 ClassLoader
 * 传播、以及核心为未知类型补 dispatcher 的问题。
 *
 * @author zcd
 */
@PluginExtensible
@ExtensionPoint(id = "command.provide", shape = ExtensionShape.PROVIDE)
public final class PluginRequest extends Callback<Object> {

    /** 命令名，如 {@code "sql:query"}。 */
    private final String name;

    /** 载荷原始类型，用于反序列化与运行时校验。 */
    private final Class<?> payloadType;

    /** 载荷对象，由 {@code ObjectMapperWrapper} 按 payloadType 绑定。 */
    private final Object payload;

    /**
     * 构造插件回调。
     *
     * @param name          命令名，不可为空
     * @param payloadType   载荷类型，不可为 {@code null}
     * @param payload       载荷对象，可为 {@code null}
     * @param sessionId     会话标识，可为 {@code null}
     * @param timeoutMillis 建议超时（毫秒），不大于 {@code 0} 表示不设截止时间
     * @throws JellyfishException 命令名为空、载荷类型为空或载荷与类型不匹配时抛出
     */
    public PluginRequest(String name, Class<?> payloadType, Object payload, String sessionId, long timeoutMillis) {
        super(Object.class, sessionId, timeoutMillis);
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("plugin callback name must not be blank");
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
     * 构造不带截止时间的插件回调。
     *
     * @param name        命令名，不可为空
     * @param payloadType 载荷类型，不可为 {@code null}
     * @param payload     载荷对象，可为 {@code null}
     */
    public PluginRequest(String name, Class<?> payloadType, Object payload) {
        this(name, payloadType, payload, null, 0L);
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
