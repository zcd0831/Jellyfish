package zcd.jellyfish.api.extension;

/**
 * 权限判定结果：内核侧给出的终态结论，三态。
 * <p>
 * 与 {@link PermissionVeto} 的分工是刻意的：判定结论只能由内核的核心策略给出，
 * 插件侧拿到的结果类型只有「不拦截 / 拦截」两态，**写不出 ASK**——插件既不能放宽核心策略，
 * 也不能要求人工审批，能力边界由类型本身承载，而不是靠运行期判定。
 * <p>
 * {@code ASK} 的处理见 {@code PermissionManager}：审批通道未落地时它会被降级为 {@code DENY}，
 * 绝不降级为放行。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PermissionDecision {

    /** 判定三态。 */
    public enum Outcome {

        /** 放行。 */
        ALLOW,

        /** 拒绝。 */
        DENY,

        /**
         * 需要人工审批。
         * <p>
         * 这不是终态：审批通道未落地时内核会把它降级为 {@link #DENY}，因此调用点不需要处理 ASK。
         */
        ASK
    }

    /** 判定结论。 */
    private final Outcome outcome;

    /** 判定理由，用于审计与提示。 */
    private final String reason;

    /**
     * 构造判定结果。
     *
     * @param outcome 判定结论
     * @param reason  判定理由，可为 {@code null}
     */
    private PermissionDecision(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    /**
     * 构造放行判定。
     *
     * @param reason 判定理由，可为 {@code null}
     * @return 判定结果
     */
    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Outcome.ALLOW, reason);
    }

    /**
     * 构造拒绝判定。
     *
     * @param reason 拒绝理由，可为 {@code null}
     * @return 判定结果
     */
    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Outcome.DENY, reason);
    }

    /**
     * 构造「需要人工审批」判定。
     *
     * @param reason 说明理由，可为 {@code null}
     * @return 判定结果
     */
    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Outcome.ASK, reason);
    }

    /**
     * 获取判定结论。
     *
     * @return 判定结论
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * 判断是否放行。
     *
     * @return 放行返回 {@code true}
     */
    public boolean isAllowed() {
        return outcome == Outcome.ALLOW;
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
     * 判断是否需要人工审批。
     *
     * @return 需要审批返回 {@code true}
     */
    public boolean isAsk() {
        return outcome == Outcome.ASK;
    }

    /**
     * 获取判定理由。
     *
     * @return 判定理由，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PermissionDecision{outcome=" + outcome + ", reason=" + reason + '}';
    }
}
