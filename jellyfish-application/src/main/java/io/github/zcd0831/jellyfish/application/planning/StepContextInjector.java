package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.util.List;

/**
 * 上下文注入：把"分配到的经验"和"重划纠偏指令"写成 SYSTEM 记录塞进流水。
 * <p>两条注入的形态一致（首行带 step 标记做去重、标记不落库），差别只在提交时机：
 * 经验注入自己提交，纠偏指令跟着计划那一次提交走。
 * 规则出处：04-perception §4.8.1 规则 9 ①/规则 10、06-planning §6.8.1 规则 6、§7.3 规则 3。
 */
final class StepContextInjector {

    private static final String REPLAN_TOOL = "replan_plan";

    private final ExperienceRepository experiences;
    private final CommitScope commitScope;

    StepContextInjector(ExperienceRepository experiences, CommitScope commitScope) {
        this.experiences = experiences;
        this.commitScope = commitScope;
    }

    /** 步骤启动前注入该步分配到的经验；每一步只注入一次。 */
    void injectExperiences(Session session, PlanStep step) {
        List<ExperienceId> ids = step.assignedExperienceIds();
        if (ids.isEmpty() || PlanProgress.alreadyInjected(session, step)) {
            return;
        }
        StringBuilder text = new StringBuilder(PlanProgress.experienceMarker(step));
        // 条数由 experienceTopN 天然有界，不再二次截断：以前借 toolResultTruncateLength
        // （工具结果长度）当注入上限，调工具阈值会顺带改掉经验注入的内容（07-memory §7.9.1）
        for (ExperienceId id : ids) {
            java.util.Optional<io.github.zcd0831.jellyfish.domain.memory.experience.Experience> found =
                    experiences.find(id);
            if (!found.isPresent() || !found.get().isActive()) {
                continue;
            }
            io.github.zcd0831.jellyfish.domain.memory.experience.Experience experience = found.get();
            text.append("\n- 适用场景：").append(experience.applicableScenario())
                    .append("｜教训：").append(experience.lesson().getInstruction())
                    .append("｜期望：").append(experience.lesson().getExpectedOutcome());
        }
        if (text.indexOf("\n") < 0) {
            return;   // 一条可用经验都没有，不写空记录
        }
        Record injected = session.appendRecord(RecordRole.SYSTEM, text.toString(), null,
                System.currentTimeMillis());
        commitScope.stageAppend(session.id(), injected);
        commitScope.commit();
    }

    /**
     * 纠偏指令：告诉模型这一步连续失败了几次，可以调 `replan_plan` 修正计划，或换一种做法重做。
     * **只 stage**：跟计划变更一起提交，否则会重开"判败但还没推进"的非法窗口。
     */
    void injectCorrection(Session session, PlanStep step, String reason, int failedAttempts) {
        String text = PlanProgress.correctionMarker(step)
                + "\n该步已连续失败 " + failedAttempts + " 次：" + reason
                + "\n请调用 " + REPLAN_TOOL + " 修正剩余步骤；或换一种做法重做这一步。";
        Record injected = session.appendRecord(RecordRole.SYSTEM, text, null, System.currentTimeMillis());
        commitScope.stageAppend(session.id(), injected);
    }
}
