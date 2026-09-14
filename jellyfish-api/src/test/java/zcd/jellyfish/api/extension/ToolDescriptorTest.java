package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolDescriptor} 的单元测试：验证字段校验、默认值与不可变性。
 *
 * @author zcd
 */
class ToolDescriptorTest {

    @Test
    void constructor_should_reject_blank_name() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new ToolDescriptor(null, "desc"));
        assertThrows(JellyfishException.class, () -> new ToolDescriptor("  ", "desc"));
    }

    @Test
    void getters_should_return_constructed_values() {
        // Given
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("expression", "string");
        ToolDescriptor descriptor = new ToolDescriptor("calculator", "算一下", parameters,
                Arrays.asList("expression"));

        // Then
        assertEquals("calculator", descriptor.getName());
        assertEquals("算一下", descriptor.getDescription());
        assertEquals("string", descriptor.getParameters().get("expression"));
        assertEquals(Arrays.asList("expression"), descriptor.getRequired());
    }

    @Test
    void collections_should_be_defensive_copies() {
        // Given
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("expression", "string");
        List<String> required = new ArrayList<>();
        required.add("expression");
        ToolDescriptor descriptor = new ToolDescriptor("calculator", "desc", parameters, required);

        // When：改动入参不应影响描述符
        parameters.put("extra", "number");
        required.add("extra");

        // Then
        assertEquals(1, descriptor.getParameters().size());
        assertEquals(1, descriptor.getRequired().size());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getParameters().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getRequired().add("x"));
    }

    @Test
    void collections_should_be_empty_when_absent() {
        // When
        ToolDescriptor descriptor = new ToolDescriptor("calculator", null);

        // Then
        assertNull(descriptor.getDescription());
        assertTrue(descriptor.getParameters().isEmpty());
        assertTrue(descriptor.getRequired().isEmpty());
    }

    @Test
    void toString_should_render_name() {
        // When / Then
        assertEquals("ToolDescriptor{name=calculator}", new ToolDescriptor("calculator", null).toString());
    }
}
