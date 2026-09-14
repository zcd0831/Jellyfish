package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.CommandRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackRegistry} 的单元测试：验证两条注册路径、覆盖、按来源回收与按 order 解析。
 *
 * @author zcd
 */
class CallbackRegistryTest {

    /** 被测注册表。 */
    private final CallbackRegistry registry = new CallbackRegistry();

    @Test
    void register_should_store_handler_when_key_is_free() {
        // When
        registry.register("builtin", CommandRequest.class, "calc", callback -> "ok", RegisterOptions.DEFAULT);

        // Then
        List<ExtensionHandler<?, ?>> resolved = registry.resolve(new CommandRequest("calc", Object.class, null));
        assertEquals(1, resolved.size());
    }

    @Test
    void register_should_throw_when_key_already_occupied_without_override() {
        // Given
        registry.register("builtin", CommandRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register("plugin-a", CommandRequest.class, "calc",
                callback -> "two", RegisterOptions.DEFAULT));
    }

    @Test
    void register_should_replace_when_override_declared() {
        // Given
        registry.register("builtin", CommandRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When
        registry.register("plugin-a", CommandRequest.class, "calc", callback -> "two",
                RegisterOptions.override(true));

        // Then
        assertTrue(registry.render().contains("overrides builtin"));
        assertTrue(registry.render().contains("plugin-a"));
    }

    @Test
    void register_should_throw_when_arguments_are_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> registry.register("builtin", null, "calc",
                callback -> "ok", RegisterOptions.DEFAULT));
        assertThrows(NullPointerException.class, () -> registry.register("builtin", CommandRequest.class, "calc",
                null, RegisterOptions.DEFAULT));
        assertThrows(NullPointerException.class, () -> registry.register("builtin", CommandRequest.class, "calc",
                callback -> "ok", null));
    }

    @Test
    void contribute_should_allow_multiple_handlers_for_same_type() {
        // When
        registry.contribute("owner-a", ContributionRequest.class, callback -> "a", RegisterOptions.DEFAULT);
        registry.contribute("owner-b", ContributionRequest.class, callback -> "b", RegisterOptions.DEFAULT);

        // Then
        assertEquals(2, registry.resolve(new ContributionRequest()).size());
    }

    @Test
    void resolve_should_return_all_handlers_sorted_by_order() {
        // Given
        ExtensionHandler<ContributionRequest, String> late = callback -> "late";
        ExtensionHandler<ContributionRequest, String> early = callback -> "early";
        registry.contribute("late-owner", ContributionRequest.class, late, RegisterOptions.order(10));
        registry.contribute("early-owner", ContributionRequest.class, early, RegisterOptions.order(-5));

        // When
        List<ExtensionHandler<?, ?>> handlers = registry.resolve(new ContributionRequest());

        // Then
        assertEquals(2, handlers.size());
        assertSame(early, handlers.get(0));
        assertSame(late, handlers.get(1));
    }

    @Test
    void resolve_should_return_empty_when_nothing_registered() {
        // When
        List<ExtensionHandler<?, ?>> handlers = registry.resolve(new ContributionRequest());

        // Then
        assertTrue(handlers.isEmpty());
    }

    @Test
    void resolve_should_match_type_wide_contribution_regardless_of_route_key() {
        // Given：类型级贡献（路由键为 null）对所有路由键都生效
        ExtensionHandler<CommandRequest, Object> typeWide = callback -> "any";
        registry.contribute("plugin-a", CommandRequest.class, typeWide, RegisterOptions.DEFAULT);
        registry.register("builtin", CommandRequest.class, "calc", callback -> "calc", RegisterOptions.DEFAULT);

        // Then：带路由键与不带路由键的回调都能命中类型级贡献
        assertEquals(2, registry.resolve(new CommandRequest("calc", Object.class, null)).size());
        assertEquals(1, registry.resolve(new CommandRequest("other", Object.class, null)).size());
    }

    @Test
    void unregisterAll_should_remove_registrations_of_owner() {
        // Given
        registry.register("plugin-a", CommandRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);
        registry.register("plugin-b", CommandRequest.class, "other", callback -> "two", RegisterOptions.DEFAULT);

        // When
        int removed = registry.unregisterAll("plugin-a");

        // Then
        assertEquals(1, removed);
        assertTrue(registry.resolve(new CommandRequest("calc", Object.class, null)).isEmpty());
        assertNotNull(registry.resolve(new CommandRequest("other", Object.class, null)).get(0));
    }

    @Test
    void subscription_close_should_remove_registration() {
        // Given
        Subscription subscription = registry.register("plugin-a", CommandRequest.class, "calc",
                callback -> "one", RegisterOptions.DEFAULT);

        // When
        subscription.close();

        // Then
        assertTrue(registry.resolve(new CommandRequest("calc", Object.class, null)).isEmpty());
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
        registry.register("plugin-a", CommandRequest.class, "calc", callback -> "one", RegisterOptions.DEFAULT);

        // When
        registry.clear();

        // Then
        assertTrue(registry.isEmpty());
    }

    /**
     * 测试用收集式回调。
     *
     * @author zcd
     */
    private static final class ContributionRequest extends ExtensionRequest<String> {

        /** 构造测试回调。 */
        private ContributionRequest() {
            super(String.class, null);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
