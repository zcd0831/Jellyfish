package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentDefinition} 的单元测试：验证字段透传、权限缺省与 key 回填语义。
 *
 * @author zcd
 */
class AgentDefinitionTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        AgentPermissions permissions = new AgentPermissions(Collections.singletonList("bash"), null, null);

        // When
        AgentDefinition definition = new AgentDefinition("coder", "通用编码助手", "You are Jellyfish.", permissions);

        // Then
        assertEquals("coder", definition.getAgentId());
        assertEquals("通用编码助手", definition.getDescription());
        assertEquals("You are Jellyfish.", definition.getSystemPrompt());
        assertSame(permissions, definition.getPermissions());
    }

    @Test
    void getPermissions_should_return_empty_object_when_null() {
        // When
        AgentDefinition definition = new AgentDefinition("coder", null, null, null);

        // Then
        assertTrue(definition.getPermissions().isEmpty());
        assertNull(definition.getDescription());
        assertNull(definition.getSystemPrompt());
    }

    @Test
    void withAgentId_should_return_copy_without_touching_original() {
        // Given
        AgentDefinition original = new AgentDefinition(null, "描述", "prompt", null);

        // When
        AgentDefinition filled = original.withAgentId("coder");

        // Then
        assertNotSame(original, filled);
        assertNull(original.getAgentId());
        assertEquals("coder", filled.getAgentId());
        assertEquals("描述", filled.getDescription());
        assertEquals("prompt", filled.getSystemPrompt());
    }

    @Test
    void toString_should_not_expose_system_prompt() {
        // Given
        AgentDefinition definition = new AgentDefinition("coder", null, "secret prompt", null);

        // When
        String rendered = definition.toString();

        // Then
        assertTrue(rendered.contains("coder"));
        assertTrue(!rendered.contains("secret prompt"));
    }

    @Test
    void deserialization_should_bind_all_fields() {
        // Given
        String json = "{\"description\":\"desc\",\"systemPrompt\":\"prompt\","
                + "\"permissions\":{\"deniedTools\":[\"bash\"]}}";

        // When
        AgentDefinition definition = ObjectMapperWrapper.readValue(json, AgentDefinition.class);

        // Then
        assertEquals("desc", definition.getDescription());
        assertEquals("prompt", definition.getSystemPrompt());
        assertEquals(Collections.singletonList("bash"), definition.getPermissions().getDeniedTools());
        // agentId 只认配置 key，条目内部不声明标识
        assertNull(definition.getAgentId());
    }
}
