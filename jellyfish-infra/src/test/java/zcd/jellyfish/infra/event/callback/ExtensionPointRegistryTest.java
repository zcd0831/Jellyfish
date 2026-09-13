package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.ExtensionShape;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.ToolCallRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionPointRegistry} 的单元测试：验证内置定义、标识唯一性与未知类型 fail-fast。
 *
 * @author zcd
 */
class ExtensionPointRegistryTest {

    @Test
    void withBuiltIns_should_register_all_builtin_callback_types() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // Then
        assertTrue(registry.isRegistered(ToolCallRequest.class));
        assertTrue(registry.isRegistered(zcd.jellyfish.api.event.callback.PermissionCheckRequest.class));
        assertTrue(registry.isRegistered(PluginRequest.class));
    }

    @Test
    void definitionOf_should_return_same_instance_as_register() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // When
        ExtensionPointDefinition definition = registry.definitionOf(ToolCallRequest.class);

        // Then
        assertEquals("tool.provide", definition.getId());
        assertEquals(ToolCallRequest.class, definition.getCallbackType());
    }

    @Test
    void definitionOf_should_throw_when_callback_type_not_registered() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // When / Then
        assertThrows(JellyfishException.class, () -> registry.definitionOf(UnregisteredRequest.class));
    }

    @Test
    void register_should_throw_when_id_duplicated_by_another_type() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // When / Then
        assertThrows(JellyfishException.class, () -> registry.register(DuplicateIdRequest.class));
    }

    @Test
    void register_should_accept_same_type_twice_idempotently() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // When
        ExtensionPointDefinition first = registry.register(ToolCallRequest.class);
        ExtensionPointDefinition second = registry.register(ToolCallRequest.class);

        // Then
        assertSame(first, second);
    }

    @Test
    void clear_should_remove_all_definitions() {
        // Given
        ExtensionPointRegistry registry = ExtensionPointRegistry.withBuiltIns();

        // When
        registry.clear();

        // Then
        assertFalse(registry.isRegistered(ToolCallRequest.class));
    }

    /**
     * 测试用未登记回调。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.unregistered", shape = ExtensionShape.CONTRIBUTE)
    private static final class UnregisteredRequest extends Callback<String> {

        /** 构造测试回调。 */
        private UnregisteredRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用重复标识回调：故意复用 {@code tool.provide}。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "tool.provide", shape = ExtensionShape.CONTRIBUTE)
    private static final class DuplicateIdRequest extends Callback<String> {

        /** 构造测试回调。 */
        private DuplicateIdRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
