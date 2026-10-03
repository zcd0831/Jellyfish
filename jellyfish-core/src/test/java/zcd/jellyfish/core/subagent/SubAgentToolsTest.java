package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.DescriptorBinding;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SubAgentTools} 的单元测试：验证 {@code task} 以 {@code owner=core} 注册、
 * 随开关挂上与摘下、随配置重载重算，以及「插件必须显式 override 才能替换它」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentToolsTest {

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 子代理委派器。 */
    @Mock
    private SubAgentLauncher launcher;

    /** 异步通知通道；只用于拿到重载监听，测试里手动触发以保证确定性。 */
    @Mock
    private EventChannel events;

    /** agent 运行时门面：面板的筛选依据，本类只验证注册与注销。 */
    @Mock
    private AgentRuntime runtime;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 被测对象。 */
    private SubAgentTools tools;

    /** 捕获到的重载监听，{@code null} 表示尚未订阅。 */
    private Consumer<ConfigReloadedEvent> reloadListener;

    /** 订阅是否仍然有效；{@code close()} 后置假，相当于真通道不再派发。 */
    private boolean reloadSubscribed;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        tools = new SubAgentTools(extensions, events, agentManager, runtimeConfig, new TaskTool(launcher),
                new SubAgentPanel(runtime));
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        // 真派发要等线程池，而这里要验证的是「收到重载后重算」这件事本身，
        // 因此把监听拿在手里直接触发——与 MetricsSubscriberTest 不同，这里没有并发语义要守
        lenient().when(events.subscribe(eq(SubAgentTools.OWNER), eq(ConfigReloadedEvent.class), any()))
                .thenAnswer(invocation -> {
                    reloadListener = invocation.getArgument(2, Consumer.class);
                    reloadSubscribed = true;
                    return (Subscription) () -> reloadSubscribed = false;
                });
    }

    @Test
    void register_should_expose_task_tool_as_core_owner() {
        // When
        tools.register();

        // Then：owner=core 是「插件必须显式 override」这条约束的前提
        List<DescriptorBinding<ToolDescriptor>> bindings =
                extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class);
        assertEquals(1, bindings.size());
        assertEquals(SubAgentTools.OWNER, bindings.get(0).getOwner());
        assertEquals(TaskTool.NAME, bindings.get(0).getRouteKey());
    }

    @Test
    void register_should_be_idempotent() {
        // When
        tools.register();
        tools.register();

        // Then：重复注册若真的又注册一次，第二次会因为同键冲突当场抛错
        assertEquals(1, extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void close_should_unregister_task_and_catalog() {
        // Given
        tools.register();

        // When
        tools.close();

        // Then
        assertTrue(extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).isEmpty());
        assertTrue(extensions.bindings(PromptContributionRequest.class, null).isEmpty());
        assertTrue(extensions.bindings(PanelContributionRequest.class, null).isEmpty());
        // 幂等：再关一次不抛错
        tools.close();
    }

    @Test
    void register_should_expose_run_panel_as_core_owner() {
        // When
        tools.register();

        // Then：面板与 task 工具同一 owner；没有面板就拿不到「现在有几个子代理在跑」这个事实
        List<HandlerBinding<PanelContributionRequest, PanelContribution>> bindings =
                extensions.bindings(PanelContributionRequest.class, null);
        assertEquals(1, bindings.size());
        assertEquals(SubAgentTools.OWNER, bindings.get(0).getOwner());
    }

    @Test
    void disabled_should_leave_no_panel_registered() {
        // 面板与工具同生共死：开关关着时注册一块永远为空的面板只会白占区域
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null,
                null, null));

        // When
        tools.register();

        // Then
        assertTrue(extensions.bindings(PanelContributionRequest.class, null).isEmpty());
    }


    @Test
    void register_should_let_plugin_override_with_explicit_option() {
        // Given
        tools.register();
        ToolDescriptor replacement = new ToolDescriptor(TaskTool.NAME, "自定义委派", null, null);

        // When：插件显式声明覆盖
        extensions.handle("my-plugin", ToolCallRequest.class, TaskTool.NAME, replacement,
                (ExtensionHandler<ToolCallRequest, ToolCallResult>) request -> null,
                RegisterOptions.override(true));

        // Then：覆盖成功，来源变成插件
        List<DescriptorBinding<ToolDescriptor>> bindings =
                extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class);
        assertEquals(1, bindings.size());
        assertEquals("my-plugin", bindings.get(0).getOwner());
    }

    @Test
    void register_should_restore_core_tool_when_overriding_plugin_is_reclaimed() {
        // Given：插件顶替了内核的 task 工具
        tools.register();
        ToolDescriptor replacement = new ToolDescriptor(TaskTool.NAME, "自定义委派", null, null);
        Subscription plugin = extensions.handle("my-plugin", ToolCallRequest.class, TaskTool.NAME, replacement,
                (ExtensionHandler<ToolCallRequest, ToolCallResult>) request -> null,
                RegisterOptions.override(true));
        assertEquals("my-plugin",
                extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).get(0).getOwner());

        // When：插件停止（框架按 pluginId 回收它的全部登记）
        plugin.close();

        // Then：内核的 task 工具回到注册表，而不是永久消失
        // （覆盖一旦不可逆，「装过插件之后内置工具就没了」这种问题只能靠重启进程恢复）
        List<DescriptorBinding<ToolDescriptor>> bindings =
                extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class);
        assertEquals(1, bindings.size());
        assertEquals(SubAgentTools.OWNER, bindings.get(0).getOwner());
        assertEquals(TaskTool.NAME, bindings.get(0).getDescriptor().getName());
    }

    @Test
    void register_should_reject_plugin_without_explicit_override() {
        // Given
        tools.register();

        // When / Then：静默替换一个内核工具比替换一个命令更危险，必须显式声明
        assertThrows(ExtensionException.class, () -> extensions.handle("my-plugin", ToolCallRequest.class,
                TaskTool.NAME, null, (ExtensionHandler<ToolCallRequest, ToolCallResult>) request -> null,
                RegisterOptions.DEFAULT));
    }

    @Test
    void register_should_not_expose_task_when_disabled() {
        // Given：开关关掉
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null, null, null));

        // When
        tools.register();

        // Then：只让清单变空是不够的——模型依然看得到 task，却没有合法的 subagent_type 可传
        assertTrue(extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).isEmpty());
        assertTrue(extensions.bindings(PromptContributionRequest.class, null).isEmpty());
    }

    @Test
    void reconcile_should_expose_task_when_enabled_flips_on() {
        // Given：启动时关着
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null, null, null));
        tools.register();

        // When：/reload 把开关打开（配置已刷新，随后事件到达）
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings());
        publishReloaded();

        // Then：不重算的话，这个开关就得等下次重启才生效
        assertEquals(1, extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void reconcile_should_hide_task_when_enabled_flips_off() {
        // Given
        tools.register();

        // When
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null, null, null));
        publishReloaded();

        // Then
        assertTrue(extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).isEmpty());
        assertTrue(extensions.bindings(PromptContributionRequest.class, null).isEmpty());
    }

    @Test
    void reconcile_should_be_idempotent_across_repeated_reloads() {
        // Given
        tools.register();

        // When：连着重载两次；重复注册会因同键冲突当场抛错
        publishReloaded();
        publishReloaded();

        // Then
        assertEquals(1, extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void close_should_stop_reacting_to_reload() {
        // Given：注册完再关掉，此时配置仍是「开着」
        tools.register();
        tools.close();

        // When：重载一次——监听若还活着，就一定会因「开着且当前未注册」而重新注册
        publishReloaded();

        // Then
        assertTrue(extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).isEmpty());
    }

    @Test
    void catalog_should_list_only_delegatable_agents() {
        // Given
        when(agentManager.all()).thenReturn(Arrays.asList(
                new AgentDefinition("scout", "侦察代码库", null, Boolean.TRUE, null),
                new AgentDefinition("writer", "写代码", null, Boolean.FALSE, null),
                new AgentDefinition("plain", null, null, null, null)));

        // When
        String catalog = catalogOf();

        // Then：只列可委派的那个，并带上它的描述
        assertTrue(catalog.contains("scout：侦察代码库"), catalog);
        assertFalse(catalog.contains("writer"), catalog);
        assertFalse(catalog.contains("plain"), catalog);
    }

    @Test
    void catalog_should_beEmpty_when_no_delegatable_agent() {
        // Given
        when(agentManager.all()).thenReturn(
                Arrays.asList(new AgentDefinition("writer", "写代码", null, Boolean.FALSE, null)));
        tools.register();

        // When
        PromptContribution contribution = contribution();

        // Then：一段「可用类型：」后面什么都没有的废话不如不说
        assertTrue(contribution.isEmpty());
    }

    @Test
    void catalog_should_beEmpty_when_disabled_after_registration() {
        // Given：注册时还开着，随后配置被改关——而重算事件还在队列里没到
        tools.register();
        when(runtimeConfig.getSubAgentSettings()).thenReturn(new SubAgentSettings(false, null, null, null, null, null, null, null));

        // When
        PromptContribution contribution = contribution();

        // Then：这一段窗口里若只靠注册状态，清单会多宣传一个已关掉的能力
        assertTrue(contribution.isEmpty());
    }

    /**
     * 触发一次配置重载。
     * <p>
     * 已退订时什么也不做——真通道同样不会再派发，因此“没有反应”正是要验证的行为。
     */
    private void publishReloaded() {
        if (reloadListener != null && reloadSubscribed) {
            reloadListener.accept(new ConfigReloadedEvent(null, 1L));
        }
    }

    /**
     * 取清单贡献的文本。
     *
     * @return 清单文本
     */
    private String catalogOf() {
        tools.register();
        return contribution().getText();
    }

    /**
     * 调用已注册的清单贡献处理器。
     *
     * @return 贡献结果
     */
    private PromptContribution contribution() {
        List<HandlerBinding<PromptContributionRequest, PromptContribution>> bindings =
                extensions.bindings(PromptContributionRequest.class, null);
        return extensions.invoke(bindings.get(0).getHandler(), new PromptContributionRequest("session-1"));
    }
}
