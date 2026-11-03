package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider;
import io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 四层判定与短路。规则出处：06-planning §6.8.2、§6.9.3。 */
class VerificationRunnerTest {

    private final InternalSessionRunner forks = mock(InternalSessionRunner.class);
    private final FakeHuman human = new FakeHuman();

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    /** 默认：裁判子会话什么都答不出来（等价于裁判缺失）；需要的用例自己再覆盖。 */
    private VerificationRunner runner(EnvironmentFeedbackVerifier env) {
        when(forks.runStateless(any(Session.class), any(), any(), any())).thenReturn(null);
        return new VerificationRunner(new ForkRunnerProvider() {
            @Override
            public InternalSessionRunner get() {
                return forks;
            }
        }, human, env);
    }

    private static Plan plan(Set<VerificationType> layers) {
        return Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "做事", "目标", "期望", "标准", layers)));
    }

    @Test
    void 确定性层_空摘要失败非空通过() {
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC));

        VerificationReport empty = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, " ", Collections.<String>emptyList());
        VerificationReport ok = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "做完了", Collections.<String>emptyList());

        assertEquals(VerificationOutcome.FAILED, empty.outcome());
        assertEquals(VerificationOutcome.PASSED, ok.outcome());
    }

    @Test
    void 确定性层_声明是JSON但无法解析则失败() {
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC));

        VerificationReport broken = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "{\"a\":}", Collections.<String>emptyList());

        assertEquals(VerificationOutcome.FAILED, broken.outcome());
    }

    @Test
    void 环境反馈层_未配置为无法判定_配置后按其结论() {
        Plan plan = plan(EnumSet.of(VerificationType.ENV_FEEDBACK));

        VerificationReport undetermined = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "ok", Collections.<String>emptyList());
        VerificationReport failed = runner(new EnvironmentFeedbackVerifier() {
            @Override
            public Outcome verify(io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep step,
                                  String summary) {
                return Outcome.FAILED;
            }
        }).verify(session, plan, plan.steps().get(0), 1, "ok", Collections.<String>emptyList());

        assertEquals(VerificationOutcome.UNDETERMINED, undetermined.outcome());
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
    }

    @Test
    void 裁判层_通过_失败_无法判定与解析失败() {
        Plan plan = plan(EnumSet.of(VerificationType.LLM_JUDGE));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);

        when(forks.runStateless(any(Session.class), any(), any(), any()))
                .thenReturn("{\"verdict\":\"PASSED\"}");
        assertEquals(VerificationOutcome.PASSED, verifyWith(runner, plan).outcome());

        when(forks.runStateless(any(Session.class), any(), any(), any()))
                .thenReturn("{\"verdict\":\"FAILED\",\"reason\":\"不达标\"}");
        VerificationReport failed = verifyWith(runner, plan);
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
        assertEquals("不达标", failed.failureReason());

        when(forks.runStateless(any(Session.class), any(), any(), any()))
                .thenReturn("{\"verdict\":\"UNDETERMINED\"}");
        assertEquals(VerificationOutcome.UNDETERMINED, verifyWith(runner, plan).outcome());

        when(forks.runStateless(any(Session.class), any(), any(), any())).thenReturn("不是JSON");
        assertEquals(VerificationOutcome.UNDETERMINED, verifyWith(runner, plan).outcome());

        when(forks.runStateless(any(Session.class), any(), any(), any())).thenReturn(null);
        assertEquals(VerificationOutcome.UNDETERMINED, verifyWith(runner, plan).outcome());
    }

    /**
     * 裁判在无历史子会话里跑，只能读 submit_step 声明的产物：产物清单必须原样交给它，
     * 且提示里要写清“只能读这些”（06-planning §6.8.2、§6.9.3）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 裁判拿到产物清单且提示里写清可读范围() {
        Plan plan = plan(EnumSet.of(VerificationType.LLM_JUDGE));
        List<String> artifacts = Arrays.asList("out/report.md", "out/data.csv");
        when(forks.runStateless(any(Session.class), any(), any(), any()))
                .thenReturn("{\"verdict\":\"PASSED\"}");

        runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, "做完了", artifacts);

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> scope = ArgumentCaptor.forClass(List.class);
        verify(forks).runStateless(eq(session), any(), instruction.capture(), scope.capture());
        assertEquals(artifacts, scope.getValue(), "产物清单原样交给裁判");
        assertTrue(instruction.getValue().contains("out/report.md"), instruction.getValue());
        assertTrue(instruction.getValue().contains("read_artifact"), instruction.getValue());
    }

    @Test
    void 裁判缺失即无法判定() {
        Plan plan = plan(EnumSet.of(VerificationType.LLM_JUDGE));

        assertEquals(VerificationOutcome.UNDETERMINED,
                verifyWith(runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED), plan).outcome());
    }

    @Test
    void 人工层_同意通过_不同意失败_取消无法判定() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);

        human.answer = "同意";
        assertEquals(VerificationOutcome.PASSED, verifyWith(runner, plan).outcome());

        human.answer = "不同意，理由不足";
        VerificationReport failed = verifyWith(runner, plan);
        assertEquals(VerificationOutcome.FAILED, failed.outcome());
        assertEquals("不同意，理由不足", failed.failureReason());

        human.cancel = true;
        assertEquals(VerificationOutcome.UNDETERMINED, verifyWith(runner, plan).outcome());
    }

    @Test
    void 人工层空回答按不通过处理() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);

        human.answer = "";
        VerificationReport blank = verifyWith(runner, plan);

        // 空回车不是"同意"：人工层要求返回同意/不同意 + 原因（06-planning §6.8.2 规则 5）
        assertEquals(VerificationOutcome.FAILED, blank.outcome());
        assertEquals("用户未给出明确答复，按不通过处理", blank.failureReason());

        human.answer = "不继续";
        assertEquals(VerificationOutcome.FAILED, verifyWith(runner, plan).outcome());
    }

    @Test
    void 人工层以选项呈现且同意不需要原因() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        human.queued.add("同意");

        assertEquals(VerificationOutcome.PASSED, verifyWith(runner, plan).outcome());

        // 06-planning §6.8.2 规则 5：人工层以选项「同意 / 不同意」呈现
        assertEquals(java.util.Arrays.asList("同意", "不同意"), human.asked.get(0).options());
        assertEquals(1, human.asked.size());
    }

    @Test
    void 人工层选不同意必须补原因_原因成为失败理由() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        human.queued.add("不同意");
        human.queued.add("产物缺签名");

        VerificationReport report = verifyWith(runner, plan);

        assertEquals(VerificationOutcome.FAILED, report.outcome());
        assertEquals("产物缺签名", report.failureReason());
        assertEquals(2, human.asked.size());
        assertTrue(human.asked.get(1).options() == null, "追问原因是自由文本");
    }

    @Test
    void 人工层选不同意但一直不填原因_追问有上限且不卡死() {
        Plan plan = plan(EnumSet.of(VerificationType.HUMAN_APPROVAL));
        VerificationRunner runner = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        human.answer = "";
        human.queued.add("不同意");

        VerificationReport report = verifyWith(runner, plan);

        assertEquals(VerificationOutcome.FAILED, report.outcome());
        assertEquals("用户不同意，但未给出原因", report.failureReason());
        assertEquals(1 + 3, human.asked.size(), "追问最多 3 次");
    }

    @Test
    void 多层时前一层失败即短路() {
        when(forks.runStateless(any(Session.class), any(), any(), any()))
                .thenReturn("{\"verdict\":\"PASSED\"}");
        Plan plan = plan(EnumSet.of(VerificationType.DETERMINISTIC, VerificationType.LLM_JUDGE));

        VerificationReport report = runner(EnvironmentFeedbackVerifier.NOT_CONFIGURED)
                .verify(session, plan, plan.steps().get(0), 1, " ", Collections.<String>emptyList());

        assertEquals(VerificationOutcome.FAILED, report.outcome());
        assertEquals(VerificationType.DETERMINISTIC, report.failedType());
    }

    private VerificationReport verifyWith(VerificationRunner runner, Plan plan) {
        return runner.verify(session, plan, plan.steps().get(0), 1, "做完了", Collections.<String>emptyList());
    }

    private static final class FakeHuman implements HumanInteractionPort {

        private String answer = "同意";
        private boolean cancel;
        private final java.util.List<String> queued = new java.util.ArrayList<String>();
        private final java.util.List<Question> asked = new java.util.ArrayList<Question>();

        @Override
        public Answer ask(Question question) {
            asked.add(question);
            if (cancel) {
                return Answer.cancelled();
            }
            return Answer.answered(queued.isEmpty() ? answer : queued.remove(0));
        }
    }
}
