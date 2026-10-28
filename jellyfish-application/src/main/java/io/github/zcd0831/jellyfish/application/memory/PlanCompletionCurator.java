package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划完结时的唯一归纳入口：**一次**内置代理调用、**一个**判定——用过技能就判改进，没用过才判沉淀。
 * 经验与技能互斥（同一件事只进一边），所以必须一次判定，不能拆成两次。
 * 规则出处：07-memory §7.9.6。
 */
public final class PlanCompletionCurator {

    private static final AgentId DISTILL_AGENT = AgentId.of("builtin.distill");

    private final RecordStore records;
    private final ModelInvocationPort model;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final BudgetService budgetService;
    private final ExperienceDistillationService experienceDistillation;
    private final SkillDistillationService skillDistillation;

    public PlanCompletionCurator(RecordStore records, ModelInvocationPort model, CatalogRepository catalog,
                                 PolicyRepository policies, BudgetService budgetService,
                                 ExperienceDistillationService experienceDistillation,
                                 SkillDistillationService skillDistillation) {
        this.records = records;
        this.model = model;
        this.catalog = catalog;
        this.policies = policies;
        this.budgetService = budgetService;
        this.experienceDistillation = experienceDistillation;
        this.skillDistillation = skillDistillation;
    }

    public DistillationOutcome curate(Session session, DistillationInput input) {
        AgentDefinition agent = agent();
        if (agent == null) {
            return DistillationOutcome.empty();
        }
        List<SkillId> used = usedSkillIds(input);
        String answer = ask(session, agent, material(session, input, used));
        DistillationOutcome outcome = used.isEmpty() ? parseNewKnowledge(answer) : parseRevisions(answer, used);
        dispatch(session, outcome);
        return outcome;
    }

    private void dispatch(Session session, DistillationOutcome outcome) {
        if (outcome.experience() != null) {
            experienceDistillation.apply(outcome.experience(), session.id());
        }
        for (SkillDraft draft : outcome.skills()) {
            skillDistillation.apply(draft, session.id());
        }
    }

    /** 用过技能只判改进，没用过才判沉淀：互斥起点就在这里（§8.6）。 */
    private static List<SkillId> usedSkillIds(DistillationInput input) {
        Set<SkillId> used = new LinkedHashSet<SkillId>();
        for (StepTrace trace : input.stepTraces()) {
            used.addAll(trace.usedSkillIds());
        }
        return new ArrayList<SkillId>(used);
    }

    private String material(Session session, DistillationInput input, List<SkillId> used) {
        StringBuilder out = new StringBuilder();
        out.append("目标：").append(input.goal()).append("\n结局：").append(input.outcome()).append('\n');
        if (used.isEmpty()) {
            out.append("\n已有技能（若本次做法已被某条覆盖，就选 MERGE）：\n");
            for (SkillDefinition skill : catalog.skills()) {
                if (skill.isActive()) {
                    out.append("- ").append(skill.id().getValue()).append(" | ").append(skill.name())
                            .append(" | ").append(skill.description()).append('\n');
                }
            }
        } else {
            out.append("\n本次用到的技能（现有正文）：\n");
            for (SkillId skillId : used) {
                SkillDefinition skill = find(skillId);
                out.append("\n[").append(skillId.getValue()).append("]\n")
                        .append(skill == null ? "（已不可用）" : skill.sopTemplate().getRawMarkdown()).append('\n');
            }
        }
        for (StepTrace trace : input.stepTraces()) {
            out.append("\n步骤：").append(trace.description()).append("（").append(trace.outcome()).append("）\n");
            if (trace.failureReason() != null) {
                out.append("失败原因：").append(trace.failureReason()).append('\n');
            }
            if (trace.reflectionConclusion() != null) {
                out.append("反思结论：").append(trace.reflectionConclusion()).append('\n');
            }
            out.append("轨迹：").append(traceOf(session, trace)).append('\n');
        }
        return out.toString();
    }

    /**
     * 轨迹结构化：每轮一行"工具名(参数摘要) → 结果摘要"，而不是原文拼接。
     * 只读 `content`——`reasoning` 是回放用事实，永不进沉淀（07-memory §7.9.6）。
     */
    private String traceOf(Session session, StepTrace trace) {
        if (trace.recordRange() == null) {
            return "（无轨迹区间）";
        }
        try {
            List<Record> range = records.range(session.id(), trace.recordRange().getStartRecordId(),
                    trace.recordRange().getEndRecordId());
            StringBuilder out = new StringBuilder();
            int budget = policies.load().toolResultTruncateLength();
            int round = 0;
            for (Record record : range) {
                if (record.role() == RecordRole.SYSTEM || record.role() == RecordRole.SUMMARY) {
                    continue;
                }
                if (out.length() >= budget) {
                    out.append("…");
                    break;
                }
                switch (record.role()) {
                    case ASSISTANT:
                        out.append("第 ").append(++round).append(" 轮：")
                                .append(oneLine(record.content(), 160)).append('\n');
                        break;
                    case USER:
                        out.append("用户：").append(oneLine(record.content(), 160)).append('\n');
                        break;
                    case TOOL_REQUEST:
                        out.append("第 ").append(Math.max(round, 1)).append(" 轮：")
                                .append(describeCall(record.content())).append(" → ");
                        break;
                    case TOOL_RESULT:
                        out.append("结果：").append(oneLine(record.content(), 200)).append('\n');
                        break;
                    default:
                        break;
                }
            }
            return out.toString();
        } catch (RuntimeException missing) {
            return "（轨迹不可读）";
        }
    }

