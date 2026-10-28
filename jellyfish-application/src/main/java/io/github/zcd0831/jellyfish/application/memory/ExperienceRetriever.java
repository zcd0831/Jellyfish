package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划诞生 / 重划时：全库 ACTIVE 交检索子代理语义筛选，返回每步 Top-N。
 * **只返回分配结果，不写 Plan**（由外循环调 `Plan.assignExperiences`）。
 * 规则出处：07-memory §7.5、§7.9.1。
 */
public final class ExperienceRetriever {

    private static final AgentId RETRIEVE_AGENT = AgentId.of("builtin.retrieve");

    private final ExperienceRepository experiences;
    private final ModelInvocationPort model;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final BudgetService budgetService;
    private final TokenEstimator estimator;

    public ExperienceRetriever(ExperienceRepository experiences, ModelInvocationPort model,
                               CatalogRepository catalog, PolicyRepository policies,
                               BudgetService budgetService, TokenEstimator estimator) {
        this.experiences = experiences;
        this.model = model;
        this.catalog = catalog;
        this.policies = policies;
        this.budgetService = budgetService;
        this.estimator = estimator;
    }

    public Map<StepId, List<ExperienceId>> retrieveAndAssign(Session session, RetrievalInput input) {
        List<Experience> active = experiences.findActive();
        if (active.isEmpty()) {
            return Collections.emptyMap();
        }
        AgentDefinition agent = agent();
        if (agent == null) {
            return Collections.emptyMap();
        }
        List<Experience> candidates = trimmed(active, input, agent, session);
        if (candidates == null) {
            return Collections.emptyMap();   // 素材丢完仍超限：放弃本次检索
        }
        String material = material(candidates, input);
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                    agent.basePrompt().getTemplateText(),
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, material)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage());
            }
            return parse(result.text(), input, active);
        } catch (RuntimeException unavailable) {
            // 检索失败只是"这次没有经验可用"，不阻断计划执行
            return Collections.emptyMap();
        }
    }

    /**
     * 素材超限就按置信度升序整条丢经验；丢完还超（或已无可丢）⇒ 放弃本次检索。
     * 裁剪只影响本次素材，不动聚合状态（07-memory §7.9.1）。
     */
    private List<Experience> trimmed(List<Experience> active, RetrievalInput input, AgentDefinition agent,
                                     Session session) {
        List<Experience> candidates = new ArrayList<Experience>(active);
        sortByConfidence(candidates);
        int budget = budgetFor(agent, session);
        while (!candidates.isEmpty() && estimate(material(candidates, input)) > budget) {
            candidates.remove(0);
        }
        if (candidates.isEmpty() || estimate(material(candidates, input)) > budget) {
            TechnicalLog.retrievalSkipped("over_limit");
            return null;
        }
        return candidates;
    }

    /** 辅助调用的输入上限：与主窗口同口径，扣掉代理提示词（07-memory §7.9.1）。 */
    private int budgetFor(AgentDefinition agent, Session session) {
        io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy policy = policies.load();
        int ceiling = Math.min(policy.summarizationTokenThreshold(),
                model.describe(session.activeModelId()).maxInputTokens() - policy.inputSafetyMargin());
        return ceiling - estimate(agent.basePrompt().getTemplateText());
    }

    private int estimate(String text) {
        return estimator.estimate(text);
    }

    /** 置信度升序；同分按 createdAt 升序，保证裁剪结果确定（07-memory §7.9.1）。 */
    private static void sortByConfidence(List<Experience> list) {
        java.util.Collections.sort(list, new java.util.Comparator<Experience>() {
            @Override
            public int compare(Experience left, Experience right) {
                int byConfidence = Double.compare(left.confidence().getValue(), right.confidence().getValue());
                return byConfidence != 0 ? byConfidence : Long.compare(left.createdAt(), right.createdAt());
            }
        });
    }

    /** 整库 + 各步描述一起交给子代理：不做关键词预筛（§8.1 要点 1）。 */
    private String material(List<Experience> active, RetrievalInput input) {
        StringBuilder out = new StringBuilder("经验库：\n");
        for (Experience experience : active) {
            out.append("- ").append(experience.id().getValue()).append(" | ")
                    .append(experience.applicableScenario()).append(" | ")
                    .append(experience.lesson().getInstruction()).append('\n');
        }
        out.append("\n步骤：\n");
        for (StepBrief step : input.steps()) {
            out.append("- ").append(step.stepId().getValue()).append(" | ")
                    .append(step.description()).append('\n');
        }
        out.append("\n对每个步骤给出最多 ").append(policies.load().experienceTopN())
                .append(" 条最相关的经验 id；没有相关经验就给空数组。");
        return out.toString();
    }

    private Map<StepId, List<ExperienceId>> parse(String text, RetrievalInput input, List<Experience> active) {
        java.util.Set<String> known = new java.util.HashSet<String>();
        for (Experience experience : active) {
            known.add(experience.id().getValue());
        }
        Map<StepId, List<ExperienceId>> assignment = new LinkedHashMap<StepId, List<ExperienceId>>();
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(text == null ? "" : text.trim());
        } catch (IllegalArgumentException broken) {
            return assignment;
        }
        Object steps = parsed.get("steps");
        if (!(steps instanceof List)) {
            return assignment;
        }
        int topN = Math.max(1, policies.load().experienceTopN());
        for (Object item : (List<?>) steps) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> fields = (Map<?, ?>) item;
            StepId stepId = stepOf(String.valueOf(fields.get("stepId")), input);
            if (stepId == null) {
                continue;
            }
            List<ExperienceId> ids = new ArrayList<ExperienceId>();
            Object raw = fields.get("experienceIds");
            if (raw instanceof List) {
                for (Object id : (List<?>) raw) {
                    if (ids.size() >= topN) {
                        break;
                    }
                    String value = String.valueOf(id);
                    if (known.contains(value)) {
                        ids.add(ExperienceId.of(value));
                    }
                }
            }
            assignment.put(stepId, ids);
        }
        return assignment;
    }

    private static StepId stepOf(String raw, RetrievalInput input) {
        for (StepBrief step : input.steps()) {
            if (step.stepId().getValue().equals(raw)) {
                return step.stepId();
            }
        }
        return null;
    }

    private AgentDefinition agent() {
        for (AgentDefinition agent : catalog.agents()) {
            if (RETRIEVE_AGENT.equals(agent.id())) {
                return agent;
            }
        }
        return null;
    }
}
