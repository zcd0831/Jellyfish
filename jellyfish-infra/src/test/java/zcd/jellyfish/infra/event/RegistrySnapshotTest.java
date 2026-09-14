package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.notification.EventRegistry;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrySnapshot}（过渡期双视图）的单元测试：验证空快照与扩展点/通知两部分的渲染。
 *
 * @author zcd
 */
class RegistrySnapshotTest {

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    @Test
    void isEmpty_should_return_true_and_render_placeholder_when_nothing_registered() {
        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(new ExtensionRegistry(new TypeRegistry()),
                new EventRegistry());

        // Then
        assertTrue(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("no extension handler or notification registered"));
    }

    @Test
    void isEmpty_should_return_false_and_render_all_registries_when_registered() {
        // Given
        extensions.handle("builtin", CommandRequest.class, "calc", null, request -> "ok",
                RegisterOptions.DEFAULT);
        eventRegistry.subscribe("metrics", ConfigWarningEvent.class, null, event -> {
            // 仅用于产生一条订阅诊断记录
        });

        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(extensions, eventRegistry);

        // Then
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("registrations:"));
        assertTrue(snapshot.render().contains("calc"));
        assertTrue(snapshot.render().contains("notifications:"));
        assertTrue(snapshot.render().contains("ConfigWarningEvent"));
    }
}
