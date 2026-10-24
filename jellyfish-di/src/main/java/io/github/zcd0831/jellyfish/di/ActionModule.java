package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;

import javax.inject.Singleton;

/** 行动：工具执行与记账。规则出处：模块-行动 §4。 */
@Module
public final class ActionModule {

    @Provides
    @Singleton
    BudgetService budgetService(BudgetRepository budgets, SessionRepository sessions,
                                PolicyRepository policies, CommitScope commitScope,
                                DomainEventPublisher publisher) {
        return new BudgetService(budgets, sessions, policies, commitScope, publisher);
    }

    @Provides
    @Singleton
    ToolExecutor toolExecutor(CatalogRepository catalog, ExternalToolPort externalTools,
                              ArtifactStore artifacts, HumanInteractionPort human, PolicyRepository policies) {
        return new ToolExecutor(catalog, externalTools, artifacts, human, policies);
    }
}
