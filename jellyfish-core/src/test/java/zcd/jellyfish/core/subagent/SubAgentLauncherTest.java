package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.runtime.RunContext;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.core.runtime.RunRegistry;
import zcd.jellyfish.core.runtime.RunScheduler;
import zcd.jellyfish.core.prompt.ToolFilter;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
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

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubAgentLauncher} 的单元测试：验证准入判定「不产生任何副作用」、派生与收尾的配对、
 * 用量归集，以及终态到 {@link SubAgentStatus} 的映射。
 * <p>
 * 用真实 {@link SessionManager} 与 {@link RunContextHolder}，只 mock 外部协作者与嵌套回合的执行体
 * （后者是 {@link ReActLooper#runNested}，本类的职责只是把它串起来）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentLauncherTest {

    /** 可委派的 agent 标识。 */
    private static final String SCOUT = "scout";

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 会话模型解析器。 */
    @Mock
    private SessionModelResolver sessionModelResolver;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** ReAct 循环器：本类只验证调用参数，用 mock 截断真正的大循环。 */
    @Mock
    private ReActLooper reActLooper;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 权限管理器：本类用它给出子代理的工具清单过滤。 */
    @Mock
    private PermissionManager permissionManager;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 真实委派作用域持有者。 */
    private RunContextHolder runContexts;

    /** 真实 agent run 门面（登记 + 终结本次委派）。 */
    private AgentRuntime runtime;

    /** 被测对象。 */
    private SubAgentLauncher launcher;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()),
                new SessionDefaults());
        runContexts = new RunContextHolder();
        // 调度器在构造时就要读设置，因此先打桩再建它
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        RunRegistry registry = new RunRegistry();
        RunScheduler scheduler = new RunScheduler(runContexts, registry, runtimeConfig);
        runtime = new AgentRuntime(registry, runContexts, scheduler);
        launcher = new SubAgentLauncher(sessionManager, agentManager, sessionModelResolver, runtimeConfig,
                reActLooper, runContexts, permissionManager, runtime);
        // 默认设置对所有用例都一样，个别用例自己覆盖
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        // 默认不过滤工具（无策略即全放行），个别用例自己覆盖
        lenient().when(permissionManager.usableTools(any(), any())).thenReturn(toolName -> true);
    }

    @Test
    void run_should_reject_when_disabled() {
        // Given
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null, null, null));
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：连 agent 都不该去查
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("禁用"));
        verify(agentManager, never()).find(any());
    }

    @Test
    void run_should_reject_when_no_active_scope() {
        // Given：不在任何回合里（没有作用域）
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("没有进行中的回合"));
        verify(reActLooper, never()).runNested(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void run_should_reject_when_depth_limit_exhausted() {
        // Given：maxDepth = 0 表示禁止委派
        runContexts.open(0, 8);
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("层数上限"));
    }

    @Test
    void run_should_reject_when_spawn_budget_exhausted() {
        // Given：预算只有 1，已经被用掉
        runContexts.open(8, 1);
        runContexts.current().tryAcquireSpawn();
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：理由要指向预算而不是深度——两者的指导动作不同
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("达到上限 1"));
    }

    @Test
    void run_should_reject_when_agent_unknown_and_list_available() {
        // Given：模型瞎猜了一个类型
        when(agentManager.all()).thenReturn(Arrays.asList(definition(true), definitionOf("writer", false)));
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, "ghost", "查一下"), null);

        // Then：提示里要带上「有哪些可用」，否则模型只能继续瞎猜
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("未知的子代理类型：ghost"));
        assertTrue(outcome.getError().contains(SCOUT), outcome.getError());
        assertFalse(outcome.getError().contains("writer"), outcome.getError());
    }

    @Test
    void run_should_reject_when_agent_not_delegatable() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definitionOf(SCOUT, false));
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("delegatable"));
    }

    @Test
    void run_should_reject_when_delegating_to_itself() {
        // Given：父会话绑的就是 scout
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, SCOUT);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：委派给自己等于把同一件事再说一遍，只会烧钱
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("自己"));
    }

    @Test
    void run_should_reject_when_prompt_blank() {
        // Given：子代理看不到本次对话，空任务等于让它自由发挥
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "   "), null);

        // Then
        assertEquals(SubAgentStatus.REJECTED, outcome.getStatus());
        assertTrue(outcome.getError().contains("任务描述不能为空"));
    }

    @Test
    void run_should_fail_when_model_unresolvable() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT))
                .thenThrow(new JellyfishException("model not found: openai/ghost"));
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：失败且没有留下任何子会话（模型校验排在建会话之前）
        assertEquals(SubAgentStatus.FAILED, outcome.getStatus());
        assertTrue(outcome.getError().contains("model not found"));
        assertEquals(1, sessionManager.all().size());
        verify(reActLooper, never()).runNested(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void run_should_return_completed_and_close_child_session() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        stubNestedTurn("子代理答复", 2);
        ArgumentCaptor<Session> childCaptor = ArgumentCaptor.forClass(Session.class);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：结果透传，轮数与用量一并带出
        assertEquals(SubAgentStatus.COMPLETED, outcome.getStatus());
        assertEquals("子代理答复", outcome.getText());
        assertEquals(2, outcome.getRounds());
        assertTrue(outcome.hasText());

        // And：子会话是瞬时的（带父会话标识、不进会话列表），且返回前已被关闭
        verify(reActLooper).runNested(childCaptor.capture(), eq("查一下"), any(), any(), eq(8), any());
        Session child = childCaptor.getValue();
        assertEquals(parent.getSessionId(), child.getParentSessionId());
        assertThrows(JellyfishException.class, () -> sessionManager.require(child.getSessionId()));
        assertEquals(1, sessionManager.all().size());
    }

    @Test
    void run_should_inherit_parent_permission_mode_and_not_model() {
        // Given：父会话是 PLAN（只读）
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.PLAN, null);
        stubNestedTurn("答复", 1);
        ArgumentCaptor<Session> childCaptor = ArgumentCaptor.forClass(Session.class);

        // When
        launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：权限模式必须继承（子代理不该比派它的会话更宽松），
        // 而 provider / model 留空——它们由子代理自己的 agent 定义决定，不继承父会话
        verify(reActLooper).runNested(childCaptor.capture(), any(), any(), any(), anyInt(), any());
        Session child = childCaptor.getValue();
        assertEquals(PermissionMode.PLAN, child.getPermissionMode());
        assertNull(child.getProvider());
        assertNull(child.getModel());
    }

    @Test
    void run_should_use_sub_agent_max_rounds() {
        // Given：子代理的轮数上限与主会话不同
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(null, null, null, 3, null, null, null, null));
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        stubNestedTurn("答复", 1);

        // When
        launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        verify(reActLooper).runNested(any(), any(), any(), any(), eq(3), any());
    }

    @Test
    void run_should_forward_child_usage_to_parent() {
        // Given：子代理回合花掉了真实 token
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        stubNestedTurn("答复", 2);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：账要算在父会话头上。注意每次 appendMessage 本身就会推进调用计数（既有语义），
        // 因此子会话两条消息 = 2 次；关键是它没有被压成「1 次」
        assertEquals(12L, outcome.getUsage().getTotalTokens());
        assertEquals(2L, outcome.getUsage().getLlmCalls());
        assertEquals(12L, parent.getUsage().getTotalTokens());
        assertEquals(2L, parent.getUsage().getLlmCalls());
    }

    @Test
    void run_should_map_truncated_result() {
        // Given：子代理达到自己的轮数上限
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenReturn(ReActResult.truncated("s-1", "已达上限", 8));

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：截断也算「有文本」——模型据此知道任务没做完，而不是以为子代理没说话
        assertEquals(SubAgentStatus.TRUNCATED, outcome.getStatus());
        assertEquals("已达上限", outcome.getText());
        assertTrue(outcome.hasText());
    }

    @Test
    void run_should_append_last_assistant_text_when_truncated() {
        // Given：子代理跑到轮数上限，且最后一轮停在工具调用上（那条助手消息没有正文）
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Session child = invocation.getArgument(0);
                    String childId = child.getSessionId();
                    sessionManager.appendMessage(childId, LlmMessage.user("只属于任务原文的标记"), null);
                    sessionManager.appendMessage(childId, LlmMessage.assistant("我先读一下入口文件"), null);
                    sessionManager.appendMessage(childId, LlmMessage.tool("call_1", "read_file", "只属于工具结果的标记"),
                            null);
                    sessionManager.appendMessage(childId, LlmMessage.assistant(null), null);
                    return ReActResult.truncated(childId, "已达上限", 3);
                });

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：主会话拿到的不能只有一句通知，还要有子代理最后说的那段话
        assertEquals(SubAgentStatus.TRUNCATED, outcome.getStatus());
        assertTrue(outcome.getText().startsWith("已达上限"), "内核提示要留在最前面");
        assertTrue(outcome.getText().contains("我先读一下入口文件"), "最后一段正文要跟着回灌");
        // 任务原文与工具回显是子代理看到的，不是它说的
        assertFalse(outcome.getText().contains("只属于任务原文的标记"));
        assertFalse(outcome.getText().contains("只属于工具结果的标记"));
    }

    @Test
    void run_should_keep_hint_only_when_truncated_without_assistant_text() {
        // Given：子代理光顾着调工具，一句正文都没写过
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Session child = invocation.getArgument(0);
                    String childId = child.getSessionId();
                    sessionManager.appendMessage(childId, LlmMessage.user("任务"), null);
                    return ReActResult.truncated(childId, "已达上限", 1);
                });

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：没有正文可附时不要为「空内容」另编一句说明
        assertEquals("已达上限", outcome.getText());
    }

    @Test
    void run_should_map_cancelled_result() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenReturn(ReActResult.cancelled("s-1", 1));

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        assertEquals(SubAgentStatus.CANCELLED, outcome.getStatus());
        assertFalse(outcome.hasText());
        assertEquals(1, outcome.getRounds());
    }

    @Test
    void run_should_return_failed_and_still_close_child_when_nested_throws() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        when(reActLooper.runNested(any(Session.class), any(), any(), any(), anyInt(), any()))
                .thenThrow(new JellyfishException("网络断了"));
        ArgumentCaptor<Session> childCaptor = ArgumentCaptor.forClass(Session.class);

        // When
        SubAgentOutcome outcome = launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：失败也要把子会话收干净，否则每失败一次就漏一个会话
        assertEquals(SubAgentStatus.FAILED, outcome.getStatus());
        assertTrue(outcome.getError().contains("网络断了"));
        verify(reActLooper).runNested(childCaptor.capture(), any(), any(), any(), anyInt(), any());
        assertThrows(JellyfishException.class, () -> sessionManager.require(childCaptor.getValue().getSessionId()));
    }

    @Test
    void run_should_count_spawn_against_budget() {
        // Given
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        stubNestedTurn("答复", 1);

        // When
        launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then
        assertEquals(1, runContexts.current().getSpawnCount());
        assertEquals(0, runContexts.current().getDepth());
    }

    @Test
    void run_should_filter_tools_by_child_agent_policy() {
        // Given：子代理的 agent 配置只允许只读工具
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        when(permissionManager.usableTools(eq(SCOUT), any())).thenReturn("read_file"::equals);
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.NORMAL, null);
        stubNestedTurn("答复", 1);
        ArgumentCaptor<ToolFilter> filterCaptor = ArgumentCaptor.forClass(ToolFilter.class);

        // When
        launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：过滤器要真的按那份策略收窄，而不是一个全放行的空壳——
        // 否则子代理会看到 write_file、调用它、被拒，白跑一轮
        verify(reActLooper).runNested(any(), any(), any(), any(), anyInt(), filterCaptor.capture());
        assertTrue(filterCaptor.getValue().accepts("read_file"));
        assertFalse(filterCaptor.getValue().accepts("write_file"));
    }

    @Test
    void run_should_compute_filter_with_parent_permission_mode() {
        // Given：父会话处于 PLAN 模式（权限模式继承给子代理）
        when(agentManager.find(SCOUT)).thenReturn(definition(true));
        when(sessionModelResolver.resolveByAgentOrDefault(SCOUT)).thenReturn(resolvedModel());
        runContexts.open(8, 8);
        Session parent = parent(PermissionMode.PLAN, null);
        stubNestedTurn("答复", 1);

        // When
        launcher.run(call(parent, SCOUT, "查一下"), null);

        // Then：PLAN 会把清单额外收窄到只读工具，判据必须按 PLAN 算
        verify(permissionManager).usableTools(eq(SCOUT), eq(PermissionMode.PLAN));
    }

    /**
     * 构造一个父会话。
     *
     * @param mode    权限模式
     * @param agentId 绑定的 agentId，可为 {@code null}
     * @return 父会话运行态
     */
    private Session parent(PermissionMode mode, String agentId) {
        return sessionManager.create(agentId == null ? "jellyfish" : agentId, null, null, mode);
    }

    /**
     * 构造一次委派请求。
     *
     * @param parent  父会话
     * @param agentId 目标 agent 标识
     * @param prompt  任务原文
     * @return 委派请求
     */
    private static SubAgentCall call(Session parent, String agentId, String prompt) {
        return new SubAgentCall(parent.getSessionId(), agentId, prompt, null);
    }

    /**
     * 桩：让嵌套回合在子会话里留下两条消息（后者带用量）并返回完成结果。
     * <p>
     * 只在 stub 里写子会话，不碰父会话——用量归集正是被测行为。
     *
     * @param text   子代理最终文本
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

    /**
     * 构造可委派的 agent 定义。
     *
     * @param delegatable 是否可委派
     * @return agent 定义
     */
    private static AgentDefinition definition(boolean delegatable) {
        return definitionOf(SCOUT, delegatable);
    }

    /**
     * 构造带指定标识的 agent 定义。
     *
     * @param agentId     agent 标识
     * @param delegatable 是否可委派
     * @return agent 定义
     */
    private static AgentDefinition definitionOf(String agentId, boolean delegatable) {
        return new AgentDefinition(agentId, "侦察", null, delegatable, null);
    }

    /**
     * 构造一个解析结果。
     *
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel() {
        return new ResolvedModel(new Provider("openai", "openai", null, null, null),
                new Model("gpt-4o", "gpt-4o", 0, 0));
    }
}
