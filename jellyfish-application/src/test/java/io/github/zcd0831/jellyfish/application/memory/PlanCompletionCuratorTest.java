package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.memory.skill.InstructionTemplate;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillStatus;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.RecordRange;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 编排者：一次调用、一个判定、互斥分派（07-memory §7.9.6）。 */
class PlanCompletionCuratorTest {

    private final RecordStore records = mock(RecordStore.class);
    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final ExperienceDistillationService experienceWriter = mock(ExperienceDistillationService.class);
    private final SkillDistillationService skillWriter = mock(SkillDistillationService.class);
    private final io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks =
            mock(io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner.class);
    private final io.github.zcd0831.jellyfish.application.shared.port.CommitScope commitScope =
            mock(io.github.zcd0831.jellyfish.application.shared.port.CommitScope.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private PlanCompletionCurator curator() {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        return new PlanCompletionCurator(records, model, catalog, policies, budget, experienceWriter, skillWriter,
                forks, commitScope);
    }

    private static AgentDefinition distillAgent() {
        return new AgentDefinition(AgentId.of("builtin.distill"), "归纳", AgentSource.BUILT_IN,
                new PromptTemplate("归纳提示词", Collections.<String>emptyList()), AgentStatus.ACTIVE,
                Collections.<io.github.zcd0831.jellyfish.domain.shared.ToolId>emptyList(),
                Collections.emptyList());
    }

    private static ModelInvocationResult reply(String text) {
        return new ModelInvocationResult(text, Collections.emptyList(), new ModelUsage(1, 1), ModelId.of("m1"));
    }

    private static DistillationInput input(StepTrace trace) {
        return new DistillationInput(PlanId.of("p1"), SessionId.of("s1"), "目标", PlanStatus.COMPLETED,
                Collections.singletonList(trace));
    }

    private static StepTrace traceWithoutSkill() {
        return new StepTrace(StepId.of("step1"), "做事", StepStatus.SUCCEEDED, null, null, null, null);
    }

    private static StepTrace traceWithSkill(String skillId) {
        return new StepTrace(StepId.of("step1"), "做事", StepStatus.SUCCEEDED, null,
                Collections.singletonList(SkillId.of(skillId)), null, null);
    }

    @Test
    void 缺归纳代理时一次也不调用模型() {
        when(catalog.agents()).thenReturn(Collections.<AgentDefinition>emptyList());

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        assertTrue(outcome.isEmpty());
        verify(model, never()).invoke(any(ModelInvocationRequest.class));
    }

    @Test
    void 未用过技能时判经验且只调用一次() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(forks.run(any(Session.class), any())).thenReturn((
                "{\"experience\":{\"applicableScenario\":\"改代码时\",\"instruction\":\"先读\"}}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        verify(forks, times(1)).run(any(Session.class), any());
        assertEquals("改代码时", outcome.experience().applicableScenario());
        verify(experienceWriter).apply(any(ExperienceDraft.class), any(SessionId.class), any());
        assertTrue(session.records().stream().anyMatch(r ->
                        r.role() == io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.SYSTEM
                                && r.content().startsWith("[沉淀告知 plan=")),
                "落盘成功后要写告知记录");
        verify(skillWriter, never()).apply(any(SkillDraft.class), any(SessionId.class), any());
    }

    @Test
    void 未用过技能时判技能且不碰经验() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(forks.run(any(Session.class), any())).thenReturn((
                "{\"skill\":{\"name\":\"依赖升级\",\"description\":\"升版本\",\"triggerCondition\":\"升级时\","
                        + "\"sopBody\":\"## 步骤\"}}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        assertEquals(1, outcome.skills().size());
        assertEquals(SkillDraft.Action.CREATE, outcome.skills().get(0).action());
        verify(skillWriter).apply(any(SkillDraft.class), any(SessionId.class), any());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
    }

    @Test
    void 经验与技能同时给出时本次不产出() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(forks.run(any(Session.class), any())).thenReturn((
                "{\"experience\":{\"applicableScenario\":\"场景\",\"instruction\":\"教训\"},"
                        + "\"skill\":{\"name\":\"n\",\"description\":\"d\",\"triggerCondition\":\"t\","
                        + "\"sopBody\":\"b\"}}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        assertTrue(outcome.isEmpty());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
        verify(skillWriter, never()).apply(any(SkillDraft.class), any(SessionId.class), any());
    }

    @Test
    void 用过技能时只接受对这些技能的改进() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.singletonList(skill("learned.a", true)));
        when(forks.run(any(Session.class), any())).thenReturn((
                "{\"revisions\":[{\"skillId\":\"learned.a\",\"sopBody\":\"新正文\"},"
                        + "{\"skillId\":\"learned.zzz\",\"sopBody\":\"幻觉\"}]}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithSkill("learned.a")));

        assertEquals(1, outcome.skills().size());
        assertEquals(SkillId.of("learned.a"), outcome.skills().get(0).targetSkillId());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
    }

    @Test
    void 模型不可用时只是这次没学到() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(forks.run(any(Session.class), any())).thenThrow(new IllegalStateException("挂了"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        assertTrue(outcome.isEmpty());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
    }

    private static SkillDefinition skill(String id, boolean mutable) {
        return new SkillDefinition(SkillId.of(id), "名", "述", "触发", new InstructionTemplate("正文"),
                SkillStatus.ACTIVE, mutable, null);
    }
}
