package zcd.jellyfish.infra.agent;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.AgentPermissions;
import zcd.jellyfish.infra.config.AgentSettings;
import zcd.jellyfish.infra.permission.PermissionPolicy;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/**
 * agent 定义与权限策略的只读索引。
 * <p>
 * 两张表由一个内置默认 agent 加一份双源合并后的用户配置一次性重建：定义表直接来自
 * {@code default-agent.json} 与 {@code agents.json}，策略表是定义表到 {@link PermissionPolicy}
 * 的一次性转换结果。<b>转换放在刷新期做</b>，是为了让 {@code policyOf} 退化成一次 map 取值——
 * 工具调用是高频路径，每次调用都复制三组工具名集合没有必要。
 * <p>
 * <b>内置默认 agent 不会被同名自定义条目覆盖</b>：它保证「进来就有 agent 可用」这个不变量，
 * 而用户配置可以随项目变化；若允许同名覆盖，同一份配置在不同项目下会得到不同的默认身份。
 * 因此冲突时保留内置定义并告警，让用户把自定义 agent 换个名字。
 * <p>
 * 与 {@code ModelRegistry} 同款：用 volatile 字段发布不可变映射，刷新在 {@code synchronized} 下
 * 整体重建，因此不会残留上一次配置里的条目。判定只需要读其中一张表，不存在跨表读到半更新状态的问题。
 * <p>
 * <b>本类自己发配置告警</b>：索引的职责就是决定「哪些条目能成为索引项」，非法条目（空白 key）在这里
 * 被跳过并告警，理由与 {@code ReadOnlyTools} 对非法白名单声明的处理一致。
 *
 * @author zcd
 */
@Singleton
public final class AgentRegistry {

    /** 事件发布入口，用于广播配置告警。 */
    private final EventPublisher events;

    /** agentId → 定义，顺序与配置一致。 */
    private volatile Map<String, AgentDefinition> definitionsById = Collections.emptyMap();

    /** agentId → 权限策略，与定义表同步重建。 */
    private volatile Map<String, PermissionPolicy> policiesById = Collections.emptyMap();

    /**
     * 构造索引。
     *
     * @param events 事件发布入口，用于广播配置告警
     */
    @Inject
    public AgentRegistry(EventPublisher events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 用内置默认 agent 与最新用户配置整体重建索引。
     * <p>
     * 空白 key 与空定义被跳过并告警；用户配置里与内置 agent 同名的条目被跳过并告警；
     * 两者都不存在时两张表都为空，此时已属启动期失败（内置定义缺失），不在这里处理。
     *
     * @param settings    合并后的用户 agent 配置，可为 {@code null}（按空处理）
     * @param systemAgent 内置默认 agent，可为 {@code null}（未加载配置时）
     */
    public synchronized void refresh(AgentSettings settings, AgentDefinition systemAgent) {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        Map<String, PermissionPolicy> policies = new LinkedHashMap<>();
        String systemAgentId = indexSystemAgent(systemAgent, definitions, policies);
        if (settings != null) {
            index(settings, definitions, policies, systemAgentId);
        }
        // 先建完两张表再一次性发布：读取方要么看到新定义与新策略，要么看到旧定义与旧策略
        this.definitionsById = Collections.unmodifiableMap(definitions);
        this.policiesById = Collections.unmodifiableMap(policies);
    }

    /**
     * 把内置默认 agent 作为第一个索引项写入两张表。
     *
     * @param systemAgent 内置默认 agent，可为 {@code null}
     * @param definitions 定义表收集目标
     * @param policies    策略表收集目标
     * @return 内置 agent 的标识；未提供或标识为空白时返回 {@code null}
     */
    private static String indexSystemAgent(AgentDefinition systemAgent, Map<String, AgentDefinition> definitions,
                                           Map<String, PermissionPolicy> policies) {
        if (systemAgent == null || StringUtils.isBlank(systemAgent.getAgentId())) {
            return null;
        }
        definitions.put(systemAgent.getAgentId(), systemAgent);
        policies.put(systemAgent.getAgentId(), toPolicy(systemAgent));
        return systemAgent.getAgentId();
    }

    /**
     * 逐条索引用户配置里的 agent：非法条目与同名冲突跳过并告警。
     *
     * @param settings      合并后的用户 agent 配置
     * @param definitions   定义表收集目标
     * @param policies      策略表收集目标
     * @param systemAgentId 内置默认 agent 的标识，可为 {@code null}
     */
    private void index(AgentSettings settings, Map<String, AgentDefinition> definitions,
                       Map<String, PermissionPolicy> policies, String systemAgentId) {
        for (Map.Entry<String, AgentDefinition> entry : settings.getAgents().entrySet()) {
            String agentId = entry.getKey();
            AgentDefinition definition = entry.getValue();
            if (StringUtils.isBlank(agentId)) {
                warn(agentId, "agents 段存在空白 key，已跳过该条目");
                continue;
            }
            if (definition == null) {
                warn(agentId, "agent 定义为 null，已跳过该条目");
                continue;
            }
            if (agentId.equals(systemAgentId)) {
                warn(agentId, "与内置默认 agent 同名，已忽略该自定义条目");
                continue;
            }
            definitions.put(agentId, definition);
            policies.put(agentId, toPolicy(definition));
        }
    }

    /**
     * 按 agentId 查找定义。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 定义；{@code null} 或未声明的标识返回 {@code null}
     */
    public AgentDefinition find(String agentId) {
        return agentId == null ? null : definitionsById.get(agentId);
    }

    /**
     * 按 agentId 取权限策略。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 策略；未命中时返回 {@link PermissionPolicy#unrestricted()}，恒非 {@code null}
     */
    public PermissionPolicy policyOf(String agentId) {
        PermissionPolicy policy = agentId == null ? null : policiesById.get(agentId);
        return policy == null ? PermissionPolicy.unrestricted() : policy;
    }

    /**
     * 获取全部定义。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}，内置默认 agent 排在最前
     */
    public Collection<AgentDefinition> all() {
        return Collections.unmodifiableCollection(definitionsById.values());
    }

    /**
     * 把权限段转换成内核判定用的策略。
     * <p>
     * 空白项与重复项由 {@link PermissionPolicy#of} 统一处理，这里不重复过滤——配置写错不该被悄悄修好。
     *
     * @param definition agent 定义
     * @return 权限策略；未声明任何授权时返回 {@link PermissionPolicy#unrestricted()}
     */
    private static PermissionPolicy toPolicy(AgentDefinition definition) {
        AgentPermissions permissions = definition.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return PermissionPolicy.unrestricted();
        }
        return PermissionPolicy.of(new LinkedHashSet<>(permissions.getDeniedTools()),
                new LinkedHashSet<>(permissions.getAskTools()),
                new LinkedHashSet<>(permissions.getAllowedTools()));
    }

    /**
     * 广播一条配置告警。
     *
     * @param agentId agent 标识，作为配置来源，可为 {@code null}
     * @param message 告警描述
     */
    private void warn(String agentId, String message) {
        events.publish(new ConfigWarningEvent(agentId, message));
    }
}
