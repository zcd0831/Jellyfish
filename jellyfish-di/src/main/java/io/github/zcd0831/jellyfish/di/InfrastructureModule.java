package io.github.zcd0831.jellyfish.di;

import io.github.zcd0831.jellyfish.infrastructure.local.Workspace;
import io.github.zcd0831.jellyfish.infrastructure.local.UrlFetcher;
import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.action.PortRoutes;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.memory.port.SkillSopProvider;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
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
import io.github.zcd0831.jellyfish.infrastructure.file.FilePluginRepository;
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
import io.github.zcd0831.jellyfish.infrastructure.file.FileSkillSopProvider;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.model.HttpJsonClient;
import io.github.zcd0831.jellyfish.infrastructure.model.ModelInvoker;
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

    public InfrastructureModule(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
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
    FileCatalogRepository catalogRepository(DataDirectory dir, JsonCodec codec, PluginRepository plugins,
                                            PluginRuntimePort pluginRuntime) {
        return new FileCatalogRepository(dir, codec, plugins, pluginRuntime);
    }

    @Provides
    @Singleton
    PluginRepository pluginRepository(DataDirectory dir, JsonCodec codec) {
        return new FilePluginRepository(dir, codec);
    }

    @Provides
    @Singleton
    CatalogRepository catalogAlias(FileCatalogRepository catalog) {
        return catalog;
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
    SkillSopProvider skillSopProvider(FileCatalogRepository catalog) {
        return new FileSkillSopProvider(catalog);
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

    /** 工作区根 = 启动目录，启动求一次（模块-行动 §7.1 规则 9）。 */
    @Provides
    @Singleton
    Workspace workspace() {
        return Workspace.atLaunchDirectory();
    }

    @Provides
    @Singleton
    UrlFetcher urlFetcher() {
        return new UrlFetcher();
    }

    @Provides
    @Singleton
    Clock clock(PortRoutes routes) {
        return routes.resolve("Clock", (Clock) new SystemClock());
    }

    @Provides
    @Singleton
    DomainEventPublisher domainEventPublisher() {
        return new InProcessEventPublisher();
    }

    @Provides
    @Singleton
    TokenCounter tokenCounter(PortRoutes routes) {
        return routes.resolve("TokenCounter", (TokenCounter) new CompositeTokenCounter());
    }

    @Provides
    @Singleton
    TokenEstimator tokenEstimator() {
        return new EstimatorStrategy();
    }

    @Provides
    @Singleton
    ExternalToolPort externalToolPort(PortRoutes routes) {
        return routes.resolve("ExternalToolPort", (ExternalToolPort) new HttpToolAdapter());
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
                                            HttpJsonClient http, ProgressPort progress, TokenCounter counter,
                                            PortRoutes routes) {
        return routes.resolve("ModelInvocationPort",
                (ModelInvocationPort) new ModelInvoker(catalog, policies, http, progress, counter));
    }

    /** 每次注入一个新的写入作用域：一个事务一个实例。 */
    @Provides
    CommitScope commitScope(DataDirectory dir, JsonCodec codec) {
        return new FileCommitScope(dir, codec);
    }
}
