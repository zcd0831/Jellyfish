package zcd.jellyfish.core.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.core.runtime.AgentRunRequest;
import zcd.jellyfish.core.runtime.AgentRunStatus;
import zcd.jellyfish.core.runtime.RunRegistry;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@link SubAgentArchive} 的单元测试：落到自己的命名空间、内容含 run 身份与完整 transcript。
 * <p>
 * 用真实的 {@link ToolOutputStore} 与真实 {@link SessionManager}：本类要验证的正是
 * 「一次委派结束之后，磁盘上到底留下了什么」，把其中任何一环换成 mock 都会把这条链断在验证之外。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentArchiveTest {

    /** 每个用例独立的落盘根目录。 */
    @TempDir
    Path tempDir;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 会话域服务依赖的 agent 门面（只为构造 SessionManager）。 */
    @Mock
    private AgentManager agentManager;

    /** 会话域服务依赖的通知入口（本用例不观察事件）。 */
    @Mock
    private EventPublisher events;

    /** 真实会话域服务：用来造出带消息的子会话。 */
    private SessionManager sessionManager;

    /** 被测归档器。 */
    private SubAgentArchive archive;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(agentManager, events,
                new ExtensionRegistry(new TypeRegistry()), new SessionDefaults());
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(
                new ReactSettings(null, null, null, null, null, null,
                        new ToolOutputSettings(tempDir.toString(), 0, 0L, null, null)));
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        archive = new SubAgentArchive(new ToolOutputStore(runtimeConfig), runtimeConfig);
    }

    @Test
    void archive_should_write_runIdentity_andFullTranscript() throws IOException {
        // Given：一个已终结的 run 与一个带消息的子会话
        Session child = sessionManager.createEphemeral("s-parent", "coder", null, null, null);
        sessionManager.appendMessage(child.getSessionId(),
                new LlmMessage("assistant", "查完了，结论如下", null, null,
                        Collections.emptyList()),
                new LlmUsage(3, 5, 8));
        RunRegistry registry = new RunRegistry();
        String runId = registry.register(
                new AgentRunRequest("s-parent", "coder", child.getSessionId(), "call-1"), null, null);
        registry.markRunning(runId);
        registry.finish(runId, AgentRunStatus.DONE, 3, SessionUsage.EMPTY);

        // When
        String path = archive.archive(registry.snapshot(runId).get(), child);

        // Then：落在自己的命名空间下，键是 runId
        assertNotNull(path);
        assertTrue(Paths.get(path).startsWith(tempDir), path);
        assertEquals(tempDir.resolve(SubAgentArchive.NAMESPACE), Paths.get(path).getParent());
        assertTrue(path.endsWith(runId + ".json"), path);

        // And：run 身份、终态与 transcript 都在
        JsonNode document = ObjectMapperWrapper.readTree(
                new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
        assertEquals(runId, document.get("runId").asText());
        assertEquals("s-parent", document.get("parentSessionId").asText());
        assertEquals("coder", document.get("agentId").asText());
        assertEquals(child.getSessionId(), document.get("sessionId").asText());
        assertEquals("DONE", document.get("status").asText());
        assertEquals(3, document.get("rounds").asInt());
        JsonNode messages = document.get("session").get("messages");
        assertEquals(1, messages.size());
        assertEquals("查完了，结论如下", messages.get(0).get("content").asText());
    }

    @Test
    void archive_should_skip_when_snapshot_missing() {
        // 拿不到快照就跳过：没有 run 身份的一堆消息没有回看价值，也不该在磁盘上冒充一份归档
        assertNull(archive.archive(null, null));
    }
}
