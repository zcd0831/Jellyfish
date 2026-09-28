package zcd.jellyfish.api.extension;

/**
 * 权限拦截裁定：插件在权限检查扩展点上能表达的结果，三态。
 * <p>
 * <b>为什么不是 {@link PermissionDecision}</b>：判定结论（含「放行」）只能由内核的核心策略给出。
 * 本类<b>没有 ALLOW 这一态</b>，因此「插件既不能放宽核心策略，也不能自行放行」不是靠文档约定的纪律，
 * 而是类型上就写不出来——这正是把两态否决扩成三态裁定后依然守住的那条边界。
 * <p>
 * <b>三态的含义</b>：
 * <ul>
 *     <li>{@link Outcome#ABSTAIN}：无异议，不改变核心策略的结论；</li>
 *     <li>{@link Outcome#ASK}：把我拦不住的东西升级为人工审批。这一态只可能让调用<b>更严</b>——
 *     最终仍要经审批通道，无审批者 / 超时 / 拒绝一律按拒绝处理；</li>
 *     <li>{@link Outcome#DENY}：直接拒绝。</li>
 * </ul>
 * 多个插件同时表态时由 {@code PermissionManager} 取最严（{@code DENY > ASK > ABSTAIN}）。
 * <p>
 * <b>为什么需要 ASK 这一态</b>：只有两态时，「只读命令免打扰、写类命令要人看一眼」这类策略根本写不出来，
 * 用户只剩下「全放行（危险）」与「每次点批准（烦到关掉授权）」两个选择，而后者最终会退化成前者。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PermissionVerdict {

    /** 裁定三态。 */
    public enum Outcome {
        /** 无异议：不改变核心策略的结论。 */
        ABSTAIN,
        /**
         * 升级为人工审批。
         * <p>
         * 这一态只收紧不放宽：内核会向审批者提问，拿不到批准就降级为拒绝。
         */
        ASK,
        /** 直接拒绝。 */
        DENY
    }

    /** 单例：无异议裁定。 */
    private static final PermissionVerdict ABSTAIN = new PermissionVerdict(Outcome.ABSTAIN, null);

    /** 裁定结论。 */
    private final Outcome outcome;

    /** 裁定理由，无异议时为 {@code null}。 */
    private final String reason;

    /**
     * 构造裁定。
     *
     * @param outcome 裁定结论
     * @param reason  裁定理由，可为 {@code null}
     */
    private PermissionVerdict(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    /**
     * 构造「无异议」裁定：表示本次拦截不改变核心策略的结论。
     *
     * @return 无异议裁定
     */
    public static PermissionVerdict abstain() {
        return ABSTAIN;
    }

    /**
     * 构造「升级为人工审批」裁定。
     *
     * @param reason 升级理由，可为 {@code null}
     * @return 裁定
     */
    public static PermissionVerdict ask(String reason) {
        return new PermissionVerdict(Outcome.ASK, reason);
    }

    /**
     * 构造「拒绝」裁定。
     *
     * @param reason 拒绝理由，可为 {@code null}
     * @return 裁定
     */
    public static PermissionVerdict deny(String reason) {
        return new PermissionVerdict(Outcome.DENY, reason);
    }

    /**
     * 获取裁定结论。
     *
     * @return 裁定结论
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * 判断是否无异议。
     *
     * @return 无异议返回 {@code true}
     */
    public boolean isAbstain() {
        return outcome == Outcome.ABSTAIN;
    }

    /**
     * 判断是否要求人工审批。
     *
     * @return 要求审批返回 {@code true}
     */
    public boolean isAsk() {
        return outcome == Outcome.ASK;
    }

    /**
     * 判断是否拒绝。
     *
     * @return 拒绝返回 {@code true}
     */
    public boolean isDenied() {
        return outcome == Outcome.DENY;
    }

    /**
     * 获取裁定理由。
     *
     * @return 裁定理由，无异议时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PermissionVerdict{outcome=" + outcome + ", reason=" + reason + '}';
    }
}
