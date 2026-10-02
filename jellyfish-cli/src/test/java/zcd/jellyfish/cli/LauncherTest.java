package zcd.jellyfish.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.cli.di.JellyfishComponent;
import zcd.jellyfish.cli.mode.CliRunMode;
import zcd.jellyfish.cli.mode.RunMode;
import zcd.jellyfish.cli.mode.ServerRunMode;
import zcd.jellyfish.cli.mode.TuiRunMode;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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

    /**
     * 提示词组装器：本用例只验启动模式的选择，压缩的请求怎么拼与它无关，
     * 因此给一个未打桩的 mock（{@code buildFork} 返回 {@code null}）。
     */
    @Mock
    private PromptAssembler promptAssembler;

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

    /**
     * 真实运行时信息持有者：外壳种类由 {@code Launcher} 写入，本用例据此断言「写在了 bootstrap 之前」。
     * <p>
     * 刻意不用 mock：写入是断言的一部分，用 mock 就只能验「调过 set」而验不了「写进去的是什么」。
     */
    private final RuntimeInfoHolder runtimeInfoHolder = new RuntimeInfoHolder();

    /** 真实输入改写服务：只为满足三个运行模式的构造（非空校验）。 */
    @Mock
    private ConversationService conversations;

    /** 在途回合表：TUI / Server 装配需要。 */
    private final TurnRegistry turnRegistry = new TurnRegistry();

    /** 可靠 lane：三个模式装配都需要。 */
    private final ShellStreams shellStreams = new ShellStreams(new ShellIngress(new MetricsRegistry()));

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

    /** 输入指令服务：本测试只验证装配，不真的执行指令。 */
    @Mock
    private InputDirectives inputDirectives;

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

    /** 首页状态栏要读的待生效默认值；TUI 装配需要它。 */
    private final SessionDefaults sessionDefaults = new SessionDefaults();

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
                new ExtensionRegistry(new TypeRegistry()), events, new SessionModelResolver(models, agents),
                promptAssembler);
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
    void shell_should_match_selected_mode() {
        // Given：两个模式构造器都会对与自己相关的门面做非空校验，因此需要它们各自的桩
        givenTuiCollaborators();
        givenServerCollaborators();

        assertEquals(RuntimeInfo.Shell.CLI,
                launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.CLI).build()).shell());
        assertEquals(RuntimeInfo.Shell.TUI,
                launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.TUI).build()).shell());
        assertEquals(RuntimeInfo.Shell.SERVER,
                launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.SERVER).port(9096).build()).shell());
    }

    @Test
    void launch_should_write_runtime_info_before_bootstrap() {
        // Given：插件在 start() 里就会读运行时信息（据此前置决定要不要注册需要审批的能力），
        // 因此写入必须早于 bootstrap，否则插件读到的是缺省的「未知外壳」
        givenComponentCollaborators();
        when(conversations.submit(eq(session.getSessionId()), eq("/help"), any(), any()))
                .thenReturn(Submission.command(session.getSessionId(), CommandResult.ok("帮助")));
        AtomicReference<RuntimeInfo> atBootstrap = new AtomicReference<RuntimeInfo>();
        doAnswer(invocation -> {
            atBootstrap.set(runtimeInfoHolder.snapshot());
            return null;
        }).when(harness).bootstrap();

        // When
        launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("/help").build());

        // Then：bootstrap 那一刻已经是最终值；-cli 没有审批通道，插件据此可以优雅降级
        assertEquals(RuntimeInfo.Shell.CLI, atBootstrap.get().getShell());
        assertFalse(atBootstrap.get().supportsApproval());
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
        when(conversations.submit(eq(session.getSessionId()), eq("/help"), any(), any()))
                .thenReturn(Submission.command(session.getSessionId(), CommandResult.ok("帮助")));

        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("/help").build());

        assertEquals(ExitCodes.OK, code);
        assertEquals("帮助\n", console.out());
        verify(harness).bootstrap();
        verify(harness).shutdown();
    }

    @Test
    void launch_should_keep_current_session_when_one_exists() {
        givenComponentCollaborators();
        when(conversations.submit(eq(session.getSessionId()), eq("/status"), any(), any()))
                .thenReturn(Submission.command(session.getSessionId(), CommandResult.ok("概要")));

        launcher.launch(StartupOptions.builder(StartupOptions.Mode.CLI).prompt("/status").build());

        assertNotNull(sessions.current());
        verify(conversations).submit(eq(session.getSessionId()), eq("/status"), any(), any());
    }

    @Test
    void launch_should_return_startup_error_and_still_shutdown_when_bootstrap_fails() {
        // 本用例在 bootstrap 就失败，走不到模式实现：因此只桩 Launcher 与 SessionBootstrap 要用的那几个
        when(component.agentHarness()).thenReturn(harness);
        when(component.conversationService()).thenReturn(conversations);
        when(component.shellStreams()).thenReturn(shellStreams);
        when(component.sessionManager()).thenReturn(sessions);
        when(component.runtimeInfoHolder()).thenReturn(runtimeInfoHolder);
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
        when(conversations.submit(eq(session.getSessionId()), eq("boom"), any(), any()))
                .thenThrow(new JellyfishException("命令域故障"));

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
     * <p>
     * 运行时信息持有者是唯一的例外，用 {@code lenient()}：它只在 {@code launch} 且环境自检通过之后
     * 才被读写，而「选模式」与「环境不满足」两类用例根本走不到那一步——
     * 那是用例刻意不走路径，不是多余的桩。
     */
    private void givenRunModeCollaborators() {
        when(component.conversationService()).thenReturn(conversations);
        when(component.shellStreams()).thenReturn(shellStreams);
        when(component.sessionManager()).thenReturn(sessions);
        lenient().when(component.runtimeInfoHolder()).thenReturn(runtimeInfoHolder);
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
        when(component.commandManager()).thenReturn(commands);
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
        when(component.extensionRegistry()).thenReturn(extensionRegistry);
        when(component.eventChannel()).thenReturn(eventChannel);
        when(component.approvalChannel()).thenReturn(approvalChannel);
        when(component.conversationCompactor()).thenReturn(conversationCompactor);
        when(component.inputDirectives()).thenReturn(inputDirectives);
        when(component.turnRegistry()).thenReturn(turnRegistry);
        when(component.sessionDefaults()).thenReturn(sessionDefaults);
    }

    /**
     * 桩：构造 Server 模式所需的门面。
     */
    private void givenServerCollaborators() {
        givenRunModeCollaborators();
        when(component.commandManager()).thenReturn(commands);
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
        when(component.approvalChannel()).thenReturn(approvalChannel);
        when(component.healthCheck()).thenReturn(healthCheck);
        when(component.turnRegistry()).thenReturn(turnRegistry);
    }

    /**
     * 桩：构造 CLI 模式 + 启动期会话保证所需的全部协作者。
     */
    private void givenComponentCollaborators() {
        givenRunModeCollaborators();
        when(component.agentHarness()).thenReturn(harness);
        when(component.modelManager()).thenReturn(models);
        when(component.agentManager()).thenReturn(agents);
    }
}
