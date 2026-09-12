package zcd.jellyfish.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 通知事件公共基类：统一维护事件标识、发生时间与会话标识。
 * <p>
 * 所有进程内通知都带这三个元信息，抽到基类避免每个事件重复实现；子类只需关注业务字段。
 *
 * @author zcd
 */
public abstract class AbstractJellyfishEvent implements JellyfishEvent {

    /** 事件唯一标识。 */
    private final String eventId;

    /** 事件发生时间戳（毫秒）。 */
    private final long occurredAt;

    /** 会话标识，进程级事件为 {@code null}。 */
    private final String sessionId;

    /**
     * 构造事件公共部分。
     *
     * @param sessionId 会话标识，可为 {@code null}
     */
    protected AbstractJellyfishEvent(String sessionId) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = System.currentTimeMillis();
        this.sessionId = sessionId;
    }

    @Override
    public final String getEventId() {
        return eventId;
    }

    @Override
    public final long getOccurredAt() {
        return occurredAt;
    }

    @Override
    public final String getSessionId() {
        return sessionId;
    }

    /**
     * 判断事件是否属于指定会话。
     *
     * @param targetSessionId 目标会话标识
     * @return 会话标识相等返回 {@code true}
     */
    public final boolean belongsToSession(String targetSessionId) {
        return Objects.equals(sessionId, targetSessionId);
    }
}
