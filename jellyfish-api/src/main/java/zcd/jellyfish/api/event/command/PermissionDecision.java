package zcd.jellyfish.api.event.command;

/**
 * 权限判定结果。
 *
 * @author zcd
 */
public final class PermissionDecision {

    /** 是否放行。 */
    private final boolean granted;

    /** 判定理由，用于审计与提示。 */
    private final String reason;

    /**
     * 构造权限判定结果。
     *
     * @param granted 是否放行
     * @param reason  判定理由，可为 {@code null}
     */
    private PermissionDecision(boolean granted, String reason) {
        this.granted = granted;
        this.reason = reason;
    }

    /**
     * 构造放行判定。
     *
     * @param reason 判定理由，可为 {@code null}
     * @return 判定结果
     */
    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, reason);
    }

    /**
     * 构造拒绝判定。
     *
     * @param reason 拒绝理由，可为 {@code null}
     * @return 判定结果
     */
    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }

    /**
     * 判断是否放行。
     *
     * @return 放行返回 {@code true}
     */
    public boolean isGranted() {
        return granted;
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
        return "PermissionDecision{granted=" + granted + ", reason=" + reason + '}';
    }
}
