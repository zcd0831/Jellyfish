package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SessionTitleGenerator;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;

import javax.inject.Singleton;

/** 感知：窗口推导、标题、会话开启。规则出处：04-perception §4.5。 */
@Module
public final class PerceptionModule {

    @Provides
    @Singleton
    SystemPromptAssembler systemPromptAssembler() {
        return new SystemPromptAssembler();
    }

    @Provides
    @Singleton
    ContextWindowService contextWindowService(ModelInvocationPort model, PolicyRepository policies,
                                              CommitScope commitScope, DomainEventPublisher publisher,
                                              ProgressPort progress, BudgetService budgetService,
                                              TokenEstimator estimator) {
        return new ContextWindowService(model, policies, commitScope, publisher, progress, budgetService,
                estimator);
    }

    @Provides
    SessionTitleGenerator sessionTitleGenerator(ModelInvocationPort model, BudgetService budgetService) {
        return new SessionTitleGenerator(model, budgetService);
    }

    @Provides
    @Singleton
    ManageSessionUseCase manageSessionUseCase(SessionRepository sessions, ArtifactStore artifacts,
                                              CommitScope commitScope, DomainEventPublisher publisher,
                                              RecordStore records, ContextWindowService contextWindow,
                                              BudgetRepository budgets, PolicyRepository policies) {
        return new ManageSessionUseCase(sessions, artifacts, commitScope, publisher, records, contextWindow,
                budgets, policies);
    }

    @Provides
    AdjustPolicyUseCase adjustPolicyUseCase(PolicyRepository policies, CommitScope commitScope,
                                            DomainEventPublisher publisher) {
        return new AdjustPolicyUseCase(policies, commitScope, publisher);
    }

    @Provides
    SwitchModelUseCase switchModelUseCase(SessionRepository sessions, RecordStore records,
                                         CatalogRepository catalog, CommitScope commitScope,
                                         DomainEventPublisher publisher) {
        return new SwitchModelUseCase(sessions, records, catalog, commitScope, publisher);
    }

    @Provides
    StartSessionUseCase startSessionUseCase(CatalogRepository catalog, SessionRepository sessions,
                                            BudgetRepository budgets, SessionTitleGenerator titleGenerator,
                                            SystemPromptAssembler assembler, CommitScope commitScope,
                                            Clock clock, SessionCancellations cancellations) {
        return new StartSessionUseCase(catalog, sessions, budgets, titleGenerator, assembler, commitScope,
                clock, cancellations);
    }
}
