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
 * {@link AgentDefinition} 的单元测试：验证字段透传、权限缺省、key 回填与「提示词只来自 md」。
 *
 * @author zcd
 */
class AgentDefinitionTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        AgentPermissions permissions = new AgentPermissions(Collections.singletonList("bash"), null, null);

        // When
        AgentDefinition definition = new AgentDefinition("coder", "通用编码助手", permissions);

        // Then
        assertEquals("coder", definition.getAgentId());
        assertEquals("通用编码助手", definition.getDescription());
        assertSame(permissions, definition.getPermissions());
        // 提示词不再由 JSON 提供，反序列化产物该字段必为 null
        assertNull(definition.getSystemPrompt());
    }

    @Test
    void getPermissions_should_return_empty_object_when_null() {
        // When
        AgentDefinition definition = new AgentDefinition("coder", null, null);

        // Then
        assertTrue(definition.getPermissions().isEmpty());
        assertNull(definition.getDescription());
        assertNull(definition.getSystemPrompt());
    }

    @Test
    void withAgentId_should_return_copy_without_touching_original() {
        // Given
        AgentDefinition original = new AgentDefinition(null, "描述", null).withSystemPrompt("prompt");

        // When
        AgentDefinition filled = original.withAgentId("coder");

        // Then
        assertNotSame(original, filled);
        assertNull(original.getAgentId());
        assertEquals("coder", filled.getAgentId());
        assertEquals("描述", filled.getDescription());
        // 回填 key 不能丢掉已补上的提示词
        assertEquals("prompt", filled.getSystemPrompt());
    }

    @Test
    void withSystemPrompt_should_return_copy_with_prompt() {
        // Given
        AgentDefinition original = new AgentDefinition("coder", "描述", null);

        // When
        AgentDefinition withPrompt = original.withSystemPrompt("You are Jellyfish.");

        // Then
        assertNotSame(original, withPrompt);
        assertNull(original.getSystemPrompt());
        assertEquals("You are Jellyfish.", withPrompt.getSystemPrompt());
        assertEquals("coder", withPrompt.getAgentId());
    }

    @Test
    void toString_should_not_expose_system_prompt() {
        // Given
        AgentDefinition definition = new AgentDefinition("coder", null, null).withSystemPrompt("secret prompt");

        // When
        String rendered = definition.toString();

        // Then
        assertTrue(rendered.contains("coder"));
        assertTrue(!rendered.contains("secret prompt"));
    }

    @Test
    void deserialization_should_ignore_system_prompt_field() {
        // Given：旧配置里可能还写着 systemPrompt，提示词已改由同名 md 文件提供
        String json = "{\"description\":\"desc\",\"systemPrompt\":\"prompt\","
                + "\"permissions\":{\"deniedTools\":[\"bash\"]}}";

        // When
        AgentDefinition definition = ObjectMapperWrapper.readValue(json, AgentDefinition.class);

        // Then
        assertEquals("desc", definition.getDescription());
        assertNull(definition.getSystemPrompt());
        assertEquals(Collections.singletonList("bash"), definition.getPermissions().getDeniedTools());
        assertNull(definition.getAgentId());
    }

    @Test
    void deserialization_should_bind_inline_agent_id() {
        // Given：内置 default-agent.json 显式声明 agentId（用户 agents.json 的条目靠 map key 回填）
        String json = "{\"agentId\":\"jellyfish\",\"description\":\"系统默认 agent\"}";

        // When
        AgentDefinition definition = ObjectMapperWrapper.readValue(json, AgentDefinition.class);

        // Then
        assertEquals("jellyfish", definition.getAgentId());
        assertEquals("系统默认 agent", definition.getDescription());
        assertNull(definition.getSystemPrompt());
    }
}
