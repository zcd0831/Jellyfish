package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
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
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 验收：并行子代理、独立超时、禁止递归、子会话目录与流水、预算归集。
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

        sessions = new FileSessionRepository(dir, codec);
        records = new FileRecordStore(dir, codec);
        final FilePolicyRepository filePolicies = new FilePolicyRepository(dir, codec);
        cancellations = new SessionCancellations();
        model = new FakeModel();
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
        catalog = FileCatalogRepository.standalone(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        budgetService = new BudgetService(budgets, sessions, policies, new FileCommitScope(dir, codec), publisher, event -> { });
        titleGenerator = new SessionTitleGenerator(model, budgetService);
        ContextWindowService contextWindow = new ContextWindowService(model, policies,
                new FileCommitScope(dir, codec), publisher, event -> { }, budgetService, new EstimatorStrategy());
        PlanRepository plans = new FilePlanRepository(dir, codec);
        PlanFactory planFactory = new PlanFactory();
        FakeHuman human = new FakeHuman();
        ToolExecutor toolExecutor = new ToolExecutor(catalog, new HttpToolAdapter(), new FileArtifactStore(dir),
                human, policies, InternalToolHandlers.empty(), event -> { }, new SessionToolScope());
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
                executionLoop, model, toolExecutor, human, event -> { }, plans, contextWindow,
                cancellations);
        runSessionHolder[0] = runSession;
        startSession = new StartSessionUseCase(catalog, sessions, budgets, titleGenerator,
                new SystemPromptAssembler(), new FileCommitScope(dir, codec), new SystemClock(),
                cancellations);

        pool = Executors.newFixedThreadPool(3);
        final RunSessionUseCase run = runSession;
        SubagentLauncher launcher = new SubagentLauncher(startSession, new RunSessionProvider() {
            @Override
            public RunSessionUseCase get() {
                return run;
            }
        }, sessions, records, budgetService, policies, event -> { }, pool, cancellations);

        // 主会话可用的工具：spawn_subagents + 计划工具都不需要，这里只测 spawn
        ToolExecutor spawnExecutor = new ToolExecutor(catalog, new HttpToolAdapter(),
                new FileArtifactStore(dir), human, policies,
                new InternalToolHandlers(Collections.singletonList(new InternalToolHandlers.NamedHandler(
                        "spawn_subagents", new SpawnSubagentsHandler(launcher)))), event -> { }, new SessionToolScope());
        toolExecutorHolder = spawnExecutor;

        main = Session.create(SessionId.of("main"), null,
                io.github.zcd0831.jellyfish.domain.shared.AgentId.of("builtin.main"), ModelId.of("m1"),
                1L, null);
        sessions.save(main);
    }

    private ToolExecutor toolExecutorHolder;
    private SessionCancellations cancellations;
    /** "慢任务"阻塞多久：负向验证靠它把「终止之后会不会又写一笔」暴露出来。 */
    private long slowMillis = 3000;

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
        assertTrue(outcome.content().contains("结论A"), outcome.content());
        assertTrue(outcome.content().contains("已终止（超时）"), outcome.content());
    }

    @Test
    void 超时终止后子代理不再落流水() throws Exception {
        subagentTimeoutMillis = 400;
        slowMillis = 1200;
        String content = spawn("慢任务");
        assertTrue(content.contains("已终止（超时）"), content);

        Path subDir = tmp.resolve("sessions").resolve("main").resolve("sub");
        int before = countFiles(subDir);
        // 等到"没有被终止时它一定会写出东西"的那一刻之后再看
        Thread.sleep(1500);
        int after = countFiles(subDir);

        assertEquals(before, after, "被判超时之后，子代理不得再往盘上写任何流水");
    }

    private static int countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int count = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            count = (int) walk.filter(Files::isRegularFile).count();
        }
        return count;
    }

    @Test
    void 并行超时按各自开始计时而不是排队累加() {
        subagentTimeoutMillis = 800;
        long started = System.currentTimeMillis();

        spawn("慢任务", "慢任务", "慢任务");

        long elapsed = System.currentTimeMillis() - started;
        // 顺序等待会是 3×800ms；从各自实际开始计时则应 ≈ 800ms + 抖动
        assertTrue(elapsed < 2000, "并行超时不得按提交顺序累加，实际 " + elapsed + "ms");
    }

    @Test
    void 终止父会话时在跑的子代理一起收手() throws Exception {
        subagentTimeoutMillis = 30000;
        cancellations.register(main.id(), null);
        final java.util.concurrent.atomic.AtomicReference<String> spawned =
                new java.util.concurrent.atomic.AtomicReference<String>();
        Thread spawner = new Thread(new Runnable() {
            @Override
            public void run() {
                spawned.set(spawn("慢任务"));
            }
        }, "spawner");
        spawner.start();
        Thread.sleep(400);   // 让子代理起来并挂到父会话名下
        int before = countFiles(tmp.resolve("sessions").resolve("main").resolve("sub"));

        cancellations.cancel(main.id());   // 等价于按一次 Esc
        spawner.join(3000);
        Thread.sleep(400);

        assertFalse(spawner.isAlive(), "父会话被终止后，spawn 必须马上返回");
        assertTrue(spawned.get() != null && spawned.get().contains("已终止"),
                "级联终止的子代理要如实说「已终止」，不能报创建失败：" + spawned.get());
        assertEquals(before, countFiles(tmp.resolve("sessions").resolve("main").resolve("sub")),
                "级联终止后子代理不得再落流水");
    }

    private String spawn(String... tasks) {
        List<Map<String, Object>> requests = new java.util.ArrayList<Map<String, Object>>();
        for (String task : tasks) {
            requests.add(request(task));
        }
        return toolExecutorHolder.execute(main, "spawn_subagents", spawnArgs(requests.toArray(new Map[0])),
                io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r1")).content();
    }

    private static String firstSessionId(String json) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"sessionId\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
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
                d.collapseRetainRecordCount(), d.toolResultTruncateLength(),
                timeoutMillis, d.experienceCapacity(), d.experienceTopN(), d.experiencePerPlan(),
                d.experienceInitialConfidence(), d.experienceConfidenceStep(), d.experienceRetireThreshold(),
                d.skillCapacity(), d.budgetWarnRatio(), d.budgetLimitTokens(), d.modelFallbackChain(),
                d.trustedToolIds());
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 子任务里带"慢"字就阻塞 3 秒，其余按任务名回结论。
     * <strong>阻塞期间只认终止令牌</strong>（不看线程中断）：这样测试才能证明终止靠的是令牌，
     * 而不是靠 future.cancel(true) 撞大运（08-application §8.9.4.1）。
     */
    private final class FakeModel implements ModelInvocationPort {

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            String task = request.messages().isEmpty() ? "" : String.valueOf(request.messages().get(0).content());
            if (task.contains("慢")) {
                CancellationToken token = cancellations.forSession(request.sessionId());
                long until = System.currentTimeMillis() + slowMillis;
                while (!token.isCancelled() && System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (token.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("被终止");
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
            return new ModelProfile(80000, 4096);
        }
    }

    private static final class FakeHuman implements HumanInteractionPort {

        @Override
        public Answer ask(Question question) {
            return Answer.answered("同意");
        }
    }
}
