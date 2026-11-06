package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.SkillDistillationService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.RunSessionProvider;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SubagentLauncher;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileReflectionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 测试用的外循环装配：把 S4 的记忆协作一并接好，避免每个用例重复 17 个参数。 */
final class LoopSupport {

    private LoopSupport() {
    }

    /** 端到端测试不装配裁判 / 检索子会话：给一个“没有执行点”的提供者，按无法判定 / 空分配处理。 */
    static io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider noForks() {
        return new io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider() {
            @Override
            public io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner get() {
                return null;
            }
        };
    }

    static io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider forksOf(
            final io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner runner) {
        return new io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider() {
            @Override
            public io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner get() {
                return runner;
            }
        };
    }

    /**
     * 计划处理器用的经验分配器：端到端用例的数据目录里通常没有经验，
     * {@code retrieveAndAssign} 会直接返回空分配，不会去 fork 任何子会话。
     */
    static io.github.zcd0831.jellyfish.application.planning.ExperienceAssigner assigner(
            DataDirectory dir, JsonCodec codec, CatalogRepository catalog, PolicyRepository policies,
            ModelInvocationPort model) {
        return assigner(dir, codec, catalog, policies, model, noForks());
    }

    /** 需要真的让检索代理跑起来（分配经验）的用例走这条：执行点由调用方给。 */
    static io.github.zcd0831.jellyfish.application.planning.ExperienceAssigner assigner(
            DataDirectory dir, JsonCodec codec, CatalogRepository catalog, PolicyRepository policies,
            ModelInvocationPort model,
            io.github.zcd0831.jellyfish.application.perception.ForkRunnerProvider forks) {
        return new io.github.zcd0831.jellyfish.application.planning.ExperienceAssigner(
                new ExperienceRetriever(new FileExperienceRepository(dir, codec), catalog, policies,
                        text -> text == null ? 0 : text.length(), forks, model));
    }

    static ExecutionLoop build(final DataDirectory dir, JsonCodec codec, ModelInvocationPort model,
                               PolicyRepository policies, CatalogRepository catalog,
                               BudgetService budgetService, ContextWindowService contextWindow,
                               ReActLoop reActLoop, VerificationRunner verificationRunner, PlanRepository plans,
                               DomainEventPublisher publisher, ProgressPort progress,
                               StartSessionUseCase startSession, SessionRepository sessions,
                               RecordStore records,
                               io.github.zcd0831.jellyfish.application.planning.SessionCancellations cancellations,
                               RunSessionProvider runSession) {
        ExperienceRepository experiences = new FileExperienceRepository(dir, codec);
        ReflectionRepository reflections = new FileReflectionRepository(dir, codec);
        io.github.zcd0831.jellyfish.application.action.SessionToolScope scope =
                new io.github.zcd0831.jellyfish.application.action.SessionToolScope();
        final io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks =
                new io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner(startSession, sessions,
                        reActLoop, scope, new FileCommitScope(dir, codec),
                        () -> System.currentTimeMillis(), cancellations, budgetService);
        ExperienceFeedbackService feedback = new ExperienceFeedbackService(experiences, policies,
                new FileCommitScope(dir, codec), publisher);
        ExperienceDistillationService distillation = new ExperienceDistillationService(experiences, policies,
                new FileCommitScope(dir, codec), publisher, progress);
        SkillDistillationService skills = new SkillDistillationService(catalog, policies,
                new FileCommitScope(dir, codec), publisher, progress);
        PlanCompletionCurator curator = new PlanCompletionCurator(catalog, distillation, skills, forks,
                new FileCommitScope(dir, codec));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        SubagentLauncher launcher = new SubagentLauncher(startSession, runSession, sessions, records,
                budgetService, policies, progress, pool, cancellations);
        return new ExecutionLoop(reActLoop, verificationRunner, plans, new PlanFactory(), catalog,
                policies, new FileCommitScope(dir, codec), publisher, progress, cancellations,
                feedback, curator, reflections, experiences, scope, forks);
    }
}
