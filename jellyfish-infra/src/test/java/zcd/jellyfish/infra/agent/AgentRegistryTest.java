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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AgentRegistry} 的单元测试：验证索引重建、策略转换、未命中语义、非法条目容错，
 * 以及「内置默认 agent 不可被同名用户条目覆盖」这条规则。
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
        registry.refresh(settings(definition(CODER, null)), null);

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
        registry.refresh(settings(definition(CODER, permissions)), null);

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
        registry.refresh(settings(definition(CODER, null)), null);

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
        registry.refresh(settings(definition(CODER, new AgentPermissions(null, null, null))), null);

        // Then
        assertTrue(registry.policyOf(CODER).isEmpty());
        assertTrue(registry.policyOf(CODER).allows("bash"));
        verifyNoInteractions(events);
    }

    @Test
    void policyOf_should_deny_everything_when_allow_list_declared_empty() {
        // Given："allowedTools": [] 是「一个都不允许」，不能被当成「未配置」而全放行
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(definition(CODER,
                new AgentPermissions(null, null, Collections.<String>emptyList()))), null);

        // Then
        PermissionPolicy policy = registry.policyOf(CODER);
        assertFalse(policy.isEmpty());
        assertFalse(policy.allows("bash"));
        assertFalse(policy.allows(null));
    }

    @Test
    void refresh_should_replace_previous_entries_wholesale() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null), definition("writer", null)), null);

        // When
        registry.refresh(settings(definition("reviewer", null)), null);

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
        registry.refresh(settings(definition("first", null), definition("second", null)), null);

        // Then
        assertEquals(Arrays.asList("first", "second"), idsOf(registry.all()));
    }

    @Test
    void refresh_should_index_system_agent_first() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When：内置 agent 始终排在索引最前，用户 agent 追加在后
        registry.refresh(settings(definition(CODER, null)), systemAgent("jellyfish"));

        // Then
        assertEquals(Arrays.asList("jellyfish", CODER), idsOf(registry.all()));
        assertEquals("jellyfish", registry.find("jellyfish").getAgentId());
        verify(events, never()).publish(any());
    }

    @Test
    void refresh_should_keep_system_agent_and_warn_when_user_declares_same_id() {
        // Given
        AgentDefinition systemDefinition = systemAgent("jellyfish");
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(definition("jellyfish", null), definition(CODER, null)), systemDefinition);

        // Then：同名用户条目被跳过，留下的是内置定义本体
        assertEquals(Arrays.asList("jellyfish", CODER), idsOf(registry.all()));
        assertSame(systemDefinition, registry.find("jellyfish"));
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events).publish(captor.capture());
        assertTrue(captor.getValue().getMessage().contains("同名"));
    }

    @Test
    void refresh_should_skip_blank_key_and_warn() {
        // Given
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        agents.put("  ", new AgentDefinition(null, null, null));
        agents.put(CODER, definition(CODER, null));

        // When
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(new AgentSettings(agents), null);

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
        registry.refresh(new AgentSettings(agents), null);

        // Then
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.policyOf("ghost").isEmpty());
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        // 两条：一条说这个条目被跳过，一条说 policyOf 撞上了没声明的标识（见 policyOf_should_warn_once_*）
        verify(events, times(2)).publish(captor.capture());
        assertEquals("ghost", captor.getAllValues().get(0).getSource());
        assertTrue(captor.getAllValues().get(0).getMessage().contains("null"));
    }

    @Test
    void policyOf_should_warn_once_when_agent_not_declared() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);

        // When：同一个未知标识被问两次——它跑在工具调用的同步路径上，每次调用都会问一遍
        registry.policyOf("ghost");
        registry.policyOf("ghost");

        // Then：只报一条，否则事件流会被刷成噪音
        ArgumentCaptor<ConfigWarningEvent> captor = ArgumentCaptor.forClass(ConfigWarningEvent.class);
        verify(events).publish(captor.capture());
        assertEquals("ghost", captor.getValue().getSource());
        assertTrue(captor.getValue().getMessage().contains("不受限"));
    }

    @Test
    void policyOf_should_not_warn_when_agent_id_null() {
        // Given：null 是「没绑 agent」，不是「绑了一个不存在的 agent」
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);

        // When
        assertTrue(registry.policyOf(null).isEmpty());

        // Then
        verifyNoInteractions(events);
    }

    @Test
    void refresh_should_reset_unknown_id_warning() {
        // Given：先撞上一次未声明标识
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);
        registry.policyOf("ghost");
        verify(events).publish(any(ConfigWarningEvent.class));

        // When：配置改过之后再问同一个标识
        registry.refresh(settings(definition(CODER, null)), null);
        registry.policyOf("ghost");

        // Then：要再报一次——「上一轮报过」不能变成新一轮的免报理由
        verify(events, times(2)).publish(any(ConfigWarningEvent.class));
    }

    @Test
    void policyOf_should_stop_warning_per_id_when_tooMany_unknown_ids() {
        // Given：未声明标识可以来自会话参数，去重表不能是一个可被外部推动增长的集合
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);

        // When
        for (int i = 0; i < 200; i++) {
            registry.policyOf("ghost-" + i);
        }

        // Then：64 条逐条告警 + 1 条「其余不再逐条告警」
        verify(events, times(65)).publish(any(ConfigWarningEvent.class));
    }

    @Test
    void refresh_should_tolerate_null_settings_and_null_system_agent() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);

        // When
        registry.refresh(null, null);

        // Then
        assertTrue(registry.all().isEmpty());
        verify(events, never()).publish(any());
    }

    @Test
    void all_should_return_unmodifiable_collection() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);
        registry.refresh(settings(definition(CODER, null)), null);

        // When / Then
        assertThrows(UnsupportedOperationException.class, () -> registry.all().add(definition("other", null)));
    }

    @Test
    void refresh_should_not_warn_when_all_entries_valid() {
        // Given
        AgentRegistry registry = new AgentRegistry(events);

        // When
        registry.refresh(settings(definition(CODER, null), definition("writer", null)), null);

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
     * 构造 agent 定义。
     *
     * @param agentId     agent 标识，可为 {@code null}
     * @param permissions 权限段，可为 {@code null}
     * @return agent 定义
     */
    private static AgentDefinition definition(String agentId, AgentPermissions permissions) {
        return new AgentDefinition(agentId, "desc", permissions);
    }

    /**
     * 构造内置默认 agent。
     *
     * @param agentId 内置 agent 标识
     * @return 内置 agent 定义
     */
    private static AgentDefinition systemAgent(String agentId) {
        return new AgentDefinition(agentId, "系统默认 agent", null).withSystemPrompt("system");
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
