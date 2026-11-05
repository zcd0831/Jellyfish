package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.perception.StartSessionDeps;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.SessionTitleGenerator;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.time.SystemClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起标题也要记账：台账必须先落地再取标题，否则 BudgetService.recordUsage 会另建一份写进去、
 * 调用方随后又用手里的旧实例覆盖回去，标题那次调用的用量凭空消失。
 * 规则出处：05-action §5.8.5（台账只有一份）、08-application §8.8.3 纪律一「绝不跨 IO 持事务」。
 */
class TitleBudgetTest {

    @TempDir
    Path tmp;

    private void write(String relative, String body) throws Exception {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 起标题的用量记进同一份台账且只建一份() throws Exception {
        write("providers/p1.json", "{\"id\":\"p1\",\"name\":\"fake\",\"apiEndpoint\":\"http://localhost\","
                + "\"protocol\":\"OPENAI_CHAT\",\"credential\":{\"apiKeyRef\":\"FAKE_KEY\"},\"status\":\"ACTIVE\"}");
        write("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"fake\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,\"maxOutputTokens\":4096},"
                + "\"capabilities\":[]}");
        DataDirectory data = DataDirectory.of(tmp.toString());
        JsonCodec codec = new JsonCodec();
        FileSessionRepository sessions = new FileSessionRepository(data, codec);
        FileBudgetRepository budgets = new FileBudgetRepository(data, codec);
        FilePolicyRepository policies = new FilePolicyRepository(data, codec);
        FileCatalogRepository catalog = FileCatalogRepository.standalone(data, codec);
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        SessionCancellations cancellations = new SessionCancellations();
        FileCommitScope commitScope = new FileCommitScope(data, codec);
        BudgetService budgetService = new BudgetService(budgets, sessions, policies, commitScope, publisher,
                event -> { });
        ModelInvocationPort model = new ModelInvocationPort() {
            @Override
            public ModelInvocationResult invoke(ModelInvocationRequest request) {
                return new ModelInvocationResult("标题", Collections.emptyList(),
                        new ModelUsage(40L, 0L, false), ModelId.of("m1"));
            }

            @Override
            public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
                return invoke(request);
            }

            @Override
            public ModelProfile describe(ModelId modelId) {
                return new ModelProfile(1000, 100);
            }
        };
        StartSessionUseCase start = new StartSessionUseCase(StartSessionDeps.builder()
                .catalog(catalog).sessions(sessions).budgets(budgets)
                .titleGenerator(new SessionTitleGenerator(model, budgetService))
                .assembler(new io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler())
                .commitScope(commitScope).clock(new SystemClock()).cancellations(cancellations)
                .eventPublisher(publisher).build());

        Result<SessionId> started = start.start(AgentId.of("builtin.main"), ModelId.of("m1"), "帮我看看这个项目");

        assertTrue(started.isOk(), "会话没开起来");
        SessionId sessionId = started.value();
        assertEquals(40L, budgets.load(sessionId).consumedTokens(),
                "起标题用掉的 token 必须记在同一份台账上");
        assertEquals("标题", sessions.load(sessionId).title(), "标题也要落进会话头");
    }
}
