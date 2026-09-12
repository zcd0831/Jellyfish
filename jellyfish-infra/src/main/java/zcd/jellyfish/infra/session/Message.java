package zcd.jellyfish.infra.session;

/**
 * 会话中的单条消息。
 * <p>
 * 目前仅保留消息落库/回放所需的最小字段，待会话持久化落地后再扩展。
 *
 * @author zcd
 */
public class Message {

    /** 消息角色，如 system / user / assistant / tool。 */
    private String role;

    /** 消息文本内容。 */
    private String content;

    /** 消息产生时间（epoch millis）。 */
    private long timestamp;

    /**
     * 获取消息角色。
     *
     * @return 消息角色
     */
    public String getRole() {
        return role;
    }

    /**
     * 设置消息角色。
     *
     * @param role 消息角色
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 获取消息内容。
     *
     * @return 消息内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置消息内容。
     *
     * @param content 消息内容
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 获取消息时间戳。
     *
     * @return epoch millis
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置消息时间戳。
     *
     * @param timestamp epoch millis
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
