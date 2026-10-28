package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 四层判定与短路。规则出处：06-planning §6.8.2。 */
class VerificationRunnerTest {

    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final FakeHuman human = new FakeHuman();

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private VerificationRunner runner(EnvironmentFeedbackVerifier env) {
        return new VerificationRunner(model, catalog, human, budget, env);
    }

    private static StepStatus ignored() {
        return StepStatus.PENDING;
    }

    private static Plan plan(Set<VerificationType> layers) {
        return Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "做事", "目标", "期望", "标准", layers)));
    }

    @Test
    void 确定性层_空摘要失败非空通过() {
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC));

        VerificationReport empty = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, " ");
        VerificationReport ok = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "做完了");

        assertEquals(VerificationOutcome.FAILED, empty.outcome());
        assertEquals(VerificationOutcome.PASSED, ok.outcome());
    }

    @Test
    void 确定性层_声明是JSON但无法解析则失败() {
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC));

        VerificationReport broken = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "{\"a\":}");

        assertEquals(VerificationOutcome.FAILED, broken.outcome());
    }

    @Test
    void 环境反馈层_未配置为无法判定_配置后按其结论() {
        Plan plan = plan(EnumSet.of(VerificationType.ENV_FEEDBACK));

        VerificationReport undetermined = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "ok");
        VerificationReport failed = runner(new EnvironmentFeedbackVerifier() {
            @Override
            public Outcome verify(io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep step,
                                  String summary) {
                return Outcome.FAILED;
            }
        }).verify(session, plan, plan.steps().get(0), 1, "ok");

        assertEquals(VerificationOutcome.UNDETERMINED, undetermined.outcome());
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
    }

    @Test
    void 裁判层_通过_失败_无法判定与解析失败() {
        when(catalog.agents()).thenReturn(Collections.singletonList(judgeAgent()));
        Plan plan = plan(EnumSet.of(VerificationType.LLM_JUDGE));

        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("{\"verdict\":\"PASSED\"}"));
        assertEquals(VerificationOutcome.PASSED, verify(plan, "ok").outcome());

        when(model.invoke(any(ModelInvocationRequest.class)))
                .thenReturn(reply("{\"verdict\":\"FAILED\",\"reason\":\"不达标\"}"));
        VerificationReport failed = verify(plan, "ok");
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
        assertEquals("不达标", failed.failureReason());

        when(model.invoke(any(ModelInvocationRequest.class)))
                .thenReturn(reply("{\"verdict\":\"UNDETERMINED\"}"));
        assertEquals(VerificationOutcome.UNDETERMINED, verify(plan, "ok").outcome());

        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply("不是JSON"));
        assertEquals(VerificationOutcome.UNDETERMINED, verify(plan, "ok").outcome());

        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(null));
        assertEquals(VerificationOutcome.UNDETERMINED, verify(plan, "ok").outcome());
    }

    @Test
    void 裁判层_缺裁判代理即无法判定() {
        when(catalog.agents()).thenReturn(Collections.<AgentDefinition>emptyList());
        Plan plan = plan(EnumSet.of(VerificationType.LLM_JUDGE));

        assertEquals(VerificationOutcome.UNDETERMINED, verify(plan, "ok").outcome());
    }

    @Test
    void 人工层_同意通过_不同意失败_取消无法判定() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));

        human.answer = "同意";
        assertEquals(VerificationOutcome.PASSED, verify(plan, "ok").outcome());

        human.answer = "不同意，理由不足";
        VerificationReport failed = verify(plan, "ok");
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
        assertEquals("不同意，理由不足", failed.failureReason());

        human.cancel = true;
        assertEquals(VerificationOutcome.UNDETERMINED, verify(plan, "ok").outcome());
    }

    @Test
    void 多层时前一层失败即短路() {
        when(catalog.agents()).thenReturn(Collections.singletonList(judgeAgent()));
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC, VerificationType.LLM_JUDGE));

        VerificationReport report = verify(plan, " ");

        assertEquals(VerificationOutcome.FAILED, report.outcome());
        assertEquals(VerificationType.DETERMINISTIC, report.failedType());
    }

    private VerificationReport verify(Plan plan, String summary) {
        return runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, summary);
    }

    private static ModelInvocationResult reply(String text) {
        return new ModelInvocationResult(text, Collections.emptyList(), new ModelUsage(1, 1),
                ModelId.of("m1"));
    }

    private static AgentDefinition judgeAgent() {
        return new AgentDefinition(AgentId.of("builtin.judge"), "裁判", AgentSource.BUILT_IN,
                new PromptTemplate("你是独立判定者", Collections.<String>emptyList()), AgentStatus.ACTIVE,
                Collections.emptyList(), Collections.emptyList());
    }

    private static final class FakeHuman implements HumanInteractionPort {

        private String answer = "同意";
        private boolean cancel;

        @Override
        public Answer ask(Question question) {
            return cancel ? Answer.cancelled() : Answer.answered(answer);
        }
    }
}
