package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `PlanRepository` 的契约：按 id 读取、按会话找执行中的计划。 */
public abstract class PlanRepositoryContract {

    protected abstract PlanRepository repository();

    protected abstract SessionRepository sessions();

    @BeforeEach
    void givenSession() {
        sessions().save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
    }

    protected static Plan plan(String id, PlanStatus status) {
        Plan plan = Plan.create(PlanId.of(id), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step-" + id), 1, "做事", "g", "o", "c",
                        EnumSet.of(VerificationType.DETERMINISTIC))));
        if (status == PlanStatus.FAILED) {
            plan.terminate("测试终结");
        }
        return plan;
    }

    @Test
    void 保存后能读回步骤与状态() {
        repository().save(plan("p1", PlanStatus.EXECUTING));

        Plan loaded = repository().load(PlanId.of("p1"));

        assertEquals(PlanStatus.EXECUTING, loaded.status());
        assertEquals(1, loaded.steps().size());
        assertEquals("g", loaded.steps().get(0).goal());
    }

    @Test
    void 不存在的计划返回null() {
        assertNull(repository().load(PlanId.of("nope")));
    }

    @Test
    void findRunning只返回执行中的计划() {
        repository().save(plan("running", PlanStatus.EXECUTING));
        repository().save(plan("failed", PlanStatus.FAILED));

        assertEquals(1, repository().findRunning(SessionId.of("s1")).size());
        assertEquals("running", repository().findRunning(SessionId.of("s1")).get(0).id().getValue());
    }

    @Test
    void 覆盖保存保留最新状态() {
        Plan plan = plan("p1", PlanStatus.EXECUTING);
        repository().save(plan);
        plan.terminate("手工终止");
        repository().save(plan);

        assertEquals(PlanStatus.FAILED, repository().load(PlanId.of("p1")).status());
        assertTrue(repository().findRunning(SessionId.of("s1")).isEmpty());
    }
}
