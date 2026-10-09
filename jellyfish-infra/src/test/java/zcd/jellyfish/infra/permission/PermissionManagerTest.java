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
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.infra.config.PermissionApprovalSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionManager} 的单元测试：逐条钉住判定矩阵与 fail-open 的适用域。
 * <p>
 * 扩展层用<b>真实实例</b>（真实注册表 + 真实派发），因此短路、顺序、异常处置这些编排语义
 * 都是端到端验证的；只有策略来源与事件发布这两个接口被 mock：
 * 前者将来由 {@code AgentManager} 实现，后者是外部协作方。
 * <p>
 * 按模式收窄的授权（例如「只跑只读工具」）不在这里测：那是插件用同一个类型级扩展点表达的一条普通拦截，
 * 内核侧只保证「插件能收紧、不能放宽」——它由下面这些用例覆盖，策略本身归对应插件自己的测试。
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

    /** 运行时配置，只用到其中的审批超时。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 真实的同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 真实的审批通道：本测试关注的是判定编排，不是通道自身的并发语义。 */
    private ApprovalChannel channel;

    /** 被测对象。 */
    private PermissionManager manager;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        channel = new ApprovalChannel();
        manager = new PermissionManager(policies, extensions, events, channel, runtimeConfig);
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
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard = request -> {
            intercepted.incrementAndGet();
            return PermissionVerdict.deny("插件也要拦");
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
    void decide_should_deny_when_policy_requires_approval_and_no_approver() {
        // Given：策略要求审批，但没挂审批者（-cli / -server 的情形）
        stubApprovalTimeout();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then：审批者缺席只能拒绝，绝不降级为放行
        assertTrue(decision.isDenied());
        assertFalse(decision.isAsk());
        assertTrue(decision.getReason().contains("agent 策略要求人工审批该工具"), decision.getReason());
        assertTrue(decision.getReason().contains(ApprovalChannel.NO_APPROVER), decision.getReason());
    }

    @Test
    void decide_should_allow_when_approval_is_granted() {
        // Given：策略要求审批，审批者给了批准
        requireApproval();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));
        answerApproval(true);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then：ASK 不作为终态外泄，拿到的就是放行
        assertTrue(decision.isAllowed());
        assertFalse(decision.isAsk());
        assertTrue(decision.getReason().contains("用户已批准"), decision.getReason());
    }

    @Test
    void decide_should_deny_when_approval_is_rejected() {
        // Given
        requireApproval();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));
        answerApproval(false);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then
        assertTrue(decision.isDenied());
        assertTrue(decision.getReason().contains("用户已拒绝"), decision.getReason());
    }

    @Test
    void decide_should_attribute_audit_to_approval_when_policy_requires_approval() {
        // Given
        requireApproval();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));
        answerApproval(true);

        // When
        manager.decide(new PermissionCheckRequest("agent-a", "deploy", null, "session-1"));

        // Then：审计要能一眼分出「策略直接放行」与「有人在审批框上点了批准」
        PermissionDecidedEvent event = captureEvent();
        assertEquals(PermissionManager.APPROVAL_SOURCE, event.getSource());
        assertEquals(PermissionDecision.Outcome.ALLOW, event.getOutcome());
        assertEquals("session-1", event.getSessionId());
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
    void decide_should_deny_and_short_circuit_when_plugin_intercepts() {
        // Given：两个拦截插件，order 靠前的先执行
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard = request -> PermissionVerdict.deny("危险命令");
        AtomicInteger tailCalls = new AtomicInteger();
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> tail = request -> {
            tailCalls.incrementAndGet();
            return PermissionVerdict.deny("后面的拦截");
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
    void decide_should_deny_when_interceptor_throws() {
        // Given：核心策略放行，但那条按模式收窄的拦截自己抛了错
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> broken = request -> {
            throw new IllegalStateException("脚本超时");
        };
        extensions.contribute("plan-guard", PermissionCheckRequest.class, null, broken, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "write_file", null));

        // Then：按拒绝——「没能表态」不能等价于「无异议」，否则这道收窄会静默消失且不留痕迹
        assertTrue(decision.isDenied());
        assertTrue(decision.getReason().contains("plan-guard"), decision.getReason());
        assertEquals("plan-guard", captureEvent().getSource());
    }

    @Test
    void decide_should_deny_when_interceptor_returnsNull() {
        // Given：处理器坏了——契约要求返回三态之一，它什么都不返回
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> silent = request -> null;
        extensions.contribute("silent-guard", PermissionCheckRequest.class, null, silent, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "write_file", null));

        // Then：同样按拒绝，「没给结论」不是「没意见」
        assertTrue(decision.isDenied());
        assertTrue(decision.getReason().contains("silent-guard"), decision.getReason());
    }

    @Test
    void decide_should_deny_and_attribute_to_plugin_when_core_requires_approval() {
        // Given：核心要求审批，插件也拦截
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("deploy"), null));
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard = request -> PermissionVerdict.deny("插件拦截");
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "deploy", null));

        // Then：插件拦截优先于 ASK 降级，理由保持插件原文
        assertTrue(decision.isDenied());
        assertEquals("插件拦截", decision.getReason());
        assertEquals("guard", captureEvent().getSource());
    }

    @Test
    void decide_should_route_plugin_ask_to_approval() {
        // Given：插件把一道自己拦不住的调用升级为人工审批，但没有人在场
        stubApprovalTimeout();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard =
                request -> PermissionVerdict.ask("写类命令需要人看一眼");
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "shell", null));

        // Then：插件的 ASK 只是「更严」，最终仍受 fail-closed 约束
        assertTrue(decision.isDenied());
        assertTrue(decision.getReason().contains("写类命令需要人看一眼"), decision.getReason());
        assertTrue(decision.getReason().contains(ApprovalChannel.NO_APPROVER), decision.getReason());
    }

    @Test
    void decide_should_allow_when_plugin_asks_and_approval_is_granted() {
        // Given
        requireApproval();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard =
                request -> PermissionVerdict.ask("需要审批");
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);
        answerApproval(true);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "shell", null));

        // Then：升级为审批之后走的就是与核心策略 ASK 完全相同的那条路
        assertTrue(decision.isAllowed());
        assertEquals(PermissionManager.APPROVAL_SOURCE, captureEvent().getSource());
    }

    @Test
    void decide_should_wait_forever_and_still_answer_when_approval_timeout_is_zero() {
        // Given：approvalTimeoutSeconds=0 表示永不超时——判定仍然要等人裁决，只是不再有等待上限
        when(runtimeConfig.getPermissionApprovalSettings())
                .thenReturn(new PermissionApprovalSettings(PermissionApprovalSettings.INFINITE_TIMEOUT_SECONDS));
        channel.attach();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard =
                request -> PermissionVerdict.ask("需要审批");
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);
        answerApproval(true);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "shell", null));

        // Then：无限等待同样在被裁决时收敛，判定结果与有超时时一致
        assertTrue(decision.isAllowed());
        assertEquals(PermissionManager.APPROVAL_SOURCE, captureEvent().getSource());
    }

    @Test
    void decide_should_let_later_deny_win_over_earlier_ask() {
        // Given：order 靠前的插件 ASK、靠后的插件 DENY
        // 刻意不 stub 审批超时：若 DENY 没短路，后面的审批路径会因为拿不到超时配置而直接报错
        channel.attach();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> asker =
                request -> PermissionVerdict.ask("想升级为审批");
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> denier =
                request -> PermissionVerdict.deny("但后面这个直接拒绝");
        extensions.contribute("asker", PermissionCheckRequest.class, null, asker, RegisterOptions.order(-1));
        extensions.contribute("denier", PermissionCheckRequest.class, null, denier, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "shell", null));

        // Then：取最严，DENY 短路，审批通道根本不该被问起
        assertTrue(decision.isDenied());
        assertEquals("但后面这个直接拒绝", decision.getReason());
        assertEquals("denier", captureEvent().getSource());
        assertFalse(channel.pending().isPresent());
    }

    @Test
    void decide_should_keep_first_reason_when_two_plugins_ask() {
        // Given：两个插件都要求审批
        stubApprovalTimeout();
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> first =
                request -> PermissionVerdict.ask("先到的理由");
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> second =
                request -> PermissionVerdict.ask("后到的理由");
        extensions.contribute("first", PermissionCheckRequest.class, null, first, RegisterOptions.order(-1));
        extensions.contribute("second", PermissionCheckRequest.class, null, second, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "shell", null));

        // Then：同为 ASK 时保留先到者，审计里的理由才不会随插件顺序变化；
        // 而「因 ASK 走了审批」这件事仍然如实反映在审计来源上
        assertTrue(decision.getReason().contains("先到的理由"), decision.getReason());
        assertFalse(decision.getReason().contains("后到的理由"), decision.getReason());
        assertEquals(PermissionManager.APPROVAL_SOURCE, captureEvent().getSource());
    }

    @Test
    void decide_should_ignore_plugin_veto_that_is_not_denied() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());
        ExtensionHandler<PermissionCheckRequest, PermissionVerdict> guard = request -> PermissionVerdict.abstain();
        extensions.contribute("guard", PermissionCheckRequest.class, null, guard, RegisterOptions.DEFAULT);

        // When
        PermissionDecision decision = manager.decide(new PermissionCheckRequest("agent-a", "bash", null));

        // Then
        assertTrue(decision.isAllowed());
        assertEquals(PermissionManager.CORE_SOURCE, captureEvent().getSource());
    }

    @Test
    void decide_should_publish_audit_event_even_when_allowed() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.unrestricted());

        // When
        manager.decide(new PermissionCheckRequest("agent-a", "read_file", null, "session-1"));

        // Then
        PermissionDecidedEvent event = captureEvent();
        assertEquals(PermissionDecision.Outcome.ALLOW, event.getOutcome());
        assertEquals("agent-a", event.getAgentId());
        assertEquals("read_file", event.getToolName());
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
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(null, extensions, events, channel, runtimeConfig));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, null, events, channel, runtimeConfig));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, extensions, null, channel, runtimeConfig));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, extensions, events, null, runtimeConfig));
        assertThrows(NullPointerException.class,
                () -> new PermissionManager(policies, extensions, events, channel, null));
    }

    @Test
    void usableTools_should_narrow_to_allow_list() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, null, toolSet("read_file")));
        Predicate<String> usable = manager.usableTools("agent-a");

        // When / Then：与执行期同一个判据——清单里出现、执行时却被拒会让模型白跑一轮
        assertTrue(usable.test("read_file"));
        assertFalse(usable.test("bash"));
    }

    @Test
    void usableTools_should_drop_denied_tool() {
        // Given
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(toolSet("bash"), null, null));
        Predicate<String> usable = manager.usableTools("agent-a");

        // When / Then
        assertFalse(usable.test("bash"));
        assertTrue(usable.test("read_file"));
    }

    @Test
    void usableTools_should_keep_tool_that_only_requires_approval() {
        // Given：ASK 说明工具是可用的，只是要人点一下批准；从清单里拿掉会让
        // 「只读免打扰、写类要审批」这套配置直接失效
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("bash"), null));
        Predicate<String> usable = manager.usableTools("agent-a");

        // When / Then
        assertTrue(usable.test("bash"));
    }

    @Test
    void usableTools_should_be_pure_without_audit_or_approval() {
        // Given：一个要求审批的工具
        when(policies.policyOf("agent-a")).thenReturn(PermissionPolicy.of(null, toolSet("bash"), null));
        Predicate<String> usable = manager.usableTools("agent-a");

        // When：清单过滤是每轮组装都会跑的路径，绝不能弹审批框或刷审计事件
        usable.test("bash");
        usable.test("bash");

        // Then
        verify(events, never()).publish(any());
    }

    @Test
    void usableTools_should_allow_when_no_policy() {
        // Given：未绑定 agent 时取不到策略，按 fail-open 全放行
        when(policies.policyOf(null)).thenReturn(PermissionPolicy.unrestricted());

        // When / Then
        assertTrue(manager.usableTools(null).test("anything"));
    }

    /**
     * 只声明审批超时，不挂审批者。
     */
    private void stubApprovalTimeout() {
        when(runtimeConfig.getPermissionApprovalSettings()).thenReturn(new PermissionApprovalSettings(5));
    }

    /**
     * 声明审批超时并挂上审批者：超时只需要长于辅助线程的轮询间隔。
     */
    private void requireApproval() {
        stubApprovalTimeout();
        channel.attach();
    }

    /**
     * 启动一个辅助线程，对下一条挂起的审批请求给出结论。
     * <p>
     * 真实形态是「渲染线程每帧取件」，这里用轮询代替渲染循环：请求挂上后立刻裁决。
     *
     * @param approved 是否批准
     */
    private void answerApproval(boolean approved) {
        Thread answer = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                Optional<ApprovalChannel.Pending> pending = channel.pending();
                if (pending.isPresent()) {
                    channel.resolve(pending.get().getId(), approved);
                    return;
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "approval-answer");
        answer.setDaemon(true);
        answer.start();
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
