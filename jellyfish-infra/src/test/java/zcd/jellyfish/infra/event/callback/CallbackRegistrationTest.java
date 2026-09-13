package zcd.jellyfish.infra.event.callback;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.PluginRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackRegistration} 的单元测试：验证注册项字段原样透出。
 *
 * @author zcd
 */
class CallbackRegistrationTest {

    @Test
    void getters_should_return_all_constructed_values() {
        // Given
        CallbackHandler<PluginRequest, Object> handler = callback -> "ok";
        CallbackRegistration registration = new CallbackRegistration("plugin-a", PluginRequest.class, "calc",
                handler, true, 7L, 42, "builtin");

        // Then
        assertEquals("plugin-a", registration.getOwner());
        assertEquals(PluginRequest.class, registration.getCallbackType());
        assertEquals("calc", registration.getRouteKey());
        assertSame(handler, registration.getHandler());
        assertTrue(registration.isFromPlugin());
        assertEquals(7L, registration.getSequence());
        assertEquals(42, registration.getOrder());
        assertEquals("builtin", registration.getOverriddenOwner());
    }

    @Test
    void getters_should_return_nulls_when_optional_fields_absent() {
        // Given
        CallbackHandler<PluginRequest, Object> handler = callback -> "ok";
        CallbackRegistration registration = new CallbackRegistration("builtin", PluginRequest.class, null,
                handler, false, 1L, 0, null);

        // Then
        assertNull(registration.getRouteKey());
        assertNull(registration.getOverriddenOwner());
        assertFalse(registration.isFromPlugin());
    }
}
