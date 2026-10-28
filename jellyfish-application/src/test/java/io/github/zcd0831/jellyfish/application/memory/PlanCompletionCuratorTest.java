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

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private PlanCompletionCurator curator() {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        return new PlanCompletionCurator(records, model, catalog, policies, budget, experienceWriter, skillWriter);
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
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
                "{\"experience\":{\"applicableScenario\":\"改代码时\",\"instruction\":\"先读\"}}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        verify(model, times(1)).invoke(any(ModelInvocationRequest.class));
        assertEquals("改代码时", outcome.experience().applicableScenario());
        verify(experienceWriter).apply(any(ExperienceDraft.class), any(SessionId.class), any());
        verify(skillWriter, never()).apply(any(SkillDraft.class), any(SessionId.class), any());
    }

    @Test
    void 未用过技能时判技能且不碰经验() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
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
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
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
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
                "{\"revisions\":[{\"skillId\":\"learned.a\",\"sopBody\":\"新正文\"},"
                        + "{\"skillId\":\"learned.zzz\",\"sopBody\":\"幻觉\"}]}"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithSkill("learned.a")));

        assertEquals(1, outcome.skills().size());
        assertEquals(SkillId.of("learned.a"), outcome.skills().get(0).targetSkillId());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
    }

    @Test
    void 轨迹区间可读时进入素材() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{}"));
        List<Record> range = new ArrayList<Record>();
        range.add(new Record(RecordId.of("r1"), RecordRole.SYSTEM, "系统", null, 1, 1L));
        range.add(new Record(RecordId.of("r2"), RecordRole.USER, "用户原话", null, 2, 2L));
        when(records.range(any(SessionId.class), any(RecordId.class), any(RecordId.class))).thenReturn(range);

        StepTrace trace = new StepTrace(StepId.of("step1"), "做事", StepStatus.FAILED,
                new RecordRange(RecordId.of("r1"), RecordId.of("r2")), null, "失败", "反思");
        curator().curate(session, input(trace));

        org.mockito.ArgumentCaptor<ModelInvocationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ModelInvocationRequest.class);
        verify(model).invoke(captor.capture());
        String material = captor.getValue().messages().get(0).content();
        assertTrue(material.contains("用户原话"), "轨迹正文应进入素材");
        assertTrue(!material.contains("系统"), "SYSTEM 记录不是轨迹");
    }

    @Test
    void 轨迹结构化且不含reasoning() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{}"));
        List<Record> range = new ArrayList<Record>();
        range.add(new Record(RecordId.of("r1"), RecordRole.ASSISTANT, "我先看看", null, 1, 1L));
        range.add(new Record(RecordId.of("r2"), RecordRole.TOOL_REQUEST,
                "{\"callId\":\"c1\",\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}",
                "这里是不能进沉淀的内部推理", null, 0, 2, 2L));
        range.add(new Record(RecordId.of("r3"), RecordRole.TOOL_RESULT, "目录里有 pom.xml", null, 3, 3L));
        when(records.range(any(SessionId.class), any(RecordId.class), any(RecordId.class))).thenReturn(range);

        StepTrace trace = new StepTrace(StepId.of("step1"), "做事", StepStatus.SUCCEEDED,
                new RecordRange(RecordId.of("r1"), RecordId.of("r3")), null, null, null);
        curator().curate(session, input(trace));

        org.mockito.ArgumentCaptor<ModelInvocationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ModelInvocationRequest.class);
        verify(model).invoke(captor.capture());
        String material = captor.getValue().messages().get(0).content();
        assertTrue(material.contains("list_dir"), material);
        assertTrue(material.contains("目录里有 pom.xml"), material);
        assertTrue(!material.contains("这里是不能进沉淀的内部推理"), "reasoning 不进沉淀");
    }

    @Test
    void 轨迹不可读时仍完成判定() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{}"));
        when(records.range(any(SessionId.class), any(RecordId.class), any(RecordId.class)))
                .thenThrow(new IllegalArgumentException("区间不存在"));

        StepTrace trace = new StepTrace(StepId.of("step1"), "做事", StepStatus.FAILED,
                new RecordRange(RecordId.of("r1"), RecordId.of("r2")), null, "失败", null);
        DistillationOutcome outcome = curator().curate(session, input(trace));

        assertTrue(outcome.isEmpty());
        verify(model).invoke(any(ModelInvocationRequest.class));
    }

    @Test
    void 模型不可用时只是这次没学到() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(catalog.skills()).thenReturn(Collections.<SkillDefinition>emptyList());
        when(model.invoke(any(ModelInvocationRequest.class))).thenThrow(new IllegalStateException("挂了"));

        DistillationOutcome outcome = curator().curate(session, input(traceWithoutSkill()));

        assertTrue(outcome.isEmpty());
        verify(experienceWriter, never()).apply(any(ExperienceDraft.class), any(SessionId.class), any());
    }

    private static SkillDefinition skill(String id, boolean mutable) {
        return new SkillDefinition(SkillId.of(id), "名", "述", "触发", new InstructionTemplate("正文"),
                SkillStatus.ACTIVE, mutable, null);
    }
}
