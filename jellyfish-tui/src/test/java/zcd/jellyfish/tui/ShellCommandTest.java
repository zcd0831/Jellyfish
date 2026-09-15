package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellCommand} 的单元测试：验证外壳自有命令的判定边界与补全用的名字常量。
 *
 * @author zcd
 */
@DisplayName("ShellCommand 外壳命令判定")
class ShellCommandTest {

    @Test
    @DisplayName("命令名常量应可直接用于补全清单（不含前缀）")
    void constants_should_be_consistent() {
        assertEquals("exit", ShellCommand.EXIT_NAME);
        assertEquals("/exit", ShellCommand.EXIT);
        assertEquals("ui", UiCommand.NAME);
        assertEquals("/ui", UiCommand.PREFIX);
    }

    @Test
    @DisplayName("退出命令及其别名、带参数形式都应命中")
    void isExitCommand_should_matchExitAndQuit() {
        assertTrue(ShellCommand.isExitCommand("/exit"));
        assertTrue(ShellCommand.isExitCommand("/quit"));
        assertTrue(ShellCommand.isExitCommand("/exit now"));
        assertTrue(ShellCommand.isExitCommand("  /exit  "));
    }

    @Test
    @DisplayName("/ui 不是退出命令：两者必须能分开，否则敲 /ui 会直接退出")
    void isExitCommand_should_notMatchUi() {
        assertFalse(ShellCommand.isExitCommand("/ui"));
        assertFalse(ShellCommand.isExitCommand("/ui dock"));
    }

    @Test
    @DisplayName("外壳命令判定同时认 /exit 与 /ui：它们都不进内核命令注册表")
    void isShellCommand_should_matchExitQuitAndUi() {
        assertTrue(ShellCommand.isShellCommand("/exit"));
        assertTrue(ShellCommand.isShellCommand("/quit"));
        assertTrue(ShellCommand.isShellCommand("/exit now"));
        assertTrue(ShellCommand.isShellCommand("  /exit  "));
        assertTrue(ShellCommand.isShellCommand("/ui"));
        assertTrue(ShellCommand.isShellCommand("/ui dock off"));
    }

    @Test
    @DisplayName("非命令、其它命令与 null 不应命中")
    void isShellCommand_should_notMatchOthers() {
        assertFalse(ShellCommand.isShellCommand("/help"));
        assertFalse(ShellCommand.isShellCommand("exit"));
        assertFalse(ShellCommand.isShellCommand("/exiting"));
        assertFalse(ShellCommand.isShellCommand(null));
        assertFalse(ShellCommand.isShellCommand("/uix"));
    }
}
