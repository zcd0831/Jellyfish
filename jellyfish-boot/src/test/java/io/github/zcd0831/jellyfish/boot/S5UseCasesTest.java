package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.ManageCatalogUseCase;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileArtifactStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePolicyRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 三个用例：策略校验、归档/删除、内置前缀保留。
 * 规则出处：模块-应用层 §4、§8.4、§8.5，技术决策 D11（C_INVALID_VALUE）、M04。
 */
class S5UseCasesTest {

    @TempDir
    Path tmp;

    private final JsonCodec codec = new JsonCodec();
    private DataDirectory dir;
    private FileSessionRepository sessions;
    private FilePolicyRepository policies;
    private ManageSessionUseCase manageSession;
    private AdjustPolicyUseCase adjustPolicy;
    private ManageCatalogUseCase manageCatalog;
    private io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope commitScope;
    private io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository budgets;

    @BeforeEach
    void setUp() {
        DataDirectory dir = DataDirectory.of(tmp.toString());
        this.dir = dir;
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        sessions = new FileSessionRepository(dir, codec);
        policies = new FilePolicyRepository(dir, codec);
        commitScope = new FileCommitScope(dir, codec);
        budgets = new io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository(dir, codec);
        io.github.zcd0831.jellyfish.application.action.BudgetService budgetService =
                new io.github.zcd0831.jellyfish.application.action.BudgetService(
                        budgets, sessions, policies, commitScope, publisher, event -> { });
        io.github.zcd0831.jellyfish.application.perception.ContextWindowService window =
                new io.github.zcd0831.jellyfish.application.perception.ContextWindowService(
                        new io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort() {
                            @Override
                            public io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult invoke(
                                    io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest request) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult invokeStreaming(
                                    io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest request,
                                    io.github.zcd0831.jellyfish.application.shared.ChunkSink sink) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public io.github.zcd0831.jellyfish.application.action.port.ModelProfile describe(
                                    io.github.zcd0831.jellyfish.domain.shared.ModelId modelId) {
                                return new io.github.zcd0831.jellyfish.application.action.port.ModelProfile(50000, 4096);
                            }
                        },
                        policies, commitScope, publisher, event -> { }, budgetService,
                        text -> text == null ? 0 : text.length() / 4);
        manageSession = new ManageSessionUseCase(sessions, new FileArtifactStore(dir),
                commitScope, publisher, store(), window);
        adjustPolicy = new AdjustPolicyUseCase(policies, commitScope, publisher);
        manageCatalog = new ManageCatalogUseCase(FileCatalogRepository.standalone(dir, codec));
    }

