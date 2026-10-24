package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.memory.experience.ConfidenceScore;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.RecordRange;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 沉淀的分支：无素材 / 缺代理 / 解析失败 / 合并 / 容量淘汰 / 轨迹不可读。 */
class ExperienceDistillationBranchesTest {

    private final ExperienceRepository experiences = mock(ExperienceRepository.class);
    private final RecordStore records = mock(RecordStore.class);
    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final CommitScope commitScope = mock(CommitScope.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private ExperienceDistillationService service(RuntimePolicy policy) {
        when(policies.load()).thenReturn(policy);
        return new ExperienceDistillationService(experiences, records, model, catalog, policies, budget,
                commitScope, events -> { }, event -> { });
    }

    private static AgentDefinition distillAgent() {
        return new AgentDefinition(AgentId.of("builtin.distill"), "归纳", AgentSource.BUILT_IN,
                new PromptTemplate("归纳提示词", Collections.<String>emptyList()), AgentStatus.ACTIVE,
                Collections.<io.github.zcd0831.jellyfish.domain.shared.ToolId>emptyList(),
                Collections.emptyList());
    }

    private static DistillationInput input(StepTrace trace) {
        return new DistillationInput(PlanId.of("p1"), SessionId.of("s1"), "目标", PlanStatus.FAILED,
                Collections.singletonList(trace));
    }

    private static StepTrace failingTrace() {
        return new StepTrace(StepId.of("step1"), "改代码", StepStatus.FAILED, null, null, "没读就改", null);
    }

    private static ModelInvocationResult reply(String text) {
        return new ModelInvocationResult(text, Collections.emptyList(), new ModelUsage(1, 1), ModelId.of("m1"));
    }

    @Test
    void 无素材时不调用模型() {
        service(RuntimePolicy.defaults()).distill(session,
                input(new StepTrace(StepId.of("s"), "顺利", StepStatus.SUCCEEDED, null, null, null, null)));

        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void 缺归纳代理时不产出() {
        when(catalog.agents()).thenReturn(Collections.<AgentDefinition>emptyList());
        service(RuntimePolicy.defaults()).distill(session, input(failingTrace()));

        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void 候选解析失败时不产出() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("不是JSON"));
        service(RuntimePolicy.defaults()).distill(session, input(failingTrace()));

        assertEquals(0, org.mockito.Mockito.mockingDetails(experiences).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save")).count());
    }

    @Test
    void 语义重复时合并而不是新建() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
                "{\"experiences\":[{\"applicableScenario\":\"改代码时\",\"instruction\":\"先读\"}]}"));
        Experience existing = new Experience(ExperienceId.of("e1"), "改代码时",
                new LessonLearned("旧教训", "旧期望"), new ConfidenceScore(0.5), ExperienceStatus.ACTIVE, 0, 10L);
        when(experiences.findActive()).thenReturn(Collections.singletonList(existing));

        service(RuntimePolicy.defaults()).distill(session, input(failingTrace()));

        assertTrue(experiences.findActive().get(0).confidence().getValue() > 0.5, "合并应提升置信度");
    }

    @Test
    void 容量满时先淘汰再新建() {
        RuntimePolicy tiny = RuntimePolicy.defaults().adjust(java.util.Collections.<String, Object>singletonMap(
                "experienceCapacity", 1));
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(
                "{\"experiences\":[{\"applicableScenario\":\"新场景\",\"instruction\":\"新教训\"}]}"));
        Experience old = new Experience(ExperienceId.of("e1"), "旧场景",
                new LessonLearned("旧", "旧"), new ConfidenceScore(0.5), ExperienceStatus.ACTIVE, 0, 1L);
        when(experiences.findActive()).thenReturn(Collections.singletonList(old));
        when(experiences.count()).thenReturn(1);

        service(tiny).distill(session, input(failingTrace()));

        assertEquals(ExperienceStatus.ARCHIVED, old.status(), "容量满时淘汰最早的经验");
    }

    @Test
    void 轨迹区间可读时进入素材() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{}"));
        List<Record> range = new ArrayList<Record>();
        range.add(new Record(RecordId.of("r1"), RecordRole.SYSTEM, "系统", null, 1, 1L));
        range.add(new Record(RecordId.of("r2"), RecordRole.USER, "用户", null, 2, 2L));
        when(records.range(any(SessionId.class), any(RecordId.class), any(RecordId.class))).thenReturn(range);

        StepTrace trace = new StepTrace(StepId.of("step1"), "做事", StepStatus.FAILED,
                new RecordRange(RecordId.of("r1"), RecordId.of("r2")), null, "失败", "反思");
        service(RuntimePolicy.defaults()).distill(session, input(trace));

        org.mockito.Mockito.verify(model).invoke(any(ModelInvocationRequest.class));
    }

    @Test
    void 轨迹不可读时仍可继续() {
        when(catalog.agents()).thenReturn(Collections.singletonList(distillAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{}"));
        when(records.range(any(SessionId.class), any(RecordId.class), any(RecordId.class)))
                .thenThrow(new IllegalArgumentException("区间不存在"));

        StepTrace trace = new StepTrace(StepId.of("step1"), "做事", StepStatus.FAILED,
                new RecordRange(RecordId.of("r1"), RecordId.of("r2")), null, "失败", null);
        service(RuntimePolicy.defaults()).distill(session, input(trace));

        org.mockito.Mockito.verify(model).invoke(any(ModelInvocationRequest.class));
    }
}
