package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolActivation} 与 {@link ToolActivationRequest} 的单元测试。
 *
 * @author zcd
 */
class ToolActivationTest {

    @Test
    void abstain_should_not_be_decided() {
        ToolActivation activation = ToolActivation.abstain();

        assertFalse(activation.isDecided());
        assertFalse(activation.isHidden());
        assertNull(activation.getReason());
    }

    @Test
    void visible_should_be_decided_but_not_hidden() {
        ToolActivation activation = ToolActivation.visible();

        assertTrue(activation.isDecided());
        assertFalse(activation.isHidden());
    }

    @Test
    void hidden_should_carry_reason() {
        ToolActivation activation = ToolActivation.hidden("本会话未连接该服务");

        assertTrue(activation.isDecided());
        assertTrue(activation.isHidden());
        assertEquals("本会话未连接该服务", activation.getReason());
    }

    @Test
    void hidden_should_tolerate_absent_reason() {
        assertNull(ToolActivation.hidden(null).getReason());
    }

    @Test
    void request_should_carry_session_facts_and_descriptor() {
        ToolDescriptor descriptor = new ToolDescriptor("mcp_search", "搜索", Collections.emptyMap(),
                Collections.<String>emptyList());

        ToolActivationRequest request = new ToolActivationRequest("s1", "coder", descriptor,
                PermissionMode.PLAN);

        assertEquals("s1", request.getSessionId());
        assertEquals("coder", request.getAgentId());
        assertEquals("mcp_search", request.getToolName());
        assertEquals(descriptor, request.getDescriptor());
        assertEquals(PermissionMode.PLAN, request.getPermissionMode());
        assertEquals(ToolActivation.class, request.getResultType());
        // 类型级扩展点：一个工具该不该出现可以由多个插件各自表态
        assertNull(request.getRouteKey());
    }

    @Test
    void request_should_tolerate_null_descriptor() {
        ToolActivationRequest request = new ToolActivationRequest("s1", null, null, null);

        assertNull(request.getToolName());
        assertNull(request.getDescriptor());
    }
}
