package zcd.jellyfish.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
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
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Mock
    private AgentHarness harness;

    @Mock
    private CommandManager commands;

    @Mock
    private ModelManager models;

    @Mock
    private AgentManager agents;

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
        launcher = new Launcher(component, console);
    }

    @Test
    void modeFor_should_return_cli_mode_when_cli_given() {
        givenRunModeCollaborators();

        RunMode mode = launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertTrue(mode instanceof CliRunMode);
        assertTrue(mode.isImplemented());
    }

    @Test
    void modeFor_should_return_tui_placeholder_when_tui_given() {
        RunMode mode = launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        assertTrue(mode instanceof TuiRunMode);
        assertFalse(mode.isImplemented());
    }

    @Test
    void modeFor_should_return_server_placeholder_when_server_given() {
        RunMode mode = launcher.modeFor(StartupOptions.builder(StartupOptions.Mode.SERVER).build());

        assertTrue(mode instanceof ServerRunMode);
        assertFalse(mode.isImplemented());
    }

    @Test
    void launch_should_return_not_implemented_and_skip_kernel_when_tui_given() {
        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        assertEquals(ExitCodes.NOT_IMPLEMENTED, code);
        assertTrue(console.err().contains("-tui"));
        verify(component, never()).agentHarness();
    }

    @Test
    void launch_should_return_not_implemented_and_skip_kernel_when_server_given() {
        int code = launcher.launch(StartupOptions.builder(StartupOptions.Mode.SERVER).port(9096).build());

        assertEquals(ExitCodes.NOT_IMPLEMENTED, code);
        assertTrue(console.err().contains("-server"));
        verify(component, never()).agentHarness();
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
    private void givenRunModeCollaborators() {
        when(component.agentHarness()).thenReturn(harness);
        when(component.commandManager()).thenReturn(commands);
        when(component.sessionManager()).thenReturn(sessions);
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
