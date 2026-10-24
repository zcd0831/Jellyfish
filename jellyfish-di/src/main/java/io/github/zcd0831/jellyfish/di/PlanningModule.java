package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.memory.port.SkillSopProvider;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.EnvironmentFeedbackVerifier;
import io.github.zcd0831.jellyfish.application.planning.GeneratePlanHandler;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SpawnSubagentsHandler;
import io.github.zcd0831.jellyfish.application.planning.LoadSkillHandler;
import io.github.zcd0831.jellyfish.application.planning.RunSessionProvider;
import io.github.zcd0831.jellyfish.application.planning.SubagentLauncher;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;

import java.util.Arrays;
import javax.inject.Provider;
import java.util.concurrent.ExecutorService;

import javax.inject.Singleton;

/** 规划：内循环与外循环。规则出处：模块-规划 §4。 */
@Module
public final class PlanningModule {

    @Provides
    @Singleton
    PlanFactory planFactory() {
        return new PlanFactory();
    }

    @Provides
    @Singleton
    GeneratePlanHandler generatePlanHandler(PlanFactory planFactory, PlanRepository plans,
                                            CatalogRepository catalog, CommitScope commitScope,
                                            DomainEventPublisher publisher) {
        return new GeneratePlanHandler(planFactory, plans, catalog, commitScope, publisher);
    }

    @Provides
    @Singleton
    InternalToolHandlers internalToolHandlers(GeneratePlanHandler generatePlanHandler,
                                              SpawnSubagentsHandler spawnSubagentsHandler,
                                              SkillSopProvider sopProvider) {
        return new InternalToolHandlers(Arrays.asList(
                new InternalToolHandlers.NamedHandler("generate_plan", generatePlanHandler),
                new InternalToolHandlers.NamedHandler("spawn_subagents", spawnSubagentsHandler),
                new InternalToolHandlers.NamedHandler("load_skill", new LoadSkillHandler(sopProvider))));
    }

    @Provides
    @Singleton
    SubagentLauncher subagentLauncher(StartSessionUseCase startSession,
                                      final Provider<RunSessionUseCase> runSession,
                                      SessionRepository sessions, RecordStore records,
                                      BudgetService budgetService, PolicyRepository policies,
                                      ProgressPort progress, ExecutorService pool) {
        RunSessionProvider lazyRunSession = new RunSessionProvider() {
            @Override
            public RunSessionUseCase get() {
                return runSession.get();
            }
        };
        return new SubagentLauncher(startSession, lazyRunSession, sessions, records, budgetService, policies,
                progress, pool);
    }

    @Provides
    @Singleton
    SpawnSubagentsHandler spawnSubagentsHandler(SubagentLauncher launcher) {
        return new SpawnSubagentsHandler(launcher);
    }

    @Provides
    @Singleton
    ReActLoop reActLoop(ModelInvocationPort model, ToolExecutor toolExecutor, BudgetService budgetService,
                        ContextWindowService contextWindow, PolicyRepository policies,
                        CommitScope commitScope, HumanInteractionPort human) {
        return new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies, commitScope, human);
    }

    @Provides
    @Singleton
    EnvironmentFeedbackVerifier environmentFeedbackVerifier() {
        return EnvironmentFeedbackVerifier.NOT_CONFIGURED;
    }

    @Provides
    @Singleton
    VerificationRunner verificationRunner(ModelInvocationPort model, CatalogRepository catalog,
                                          HumanInteractionPort human, BudgetService budgetService,
                                          EnvironmentFeedbackVerifier envFeedback) {
        return new VerificationRunner(model, catalog, human, budgetService, envFeedback);
    }

    @Provides
    @Singleton
    ExecutionLoop executionLoop(ContextWindowService contextWindow, ReActLoop reActLoop,
                                VerificationRunner verificationRunner, PlanRepository plans,
                                PlanFactory planFactory, CatalogRepository catalog, PolicyRepository policies,
                                CommitScope commitScope, DomainEventPublisher publisher, ProgressPort progress,
                                ModelInvocationPort model, BudgetService budgetService,
                                ExperienceRetriever experienceRetriever,
                                ExperienceFeedbackService experienceFeedback,
                                ExperienceDistillationService experienceDistillation,
                                ReflectionRepository reflections, SubagentLauncher subagentLauncher) {
        return new ExecutionLoop(contextWindow, reActLoop, verificationRunner, plans, planFactory, catalog,
                policies, commitScope, publisher, progress, model, budgetService, experienceRetriever,
                experienceFeedback, experienceDistillation, reflections, subagentLauncher);
    }

    @Provides
    RunSessionUseCase runSessionUseCase(SessionRepository sessions, RecordStore records,
                                        CommitScope commitScope, DomainEventPublisher publisher,
                                        ExecutionLoop executionLoop) {
        return new RunSessionUseCase(sessions, records, commitScope, publisher, executionLoop);
    }
}
