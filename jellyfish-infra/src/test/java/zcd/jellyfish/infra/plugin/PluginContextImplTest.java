package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.action.ActionFailureReason;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.extension.ShellContributionStatus;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PluginContextImpl} 的单元测试：验证四个注册/发布入口都绑定 {@code pluginId}。
 * <p>
 * 这里使用真实的注册表与事件通道（同步执行器）而非 mock：本类的职责只是把 {@code pluginId} 作为 owner
 * 透传下去，用真实组件可以顺带验证注册与订阅确实落到了同一份 {@link TypeRegistry} 上。
 *
 * @author zcd
 */
class PluginContextImplTest {

    /** 共用注册表。 */
    private final TypeRegistry typeRegistry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(typeRegistry);

    /** 事件通道：用默认线程池，测试以闩锁等待异步投递。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), typeRegistry);

    /** 会话域服务：桩，只为满足插件上下文的构造。 */
    private final SessionManager sessions = Mockito.mock(SessionManager.class);

    /** 被测插件上下文。 */
    private final PluginContextImpl context = new PluginContextImpl(
            PluginDeclaration.of("plugin-a"), extensions, events, sessions);

    @Test
    void pluginId_should_come_from_declaration() {
        // Then
        assertEquals("plugin-a", context.pluginId());
    }

    @Test
    void delegations_should_expose_the_injected_port() {
        // Given：装配方注入了一个具体端口
        SubAgentPort port = request -> DelegationHandle.settled(
                DelegationResult.completed("run-1", "结论", 1, 1L));

        // When / Then：插件拿到的就是它
        assertSame(port, contextWithDelegations(port).delegations());
    }

    @Test
    void delegations_should_defaultToUnavailable() {
        // 默认构造（不完整装配、单测）不给 null 也不抛异常：
        // 插件照旧 spawn → await，只会在结果里看到 REJECTED
        assertEquals(DelegationStatus.REJECTED,
                context.delegations().spawn(DelegationRequest.of("s-1", "scout", "查一下")).await().getStatus());
    }

    @Test
    void subContext_should_share_the_same_port() {
        // 端口是进程级能力，子单元与父单元看到的必须是同一个——
        // 否则「插件把自己注册成两个 owner」会得到两套委派行为
        SubAgentPort port = SubAgentPort.unavailable();
        PluginContext parent = contextWithDelegations(port);

        assertSame(port, parent.delegations());
        assertSame(port, parent.subContext("jira").delegations());
    }

    @Test
    void subContext_should_deriveChildIdentity_when_childIdIsValid() {
        // When
        PluginContextImpl child = (PluginContextImpl) context.subContext("jira");

        // Then：身份是「父身份 + 分隔符 + 子标识」，子上下文不再是根插件标识
        assertEquals("plugin-a::jira", child.pluginId());
    }

    @Test
    void subContext_should_carryConfiguration_when_parentHasConfiguration() {
        // Given
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put("scriptsRoot", "scripts/python");
        PluginContextImpl configured = new PluginContextImpl(
                PluginDeclaration.of("plugin-a", configuration), extensions, events, sessions);

        // When
        PluginContextImpl child = (PluginContextImpl) configured.subContext("jira");

        // Then：子上下文与父上下文读同一份配置段，插件不必再调一次 configuration().get(...)
        assertEquals(configuration, child.configuration());
    }

    @Test
    void subContext_should_registerUnderChildOwner_when_handlerIsRegisteredThroughIt() {
        // When
        PluginContextImpl child = (PluginContextImpl) context.subContext("jira");
        child.handle(ToolCallRequest.class, "jira_issue", new ToolDescriptor("jira_issue", "读 issue"),
                request -> new ToolCallResult("jira_issue", "ok"));

        // Then
        assertEquals("plugin-a::jira",
                typeRegistry.resolve(ToolCallRequest.class, "jira_issue").get(0).getOwner());
    }

    @Test
    void subContext_should_supportFurtherNesting_when_childDerivesAgain() {
        // When：层级回收天然支持任意深度，所以不特意禁止；这里钉住形状而不是禁止它
        PluginContextImpl grandChild =
                (PluginContextImpl) ((PluginContextImpl) context.subContext("a")).subContext("b");

        // Then
        assertEquals("plugin-a::a::b", grandChild.pluginId());
    }

