package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellCommand} 的单元测试：验证外壳自有命令的判定边界、补全用的名字常量，
 * 以及外壳侧的候选入口（{@code /ui} 靠它才能「选中即弹选择页」）。
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
        assertEquals("thinking", ShellCommand.THINKING_NAME);
        assertEquals("/thinking", ShellCommand.THINKING);
        assertEquals("toolargs", ShellCommand.TOOL_ARGS_NAME);
        assertEquals("/toolargs", ShellCommand.TOOL_ARGS);
        assertEquals("mouse", MouseCommand.NAME);
        assertEquals("/mouse", MouseCommand.PREFIX);
    }

    @Test
    @DisplayName("/toolargs 是外壳命令，且不与 /thinking /exit 误判")
    void isToolArgsCommand_should_onlyMatchToolArgs() {
        assertTrue(ShellCommand.isToolArgsCommand("/toolargs"));
        assertTrue(ShellCommand.isToolArgsCommand("  /toolargs  "));
        assertTrue(ShellCommand.isToolArgsCommand("/toolargs on"));
        assertTrue(ShellCommand.isShellCommand("/toolargs"));

        assertFalse(ShellCommand.isToolArgsCommand("/toolarg"));
        assertFalse(ShellCommand.isToolArgsCommand("/thinking"));
        assertFalse(ShellCommand.isToolArgsCommand("/exit"));
        assertFalse(ShellCommand.isToolArgsCommand("toolargs"));
        assertFalse(ShellCommand.isToolArgsCommand(null));
        assertFalse(ShellCommand.isExitCommand("/toolargs"));
    }

    @Test
    @DisplayName("/thinking 是外壳命令，且与 /exit /ui 互不误判")
    void isThinkingCommand_should_onlyMatchThinking() {
        assertTrue(ShellCommand.isThinkingCommand("/thinking"));
        assertTrue(ShellCommand.isThinkingCommand("  /thinking  "));
        assertTrue(ShellCommand.isThinkingCommand("/thinking on"));
        assertTrue(ShellCommand.isShellCommand("/thinking"));

        assertFalse(ShellCommand.isThinkingCommand("/think"));
        assertFalse(ShellCommand.isThinkingCommand("/toolargs"));
        assertFalse(ShellCommand.isThinkingCommand("/exit"));
        assertFalse(ShellCommand.isThinkingCommand("/ui"));
        assertFalse(ShellCommand.isThinkingCommand("thinking"));
        assertFalse(ShellCommand.isThinkingCommand(null));
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
        assertTrue(ShellCommand.isShellCommand("/thinking"));
        assertTrue(ShellCommand.isShellCommand("/toolargs"));
        assertTrue(ShellCommand.isShellCommand("/mouse"));
        assertTrue(ShellCommand.isShellCommand("/mouse off"));
    }

    @Test
    @DisplayName("/mouse 不是退出命令：否则敲 /mouse 会直接退出界面")
    void isExitCommand_should_notMatchMouse() {
        assertFalse(ShellCommand.isExitCommand("/mouse"));
        assertFalse(ShellCommand.isExitCommand("/mouse off"));
    }

    @Test
    @DisplayName("非命令、其它命令与 null 不应命中")
    void isShellCommand_should_notMatchOthers() {
        assertFalse(ShellCommand.isShellCommand("/help"));
        assertFalse(ShellCommand.isShellCommand("exit"));
        assertFalse(ShellCommand.isShellCommand("/exiting"));
        assertFalse(ShellCommand.isShellCommand(null));
        assertFalse(ShellCommand.isShellCommand("/uix"));
        assertFalse(ShellCommand.isShellCommand("/mousex"));
    }

    @Test
    @DisplayName("/ui 的候选由外壳自己给出：命令域答不出来，缺了它补全面板选中 /ui 只会回填命令名")
    void options_should_offerRegions_when_uiCommand() {
        List<CommandChoice> options = ShellCommand.options(UiCommand.NAME, new UiPlacement(), null);

        assertFalse(options.isEmpty());
        assertTrue(valuesOf(options).contains("dock"));
        assertTrue(valuesOf(options).contains("status"));
    }

    @Test
    @DisplayName("候选与 /ui 真正执行时是同一份：两处各造一份迟早漂移")
    void options_should_matchWhatUiCommandGives_when_uiCommand() {
        UiPlacement placement = new UiPlacement();

        assertEquals(valuesOf(UiCommand.execute(UiCommand.PREFIX, placement, null, null).getChoices()),
                valuesOf(ShellCommand.options(UiCommand.NAME, placement, null)));
    }

    @Test
    @DisplayName("开合型外壳命令与命令域命令一律给空清单：它们没有可挑的取值")
    void options_should_beEmpty_when_noChoices() {
        UiPlacement placement = new UiPlacement();

        for (String name : Arrays.asList(ShellCommand.EXIT_NAME, ShellCommand.THINKING_NAME,
                ShellCommand.TOOL_ARGS_NAME, MouseCommand.NAME, "resume", null)) {
            assertTrue(ShellCommand.options(name, placement, null).isEmpty(), String.valueOf(name));
        }
    }

    /**
     * 取候选清单里的取值，便于跨两份候选比对（{@link CommandChoice} 没有 {@code equals}）。
     *
     * @param choices 候选清单
     * @return 取值列表
     */
    private static List<String> valuesOf(List<CommandChoice> choices) {
        List<String> values = new ArrayList<String>();
        for (CommandChoice choice : choices) {
            values.add(choice.getValue());
        }
        return values;
    }
}
