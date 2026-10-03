package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 工具调用的**调用者身份**在 {@link ToolExecutor} 上的装配测试。
 * <p>
 * <b>为什么单独一个测试类、又为什么放在这个包</b>：三个身份字段里有两个来自当前执行路径的
 * {@link RunContext}，而装载上下文的入口 {@link RunContextHolder#set(RunContext)} 是包级可见的
 * （只有调度器该用它）。放在 {@code core.runtime} 里，这个测试才能既构造真实的 run 上下文，
 * 又走真实的 {@link ToolExecutor} —— 而不用为了测试把 {@code set} 开成公开方法。
 * <p>
 * 覆盖三种调用位置，它们正好是插件能看到的三种答案：
 * <ul>
 *     <li>不在任何回合里（外壳线程、进程级调用）：三个字段全 {@code null}；</li>
 *     <li>顶层回合（用户直接对话）：会话是根会话，没有 run 身份；</li>
 *     <li>子 run 里：父会话来自会话本身，run 身份来自上下文。</li>
 * </ul>
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工具调用的调用者身份")
class ToolCallIdentityTest {

    /** 权限管理器：一律放行，本测试不关心权限。 */
    @Mock
    private PermissionManager permissionManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** agent 门面，只为装配真实会话服务。 */
    @Mock
    private AgentManager agentManager;

    /** 输出落盘存储。 */
    @Mock
    private ToolOutputStore store;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 真实会话服务。 */
    private SessionManager sessionManager;

    /** 真实上下文持有者：本测试直接往里装载上下文。 */
    private RunContextHolder runContexts;

    /** 被测执行器。 */
    private ToolExecutor executor;

    /** 处理器收到的请求。 */
    private ToolCallRequest captured;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        runContexts = new RunContextHolder();
        executor = new ToolExecutor(permissionManager, extensions, events,
                new ToolOutputLimiter(runtimeConfig, store), runContexts);
        lenient().when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow("测试放行"));
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        extensions.handle("test", ToolCallRequest.class, "probe", null,
                new ExtensionHandler<ToolCallRequest, ToolCallResult>() {

                    @Override
                    public ToolCallResult handle(ToolCallRequest request) {
                        captured = request;
                        return new ToolCallResult("probe", "ok");
                    }
                }, RegisterOptions.DEFAULT);
    }

    @Test
    @DisplayName("不在任何回合里：三个字段都是 null，不抛异常")
    void outsideTurn_shouldCarryNoIdentity() {
        // Given：没有装载任何上下文
        Session session = sessionManager.createDefault();

        // When
        executor.execute(session, null, "c1", "probe", Collections.<String, Object>emptyMap(), null);

        // Then
        assertNull(captured.getParentSessionId(), "根会话没有父会话");
        assertNull(captured.getRunId(), "不在 run 上就没有 run 身份");
        assertNull(captured.getRootRunId(), "不在 run 上就没有树根身份");
        assertEquals(session.getSessionId(), captured.getSessionId());
    }

    @Test
    @DisplayName("顶层回合：会话仍是根会话，因此没有父会话也没有 run 身份")
    void inTopLevelTurn_shouldCarryNoRunIdentity() {
        // Given
        Session session = sessionManager.createDefault();
        runContexts.open(1, 3);

        // When
        executor.execute(session, null, "c1", "probe", Collections.<String, Object>emptyMap(), null);

        // Then
        assertNull(captured.getParentSessionId());
        assertNull(captured.getRunId());
        assertNull(captured.getRootRunId());
    }

    @Test
    @DisplayName("子 run 里：父会话来自会话、run 身份来自上下文")
    void insideRun_shouldCarryParentSessionAndRunIdentity() {
        // Given：一个顶层回合派出了一个子 run（子会话 + 该 run 的上下文）
        Session parent = sessionManager.createDefault();
        runContexts.open(1, 3);
        Session child = sessionManager.createEphemeral(parent.getSessionId(), "worker", null, null);
        runContexts.set(new RunContext(new RunTree(1, 3, 0L, 0L), 1, "run-1", "root-1", null));

        // When
        executor.execute(child, null, "c1", "probe", Collections.<String, Object>emptyMap(), null);

        // Then
        assertEquals(child.getSessionId(), captured.getSessionId(), "会话标识是子代理自己的");
        assertEquals(parent.getSessionId(), captured.getParentSessionId(), "父会话是要共享出去的那个键");
        assertEquals("run-1", captured.getRunId());
        assertEquals("root-1", captured.getRootRunId());
    }
}
