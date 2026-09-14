package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionCheckRequest} 的单元测试：验证类型级路由、结果类型、参数只读与模式缺省。
 *
 * @author zcd
 */
class PermissionCheckRequestTest {

    @Test
    void getRouteKey_should_be_null_because_extension_point_is_type_level() {
        // Given
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", null);

        // Then
        assertNull(request.getRouteKey());
    }

    @Test
    void getResultType_should_be_permission_veto() {
        // Given
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", null);

        // Then：插件侧结果只有两态，ASK 在插件侧不可表达
        assertEquals(PermissionVeto.class, request.getResultType());
    }

    @Test
    void constructor_should_default_mode_to_normal_and_session_to_null() {
        // When
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", null);

        // Then
        assertEquals(PermissionMode.NORMAL, request.getMode());
        assertNull(request.getSessionId());
    }

    @Test
    void constructor_should_keep_provided_mode_and_session() {
        // When
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", null,
                PermissionMode.PLAN, "session-1");

        // Then
        assertEquals(PermissionMode.PLAN, request.getMode());
        assertEquals("session-1", request.getSessionId());
        assertEquals("agent-a", request.getAgentId());
        assertEquals("read_file", request.getToolName());
    }

    @Test
    void getArguments_should_return_unmodifiable_copy() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "/tmp/a.txt");
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", arguments);

        // When：改动入参与返回值都不应影响请求内部状态
        arguments.put("path", "/tmp/b.txt");

        // Then
        assertEquals("/tmp/a.txt", request.getArguments().get("path"));
        assertThrows(UnsupportedOperationException.class, () -> request.getArguments().put("x", 1));
    }

    @Test
    void getArguments_should_return_empty_map_when_arguments_absent() {
        // When
        PermissionCheckRequest request = new PermissionCheckRequest("agent-a", "read_file", null);

        // Then
        assertTrue(request.getArguments().isEmpty());
    }

    @Test
    void constructor_should_allow_null_agent_id() {
        // When：未绑定 agent 时按无策略处理，由内核决定放行与否
        PermissionCheckRequest request = new PermissionCheckRequest(null, "read_file", null);

        // Then
        assertNull(request.getAgentId());
    }

    @Test
    void constructor_should_throw_when_tool_name_is_blank() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new PermissionCheckRequest("agent-a", null, null));
        assertThrows(JellyfishException.class, () -> new PermissionCheckRequest("agent-a", "  ", null));
    }
}
