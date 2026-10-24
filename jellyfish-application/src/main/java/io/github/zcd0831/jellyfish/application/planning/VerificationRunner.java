package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationChain;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按 `VerificationChain` 给出的链序逐层执行，短路，产出不可变报告。
 * 规则出处：模块-规划 §4、§7.2、§8.3；四层口径见 M16。
 */
public final class VerificationRunner {

    private static final AgentId JUDGE_AGENT = AgentId.of("builtin.judge");

    private final VerificationChain chain = new VerificationChain();
    private final ModelInvocationPort model;
    private final CatalogRepository catalog;
    private final HumanInteractionPort human;
    private final BudgetService budgetService;
    private final EnvironmentFeedbackVerifier envFeedback;

    public VerificationRunner(ModelInvocationPort model, CatalogRepository catalog, HumanInteractionPort human,
                              BudgetService budgetService, EnvironmentFeedbackVerifier envFeedback) {
        this.model = model;
        this.catalog = catalog;
        this.human = human;
        this.budgetService = budgetService;
        this.envFeedback = envFeedback;
    }

    public VerificationReport verify(Session session, Plan plan, PlanStep step, int attemptNo, String summary) {
        String reportId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        List<VerificationType> layers = chain.orderedLayers(step.requiredVerifications());
        for (VerificationType layer : layers) {
            LayerResult result = runLayer(session, step, summary, layer);
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

    private LayerResult runLayer(Session session, PlanStep step, String summary, VerificationType layer) {
        switch (layer) {
            case DETERMINISTIC:
                return deterministic(summary);
            case ENV_FEEDBACK:
                return fromEnv(envFeedback.verify(step, summary));
            case LLM_JUDGE:
                return judge(session, step, summary);
            default:
                return humanApproval(step, summary);
        }
    }

    /** 第一层：非空 + 结构（形似 JSON 必须可解析）。规则出处：M16。 */
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
     * 第三层：裁判只吃"目标 / 期望 / 标准 / 结果"，拿不到执行者的推理链（父子包庇对策）。
     * 规则出处：M16。
     */
    private LayerResult judge(Session session, PlanStep step, String summary) {
        AgentDefinition judgeAgent = judgeAgent();
        if (judgeAgent == null) {
            return LayerResult.undetermined("裁判代理 builtin.judge 缺失");
        }
        String input = "原定目标：" + text(step.goal())
                + "\n结果期望：" + text(step.expectedOutcome())
                + "\n验证标准：" + text(step.verificationCriteria())
                + "\n执行结果：" + text(summary);
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                    judgeAgent.basePrompt().getTemplateText(),
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, input)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage().total());
            }
            return parseVerdict(result.text());
        } catch (RuntimeException unavailable) {
            return LayerResult.undetermined("裁判调用失败：" + unavailable.getMessage());
        }
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

    /** 第四层：高危操作或模型无法判定的节点交人。规则出处：M16。 */
    private LayerResult humanApproval(PlanStep step, String summary) {
        Answer answer = human.ask(new Question(Question.Source.VERIFICATION,
                "步骤「" + step.description() + "」提交验收，同意吗？（理由可选）", null));
        if (answer.kind() == Answer.Kind.CANCELLED) {
            return LayerResult.undetermined("用户取消了验收");
        }
        String text = answer.text() == null ? "" : answer.text();
        if (text.contains("同意") || text.equalsIgnoreCase("y") || text.equalsIgnoreCase("yes")) {
            return LayerResult.passed();
        }
        return LayerResult.failed(text.trim().isEmpty() ? "用户不同意，但未给出原因" : text);
    }

    private AgentDefinition judgeAgent() {
        for (AgentDefinition agent : catalog.agents()) {
            if (JUDGE_AGENT.equals(agent.id())) {
                return agent;
            }
        }
        return null;
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
