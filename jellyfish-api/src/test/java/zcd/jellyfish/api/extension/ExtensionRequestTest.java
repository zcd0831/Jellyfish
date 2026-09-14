package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionRequest} 的单元测试：以 {@link ToolCallRequest} 为具体实现，验证只读元信息与路由键。
 * <p>
 * 请求不再携带应答槽与截止时间：结果由处理器直接返回，超时护栏属于调用点，因此这里不覆盖这两类语义。
 *
 * @author zcd
 */
class ExtensionRequestTest {

    @Test
    void getRouteKey_should_return_tool_name() {
        // Given
        ToolCallRequest request = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), "session-1");

        // Then
        assertEquals("calculator", request.getRouteKey());
        assertEquals(ToolCallResult.class, request.getResultType());
        assertEquals("session-1", request.getSessionId());
    }

    @Test
    void getSessionId_should_be_null_when_constructed_without_session() {
        // When
        ToolCallRequest request = new ToolCallRequest("calculator", null);

        // Then
        assertNull(request.getSessionId());
    }

    @Test
    void getArguments_should_return_unmodifiable_copy() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("expression", "1+1");
        ToolCallRequest request = new ToolCallRequest("calculator", arguments, null);

        // When：改动入参与返回值都不应影响请求内部状态
        arguments.put("expression", "2+2");

        // Then
        assertEquals("1+1", request.getArguments().get("expression"));
        assertThrows(UnsupportedOperationException.class, () -> request.getArguments().put("x", 1));
    }

    @Test
    void getArguments_should_return_empty_map_when_arguments_absent() {
        // When
        ToolCallRequest request = new ToolCallRequest("calculator", null);

        // Then
        assertTrue(request.getArguments().isEmpty());
    }

    @Test
    void constructor_should_throw_when_tool_name_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new ToolCallRequest(null, null));
        assertThrows(JellyfishException.class, () -> new ToolCallRequest("  ", null));
    }

    @Test
    void toString_should_render_route_key() {
        // Given
        ToolCallRequest request = new ToolCallRequest("calculator", null);

        // Then
        assertEquals("ToolCallRequest{routeKey=calculator}", request.toString());
    }

    @Test
    void getResultType_should_never_be_null() {
        // Given
        ToolCallResult result = new ToolCallResult("calculator", 42);

        // Then
        assertSame("calculator", result.getToolName());
        assertEquals(42, result.getOutput());
        assertEquals("ToolCallResult{toolName=calculator, output=42}", result.toString());
    }
}
