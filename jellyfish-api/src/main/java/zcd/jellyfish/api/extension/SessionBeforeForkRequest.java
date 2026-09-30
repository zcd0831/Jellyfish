package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话分支前请求：内核在<b>复制任何消息之前</b>询问「现在可以从这一点分出去吗」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：只要装了插件，每一次 fork 都会问一遍。
 * <p>
 * <b>为什么要有它</b>：fork 会复制出一份并列的历史，而插件常需要先确认「现在这份状态配不配被复制」
 * （工作区有没有未提交的改动、正在跑的检查点是否已经落盘、这个会话是不是还没走完引导流程）。
 * 没有这个落点，插件只能在事后（{@code SessionCreatedEvent}）发现，那时新会话已经在磁盘上了。
 * <p>
 * <b>否决一定会被采纳</b>：与 {@code SessionBeforeCloseRequest} 不同，fork 是一条<b>用户或插件显式发起</b>
 * 的动作，不是进程收尾路径，因此不存在「否决只会把资源留在表里」的顾虑——
 * 拦下就是拦下，什么都不复制、不落盘。
 * <p>
 * <b>必须快且不得阻塞</b>：handler 在调用线程上同步执行，且此刻可能持有会话锁。
 * 只能做只读的检查，不得回调内核、不得发布事件。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 * @see LifecycleVerdict
 */
public final class SessionBeforeForkRequest extends ExtensionRequest<LifecycleVerdict> {

    /** 源会话所属的 agentId，未绑定时为 {@code null}。 */
    private final String agentId;

    /** 分支点消息标识，{@code null} 表示从末尾分支。 */
    private final String messageId;

    /** 对齐后的实际切点下标（含），即复制范围的最后一条消息。 */
    private final int cutIndex;

    /** 本次将要复制的消息条数。 */
    private final int messageCount;

    /**
     * 构造会话分支前请求。
     * <p>
     * <b>给的是对齐之后的切点</b>：插件看到的 {@code cutIndex} 就是内核真正要复制到的那一条，
     * 而不是调用方原始指定的那一条。两者不一致时（原始切点落在工具调用组中间），
     * 插件据此看到的才是事实。
     *
     * @param sessionId    源会话标识，不可为空白
     * @param agentId      源会话所属的 agentId，可为 {@code null}
     * @param messageId    分支点消息标识，可为 {@code null}（表示从末尾分支）
     * @param cutIndex     对齐后的切点下标（含）
     * @param messageCount 将要复制的消息条数
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public SessionBeforeForkRequest(String sessionId, String agentId, String messageId, int cutIndex,
                                    int messageCount) {
        super(LifecycleVerdict.class, sessionId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("session id must not be blank");
        }
        this.agentId = agentId;
        this.messageId = messageId;
        this.cutIndex = cutIndex;
        this.messageCount = messageCount;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取源会话所属的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取调用方指定的分支点消息标识。
     *
     * @return 消息标识；从末尾分支时为 {@code null}
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * 获取对齐后的切点下标（含）。
     *
     * @return 切点下标；从末尾分支时是最后一条消息的下标（空会话为 {@code -1}）
     */
    public int getCutIndex() {
        return cutIndex;
    }

    /**
     * 获取将要复制的消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }
}
