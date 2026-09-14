package zcd.jellyfish.infra.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.AgentPermissions;
import zcd.jellyfish.infra.config.AgentSettings;
import zcd.jellyfish.infra.permission.PermissionPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AgentRegistry} 的单元测试：验证索引重建、策略转换、未命中语义与非法条目容错。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class AgentRegistryTest {

    /** agent 标识，供多处断言复用。 */
    private static final String CODER = "coder";

    /** 事件发布入口，用于验证配置告警。 */
    @Mock
    private EventPublisher events;

    @Test
    void find_should_return_null_when_agent_id_null_or_not_declared() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(null, definition(CODER, null)));

        // When / Then
        assertNull(registry.find(null));
        assertNull(registry.find("ghost"));
        assertEquals(CODER, registry.find(CODER).getAgentId());
    }

    @Test
    void policyOf_should_return_policy_of_declared_agent() {
        // Given
        AgentPermissions permissions = new AgentPermissions(
                Collections.singletonList("bash"), Collections.singletonList("write_file"),
                Arrays.asList("read_file", "grep"));
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(null, definition(CODER, permissions)));

        // Then
        PermissionPolicy policy = registry.policyOf(CODER);
        assertTrue(policy.denies("bash"));
        assertTrue(policy.requiresApproval("write_file"));
        assertTrue(policy.allows("read_file"));
        assertFalse(policy.allows("bash"));
    }

    @Test
    void policyOf_should_return_unrestricted_when_agent_not_declared() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(null, definition(CODER, null)));

        // When / Then
        PermissionPolicy unknown = registry.policyOf("ghost");
        assertTrue(unknown.isEmpty());
        assertTrue(registry.policyOf(null).isEmpty());
        assertTrue(unknown.allows("bash"));
    }

    @Test
    void policyOf_should_return_unrestricted_when_agent_declares_nothing() {
        // Given：三组全空的权限段等价于「无策略」，而不是「什么都不允许」
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(null, definition(CODER, new AgentPermissions(null, null, null))));

        // Then
        assertTrue(registry.policyOf(CODER).isEmpty());
        assertTrue(registry.policyOf(CODER).allows("bash"));
        verifyNoInteractions(events);
    }

    @Test
    void refresh_should_replace_previous_entries_wholesale() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(null, definition(CODER, null), definition("writer", null)));

        // When
        registry.refresh(settings(null, definition("reviewer", null)));

        // Then
        assertNull(registry.find(CODER));
        assertNull(registry.find("writer"));
        assertEquals("reviewer", registry.find("reviewer").getAgentId());
        assertTrue(registry.policyOf(CODER).isEmpty());
    }

    @Test
    void refresh_should_keep_order_of_configuration() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(null, definition("first", null), definition("second", null)));

        // Then
        assertEquals(Arrays.asList("first", "second"), idsOf(registry.all()));
    }

    @Test
    void refresh_should_skip_blank_key_and_warn() {
        // Given
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put("  ", new AgentDefinition(null, null, null, null));
        agents.put(CODER, definition(CODER, null));

        // When
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(new AgentSettings(null, agents));

        // Then：非法条目被跳过，合法条目照常入索引
        assertEquals(Collections.singletonList(CODER), idsOf(registry.all()));
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events).publish(captor.capture());
        assertTrue(captor.getValue().getMessage().contains("空白 key"));
    }

    @Test
    void refresh_should_skip_null_definition_and_warn() {
        // Given
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put("ghost", null);

        // When
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(new AgentSettings(null, agents));

        // Then
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.policyOf("ghost").isEmpty());
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events).publish(captor.capture());
        assertEquals("ghost", captor.getValue().getSource());
    }

    @Test
    void refresh_should_tolerate_null_settings() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(null, definition(CODER, null)));

        // When
        registry.refresh(null);

        // Then
        assertTrue(registry.all().isEmpty());
        assertNull(registry.getDefaultAgentId());
        verify(events, never()).publish(any());
    }

    @Test
    void getDefaultAgentId_should_return_null_when_blank() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings("   ", definition(CODER, null)));

        // Then
        assertNull(registry.getDefaultAgentId());
    }

    @Test
    void getDefaultAgentId_should_return_configured_value() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(CODER, definition(CODER, null)));

        // Then
        assertEquals(CODER, registry.getDefaultAgentId());
    }

    @Test
    void all_should_return_unmodifiable_collection() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(null, definition(CODER, null)));

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> registry.all().add(definition("other", null)));
    }

    @Test
    void refresh_should_not_warn_when_all_entries_valid() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(CODER, definition(CODER, null), definition("writer", null)));

        // Then
        verify(events, never()).publish(any());
    }

    @Test
    void constructor_should_reject_null_events() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new AgentRegistry(null));
    }

    /**
     * 构造 agent 配置。
     *
     * @param defaultAgent 默认 agentId，可为 {@code null}
     * @param definitions  agent 定义，顺序即配置顺序
     * @return agent 配置
     */
    private static AgentSettings settings(String defaultAgent, AgentDefinition... definitions) {
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            agents.put(definition.getAgentId(), definition);
        }
        return new AgentSettings(defaultAgent, agents);
    }

    /**
     * 构造 agent 定义。
     *
     * @param agentId     agent 标识，可为 {@code null}
     * @param permissions 权限段，可为 {@code null}
     * @return agent 定义
     */
    private static AgentDefinition definition(String agentId, AgentPermissions permissions) {
        return new AgentDefinition(agentId, "desc", "prompt", permissions);
    }

    /**
     * 提取定义集合里的 agentId，用于断言顺序。
     *
     * @param definitions agent 定义集合
     * @return agentId 列表
     */
    private static List<String> idsOf(Collection<AgentDefinition> definitions) {
        List<String> ids = new ArrayList<>();
        for (AgentDefinition definition : definitions) {
            ids.add(definition.getAgentId());
        }
        return ids;
    }
}
