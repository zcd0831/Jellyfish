package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;

import javax.inject.Singleton;

/** 规划：内循环与外循环。规则出处：模块-规划 §4。 */
@Module
public final class PlanningModule {

    @Provides
    @Singleton
    ReActLoop reActLoop(ModelInvocationPort model, ToolExecutor toolExecutor, BudgetService budgetService,
                        ContextWindowService contextWindow, PolicyRepository policies,
                        CommitScope commitScope, HumanInteractionPort human) {
        return new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies, commitScope, human);
    }

    @Provides
    @Singleton
    ExecutionLoop executionLoop(ContextWindowService contextWindow, ReActLoop reActLoop) {
        return new ExecutionLoop(contextWindow, reActLoop);
    }

    @Provides
    RunSessionUseCase runSessionUseCase(SessionRepository sessions, RecordStore records,
                                        CommitScope commitScope, DomainEventPublisher publisher,
                                        ExecutionLoop executionLoop) {
        return new RunSessionUseCase(sessions, records, commitScope, publisher, executionLoop);
    }
}
