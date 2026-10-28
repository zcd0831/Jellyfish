package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 step 的一次完整执行周期：可跨多段 ReAct、被多次用户输入切分，但不切分 attempt。
 * 规则出处：06-planning §6.6、§5.2。
 */
public class Attempt {

    private final int attemptNo;
    private final List<SkillId> usedSkillIds = new ArrayList<SkillId>();

    private RecordRange recordRange;
    private VerificationReportId verificationReportId;
    private ReflectionId reflectionId;
    private AttemptOutcome outcome;

    public Attempt(int attemptNo, RecordRange recordRange) {
        this.attemptNo = attemptNo;
        this.recordRange = recordRange;
    }

    /** 规则出处：§5.2（收敛但未提交不结束，记录区间继续延伸）。 */
    public void extendRange(RecordRange range) {
        if (recordRange == null) {
            this.recordRange = range;
        } else {
            this.recordRange = new RecordRange(recordRange.getStartRecordId(), range.getEndRecordId());
        }
    }

    /** 技能使用的唯一来源：改进判定的证据（06-planning §6.6、§6.9.1）。 */
    public void recordSkillUsage(SkillId skillId) {
        if (!usedSkillIds.contains(skillId)) {
            usedSkillIds.add(skillId);
        }
    }

    public void attachReport(VerificationReportId reportId) {
        this.verificationReportId = reportId;
    }

    public void attachReflection(ReflectionId reflectionId) {
        this.reflectionId = reflectionId;
    }

    public void finish(AttemptOutcome outcome) {
        this.outcome = outcome;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public RecordRange recordRange() {
        return recordRange;
    }

    public List<SkillId> usedSkillIds() {
        return Collections.unmodifiableList(usedSkillIds);
    }

    public VerificationReportId verificationReportId() {
        return verificationReportId;
    }

    public ReflectionId reflectionId() {
        return reflectionId;
    }

    public AttemptOutcome outcome() {
        return outcome;
    }
}
