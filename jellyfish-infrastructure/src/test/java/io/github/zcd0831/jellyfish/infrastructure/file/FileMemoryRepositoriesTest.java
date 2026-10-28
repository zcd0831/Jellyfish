package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 经验/反思/报告仓储的分支：命中与未命中、只新建、按步骤过滤。 */
class FileMemoryRepositoriesTest {

    @TempDir
    Path tmp;

    private final JsonCodec codec = new JsonCodec();
    private DataDirectory dir;
    private FileSessionRepository sessions;
    private Plan plan;

    @BeforeEach
    void setUp() {
        dir = DataDirectory.of(tmp.toString());
        sessions = new FileSessionRepository(dir, codec);
        sessions.save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
        plan = Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "做事", "g", "o", "c",
                        EnumSet.of(VerificationType.DETERMINISTIC))));
        new FilePlanRepository(dir, codec).save(plan);
    }

    @Test
    void 经验仓储支持按id取与淘汰候选排序() {
        FileExperienceRepository experiences = new FileExperienceRepository(dir, codec);
        experiences.save(Experience.create(ExperienceId.of("e1"), "场景1",
                new LessonLearned("教训1", "期望"), 0.5, 100L, null));
        experiences.save(Experience.create(ExperienceId.of("e2"), "场景2",
                new LessonLearned("教训2", "期望"), 0.5, 50L, null));

        assertTrue(experiences.find(ExperienceId.of("e1")).isPresent());
        assertFalse(experiences.find(ExperienceId.of("nope")).isPresent());
        assertEquals(2, experiences.count());
        assertEquals("e2", experiences.evictionCandidate().id().getValue(), "最早创建的先淘汰");

        experiences.archive(ExperienceId.of("e1"));
        assertFalse(experiences.find(ExperienceId.of("e1")).get().isActive());
        assertEquals(ExperienceStatus.ARCHIVED, experiences.find(ExperienceId.of("e1")).get().status());
    }

    @Test
    void 反思仓储只新建且可回读() {
        FileReflectionRepository reflections = new FileReflectionRepository(dir, codec);
        Reflection reflection = Reflection.record(plan.id(), StepId.of("step1"), 1, "先看日志", 100L);

        reflections.save(reflection);
        reflections.save(reflection);

        assertTrue(reflections.get(reflection.id()).isPresent());
        assertFalse(reflections.get(ReflectionId.of("nope")).isPresent());
    }

    @Test
    void 报告仓储按步骤过滤且重复保存幂等() {
        FileReportRepository reports = new FileReportRepository(dir, codec);
        VerificationReport passed = VerificationReport.decide(VerificationReportId.of("r1"), plan.id(),
                StepId.of("step1"), 1, VerificationOutcome.PASSED, null, null, 10L);
        VerificationReport failed = VerificationReport.decide(VerificationReportId.of("r2"), plan.id(),
                StepId.of("step2"), 1, VerificationOutcome.FAILED, VerificationType.LLM_JUDGE, "不行", 20L);

        reports.save(passed);
        reports.save(passed);
        reports.save(failed);

        assertTrue(reports.get(VerificationReportId.of("r1")).isPresent());
        assertEquals(1, reports.findByStep(plan.id(), StepId.of("step1")).size());
        assertEquals("不行", reports.findByStep(plan.id(), StepId.of("step2")).get(0).failureReason());
        assertTrue(reports.findByStep(PlanId.of("nope"), StepId.of("step1")).isEmpty());
    }

    @Test
    void 台账仓储缺失时返回空() {
        assertTrue(new FileBudgetRepository(dir, codec).load(SessionId.of("s1")) == null);

        FileBudgetRepository budgets = new FileBudgetRepository(dir, codec);
        budgets.save(Budget.openFor(BudgetId.of("b1"), SessionId.of("s1")));
        assertEquals(0L, budgets.load(SessionId.of("s1")).consumedTokens());
    }
}
