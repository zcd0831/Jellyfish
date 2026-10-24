package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.InstanceLock;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.SchemaMigrator;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileArtifactStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FileInstanceLock;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePlanRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileReflectionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileReportRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSchemaMigrator;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.model.HttpJsonClient;
import io.github.zcd0831.jellyfish.infrastructure.model.ModelInvoker;
import io.github.zcd0831.jellyfish.infrastructure.terminal.TerminalProgressReporter;
import io.github.zcd0831.jellyfish.infrastructure.time.SystemClock;
import io.github.zcd0831.jellyfish.infrastructure.tokenizer.CompositeTokenCounter;
import io.github.zcd0831.jellyfish.infrastructure.tokenizer.EstimatorStrategy;
import io.github.zcd0831.jellyfish.infrastructure.tool.HttpToolAdapter;

import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Singleton;

/**
 * 端口 → 适配器。DI 注解只允许出现在本模块（技术决策 D04）。
 */
@Module
public final class InfrastructureModule {

    private final DataDirectory dataDirectory;
    private final PrintStream console;

    public InfrastructureModule(DataDirectory dataDirectory, PrintStream console) {
        this.dataDirectory = dataDirectory;
        this.console = console;
    }

    @Provides
    @Singleton
    DataDirectory dataDirectory() {
        return dataDirectory;
    }

    @Provides
    @Singleton
    JsonCodec jsonCodec() {
        return new JsonCodec();
    }

    @Provides
    @Singleton
    SessionRepository sessionRepository(DataDirectory dir, JsonCodec codec) {
        return new FileSessionRepository(dir, codec);
    }

    @Provides
    @Singleton
    RecordStore recordStore(DataDirectory dir, JsonCodec codec) {
        return new FileRecordStore(dir, codec);
    }

    @Provides
    @Singleton
    PolicyRepository policyRepository(DataDirectory dir, JsonCodec codec) {
        return new FilePolicyRepository(dir, codec);
    }

    @Provides
    @Singleton
    BudgetRepository budgetRepository(DataDirectory dir, JsonCodec codec) {
        return new FileBudgetRepository(dir, codec);
    }

    @Provides
    @Singleton
    CatalogRepository catalogRepository(DataDirectory dir, JsonCodec codec) {
        return new FileCatalogRepository(dir, codec);
    }

    @Provides
    @Singleton
    PlanRepository planRepository(DataDirectory dir, JsonCodec codec) {
        return new FilePlanRepository(dir, codec);
    }

    @Provides
    @Singleton
    VerificationReportRepository verificationReportRepository(DataDirectory dir, JsonCodec codec) {
        return new FileReportRepository(dir, codec);
    }

    @Provides
    @Singleton
    ExperienceRepository experienceRepository(DataDirectory dir, JsonCodec codec) {
        return new FileExperienceRepository(dir, codec);
    }

    @Provides
    @Singleton
    ReflectionRepository reflectionRepository(DataDirectory dir, JsonCodec codec) {
        return new FileReflectionRepository(dir, codec);
    }

    @Provides
    @Singleton
    ArtifactStore artifactStore(DataDirectory dir) {
        return new FileArtifactStore(dir);
    }

    @Provides
    @Singleton
    InstanceLock instanceLock(DataDirectory dir) {
        return new FileInstanceLock(dir);
    }

    @Provides
    @Singleton
    SchemaMigrator schemaMigrator(DataDirectory dir) {
        return new FileSchemaMigrator(dir);
    }

    @Provides
    @Singleton
    Clock clock() {
        return new SystemClock();
    }

    @Provides
    @Singleton
    DomainEventPublisher domainEventPublisher() {
        return new InProcessEventPublisher();
    }

    @Provides
    @Singleton
    ProgressPort progressPort() {
        return new TerminalProgressReporter(console);
    }

    @Provides
    @Singleton
    HumanInteractionPort humanInteractionPort() {
        return new io.github.zcd0831.jellyfish.infrastructure.terminal.TerminalHumanInteraction(System.in, console);
    }

    @Provides
    @Singleton
    TokenCounter tokenCounter() {
        return new CompositeTokenCounter();
    }

    @Provides
    @Singleton
    TokenEstimator tokenEstimator() {
        return new EstimatorStrategy();
    }

    @Provides
    @Singleton
    ExternalToolPort externalToolPort() {
        return new HttpToolAdapter();
    }

    /** 子代理用有界池，不用 ForkJoinPool.commonPool（D05-3）。 */
    @Provides
    @Singleton
    ExecutorService subagentPool() {
        int size = Math.min(4, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(Math.max(1, size));
    }

    @Provides
    @Singleton
    HttpJsonClient httpJsonClient() {
        return new HttpJsonClient();
    }

    @Provides
    @Singleton
    ModelInvocationPort modelInvocationPort(CatalogRepository catalog, PolicyRepository policies,
                                            HttpJsonClient http, ProgressPort progress, TokenCounter counter) {
        return new ModelInvoker(catalog, policies, http, progress, counter);
    }

    /** 每次注入一个新的写入作用域：一个事务一个实例。 */
    @Provides
    CommitScope commitScope(DataDirectory dir, JsonCodec codec) {
        return new FileCommitScope(dir, codec);
    }
}
