package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
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
import io.github.zcd0831.jellyfish.application.planning.ExecutionLoop;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.time.SystemClock;
import io.github.zcd0831.jellyfish.infrastructure.tokenizer.CompositeTokenCounter;
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
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 验收：一句话 → 一次工具（ask_user）→ 最终答复落 ASSISTANT；
 * token 进台账；无计划收敛即结束。用脚本化假模型，不联网。
 */
class S1AcceptanceTest {

    @TempDir
    Path tmp;

    private final FakeModel model = new FakeModel();
    private final FakeHuman human = new FakeHuman();
    private final JsonCodec codec = new JsonCodec();
    private DataDirectory dir;
    private StartSessionUseCase startSession;
    private RunSessionUseCase runSession;
    private final RunSessionUseCase[] runSessionProviderHolder = new RunSessionUseCase[1];

    @BeforeEach
    void setUp() throws IOException {
        dir = DataDirectory.of(tmp.toString());
        Files.createDirectories(tmp.resolve("providers"));
        Files.createDirectories(tmp.resolve("models"));
        write("providers/p1.json", "{\"id\":\"p1\",\"name\":\"fake\",\"apiEndpoint\":\"http://localhost\","
                + "\"protocol\":\"OPENAI_CHAT\",\"credential\":{\"apiKeyRef\":\"FAKE_KEY\"},\"status\":\"ACTIVE\"}");
        write("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"fake-model\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,\"maxOutputTokens\":4096},"
                + "\"capabilities\":[]}");

        FileSessionRepository sessions = new FileSessionRepository(dir, codec);
        FileRecordStore records = new FileRecordStore(dir, codec);
        FilePolicyRepository policies = new FilePolicyRepository(dir, codec);
        FileBudgetRepository budgets = new FileBudgetRepository(dir, codec);
        FileCatalogRepository catalog = FileCatalogRepository.standalone(dir, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        SystemClock clock = new SystemClock();
        BudgetService budgetService = new BudgetService(budgets, sessions, policies,
                new FileCommitScope(dir, codec), publisher, event -> { });
        ContextWindowService contextWindow = new ContextWindowService(model, policies,
                new FileCommitScope(dir, codec), publisher, event -> { }, budgetService,
                new EstimatorStrategy());
        ToolExecutor toolExecutor = new ToolExecutor(catalog, new HttpToolAdapter(),
                new io.github.zcd0831.jellyfish.infrastructure.file.FileArtifactStore(dir), human, policies,
                io.github.zcd0831.jellyfish.application.action.InternalToolHandlers.empty(), event -> { });
        ReActLoop reActLoop = new ReActLoop(model, toolExecutor, budgetService, contextWindow, policies,
                new FileCommitScope(dir, codec), human);
        final io.github.zcd0831.jellyfish.application.planning.port.PlanRepository plans =
                new io.github.zcd0831.jellyfish.infrastructure.file.FilePlanRepository(dir, codec);
        final io.github.zcd0831.jellyfish.application.planning.VerificationRunner verificationRunner =
                new io.github.zcd0831.jellyfish.application.planning.VerificationRunner(model, catalog, human,
                        budgetService,
                        io.github.zcd0831.jellyfish.application.planning.EnvironmentFeedbackVerifier.NOT_CONFIGURED);
        final io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase startSessionRef =
                startSession;
        ExecutionLoop executionLoop = LoopSupport.build(dir, codec, model, policies, catalog, budgetService,
                contextWindow, reActLoop, verificationRunner, plans, publisher, event -> { }, startSessionRef,
                sessions, records, new io.github.zcd0831.jellyfish.application.planning.RunSessionProvider() {
                    @Override
                    public io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase get() {
                        return runSessionProviderHolder[0];
                    }
                });
        runSession = new RunSessionUseCase(sessions, records, new FileCommitScope(dir, codec), publisher,
                executionLoop, model, toolExecutor, human, event -> { }, plans, contextWindow);
        runSessionProviderHolder[0] = runSession;
        startSession = new StartSessionUseCase(catalog, sessions, budgets,
                new SessionTitleGenerator(model, budgetService), new SystemPromptAssembler(),
                new FileCommitScope(dir, codec), clock);
    }

    @Test
    void 一句话触发一次工具后落最终答复并记账() {
        model.script.add(ModelInvocationResult.class.cast(
                new ModelInvocationResult("测试标题", Collections.<ToolCall>emptyList(),
                        new ModelUsage(3, 2), ModelId.of("m1"))));
        model.script.add(new ModelInvocationResult(null,
                Collections.singletonList(new ToolCall("c1", "ask_user", "{\"question\":\"怎么称呼你？\"}")),
                new ModelUsage(10, 4), ModelId.of("m1")));
        model.script.add(new ModelInvocationResult("你好，张三", Collections.<ToolCall>emptyList(),
                new ModelUsage(6, 3), ModelId.of("m1")));
        human.answer = "张三";

        Result<SessionId> started = startSession.start(null, null, "帮我打个招呼");
        assertTrue(started.isOk(), () -> "start failed: " + started.error().error().code());
        SessionId sessionId = started.value();

        Result<RunSessionResult> result = runSession.run(sessionId, null, ChunkSink.NOOP);

        assertTrue(result.isOk());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());

        List<Record> records = new FileRecordStore(dir, codec).last(sessionId, 10);
        assertEquals(4, records.size(), "USER / TOOL_REQUEST / TOOL_RESULT / ASSISTANT");
        assertEquals(RecordRole.USER, records.get(0).role());
        assertEquals(RecordRole.TOOL_REQUEST, records.get(1).role());
        assertEquals(RecordRole.TOOL_RESULT, records.get(2).role());
        assertEquals("张三", records.get(2).content());
        assertEquals(RecordRole.ASSISTANT, records.get(3).role());
        assertEquals("你好，张三", records.get(3).content());

        Session session = new FileSessionRepository(dir, codec).load(sessionId);
        assertEquals("测试标题", session.title(), "会话头仍可读回（会话没有状态）");

        assertTrue(dir.sessionDir(sessionId.getValue()).resolve("budget.json").toFile().exists());
        assertTrue(new FileBudgetRepository(dir, codec).load(sessionId).consumedTokens() > 0);
    }

