package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.core.runtime.RunRegistry;
import zcd.jellyfish.core.runtime.RunScheduler;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 「插件驱动的 run」与「{@code task} 驱动的 run} 走同一条链路的端到端验证。
 * <p>
 * <b>为什么不能只靠「靠构造保证一致」</b>：P2b 的适配器把一切交给 {@code SubAgentLauncher}，
 * 这在代码上就一致了；但「端口真的能驱动出 run 吗、归档真的写了吗、用量真的归到父会话了吗、
 * 登记表真的清了吗」都是运行期事实，只有跑一遍才算数。本测试因此<b>全部用真实实现</b>——
 * 真实的会话域、登记表、调度器、运行时、归档器与端口，只把 {@code ReActLooper}（也就是模型那一层）
 * 换成脚本，因此不需要任何 LLM。
 * <p>
 * <b>它补的是哪一段</b>：{@code SubAgentLauncherTest} 验的是「{@code task} 那条路」，
 * {@code SubAgentDelegationAdapterTest} 只验翻译（委派器是 mock）。这里把两者接起来，
 * 并断言只有真实链路才会产生的那些副作用。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentDelegationEndToEndTest {

    /** 可委派的 agent 标识。 */
    private static final String SCOUT = "scout";

    /** 每个归档文件独立的落盘根目录。 */
    @TempDir
    Path archiveRoot;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 会话模型解析器。 */
    @Mock
    private SessionModelResolver sessionModelResolver;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** ReAct 循环器：本测试只把它当成「模型那一层」的替身。 */
    @Mock
    private ReActLooper reActLooper;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 权限管理器。 */
    @Mock
    private PermissionManager permissionManager;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 真实委派作用域持有者。 */
    private RunContextHolder runContexts;

    /** 真实 agent run 门面。 */
    private AgentRuntime runtime;

    /** 被测端口（真实适配器 + 真实委派器）。 */
    private SubAgentPort port;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()),
                new SessionDefaults());
        runContexts = new RunContextHolder();
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(
                new ReactSettings(null, null, null, null, null, null,
                        new ToolOutputSettings(archiveRoot.toString(), 0, 0L, null, null)));
        lenient().when(permissionManager.usableTools(any())).thenReturn(toolName -> true);
        RunRegistry registry = new RunRegistry();
        RunScheduler scheduler = new RunScheduler(runContexts, registry, new RunEventBus(), runtimeConfig);
        runtime = new AgentRuntime(registry, runContexts, scheduler);
        SubAgentArchive archive = new SubAgentArchive(new ToolOutputStore(runtimeConfig), runtimeConfig);
        SubAgentLauncher launcher = new SubAgentLauncher(sessionManager, agentManager, sessionModelResolver,
                runtimeConfig, reActLooper, runContexts, permissionManager, runtime, archive);
        port = new SubAgentDelegationAdapter(launcher);
    }

    @Test
    void port_driven_runs_should_produce_results_usage_archive_and_cleanup() throws IOException {
        // Given：两个可以并行跑的委派
        when(agentManager.find(SCOUT)).thenReturn(new AgentDefinition(SCOUT, "侦察", null, true, null));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(new ResolvedModel(
                new Provider("openai", "openai", null, null, null), new Model("gpt-4o", "gpt-4o", 0, 0)));
        runContexts.open(8, 8);
        Session parent = sessionManager.create("jellyfish", null, null);
        stubNestedTurn("结论", 2);

        // When：连发两个派生（扇出），再逐个等
        DelegationHandle first = port.spawn(new DelegationRequest(parent.getSessionId(), SCOUT, "查 A",
                CancellationToken.NONE));
        DelegationHandle second = port.spawn(new DelegationRequest(parent.getSessionId(), SCOUT, "查 B",
                CancellationToken.NONE));
        DelegationResult firstResult = first.await();
        DelegationResult secondResult = second.await();

        // Then：结果被正确翻译（runId 说明确实派生过，而不是被拒）
        assertNotNull(first.runId());
        assertEquals(DelegationStatus.COMPLETED, firstResult.getStatus());
        assertEquals("结论", firstResult.getText());
        assertEquals(2, firstResult.getRounds());
        assertEquals(12L, firstResult.getTotalTokens());
        assertEquals(DelegationStatus.COMPLETED, secondResult.getStatus());

        // And：两次的用量都归集到了父会话（这是 task 与端口共用的那段收尾）
        assertEquals(24L, sessionManager.require(parent.getSessionId()).getUsage().getTotalTokens());

        // And：每个 run 都留下了归档，且落在独立命名空间下
        List<Path> archived = listArchives();
        assertEquals(2, archived.size(), archived.toString());
        assertTrue(archived.get(0).getFileName().toString().endsWith(".json"));

        // And：终态条目已从登记表摘掉、子会话已关闭且不进会话列表
        assertTrue(runtime.activeRuns().isEmpty(), runtime.activeRuns().toString());
        assertEquals(1, sessionManager.all().size());
    }

    @Test
    void port_should_reject_with_the_kernel_admission_reason_when_no_turn_is_running() {
        // Given：没有进行中的回合（没有 open 过作用域）
        // 刻意不打桩 agentManager.find：准入在「有没有回合」这一步就停了，走不到查类型
        Session parent = sessionManager.create("jellyfish", null, null);

        // When
        DelegationResult result = port.spawn(new DelegationRequest(parent.getSessionId(), SCOUT, "查一下",
                CancellationToken.NONE)).await();

        // Then：拒绝理由来自内核的准入（而不是端口缺失那句）——这证明装配接的是真实现
        assertEquals(DelegationStatus.REJECTED, result.getStatus());
        assertTrue(result.getError().contains("没有进行中的回合"), result.getError());
        assertFalse(result.getError().contains("没有提供子代理委派能力"), result.getError());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    /**
     * 枚举归档目录下的文件。
     *
     * @return 文件列表
     * @throws IOException 读取失败时抛出
     */
    private List<Path> listArchives() throws IOException {
        Path directory = archiveRoot.resolve(SubAgentArchive.NAMESPACE);
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        }
        return files;
    }

    /**
     * 把「模型那一层」换成脚本：子代理回合直接产出给定文本，并记下一笔用量。
     *
     * @param text   最终文本
     * @param rounds 轮数
     */
    private void stubNestedTurn(String text, int rounds) {
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Session child = invocation.getArgument(0);
                    String childId = child.getSessionId();
                    sessionManager.appendMessage(childId, LlmMessage.user("任务"), null);
                    sessionManager.appendMessage(childId, LlmMessage.assistant(text), new LlmUsage(5, 7, 12));
                    return ReActResult.completed(childId, text, rounds);
                });
    }
}
