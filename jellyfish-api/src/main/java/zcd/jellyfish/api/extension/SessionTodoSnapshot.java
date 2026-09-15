package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话内一条待办的快照。
 * <p>
 * 状态用 {@code done} 布尔而不是字符串枚举：内核侧只有「未完成 / 已完成」两态，
 * 布尔是它的无损表达，也让落盘文件少一层枚举名与代码常量必须同步的耦合。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionTodoSnapshot {

    /** 会话内编号。 */
    private final String id;

    /** 待办内容。 */
    private final String content;

    /** 是否已完成。 */
    private final boolean done;

    /** 创建时间戳（epoch millis）。 */
    private final long createdAt;

    /**
     * 构造待办快照。
     *
     * @param id        会话内编号，不可为空白
     * @param content   待办内容，不可为空白
     * @param done      是否已完成
     * @param createdAt 创建时间戳（epoch millis）
     * @throws JellyfishException 编号或内容为空白时抛出
     */
    public SessionTodoSnapshot(String id, String content, boolean done, long createdAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new JellyfishException("todo id must not be blank");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new JellyfishException("todo content must not be blank");
        }
        this.id = id;
        this.content = content;
        this.done = done;
        this.createdAt = createdAt;
    }

    /**
     * 获取会话内编号。
     *
     * @return 编号
     */
    public String getId() {
        return id;
    }

    /**
     * 获取待办内容。
     *
     * @return 待办内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 判断是否已完成。
     *
     * @return 已完成返回 {@code true}
     */
    public boolean isDone() {
        return done;
    }

    /**
     * 获取创建时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "SessionTodoSnapshot{id=" + id + ", done=" + done + '}';
    }
}
