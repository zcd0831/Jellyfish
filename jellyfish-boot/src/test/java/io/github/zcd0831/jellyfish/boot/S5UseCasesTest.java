package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.ManageCatalogUseCase;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 三个用例：策略校验、归档/删除、内置前缀保留。
 * 规则出处：模块-应用层 §4、§8.4、§8.5，技术决策 D11（C_INVALID_VALUE）、M04。
 */
class S5UseCasesTest {

    @TempDir
    Path tmp;

    private final JsonCodec codec = new JsonCodec();
    private FileSessionRepository sessions;
    private FilePolicyRepository policies;
    private ManageSessionUseCase manageSession;
    private AdjustPolicyUseCase adjustPolicy;
    private ManageCatalogUseCase manageCatalog;

    @BeforeEach
    void setUp() {
        DataDirectory dir = DataDirectory.of(tmp.toString());
        InProcessEventPublisher publisher = new InProcessEventPublisher();
        sessions = new FileSessionRepository(dir, codec);
        policies = new FilePolicyRepository(dir, codec);
        manageSession = new ManageSessionUseCase(sessions, new FileArtifactStore(dir),
                new FileCommitScope(dir, codec), publisher);
        adjustPolicy = new AdjustPolicyUseCase(policies, new FileCommitScope(dir, codec), publisher);
        manageCatalog = new ManageCatalogUseCase(new FileCatalogRepository(dir, codec));
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
    void 归档后不可续跑且目录移走() {
        SessionId id = newSession();

        assertTrue(manageSession.archive(id).isOk());

        assertTrue(Files.isDirectory(tmp.resolve("archive").resolve("s1")));
        assertFalse(Files.exists(tmp.resolve("sessions").resolve("s1")));
    }

    @Test
    void 删除会话族目录() {
        SessionId id = newSession();

        assertTrue(manageSession.delete(id).isOk());

        assertFalse(Files.exists(tmp.resolve("sessions").resolve("s1")));
    }

    @Test
    void 取消把会话落回可续跑() {
        SessionId id = newSession();
        Session session = sessions.load(id);
        session.resume();
        sessions.save(session);

        assertTrue(manageSession.cancel(id).isOk());

        assertEquals(SessionStatus.RESUMABLE, sessions.load(id).status());
    }

    @Test
    void 内置前缀不允许用户写入() {
        io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition builtinAgent = null;
        for (io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition agent
                : new FileCatalogRepository(DataDirectory.of(tmp.toString()), codec).agents()) {
            if (agent.id().getValue().equals("builtin.main")) {
                builtinAgent = agent;
            }
        }
        assertTrue(builtinAgent != null, "应能读到内置代理");

        assertFalse(manageCatalog.saveAgent(builtinAgent).isOk(), "内置代理不允许经用例写入");
    }
}
