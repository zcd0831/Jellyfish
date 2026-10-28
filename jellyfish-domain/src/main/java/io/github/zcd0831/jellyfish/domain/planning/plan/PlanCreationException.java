package io.github.zcd0831.jellyfish.domain.planning.plan;

/**
 * `generate_plan` 的草案不合法：**预期内失败**，转成工具错误结果回灌模型，不消耗重试。
 * 规则出处：06-planning §6.8.1 规则 1、§6.9.4。
 */
public final class PlanCreationException extends RuntimeException {

    private final PlanningError error;

    public PlanCreationException(PlanningError error, String detail) {
        super(detail);
        this.error = error;
    }

    public PlanningError error() {
        return error;
    }
}
