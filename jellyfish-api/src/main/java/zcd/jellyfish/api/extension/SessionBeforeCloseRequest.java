package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话关闭前请求：内核在<b>最后一次落盘之前</b>询问「现在可以关吗」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：只要装了插件，每一次关闭都会问一遍。
 * <p>
 * <b>为什么要有它</b>：插件常需要在会话结束时做一次收尾（写检查点、导出记录、提示未提交的改动）。
 * 此前没有任何「会话要结束了」的同步落点——事件通道里的 {@code SessionClosedEvent} 是事后通知、
 * 而且可丢，插件在那上面做的收尾一旦丢了就悄悄没做。
 * <p>
 * <b>否决只在用户主动关闭时生效</b>：进程收尾、内部收尾与配置重载都不允许被插件拖住——
 * 那三种场合下否决一个关闭请求，结果只会是「资源留在了表里」，而不是「用户改变主意」。
 * 调用点据此忽略否决，见 {@link Reason}。
 * <p>
 * <b>是「关闭」不是「删除」</b>：删除走既有的会话删除扩展点，本钩子不覆盖它——
 * 「结束运行态、保留快照」与「连磁盘上那一份也不要了」是两件事。
 * <p>
 * <b>必须快且不得阻塞</b>：handler 在关闭路径的调用线程上同步执行，而那条路径可能是进程收尾。
 * 因此它只能做只读的检查与快速的收尾动作，不得回调内核、不得发布事件。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionBeforeCloseRequest extends ExtensionRequest<LifecycleVerdict> {

    /** 关闭原因：说明这次关闭是谁想要的。 */
    public enum Reason {
        /**
         * 用户主动要求关闭（例如切换会话时先关掉旧的）。
         * <p>
         * <b>只有这一档的否决会被内核采纳</b>——「用户想关、插件说先别关」是唯一有意义的否决场景。
         */
        USER_REQUEST,
        /** 配置重载：插件即将被重启，因此不允许被拖住。 */
        RELOAD,
        /** 进程收尾：关机路径一律不被插件拖住。 */
        SHUTDOWN,
        /**
         * 内核内部收尾（例如瞬时子代理会话跑完了）。
         * <p>
         * 它不由用户发起，也不改变任何用户可见的东西，因此同样忽略否决。
         */
        INTERNAL
    }

    /** 会话所属的 agentId，未绑定时为 {@code null}。 */
    private final String agentId;

    /** 关闭原因。 */
    private final Reason reason;

    /**
     * 构造会话关闭前请求。
     *
     * @param sessionId 会话标识，不可为空白
     * @param agentId   会话所属的 agentId，可为 {@code null}
     * @param reason    关闭原因，可为 {@code null}（按 {@link Reason#USER_REQUEST} 处理）
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public SessionBeforeCloseRequest(String sessionId, String agentId, Reason reason) {
        super(LifecycleVerdict.class, sessionId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("session id must not be blank");
        }
        this.agentId = agentId;
        this.reason = reason == null ? Reason.USER_REQUEST : reason;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取会话所属的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取关闭原因。
     *
     * @return 关闭原因，保证非 {@code null}
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * 判断本次关闭的否决是否会被内核采纳。
     * <p>
     * 插件通常不需要读它：无论哪一档，返回 {@link LifecycleVerdict#cancel(String)} 都是安全的，
     * 内核自己决定采不采纳。它存在只是为了让插件在明知不被采纳时少做无用功。
     *
     * @return 否决会被采纳返回 {@code true}
     */
    public boolean isVetoSupported() {
        return reason == Reason.USER_REQUEST;
    }
}
