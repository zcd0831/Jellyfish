package zcd.jellyfish.infra.event.command;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.PluginCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandKey} 的单元测试：验证空值校验、相等性与诊断文本。
 *
 * @author zcd
 */
class CommandKeyTest {

    @Test
    void of_should_throw_when_command_type_is_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> CommandKey.of(null, "calc"));
    }

    @Test
    void getters_should_return_constructed_values() {
        // When
        CommandKey key = CommandKey.of(PluginCommand.class, "calc");

        // Then
        assertEquals(PluginCommand.class, key.getCommandType());
        assertEquals("calc", key.getRouteKey());
    }

    @Test
    void getRouteKey_should_return_null_when_type_unique() {
        // When
        CommandKey key = CommandKey.of(PluginCommand.class, null);

        // Then
        assertNull(key.getRouteKey());
    }

    @Test
    void equals_and_hashCode_should_match_when_same_type_and_route_key() {
        // Given
        CommandKey key = CommandKey.of(PluginCommand.class, "calc");
        CommandKey same = CommandKey.of(PluginCommand.class, "calc");

        // Then
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
    }

    @Test
    void equals_should_differ_when_route_key_differs() {
        // Given
        CommandKey key = CommandKey.of(PluginCommand.class, "calc");
        CommandKey other = CommandKey.of(PluginCommand.class, "other");

        // Then
        assertNotEquals(key, other);
    }

    @Test
    void equals_should_differ_when_command_type_differs() {
        // Given
        CommandKey key = CommandKey.of(PluginCommand.class, null);
        CommandKey other = CommandKey.of(OtherCommand.class, null);

        // Then
        assertNotEquals(key, other);
    }

    @Test
    void equals_should_return_false_for_other_types() {
        // Given
        CommandKey key = CommandKey.of(PluginCommand.class, "calc");

        // Then
        assertFalse(key.equals("calc"));
        assertTrue(key.equals(key));
    }

    @Test
    void toString_should_render_route_key_and_type_unique_marker() {
        // When / Then
        assertEquals("PluginCommand#calc", CommandKey.of(PluginCommand.class, "calc").toString());
        assertEquals("PluginCommand#<type-unique>", CommandKey.of(PluginCommand.class, null).toString());
    }

    /**
     * 另一个命令类型，仅用于验证键的类型维度。
     *
     * @author zcd
     */
    private static final class OtherCommand extends Command<String> {

        /**
         * 构造测试命令。
         */
        private OtherCommand() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
