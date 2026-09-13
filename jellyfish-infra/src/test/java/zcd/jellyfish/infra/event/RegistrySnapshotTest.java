package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.callback.ExtensionPointRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrySnapshot} 的单元测试：验证空快照与三张注册表的渲染。
 *
 * @author zcd
 */
class RegistrySnapshotTest {

    /** 扩展点定义注册表。 */
    private final ExtensionPointRegistry extensionPointRegistry = ExtensionPointRegistry.withBuiltIns();

    /** 回调注册表。 */
    private final CallbackRegistry callbackRegistry = new CallbackRegistry(extensionPointRegistry);

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    @Test
    void isEmpty_should_return_true_and_render_placeholder_when_nothing_registered() {
        // Given
        ExtensionPointRegistry emptyDefinitions = new ExtensionPointRegistry();
        CallbackRegistry emptyCallbacks = new CallbackRegistry(emptyDefinitions);

        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(emptyCallbacks, new EventRegistry(), emptyDefinitions);

        // Then
        assertTrue(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("no extension point, callback or notification registered"));
    }

    @Test
    void isEmpty_should_return_false_and_render_all_registries_when_registered() {
        // Given
        callbackRegistry.register("builtin", false, PluginRequest.class, "calc", callback -> "ok",
                RegisterOptions.DEFAULT);
        eventRegistry.subscribe("metrics", ConfigWarningEvent.class, null, event -> {
            // 仅用于产生一条订阅诊断记录
        });

        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(callbackRegistry, eventRegistry, extensionPointRegistry);

        // Then
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("extensionPoints:"));
        assertTrue(snapshot.render().contains("command.provide"));
        assertTrue(snapshot.render().contains("callbacks:"));
        assertTrue(snapshot.render().contains("calc"));
        assertTrue(snapshot.render().contains("notifications:"));
        assertTrue(snapshot.render().contains("ConfigWarningEvent"));
    }
}
