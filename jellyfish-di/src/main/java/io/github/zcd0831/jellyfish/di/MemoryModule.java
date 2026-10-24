package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

import javax.inject.Singleton;

/** 记忆：经验检索 / 反馈 / 沉淀。规则出处：模块-记忆 §4。 */
@Module
public final class MemoryModule {

    @Provides
    @Singleton
    ExperienceRetriever experienceRetriever(ExperienceRepository experiences, ModelInvocationPort model,
                                            CatalogRepository catalog, PolicyRepository policies,
                                            BudgetService budgetService) {
        return new ExperienceRetriever(experiences, model, catalog, policies, budgetService);
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
                                                                RecordStore records, ModelInvocationPort model,
                                                                CatalogRepository catalog,
                                                                PolicyRepository policies,
                                                                BudgetService budgetService,
                                                                CommitScope commitScope,
                                                                DomainEventPublisher publisher,
                                                                ProgressPort progress) {
        return new ExperienceDistillationService(experiences, records, model, catalog, policies, budgetService,
                commitScope, publisher, progress);
    }
}
