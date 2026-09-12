package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 插件自定义通知的通用载体。
 * <p>
 * 与插件自定义命令同理：插件不定义新的通知 Java 类型，而是携带来源与载荷对象发布通知，
 * 避免插件自定义类跨 ClassLoader 传播。
 *
 * @author zcd
 */
public final class PluginNotificationEvent extends AbstractJellyfishEvent {

    /** 通知来源，通常是 pluginId。 */
    private final String source;

    /** 载荷对象，未提供时为 {@code null}。 */
    private final Object payload;

    /**
     * 构造插件通知事件。
     *
     * @param source    通知来源
     * @param payload   载荷对象，可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     */
    public PluginNotificationEvent(String source, Object payload, String sessionId) {
        super(sessionId);
        this.source = source;
        this.payload = payload;
    }

    /**
     * 获取通知来源。
     *
     * @return 通知来源
     */
    public String getSource() {
        return source;
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
