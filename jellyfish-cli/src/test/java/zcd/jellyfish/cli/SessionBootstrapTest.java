package zcd.jellyfish.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionBootstrap} 的单元测试：验证「保证有当前会话」的三条路径与启动参数的存在性校验。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SessionBootstrapTest {

    @Mock
    private SessionManager sessions;

    @Mock
    private ModelManager models;

    @Mock
    private AgentManager agents;

    private Session session;

    private String sessionId;

    private SessionBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new SessionBootstrap(sessions, models, agents);
        session = SessionTestSupport.newSession();
        sessionId = session.getSessionId();
    }

    @Test
    void ensureCurrentSession_should_create_and_switch_when_no_current_session() {
        when(sessions.current()).thenReturn(null);
        when(sessions.create(null, null, null, null)).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertSame(session, actual);
        verify(sessions).switchTo(sessionId);
    }

    @Test
    void ensureCurrentSession_should_keep_existing_current_session() {
        when(sessions.current()).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertSame(session, actual);
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_switch_when_session_option_given() {
        when(sessions.switchTo("abc")).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.CLI).sessionId("abc").build());

        assertSame(session, actual);
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_fail_when_session_option_targets_missing_session() {
        when(sessions.switchTo("missing")).thenThrow(new JellyfishException("session not found: missing"));

        JellyfishException error = assertThrows(JellyfishException.class, () -> bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.CLI).sessionId("missing").build()));

        assertEquals("会话不存在：missing（会话不持久化，单次模式每次进程都是新会话）", error.getMessage());
    }

    @Test
    void ensureCurrentSession_should_pass_overrides_into_created_session() {
        when(sessions.current()).thenReturn(null);
        when(sessions.create("coder", "openai", "gpt-4o", PermissionMode.PLAN)).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI)
                .agentId("coder").model("openai", "gpt-4o").permissionMode(PermissionMode.PLAN).build());

        assertSame(session, actual);
        verify(agents).require("coder");
        verify(models).resolve("openai", "gpt-4o");
        verify(sessions).switchTo(sessionId);
    }

    @Test
    void ensureCurrentSession_should_apply_overrides_to_existing_session() {
        when(sessions.current()).thenReturn(session);

        bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI)
                .agentId("coder").model("openai", "gpt-4o").permissionMode(PermissionMode.PLAN).build());

        verify(sessions).bindAgent(sessionId, "coder");
        verify(sessions).switchModel(sessionId, "openai", "gpt-4o");
        verify(sessions).setPermissionMode(sessionId, PermissionMode.PLAN);
    }

    @Test
    void ensureCurrentSession_should_not_touch_session_when_no_override_given() {
        when(sessions.current()).thenReturn(session);

        bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        verifyNoMoreInteractions(sessions);
    }

    @Test
    void ensureCurrentSession_should_fail_fast_when_model_missing() {
        when(models.resolve("openai", "gpt-4o")).thenThrow(new JellyfishException("model not found"));

        JellyfishException error = assertThrows(JellyfishException.class, () -> bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.CLI).model("openai", "gpt-4o").build()));

        assertEquals("模型不存在：openai/gpt-4o（可用 /model 命令查看可用模型）", error.getMessage());
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_fail_fast_when_agent_missing() {
        when(agents.require("ghost")).thenThrow(new JellyfishException("agent not found"));

        JellyfishException error = assertThrows(JellyfishException.class, () -> bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.CLI).agentId("ghost").build()));

        assertEquals("agent 不存在：ghost（可用 /agent 命令查看可用 agent）", error.getMessage());
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_tolerate_empty_agent_configuration() {
        when(sessions.current()).thenReturn(null);
        when(sessions.create(null, null, null, null)).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertSame(session, actual);
        verifyNoMoreInteractions(agents);
    }

    @Test
    void ensureCurrentSession_should_defer_when_tui_without_session_or_overrides() {
        when(sessions.current()).thenReturn(null);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        // TUI 首页：不建会话，但依然做完了存在性校验（无覆盖项时无需校验）
        assertNull(actual);
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_still_create_when_tui_given_overrides() {
        when(sessions.current()).thenReturn(null);
        when(sessions.create("coder", null, null, null)).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.TUI).agentId("coder").build());

        assertSame(session, actual);
        verify(sessions).switchTo(sessionId);
    }

    @Test
    void ensureCurrentSession_should_still_switch_when_tui_given_session() {
        when(sessions.switchTo("abc")).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(
                StartupOptions.builder(StartupOptions.Mode.TUI).sessionId("abc").build());

        assertSame(session, actual);
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void ensureCurrentSession_should_keep_existing_current_session_on_tui() {
        when(sessions.current()).thenReturn(session);

        Session actual = bootstrap.ensureCurrentSession(StartupOptions.builder(StartupOptions.Mode.TUI).build());

        assertSame(session, actual);
        verify(sessions, never()).create(null, null, null, null);
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        assertThrows(NullPointerException.class, () -> new SessionBootstrap(null, models, agents));
        assertThrows(NullPointerException.class, () -> new SessionBootstrap(sessions, null, agents));
        assertThrows(NullPointerException.class, () -> new SessionBootstrap(sessions, models, null));
    }

    @Test
    void ensureCurrentSession_should_reject_null_options() {
        assertThrows(NullPointerException.class, () -> bootstrap.ensureCurrentSession(null));
    }
}
