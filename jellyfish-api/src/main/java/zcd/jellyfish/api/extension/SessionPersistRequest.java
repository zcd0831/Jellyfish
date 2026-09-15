package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话持久化请求：内核把「这个会话现在长这样」交给插件，要求它落盘。
 * <p>
 * <b>结果类型是 {@link Void}</b>：持久化没有回传值——它要的不是一个答复，而是「这件事必须完成」。
 * 用 {@code Void} 而不是随便找个占位结果类，是为了让「无返回值」这件事在类型上就是确定的。
 * <p>
 * <b>失败必须上抛</b>：内核在「消息已追加」「会话已创建」之后同步派发本请求，
 * 处理器抛出的异常原样冒泡。这是刻意的——那一刻起「会话状态已变」与「状态已落盘」必须同生共死，
 * 静默吞掉只会让下一次启动悄悄少一段历史。
 * <p>
 * <b>为什么是类型级贡献而不是具名处理器</b>：同一份会话可以同时落文件、写数据库、推到远端，
 * 这些都是「都做」而不是「二选一」，因此用 {@code contribute} 注册、由内核按顺序逐个调用。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionPersistRequest extends ExtensionRequest<Void> {

    /** 要落盘的会话快照。 */
    private final SessionSnapshot snapshot;

    /**
     * 构造持久化请求。
     *
     * @param snapshot 会话快照，不可为 {@code null}
     * @throws JellyfishException 快照为 {@code null} 时抛出
     */
    public SessionPersistRequest(SessionSnapshot snapshot) {
        super(Void.class, snapshot == null ? null : snapshot.getSessionId());
        if (snapshot == null) {
            throw new JellyfishException("session snapshot must not be null");
        }
        this.snapshot = snapshot;
    }

    @Override
    public String getRouteKey() {
        // 类型级请求：路由键恒为 null，这样类型级注册与任何路由键都能命中
        return null;
    }

    /**
     * 获取会话快照。
     *
     * @return 会话快照，保证非 {@code null}
     */
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public String toString() {
        return "SessionPersistRequest{sessionId=" + getSessionId() + '}';
    }
}
