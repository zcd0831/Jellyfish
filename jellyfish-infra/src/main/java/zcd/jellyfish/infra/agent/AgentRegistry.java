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
 * 两张表加一个默认值由 {@link #refresh(AgentSettings)} 一次性整体重建：定义表直接来自
 * {@code agents.json}，策略表是定义表到 {@link PermissionPolicy} 的一次性转换结果。
 * <b>转换放在刷新期做</b>，是为了让 {@code policyOf} 退化成一次 map 取值——工具调用是高频路径，
 * 每次调用都复制三组工具名集合没有必要。
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

    /** 默认 agentId，未配置或配置为空白时为 {@code null}。 */
    private volatile String defaultAgentId;

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
     * 用最新配置整体重建索引。
     * <p>
     * 空白 key 与空定义被跳过并告警；没有任何条目时两张表都为空，这是合法状态（全员 fail-open）。
     *
     * @param settings 合并后的 agent 配置，可为 {@code null}（按空处理）
     */
    public synchronized void refresh(AgentSettings settings) {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        Map<String, PermissionPolicy> policies = new LinkedHashMap<>();
        if (settings != null) {
            index(settings, definitions, policies);
        }
        // 先建完两张表再一次性发布：读取方要么看到新定义与新策略，要么看到旧定义与旧策略
        this.definitionsById = Collections.unmodifiableMap(definitions);
        this.policiesById = Collections.unmodifiableMap(policies);
        this.defaultAgentId = settings == null || StringUtils.isBlank(settings.getDefaultAgent())
                ? null
                : settings.getDefaultAgent();
    }

    /**
     * 逐条索引配置里的 agent：非法条目跳过并告警。
     *
     * @param settings    合并后的 agent 配置
     * @param definitions 定义表收集目标
     * @param policies    策略表收集目标
     */
    private void index(AgentSettings settings, Map<String, AgentDefinition> definitions,
                       Map<String, PermissionPolicy> policies) {
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
     * 获取默认 agentId。
     *
     * @return 配置声明的默认 agentId，未配置时为 {@code null}
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 获取全部定义。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}，顺序与配置一致
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