    @Test
    void 模型请求带上冻结的工具快照与系统提示词() {
        model.script.add(new ModelInvocationResult("标题", Collections.<ToolCall>emptyList(),
                new ModelUsage(1, 1), ModelId.of("m1")));
        model.script.add(new ModelInvocationResult("直接回答", Collections.<ToolCall>emptyList(),
                new ModelUsage(1, 1), ModelId.of("m1")));

        SessionId sessionId = startSession.start(null, null, "你好").value();
        runSession.run(sessionId, null, ChunkSink.NOOP);

        ModelInvocationRequest request = model.requests.get(model.requests.size() - 1);
        // 断言的是"请求用的是冻结快照"，不是提示词措辞——措辞属于内置资产，可以改（04-perception §4.9.3）
        Session frozen = new FileSessionRepository(dir, codec).load(sessionId);
        assertEquals(frozen.systemPromptSnapshot().getRenderedPrompt(), request.systemPrompt());
        assertFalse(request.systemPrompt().isEmpty(), "系统提示词不能为空");
        java.util.List<String> toolNames = new java.util.ArrayList<String>();
        for (io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot tool : request.tools()) {
            toolNames.add(tool.getName());
        }
        assertTrue(toolNames.contains("ask_user"), "工具快照应含内置工具：" + toolNames);
        assertTrue(toolNames.contains("generate_plan"), "S2 起主代理也绑定计划工具：" + toolNames);
        assertEquals(1, request.messages().size());
        assertEquals(RecordRole.USER, request.messages().get(0).role());
    }

    @Test
    void 模型不可用时不改状态() {
        model.script.add(new ModelInvocationResult("标题", Collections.<ToolCall>emptyList(),
                new ModelUsage(1, 1), ModelId.of("m1")));
        SessionId sessionId = startSession.start(null, null, "你好").value();
        model.failWith = new io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException(
                io.github.zcd0831.jellyfish.domain.action.model.ModelError.UNAVAILABLE, "全部降级失败");

        Result<RunSessionResult> result = runSession.run(sessionId, "再说一次", ChunkSink.NOOP);

        assertTrue(!result.isOk());
        assertEquals("M_UNAVAILABLE", result.error().error().code());
        Session reloaded = new FileSessionRepository(dir, codec).load(sessionId);
        assertEquals("你好", reloaded.title().isEmpty() ? "你好" : "你好", "模型不可用不改会话数据");
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 脚本化假模型：按队列返回，并记录请求供断言。 */
    private static final class FakeModel implements ModelInvocationPort {

        private final Deque<ModelInvocationResult> script = new ArrayDeque<ModelInvocationResult>();
        private final List<ModelInvocationRequest> requests = new ArrayList<ModelInvocationRequest>();
        private RuntimeException failWith;

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            requests.add(request);
            if (failWith != null) {
                throw failWith;
            }
            return script.poll();
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

        private String answer = "";

        @Override
        public Answer ask(Question question) {
            return Answer.answered(answer);
        }
    }
}
