package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 纯规则：按固定链序过滤声明层，短路执行与三态合并。
 * 规则出处：06-planning §6.5、§6.8.2 规则 2/3。
 */
public final class VerificationChain {

    /** 固定链序；枚举声明顺序即链序（§7.2 规则 2）。 */
    private static final List<VerificationType> ORDER = new ArrayList<VerificationType>(
            EnumSet.allOf(VerificationType.class));

    public List<VerificationType> orderedLayers(Set<VerificationType> declared) {
        List<VerificationType> layers = new ArrayList<VerificationType>();
        if (declared == null) {
            return layers;
        }
        for (VerificationType type : ORDER) {
            if (declared.contains(type)) {
                layers.add(type);
            }
        }
        return layers;
    }

    /** 全部通过 ⇒ PASSED。规则出处：§8.3 最后一步。 */
    public VerificationReport passed(VerificationReportId id, PlanId planId, StepId stepId, int attemptNo,
                                     long now) {
        return VerificationReport.decide(id, planId, stepId, attemptNo, VerificationOutcome.PASSED, null, null,
                now);
    }

    /** 某层不通过或无法判定 ⇒ 短路，只留失败层与原因。 */
    public VerificationReport rejected(VerificationReportId id, PlanId planId, StepId stepId, int attemptNo,
                                       VerificationOutcome outcome, VerificationType failedType,
                                       String reason, long now) {
        return VerificationReport.decide(id, planId, stepId, attemptNo, outcome, failedType, reason, now);
    }
}
