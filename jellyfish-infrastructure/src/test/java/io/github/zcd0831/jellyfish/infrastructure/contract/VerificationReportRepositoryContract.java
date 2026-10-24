package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `VerificationReportRepository` 的契约：**只新建、不覆盖**。 */
public abstract class VerificationReportRepositoryContract {

    protected abstract VerificationReportRepository repository();

    protected abstract SessionRepository sessions();

    protected abstract PlanRepository plans();

    @BeforeEach
    void givenPlan() {
        sessions().save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
        plans().save(PlanRepositoryContract.plan("p1", io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus.EXECUTING));
    }

    private static VerificationReport report(String id, VerificationOutcome outcome) {
        return VerificationReport.decide(VerificationReportId.of(id), PlanId.of("p1"), StepId.of("step-p1"),
                1, outcome, outcome == VerificationOutcome.PASSED ? null : VerificationType.DETERMINISTIC,
                outcome == VerificationOutcome.PASSED ? null : "不合格", 10L);
    }

    @Test
    void 保存后可按id与步骤读回() {
        repository().save(report("r1", VerificationOutcome.PASSED));

        assertTrue(repository().get(VerificationReportId.of("r1")).isPresent());
        assertEquals(1, repository().findByStep(PlanId.of("p1"), StepId.of("step-p1")).size());
    }

    @Test
    void 重复保存同一份报告是幂等的() {
        repository().save(report("r1", VerificationOutcome.FAILED));
        repository().save(report("r1", VerificationOutcome.PASSED));

        assertEquals(VerificationOutcome.FAILED,
                repository().get(VerificationReportId.of("r1")).get().outcome(),
                "报告不可变：先写出的版本不能被覆盖");
    }

    @Test
    void 不存在时返回空() {
        assertFalse(repository().get(VerificationReportId.of("nope")).isPresent());
        assertTrue(repository().findByStep(PlanId.of("p1"), StepId.of("other")).isEmpty());
    }
}
