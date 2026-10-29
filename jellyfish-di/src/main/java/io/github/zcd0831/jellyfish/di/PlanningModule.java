package io.github.zcd0831.jellyfish.di;

import java.util.List;
import java.util.ArrayList;
import io.github.zcd0831.jellyfish.infrastructure.local.Workspace;
import io.github.zcd0831.jellyfish.infrastructure.local.UrlFetcher;
import io.github.zcd0831.jellyfish.infrastructure.local.LocalToolHandlers;
import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.PortRoutes;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
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
import io.github.zcd0831.jellyfish.application.action.ToolInvoker;
import io.github.zcd0831.jellyfish.application.planning.SessionRunner;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.EnvironmentFeedbackVerifier;
import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.planning.GeneratePlanHandler;
import io.github.zcd0831.jellyfish.application.planning.ReplanPlanHandler;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.planning.SpawnSubagentsHandler;
import io.github.zcd0831.jellyfish.application.planning.LoadSkillHandler;
import io.github.zcd0831.jellyfish.application.planning.RunSessionProvider;
import io.github.zcd0831.jellyfish.application.planning.SubagentLauncher;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
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

/** 规划：内循环与外循环。规则出处：06-planning §6.5。 */
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
    io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner internalSessionRunner(
            io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase startSession,
            io.github.zcd0831.jellyfish.application.perception.port.SessionRepository sessions,
            ReActLoop reActLoop, SessionToolScope scope, CommitScope commitScope, Clock clock) {
        return new io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner(
                startSession, sessions, reActLoop, scope, commitScope, clock);
    }

    @Provides
    @Singleton
    ReplanPlanHandler replanPlanHandler(PlanFactory planFactory, PlanRepository plans,
                                        CatalogRepository catalog, CommitScope commitScope) {
        return new ReplanPlanHandler(planFactory, plans, catalog, commitScope);
    }

    @Provides
    @Singleton
    InternalToolHandlers internalToolHandlers(GeneratePlanHandler generatePlanHandler,
                                              ReplanPlanHandler replanPlanHandler,
                                              SpawnSubagentsHandler spawnSubagentsHandler,
                                              SkillSopProvider sopProvider, Workspace workspace,
                                              UrlFetcher urlFetcher) {
        List<InternalToolHandlers.NamedHandler> handlers = new ArrayList<InternalToolHandlers.NamedHandler>(Arrays.asList(
                new InternalToolHandlers.NamedHandler("generate_plan", generatePlanHandler),
                new InternalToolHandlers.NamedHandler("replan_plan", replanPlanHandler),
                new InternalToolHandlers.NamedHandler("spawn_subagents", spawnSubagentsHandler),
                new InternalToolHandlers.NamedHandler("load_skill", new LoadSkillHandler(sopProvider))));
        handlers.addAll(LocalToolHandlers.all(workspace, urlFetcher));
        return new InternalToolHandlers(handlers);
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
                                PlanCompletionCurator planCompletionCurator,
                                ReflectionRepository reflections, ExperienceRepository experiences,
                                SessionToolScope scope,
                                io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks) {
        return new ExecutionLoop(contextWindow, reActLoop, verificationRunner, plans, planFactory, catalog,
                policies, commitScope, publisher, progress, model, budgetService, experienceRetriever,
                experienceFeedback, planCompletionCurator, reflections, experiences, scope, forks);
    }

    /** 零插件时就是默认编排；插件装上后由路由接管。 */
    @Provides
    @Singleton
    SessionRunner sessionRunner(ExecutionLoop executionLoop, PortRoutes routes) {
        return routes.resolve("SessionRunner", (SessionRunner) executionLoop);
    }

    /** 在跑会话的终止信号登记表：全进程一份（08-application §8.9.4.1 的两张进程内表）。 */
    @Provides
    @Singleton
    SessionCancellations sessionCancellations() {
        return new SessionCancellations();
    }

    /** 基础设施（传输层、本地工具）只认端口，不认应用层的具体表。 */
    @Provides
    @Singleton
    CancellationRegistry cancellationRegistry(SessionCancellations cancellations) {
        return cancellations;
    }

    @Provides
    RunSessionUseCase runSessionUseCase(SessionRepository sessions, RecordStore records,
                                        CommitScope commitScope, DomainEventPublisher publisher,
                                        SessionRunner runner, ModelInvocationPort model, ToolInvoker tools,
                                        HumanInteractionPort human, ProgressPort progress,
                                        PlanRepository plans, ContextWindowService contextWindow,
                                        SessionCancellations cancellations) {
        return new RunSessionUseCase(sessions, records, commitScope, publisher, runner, model, tools, human,
                progress, plans, contextWindow, cancellations);
    }
}
