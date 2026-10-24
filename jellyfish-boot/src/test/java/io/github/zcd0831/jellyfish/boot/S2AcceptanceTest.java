package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
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
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 验收（D15）：生成计划 → 执行 → 提交 → 免验证完成 → 结论交付；失败触发重试。
 * 用脚本化假模型，不联网。
 */
class S2AcceptanceTest {

    @TempDir
    Path tmp;

    private final FakeModel model = new FakeModel();
    private final FakeHuman human = new FakeHuman();
    private final JsonCodec codec = new JsonCodec();
    private DataDirectory dir;
    private StartSessionUseCase startSession;
    private RunSessionUseCase runSession;
    private final RunSessionUseCase[] runSessionHolder = new RunSessionUseCase[1];
    private PlanRepository plans;

    @BeforeEach
    void setUp() throws IOException {
        dir = DataDirectory.of(tmp.toString());
        Files.createDirectories(tmp.resolve("providers"));
        Files.createDirectories(tmp.resolve("models"));
        write("providers/p1.json", "{\"id\":\"p1\",\"name\":\"fake\",\"apiEndpoint\":\"http://localhost\","
                + "\"protocol\":\"OPENAI_CHAT\",\"credential\":{\"apiKeyRef\":\"K\"},\"status\":\"ACTIVE\"}");
        write("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"fake\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,\"maxOutputTokens\":4096},"
                + "\"capabilities\":[]}");

        FileSessionRepository sessions = new FileSessionRepository(dir, codec);
        RecordStore records = new FileRecordStore(dir, codec);
        FilePolicyRepository policies = new FilePolicyRepository(dir, codec);
        FileBudgetRepository budgets = new FileBudgetRepository(dir, codec);
        FileCatalogRepository catalog = new FileCatalogRepository(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        BudgetService budgetService = new BudgetService(budgets, sessions, policies,
                new FileCommitScope(dir, codec), publisher);
        ContextWindowService contextWindow = new ContextWindowService(model, policies,
                new FileCommitScope(dir, codec), publisher, event -> { }, budgetService, new EstimatorStrategy());
        plans = new FilePlanRepository(dir, codec);
        PlanFactory planFactory = new PlanFactory();
        ToolExecutor toolExecutor = new ToolExecutor(catalog, new HttpToolAdapter(),
                new FileArtifactStore(dir), human, policies,
                new InternalToolHandlers(Collections.singletonList(new InternalToolHandlers.NamedHandler(
                        "generate_plan", new GeneratePlanHandler(planFactory, plans, catalog,
                        new FileCommitScope(dir, codec), publisher)))));
        ReActLoop reActLoop = new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies,
                new FileCommitScope(dir, codec), human);
        VerificationRunner verificationRunner = new VerificationRunner(model, catalog, human, budgetService,
                EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository experiences =
                new io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository(dir, codec);
        io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository reflections =
                new io.github.zcd0831.jellyfish.infrastructure.file.FileReflectionRepository(dir, codec);
        io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever retriever =
                new io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever(experiences, model,
                        catalog, policies, budgetService);
        io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService feedback =
                new io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService(experiences,
                        policies, new FileCommitScope(dir, codec), publisher);
        io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService distillation =
                new io.github.zcd0831.jellyfish.application.memory.ExperienceDistillationService(experiences,
                        records, model, catalog, policies, budgetService, new FileCommitScope(dir, codec),
                        publisher, event -> { });
        ExecutionLoop executionLoop = new ExecutionLoop(contextWindow, reActLoop, verificationRunner, plans,
                planFactory, catalog, policies, new FileCommitScope(dir, codec), publisher, event -> { }, model,
                budgetService, retriever, feedback, distillation, reflections,
                new io.github.zcd0831.jellyfish.application.planning.SubagentLauncher(startSession,
                        new io.github.zcd0831.jellyfish.application.planning.RunSessionProvider() {
                            @Override
                            public RunSessionUseCase get() {
                                return runSessionHolder[0];
                            }
                        }, sessions, records, budgetService, policies, event -> { },
                        java.util.concurrent.Executors.newFixedThreadPool(2)), experiences);
        runSession = new RunSessionUseCase(sessions, records, new FileCommitScope(dir, codec), publisher,
                executionLoop);
        runSessionHolder[0] = runSession;
        startSession = new StartSessionUseCase(catalog, sessions, budgets,
                new SessionTitleGenerator(model, budgetService), new SystemPromptAssembler(),
                new FileCommitScope(dir, codec), new SystemClock());
    }

    @Test
    void 生成计划到免验证完成再到结论交付() {
        SessionId sessionId = start("把两件事都做了");
        model.script.clear();

        // 第 1 轮：生成计划；同轮收敛（尚无计划）
        model.script.add(toolCall("c1", "generate_plan", planArgs()));
        model.script.add(text("计划已创建"));
        // 第 2 轮：在步骤内提交
        model.script.add(toolCall("c2", "submit_step", "{\"summary\":\"两件事都做完了\"}"));
        // 第 3 轮：计划完结后的自由内循环，产出结论
        model.script.add(text("都做完了，结论如下……"));

        Result<RunSessionResult> result = runSession.run(sessionId, null, ChunkSink.NOOP);
        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
        List<Plan> finished = plans.findRunning(sessionId);
        assertTrue(finished.isEmpty(), "计划应已完结");
        Session session = new FileSessionRepository(dir, codec).load(sessionId);
        assertTrue(session.title() != null, "会话头仍可读回");
    }

    @Test
    void 确定性验证不通过则重试同一步() {
        SessionId sessionId = start("写个东西");
        model.script.clear();

        model.script.add(toolCall("c1", "generate_plan", planArgsWithDeterministic()));
        model.script.add(text("计划已创建"));
        // 第一次提交：空摘要 → 确定性层失败
        model.script.add(toolCall("c2", "submit_step", "{\"summary\":\"\"}"));
        // 重试：合格摘要 → 通过
        model.script.add(toolCall("c3", "submit_step", "{\"summary\":\"写完了\"}"));
        model.script.add(text("完成"));

        Result<RunSessionResult> result = runSession.run(sessionId, null, ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());
        List<java.nio.file.Path> reportFiles = reports(sessionId);
        assertEquals(2, reportFiles.size(), "一次失败 + 一次通过，两份不可变报告");
    }

    private List<java.nio.file.Path> reports(SessionId sessionId) {
        Path dir = tmp.resolve("sessions").resolve(sessionId.getValue()).resolve("reports");
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return files;
    }

    private SessionId start(String input) {
        model.script.add(text("标题"));
        Result<SessionId> started = startSession.start(null, null, input);
        assertTrue(started.isOk(), () -> "start failed: " + started.error().error().code());
        return started.value();
    }

    private static String planArgs() {
        return "{\"goal\":\"把两件事都做了\",\"steps\":[{\"description\":\"做第一件事\","
                + "\"goal\":\"完成第一件事\",\"expectedOutcome\":\"做完\","
                + "\"verificationCriteria\":\"摘要非空\"}]}";
    }

    private static String planArgsWithDeterministic() {
        return "{\"goal\":\"写个东西\",\"steps\":[{\"description\":\"写东西\","
                + "\"goal\":\"写出来\",\"expectedOutcome\":\"有内容\",\"verificationCriteria\":\"摘要非空\","
                + "\"verifications\":[\"DETERMINISTIC\"]}]}";
    }

    private static ModelInvocationResult toolCall(String callId, String name, String argsJson) {
        return new ModelInvocationResult(null,
                Collections.singletonList(new ToolCall(callId, name, argsJson)), new ModelUsage(5, 2),
                ModelId.of("m1"));
    }

    private static ModelInvocationResult text(String value) {
        return new ModelInvocationResult(value, Collections.<ToolCall>emptyList(), new ModelUsage(3, 1),
                ModelId.of("m1"));
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeModel implements ModelInvocationPort {

        private final Deque<ModelInvocationResult> script = new ArrayDeque<ModelInvocationResult>();
        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            ModelInvocationResult next = script.poll();
            return next == null ? text("（脚本已用尽）") : next;
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
