package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `/delete` 是唯一删数据且不可撤销的命令：必须先二次确认，确认后才真删。
 * 规则出处：08-application §8.9.4（删除「需外壳先做二次确认」）。
 */
class CommandDispatcherDeleteTest {

    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final SessionCursor cursor = new SessionCursor();

    private String shown() {
        try {
            return screen.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private CommandDispatcher dispatcher() {
        when(manageSession.delete(any(SessionId.class))).thenReturn(Result.ok((Void) null));
        when(manageSession.list()).thenReturn(Result.ok(
                java.util.Collections.<io.github.zcd0831.jellyfish.application.perception.port.SessionSummary>emptyList()));
        cursor.moveTo(SessionId.of("s1"));
        return new CommandDispatcher(new AnsiRenderer(new PrintStream(screen, true), 80, false),
                new TurnStats(), manageSession, mock(io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase.class),
                mock(io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase.class),
                mock(io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase.class),
                mock(io.github.zcd0831.jellyfish.application.action.port.CatalogRepository.class), cursor);
    }

    @Test
    void 第一次删除只问不做() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/delete");

        verify(manageSession, never()).delete(any(SessionId.class));
        assertTrue(shown().contains("再输入一次"),
                screen.toString());
    }

    @Test
    void 再确认一次才真的删() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/delete");
        dispatcher.command("/delete");

        verify(manageSession).delete(SessionId.of("s1"));
        assertTrue(shown().contains("已删除"));
    }

    @Test
    void 中间输入别的命令就取消() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/delete");
        dispatcher.command("/sessions");

        verify(manageSession, never()).delete(any(SessionId.class));
        assertFalse(shown().contains("已删除"));
    }
}
