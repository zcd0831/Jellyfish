package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话删除请求：内核把「这个会话已经不要了」交给插件，要求它清掉自己那一份存储。
 * <p>
 * <b>为什么必须是一个独立的扩展点，而不是只把会话从内存里移除</b>：会话的真相在插件的存储里
 * （{@code session-file} 插件一个会话一个 JSON 文件）。只删内存的话，下一次启动
 * {@link SessionRestoreRequest} 会把文件读回来——用户看到的「删除」在下一次启动时复活，
 * 这比没有删除更糟。
 * <p>
 * <b>结果类型是 {@link Void}</b>：删除没有回传值——它要的不是一个答复，而是「这件事必须完成」。
 * 与 {@link SessionPersistRequest} 同形，理由也相同。
 * <p>
 * <b>失败必须上抛</b>：内核在「先派发删除、再移出会话表」的次序下调用本请求，
 * 处理器抛出的异常原样冒泡。这是刻意的——删不掉就当没删，会话仍留在内存里、状态保持一致，
 * 而不是出现「界面说删了、文件还在、重启复活」这种三处对不上的状态。
 * <p>
 * <b>为什么是类型级贡献而不是具名处理器</b>：同一次删除可能需要在多个插件里各清一份
 * （会话文件、待办文件……），这些是「都做」而不是「二选一」，因此用 {@code contribute} 注册、
 * 由内核按顺序逐个调用。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionDeleteRequest extends ExtensionRequest<Void> {

    /**
     * 构造删除请求。
     *
     * @param sessionId 要删除的会话标识，不可为空白
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public SessionDeleteRequest(String sessionId) {
        super(Void.class, sessionId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("sessionId must not be blank");
        }
    }

    @Override
    public String getRouteKey() {
        // 类型级请求：路由键恒为 null，这样类型级注册与任何路由键都能命中
        return null;
    }

    @Override
    public String toString() {
        return "SessionDeleteRequest{sessionId=" + getSessionId() + '}';
    }
}
