package zcd.jellyfish.infra.event.command;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.command.CommandHandler;
import zcd.jellyfish.api.event.command.PluginCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandRegistration} 的单元测试：验证注册项字段原样透出。
 *
 * @author zcd
 */
class CommandRegistrationTest {

    @Test
    void getters_should_return_all_constructed_values() {
        // Given
        CommandHandler<PluginCommand, Object> handler = command -> "ok";
        CommandRegistration registration = new CommandRegistration("plugin-a", PluginCommand.class, "calc",
                handler, true, 7L, "builtin");

        // Then
        assertEquals("plugin-a", registration.getOwner());
        assertEquals(PluginCommand.class, registration.getCommandType());
        assertEquals("calc", registration.getRouteKey());
        assertSame(handler, registration.getHandler());
        assertTrue(registration.isFromPlugin());
        assertEquals(7L, registration.getSequence());
        assertEquals("builtin", registration.getOverriddenOwner());
    }

    @Test
    void getters_should_return_nulls_when_optional_fields_absent() {
        // Given
        CommandHandler<PluginCommand, Object> handler = command -> "ok";
        CommandRegistration registration = new CommandRegistration("builtin", PluginCommand.class, null,
                handler, false, 1L, null);

        // Then
        assertNull(registration.getRouteKey());
        assertNull(registration.getOverriddenOwner());
        assertFalse(registration.isFromPlugin());
    }
}
