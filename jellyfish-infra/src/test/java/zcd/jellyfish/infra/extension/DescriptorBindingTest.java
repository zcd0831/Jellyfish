package zcd.jellyfish.infra.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandDescriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DescriptorBinding} 的单元测试：验证三个字段的透传、可空语义与入参校验。
 *
 * @author zcd
 */
class DescriptorBindingTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        CommandDescriptor descriptor = new CommandDescriptor("帮助", "[命令]", null);

        // When
        DescriptorBinding<CommandDescriptor> binding = new DescriptorBinding<>("plugin-a", "help", descriptor);

        // Then
        assertEquals("plugin-a", binding.getOwner());
        assertEquals("help", binding.getRouteKey());
        assertSame(descriptor, binding.getDescriptor());
    }

    @Test
    void constructor_should_allow_null_route_key_and_descriptor() {
        // When：类型级注册没有路由键，「没写名片」也是合法状态
        DescriptorBinding<CommandDescriptor> binding = new DescriptorBinding<>("kernel", null, null);

        // Then
        assertNull(binding.getRouteKey());
        assertNull(binding.getDescriptor());
    }

    @Test
    void constructor_should_throw_when_owner_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new DescriptorBinding<CommandDescriptor>(null, "help", null));
        assertThrows(JellyfishException.class, () -> new DescriptorBinding<CommandDescriptor>("  ", "help", null));
    }

    @Test
    void toString_should_render_owner_and_route_key() {
        // When / Then
        assertEquals("DescriptorBinding{owner=plugin-a, routeKey=help}",
                new DescriptorBinding<CommandDescriptor>("plugin-a", "help", null).toString());
        assertEquals("DescriptorBinding{owner=kernel, routeKey=<type-wide>}",
                new DescriptorBinding<CommandDescriptor>("kernel", null, null).toString());
    }
}
