package zcd.jellyfish.infra.command;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandDescriptor;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandInfo} 的单元测试：验证字段透传与「没有名片」时的空安全读取。
 *
 * @author zcd
 */
class CommandInfoTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        CommandDescriptor descriptor = new CommandDescriptor("切换 agent", "<agentId>", Arrays.asList("a"));

        // When
        CommandInfo info = new CommandInfo("agent", descriptor);

        // Then
        assertEquals("agent", info.getName());
        assertSame(descriptor, info.getDescriptor());
        assertEquals(Arrays.asList("a"), info.getAliases());
        assertEquals("切换 agent", info.getSummary());
        assertEquals("<agentId>", info.getUsage());
    }

    @Test
    void accessors_should_be_null_safe_without_descriptor() {
        // When：不给命令写名片的插件确实存在，清单项仍然要可用
        CommandInfo info = new CommandInfo("plain", null);

        // Then
        assertNull(info.getDescriptor());
        assertTrue(info.getAliases().isEmpty());
        assertNull(info.getSummary());
        assertNull(info.getUsage());
    }

    @Test
    void constructor_should_throw_when_name_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new CommandInfo(null, null));
        assertThrows(JellyfishException.class, () -> new CommandInfo("  ", null));
    }

    @Test
    void toString_should_render_name() {
        // When / Then
        assertEquals("CommandInfo{name=help}", new CommandInfo("help", null).toString());
    }

    @Test
    void sessionRequired_should_defaultToTrue_withoutDescriptor() {
        // When：没写名片的命令在运行时缺省就是「需要会话」
        CommandInfo info = new CommandInfo("plain", null);

        // Then：与 CommandDescriptor 三参构造器同一个口径，两处不能给出不同答案
        assertTrue(info.isSessionRequired());
    }

    @Test
    void sessionRequired_should_followDescriptor() {
        // Given
        CommandInfo required = new CommandInfo("status", new CommandDescriptor("概要", null, null, true));
        CommandInfo free = new CommandInfo("help", new CommandDescriptor("帮助", null, null, false));

        // Then
        assertTrue(required.isSessionRequired());
        assertFalse(free.isSessionRequired());
    }
}
