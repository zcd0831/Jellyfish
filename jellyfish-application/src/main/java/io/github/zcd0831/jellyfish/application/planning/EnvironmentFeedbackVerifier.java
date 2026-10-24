package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;

/**
 * 第二层（ENV_FEEDBACK）的钩子：校验外部系统调用后的实际返回。
 * 本期没有这类 step，因此默认实现返回"无法判定"——**不假装通过**（模块-规划 §7.2）。
 */
public interface EnvironmentFeedbackVerifier {

    Outcome verify(PlanStep step, String summary);

    enum Outcome {
        PASSED,
        FAILED,
        UNDETERMINED
    }

    EnvironmentFeedbackVerifier NOT_CONFIGURED = new EnvironmentFeedbackVerifier() {
        @Override
        public Outcome verify(PlanStep step, String summary) {
            return Outcome.UNDETERMINED;
        }
    };
}
