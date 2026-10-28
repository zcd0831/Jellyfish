package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
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
    private final InternalSessionRunner forks;
    private final CommitScope commitScope;

    public PlanCompletionCurator(RecordStore records, ModelInvocationPort model, CatalogRepository catalog,
                                 PolicyRepository policies, BudgetService budgetService,
                                 ExperienceDistillationService experienceDistillation,
                                 SkillDistillationService skillDistillation, InternalSessionRunner forks,
                                 CommitScope commitScope) {
        this.records = records;
        this.model = model;
        this.catalog = catalog;
        this.policies = policies;
        this.budgetService = budgetService;
        this.experienceDistillation = experienceDistillation;
        this.skillDistillation = skillDistillation;
        this.forks = forks;
        this.commitScope = commitScope;
    }

    public DistillationOutcome curate(Session session, DistillationInput input) {
        AgentDefinition agent = agent();
        if (agent == null) {
            return DistillationOutcome.empty();
        }
        if (alreadyNotified(session, input)) {
            return DistillationOutcome.empty();
        }
        List<SkillId> used = usedSkillIds(input);
        String answer;
        try {
            answer = forks.run(session, instruction(agent, used));
        } catch (RuntimeException unavailable) {
            return DistillationOutcome.empty();   // 沉淀失败只是“这次没学到”，不影响会话继续
        }
        DistillationOutcome outcome = used.isEmpty() ? parseNewKnowledge(answer) : parseRevisions(answer, used);
        dispatch(session, input, outcome);
        notifyUser(session, input, outcome);
        return outcome;
    }

    /**
     * 末尾指令：代理提示词 + 本次用到的技能 id（判改进要用）+ 输出形状。
     * 轨迹与窗口都由 fork 带过去，这里不再拼素材（08-application §8.9.7）。
     */
    private String instruction(AgentDefinition agent, List<SkillId> used) {
        StringBuilder out = new StringBuilder(agent.basePrompt().getTemplateText());
        out.append("\n\n本次计划用到的技能：");
        if (used.isEmpty()) {
            out.append("（无，判是否值得沉淀）");
        } else {
            for (SkillId skillId : used) {
                out.append(skillId.getValue()).append(' ');
            }
        }
        out.append("\n只输出 JSON：{\"experience\":{\"applicableScenario\":…,\"instruction\":…,"
                + "\"expectedOutcome\":…}} 或 {\"skills\":[{action,name,description,triggerCondition,"
                + "sopBody,targetSkillId?}]}；两者只能有一个，都没有就输出 {}。");
        return out.toString();
    }

    /** 告知记录：有界摘要 + planId 标记，供“结论交付”轮由模型播报（08-application §8.9.7 T17）。 */
    private void notifyUser(Session session, DistillationInput input, DistillationOutcome outcome) {
        String digest = digestOf(outcome);
        if (digest == null) {
            return;
        }
        Record notice = session.appendRecord(RecordRole.SYSTEM,
                "[沉淀告知 plan=" + input.planId().getValue() + "] " + digest, null, System.currentTimeMillis());
        commitScope.stageAppend(session.id(), notice);
        commitScope.commit();
    }

    private static String digestOf(DistillationOutcome outcome) {
        if (outcome.experience() != null) {
            return "已将「" + outcome.experience().applicableScenario() + "」沉淀为经验";
        }
        if (!outcome.skills().isEmpty()) {
            SkillDraft draft = outcome.skills().get(0);
            return (draft.isMerge() ? "已改进技能「" : "已生成技能「") + draft.name() + "」";
        }
        return null;
    }

    private static boolean alreadyNotified(Session session, DistillationInput input) {
        String marker = "[沉淀告知 plan=" + input.planId().getValue() + "]";
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM && record.content() != null
                    && record.content().startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    private void dispatch(Session session, DistillationInput input, DistillationOutcome outcome) {
        if (outcome.experience() != null) {
            experienceDistillation.apply(outcome.experience(), session.id(), input.planId());
        }
        for (SkillDraft draft : outcome.skills()) {
            skillDistillation.apply(draft, session.id(), input.planId());
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
