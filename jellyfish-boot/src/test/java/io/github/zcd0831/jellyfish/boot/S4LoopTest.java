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
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.SessionTitleGenerator;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.planning.EnvironmentFeedbackVerifier;
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.GeneratePlanHandler;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.RunSessionProvider;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SubagentLauncher;
import io.github.zcd0831.jellyfish.application.planning.VerificationRunner;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileArtifactStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository;
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
import java.util.Collections;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 闭环接入外循环：计划诞生 → 检索分配 → step 成功 → 奖励（只奖被建议的）。
 * 规则出处：模块-记忆 §8.1、§8.3，模型图 §5.1。
 */
class S4LoopTest {

    @TempDir
    Path tmp;

    private final JsonCodec codec = new JsonCodec();
    private final java.util.List<io.github.zcd0831.jellyfish.application.shared.ProgressEvent> announced =
            new java.util.ArrayList<io.github.zcd0831.jellyfish.application.shared.ProgressEvent>();
    private final FakeModel model = new FakeModel();
    private DataDirectory dir;
    private StartSessionUseCase startSession;
    private RunSessionUseCase runSession;
    private ExperienceRepository experiences;
    private final RunSessionUseCase[] holder = new RunSessionUseCase[1];
    private PlanRepository plans;

    @BeforeEach
    void setUp() throws IOException {
        dir = DataDirectory.of(tmp.toString());
        Files.createDirectories(tmp.resolve("providers"));
        Files.createDirectories(tmp.resolve("models"));
        write("providers/p1.json", "{\"id\":\"p1\",\"name\":\"f\",\"apiEndpoint\":\"http://x\","
                + "\"credential\":{\"apiKeyRef\":\"K\"},\"status\":\"ACTIVE\"}");
        write("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"f\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,\"maxOutputTokens\":4096}}");

        FileSessionRepository sessions = new FileSessionRepository(dir, codec);
        RecordStore records = new FileRecordStore(dir, codec);
        FilePolicyRepository policies = new FilePolicyRepository(dir, codec);
        FileBudgetRepository budgets = new FileBudgetRepository(dir, codec);
        FileCatalogRepository catalog = FileCatalogRepository.standalone(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        experiences = new FileExperienceRepository(dir, codec);
        BudgetService budgetService = new BudgetService(budgets, sessions, policies,
                new FileCommitScope(dir, codec), publisher, announced::add);
        ContextWindowService contextWindow = new ContextWindowService(model, policies,
                new FileCommitScope(dir, codec), publisher, event -> { }, budgetService, new EstimatorStrategy());
        plans = new FilePlanRepository(dir, codec);
        PlanFactory planFactory = new PlanFactory();
        FakeHuman human = new FakeHuman();
        ToolExecutor toolExecutor = new ToolExecutor(catalog, new HttpToolAdapter(), new FileArtifactStore(dir),
                human, policies, new InternalToolHandlers(Collections.singletonList(
                new InternalToolHandlers.NamedHandler("generate_plan",
                        new GeneratePlanHandler(planFactory, plans, catalog, new FileCommitScope(dir, codec),
                                publisher)))), event -> { });
        ReActLoop reActLoop = new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies,
                new FileCommitScope(dir, codec), human);
        VerificationRunner verificationRunner = new VerificationRunner(model, catalog, human, budgetService,
                EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        startSession = new StartSessionUseCase(catalog, sessions, budgets,
                new SessionTitleGenerator(model, budgetService), new SystemPromptAssembler(),
                new FileCommitScope(dir, codec), new SystemClock());
        ExecutionLoop executionLoop = LoopSupport.build(dir, codec, model, policies, catalog, budgetService,
                contextWindow, reActLoop, verificationRunner, plans, publisher, announced::add, startSession,
                sessions, records, new RunSessionProvider() {
                    @Override
                    public RunSessionUseCase get() {
                        return holder[0];
                    }
                });
        runSession = new RunSessionUseCase(sessions, records, new FileCommitScope(dir, codec), publisher,
                executionLoop, model, toolExecutor, human, event -> { }, plans, contextWindow);
        holder[0] = runSession;
    }

    @Test
    void 计划诞生分配经验且步骤成功后只奖励被建议的() {
        experiences.save(Experience.create(ExperienceId.of("e1"), "需要多步任务时",
                new LessonLearned("先拆步骤再执行", "不遗漏子任务"), 0.5, 100L));
        model.seed(text("标题"));
        SessionId sessionId = startSession.start(null, null, "做两件事").value();
        model.seed(toolCall("c1", "generate_plan",
                "{\"goal\":\"做两件事\",\"steps\":[{\"description\":\"第一步\","
                        + "\"goal\":\"g\",\"expectedOutcome\":\"o\",\"verificationCriteria\":\"c\"}]}"));
        model.seed(text("计划已创建"));
        model.seed(toolCall("c2", "submit_step", "{\"summary\":\"第一步做完了\"}"));
        model.seed(text("全部完成"));

        Result<RunSessionResult> result = runSession.run(sessionId, null, ChunkSink.NOOP);

        assertTrue(result.isOk(), () -> "run failed: " + result.error().error().code());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());

        Plan plan = plans.load(io.github.zcd0831.jellyfish.domain.shared.PlanId.of(firstPlanId(sessionId)));
        assertEquals(1, plan.steps().get(0).assignedExperienceIds().size(), "计划诞生时应分配经验");
        assertEquals(ExperienceId.of("e1"), plan.steps().get(0).assignedExperienceIds().get(0));

        double confidence = experiences.findActive().get(0).confidence().getValue();
        assertEquals(0.6, confidence, 1e-9, "成功后应奖励被建议的经验");

        assertTrue(injectedContext(sessionId).contains("先拆步骤再执行"), "经验正文应注入为 SYSTEM 记录");
    }

