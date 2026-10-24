package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService;
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

    static ExecutionLoop build(final DataDirectory dir, JsonCodec codec, ModelInvocationPort model,
                               PolicyRepository policies, CatalogRepository catalog,
                               BudgetService budgetService, ContextWindowService contextWindow,
                               ReActLoop reActLoop, VerificationRunner verificationRunner, PlanRepository plans,
                               DomainEventPublisher publisher, ProgressPort progress,
                               StartSessionUseCase startSession, SessionRepository sessions,
                               RecordStore records, RunSessionProvider runSession) {
        ExperienceRepository experiences = new FileExperienceRepository(dir, codec);
        ReflectionRepository reflections = new FileReflectionRepository(dir, codec);
        ExperienceRetriever retriever = new ExperienceRetriever(experiences, model, catalog, policies,
                budgetService);
        ExperienceFeedbackService feedback = new ExperienceFeedbackService(experiences, policies,
                new FileCommitScope(dir, codec), publisher);
        ExperienceDistillationService distillation = new ExperienceDistillationService(experiences, records,
                model, catalog, policies, budgetService, new FileCommitScope(dir, codec), publisher, progress);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        SubagentLauncher launcher = new SubagentLauncher(startSession, runSession, sessions, records,
                budgetService, policies, progress, pool);
        return new ExecutionLoop(contextWindow, reActLoop, verificationRunner, plans, new PlanFactory(), catalog,
                policies, new FileCommitScope(dir, codec), publisher, progress, model, budgetService, retriever,
                feedback, distillation, reflections, launcher);
    }
}
