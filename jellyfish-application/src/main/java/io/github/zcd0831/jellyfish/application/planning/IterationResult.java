package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内循环一次迭代的输出。规则出处：模块-规划 §5（IterationResult）。
 *
 * <p>`usedSkillIds` 是"本次真的 load 了哪些技能"的唯一出口：内循环拿不到 Plan，
 * 由外循环把它写进 attempt（模块-记忆 §8.6 的改进判定靠它）。
 */
public final class IterationResult {

    private final boolean isConverged;
    private final boolean submitted;
    private final String submittedSummary;
    private final int round;
    private final List<SkillId> usedSkillIds;

    private IterationResult(boolean isConverged, boolean submitted, String submittedSummary, int round,
                            List<SkillId> usedSkillIds) {
        this.isConverged = isConverged;
        this.submitted = submitted;
        this.submittedSummary = submittedSummary;
        this.round = round;
        this.usedSkillIds = Collections.unmodifiableList(new ArrayList<SkillId>(usedSkillIds));
    }

    public static IterationResult converged(int round, List<SkillId> usedSkillIds) {
        return new IterationResult(true, false, null, round, usedSkillIds);
    }

    public static IterationResult submitted(String summary, int round, List<SkillId> usedSkillIds) {
        return new IterationResult(true, true, summary, round, usedSkillIds);
    }

    public static IterationResult notConverged(int round, List<SkillId> usedSkillIds) {
        return new IterationResult(false, false, null, round, usedSkillIds);
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

    public int round() {
        return round;
    }

    public List<SkillId> usedSkillIds() {
        return usedSkillIds;
    }
}
