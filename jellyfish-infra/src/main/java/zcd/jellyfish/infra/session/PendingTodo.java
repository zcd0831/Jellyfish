package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话级待办项：计划模式下「还有哪些事没做完」的载体。
 * <p>
 * <b>为什么是会话级而不是进程级</b>：待办天然跟着对话走——换了会话就该换一份待办。
 * 因此它归 {@link Session} 持有，跨会话互不影响。
 * <p>
 * <b>为什么不写回消息历史</b>：待办只在构建「本轮上下文」时注入（见 {@code core/prompt}），
 * 不落进 {@code Session.messages}；否则每一轮都会把同一批待办重复追加进历史，回放与 token 统计全部失真。
 * <p>
 * 不可变值对象：状态流转（{@code PENDING → DONE}）返回新实例，天然免锁、可安全跨线程传递。
 *
 * @author zcd
 */
public final class PendingTodo {

    /** 待办状态。 */
    public enum Status {

        /** 尚未完成。 */
        PENDING,

        /** 已完成。 */
        DONE
    }

    /** 会话内自增编号，供用户按编号标记完成。 */
    private final String id;

    /** 待办内容。 */
    private final String content;

    /** 当前状态。 */
    private final Status status;

    /** 创建时间戳（epoch millis）。 */
    private final long createdAt;

    /**
     * 构造待办项。
     *
     * @param id        会话内编号，不可为空白
     * @param content   待办内容，不可为空白
     * @param status    状态，不可为 {@code null}
     * @param createdAt 创建时间戳（epoch millis）
     * @throws JellyfishException 编号或内容为空白、状态为 {@code null} 时抛出
     */
    public PendingTodo(String id, String content, Status status, long createdAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new JellyfishException("todo id must not be blank");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new JellyfishException("todo content must not be blank");
        }
        if (status == null) {
            throw new JellyfishException("todo status must not be null");
        }
        this.id = id;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 构造一条「未完成」待办。
     *
     * @param id        会话内编号，不可为空白
     * @param content   待办内容，不可为空白
     * @param createdAt 创建时间戳（epoch millis）
     * @return 未完成待办
     * @throws JellyfishException 编号或内容为空白时抛出
     */
    public static PendingTodo pending(String id, String content, long createdAt) {
        return new PendingTodo(id, content, Status.PENDING, createdAt);
    }

    /**
     * 获取编号。
     *
     * @return 会话内编号
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
     * 获取状态。
     *
     * @return 状态
     */
    public Status getStatus() {
        return status;
    }

    /**
     * 获取创建时间戳。
     *
     * @return 创建时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 判断是否尚未完成。
     *
     * @return 状态为 {@link Status#PENDING} 返回 {@code true}
     */
    public boolean isPending() {
        return status == Status.PENDING;
    }

    /**
     * 判断是否已完成。
     *
     * @return 状态为 {@link Status#DONE} 返回 {@code true}
     */
    public boolean isDone() {
        return status == Status.DONE;
    }

    /**
     * 返回一条「已完成」的新待办，保留编号、内容与创建时间。
     *
     * @return 已完成待办；本就已完成时返回自身
     */
    public PendingTodo done() {
        return isDone() ? this : new PendingTodo(id, content, Status.DONE, createdAt);
    }

    @Override
    public String toString() {
        return "PendingTodo{id=" + id + ", status=" + status + ", content=" + content + '}';
    }
}
