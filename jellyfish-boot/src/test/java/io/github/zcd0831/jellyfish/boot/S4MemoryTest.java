package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.memory.DistillationInput;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.FeedbackInput;
import io.github.zcd0831.jellyfish.application.memory.RetrievalInput;
import io.github.zcd0831.jellyfish.application.memory.StepBrief;
import io.github.zcd0831.jellyfish.application.memory.StepTrace;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 记忆闭环：检索分配、奖惩不对称、沉淀（含每 Plan 上限）。
 * 规则出处：模块-记忆 §8.1、§8.3、§7.1 规则 ③⑦⑧。
 */
class S4MemoryTest {

    @TempDir
    Path tmp;

    private final JsonCodec codec = new JsonCodec();
    private final FakeModel model = new FakeModel();
    private DataDirectory dir;
    private ExperienceRepository experiences;
    private PolicyRepository policies;
    private Session session;
    private BudgetService budgetService;
    private ExperienceFeedbackService feedback;
    private ExperienceRetriever retriever;
    private ExperienceDistillationService distillation;

    @BeforeEach
    void setUp() {
        dir = DataDirectory.of(tmp.toString());
        policies = new FilePolicyRepository(dir, codec);
        FileSessionRepository sessions = new FileSessionRepository(dir, codec);
        experiences = new FileExperienceRepository(dir, codec);
        FileRecordStore records = new FileRecordStore(dir, codec);
        FileCatalogRepository catalog = new FileCatalogRepository(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        budgetService = new BudgetService(new FileBudgetRepository(dir, codec), sessions, policies,
                new FileCommitScope(dir, codec), publisher);
        feedback = new ExperienceFeedbackService(experiences, policies, new FileCommitScope(dir, codec),
                publisher);
        retriever = new ExperienceRetriever(experiences, model, catalog, policies, budgetService);
        distillation = new ExperienceDistillationService(experiences, records, model, catalog, policies,
                budgetService, new FileCommitScope(dir, codec), publisher, event -> { });

        session = Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"), ModelId.of("m1"),
                1L, null);
        sessions.save(session);
    }

    private Experience saved(String id, double confidence) {
        Experience experience = Experience.create(ExperienceId.of(id), "场景" + id,
                new LessonLearned("教训" + id, "期望"), confidence, 100L);
        experiences.save(experience);
        return experience;
    }

    @Test
    void 成功只奖励被建议的而失败惩罚注入的全部() {
        saved("e1", 0.5);
        saved("e2", 0.5);
        FeedbackInput input = new FeedbackInput(StepId.of("step1"),
                Arrays.asList(ExperienceId.of("e1"), ExperienceId.of("e2")),
                Collections.singletonList(ExperienceId.of("e1")));

        feedback.onStepSucceeded(input);
        assertEquals(0.6, confidence("e1"), 1e-9);
        assertEquals(0.5, confidence("e2"), 1e-9, "未被建议的经验不该被奖励");

        feedback.onStepFailed(input);
        assertEquals(0.5, confidence("e1"), 1e-9);
        assertEquals(0.4, confidence("e2"), 1e-9);
    }

    @Test
    void 检索把子代理的建议映射回步骤且不超过TopN() {
        saved("e1", 0.5);
        saved("e2", 0.5);
        model.reply = "{\"steps\":[{\"stepId\":\"step1\",\"experienceIds\":[\"e1\",\"e2\"]}]}";

        Map<StepId, List<ExperienceId>> assignment = retriever.retrieveAndAssign(session,
                new RetrievalInput(PlanId.of("p1"),
                        Collections.singletonList(new StepBrief(StepId.of("step1"), "读文件"))));

        assertEquals(1, assignment.size());
        assertTrue(assignment.get(StepId.of("step1")).size() <= runtimeTopN());
        assertEquals(2, assignment.get(StepId.of("step1")).size());
    }

    @Test
    void 沉淀按每Plan上限产出且场景与教训落库() {
        model.reply = "{\"experiences\":[{\"applicableScenario\":\"需要改代码时\","
                + "\"instruction\":\"先读再改\",\"expectedOutcome\":\"不破坏上下文\"},"
                + "{\"applicableScenario\":\"第二条\",\"instruction\":\"不该被写入\"}]}";
        StepTrace trace = new StepTrace(StepId.of("step1"), "改代码", StepStatus.FAILED, null, null,
                "没读就改", "应先读文件");

        distillation.distill(session, new DistillationInput(PlanId.of("p1"), session.id(), "改个 bug",
                PlanStatus.FAILED, Collections.singletonList(trace)));

        List<Experience> active = experiences.findActive();
        assertEquals(1, active.size(), "experiencePerPlan 默认 1");
        assertEquals("需要改代码时", active.get(0).applicableScenario());
    }

    @Test
    void 没有可用素材时不产出经验() {
        StepTrace trace = new StepTrace(StepId.of("step1"), "顺利的步骤", StepStatus.SUCCEEDED, null, null,
                null, null);

        distillation.distill(session, new DistillationInput(PlanId.of("p1"), session.id(), "目标",
                PlanStatus.COMPLETED, Collections.singletonList(trace)));

        assertTrue(experiences.findActive().isEmpty());
    }

    private double confidence(String id) {
        for (Experience experience : experiences.findActive()) {
            if (experience.id().getValue().equals(id)) {
                return experience.confidence().getValue();
            }
        }
        throw new IllegalStateException("经验不存在：" + id);
    }

    private int runtimeTopN() {
        return policies.load().experienceTopN();
    }

    private static final class FakeModel implements ModelInvocationPort {

        private String reply = "{}";

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            return new ModelInvocationResult(reply, Collections.<ToolCall>emptyList(),
                    new ModelUsage(3, 1), ModelId.of("m1"));
        }

        @Override
        public ModelInvocationResult invokeStreaming(ModelInvocationRequest request,
                                                     io.github.zcd0831.jellyfish.application.shared.ChunkSink sink) {
            return invoke(request);
        }

        @Override
        public ModelProfile describe(ModelId modelId) {
            return new ModelProfile(8000, 4096);
        }
    }
}
