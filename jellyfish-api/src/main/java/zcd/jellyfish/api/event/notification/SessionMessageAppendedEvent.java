package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话消息追加事件：消息落入会话后广播，供指标与 UI 增量刷新。
 * <p>
 * <b>不携带消息正文</b>：这是异步、可丢弃的 best-effort 通道，把对话内容塞进去既放大体积
 * 又扩大隐私面；需要正文的订阅者用 {@code sessionId} + {@code messageId} 回查会话。
 * 会话持久化（架构图 {@code SessionMgr ==> ExtReg}）走的是同步扩展点，不依赖本事件。
 * <p>
 * <b>{@code parentSessionId} 是「派生自哪条会话」，不等于「它是子代理会话」</b>：它让订阅者不必自己维护
 * 「sessionId → 父会话」的映射（那会在错过创建事件时永久失真），但分支（{@code FORKED}）会话也有父——
 * 那是「从哪条会话分出来的」这条追溯信息。要区分会话种类请看 {@code SessionKind}。
 *
 * @author zcd
 */
public final class SessionMessageAppendedEvent extends AbstractJellyfishEvent {

    /** 追加的消息标识。 */
    private final String messageId;

    /** 追加的消息角色。 */
    private final String role;

    /**
     * 派生该会话的父会话标识，{@code null} 表示它没有父（根会话）。
     * <p>
     * <b>非 {@code null} 不等于子代理会话</b>：分支（{@code FORKED}）会话也带着它，那里只是追溯信息。
     */
    private final String parentSessionId;

    /**
     * 构造根会话的消息追加事件。
     *
     * @param sessionId 会话标识
     * @param messageId 消息标识
     * @param role      消息角色
     */
    public SessionMessageAppendedEvent(String sessionId, String messageId, String role) {
        this(sessionId, messageId, role, null);
    }

    /**
     * 构造消息追加事件。
     *
     * @param sessionId       会话标识
     * @param messageId       消息标识
     * @param role            消息角色
     * @param parentSessionId 派生该会话的父会话标识，{@code null} 表示它没有父（子代理会话与
     *                        分支会话都会有值，两者不是一回事）
     */
    public SessionMessageAppendedEvent(String sessionId, String messageId, String role, String parentSessionId) {
        super(sessionId);
        this.messageId = messageId;
        this.role = role;
        this.parentSessionId = parentSessionId;
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

    /**
     * 获取派生该会话的父会话标识。
     *
     * @return 父会话标识；没有父（根会话）时为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }
}
