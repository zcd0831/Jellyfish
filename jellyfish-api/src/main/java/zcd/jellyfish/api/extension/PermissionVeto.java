package zcd.jellyfish.api.extension;

/**
 * 拦截裁定：插件在权限检查扩展点上唯一能表达的结果，只有「不拦截 / 拦截」两态。
 * <p>
 * <b>为什么不是 {@link PermissionDecision}</b>：判定结论（含 {@code ASK}）只能由内核的核心策略给出。
 * 插件既不能放宽核心策略，也不能要求人工审批，因此这里刻意不提供 {@code ASK} 与「放行」，
 * 让越界的能力在编译期就写不出来。
 * <p>
 * <b>不拦截不等于放行</b>：放行权始终在核心策略手里，插件只能往「更严」的方向施加影响——
 * 这就是「权限拦截只支持 Deny」的落地方式。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PermissionVeto {

    /** 是否拦截。 */
    private final boolean denied;

    /** 拦截理由，不拦截时为 {@code null}。 */
    private final String reason;

    /**
     * 构造裁定。
     *
     * @param denied 是否拦截
     * @param reason 拦截理由，不拦截时可为 {@code null}
     */
    private PermissionVeto(boolean denied, String reason) {
        this.denied = denied;
        this.reason = reason;
    }

    /**
     * 构造「不拦截」裁定：表示本次拦截不提出异议。
     *
     * @return 不拦截
     */
    public static PermissionVeto none() {
        return new PermissionVeto(false, null);
    }

    /**
     * 构造「拦截」裁定。
     *
     * @param reason 拦截理由，可为 {@code null}
     * @return 拦截
     */
    public static PermissionVeto deny(String reason) {
        return new PermissionVeto(true, reason);
    }

    /**
     * 判断是否拦截。
     *
     * @return 拦截返回 {@code true}
     */
    public boolean isDenied() {
        return denied;
    }

    /**
     * 获取拦截理由。
     *
     * @return 拦截理由，不拦截时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PermissionVeto{denied=" + denied + ", reason=" + reason + '}';
    }
}
