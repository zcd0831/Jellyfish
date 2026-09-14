package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandDescriptor} 的单元测试：验证别名校验、去重与不可变性。
 *
 * @author zcd
 */
class CommandDescriptorTest {

    @Test
    void getters_should_return_constructed_values() {
        // Given
        CommandDescriptor descriptor = new CommandDescriptor("切换 agent", "<agentId>", Arrays.asList("a", "ag"));

        // Then
        assertEquals("切换 agent", descriptor.getSummary());
        assertEquals("<agentId>", descriptor.getUsage());
        assertEquals(Arrays.asList("a", "ag"), descriptor.getAliases());
    }

    @Test
    void fields_should_be_absent_when_not_provided() {
        // When
        CommandDescriptor descriptor = new CommandDescriptor(null, null, null);

        // Then
        assertNull(descriptor.getSummary());
        assertNull(descriptor.getUsage());
        assertTrue(descriptor.getAliases().isEmpty());
    }

    @Test
    void aliases_should_be_defensive_copy_and_unmodifiable() {
        // Given
        List<String> aliases = new ArrayList<String>();
        aliases.add("h");
        CommandDescriptor descriptor = new CommandDescriptor("帮助", null, aliases);

        // When：改动入参不应影响名片
        aliases.add("?");

        // Then
        assertEquals(1, descriptor.getAliases().size());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getAliases().add("x"));
    }

    @Test
    void aliases_should_be_deduplicated_keeping_order() {
        // Given：重复声明同一条命令自己的别名不构成冲突
        CommandDescriptor descriptor = new CommandDescriptor("帮助", null, Arrays.asList("h", "?", "h"));

        // Then
        assertEquals(Arrays.asList("h", "?"), descriptor.getAliases());
    }

    @Test
    void constructor_should_throw_when_alias_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new CommandDescriptor("说明", null, Arrays.asList("h", null)));
        assertThrows(JellyfishException.class, () -> new CommandDescriptor("说明", null, Arrays.asList("  ")));
    }

    @Test
    void constructor_should_throw_when_alias_contains_whitespace() {
        // When / Then：别名是拼在命令名位置上的 token，含空白就永远不可能被解析出来
        assertThrows(JellyfishException.class, () -> new CommandDescriptor("说明", null, Arrays.asList("h elp")));
        assertThrows(JellyfishException.class, () -> new CommandDescriptor("说明", null, Arrays.asList("h\telp")));
    }

    @Test
    void constructor_should_throw_when_alias_carries_command_prefix() {
        // When / Then：输入阶段已把前缀剥掉，带前缀的别名是永不命中的死别名
        assertThrows(JellyfishException.class, () -> new CommandDescriptor("说明", null, Arrays.asList("/h")));
    }

    @Test
    void toString_should_render_aliases() {
        // When / Then
        assertEquals("CommandDescriptor{aliases=[h]}",
                new CommandDescriptor("帮助", null, Arrays.asList("h")).toString());
    }
}