    private SessionId newSession() {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1L, null);
        sessions.save(session);
        return session.id();
    }

    @Test
    void 合法策略调整立即生效() {
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put("maxReActRounds", "30");
        changes.put("budgetWarnRatio", "0.5");

        Result<RuntimePolicy> result = adjustPolicy.adjust(changes);

        assertTrue(result.isOk());
        assertEquals(30, result.value().maxReActRounds());
        assertEquals(0.5, result.value().budgetWarnRatio(), 1e-9);
        assertEquals(30, policies.load().maxReActRounds(), "改完即落盘生效");
    }

    @Test
    void 非法策略值被拒绝且不改文件() {
        int before = policies.load().maxRetries();
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put("maxRetries", "-1");

        Result<RuntimePolicy> result = adjustPolicy.adjust(changes);

        assertFalse(result.isOk());
        assertEquals("C_INVALID_VALUE", result.error().error().code());
        assertEquals(before, policies.load().maxRetries(), "拒绝时不得改动策略");
    }

    @Test
    void 归档压成包后应用不再列出它() {
        SessionId id = newSession();

        assertTrue(manageSession.archive(id).isOk());

        assertTrue(Files.exists(tmp.resolve("archive").resolve("s1.zip")), "归档 = 压缩成包（D22）");
        assertFalse(Files.exists(tmp.resolve("sessions").resolve("s1")));
        assertTrue(manageSession.list().value().isEmpty(), "归档后应用不再认识它");
    }

    @Test
    void 删除会话族目录() {
        SessionId id = newSession();

        assertTrue(manageSession.delete(id).isOk());

        assertFalse(Files.exists(tmp.resolve("sessions").resolve("s1")));
    }

    @Test
    void 取消只发事件不改会话数据() {
        SessionId id = newSession();

        assertTrue(manageSession.cancel(id).isOk());

        assertNotNull(sessions.load(id), "会话仍在，只是本轮被取消");
        assertFalse(manageSession.list().value().isEmpty(), "取消不影响会话存在性");
    }

    @Test
    void 内置前缀不允许用户写入() {
        io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition builtinAgent = null;
        for (io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition agent
                : FileCatalogRepository.standalone(DataDirectory.of(tmp.toString()), codec).agents()) {
            if (agent.id().getValue().equals("builtin.main")) {
                builtinAgent = agent;
            }
        }
        assertTrue(builtinAgent != null, "应能读到内置代理");

        assertFalse(manageCatalog.saveAgent(builtinAgent).isOk(), "内置代理不允许经用例写入");
    }

    @Test
    void 回放返回最近若干条并标出是否还有更早的() {
        SessionId id = newSession();
        io.github.zcd0831.jellyfish.application.perception.port.RecordStore store = store();
        for (int i = 0; i < 5; i++) {
            store.append(id, new io.github.zcd0831.jellyfish.domain.perception.session.Record(
                    io.github.zcd0831.jellyfish.domain.shared.RecordId.of("r" + i),
                    io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.USER,
                    "第" + i + "条", null, i + 1, System.currentTimeMillis()));
        }

        io.github.zcd0831.jellyfish.application.perception.SessionTranscript tail =
                manageSession.transcript(id, 3).value();

        assertEquals(3, tail.records().size());
        assertTrue(tail.hasEarlier(), "还有更早的记录");
        assertEquals("第4条", tail.records().get(2).content());

        io.github.zcd0831.jellyfish.application.perception.SessionTranscript all =
                manageSession.transcript(id, 50).value();
        assertEquals(5, all.records().size());
        assertTrue(!all.hasEarlier());
    }

    @Test
    void 回放不存在的会话返回会话不存在() {
        assertFalse(manageSession.transcript(SessionId.of("nope"), 10).isOk());
    }

    private io.github.zcd0831.jellyfish.application.perception.port.RecordStore store() {
        return new io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore(dir, codec);
    }

    @Test
    void 主循环请求的用量写进台账文件且辅助调用不覆盖() {
        SessionId id = newSession();
        io.github.zcd0831.jellyfish.application.action.BudgetService budget =
                new io.github.zcd0831.jellyfish.application.action.BudgetService(
                        new io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository(dir, codec),
                        sessions, policies, new io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope(dir, codec),
                        new InProcessEventPublisher(), event -> { });

        budget.recordTurnUsage(sessions.load(id),
                new io.github.zcd0831.jellyfish.application.action.port.ModelUsage(18200L, 300L));

        io.github.zcd0831.jellyfish.domain.action.budget.Budget saved =
                new io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository(dir, codec).load(id);
        assertEquals(18200L, saved.lastPromptTokens());
        assertEquals(id.getValue(), saved.lastPromptSessionId());
        assertTrue(!saved.lastPromptEstimated(), "厂商给的就是精确值");

        // 辅助调用（标题生成这类小请求）只记账，不覆盖窗口观测
        budget.recordUsage(sessions.load(id),
                new io.github.zcd0831.jellyfish.application.action.port.ModelUsage(30L, 10L));
        assertEquals(18200L, new io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository(dir, codec)
                .load(id).lastPromptTokens(), "小请求不能把真实窗口数字盖掉");
    }

    @Test
    void 回放带回上下文占用标签且随观测变化() {
        SessionId id = newSession();

        assertEquals("— / 50k", manageSession.transcript(id, 10).value().contextLabel(),
                "还没有请求过：不拿估算冒充");

        io.github.zcd0831.jellyfish.domain.action.budget.Budget budget =
                io.github.zcd0831.jellyfish.domain.action.budget.Budget.openFor(
                        io.github.zcd0831.jellyfish.domain.shared.BudgetId.of("b1"), id);
        budget.observePrompt(id, 18200L, false);
        budgets.save(budget);

        assertEquals("18.2k / 50k", manageSession.transcript(id, 10).value().contextLabel());
    }
}
