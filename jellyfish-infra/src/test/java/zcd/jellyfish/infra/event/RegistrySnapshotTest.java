package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrySnapshot} 的单元测试：验证空快照与两张注册表的渲染。
 *
 * @author zcd
 */
class RegistrySnapshotTest {

    /** 回调注册表。 */
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    @Test
    void isEmpty_should_return_true_and_render_placeholder_when_nothing_registered() {
        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(new CallbackRegistry(), new EventRegistry());

        // Then
        assertTrue(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("no callback or notification registered"));
    }

    @Test
    void isEmpty_should_return_false_and_render_all_registries_when_registered() {
        // Given
        callbackRegistry.register("builtin", CommandRequest.class, "calc", callback -> "ok",
                RegisterOptions.DEFAULT);
        eventRegistry.subscribe("metrics", ConfigWarningEvent.class, null, event -> {
            // 仅用于产生一条订阅诊断记录
        });

        // When
        RegistrySnapshot snapshot = RegistrySnapshot.of(callbackRegistry, eventRegistry);

        // Then
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.render().contains("callbacks:"));
        assertTrue(snapshot.render().contains("calc"));
        assertTrue(snapshot.render().contains("notifications:"));
        assertTrue(snapshot.render().contains("ConfigWarningEvent"));
    }
}