    /** 找到那条 SYSTEM 注入记录（首行带 step 标记）。 */
    private String injectedContext(SessionId sessionId) {
        StringBuilder text = new StringBuilder();
        for (io.github.zcd0831.jellyfish.domain.perception.session.Record record
                : new FileRecordStore(dir, codec).last(sessionId, 50)) {
            if (record.role() == io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.SYSTEM
                    && record.content() != null && record.content().startsWith("[经验注入 step=")) {
                text.append(record.content());
            }
        }
        return text.toString();
    }

    @Test
    void 同一步的第二次attempt不重复注入() {
        experiences.save(Experience.create(ExperienceId.of("e1"), "需要多步任务时",
                new LessonLearned("先拆步骤再执行", "不遗漏子任务"), 0.5, 100L));
        model.seed(text("标题"));
        SessionId sessionId = startSession.start(null, null, "做两件事").value();
        model.seed(toolCall("c1", "generate_plan",
                "{\"goal\":\"做两件事\",\"steps\":[{\"description\":\"第一步\","
                        + "\"goal\":\"g\",\"expectedOutcome\":\"o\",\"verificationCriteria\":\"c\","
                        + "\"verifications\":[\"DETERMINISTIC\"]}]}"));
        model.seed(text("计划已创建"));
        model.seed(toolCall("c2", "submit_step", "{\"summary\":\"\"}"));
        model.seed(toolCall("c3", "submit_step", "{\"summary\":\"做完了\"}"));
        model.seed(text("完成"));

        runSession.run(sessionId, null, ChunkSink.NOOP);

        String injected = injectedContext(sessionId);
        assertEquals(1, injected.split("\\[经验注入", -1).length - 1, "同一步只注入一次");
    }

    private String firstPlanId(SessionId sessionId) {
        Path plansDir = tmp.resolve("sessions").resolve(sessionId.getValue()).resolve("plans");
        java.io.File[] files = plansDir.toFile().listFiles();
        assertTrue(files != null && files.length > 0, "应有计划文件");
        String name = files[0].getName();
        return name.substring(0, name.length() - ".json".length());
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static ModelInvocationResult text(String value) {
        return new ModelInvocationResult(value, Collections.<ToolCall>emptyList(), new ModelUsage(3, 1),
                ModelId.of("m1"));
    }

    private static ModelInvocationResult toolCall(String callId, String name, String argsJson) {
        return new ModelInvocationResult(null, Collections.singletonList(new ToolCall(callId, name, argsJson)),
                new ModelUsage(5, 2), ModelId.of("m1"));
    }

    /** 检索请求按提示词里的步骤 id 现场生成回复，其余按脚本顺序。 */
    private static final class FakeModel implements ModelInvocationPort {

        private final Deque<ModelInvocationResult> script = new ArrayDeque<ModelInvocationResult>();

        void seed(ModelInvocationResult result) {
            script.add(result);
        }

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            if (request.systemPrompt() != null && request.systemPrompt().contains("经验匹配到步骤")) {
                String stepId = extractStepId(request.messages().get(0).content());
                return text("{\"steps\":[{\"stepId\":\"" + stepId
                        + "\",\"experienceIds\":[\"e1\"]}]}");
            }
            ModelInvocationResult next = script.poll();
            return next == null ? text("（脚本已用尽）") : next;
        }

        /** 步骤 id 是 UUID；经验 id 形如 e1，所以只认 UUID。 */
        private static String extractStepId(String material) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("- ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) \\|")
                    .matcher(material);
            return m.find() ? m.group(1) : "";
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

    @Test
    void 计划与步骤的进度事件带N分之M() {
        announced.clear();
        model.seed(text("标题"));
        SessionId sessionId = startSession.start(null, null, "做两件事").value();
        model.seed(toolCall("c1", "generate_plan",
                "{\"goal\":\"做两件事\",\"steps\":[{\"description\":\"第一步\"},"
                        + "{\"description\":\"第二步\"}]}"));
        model.seed(text("计划已创建"));
        model.seed(toolCall("c2", "submit_step", "{\"summary\":\"第一步做完了\"}"));
        model.seed(text("第一步完成"));
        model.seed(toolCall("c3", "submit_step", "{\"summary\":\"第二步做完了\"}"));
        model.seed(text("全部完成"));

        runSession.run(sessionId, null, ChunkSink.NOOP);

        int plans = 0;
        boolean stepStarted = false;
        for (io.github.zcd0831.jellyfish.application.shared.ProgressEvent event : announced) {
            if (event.kind() == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.PLAN_CREATED) {
                plans++;
                assertTrue(event.message().contains("步"), "计划播报要带步数：" + event.message());
            }
            if (event.kind() == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.STEP_STARTED) {
                stepStarted = true;
                assertTrue(event.message().contains("/"), "步骤播报要带 N/M：" + event.message());
            }
        }
        assertEquals(1, plans, "一次计划只播报一次");
        assertTrue(stepStarted, "应至少播报一个步骤");
    }
}
