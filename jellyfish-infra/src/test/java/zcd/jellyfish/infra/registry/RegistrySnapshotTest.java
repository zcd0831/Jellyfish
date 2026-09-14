package zcd.jellyfish.infra.registry;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolCallRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegistrySnapshot} 的单元测试：验证单表快照的渲染内容与空表语义。
 *
 * @author zcd
 */
class RegistrySnapshotTest {

    /** 被测注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    @Test
    void render_should_be_empty_when_registry_is_empty() {
        // When
        RegistrySnapshot snapshot = registry.snapshot();

        // Then
        assertTrue(snapshot.isEmpty());
        assertTrue(snapshot.render().isEmpty());
    }

    @Test
    void render_should_include_type_route_key_owner_order_and_descriptor() {
        // Given
        registry.registerUnique("plugin-a", ToolCallRequest.class, "calculator", "handler",
                new ToolDescriptor("calculator", "算一下"), 3, false);
        registry.registerShared("kernel", CommandRequest.class, null, "handler", null, 0);

        // When
        RegistrySnapshot snapshot = registry.snapshot();

        // Then
        String rendered = snapshot.render();
        assertFalse(snapshot.isEmpty());
        assertTrue(rendered.contains("registrations:"));
        assertTrue(rendered.contains("ToolCallRequest"));
        assertTrue(rendered.contains("calculator"));
        assertTrue(rendered.contains("order=3"));
        assertTrue(rendered.contains("<- plugin-a"));
        assertTrue(rendered.contains("descriptor=ToolDescriptor"));
        assertTrue(rendered.contains("<type-wide>"));
        assertTrue(rendered.contains("<- kernel"));
    }

    @Test
    void render_should_mark_overridden_owner() {
        // Given
        registry.registerUnique("builtin", CommandRequest.class, "calc", "one", null, 0, false);
        registry.registerUnique("plugin-a", CommandRequest.class, "calc", "two", null, 0, true);

        // When
        String rendered = registry.snapshot().render();

        // Then
        assertTrue(rendered.contains("(overrides builtin)"));
    }

    @Test
    void render_should_be_stable_and_ignore_later_registrations() {
        // Given
        registry.registerShared("a", CommandRequest.class, null, "handler", null, 0);
        RegistrySnapshot snapshot = registry.snapshot();

        // When：快照生成后再注册，不应影响已生成的快照
        registry.registerShared("b", CommandRequest.class, null, "handler", null, 0);

        // Then
        assertFalse(snapshot.render().contains("<- b"));
        assertTrue(registry.snapshot().render().contains("<- b"));
    }
}
