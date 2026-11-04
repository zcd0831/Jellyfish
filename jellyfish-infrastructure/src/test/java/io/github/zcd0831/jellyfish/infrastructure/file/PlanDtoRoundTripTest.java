package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plans/&lt;id&gt;.json 的往返：写进去的每个字段都必须读得回来。
 * 规则出处：09-infrastructure §9.5；06-planning §6.6（Attempt 记录用过哪些技能）。
 */
class PlanDtoRoundTripTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void 终止原因往返() {
        Plan plan = plan();
        plan.terminate("重划代次用尽");

        Plan reloaded = roundTrip(plan);

        assertEquals(PlanStatus.FAILED, reloaded.status());
        assertEquals("重划代次用尽", reloaded.terminationReason(),
                "计划为什么结束是唯一记录，读回不能变成 null");
    }

    @Test
    void 用过的技能往返() {
        Plan plan = plan();
        plan.beginCurrentStep().recordSkillUsage(SkillId.of("learned.a"));

        Plan reloaded = roundTrip(plan);

        assertEquals(Collections.singletonList(SkillId.of("learned.a")),
                reloaded.steps().get(0).attempts().get(0).usedSkillIds(),
                "用过哪条技能是判改进的唯一证据，重读后不能清空");
    }

    @Test
    void 老文件缺这两个字段也能读() {
        String legacy = "{\"id\":\"p1\",\"sessionId\":\"s1\",\"goal\":\"g\",\"status\":\"FAILED\","
                + "\"replanGeneration\":1,\"steps\":[{\"id\":\"step1\",\"sequence\":1,"
                + "\"description\":\"d\",\"goal\":\"g\",\"expectedOutcome\":\"e\",\"verificationCriteria\":\"c\","
                + "\"requiredVerifications\":[\"DETERMINISTIC\"],\"status\":\"RUNNING\","
                + "\"attempts\":[{\"attemptNo\":1}]}]}";

        Plan plan = codec.read(legacy, PlanDto.class).toDomain();

        assertNull(plan.terminationReason(), "老文件没有终止原因就是没记，不报错");
        assertTrue(plan.steps().get(0).attempts().get(0).usedSkillIds().isEmpty(), "老文件没有技能记录就是空");
    }

    private Plan roundTrip(Plan plan) {
        return codec.read(codec.write(PlanDto.from(plan)), PlanDto.class).toDomain();
    }

    private static Plan plan() {
        return Plan.rehydrate(PlanId.of("p1"), SessionId.of("s1"), "目标", PlanStatus.EXECUTING, 1,
                Collections.singletonList(PlanSteps.rehydrate(StepId.of("step1"), 1, "做事", "目标", "期望",
                        "标准", EnumSet.of(VerificationType.DETERMINISTIC), StepStatus.PENDING)));
    }
}
