package io.github.zcd0831.jellyfish.application.planning.port;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import java.util.List;
import java.util.Optional;

/**
 * 不可变报告，只新建不覆盖。规则出处：模块-基础设施层 §3。
 */
public interface VerificationReportRepository {

    void save(VerificationReport report);

    Optional<VerificationReport> get(VerificationReportId id);

    List<VerificationReport> findByStep(PlanId planId, StepId stepId);
}
