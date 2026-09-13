package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackException;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.ExtensionShape;
import zcd.jellyfish.api.event.callback.PermissionCheckRequest;
import zcd.jellyfish.api.event.callback.PermissionDecision;
import zcd.jellyfish.api.event.callback.PluginRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackRegistry} 的单元测试：验证唯一性、覆盖、越权、回收、唯一解析与全部解析。
 *
 * @author zcd
 */
class CallbackRegistryTest {

    /** 被测注册表。 */
    private final CallbackRegistry registry = new CallbackRegistry(ExtensionPointRegistry.withBuiltIns());

    @Test
    void resolveUnique_should_return_handler_when_registered() {
        // Given
        registry.register("builtin", false, PluginRequest.class, "calc", callback -> "ok", RegisterOptions.DEFAULT);

        // When
        Object handler = registry.resolveUnique(new PluginRequest("calc", Object.class, null));

        // Then
        assertNotNull(handler);
    }

    @Test
    void resolveUnique_should_throw_no_handler_when_not_registered() {
        // When
        CallbackException exception = assertThrows(CallbackException.class,
                () -> registry.resolveUnique(new PluginRequest("missing", Object.class, null)));

        // Then
        assertEquals(CallbackException.Code.NO_HANDLER, exception.getCode());
    }

    @Test
    void resolveUnique_should_throw_ambiguous_when_both_type_unique_and_route_key_match() {
        // Given
        registry.register("builtin", false, PluginRequest.class, null, callback -> "any", RegisterOptions.DEFAULT);
        registry.register("plugin-a", true, PluginRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When
        CallbackException exception = assertThrows(CallbackException.class,
                () -> registry.resolveUnique(new PluginRequest("calc", Object.class, null)));

        // Then
        assertEquals(CallbackException.Code.AMBIGUOUS_HANDLER, exception.getCode());
    }

    @Test
    void resolveAll_should_return_empty_when_not_registered() {
        // When
        List<CallbackHandler<?, ?>> handlers = registry.resolveAll(new ContributionRequest());

        // Then
        assertTrue(handlers.isEmpty());
    }

    @Test
    void resolveAll_should_return_handlers_sorted_by_order_when_non_unique() {
        // Given
        ExtensionPointRegistry definitions = ExtensionPointRegistry.withBuiltIns();
        definitions.register(ContributionRequest.class);
        CallbackRegistry nonUnique = new CallbackRegistry(definitions);
        CallbackHandler<ContributionRequest, String> late = callback -> "late";
        CallbackHandler<ContributionRequest, String> early = callback -> "early";
        nonUnique.register("late-owner", false, ContributionRequest.class, null, late, RegisterOptions.order(10));
        nonUnique.register("early-owner", false, ContributionRequest.class, null, early, RegisterOptions.order(-5));

        // When
        List<CallbackHandler<?, ?>> handlers = nonUnique.resolveAll(new ContributionRequest());

        // Then
        assertEquals(2, handlers.size());
        assertSame(early, handlers.get(0));
        assertSame(late, handlers.get(1));
    }

    @Test
    void register_should_allow_multiple_when_shape_not_unique() {
        // Given
        ExtensionPointRegistry definitions = ExtensionPointRegistry.withBuiltIns();
        definitions.register(ContributionRequest.class);
        CallbackRegistry nonUnique = new CallbackRegistry(definitions);

        // When
        nonUnique.register("owner-a", false, ContributionRequest.class, null, callback -> "a", RegisterOptions.DEFAULT);
        nonUnique.register("owner-b", false, ContributionRequest.class, null, callback -> "b", RegisterOptions.DEFAULT);

        // Then
        assertEquals(2, nonUnique.resolveAll(new ContributionRequest()).size());
    }

    @Test
    void register_should_throw_when_conflict_without_override() {
        // Given
        registry.register("builtin", false, PluginRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register("plugin-a", true, PluginRequest.class, "calc",
                callback -> "two", RegisterOptions.DEFAULT));
    }

    @Test
    void register_should_replace_when_override_declared() {
        // Given
        registry.register("builtin", false, PluginRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When
        registry.register("plugin-a", true, PluginRequest.class, "calc", callback -> "two",
                RegisterOptions.override(true));

        // Then
        assertNotNull(registry.resolveUnique(new PluginRequest("calc", Object.class, null)));
        assertTrue(registry.render().contains("overrides builtin"));
        assertTrue(registry.render().contains("plugin-a"));
    }

    @Test
    void register_should_throw_when_plugin_registers_non_extensible_callback() {
        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register("plugin-a", true, PermissionCheckRequest.class,
                null, callback -> PermissionDecision.allow("ok"), RegisterOptions.DEFAULT));
    }

    @Test
    void unregisterAll_should_remove_registrations_of_owner() {
        // Given
        registry.register("plugin-a", true, PluginRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);
        registry.register("plugin-b", true, PluginRequest.class, "other", callback -> "two", RegisterOptions.DEFAULT);

        // When
        int removed = registry.unregisterAll("plugin-a");

        // Then
        assertEquals(1, removed);
        assertThrows(CallbackException.class,
                () -> registry.resolveUnique(new PluginRequest("calc", Object.class, null)));
        assertNotNull(registry.resolveUnique(new PluginRequest("other", Object.class, null)));
    }

    @Test
    void subscription_close_should_remove_registration() {
        // Given
        Subscription subscription = registry.register("plugin-a", true, PluginRequest.class, "calc",
                callback -> "one", RegisterOptions.DEFAULT);

        // When
        subscription.close();

        // Then
        assertThrows(CallbackException.class,
                () -> registry.resolveUnique(new PluginRequest("calc", Object.class, null)));
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
        registry.register("plugin-a", true, PluginRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When
        registry.clear();

        // Then
        assertTrue(registry.isEmpty());
    }

    /**
     * 测试用非唯一形状回调：A 贡献（0..N + 有返回值）。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.contribute", shape = ExtensionShape.CONTRIBUTE)
    private static final class ContributionRequest extends Callback<String> {

        /**
         * 构造测试回调。
         */
        private ContributionRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
