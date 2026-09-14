package zcd.jellyfish.infra.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVeto;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionManager} 的单元测试：逐条钉住判定矩阵与 fail-open 的适用域。
 * <p>
 * 扩展层用<b>真实实例</b>（真实注册表 + 真实派发），因此短路、顺序、异常处置这些编排语义
 * 都是端到端验证的；只有策略来源与事件发布这两个接口被 mock：
 * 前者将来由 {@code AgentManager} 实现，后者是外部协作方。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class PermissionManagerTest {

    /** 策略来源，将来由 AgentManager 实现。 */
    @Mock
    private PermissionPolicyProvider policies;

    /** 审计事件发布入口。 */
    @Mock
    private EventPublisher events;

    /** 真实的同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 被测对象。 */
    private PermissionManager manager;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        useReadOnlyTools();
    }

    @Test
    void decide_should_allow_when_policy_is_absent() {
        // Given：无策略即 fail-open
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isAllowed());
    }

    @Test
    void decide_should_deny_and_skip_interception_when_policy_denies() {
        // Given：策略显式拒绝，同时有一个只想拦截的插件
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(toolSet("bash"), null, null));
        AtomicInteger intercepted = new AtomicInteger();
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> guard = request -> {
            intercepted.incrementAndGet();
            return PermissionVeto.deny("插件也要拦");
        };
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then：核心已拒绝，插件不应被调用
        assertTrue(decision.isDenied());
        assertEquals("agent 策略显式拒绝该工具", decision.getReason());
        assertEquals(0, intercepted.get());
    }

    @Test
    void decide_should_degrade_ask_to_deny_when_policy_requires_approval() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then：审批通道未落地，只能降级为拒绝，绝不降级为放行
        assertTrue(decision.isDenied());
        assertFalse(decision.isAsk());
        assertTrue(decision.getReason().contains("agent 策略要求人工审批该工具"));
        assertTrue(decision.getReason().contains("审批通道未落地"));
    }

    @Test
    void decide_should_deny_when_tool_outside_allow_list() {
        // Given：允许集合非空即收窄
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, null, toolSet("read_file")));

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isDenied());
        assertEquals("工具不在 agent 允许范围内", decision.getReason());
    }

    @Test
    void decide_should_allow_read_only_tool_in_plan_mode() {
        // Given
        useReadOnlyTools("read_file");
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "read_file", null,
                PermissionMode.PLAN, null));

        // Then
        assertTrue(decision.isAllowed());
    }

    @Test
    void decide_should_deny_non_read_only_tool_in_plan_mode() {
        // Given
        useReadOnlyTools("read_file");
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "write_file", null,
                PermissionMode.PLAN, null));

        // Then
        assertTrue(decision.isDenied());
        assertEquals("PLAN 模式仅允许只读工具", decision.getReason());
    }

    @Test
    void decide_should_deny_every_tool_in_plan_mode_when_read_only_set_is_empty() {
        // Given：没有任何插件声明只读工具，属「策略已生效但集合为空」，不是「取不到策略」
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "read_file", null,
                PermissionMode.PLAN, null));

        // Then
        assertTrue(decision.isDenied());
    }

    @Test
    void decide_should_deny_and_short_circuit_when_plugin_intercepts() {
        // Given：两个拦截插件，order 靠前的先执行
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> guard = request -> PermissionVeto.deny("危险命令");
        AtomicInteger tailCalls = new AtomicInteger();
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> tail = request -> {
            tailCalls.incrementAndGet();
            return PermissionVeto.deny("后面的拦截");
        };
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.order(-1));
        extensions.contribute("tail", PermissionCheckRequest.class, null, tail, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then：取第一个拦截结果并短路
        assertTrue(decision.isDenied());
        assertEquals("危险命令", decision.getReason());
        assertEquals(0, tailCalls.get());
        assertEquals("guard", captureEvent().getSource());
    }

    @Test
    void decide_should_deny_and_attribute_to_plugin_when_core_requires_approval() {
        // Given：核心要求审批（会降级为拒绝），插件也拦截
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> guard = request -> PermissionVeto.deny("插件拦截");
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then：插件拦截优先于 ASK 降级，理由保持插件原文
        assertTrue(decision.isDenied());
        assertEquals("插件拦截", decision.getReason());
        assertEquals("guard", captureEvent().getSource());
    }

    @Test
    void decide_should_ignore_plugin_veto_that_is_not_denied() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> guard = request -> PermissionVeto.none();
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isAllowed());
        assertEquals(PermissionManager.CORE_SOURCE, captureEvent().getSource());
    }

    @Test
    void decide_should_ignore_null_veto_result() {
        // Given：处理器允许返回 null（ExtensionRegistry 不做结果强制）
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> guard = request -> null;
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isAllowed());
    }

    @Test
    void decide_should_ignore_plugin_failure() {
        // Given：一个插件抛异常不应该让整条调用链崩掉
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVeto> broken = request -> {
            throw new IllegalStateException("boom");
        };
        extensions.contribute("broken", PermissionCheckRequest.class, null, broken, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isAllowed());
    }

    @Test
    void decide_should_publish_audit_event_even_when_allowed() {
        // Given
        useReadOnlyTools("read_file");
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        manager.decide(new PermissionCheckRequest("agent-a", "read_file", null, PermissionMode.PLAN, "session-1"));

        // Then
        PermissionDecidedEvent event = captureEvent();
        assertEquals(PermissionDecision.Outcome.ALLOW, event.getOutcome());
        assertEquals("agent-a", event.getAgentId());
        assertEquals("read_file", event.getToolName());
        assertEquals(PermissionMode.PLAN, event.getMode());
        assertEquals("session-1", event.getSessionId());
        assertEquals(PermissionManager.CORE_SOURCE, event.getSource());
    }

    @Test
    void decide_should_return_decision_when_audit_publish_fails() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        doThrow(new IllegalStateException("bus down")).when(events).publish(any(PermissionDecidedEvent.class));

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "read_file", null));

        // Then：审计失败只是记账，不改变判定
        assertTrue(decision.isAllowed());
    }

    @Test
    void decide_should_reject_null_request() {
        // When / Then
        assertThrows(NullPointerException.class, () -> manager.decide(null));
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        // When / Then
        ReadOnlyTools readOnlyTools = readOnlyToolsOf();
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(null, readOnlyTools, extensions, events));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, null, extensions, events));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, readOnlyTools, null, events));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, readOnlyTools, extensions, null));
    }

    /**
     * 用指定的只读工具集合重建被测对象。
     *
     * @param toolNames 声明为只读的工具名，可为空
     */
    private void useReadOnlyTools(String... toolNames) {
        manager = new PermissionManager(policies, readOnlyToolsOf(toolNames), extensions, events);
    }

    /**
     * 构造只读工具集合：模拟某个插件在配置里声明白名单。
     *
     * @param toolNames 声明为只读的工具名，可为空
     * @return 只读工具集合
     */
    private static ReadOnlyTools readOnlyToolsOf(String... toolNames) {
        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put(PermissionSettings.READ_ONLY_TOOLS, Arrays.asList(toolNames));
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        configurations.put("readonly-plugin", pluginConfig);
        // 告警在本测试里不是关注点，用空实现避免噪音
        return new ReadOnlyTools(new PluginRuntimeConfig(null, null, null, configurations), event -> {
        });
    }

    /**
     * 捕获唯一一条审计事件。
     *
     * @return 审计事件
     */
    private PermissionDecidedEvent captureEvent() {
        ArgumentCaptor<PermissionDecidedEvent> captor = ArgumentCaptor.forClass(PermissionDecidedEvent.class);
        verify(events).publish(captor.capture());
        return captor.getValue();
    }

    /**
     * 构造工具名集合。
     *
     * @param toolNames 工具名
     * @return 集合
     */
    private static Set<String> toolSet(String... toolNames) {
        return new LinkedHashSet<>(Arrays.asList(toolNames));
    }
}
