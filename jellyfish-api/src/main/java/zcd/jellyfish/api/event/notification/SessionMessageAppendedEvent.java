package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话消息追加事件：消息落入会话后广播，供指标与 UI 增量刷新。
 * <p>
 * <b>不携带消息正文</b>：这是异步、可丢弃的 best-effort 通道，把对话内容塞进去既放大体积
 * 又扩大隐私面；需要正文的订阅者用 {@code sessionId} + {@code messageId} 回查会话。
 * 会话持久化（架构图 {@code SessionMgr ==> ExtReg}）走的是同步扩展点，不依赖本事件。
 *
 * @author zcd
 */
public final class SessionMessageAppendedEvent extends AbstractJellyfishEvent {

    /** 追加的消息标识。 */
    private final String messageId;

    /** 追加的消息角色。 */
    private final String role;

    /**
     * 构造消息追加事件。
     *
     * @param sessionId 会话标识
     * @param messageId 消息标识
     * @param role      消息角色
     */
    public SessionMessageAppendedEvent(String sessionId, String messageId, String role) {
        super(sessionId);
        this.messageId = messageId;
        this.role = role;
    }

    /**
     * 获取追加的消息标识。
     *
     * @return 消息标识
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * 获取追加的消息角色。
     *
     * @return 消息角色
     */
    public String getRole() {
        return role;
    }
}
