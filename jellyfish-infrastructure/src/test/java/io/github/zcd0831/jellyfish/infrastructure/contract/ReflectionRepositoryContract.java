package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `ReflectionRepository` 的契约：只新建、归属唯一、可按 id 读回。 */
public abstract class ReflectionRepositoryContract {

    protected abstract ReflectionRepository repository();

    protected abstract SessionRepository sessions();

    protected abstract PlanRepository plans();

    @BeforeEach
    void givenPlan() {
        sessions().save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
        plans().save(PlanRepositoryContract.plan("p1", PlanStatus.EXECUTING));
    }

    @Test
    void 保存后可按id读回() {
        Reflection reflection = Reflection.record(PlanId.of("p1"), StepId.of("step-p1"), 1, "先看日志", 10L);

        repository().save(reflection);

        assertTrue(repository().get(reflection.id()).isPresent());
        assertEquals("先看日志", repository().get(reflection.id()).get().conclusion());
    }

    @Test
    void 同一归属重复保存不产生第二份() {
        Reflection first = Reflection.record(PlanId.of("p1"), StepId.of("step-p1"), 1, "第一次", 10L);
        Reflection second = Reflection.record(PlanId.of("p1"), StepId.of("step-p1"), 1, "第二次", 20L);

        repository().save(first);
        repository().save(second);

        assertEquals("第一次", repository().get(first.id()).get().conclusion(), "归属唯一：不覆盖");
    }

    @Test
    void 不存在时返回空() {
        assertFalse(repository().get(ReflectionId.of("nope")).isPresent());
    }
}
