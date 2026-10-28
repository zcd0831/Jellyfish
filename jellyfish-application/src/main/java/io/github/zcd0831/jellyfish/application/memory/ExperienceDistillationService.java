package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 经验写入：与库内经验去重（合并 + 提置信度）或新建。
 * 素材与子代理调用在 {@link PlanCompletionCurator}——本服务零 LLM 依赖，规则可独立断言。
 * 规则出处：07-memory §7.8.1、§7.9.2。
 */
public final class ExperienceDistillationService {

    private final ExperienceRepository experiences;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;

    public ExperienceDistillationService(ExperienceRepository experiences, PolicyRepository policies,
                                         CommitScope commitScope, DomainEventPublisher eventPublisher,
                                         ProgressPort progress) {
        this.experiences = experiences;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
    }

    public void apply(ExperienceDraft draft, SessionId sessionId) {
        if (draft == null) {
            return;
        }
        RuntimePolicy policy = policies.load();
        Experience duplicate = findDuplicate(draft);
        if (duplicate != null) {
            duplicate.mergeWith(mergeSource(duplicate, draft), policy.experienceConfidenceStep());
            commit(duplicate);
            return;
        }
        evictIfFull(policy);
        commit(Experience.create(ExperienceId.of(UUID.randomUUID().toString()), draft.applicableScenario(),
                new LessonLearned(draft.instruction(), draft.expectedOutcome()),
                policy.experienceInitialConfidence(), System.currentTimeMillis()));
    }

    /** 容量满了先淘汰"最早且最少被使用"的那条：排序在应用层做，端口只提供数据（§7.1 规则 ⑥）。 */
    private void evictIfFull(RuntimePolicy policy) {
        if (experiences.count() < policy.experienceCapacity()) {
            return;
        }
        Experience evicted = null;
        for (Experience experience : experiences.findActive()) {
            if (evicted == null
                    || experience.createdAt() < evicted.createdAt()
                    || (experience.createdAt() == evicted.createdAt()
                        && experience.appliedCount() < evicted.appliedCount())) {
                evicted = experience;
            }
        }
        if (evicted != null) {
            evicted.archive();
            commit(evicted);
        }
    }

    private Experience findDuplicate(ExperienceDraft draft) {
        for (Experience experience : experiences.findActive()) {
            if (experience.applicableScenario().equals(draft.applicableScenario())) {
                return experience;
            }
        }
        return null;
    }

    /** 合并来源：只承载候选教训，使用次数为 0，也不 raise 新建事件。 */
    private static Experience mergeSource(Experience duplicate, ExperienceDraft draft) {
        return new Experience(duplicate.id(), draft.applicableScenario(),
                new LessonLearned(draft.instruction(), draft.expectedOutcome()),
                duplicate.confidence(), duplicate.status(), 0, duplicate.createdAt());
    }

    private void commit(Experience experience) {
        commitScope.stageReplace(experience);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(experience.pullEvents()));
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED, "经验沉淀完成"));
    }
}
