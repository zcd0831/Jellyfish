package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.SkillDistillationService;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

import javax.inject.Singleton;

/** 记忆：经验检索 / 反馈 / 沉淀。规则出处：07-memory §7.5。 */
@Module
public final class MemoryModule {

    @Provides
    @Singleton
    ExperienceRetriever experienceRetriever(ExperienceRepository experiences, ModelInvocationPort model,
                                            CatalogRepository catalog, PolicyRepository policies,
                                            BudgetService budgetService, TokenEstimator estimator) {
        return new ExperienceRetriever(experiences, model, catalog, policies, budgetService, estimator);
    }

    @Provides
    @Singleton
    ExperienceFeedbackService experienceFeedbackService(ExperienceRepository experiences,
                                                        PolicyRepository policies, CommitScope commitScope,
                                                        DomainEventPublisher publisher) {
        return new ExperienceFeedbackService(experiences, policies, commitScope, publisher);
    }

    @Provides
    @Singleton
    ExperienceDistillationService experienceDistillationService(ExperienceRepository experiences,
                                                                PolicyRepository policies,
                                                                CommitScope commitScope,
                                                                DomainEventPublisher publisher,
                                                                ProgressPort progress) {
        return new ExperienceDistillationService(experiences, policies, commitScope, publisher, progress);
    }

    @Provides
    @Singleton
    SkillDistillationService skillDistillationService(CatalogRepository catalog, PolicyRepository policies,
                                                      CommitScope commitScope, DomainEventPublisher publisher,
                                                      ProgressPort progress) {
        return new SkillDistillationService(catalog, policies, commitScope, publisher, progress);
    }

    @Provides
    @Singleton
    PlanCompletionCurator planCompletionCurator(RecordStore records, ModelInvocationPort model,
                                                CatalogRepository catalog, PolicyRepository policies,
                                                BudgetService budgetService, CommitScope commitScope,
                                                io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks,
                                                ExperienceDistillationService experienceDistillation,
                                                SkillDistillationService skillDistillation) {
        return new PlanCompletionCurator(records, model, catalog, policies, budgetService,
                experienceDistillation, skillDistillation, forks, commitScope);
    }
}
