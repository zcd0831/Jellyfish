package zcd.jellyfish.api.extension;

/**
 * 会话恢复请求：内核在启动期问插件「你手上有哪些会话可以恢复」。
 * <p>
 * <b>进程级请求</b>：没有 sessionId（恢复的对象就是会话本身），因此构造器不带参数。
 * <p>
 * <b>与 {@link SessionPersistRequest} 成对</b>：一个管「存」，一个管「取」。两者都走类型级贡献，
 * 因此同一份会话可以被多个插件同时备份，恢复时则把各家交回的会话合并进内核。
 * <p>
 * <b>调用时机</b>：内核在插件启动<b>之后</b>才发本请求——插件必须先注册处理器，再被问。
 * 处理器抛出的异常由内核调用点决定处置（当前为记录告警并跳过该插件的恢复），
 * 因为「某个插件的备份读不出来」不该阻断整个进程启动。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionRestoreRequest extends ExtensionRequest<SessionRestoreResult> {

    /**
     * 构造进程级恢复请求。
     */
    public SessionRestoreRequest() {
        super(SessionRestoreResult.class, null);
    }

    @Override
    public String getRouteKey() {
        // 类型级请求：路由键恒为 null
        return null;
    }
}
