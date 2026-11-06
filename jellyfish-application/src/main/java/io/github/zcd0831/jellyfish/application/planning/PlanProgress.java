package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.memory.FeedbackInput;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;

/**
 * 计划进度的口径与去重键：都只读聚合与流水，不改任何状态，所以离循环单独放。
 * <p>两个「第 x/N 步」口径不同是刻意的（08-application §8.7）：进度事件排除被替换的
 * 旧尾部，播报里的「（N 步）」是剩余待做步数。
 */
final class PlanProgress {

    private PlanProgress() {
    }

    /** 播报里的「（N 步）」：剩余待做步数，不含已完成、不含被替换的旧尾部。 */
    static int pendingSteps(Plan plan) {
        int count = 0;
        for (PlanStep step : plan.steps()) {
            if (step.status() != StepStatus.SUPERSEDED && step.status() != StepStatus.SUCCEEDED) {
                count++;
            }
        }
        return count;
    }

    /**
     * 进度文案里的「第 x/N 步」：x 与 N 同一口径，N 只计未 SUPERSEDED 的总步数。
     * 不能用 step.sequence()/steps().size()：重划会保留被替换的旧尾部，两个数都会虚高。
     */
    static String position(Plan plan, PlanStep step) {
        int total = 0;
        int position = 0;
        for (PlanStep each : plan.steps()) {
            if (each.status() == StepStatus.SUPERSEDED) {
                continue;
            }
            total++;
            if (each.id().equals(step.id())) {
                position = total;
            }
        }
        return position + "/" + total;
    }

    static boolean needsVerification(PlanStep step) {
        return !step.requiredVerifications().isEmpty();
    }

    static int failedAttempts(PlanStep step) {
        int failed = 0;
        for (Attempt attempt : step.attempts()) {
            if (attempt.outcome() == AttemptOutcome.FAILED) {
                failed++;
            }
        }
        return failed;
    }

    /** 纠偏指令的标记：可推导状态，不落库（06-planning §6.8.1 规则 6）。 */
    static String correctionMarker(PlanStep step) {
        return "[重划纠偏 step=" + step.id().getValue() + "]";
    }

    /** 经验注入的标记：与纠偏标记同为"可推导状态"的去重键。 */
    static String experienceMarker(PlanStep step) {
        return "[经验注入 step=" + step.id().getValue() + "]";
    }

    /** 每个 step 最多一次纠偏：去重靠流水本身。 */
    static boolean alreadyCorrected(Session session, PlanStep step) {
        return hasSystemRecordStartingWith(session, correctionMarker(step));
    }

    /** 每一步只注入一次经验：同样靠流水去重。 */
    static boolean alreadyInjected(Session session, PlanStep step) {
        return hasSystemRecordStartingWith(session, experienceMarker(step));
    }

    /** 奖惩同源：本期"注入"与"建议"是同一批经验（07-memory §7.9.3）。 */
    static FeedbackInput feedbackOf(PlanStep step) {
        return new FeedbackInput(step.id(), step.assignedExperienceIds());
    }

    private static boolean hasSystemRecordStartingWith(Session session, String marker) {
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM && record.content() != null
                    && record.content().startsWith(marker)) {
                return true;
            }
        }
        return false;
    }
}
