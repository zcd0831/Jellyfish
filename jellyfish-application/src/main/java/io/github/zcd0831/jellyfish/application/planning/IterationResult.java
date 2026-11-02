package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内循环一次迭代的输出。规则出处：06-planning §6.6（IterationResult）。
 * <p>`usedSkillIds` 是"本次真的 load 了哪些技能"的唯一出口：内循环拿不到 Plan，
 * 由外循环把它写进 attempt（07-memory §7.9.6 的改进判定靠它）。
 */
public final class IterationResult {

    private final boolean isConverged;
    private final boolean submitted;
    private final String submittedSummary;
    private final List<String> submittedArtifacts;
    private final int round;
    private final List<SkillId> usedSkillIds;

    private IterationResult(boolean isConverged, boolean submitted, String submittedSummary,
                            List<String> submittedArtifacts, int round, List<SkillId> usedSkillIds) {
        this.isConverged = isConverged;
        this.submitted = submitted;
        this.submittedSummary = submittedSummary;
        this.submittedArtifacts = Collections.unmodifiableList(new ArrayList<String>(
                submittedArtifacts == null ? Collections.<String>emptyList() : submittedArtifacts));
        this.round = round;
        this.usedSkillIds = Collections.unmodifiableList(new ArrayList<SkillId>(usedSkillIds));
    }

    public static IterationResult converged(int round, List<SkillId> usedSkillIds) {
        return new IterationResult(true, false, null, null, round, usedSkillIds);
    }

    public static IterationResult submitted(String summary, int round, List<SkillId> usedSkillIds) {
        return submitted(summary, null, round, usedSkillIds);
    }

    /** `artifacts` 是 submit_step 声明的产物清单：验证裁判唯一可读的范围（06-planning §6.8.2）。 */
    public static IterationResult submitted(String summary, List<String> artifacts, int round,
                                            List<SkillId> usedSkillIds) {
        return new IterationResult(true, true, summary, artifacts, round, usedSkillIds);
    }

    public static IterationResult notConverged(int round, List<SkillId> usedSkillIds) {
        return new IterationResult(false, false, null, null, round, usedSkillIds);
    }

    public boolean isConverged() {
        return isConverged;
    }

    public boolean submitted() {
        return submitted;
    }

    public String submittedSummary() {
        return submittedSummary;
    }

    public List<String> submittedArtifacts() {
        return submittedArtifacts;
    }

    public int round() {
        return round;
    }

    public List<SkillId> usedSkillIds() {
        return usedSkillIds;
    }
}
