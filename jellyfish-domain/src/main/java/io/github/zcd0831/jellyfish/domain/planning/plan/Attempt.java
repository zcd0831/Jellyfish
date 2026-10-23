package io.github.zcd0831.jellyfish.domain.planning.plan;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

/** 一个 step 的一次完整执行周期。规则出处：模块-规划 §6.6。 */
public class Attempt {

    private int attemptNo;
    private RecordRange recordRange;
    private List<SessionId> childSessionIds;
    private VerificationReportId verificationReportId;
    private ReflectionId reflectionId;
    private AttemptOutcome outcome;
}
