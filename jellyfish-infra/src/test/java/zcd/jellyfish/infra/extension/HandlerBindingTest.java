package zcd.jellyfish.infra.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link HandlerBinding} 的单元测试：验证来源与处理器的透传和入参校验。
 *
 * @author zcd
 */
class HandlerBindingTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        ExtensionHandler<CommandRequest, CommandResult> handler = request -> CommandResult.ok("ok");

        // When
        HandlerBinding<CommandRequest, CommandResult> binding = new HandlerBinding<>("plugin-a", handler);

        // Then
        assertEquals("plugin-a", binding.getOwner());
        assertSame(handler, binding.getHandler());
    }

    @Test
    void constructor_should_throw_when_owner_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new HandlerBinding<CommandRequest, CommandResult>(null,
                request -> CommandResult.ok("ok")));
        assertThrows(JellyfishException.class, () -> new HandlerBinding<CommandRequest, CommandResult>("  ",
                request -> CommandResult.ok("ok")));
    }

    @Test
    void constructor_should_throw_when_handler_is_null() {
        // When / Then
        assertThrows(NullPointerException.class,
                () -> new HandlerBinding<CommandRequest, CommandResult>("plugin-a", null));
    }

    @Test
    void toString_should_render_owner() {
        // When
        String text = new HandlerBinding<CommandRequest, CommandResult>("guard-plugin",
                request -> CommandResult.ok("ok")).toString();

        // Then
        assertEquals("HandlerBinding{owner=guard-plugin}", text);
    }
}
