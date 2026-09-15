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
    }

    @Test
    @DisplayName("退出命令及其别名、带参数形式都应命中")
    void isShellCommand_should_matchExitAndQuit() {
        assertTrue(ShellCommand.isShellCommand("/exit"));
        assertTrue(ShellCommand.isShellCommand("/quit"));
        assertTrue(ShellCommand.isShellCommand("/exit now"));
        assertTrue(ShellCommand.isShellCommand("  /exit  "));
    }

    @Test
    @DisplayName("非命令、其它命令与 null 不应命中")
    void isShellCommand_should_notMatchOthers() {
        assertFalse(ShellCommand.isShellCommand("/help"));
        assertFalse(ShellCommand.isShellCommand("exit"));
        assertFalse(ShellCommand.isShellCommand("/exiting"));
        assertFalse(ShellCommand.isShellCommand(null));
    }
}
