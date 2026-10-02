package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.action.ActionFailureReason;
import zcd.jellyfish.api.action.ActionHandle;
import zcd.jellyfish.api.action.ActionStatus;
import zcd.jellyfish.api.action.DeliverAs;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextFactory} 的单元测试：验证 owner 绑定与一次性回收。
 *
 * @author zcd
 */
class PluginContextFactoryTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(registry);

    /** 事件通道。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);

    /** 动作队列：与注册同一时刻按 owner 回收。 */
    private final ActionQueue actions = new ActionQueue();

    /** 会话域服务：桩，只为满足插件上下文的构造；扩展条目的真实语义由 SessionManagerTest 覆盖。 */
    private final SessionManager sessions = Mockito.mock(SessionManager.class);

    /** 被测工厂。 */
    private final PluginContextFactory factory = new PluginContextFactory(extensions, events, registry, new RuntimeInfoHolder(), actions, sessions, new ShellIngress(new MetricsRegistry()));

    @Test
    void create_should_bind_plugin_id_as_owner() {
        // Given
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));

        // When
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));

        // Then
        assertTrue(registry.snapshot().render().contains("<- plugin-a"));
    }

    @Test
    void create_should_produce_isolated_contexts_per_declaration() {
        // Given
        PluginContext first = factory.create(PluginDeclaration.of("plugin-a"));
        PluginContext second = factory.create(PluginDeclaration.of("plugin-b"));

        // Then
        assertEquals("plugin-a", first.pluginId());
        assertEquals("plugin-b", second.pluginId());
        assertNotEquals(first.pluginId(), second.pluginId());
    }

    @Test
    void release_should_drop_extension_handlers_and_event_subscriptions_together() {
        // Given：同一个插件既注册处理器又订阅事件
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        context.observe(ConfigWarningEvent.class, event -> {
            // 仅用于产生一条订阅
        });

        // When：一份表意味着回收是一次操作
        int removed = factory.release("plugin-a");

        // Then
        assertEquals(2, removed);
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void release_should_keep_other_plugins_registrations() {
        // Given
        PluginContext kept = factory.create(PluginDeclaration.of("plugin-b"));
        kept.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        factory.create(PluginDeclaration.of("plugin-a"))
                .handle(CommandRequest.class, "other", request -> CommandResult.ok("ok"));

        // When
        factory.release("plugin-a");

        // Then
        assertEquals(1, extensions.handlers(CommandRequest.class, "calc").size());
        assertThrows(ExtensionException.class, () -> extensions.handler(CommandRequest.class, "other"));
    }

    @Test
    void release_should_reclaim_namespace_sub_owners_of_the_same_plugin() {
        // Given：插件把子单元的注册挂在 plugin-a::child 下，而框架只拿得到 pluginId
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        extensions.handle("plugin-a::child", CommandRequest.class, "sub", null,
                request -> CommandResult.ok("sub"), RegisterOptions.DEFAULT);
        extensions.handle("plugin-a::child::grand", CommandRequest.class, "deep", null,
                request -> CommandResult.ok("deep"), RegisterOptions.DEFAULT);

        // When
        int removed = factory.release("plugin-a");

        // Then：命名空间自身与全部子来源一起清干净，不留「插件已停、工具还能调」的幽灵注册
        assertEquals(3, removed);
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
        assertTrue(extensions.handlers(CommandRequest.class, "sub").isEmpty());
        assertTrue(extensions.handlers(CommandRequest.class, "deep").isEmpty());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void release_should_not_reclaim_plugin_whose_id_merely_shares_prefix() {
        // Given：plugin-ab 与 plugin-a2 只是名字像，不能因为回收 plugin-a 而被误伤
        extensions.handle("plugin-a::child", CommandRequest.class, "sub", null,
                request -> CommandResult.ok("sub"), RegisterOptions.DEFAULT);
        extensions.handle("plugin-ab", CommandRequest.class, "ab", null,
                request -> CommandResult.ok("ab"), RegisterOptions.DEFAULT);
        extensions.handle("plugin-a2::child", CommandRequest.class, "a2", null,
                request -> CommandResult.ok("a2"), RegisterOptions.DEFAULT);

        // When
        int removed = factory.release("plugin-a");

        // Then
        assertEquals(1, removed);
        assertEquals(1, extensions.handlers(CommandRequest.class, "ab").size());
        assertEquals(1, extensions.handlers(CommandRequest.class, "a2").size());
    }

    @Test
    void release_should_be_safe_for_unknown_owner() {
        // When / Then
        assertEquals(0, factory.release("never-registered"));
    }

    @Test
    void create_should_reject_null_declaration() {
        // When / Then：上下文实现同样校验声明，这里确认异常类型保持统一
        assertThrows(NullPointerException.class, () -> factory.create(null));
    }

    @Test
    void subscription_from_context_should_release_single_registration() {
        // Given
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        Subscription subscription = context.handle(ToolCallRequest.class, "echo", request -> null);

        // When
        subscription.close();

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "echo").isEmpty());
        assertEquals(1, extensions.handlers(CommandRequest.class, "calc").size());
    }

    @Test
    void factory_should_reject_null_dependencies() {
        // When / Then
        assertThrows(NullPointerException.class,
                () -> new PluginContextFactory(null, events, registry, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry())));
        assertThrows(NullPointerException.class,
                () -> new PluginContextFactory(extensions, null, registry, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry())));
        assertThrows(NullPointerException.class,
                () -> new PluginContextFactory(extensions, events, null, new RuntimeInfoHolder(), new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry())));
        assertThrows(NullPointerException.class,
                () -> new PluginContextFactory(extensions, events, registry, null, new ActionQueue(), sessions, new ShellIngress(new MetricsRegistry())));
    }

    @Test
    void release_should_drop_pending_plugin_actions() {
        // Given：插件投了一条动作，它还在队列里等排空
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        actions.beginTurn("s1");
        ActionHandle handle = context.submit(
                PluginAction.sendUserMessage("s1", "接着干", DeliverAs.FOLLOW_UP));
        assertEquals(ActionStatus.QUEUED, handle.getStatus());

        // When
        factory.release("plugin-a");

        // Then：停止之后没人再来排空它，插件必须能从旬柄上看到「没投出去」
        assertEquals(ActionStatus.DROPPED, handle.getStatus());
        assertEquals(ActionFailureReason.PLUGIN_STOPPED, handle.getFailureReason());
        assertTrue(handle.getResult().contains("插件已停止"), handle.getResult());
    }

    @Test
    void release_should_close_context_so_late_registration_fails() {
        // Given：注册窗口是插件存活期，因此停止后仍在跑的注册路径必须当场失败，
        // 而不是落表成一个谁也回收不到（已经回收过了）的幽灵注册
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));

        // When
        factory.release("plugin-a");

        // Then
        assertThrows(JellyfishException.class, () -> context.handle(CommandRequest.class, "late",
                request -> CommandResult.ok("ok")));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void release_should_close_sub_context_registrations_too() {
        // Given：子上下文的注册挂在 plugin-a::child 下，但存活标记跟的是插件，因此一并失效
        PluginContext child = factory.create(PluginDeclaration.of("plugin-a")).subContext("child");

        // When
        factory.release("plugin-a");

        // Then
        assertThrows(JellyfishException.class, () -> child.handle(ToolCallRequest.class, "echo",
                request -> null));
    }

    @Test
    void release_should_not_affect_other_plugin_contexts() {
        // Given
        PluginContext kept = factory.create(PluginDeclaration.of("plugin-b"));
        PluginContext doomed = factory.create(PluginDeclaration.of("plugin-a"));

        // When
        factory.release("plugin-a");

        // Then：回收一个插件不能顺手让别的插件的上下文失效
        kept.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        assertEquals(1, extensions.handlers(CommandRequest.class, "calc").size());
        assertThrows(JellyfishException.class, () -> doomed.handle(CommandRequest.class, "late",
                request -> CommandResult.ok("ok")));
    }

    @Test
    void create_should_invalidate_previous_context_when_same_plugin_created_twice() {
        // Given：同一 pluginId 未经 release 又被创建，只可能来自装配错误；方向是 fail-closed
        PluginContext stale = factory.create(PluginDeclaration.of("plugin-a"));

        // When
        PluginContext fresh = factory.create(PluginDeclaration.of("plugin-a"));

        // Then：旧上下文失效，新上下文可用
        assertThrows(JellyfishException.class, () -> stale.handle(CommandRequest.class, "stale",
                request -> CommandResult.ok("ok")));
        fresh.handle(CommandRequest.class, "fresh", request -> CommandResult.ok("ok"));
        assertEquals(1, extensions.handlers(CommandRequest.class, "fresh").size());
    }
}
