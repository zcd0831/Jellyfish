package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 成功与失败作用于同一份"本次分配的经验"：成功奖励、失败惩罚（宁可错杀）。
 * 规则出处：模块-记忆 §4、§7.1 规则 ⑦⑧、§8.3。
 */
public final class ExperienceFeedbackService {

    private final ExperienceRepository experiences;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;

    public ExperienceFeedbackService(ExperienceRepository experiences, PolicyRepository policies,
                                     CommitScope commitScope, DomainEventPublisher eventPublisher) {
        this.experiences = experiences;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
    }

    public void onStepSucceeded(FeedbackInput input) {
        RuntimePolicy policy = policies.load();
        for (ExperienceId id : distinct(input.experienceIds())) {
            Experience experience = find(id);
            if (experience == null) {
                continue;
            }
            experience.reinforce(policy.experienceConfidenceStep());
            commit(experience);
        }
    }

    public void onStepFailed(FeedbackInput input) {
        RuntimePolicy policy = policies.load();
        for (ExperienceId id : distinct(input.experienceIds())) {
            Experience experience = find(id);
            if (experience == null) {
                continue;
            }
            experience.penalize(policy.experienceConfidenceStep(), policy.experienceRetireThreshold());
            commit(experience);
        }
    }

    private void commit(Experience experience) {
        commitScope.stageReplace(experience);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(experience.pullEvents()));
    }

    private Experience find(ExperienceId id) {
        for (Experience experience : experiences.findActive()) {
            if (experience.id().equals(id)) {
                return experience;
            }
        }
        return null;
    }

    private static Set<ExperienceId> distinct(List<ExperienceId> ids) {
        return new LinkedHashSet<ExperienceId>(ids);
    }
}
