package zcd.jellyfish.infra.event.command;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.PermissionCheckCommand;
import zcd.jellyfish.api.event.command.PermissionDecision;
import zcd.jellyfish.api.event.command.PluginCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandRegistry} 的单元测试：验证唯一性、覆盖、越权、回收与解析。
 *
 * @author zcd
 */
class CommandRegistryTest {

    /** 被测注册表。 */
    private final CommandRegistry registry = new CommandRegistry();

    @Test
    void resolve_should_return_handler_when_registered() {
        // Given
        registry.register("builtin", false, PluginCommand.class, "calc", command -> "ok", RegisterOptions.DEFAULT);

        // When
        Object handler = registry.resolve(new PluginCommand("calc", Object.class, null));

        // Then
        assertNotNull(handler);
    }

    @Test
    void resolve_should_throw_no_handler_when_not_registered() {
        // When
        CommandException exception = assertThrows(CommandException.class,
                () -> registry.resolve(new PluginCommand("missing", Object.class, null)));

        // Then
        assertEquals(CommandException.Code.NO_HANDLER, exception.getCode());
    }

    @Test
    void resolve_should_throw_ambiguous_when_both_type_unique_and_route_key_match() {
        // Given
        registry.register("builtin", false, PluginCommand.class, null, command -> "any", RegisterOptions.DEFAULT);
        registry.register("plugin-a", true, PluginCommand.class, "calc", command -> "one", RegisterOptions.DEFAULT);

        // When
        CommandException exception = assertThrows(CommandException.class,
                () -> registry.resolve(new PluginCommand("calc", Object.class, null)));

        // Then
        assertEquals(CommandException.Code.AMBIGUOUS_HANDLER, exception.getCode());
    }

    @Test
    void register_should_throw_when_conflict_without_override() {
        // Given
        registry.register("builtin", false, PluginCommand.class, "calc", command -> "one", RegisterOptions.DEFAULT);

        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register("plugin-a", true, PluginCommand.class, "calc",
                command -> "two", RegisterOptions.DEFAULT));
    }

    @Test
    void register_should_replace_when_override_declared() {
        // Given
        registry.register("builtin", false, PluginCommand.class, "calc", command -> "one", RegisterOptions.DEFAULT);

        // When
        registry.register("plugin-a", true, PluginCommand.class, "calc", command -> "two",
                RegisterOptions.override(true));

        // Then
        assertNotNull(registry.resolve(new PluginCommand("calc", Object.class, null)));
        assertTrue(registry.render().contains("overrides builtin"));
        assertTrue(registry.render().contains("plugin-a"));
    }

    @Test
    void register_should_throw_when_plugin_registers_non_extensible_command() {
        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register("plugin-a", true, PermissionCheckCommand.class,
                null, command -> PermissionDecision.allow("ok"), RegisterOptions.DEFAULT));
    }

    @Test
    void unregisterAll_should_remove_registrations_of_owner() {
        // Given
        registry.register("plugin-a", true, PluginCommand.class, "calc", command -> "one", RegisterOptions.DEFAULT);
        registry.register("plugin-b", true, PluginCommand.class, "other", command -> "two", RegisterOptions.DEFAULT);

        // When
        int removed = registry.unregisterAll("plugin-a");

        // Then
        assertEquals(1, removed);
        assertThrows(CommandException.class,
                () -> registry.resolve(new PluginCommand("calc", Object.class, null)));
        assertNotNull(registry.resolve(new PluginCommand("other", Object.class, null)));
    }

    @Test
    void subscription_close_should_remove_registration() {
        // Given
        Subscription subscription = registry.register("plugin-a", true, PluginCommand.class, "calc",
                command -> "one", RegisterOptions.DEFAULT);

        // When
        subscription.close();

        // Then
        assertThrows(CommandException.class,
                () -> registry.resolve(new PluginCommand("calc", Object.class, null)));
    }

    @Test
    void render_should_return_empty_when_no_registration() {
        // Then
        assertTrue(registry.render().isEmpty());
        assertTrue(registry.isEmpty());
    }

    @Test
    void clear_should_remove_all_registrations() {
        // Given
        registry.register("plugin-a", true, PluginCommand.class, "calc", command -> "one", RegisterOptions.DEFAULT);

        // When
        registry.clear();

        // Then
        assertTrue(registry.isEmpty());
    }
}
