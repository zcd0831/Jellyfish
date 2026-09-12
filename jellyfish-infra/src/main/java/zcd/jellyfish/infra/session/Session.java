package zcd.jellyfish.infra.session;

import java.util.List;

/**
 * 一次会话，持有会话内消息列表。
 * <p>
 * 当前为内存态占位实现，会话隔离与持久化由后续 {@code SessionManager} 补全。
 *
 * @author zcd
 */
public class Session {

    /** 会话唯一标识。 */
    private String sessionId;

    /** 会话内消息列表，按时间顺序排列。 */
    private List<Message> messages;

    /**
     * 获取会话唯一标识。
     *
     * @return 会话唯一标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话唯一标识。
     *
     * @param sessionId 会话唯一标识
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 获取会话消息列表。
     *
     * @return 会话消息列表
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * 设置会话消息列表。
     *
     * @param messages 会话消息列表
     */
    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
}