    @Test
    void subContext_should_rejectInvalidChildId_when_childIdBreaksOwnerNamespace() {
        // When / Then：子标识是编程错误而不是运行时条件，应当场报错
        assertThrows(JellyfishException.class, () -> context.subContext(null));
        assertThrows(JellyfishException.class, () -> context.subContext("  "));
        assertThrows(JellyfishException.class, () -> context.subContext("a b"));
        assertThrows(JellyfishException.class, () -> context.subContext("a/b"));
        assertThrows(JellyfishException.class, () -> context.subContext("a::b"));
    }

    @Test
    void configuration_should_return_empty_map_when_not_configured() {
        // Then
        assertTrue(context.configuration().isEmpty());
    }

    @Test
    void configuration_should_return_declared_section_when_configured() {
        // Given
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("precision", 4);
        PluginContextImpl configured = new PluginContextImpl(
                PluginDeclaration.of("plugin-b", configuration), extensions, events, sessions);

        // Then
        assertEquals(4, configured.configuration().get("precision"));
    }

    @Test
    void handle_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler =
                request -> new ToolCallResult("calculator", 42);

        // When
        context.handle(ToolCallRequest.class, "calculator", handler);

        // Then
        assertSame(handler, extensions.handlers(ToolCallRequest.class, "calculator").get(0));
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void handle_should_keep_descriptor_together_with_handler() {
        // Given
        ToolDescriptor descriptor = new ToolDescriptor("calculator", "算一下");

        // When
        context.handle(ToolCallRequest.class, "calculator", descriptor, request -> null);

        // Then
        assertEquals(1, extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void contribute_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<CommandRequest, CommandResult> handler = request -> CommandResult.ok("42");

        // When
        context.contribute(CommandRequest.class, handler, RegisterOptions.order(3));

        // Then
        assertSame(handler, extensions.handlers(CommandRequest.class, null).get(0));
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void observe_should_apply_filter_and_bind_plugin_owner() throws InterruptedException {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> "keep".equals(event.getSource()), event -> {
            received.add(event);
            latch.countDown();
        });

        // When
        events.publish(new ConfigWarningEvent("drop", "message"));
        events.publish(new ConfigWarningEvent("keep", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("keep", received.get(0).getSource());
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void observe_should_listen_to_every_event_when_filter_omitted() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> latch.countDown());

        // When
        events.publish(new ConfigWarningEvent("any", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void emit_should_publish_to_event_channel() throws InterruptedException {
        // Given
        List<JellyfishEvent> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> {
            received.add(event);
            latch.countDown();
        });

        // When
        context.emit(new ConfigWarningEvent("source", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
    }

    @Test
    void handle_should_fail_when_context_already_closed() {
        // Given：注册窗口是插件存活期，停止之后一律拒绝——否则会留下幽灵注册
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then
        assertThrows(JellyfishException.class, () -> closable.handle(ToolCallRequest.class, "calc",
                request -> new ToolCallResult("calc", "ok")));
        assertTrue(extensions.handlers(ToolCallRequest.class, "calc").isEmpty());
    }

    @Test
    void contribute_should_fail_when_context_already_closed() {
        // Given
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then
        assertThrows(JellyfishException.class, () -> closable.contribute(CommandRequest.class,
                request -> CommandResult.ok("ok")));
        assertTrue(extensions.handlers(CommandRequest.class, null).isEmpty());
    }

    @Test
    void observe_should_fail_when_context_already_closed() {
        // Given
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then
        assertThrows(JellyfishException.class,
                () -> closable.observe(ConfigWarningEvent.class, event -> {
                    // 仅用于产生一条订阅意图
                }));
    }

    @Test
    void emit_should_fail_when_context_already_closed() {
        // Given
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then：停止之后的发布同样属于幽灵行为，不能静默丢掉了事
        assertThrows(JellyfishException.class, () -> closable.emit(new ConfigWarningEvent("source", "message")));
    }

    @Test
    void submit_should_fail_when_context_already_closed() {
        // Given：投递与注册共用同一条存活边界，否则会在一个没人排空的队列里留下永远不兑现的 QUEUED
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle,
                new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then
        assertThrows(JellyfishException.class,
                () -> closable.submit(PluginAction.compact("s1")));
    }

    @Test
    void submit_should_report_failure_instead_of_throwing_when_no_turn_in_flight() {
        // Given：正常存活的上下文，但目标会话没有在途回合
        ActionQueue actions = new ActionQueue();
        PluginContextImpl alive = new PluginContextImpl(PluginDeclaration.of("plugin-a"), extensions,
                events, new ContextLifecycle(), new RuntimeInfoHolder(), actions, sessions, new ShellIngress(new MetricsRegistry()));

        // When
        ActionHandle handle = alive.submit(
                PluginAction.sendUserMessage("s1", "接着干", DeliverAs.FOLLOW_UP));

        // Then：「这次没赶上」是正常结果，不该变成必须 try/catch 的错误路径
        assertEquals(ActionStatus.FAILED, handle.getStatus());
        assertEquals(ActionFailureReason.NO_TURN_IN_FLIGHT, handle.getFailureReason());
        assertTrue(handle.getResult().contains("没有在途回合"), handle.getResult());
    }

    @Test
    void subContext_should_share_lifecycle_with_parent() {
        // Given：子上下文也握着注册能力，若它们各有一份标记，回收根上下文就管不住它们
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl parent = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        PluginContextImpl child = (PluginContextImpl) parent.subContext("jira");

        // When
        lifecycle.close();

        // Then
        assertThrows(JellyfishException.class, () -> child.handle(ToolCallRequest.class, "jira_issue",
                request -> new ToolCallResult("jira_issue", "ok")));
    }

    @Test
    void putExtensionEntry_should_prefix_key_with_plugin_namespace() {
        // 前缀恒从当前身份派生：插件无法写到别人的命名空间里，也无法不写前缀
        context.putExtensionEntry("s1", "checked", Collections.singletonMap("files", 3));

        verify(sessions).putExtensionEntry("s1", "plugin-a", "plugin-a::checked",
                Collections.singletonMap("files", 3));
    }

    @Test
    void putExtensionEntry_should_prefix_with_child_namespace_when_sub_context() {
        context.subContext("jira").putExtensionEntry("s1", "issue", null);

        verify(sessions).putExtensionEntry("s1", "plugin-a::jira", "plugin-a::jira::issue", null);
    }

    @Test
    void putExtensionEntry_should_reject_key_with_separator() {
        // 否则 plugin-a::a::b 读不出来它到底是「子上下文 a 写的 key b」还是
        // 「根上下文写的 key a::b」，诊断输出就失去了可归因性
        assertThrows(JellyfishException.class,
                () -> context.putExtensionEntry("s1", "a::b", null));
    }

    @Test
    void putExtensionEntry_should_reject_blank_key() {
        assertThrows(JellyfishException.class, () -> context.putExtensionEntry("s1", "  ", null));
    }

    @Test
    void removeExtensionEntry_should_prefix_key() {
        context.removeExtensionEntry("s1", "checked");

        verify(sessions).removeExtensionEntry("s1", "plugin-a::checked");
    }

    @Test
    void extensionEntries_should_read_only_own_namespace() {
        // 读取与写入的命名空间隔离对称：看不到别人的条目
        context.extensionEntries("s1");

        verify(sessions).extensionEntriesOf("s1", "plugin-a");
    }

    @Test
    void extensionEntries_should_fail_when_context_already_closed() {
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle,
                new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        assertThrows(JellyfishException.class, () -> closable.putExtensionEntry("s1", "k", null));
        assertThrows(JellyfishException.class, () -> closable.removeExtensionEntry("s1", "k"));
        assertThrows(JellyfishException.class, () -> closable.extensionEntries("s1"));
    }

    @Test
    void handle_should_still_work_before_context_closed() {
        // Given：上一条的反面 —— 失效只在关闭之后生效，关闭之前照常注册
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));

        // When
        closable.handle(ToolCallRequest.class, "calc", request -> new ToolCallResult("calc", "ok"));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "calc").size());
    }

    @Test
    void present_should_enqueue_when_shell_renders_and_session_exists() {
        // Given
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.tui(true), ingress);
        when(sessions.exists("s1")).thenReturn(true);

        // When
        ShellContributionStatus status = context.present(notice(ShellContribution.Scope.SESSION, "s1"));

        // Then
        assertEquals(ShellContributionStatus.ACCEPTED, status);
        assertEquals(1, ingress.drain().size());
    }

    @Test
    void present_should_report_dropped_when_session_does_not_exist_and_never_create_it() {
        // Given：这是「插件不能新开会话」这条硬约束的落点之一
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.tui(true), ingress);
        when(sessions.exists("ghost")).thenReturn(false);

        // When
        ShellContributionStatus status = context.present(notice(ShellContribution.Scope.SESSION, "ghost"));

        // Then
        assertEquals(ShellContributionStatus.DROPPED_NO_SESSION, status);
        assertTrue(ingress.drain().isEmpty());
        verify(sessions, never()).create(any(), any(), any());
        verify(sessions, never()).createDefault();
        verify(sessions, never()).require("ghost");
    }

