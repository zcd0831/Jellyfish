package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.memory.experience.ConfidenceScore;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 检索：素材口径、超限裁剪、放弃、解析过滤（07-memory §7.9.1）。 */
class ExperienceRetrieverTest {

    private final ExperienceRepository experiences = mock(ExperienceRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final List<ModelInvocationRequest> requests = new ArrayList<ModelInvocationRequest>();
    private String answer = "{}";
    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private final ModelInvocationPort model = new ModelInvocationPort() {
        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            requests.add(request);
            return new ModelInvocationResult(answer, Collections.emptyList(), null, ModelId.of("m1"));
        }

        @Override
        public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
            return invoke(request);
        }

        @Override
        public ModelProfile describe(ModelId modelId) {
            return new ModelProfile(100000, 4096);
        }
    };

    private ExperienceRetriever retriever() {
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("提示词")));
        return new ExperienceRetriever(experiences, model, catalog, policies, budget,
                text -> text == null ? 0 : text.length());
    }

    private static AgentDefinition agent(String prompt) {
        return new AgentDefinition(AgentId.of("builtin.retrieve"), "检索", AgentSource.BUILT_IN,
                new PromptTemplate(prompt, Collections.<String>emptyList()), AgentStatus.ACTIVE,
                Collections.<io.github.zcd0831.jellyfish.domain.shared.ToolId>emptyList(),
                Collections.emptyList());
    }

    private static Experience experience(String id, String scenario, double confidence) {
        return new Experience(ExperienceId.of(id), scenario, new LessonLearned("教训" + id, "期望"),
                new ConfidenceScore(confidence), ExperienceStatus.ACTIVE, 0, 1L, null);
    }

    private static RetrievalInput input() {
        return new RetrievalInput(io.github.zcd0831.jellyfish.domain.shared.PlanId.of("p1"),
                Collections.singletonList(new StepBrief(StepId.of("step1"), "改代码")));
    }

    @Test
    void 素材只有经验与步骤描述且不用对话轨迹() {
        when(experiences.findActive()).thenReturn(Collections.singletonList(experience("e1", "改代码时", 0.5)));

        retriever().retrieveAndAssign(session, input());

        String material = requests.get(0).messages().get(0).content();
        assertTrue(material.contains("e1"), material);
        assertTrue(material.contains("改代码"), material);
        assertEquals(RecordRoleOf.USER, requests.get(0).messages().get(0).role());
    }

    @Test
    void 超限时按置信度升序丢经验后再调用() {
        StringBuilder longScenario = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longScenario.append('长');
        }
        List<Experience> all = Arrays.asList(experience("low", longScenario.toString(), 0.1),
                experience("high", "高分", 0.9));
        when(experiences.findActive()).thenReturn(all);
        when(policies.load()).thenReturn(RuntimePolicy.defaults().adjust(
                Collections.<String, Object>singletonMap("summarizationTokenThreshold", 100)));
        when(catalog.agents()).thenReturn(Collections.singletonList(agent("提示词")));
        ExperienceRetriever subject = new ExperienceRetriever(experiences, model, catalog, policies, budget,
                text -> text == null ? 0 : text.length());

        subject.retrieveAndAssign(session, input());

        String material = requests.get(0).messages().get(0).content();
        assertTrue(!material.contains("| 长"), "最低分先被丢掉：" + material);
        assertTrue(material.contains("| 高分"), material);
    }

    @Test
    void 丢完仍超限就放弃本次检索() {
        when(experiences.findActive()).thenReturn(Collections.singletonList(experience("e1", "很长的场景", 0.5)));
        ExperienceRetriever subject = retriever();
        when(policies.load()).thenReturn(RuntimePolicy.defaults().adjust(
                Collections.<String, Object>singletonMap("summarizationTokenThreshold", 5)));

        Map<StepId, List<ExperienceId>> assignment = subject.retrieveAndAssign(session, input());

        assertTrue(assignment.isEmpty());
        assertTrue(requests.isEmpty(), "放弃检索就不该再调模型");
    }

    @Test
    void 解析时丢掉库里不存在的经验id() {
        when(experiences.findActive()).thenReturn(Collections.singletonList(experience("e1", "改代码时", 0.5)));
        answer = "{\"steps\":[{\"stepId\":\"step1\",\"experienceIds\":[\"e1\",\"ghost\"]}]}";

        Map<StepId, List<ExperienceId>> assignment = retriever().retrieveAndAssign(session, input());

        assertEquals(1, assignment.get(StepId.of("step1")).size());
        assertEquals("e1", assignment.get(StepId.of("step1")).get(0).getValue());
    }

    /** 消息角色断言用的常量，避免额外 import。 */
    private static final class RecordRoleOf {
        static final io.github.zcd0831.jellyfish.domain.perception.session.RecordRole USER =
                io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.USER;
    }
}
