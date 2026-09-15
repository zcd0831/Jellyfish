package zcd.jellyfish.infra.agent;

import org.junit.jupiter.api.BeforeEach;
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
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentManager} 的单元测试：验证装载时序（构造期空索引、refresh 才装载）、
 * 默认 agent 恒为内置 agent、fail-open 边界与装载事件。
 * <p>
 * 索引用真实的 {@link AgentRegistry}（纯内存值存储，mock 它等于把被测行为重写一遍），
 * 只 mock 外部协作者 {@link RuntimeConfig} 与 {@link EventPublisher}。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class AgentManagerTest {

    /** 用户 agent 标识。 */
    private static final String CODER = "coder";

    /** 内置默认 agent 标识。 */
    private static final String SYSTEM = "jellyfish";

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /**
     * 统一让快照里的内置 agent 缺省为 {@code null}，需要它的用例自行覆盖。
     * 构造期就会读一次，因此该桩必然被使用。
     */
    @BeforeEach
    void stubSystemAgent() {
        lenient().when(runtimeConfig.getSystemAgent()).thenReturn(null);
    }

    @Test
    void constructor_should_build_empty_index_when_config_not_loaded_yet() {
        // Given：构造期 RuntimeConfig 尚未刷新，拿到的是空配置
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());

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
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition(CODER, null)));

        // When
        manager.refresh(false);

        // Then
        assertEquals(CODER, manager.find(CODER).getAgentId());
        assertEquals("prompt", manager.systemPromptOf(CODER));
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
        when(runtimeConfig.getSystemAgent()).thenReturn(systemAgent());
        when(runtimeConfig.getAgentSettings())
                .thenReturn(settings(definition(CODER, null), definition("writer", null)));

        // When
        manager.refresh(false);

        // Then：内置 agent 也会进索引，因此事件里带上它
        ArgumentCaptor<AgentsLoadedEvent> captor = ArgumentCaptor.forClass(AgentsLoadedEvent.class);
        verify(events).publish(captor.capture());
        assertEquals(SYSTEM, captor.getValue().getDefaultAgentId());
        assertEquals(new LinkedHashSet<>(Arrays.asList(SYSTEM, CODER, "writer")),
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
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition(CODER, null)));
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
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition(CODER, null)));
        AgentManager manager = newManager();

        // When / Then
        assertThrows(JellyfishException.class, () -> manager.require("ghost"));
    }

    @Test
    void require_should_return_definition_when_declared() {
        // Given
        AgentDefinition definition = definition(CODER, null);
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition));
        AgentManager manager = newManager();

        // When / Then
        assertSame(definition, manager.require(CODER));
    }

    @Test
    void resolveDefault_should_return_system_agent() {
        // Given：默认 agent 与用户配置无关，恒为内置 agent
        AgentDefinition system = systemAgent();
        when(runtimeConfig.getAgentSettings()).thenReturn(
                settings(definition(CODER, null), definition("writer", null)));
        when(runtimeConfig.getSystemAgent()).thenReturn(system);
        AgentManager manager = newManager();

        // When / Then
        assertSame(system, manager.resolveDefault());
        assertEquals(SYSTEM, manager.getDefaultAgentId());
    }

    @Test
    void resolveDefault_should_return_null_before_config_loaded() {
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
    void systemPromptOf_should_return_system_agent_prompt() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(emptySettings());
        when(runtimeConfig.getSystemAgent()).thenReturn(systemAgent());
        AgentManager manager = newManager();

        // When / Then
        assertEquals("system prompt", manager.systemPromptOf(SYSTEM));
    }

    @Test
    void policyOf_should_be_unrestricted_when_agent_not_declared() {
        // Given
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition(CODER, null)));
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
        when(runtimeConfig.getAgentSettings()).thenReturn(settings(definition(CODER, permissions)));
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
        return new AgentSettings(null);
    }

    /**
     * 构造 agent 配置。
     *
     * @param definitions agent 定义，顺序即配置顺序
     * @return agent 配置
     */
    private static AgentSettings settings(AgentDefinition... definitions) {
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            agents.put(definition.getAgentId(), definition);
        }
        return new AgentSettings(agents);
    }

    /**
     * 构造内置默认 agent。
     *
     * @return 内置 agent 定义
     */
    private static AgentDefinition systemAgent() {
        return new AgentDefinition(SYSTEM, "系统默认 agent", null).withSystemPrompt("system prompt");
    }

    /**
     * 构造 agent 定义。
     *
     * @param agentId     agent 标识
     * @param permissions 权限段，可为 {@code null}
     * @return agent 定义
     */
    private static AgentDefinition definition(String agentId, AgentPermissions permissions) {
        return new AgentDefinition(agentId, "desc", permissions).withSystemPrompt("prompt");
    }
}
