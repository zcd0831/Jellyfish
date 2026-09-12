package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.command.CommandRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrySnapshot} 的单元测试：验证空快照与两层注册内容渲染。
 *
 * @author zcd
 */
class RegistrySnapshotTest {

    /** 命令注册表。 */
    private final CommandRegistry commandRegistry = new CommandRegistry();

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    @Test
    void isEmpty_should_return_true_and_render_placeholder_when_nothing_registered() {
        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(commandRegistry, eventRegistry);

        // Then
        assertTrue(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("no command or notification registered"));
    }

    @Test
    void isEmpty_should_return_false_and_render_both_registries_when_registered() {
        // Given
        commandRegistry.register("builtin", false, PluginCommand.class, "calc", command -> "ok",
                RegisterOptions.DEFAULT);
        eventRegistry.subscribe("metrics", ConfigWarningEvent.class, null, event -> {
            // 仅用于产生一条订阅诊断记录
        });

        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(commandRegistry, eventRegistry);

        // Then
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("commands:"));
        assertTrue(snapshot.render().contains("calc"));
        assertTrue(snapshot.render().contains("notifications:"));
        assertTrue(snapshot.render().contains("ConfigWarningEvent"));
    }
}
