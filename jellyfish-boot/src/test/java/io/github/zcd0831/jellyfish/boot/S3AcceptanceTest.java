package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.SessionTitleGenerator;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.planning.EnvironmentFeedbackVerifier;
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.GeneratePlanHandler;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.RunSessionProvider;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SpawnSubagentsHandler;
import io.github.zcd0831.jellyfish.application.planning.SubagentLauncher;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileArtifactStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePlanRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.time.SystemClock;
import io.github.zcd0831.jellyfish.infrastructure.tokenizer.EstimatorStrategy;
import io.github.zcd0831.jellyfish.infrastructure.tool.HttpToolAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 验收（D15）：并行子代理、独立超时、禁止递归、子会话目录与流水、预算归集。
 */
class S3AcceptanceTest {

    @TempDir
    Path tmp;

    private JsonCodec codec;
    private DataDirectory dir;
    private FakeModel model;
    private StartSessionUseCase startSession;
    private RunSessionUseCase runSession;
    private SessionTitleGenerator titleGenerator;
    private FileSessionRepository sessions;
    private FileRecordStore records;
    private FileCatalogRepository catalog;
    private PolicyRepository policies;
    private FileBudgetRepository budgets;
    private BudgetService budgetService;
    private ExecutorService pool;
    private final RunSessionUseCase[] runSessionHolder = new RunSessionUseCase[1];
    private Session main;

    /** 每个用例可换的超时：默认 5 秒。 */
    private int subagentTimeoutMillis = 5000;

