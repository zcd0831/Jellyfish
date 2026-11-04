package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** plans/<planId>.json 的形状。规则出处：09-infrastructure §9.5。 */
public class PlanDto {

    public String id;
    public String sessionId;
    public String goal;
    public String status;
    public int replanGeneration;
    public String terminationReason;
    public List<StepDto> steps = new ArrayList<StepDto>();

    public static PlanDto from(Plan plan) {
        PlanDto dto = new PlanDto();
        dto.id = plan.id().getValue();
        dto.sessionId = plan.sessionId().getValue();
        dto.goal = plan.goal();
        dto.status = plan.status().name();
        dto.replanGeneration = plan.replanGeneration();
        dto.terminationReason = plan.terminationReason();
        for (PlanStep step : plan.steps()) {
            dto.steps.add(StepDto.from(step));
        }
        return dto;
    }

    public Plan toDomain() {
        List<PlanStep> planSteps = new ArrayList<PlanStep>();
        for (StepDto step : steps) {
            PlanStep restored = step.toDomain();
            planSteps.add(restored);
        }
        Plan plan = Plan.rehydrate(PlanId.of(id), SessionId.of(sessionId), goal, PlanStatus.valueOf(status),
                replanGeneration, planSteps);
        // 字段可选：老文件没有这一项就是"当时没记"，与 null 同义
        plan.restoreTerminationReason(terminationReason);
        return plan;
    }

    public static class StepDto {

        public String id;
        public int sequence;
        public String description;
        public String goal;
        public String expectedOutcome;
        public String verificationCriteria;
        public List<String> requiredVerifications = new ArrayList<String>();
        public String status;
        public String finalOutput;
        public String finalVerificationReportId;
        public List<String> assignedExperienceIds = new ArrayList<String>();
        public List<AttemptDto> attempts = new ArrayList<AttemptDto>();

        static StepDto from(PlanStep step) {
            StepDto dto = new StepDto();
            dto.id = step.id().getValue();
            dto.sequence = step.sequence();
            dto.description = step.description();
            dto.goal = step.goal();
            dto.expectedOutcome = step.expectedOutcome();
            dto.verificationCriteria = step.verificationCriteria();
            for (VerificationType type : step.requiredVerifications()) {
                dto.requiredVerifications.add(type.name());
            }
            dto.status = step.status().name();
            dto.finalOutput = step.finalOutput();
            dto.finalVerificationReportId = step.finalVerificationReportId() == null
                    ? null : step.finalVerificationReportId().getValue();
            for (ExperienceId experienceId : step.assignedExperienceIds()) {
                dto.assignedExperienceIds.add(experienceId.getValue());
            }
            for (Attempt attempt : step.attempts()) {
                dto.attempts.add(AttemptDto.from(attempt));
            }
            return dto;
        }

        PlanStep toDomain() {
            Set<VerificationType> types = EnumSet.noneOf(VerificationType.class);
            for (String type : requiredVerifications) {
                types.add(VerificationType.valueOf(type));
            }
            PlanStep step = PlanSteps.rehydrate(StepId.of(id), sequence, description, goal, expectedOutcome,
                    verificationCriteria, types, StepStatus.valueOf(status));
            for (AttemptDto attempt : attempts) {
                step.restoreAttempt(attempt.toDomain());
            }
            for (String experienceId : assignedExperienceIds) {
                step.restoreExperience(ExperienceId.of(experienceId));
            }
            step.restoreOutcome(finalOutput, finalVerificationReportId == null
                    ? null : VerificationReportId.of(finalVerificationReportId));
            return step;
        }
    }

    public static class AttemptDto {

        public int attemptNo;
        public String verificationReportId;
        public String reflectionId;
        public String outcome;
        /** 本次尝试用过的技能：改进判定的唯一证据（06-planning §6.6、§6.9.1）。 */
        public List<String> usedSkillIds = new ArrayList<String>();

        static AttemptDto from(Attempt attempt) {
            AttemptDto dto = new AttemptDto();
            dto.attemptNo = attempt.attemptNo();
            dto.verificationReportId = attempt.verificationReportId() == null
                    ? null : attempt.verificationReportId().getValue();
            dto.reflectionId = attempt.reflectionId() == null ? null : attempt.reflectionId().getValue();
            dto.outcome = attempt.outcome() == null ? null : attempt.outcome().name();
            for (io.github.zcd0831.jellyfish.domain.shared.SkillId skillId : attempt.usedSkillIds()) {
                dto.usedSkillIds.add(skillId.getValue());
            }
            return dto;
        }

        Attempt toDomain() {
            Attempt attempt = new Attempt(attemptNo);
            if (verificationReportId != null) {
                attempt.attachReport(VerificationReportId.of(verificationReportId));
            }
            if (reflectionId != null) {
                attempt.attachReflection(ReflectionId.of(reflectionId));
            }
            if (outcome != null) {
                attempt.finish(AttemptOutcome.valueOf(outcome));
            }
            if (usedSkillIds != null) {
                for (String skillId : usedSkillIds) {
                    attempt.recordSkillUsage(io.github.zcd0831.jellyfish.domain.shared.SkillId.of(skillId));
                }
            }
            return attempt;
        }
    }
}
