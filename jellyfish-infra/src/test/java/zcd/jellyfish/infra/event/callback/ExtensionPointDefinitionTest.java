package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.ExtensionShape;
import zcd.jellyfish.api.event.callback.PermissionCheckRequest;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.ToolCallRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionPointDefinition} 的单元测试：验证形状预设解析与逐项覆盖。
 *
 * @author zcd
 */
class ExtensionPointDefinitionTest {

    @Test
    void resolve_should_return_provide_preset_when_shape_is_provide() {
        // When
        ExtensionPointDefinition definition = ExtensionPointDefinition.resolve(ToolCallRequest.class);

        // Then
        assertEquals("tool.provide", definition.getId());
        assertEquals(ExtensionShape.PROVIDE, definition.getShape());
        assertTrue(definition.isUnique());
        assertFalse(definition.isOrdered());
        assertEquals(ExtensionPoint.EmptyPolicy.REQUIRED, definition.getEmptyPolicy());
        assertEquals(ExtensionPoint.ResultArity.ONE, definition.getResultArity());
        assertEquals(ExtensionPoint.Execution.INLINE, definition.getExecution());
        assertEquals(ExtensionPoint.FailurePolicy.FAIL_CLOSED, definition.getFailurePolicy());
        assertTrue(definition.isPluginExtensible());
    }

    @Test
    void resolve_should_derive_plugin_extensibility_from_plugin_extensible_annotation() {
        // When
        ExtensionPointDefinition definition = ExtensionPointDefinition.resolve(PermissionCheckRequest.class);

        // Then
        assertFalse(definition.isPluginExtensible());
    }

    @Test
    void resolve_should_apply_explicit_overrides_over_shape_defaults() {
        // When
        ExtensionPointDefinition definition = ExtensionPointDefinition.resolve(OverriddenRequest.class);

        // Then
        assertTrue(definition.isUnique());
        assertFalse(definition.isOrdered());
        assertEquals(ExtensionPoint.EmptyPolicy.REQUIRED, definition.getEmptyPolicy());
        assertEquals(ExtensionPoint.ResultArity.ONE, definition.getResultArity());
        assertEquals(ExtensionPoint.Execution.INLINE, definition.getExecution());
        assertEquals(ExtensionPoint.FailurePolicy.FAIL_OPEN, definition.getFailurePolicy());
    }

    @Test
    void resolve_should_throw_when_callback_type_not_annotated() {
        // When / Then
        assertThrows(JellyfishException.class, () -> ExtensionPointDefinition.resolve(PlainRequest.class));
    }

    @Test
    void resolve_should_throw_when_callback_type_is_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> ExtensionPointDefinition.resolve(null));
    }

    /**
     * 测试用「形状为 A 但逐项覆盖」的回调。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.overridden", shape = ExtensionShape.CONTRIBUTE,
            unique = ExtensionPoint.TriState.TRUE,
            ordered = ExtensionPoint.TriState.FALSE,
            emptyPolicy = ExtensionPoint.EmptyPolicy.REQUIRED,
            resultArity = ExtensionPoint.ResultArity.ONE,
            execution = ExtensionPoint.Execution.INLINE)
    private static final class OverriddenRequest extends Callback<String> {

        /** 构造测试回调。 */
        private OverriddenRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用未标注扩展点的回调。
     *
     * @author zcd
     */
    private static final class PlainRequest extends Callback<String> {

        /** 构造测试回调。 */
        private PlainRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
