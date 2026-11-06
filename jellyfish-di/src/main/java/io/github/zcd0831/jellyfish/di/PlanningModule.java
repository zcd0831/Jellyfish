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
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoopDeps;
import io.github.zcd0831.jellyfish.application.action.ToolInvoker;
import io.github.zcd0831.jellyfish.application.planning.SessionRunner;
import io.github.zcd0831.jellyfish.application.planning.ExperienceAssigner;
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
    io.github.zcd0831.jellyfish.application.shared.StartupReconciler startupReconciler(
            SessionRepository sessions, PlanRepository plans,
            io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository reports,
            RecordStore records,
            io.github.zcd0831.jellyfish.application.action.port.BudgetRepository budgets,
            io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository reflections,
            CommitScope commitScope, DomainEventPublisher eventPublisher, ProgressPort progress) {
        return new io.github.zcd0831.jellyfish.application.shared.StartupReconciler(sessions, plans, reports,
                records, budgets, reflections, commitScope, eventPublisher, progress);
    }

    @Provides
    @Singleton
    PlanFactory planFactory() {
        return new PlanFactory();
    }

    @Provides
    @Singleton
    GeneratePlanHandler generatePlanHandler(PlanFactory planFactory, PlanRepository plans,
                                            CatalogRepository catalog, CommitScope commitScope,
                                            DomainEventPublisher publisher,
                                            ExperienceAssigner experienceAssigner) {
        return new GeneratePlanHandler(planFactory, plans, catalog, commitScope, publisher, experienceAssigner);
    }

    /** 经验分配只有一条实现：两个计划处理器共用（与计划落盘同一次提交）。 */
    @Provides
    @Singleton
    ExperienceAssigner experienceAssigner(ExperienceRetriever experienceRetriever) {
        return new ExperienceAssigner(experienceRetriever);
    }

    @Provides
    @Singleton
    io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner internalSessionRunner(
            io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase startSession,
            io.github.zcd0831.jellyfish.application.perception.port.SessionRepository sessions,
            ReActLoop reActLoop, SessionToolScope scope, CommitScope commitScope, Clock clock,
            SessionCancellations cancellations,
            io.github.zcd0831.jellyfish.application.action.BudgetService budgetService) {
        return new io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner(
                startSession, sessions, reActLoop, scope, commitScope, clock, cancellations, budgetService);
    }

    @Provides
    @Singleton
    ReplanPlanHandler replanPlanHandler(PlanFactory planFactory, PlanRepository plans,
                                        CatalogRepository catalog, CommitScope commitScope,
                                        PolicyRepository policies, ExperienceAssigner experienceAssigner) {
        return new ReplanPlanHandler(planFactory, plans, catalog, commitScope, policies, experienceAssigner);
    }

    @Provides
    @Singleton
    InternalToolHandlers internalToolHandlers(GeneratePlanHandler generatePlanHandler,
                                              ReplanPlanHandler replanPlanHandler,
                                              SpawnSubagentsHandler spawnSubagentsHandler,
                                              SkillSopProvider sopProvider, Workspace workspace,
                                              UrlFetcher urlFetcher, SessionToolScope scope) {
        List<InternalToolHandlers.NamedHandler> handlers = new ArrayList<InternalToolHandlers.NamedHandler>(Arrays.asList(
                new InternalToolHandlers.NamedHandler("generate_plan", generatePlanHandler),
                new InternalToolHandlers.NamedHandler("replan_plan", replanPlanHandler),
                new InternalToolHandlers.NamedHandler("spawn_subagents", spawnSubagentsHandler),
                new InternalToolHandlers.NamedHandler("load_skill", new LoadSkillHandler(sopProvider))));
        handlers.addAll(LocalToolHandlers.all(workspace, urlFetcher, scope));
        return new InternalToolHandlers(handlers);
    }

    @Provides
    @Singleton
    SubagentLauncher subagentLauncher(StartSessionUseCase startSession,
                                      final Provider<RunSessionUseCase> runSession,
                                      SessionRepository sessions, RecordStore records,
                                      BudgetService budgetService, PolicyRepository policies,
                                      ProgressPort progress, ExecutorService pool,
                                      SessionCancellations cancellations) {
        RunSessionProvider lazyRunSession = new RunSessionProvider() {
            @Override
            public RunSessionUseCase get() {
                return runSession.get();
            }
        };
        return new SubagentLauncher(startSession, lazyRunSession, sessions, records, budgetService, policies,
                progress, pool, cancellations);
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
        return new ReActLoop(model, toolExecutor, contextWindow, policies, commitScope, human);
    }

    @Provides
    @Singleton
    EnvironmentFeedbackVerifier environmentFeedbackVerifier() {
        return EnvironmentFeedbackVerifier.NOT_CONFIGURED;
    }

    @Provides
    @Singleton
    VerificationRunner verificationRunner(
            javax.inject.Provider<io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner> forks,
            HumanInteractionPort human, EnvironmentFeedbackVerifier envFeedback) {
        return new VerificationRunner(lazyForkRunner(forks), human, envFeedback);
    }

    /** 延迟取：裁判 → fork → 内循环 → 工具 → 计划工具 → 检索 是构造环，只能晚一步取。 */
    private static io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider lazyForkRunner(
            final javax.inject.Provider<io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner> forks) {
        return new io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider() {
            @Override
            public io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner get() {
                return forks.get();
            }
        };
    }

    @Provides
    @Singleton
    ExecutionLoop executionLoop(ReActLoop reActLoop,
                                VerificationRunner verificationRunner, PlanRepository plans,
                                PlanFactory planFactory, CatalogRepository catalog, PolicyRepository policies,
                                CommitScope commitScope, DomainEventPublisher publisher, ProgressPort progress,
                                ExperienceFeedbackService experienceFeedback,
                                PlanCompletionCurator planCompletionCurator,
                                ReflectionRepository reflections, ExperienceRepository experiences,
                                SessionToolScope scope,
                                CancellationRegistry cancellationRegistry,
                                io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks) {
        return ExecutionLoopDeps.builder()
                .reActLoop(reActLoop).verificationRunner(verificationRunner)
                .plans(plans).planFactory(planFactory).catalog(catalog).policies(policies)
                .commitScope(commitScope).eventPublisher(publisher).progress(progress)
                .cancellations(cancellationRegistry)
                .experienceFeedback(experienceFeedback).planCompletionCurator(planCompletionCurator)
                .reflections(reflections).experiences(experiences)
                .scope(scope).forks(forks)
                .build();
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
                                        SessionCancellations cancellations,
                                        io.github.zcd0831.jellyfish.application.shared.StartupReconciler reconciler) {
        return new RunSessionUseCase(sessions, records, commitScope, publisher, runner, model, tools, human,
                progress, plans, contextWindow, cancellations, reconciler);
    }
}
