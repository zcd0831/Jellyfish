package zcd.jellyfish.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.cli.di.JellyfishComponent;
import zcd.jellyfish.cli.mode.CliRunMode;
import zcd.jellyfish.cli.mode.RunMode;
import zcd.jellyfish.cli.mode.ServerRunMode;
import zcd.jellyfish.cli.mode.TuiRunMode;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Launcher} 的单元测试：验证模式分发、生命周期顺序、占位模式不启动内核，以及异常路径仍会收敛。
 * <p>
 * 会话域用<b>真实</b>的 {@link SessionManager}：{@code Launcher} 与 {@code CliRunMode} 之间靠
 * 「{@code switchTo} 之后 {@code current()} 变了」这条真实行为串联，mock 掉它就等于不测接线。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class LauncherTest {

    @Mock
    private JellyfishComponent component;

    /**
     * 扩展层：TUI 模式装配 {@code UiContributions} 时需要。
     * <p>
     * 两者都是 {@code final} 类，本模块没开 inline mock maker，因此只能用真实实例；
     * 事件通道自建线程池，所以要在 {@link #tearDown()} 里收敛。
     */
    private final TypeRegistry typeRegistry = new TypeRegistry();

    /** 真实同步扩展点策略，仅为满足 TUI 装配。 */
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistry(typeRegistry);

    /** 真实事件通道，仅为满足 TUI 装配。 */
    private final EventChannel eventChannel = new EventChannel(EventChannelOptions.defaults(), typeRegistry);

    /** 真实审批通道，仅为满足 TUI / Server 装配（未挂审批者，因此不会真的等答复）。 */
    private final ApprovalChannel approvalChannel = new ApprovalChannel();

    /** 真实健康检查汇总，仅为满足 Server 装配。 */
    private final zcd.jellyfish.infra.metrics.HealthCheck healthCheck =
            new zcd.jellyfish.infra.metrics.HealthCheck(java.util.Collections.emptyList());

    /**
     * 真实会话压缩器，仅为满足 TUI 装配。
     * <p>
     * 在 {@code setUp} 里建而不是字段初始化：它的构造参数里有 {@code sessions}，
     * 而那个字段要到 {@code setUp} 才被赋值（字段初始化先于 {@code @BeforeEach}）。
     * 全程不提交任务，因此它一个线程都不会起。
     */
    private ConversationCompactor conversationCompactor;

    @Mock
    private AgentHarness harness;

    @Mock
    private CommandManager commands;

    @Mock
    private ModelManager models;

    @Mock
    private AgentManager agents;

    /** 运行时配置门面：只为满足压缩器装配（本测试不真的压缩）。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 通知发布入口：压缩器只在「该压了却没插件」时用它，本用例不关心。 */
    @Mock
    private EventPublisher events;

    private SessionManager sessions;

    private Session session;

    private RecordingConsoleIO console;

    private Launcher launcher;

    @BeforeEach
    void setUp() {
        console = new RecordingConsoleIO(null);
        sessions = SessionTestSupport.newSessionManager();
        session = sessions.createDefault();
        sessions.switchTo(session.getSessionId());
conversationCompactor = new ConversationCompactor(sessions, models, runtimeConfig,
                new ExtensionRegistry(new TypeRegistry()), events);
        launcher = new Launcher(component, console);
    }

    @Test
    void modeFor_should_return_cli_mode_when_cli_given() {
        givenRunModeCollaborators();

        RunMode mode = launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertTrue(mode instanceof CliRunMode);
    }

    @Test
    void modeFor_should_return_tui_mode_when_tui_given() {
        givenTuiCollaborators();

        RunMode mode = launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        assertTrue(mode instanceof TuiRunMode);
    }

    @Test
    void modeFor_should_return_server_mode_when_server_given() {
        givenServerCollaborators();

        RunMode mode = launcher.modeFor(
                StartupOptions.builder(StartupOptions.Mode.SERVER).port(9096).build());

        assertTrue(mode instanceof ServerRunMode);
    }

    @Test
    void launch_should_return_startup_error_and_skip_kernel_when_tui_has_no_terminal() {
        // 无终端时 TUI 会永久挂住（退化到 dumb 终端后等一个永远不来的事件），
        // 因此必须在启动内核之前就拦下：既给用户一句可执行的报错，也不白起插件扫描与事件线程。
        assumeTrue(System.console() == null, "当前测试 JVM 有可交互终端，无法验证无终端场景");
        givenTuiCollaborators();

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        assertEquals(ExitCodes.STARTUP_ERROR, code);
        assertTrue(console.err().contains("-cli"));
        verify(harness, never()).bootstrap();
    }

    @Test
    void launch_should_bootstrap_run_and_shutdown_when_cli_given() {
        givenComponentCollaborators();
        when(commands.isCommand("/help")).thenReturn(true);
        when(commands.execute("/help", session.getSessionId())).thenReturn(CommandResult.ok("帮助"));

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("/help").build());

        assertEquals(ExitCodes.OK, code);
        assertEquals("帮助\n", console.out());
        verify(harness).bootstrap();
        verify(harness).shutdown();
    }

    @Test
    void launch_should_keep_current_session_when_one_exists() {
        givenComponentCollaborators();
        when(commands.isCommand("/status")).thenReturn(true);
        when(commands.execute("/status", session.getSessionId())).thenReturn(CommandResult.ok("概要"));

        launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("/status").build());

        assertNotNull(sessions.current());
        verify(commands).execute("/status", session.getSessionId());
    }

    @Test
    void launch_should_return_startup_error_and_still_shutdown_when_bootstrap_fails() {
        givenRunModeCollaborators();
        doThrow(new JellyfishException("插件目录不可读")).when(harness).bootstrap();

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("你好").build());

        assertEquals(ExitCodes.STARTUP_ERROR, code);
        assertTrue(console.err().contains("插件目录不可读"));
        verify(harness).shutdown();
    }

    @Test
    void launch_should_return_usage_error_when_session_option_unsatisfiable() {
        givenComponentCollaborators();

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).sessionId("missing").build());

        assertEquals(ExitCodes.USAGE_ERROR, code);
        assertTrue(console.err().contains("会话不存在：missing"));
        verify(harness).shutdown();
    }

    @Test
    void launch_should_still_shutdown_when_mode_throws_unexpected_exception() {
        givenComponentCollaborators();
        when(commands.isCommand("boom")).thenReturn(true);
        when(commands.execute("boom", session.getSessionId())).thenThrow(new JellyfishException("命令域故障"));

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("boom").build());

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
        verify(harness).shutdown();
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        assertThrows(NullPointerException.class, () -> new Launcher(null, console));
        assertThrows(NullPointerException.class, () -> new Launcher(component, null));
    }

    @Test
    void launch_should_reject_null_options() {
        assertThrows(NullPointerException.class, () -> launcher.launch(null));
    }

    /**
     * 桩：构造 CLI 模式所需的协作者。
     */
    /**
     * 桩上 CLI 真实现需要的三个门面。
     * <p>
     * 刻意按用例需要的最小集桩：Mockito 的严格模式会把「桩了但没用到」当成失败，
     * 这也正好挡住「为了省事一次桩全套」的写法。
     */
    private void givenRunModeCollaborators() {
        when(component.agentHarness()).thenReturn(harness);
        when(component.commandManager()).thenReturn(commands);
        when(component.sessionManager()).thenReturn(sessions);
    }

    /**
     * 收敛真实事件通道的线程池。
     */
    @AfterEach
    void tearDown() {
        eventChannel.close();
    }

    /**
     * 桩上 TUI 真实现需要的门面：比 CLI 多一个模型门面（状态栏展示上下文长度用）、一个 agent 门面
     * （首页无会话时状态栏展示默认 agent 用）与扩展层两个门面（{@code UiContributions} 的构造输入）。
     */
    private void givenTuiCollaborators() {
        givenRunModeCollaborators();
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
        when(component.extensionRegistry()).thenReturn(extensionRegistry);
        when(component.eventChannel()).thenReturn(eventChannel);
        when(component.approvalChannel()).thenReturn(approvalChannel);
        when(component.conversationCompactor()).thenReturn(conversationCompactor);
    }

    /**
     * 桩：构造 Server 模式所需的门面。
     */
    private void givenServerCollaborators() {
        givenRunModeCollaborators();
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
        when(component.approvalChannel()).thenReturn(approvalChannel);
        when(component.healthCheck()).thenReturn(healthCheck);
    }

    /**
     * 桩：构造 CLI 模式 + 启动期会话保证所需的全部协作者。
     */
    private void givenComponentCollaborators() {
        givenRunModeCollaborators();
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
    }
}