    /** 工具请求在流水里是单行 JSON：取工具名与参数，压成一行人读。 */
    private static String describeCall(String content) {
        Map<String, Object> fields = Json.parseObject(content);
        Object name = fields.get("name");
        Object arguments = fields.get("arguments");
        return (name == null ? "（未知工具）" : String.valueOf(name))
                + "(" + oneLine(arguments == null ? "" : String.valueOf(arguments), 120) + ")";
    }

    private static String oneLine(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private String ask(Session session, AgentDefinition agent, String material) {
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                    agent.basePrompt().getTemplateText(),
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, material)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage());
            }
            return result.text();
        } catch (RuntimeException unavailable) {
            // 沉淀只是"这次没学到"，不影响会话继续（07-memory §7.9.6）
            return null;
        }
    }

    /** 没用过技能：经验与技能二选一（模型给两个就当本次不产出）。 */
    private DistillationOutcome parseNewKnowledge(String text) {
        Map<String, Object> parsed = parseObject(text);
        ExperienceDraft experience = experienceOf(parsed.get("experience"));
        SkillDraft skill = skillOf(parsed.get("skill"));
        if (experience != null && skill != null) {
            return DistillationOutcome.empty();
        }
        if (experience != null) {
            return DistillationOutcome.experience(experience);
        }
        return skill == null ? DistillationOutcome.empty()
                : DistillationOutcome.skills(Collections.singletonList(skill));
    }

    /** 用过技能：只接受对这些技能的改进（子代理不许顺手沉淀新技能）。 */
    private DistillationOutcome parseRevisions(String text, List<SkillId> used) {
        Map<String, Object> parsed = parseObject(text);
        Object raw = parsed.get("revisions");
        if (!(raw instanceof List)) {
            return DistillationOutcome.empty();
        }
        List<SkillDraft> drafts = new ArrayList<SkillDraft>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> fields = (Map<?, ?>) item;
            Object body = fields.get("sopBody");
            SkillId target = skillIdOf(fields.get("skillId"));
            if (body == null || target == null || !used.contains(target)) {
                continue;
            }
            SkillDefinition existing = find(target);
            if (existing == null) {
                continue;
            }
            // 三件套沿用现有值：子代理只能改正文，不能借改进悄悄换掉发现入口
            drafts.add(new SkillDraft(SkillDraft.Action.MERGE, target, existing.name(),
                    existing.description(), existing.triggerCondition(), String.valueOf(body)));
        }
        return drafts.isEmpty() ? DistillationOutcome.empty() : DistillationOutcome.skills(drafts);
    }

    private ExperienceDraft experienceOf(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> fields = (Map<?, ?>) raw;
        Object scenario = fields.get("applicableScenario");
        Object instruction = fields.get("instruction");
        if (scenario == null || instruction == null) {
            return null;
        }
        return new ExperienceDraft(String.valueOf(scenario), String.valueOf(instruction),
                fields.get("expectedOutcome") == null ? null : String.valueOf(fields.get("expectedOutcome")));
    }

    private SkillDraft skillOf(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> fields = (Map<?, ?>) raw;
        Object sopBody = fields.get("sopBody");
        if (sopBody == null) {
            return null;
        }
        SkillId target = skillIdOf(fields.get("mergeInto"));
        if (target != null) {
            return new SkillDraft(SkillDraft.Action.MERGE, target, text(fields.get("name")),
                    text(fields.get("description")), text(fields.get("triggerCondition")),
                    String.valueOf(sopBody));
        }
        return new SkillDraft(SkillDraft.Action.CREATE, null, text(fields.get("name")),
                text(fields.get("description")), text(fields.get("triggerCondition")),
                String.valueOf(sopBody));
    }

    private static SkillId skillIdOf(Object raw) {
        return raw == null || String.valueOf(raw).trim().isEmpty() ? null : SkillId.of(String.valueOf(raw));
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Map<String, Object> parseObject(String text) {
        try {
            return Json.parseObject(text == null ? "" : text.trim());
        } catch (IllegalArgumentException broken) {
            return Collections.emptyMap();
        }
    }

    private SkillDefinition find(SkillId skillId) {
        for (SkillDefinition skill : catalog.skills()) {
            if (skill.id().equals(skillId)) {
                return skill;
            }
        }
        return null;
    }

    private AgentDefinition agent() {
        for (AgentDefinition agent : catalog.agents()) {
            if (DISTILL_AGENT.equals(agent.id())) {
                return agent;
            }
        }
        return null;
    }
}
