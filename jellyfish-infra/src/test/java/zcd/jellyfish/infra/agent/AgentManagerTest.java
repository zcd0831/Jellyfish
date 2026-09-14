package zcd.jellyfish.infra.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.AgentsLoadedEvent;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.AgentPermissions;
import zcd.jellyfish.infra.config.AgentSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.permission.PermissionPolicy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentManager} 的单元测试：验证装载时序（构造期空索引、refresh 才装载）、
 * 默认 agent 解析三档、fail-open 边界与装载事件。
 * <p>
 * 索引用真实的 {@link AgentRegistry}（纯内存值存储，mock 它等于把被测行为重写一遍），
 * 只 mock 外部协作者 {@link RuntimeConfig} 与 {@link EventPublisher}。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class AgentManagerTest {

    /** agent 标识。 */
    private static final String CODER = "coder";

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    @Test
    void constructor_should_build_empty_index_when_config_not_loaded_yet() {
        // Given：构造期 RuntimeConfig 尚未刷新，拿到的是空配置
        when(runtimeConfig.getAgentSettings()).thenReturn(new AgentSettings(null, null));

        // When
        AgentManager manager = newManager();

        // Then
        assertTrue(manager.all().isEmpty());
        assertNull(manager.find(CODER));
        assertNull(manager.getDefaultAgentId());
        assertNull(manager.resolveDefault());
        // 构造期总线可能尚未启动，不能广播事件
        verify(events, never()).publish(any());
    }

    @Test
    void refresh_should_load_definitions_from_config_when_reload_config_false() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition(CODER, null)));

        // When
        manager.refresh(false);

        // Then
        assertEquals(CODER, manager.find(CODER).getAgentId());
        assertEquals("prompt", manager.systemPromptOf(CODER));
        assertEquals(CODER, manager.getDefaultAgentId());
        verify(runtimeConfig, never()).refresh();
    }

    @Test
    void refresh_should_reload_config_when_reload_config_true() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();

        // When
        manager.refresh(true);

        // Then
        verify(runtimeConfig).refresh();
    }

    @Test
    void refresh_should_publish_agents_loaded_event_when_rebuilt() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();
        when(runtimeConfig.getAgentSettings())
                .thenReturn(settings(CODER, definition(CODER, null), definition("writer", null)));

        // When
        manager.refresh(false);

        // Then
        ArgumentCaptor<AgentsLoadedEvent> captor = ArgumentCaptor.forClass(AgentsLoadedEvent.class);
        verify(events).publish(captor.capture());
        assertEquals(CODER, captor.getValue().getDefaultAgentId());
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList(CODER, "writer")),
                captor.getValue().getAgentIds());
    }

    @Test
    void refresh_should_publish_empty_event_when_no_agent_configured() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();

        // When
        manager.refresh(false);

        // Then
        ArgumentCaptor<AgentsLoadedEvent> captor = ArgumentCaptor.forClass(AgentsLoadedEvent.class);
        verify(events).publish(captor.capture());
        assertTrue(captor.getValue().getAgentIds().isEmpty());
        assertNull(captor.getValue().getDefaultAgentId());
    }

    @Test
    void refresh_should_keep_index_when_event_publish_fails() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition(CODER, null)));
        doThrow(new IllegalStateException("bus down")).when(events).publish(any());

        // When
        manager.refresh(false);

        // Then
        assertNotNull(manager.find(CODER));
    }

    @Test
    void require_should_throw_when_agent_id_blank() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.require(null));
        assertThrows(JellyfishException.class, () -> manager.require("  "));
    }

    @Test
    void require_should_throw_when_agent_not_declared() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition(CODER, null)));
        AgentManager manager = newManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.require("ghost"));
    }

    @Test
    void require_should_return_definition_when_declared() {
        // Given
        AgentDefinition definition = definition(CODER, null);
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition));
        AgentManager manager = newManager();

        // When / Then
        assertSame(definition, manager.require(CODER));
    }

    @Test
    void resolveDefault_should_return_configured_agent_when_exists() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(
                settings("writer", definition(CODER, null), definition("writer", null)));
        AgentManager manager = newManager();

        // When / Then
        assertEquals("writer", manager.resolveDefault().getAgentId());
    }

    @Test
    void resolveDefault_should_return_first_agent_when_default_not_configured() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(
                settings(null, definition(CODER, null), definition("writer", null)));
        AgentManager manager = newManager();

        // When / Then
        assertEquals(CODER, manager.resolveDefault().getAgentId());
    }

    @Test
    void resolveDefault_should_return_first_agent_when_configured_default_missing() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(
                settings("ghost", definition(CODER, null), definition("writer", null)));
        AgentManager manager = newManager();

        // When / Then
        assertEquals(CODER, manager.resolveDefault().getAgentId());
    }

    @Test
    void resolveDefault_should_return_null_when_no_agent_configured() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();

        // When / Then
        assertNull(manager.resolveDefault());
    }

    @Test
    void systemPromptOf_should_return_null_when_not_declared() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        AgentManager manager = newManager();

        // When / Then
        assertNull(manager.systemPromptOf(null));
        assertNull(manager.systemPromptOf("ghost"));
    }

    @Test
    void policyOf_should_be_unrestricted_when_agent_not_declared() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition(CODER, null)));
        AgentManager manager = newManager();

        // When / Then：fail-open 只覆盖「取不到策略」
        assertNotNull(manager.policyOf(null));
        assertTrue(manager.policyOf(null).isEmpty());
        assertTrue(manager.policyOf("ghost").isEmpty());
        assertTrue(manager.policyOf("ghost").allows("bash"));
    }

    @Test
    void policyOf_should_return_declared_policy() {
        // Given
        AgentPermissions permissions = new AgentPermissions(Collections.singletonList("bash"), null, null);
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(CODER, definition(CODER, permissions)));
        AgentManager manager = newManager();

        // When
        PermissionPolicy policy = manager.policyOf(CODER);

        // Then
        assertTrue(policy.denies("bash"));
        assertTrue(policy.allows("read_file"));
    }

    /**
     * 构造被测实例。
     *
     * @return AgentManager 实例
     */
    private AgentManager newManager() {
        return new AgentManager(runtimeConfig, new AgentRegistry(events), events);
    }

    /**
     * 构造空 agent 配置。
     *
     * @return 空配置
     */
    private static AgentSettings emptySettings() {
        return new AgentSettings(null, null);
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
     * @param agentId     agent 标识
     * @param permissions 权限段，可为 {@code null}
     * @return agent 定义
     */
    private static AgentDefinition definition(String agentId, AgentPermissions permissions) {
        return new AgentDefinition(agentId, "desc", "prompt", permissions);
    }
}
