package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划完结时把执行事实蒸馏成经验：读轨迹 → 归纳子代理 → 去重合并或新建。
 * 每 Plan 最多 `experiencePerPlan` 条；产出 0 条是合法结果。
 * 规则出处：模块-记忆 §4、§7.1 规则 ②③④、§8.2。
 */
public final class ExperienceDistillationService {

    private static final AgentId DISTILL_AGENT = AgentId.of("builtin.distill");

    private final ExperienceRepository experiences;
    private final RecordStore records;
    private final ModelInvocationPort model;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final BudgetService budgetService;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;

    public ExperienceDistillationService(ExperienceRepository experiences, RecordStore records,
                                         ModelInvocationPort model, CatalogRepository catalog,
                                         PolicyRepository policies, BudgetService budgetService,
                                         CommitScope commitScope, DomainEventPublisher eventPublisher,
                                         ProgressPort progress) {
        this.experiences = experiences;
        this.records = records;
        this.model = model;
        this.catalog = catalog;
        this.policies = policies;
        this.budgetService = budgetService;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
    }

    public void distill(Session session, DistillationInput input) {
        RuntimePolicy policy = policies.load();
        String material = material(session, input);
        if (material.isEmpty()) {
            return;   // 无可用素材：本次不产出（§8.2 要点 4）
        }
        AgentDefinition agent = agent();
        if (agent == null) {
            return;
        }
        List<Candidate> candidates = ask(session, agent, material);
        int created = 0;
        for (Candidate candidate : candidates) {
            if (created >= policy.experiencePerPlan()) {
                break;
            }
            if (mergeOrCreate(session, candidate, policy)) {
                created++;
            }
        }
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED, "经验沉淀完成：" + created + " 条"));
    }

    /** 素材只留"失败后翻盘"的步骤与其最终结果，超长按截断阈值裁剪（§8.2 要点 3）。 */
    private String material(Session session, DistillationInput input) {
        StringBuilder out = new StringBuilder();
        out.append("目标：").append(input.goal()).append("\n结局：").append(input.outcome()).append('\n');
        for (StepTrace trace : input.stepTraces()) {
            if (trace.failureReason() == null && trace.reflectionConclusion() == null) {
                continue;
            }
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

    private String traceOf(Session session, StepTrace trace) {
        if (trace.recordRange() == null) {
            return "（无轨迹区间）";
        }
        try {
            List<Record> range = records.range(session.id(), trace.recordRange().getStartRecordId(),
                    trace.recordRange().getEndRecordId());
            StringBuilder out = new StringBuilder();
            int budget = policies.load().toolResultTruncateLength();
            for (Record record : range) {
                if (record.role() == RecordRole.SYSTEM) {
                    continue;
                }
                if (out.length() >= budget) {
                    out.append("…");
                    break;
                }
                out.append(record.role()).append(": ").append(record.content()).append("\n");
            }
            return out.toString();
        } catch (RuntimeException missing) {
            return "（轨迹不可读）";
        }
    }

    private List<Candidate> ask(Session session, AgentDefinition agent, String material) {
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                    agent.basePrompt().getTemplateText(),
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, material)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage().total());
            }
            return parseCandidates(result.text());
        } catch (RuntimeException unavailable) {
            return Collections.emptyList();
        }
    }

    private List<Candidate> parseCandidates(String text) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(text == null ? "" : text.trim());
        } catch (IllegalArgumentException broken) {
            return candidates;
        }
        Object raw = parsed.get("experiences");
        if (!(raw instanceof List)) {
            return candidates;
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> fields = (Map<?, ?>) item;
            Object scenario = fields.get("applicableScenario");
            Object instruction = fields.get("instruction");
            if (scenario == null || instruction == null) {
                continue;
            }
            candidates.add(new Candidate(String.valueOf(scenario), String.valueOf(instruction),
                    fields.get("expectedOutcome") == null ? null : String.valueOf(fields.get("expectedOutcome"))));
        }
        return candidates;
    }

    /** 语义重复 ⇒ 合并 + 提置信度；否则（容量够）新建。规则出处：§7.1 规则 ④⑥。 */
    private boolean mergeOrCreate(Session session, Candidate candidate, RuntimePolicy policy) {
        Experience duplicate = findDuplicate(candidate);
        if (duplicate != null) {
            duplicate.mergeWith(mergeSource(duplicate, candidate), policy.experienceConfidenceStep());
            commit(duplicate);
            return false;
        }
        if (experiences.count() >= policy.experienceCapacity()) {
            Experience evicted = evictionCandidate();
            if (evicted != null) {
                evicted.archive();
                commit(evicted);
            }
        }
        Experience created = Experience.create(ExperienceId.of(UUID.randomUUID().toString()),
                candidate.scenario, new LessonLearned(candidate.instruction, candidate.expectedOutcome),
                initialConfidence(policy, session), System.currentTimeMillis());
        commit(created);
        return true;
    }

    /** 「最早且最少被使用」先淘汰：排序在应用层做，端口只提供数据（§7.1 规则 ⑥）。 */
    private Experience evictionCandidate() {
        Experience candidate = null;
        for (Experience experience : experiences.findActive()) {
            if (candidate == null
                    || experience.createdAt() < candidate.createdAt()
                    || (experience.createdAt() == candidate.createdAt()
                        && experience.appliedCount() < candidate.appliedCount())) {
                candidate = experience;
            }
        }
        return candidate;
    }

    private Experience findDuplicate(Candidate candidate) {
        for (Experience experience : experiences.findActive()) {
            if (experience.applicableScenario().equals(candidate.scenario)) {
                return experience;
            }
        }
        return null;
    }

    /** 合并来源：只承载候选教训，使用次数为 0，也不 raise 新建事件。 */
    private static Experience mergeSource(Experience duplicate, Candidate candidate) {
        return new Experience(duplicate.id(), candidate.scenario,
                new LessonLearned(candidate.instruction, candidate.expectedOutcome),
                duplicate.confidence(), duplicate.status(), 0, duplicate.createdAt());
    }

    private static double initialConfidence(RuntimePolicy policy, Session session) {
        return policy.experienceInitialConfidence();
    }

    private void commit(Experience experience) {
        commitScope.stageReplace(experience);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(experience.pullEvents()));
    }

    private AgentDefinition agent() {
        for (AgentDefinition agent : catalog.agents()) {
            if (DISTILL_AGENT.equals(agent.id())) {
                return agent;
            }
        }
        return null;
    }

    private static final class Candidate {

        private final String scenario;
        private final String instruction;
        private final String expectedOutcome;

        private Candidate(String scenario, String instruction, String expectedOutcome) {
            this.scenario = scenario;
            this.instruction = instruction;
            this.expectedOutcome = expectedOutcome;
        }
    }
}
