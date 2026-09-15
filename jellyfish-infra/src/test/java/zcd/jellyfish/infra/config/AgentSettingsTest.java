package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSettings} 的单元测试：验证缺省值、只读性与反序列化。
 * <p>
 * 刻意没有 {@code defaultAgent}：默认 agent 是内置常量，用户配置改不了它。
 *
 * @author zcd
 */
class AgentSettingsTest {

    @Test
    void getters_should_return_empty_values_when_not_set() {
        // When
        AgentSettings settings = new AgentSettings(null);

        // Then
        assertTrue(settings.getAgents().isEmpty());
        assertTrue(settings.isEmpty());
    }

    @Test
    void getAgents_should_return_unmodifiable_map() {
        // Given
        AgentSettings settings = new AgentSettings(
                Collections.singletonMap("coder", new AgentDefinition("coder", null, null)));

        // When / Then
        Map<String, AgentDefinition> agents = settings.getAgents();
        assertThrows(UnsupportedOperationException.class,
                () -> agents.put("other", new AgentDefinition("other", null, null)));
    }

    @Test
    void isEmpty_should_return_false_when_agent_configured() {
        // Given / When
        AgentSettings settings = new AgentSettings(
                Collections.singletonMap("coder", new AgentDefinition(null, null, null)));

        // Then
        assertTrue(!settings.isEmpty());
    }

    @Test
    void deserialization_should_bind_definitions_and_ignore_default_agent() {
        // Given：defaultAgent 已废弃，写了也只当未知字段忽略
        String json = "{\"defaultAgent\":\"coder\",\"agents\":{\"coder\":{\"description\":\"desc\","
                + "\"systemPrompt\":\"prompt\",\"permissions\":{\"deniedTools\":[\"bash\"]}}}}";

        // When
        AgentSettings settings = ObjectMapperWrapper.readValue(json, AgentSettings.class);

        // Then
        AgentDefinition definition = settings.getAgents().get("coder");
        assertEquals("desc", definition.getDescription());
        // 提示词不再从 JSON 读取
        assertNull(definition.getSystemPrompt());
        assertEquals(Collections.singletonList("bash"), definition.getPermissions().getDeniedTools());
    }

    @Test
    void deserialization_should_tolerate_missing_agents_section() {
        // Given
        String json = "{}";

        // When
        AgentSettings settings = ObjectMapperWrapper.readValue(json, AgentSettings.class);

        // Then
        assertTrue(settings.isEmpty());
    }
}
