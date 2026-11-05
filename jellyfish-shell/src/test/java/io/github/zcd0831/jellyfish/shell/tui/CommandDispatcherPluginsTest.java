package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插件的四条运行期入口：装 / 授权 / 收回 / 卸载；卸载要先确认。
 * 规则出处：08-application §8.9.6（装与授权分开）、§8.9.4（不可撤销的动作先二次确认）、
 * 05-action §5.8.6 规则 1（卸载只摘运行期，目录由用户自己删）。
 */
class CommandDispatcherPluginsTest {

    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);

    private String shown() {
        try {
            return screen.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private CommandDispatcher dispatcher() {
        when(plugins.install(any(PluginId.class), anyBoolean())).thenReturn(Result.ok((Void) null));
        when(plugins.grant(any(PluginId.class), anyString())).thenReturn(Result.ok((Void) null));
        when(plugins.revoke(any(PluginId.class), anyString())).thenReturn(Result.ok((Void) null));
        when(plugins.uninstall(any(PluginId.class))).thenReturn(Result.ok((Void) null));
        when(plugins.list()).thenReturn(java.util.Collections
                .<io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest>emptyList());
        SessionCursor cursor = new SessionCursor();
        cursor.moveTo(io.github.zcd0831.jellyfish.domain.shared.SessionId.of("s1"));
        return new CommandDispatcher(new AnsiRenderer(new PrintStream(screen, true), 80, false),
                new TurnStats(), mock(io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase.class),
                mock(io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase.class),
                mock(io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase.class),
                plugins, mock(io.github.zcd0831.jellyfish.application.action.port.CatalogRepository.class), cursor);
    }

    @Test
    void 光打plugins是列出已安装() {
        dispatcher().command("/plugins");

        verify(plugins).list();
        verify(plugins, never()).install(any(PluginId.class), anyBoolean());
    }

    @Test
    void 安装只落意图并提示还要授权() {
        dispatcher().command("/plugins install demo.echo");

        verify(plugins).install(PluginId.of("demo.echo"), false);
        assertTrue(shown().contains("/plugins grant demo.echo tools"), shown());
    }

    @Test
    void 安装可以带trusted() {
        dispatcher().command("/plugins install demo.echo trusted");

        verify(plugins).install(PluginId.of("demo.echo"), true);
    }

    @Test
    void 授权与收回都把能力名传下去() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/plugins grant demo.echo tools");
        dispatcher.command("/plugins revoke demo.echo tools");

        verify(plugins).grant(PluginId.of("demo.echo"), "tools");
        verify(plugins).revoke(PluginId.of("demo.echo"), "tools");
    }

    @Test
    void 卸载第一次只问不做() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/plugins uninstall demo.echo");

        verify(plugins, never()).uninstall(any(PluginId.class));
        assertTrue(shown().contains("再输入一次"), shown());
    }

    @Test
    void 再确认一次才真的卸载() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/plugins uninstall demo.echo");
        dispatcher.command("/plugins uninstall demo.echo");

        verify(plugins).uninstall(PluginId.of("demo.echo"));
        assertTrue(shown().contains("已卸载"));
    }

    @Test
    void 中间输入别的命令就取消卸载() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/plugins uninstall demo.echo");
        dispatcher.command("/plugins");
        dispatcher.command("/plugins uninstall demo.echo");

        verify(plugins, never()).uninstall(any(PluginId.class));
        assertTrue(shown().contains("已取消卸载"));
    }

    @Test
    void 卸载别的插件不会把待确认目标用掉() {
        CommandDispatcher dispatcher = dispatcher();

        dispatcher.command("/plugins uninstall demo.echo");
        dispatcher.command("/plugins uninstall other.plugin");

        verify(plugins, never()).uninstall(eq(PluginId.of("demo.echo")));
        assertFalse(shown().contains("已卸载"), shown());
    }

    @Test
    void 缺参数时给用法而不是静默() {
        dispatcher().command("/plugins grant demo.echo");

        assertTrue(shown().contains("/plugins grant <id> tools"), shown());
    }
}
