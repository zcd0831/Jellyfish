package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.CommandRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link CallbackRegistration} 的单元测试：验证注册项字段（含描述符）原样透出。
 *
 * @author zcd
 */
class CallbackRegistrationTest {

    @Test
    void getters_should_return_all_constructed_values() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = callback -> "ok";
        CallbackRegistration registration = new CallbackRegistration("plugin-a", CommandRequest.class, "calc",
                handler, "descriptor", 7L, 42, "builtin");

        // Then
        assertEquals("plugin-a", registration.getOwner());
        assertEquals(CommandRequest.class, registration.getCallbackType());
        assertEquals("calc", registration.getRouteKey());
        assertSame(handler, registration.getHandler());
        assertEquals("descriptor", registration.getDescriptor());
        assertEquals(7L, registration.getSequence());
        assertEquals(42, registration.getOrder());
        assertEquals("builtin", registration.getOverriddenOwner());
    }

    @Test
    void getters_should_return_nulls_when_optional_fields_absent() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = callback -> "ok";
        CallbackRegistration registration = new CallbackRegistration("builtin", CommandRequest.class, null,
                handler, null, 1L, 0, null);

        // Then
        assertNull(registration.getRouteKey());
        assertNull(registration.getDescriptor());
        assertNull(registration.getOverriddenOwner());
    }
}