    @Test
    void present_should_report_dropped_when_session_id_is_blank() {
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.tui(true), ingress);
        when(sessions.exists(null)).thenReturn(false);

        assertEquals(ShellContributionStatus.DROPPED_NO_SESSION,
                context.present(notice(ShellContribution.Scope.SESSION, null)));
        assertTrue(ingress.drain().isEmpty());
    }

    @Test
    void present_should_report_dropped_when_this_shell_has_no_renderer() {
        // Given：-cli 单次调用没有界面，收了也没人来取
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.cli(false), ingress);

        // When
        ShellContributionStatus status = context.present(notice(ShellContribution.Scope.SHELL, null));

        // Then
        assertEquals(ShellContributionStatus.DROPPED_NO_RENDERER, status);
        assertTrue(ingress.drain().isEmpty());
        // 没有渲染面时连会话都不必问
        verify(sessions, never()).exists(any());
    }

    @Test
    void present_should_accept_shell_scope_without_session_lookup() {
        // Given：SHELL scope 与任何会话无关，不该顺手去查一次会话
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.server(false), ingress);

        // When
        ShellContributionStatus status = context.present(notice(ShellContribution.Scope.SHELL, null));

        // Then
        assertEquals(ShellContributionStatus.ACCEPTED, status);
        verify(sessions, never()).exists(any());
    }

    @Test
    void present_should_treat_unknown_runtime_info_as_no_renderer() {
        // Given：嵌入式用法拿不到外壳种类，保守一侧是「不收」
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.unknown(), ingress);

        assertEquals(ShellContributionStatus.DROPPED_NO_RENDERER,
                context.present(notice(ShellContribution.Scope.SHELL, null)));
    }

    @Test
    void present_should_use_full_owner_namespace_so_child_contexts_stay_attributable() {
        // When：子上下文推的贡献按子身份归因，回收与显示上限都据此分桶
        ShellIngress ingress = new ShellIngress(new MetricsRegistry());
        PluginContextImpl context = contextWith(RuntimeInfo.tui(true), ingress);
        when(sessions.exists("s1")).thenReturn(true);
        ((PluginContextImpl) context.subContext("jira")).present(notice(ShellContribution.Scope.SESSION, "s1"));

        // Then
        assertEquals("plugin-a::jira", ingress.drain().get(0).getOwner());
    }

    @Test
    void present_should_reject_null_contribution() {
        assertThrows(JellyfishException.class, () -> context.present(null));
    }

    @Test
    void present_should_fail_when_context_already_closed() {
        // Given
        ContextLifecycle lifecycle = new ContextLifecycle();
        PluginContextImpl closable = new PluginContextImpl(
                PluginDeclaration.of("plugin-a"), extensions, events, lifecycle,
                new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry()));
        lifecycle.close();

        // When / Then
        assertThrows(JellyfishException.class,
                () -> closable.present(notice(ShellContribution.Scope.SHELL, null)));
    }

    /**
     * 造一个注入了给定委派端口的上下文。
     *
     * @param port 委派端口
     * @return 上下文
     */
    private PluginContextImpl contextWithDelegations(SubAgentPort port) {
        return new PluginContextImpl(PluginDeclaration.of("plugin-a"), extensions, events,
                new ContextLifecycle(), new RuntimeInfoHolder(), new ActionQueue(), sessions,
                new ShellIngress(new MetricsRegistry()), port);
    }

    /**
     * 造一个用给定运行时信息与信箱的上下文。
     *
     * @param info    运行时信息
     * @param ingress 信箱
     * @return 上下文
     */
    private PluginContextImpl contextWith(RuntimeInfo info, ShellIngress ingress) {
        RuntimeInfoHolder holder = new RuntimeInfoHolder();
        holder.set(info);
        return new PluginContextImpl(PluginDeclaration.of("plugin-a"), extensions, events,
                new ContextLifecycle(), holder, new ActionQueue(), sessions, ingress);
    }

    /**
     * 造一条通知。
     *
     * @param scope     作用域
     * @param sessionId 会话标识
     * @return 贡献
     */
    private static ShellContribution notice(ShellContribution.Scope scope, String sessionId) {
        return ShellContribution.notice(scope, sessionId, null, ShellContribution.Severity.INFO,
                Collections.singletonList(UiLine.of("x")));
    }
}
