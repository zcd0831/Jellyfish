package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 固定链序 + 过滤 + 三态。规则出处：模块-规划 §7.2。 */
class VerificationChainTest {

    private final VerificationChain chain = new VerificationChain();

    @Test
    void 链序固定且只保留声明的层() {
        List<VerificationType> layers = chain.orderedLayers(
                EnumSet.of(VerificationType.HUMAN_APPROVAL, VerificationType.DETERMINISTIC));

        assertEquals(Arrays.asList(VerificationType.DETERMINISTIC, VerificationType.HUMAN_APPROVAL), layers);
    }

    @Test
    void 未声明任何层时为空链() {
        assertEquals(0, chain.orderedLayers(EnumSet.noneOf(VerificationType.class)).size());
    }

    @Test
    void 通过的报告不带失败层() {
        VerificationReport report = chain.passed(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("s1"), 1, 100L);

        assertEquals(VerificationOutcome.PASSED, report.outcome());
        assertNull(report.failedType());
        assertEquals(0, report.pullEvents().size());
    }

    @Test
    void 失败与无法判定都raise拒绝事件() {
        VerificationReport failed = chain.rejected(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("s1"), 1, VerificationOutcome.FAILED, VerificationType.LLM_JUDGE, "理由", 100L);
        VerificationReport undetermined = chain.rejected(VerificationReportId.of("r2"), PlanId.of("p1"),
                StepId.of("s1"), 1, VerificationOutcome.UNDETERMINED, VerificationType.ENV_FEEDBACK, null, 100L);

        assertEquals(VerificationType.LLM_JUDGE, failed.failedType());
        assertEquals("理由", failed.failureReason());
        assertEquals(1, failed.pullEvents().size());
        assertEquals(1, undetermined.pullEvents().size());
    }
}