    @BeforeEach
    void setUp() throws IOException {
        dir = DataDirectory.of(tmp.toString());
        codec = new JsonCodec();
        Files.createDirectories(tmp.resolve("providers"));
        Files.createDirectories(tmp.resolve("models"));
        write("providers/p1.json", "{\"id\":\"p1\",\"name\":\"fake\",\"apiEndpoint\":\"http://x\","
                + "\"credential\":{\"apiKeyRef\":\"K\"},\"status\":\"ACTIVE\"}");
        write("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"fake\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,\"maxOutputTokens\":4096},"
                + "\"capabilities\":[]}");

        model = new FakeModel();
        sessions = new FileSessionRepository(dir, codec);
        records = new FileRecordStore(dir, codec);
        final FilePolicyRepository filePolicies = new FilePolicyRepository(dir, codec);
        policies = new PolicyRepository() {
            @Override
            public RuntimePolicy load() {
                return policyWithTimeout(subagentTimeoutMillis);
            }

            @Override
            public void save(RuntimePolicy policy) {
                filePolicies.save(policy);
            }
        };
        budgets = new FileBudgetRepository(dir, codec);
        catalog = new FileCatalogRepository(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        budgetService = new BudgetService(budgets, sessions, policies, new FileCommitScope(dir, codec), publisher);
        titleGenerator = new SessionTitleGenerator(model, budgetService);
        ContextWindowService contextWindow = new ContextWindowService(model, policies,
                new FileCommitScope(dir, codec), publisher, event -> { }, budgetService, new EstimatorStrategy());
        PlanRepository plans = new FilePlanRepository(dir, codec);
        PlanFactory planFactory = new PlanFactory();
        FakeHuman human = new FakeHuman();
        ToolExecutor toolExecutor = new ToolExecutor(catalog, new HttpToolAdapter(), new FileArtifactStore(dir),
                human, policies, InternalToolHandlers.empty());
        ReActLoop reActLoop = new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies,
                new FileCommitScope(dir, codec), human);
        VerificationRunner verificationRunner = new VerificationRunner(model, catalog, human, budgetService,
                EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        ExecutionLoop executionLoop = LoopSupport.build(dir, codec, model, policies, catalog, budgetService,
                contextWindow, reActLoop, verificationRunner, plans, publisher, event -> { }, startSession,
                sessions, records, new RunSessionProvider() {
                    @Override
                    public RunSessionUseCase get() {
                        return runSessionHolder[0];
                    }
                });
        runSession = new RunSessionUseCase(sessions, records, new FileCommitScope(dir, codec), publisher,
                executionLoop);
        runSessionHolder[0] = runSession;
        startSession = new StartSessionUseCase(catalog, sessions, budgets, titleGenerator,
                new SystemPromptAssembler(), new FileCommitScope(dir, codec), new SystemClock());

        pool = Executors.newFixedThreadPool(3);
        final RunSessionUseCase run = runSession;
        SubagentLauncher launcher = new SubagentLauncher(startSession, new RunSessionProvider() {
            @Override
            public RunSessionUseCase get() {
                return run;
            }
        }, sessions, records, budgetService, policies, event -> { }, pool);

        // 主会话可用的工具：spawn_subagents + 计划工具都不需要，这里只测 spawn
        ToolExecutor spawnExecutor = new ToolExecutor(catalog, new HttpToolAdapter(),
                new FileArtifactStore(dir), human, policies,
                new InternalToolHandlers(Collections.singletonList(new InternalToolHandlers.NamedHandler(
                        "spawn_subagents", new SpawnSubagentsHandler(launcher)))));
        toolExecutorHolder = spawnExecutor;

        main = Session.create(SessionId.of("main"), null,
                io.github.zcd0831.jellyfish.domain.shared.AgentId.of("builtin.main"), ModelId.of("m1"),
                1L, null);
        sessions.save(main);
    }

    private ToolExecutor toolExecutorHolder;

    @Test
    void 并行子代理各自完成且落sub目录() {
        Map<String, Object> args = spawnArgs(
                request("任务A"), request("任务B"), request("任务C"));

        ToolExecOutcome outcome = toolExecutorHolder.execute(main, "spawn_subagents", args,
                io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1"));
        Path sd = tmp.resolve("sessions").resolve("main").resolve("sub");
        assertTrue(outcome.content().contains("结论A"), outcome.content());
        assertTrue(outcome.content().contains("结论B"), outcome.content());
        assertTrue(outcome.content().contains("结论C"), outcome.content());
        Path sub = tmp.resolve("sessions").resolve("main").resolve("sub");
        assertTrue(Files.isDirectory(sub), "子会话应落在 sub/ 下");
        assertEquals(3, sub.toFile().listFiles().length);
    }

    @Test
    void 一个子代理超时不影响其它() {
        subagentTimeoutMillis = 1200;
        Map<String, Object> args = spawnArgs(request("任务A"), request("慢任务"));

        ToolExecOutcome outcome = toolExecutorHolder.execute(main, "spawn_subagents", args,
                io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1"));
        Path sd = tmp.resolve("sessions").resolve("main").resolve("sub");
        java.io.File[] kids = sd.toFile().listFiles();
        StringBuilder dbg = new StringBuilder("DEBUG content=" + outcome.content() + " | ");
        if (kids != null) {
            for (java.io.File kid : kids) {
                dbg.append(kid.getName()).append(":meta=").append(new java.io.File(kid, "meta.json").exists())
                        .append(",load=").append(sessions.load(SessionId.of(kid.getName())) != null).append("; ");
            }
        }
        System.out.println(dbg);

        assertTrue(outcome.content().contains("结论A"), outcome.content());
        assertTrue(outcome.content().contains("超时未完成"), outcome.content());
    }

    @Test
    void 子代理不得再开子代理() {
        Session child = Session.create(SessionId.of("child"), SessionId.of("main"),
                io.github.zcd0831.jellyfish.domain.shared.AgentId.of("builtin.main"), ModelId.of("m1"),
                1L, null);

        ToolExecOutcome outcome = toolExecutorHolder.execute(child, "spawn_subagents",
                spawnArgs(request("任务A")), io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r2"));

        assertTrue(outcome.content().contains("不得再开子代理"), outcome.content());
    }

    private static Map<String, Object> request(String task) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task", task);
        return item;
    }

    private static Map<String, Object> spawnArgs(Map<String, Object>... requests) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("subagents", Arrays.asList(requests));
        return args;
    }

    private static RuntimePolicy policyWithTimeout(int timeoutMillis) {
        RuntimePolicy d = RuntimePolicy.defaults();
        return new RuntimePolicy(d.maxRetries(), d.reflectionThreshold(), d.maxReplanGeneration(),
                d.maxReActRounds(), d.summarizationTokenThreshold(), d.inputSafetyMargin(),
                d.contextWindowRecordCount(), d.systemRecordRatioLimit(), d.toolResultTruncateLength(),
                timeoutMillis, d.experienceCapacity(), d.experienceTopN(), d.experiencePerPlan(),
                d.experienceInitialConfidence(), d.experienceConfidenceStep(), d.experienceRetireThreshold(),
                d.budgetWarnRatio(), d.budgetLimitTokens(), d.modelFallbackChain(), d.trustedToolIds());
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 子任务里带"慢"字就睡 3 秒，其余按任务名回结论。 */
    private static final class FakeModel implements ModelInvocationPort {

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            String task = request.messages().isEmpty() ? "" : String.valueOf(request.messages().get(0).content());
            if (task.contains("慢")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("被取消");
                }
            }
            String name = task.replaceAll(".*任务([A-C]).*", "$1");
            String text = task.contains("慢") ? "慢任务的结论" : ("结论" + (name.isEmpty() ? "" : name));
            return new ModelInvocationResult(text, Collections.<ToolCall>emptyList(), new ModelUsage(4, 2),
                    ModelId.of("m1"));
        }

        @Override
        public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
            return invoke(request);
        }

        @Override
        public ModelProfile describe(ModelId modelId) {
            return new ModelProfile(8000, 4096);
        }
    }

    private static final class FakeHuman implements HumanInteractionPort {

        @Override
        public Answer ask(Question question) {
            return Answer.answered("同意");
        }
    }
}
