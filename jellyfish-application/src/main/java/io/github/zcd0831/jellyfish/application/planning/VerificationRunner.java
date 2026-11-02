package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider;
import io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationChain;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按 `VerificationChain` 给出的链序逐层执行，短路，产出不可变报告。
 * 规则出处：06-planning §6.8.2（四层判定口径）、§6.9.3。
 */
public final class VerificationRunner {

    private static final AgentId JUDGE_AGENT = AgentId.of("builtin.judge");

    private final VerificationChain chain = new VerificationChain();
    private final ForkRunnerProvider forks;
    private final HumanInteractionPort human;
    private final EnvironmentFeedbackVerifier envFeedback;

    public VerificationRunner(ForkRunnerProvider forks, HumanInteractionPort human,
                              EnvironmentFeedbackVerifier envFeedback) {
        this.forks = forks;
        this.human = human;
        this.envFeedback = envFeedback;
    }

    public VerificationReport verify(Session session, Plan plan, PlanStep step, int attemptNo, String summary,
                                     List<String> artifacts) {
        String reportId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        List<VerificationType> layers = chain.orderedLayers(step.requiredVerifications());
        for (VerificationType layer : layers) {
            LayerResult result = runLayer(session, step, summary, artifacts, layer);
            if (result.outcome != VerificationOutcome.PASSED) {
                TechnicalLog.verified(plan.id().getValue(), step.id().getValue(), result.outcome.name(),
                        System.currentTimeMillis() - startedAt);
                return chain.rejected(VerificationReportId.of(reportId), plan.id(), step.id(), attemptNo,
                        result.outcome, layer, result.reason, System.currentTimeMillis());
            }
        }
        TechnicalLog.verified(plan.id().getValue(), step.id().getValue(), VerificationOutcome.PASSED.name(),
                System.currentTimeMillis() - startedAt);
        return chain.passed(VerificationReportId.of(reportId), plan.id(), step.id(), attemptNo,
                System.currentTimeMillis());
    }

    private LayerResult runLayer(Session session, PlanStep step, String summary, List<String> artifacts,
                                 VerificationType layer) {
        switch (layer) {
            case DETERMINISTIC:
                return deterministic(summary);
            case ENV_FEEDBACK:
                return fromEnv(envFeedback.verify(step, summary));
            case LLM_JUDGE:
                return judge(session, step, summary, artifacts);
            default:
                return humanApproval(step, summary);
        }
    }

    /** 第一层：非空 + 结构（形似 JSON 必须可解析）。 */
    private LayerResult deterministic(String summary) {
        if (summary == null || summary.trim().isEmpty()) {
            return LayerResult.failed("提交摘要为空，无法验收");
        }
        String trimmed = summary.trim();
        if (trimmed.startsWith("{")) {
            try {
                Json.parseObject(trimmed);
            } catch (IllegalArgumentException broken) {
                return LayerResult.failed("提交摘要声明是 JSON 但无法解析：" + broken.getMessage());
            }
        }
        return LayerResult.passed();
    }

    private LayerResult fromEnv(EnvironmentFeedbackVerifier.Outcome outcome) {
        switch (outcome) {
            case PASSED:
                return LayerResult.passed();
            case FAILED:
                return LayerResult.failed("外部系统反馈未通过");
            default:
                return LayerResult.undetermined("环境反馈验证尚未配置（预留钩子）");
        }
    }

    /**
     * 第三层：裁判在**无历史子会话**里跑 ReAct，只能读 submit_step 声明的产物；
     * 拿不到执行者的推理链（父子包庇对策，06-planning §6.8.2、§6.9.3）。
     */
    private LayerResult judge(Session session, PlanStep step, String summary, List<String> artifacts) {
        String input = "原定目标：" + text(step.goal())
                + "\n结果期望：" + text(step.expectedOutcome())
                + "\n验证标准：" + text(step.verificationCriteria())
                + "\n执行结果：" + text(summary)
                + (artifacts == null || artifacts.isEmpty() ? ""
                        : "\n可读产物（read_artifact 只能读这些）：" + join(artifacts));
        try {
            InternalSessionRunner runner = forks.get();
            if (runner == null) {
                return LayerResult.undetermined("裁判执行点未装配");
            }
            String verdict = runner.runStateless(session, JUDGE_AGENT, input, artifacts);
            return parseVerdict(verdict);
        } catch (java.util.concurrent.CancellationException terminated) {
            throw terminated;   // 终止是终态，不能吞成“无法判定”（08-application §8.9.4.1）
        } catch (RuntimeException unavailable) {
            return LayerResult.undetermined("裁判调用失败：" + unavailable.getMessage());
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append("、");
            }
            out.append(value);
        }
        return out.toString();
    }

    private static LayerResult parseVerdict(String text) {
        if (text == null) {
            return LayerResult.undetermined("裁判没有给出判定");
        }
        Map<String, Object> verdict;
        try {
            verdict = Json.parseObject(text.trim());
        } catch (IllegalArgumentException broken) {
            return LayerResult.undetermined("裁判输出不是合法 JSON：" + text);
        }
        Object value = verdict.get("verdict");
        String reason = verdict.get("reason") == null ? null : String.valueOf(verdict.get("reason"));
        if ("PASSED".equals(value)) {
            return LayerResult.passed();
        }
        if ("FAILED".equals(value)) {
            return LayerResult.failed(reason == null ? "裁判判定未通过" : reason);
        }
        return LayerResult.undetermined(reason == null ? "裁判无法判定" : reason);
    }

    /** 第四层：高危操作或模型无法判定的节点交人。 */
    private LayerResult humanApproval(PlanStep step, String summary) {
        Answer answer = human.ask(new Question(Question.Source.VERIFICATION,
                "步骤「" + step.description() + "」提交验收，同意吗？（理由可选）", null));
        if (answer.kind() == Answer.Kind.CANCELLED) {
            return LayerResult.undetermined("用户取消了验收");
        }
        String text = answer.text() == null ? "" : answer.text();
        if (io.github.zcd0831.jellyfish.application.shared.Approvals.isApproved(text)) {
            return LayerResult.passed();
        }
        return LayerResult.failed(text.trim().isEmpty() ? "用户不同意，但未给出原因" : text);
    }


    private static String text(String value) {
        return value == null || value.isEmpty() ? "（未提供）" : value;
    }

    private static final class LayerResult {

        private final VerificationOutcome outcome;
        private final String reason;

        private LayerResult(VerificationOutcome outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }

        static LayerResult passed() {
            return new LayerResult(VerificationOutcome.PASSED, null);
        }

        static LayerResult failed(String reason) {
            return new LayerResult(VerificationOutcome.FAILED, reason);
        }

        static LayerResult undetermined(String reason) {
            return new LayerResult(VerificationOutcome.UNDETERMINED, reason);
        }
    }
}
