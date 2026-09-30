package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ShortcutBinding;
import zcd.jellyfish.infra.ui.OwnedShortcut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginShortcuts} 的单元测试：仲裁、命令存在性校验与候选清单。
 *
 * @author zcd
 */
@DisplayName("插件键位表")
class PluginShortcutsTest {

    /** 可用命令名。 */
    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList("todo", "session"));

    @Test
    @DisplayName("没有声明时返回空表：键盘行为与没有这个扩展点逐字段一致")
    void resolve_should_returnNone_when_empty() {
        assertSame(PluginShortcuts.NONE, PluginShortcuts.resolve(null, COMMANDS));
        assertSame(PluginShortcuts.NONE, PluginShortcuts.resolve(new ArrayList<OwnedShortcut>(), COMMANDS));
        assertTrue(PluginShortcuts.NONE.commandOf("ctrl+b") == null);
        assertTrue(PluginShortcuts.NONE.getCandidates().isEmpty());
    }

    @Test
    @DisplayName("按声明顺序（order 升序）先到者胜")
    void resolve_should_let_first_binding_win() {
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("early", "ctrl+b", "todo"),
                owned("late", "ctrl+b", "session")), COMMANDS);

        assertEquals("todo", shortcuts.commandOf("ctrl+b"));
        assertEquals(1, shortcuts.getCandidates().size());
        assertTrue(shortcuts.getCandidates().get(0).contains("late"), shortcuts.getCandidates().toString());
        assertTrue(shortcuts.getCandidates().get(0).contains("todo"), shortcuts.getCandidates().toString());
    }

    @Test
    @DisplayName("指向不存在命令的绑定被剔除并记入候选")
    void resolve_should_drop_binding_with_unknown_command() {
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("plugin-a", "ctrl+b", "ghost"),
                owned("plugin-b", "ctrl+d", "todo")), COMMANDS);

        assertNull(shortcuts.commandOf("ctrl+b"));
        assertEquals("todo", shortcuts.commandOf("ctrl+d"));
        assertEquals(1, shortcuts.getCandidates().size());
        assertTrue(shortcuts.getCandidates().get(0).contains("命令不存在"),
                shortcuts.getCandidates().toString());
    }

    @Test
    @DisplayName("被剔除的绑定不会占用键位")
    void resolve_should_not_let_dropped_binding_occupy_key() {
        // 先到者指向一条不存在的命令时，后来的那条应当照常生效——否则一个坏声明会白占一个键
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("broken", "ctrl+b", "ghost"),
                owned("plugin-b", "ctrl+b", "todo")), COMMANDS);

        assertEquals("todo", shortcuts.commandOf("ctrl+b"));
        assertEquals(1, shortcuts.getCandidates().size());
    }

    @Test
    @DisplayName("多个键位各自独立生效")
    void resolve_should_keep_distinct_keys() {
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("plugin-a", "ctrl+b", "todo"),
                owned("plugin-b", "ctrl+d", "session")), COMMANDS);

        assertEquals("todo", shortcuts.commandOf("ctrl+b"));
        assertEquals("session", shortcuts.commandOf("ctrl+d"));
        assertEquals(2, shortcuts.getCommands().size());
        assertFalse(shortcuts.isEmpty());
    }

    @Test
    @DisplayName("空键位与未声明的键位都返回 null")
    void commandOf_should_return_null_for_unknown_key() {
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("plugin-a", "ctrl+b", "todo")), COMMANDS);

        assertNull(shortcuts.commandOf(null));
        assertNull(shortcuts.commandOf("ctrl+z"));
    }

    @Test
    @DisplayName("命令清单读不到时所有键位失效，而不是抛错")
    void resolve_should_drop_everything_when_command_names_unavailable() {
        PluginShortcuts shortcuts = PluginShortcuts.resolve(Arrays.asList(
                owned("plugin-a", "ctrl+b", "todo")), null);

        assertNull(shortcuts.commandOf("ctrl+b"));
        assertEquals(1, shortcuts.getCandidates().size());
    }

    /**
     * 造一条带来源的绑定。
     *
     * @param owner   来源
     * @param key     键位
     * @param command 命令名
     * @return 绑定
     */
    private static OwnedShortcut owned(String owner, String key, String command) {
        return new OwnedShortcut(owner, new ShortcutBinding(key, command, null));
    }
}
