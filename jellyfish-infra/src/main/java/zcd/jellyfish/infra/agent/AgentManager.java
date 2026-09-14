package zcd.jellyfish.infra.agent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.AgentsLoadedEvent;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.permission.PermissionPolicy;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 门面：从配置装载定义，按 agentId 提供系统提示词与权限策略。
 * <p>
 * 与 {@code ModelManager} 同构：<b>构造期只建（空）索引</b>，真正的装载发生在
 * {@code AgentHarness.bootstrap()} 中 {@code runtimeConfig.refresh()} 之后——{@code RuntimeConfig}
 * 构造时不读文件，构造期拿到的必然是本进程刚启动时的空快照。
 * <p>
 * <b>fail-open 的边界</b>：{@code agentId} 为空或未被配置声明，就引用不到任何 agent，
 * {@link #policyOf(String)} 返回 {@link PermissionPolicy#unrestricted()}（放行），与 permission 方案的
 * fail-open 口径一致；需要「硬失败」的调用点（将来的 /agent 切换命令）改用 {@link #require(String)}。
 * <p>
 * <b>本类不接触扩展层</b>：不知道工具是否存在、不感知插件与提示词拼装。提示词只提供原文，
 * 拼接与模板处理归 {@code core/prompt}。
 *
 * @author zcd
 */
@Singleton
public class AgentManager implements PermissionPolicyProvider {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentManager.class);

    /** 运行时配置门面，提供合并后的 agent 配置。 */
    private final RuntimeConfig runtimeConfig;

    /** agent 定义与策略索引。 */
    private final AgentRegistry registry;

    /** 通知发布入口，用于广播装载事件。 */
    private final EventPublisher events;

    /**
     * 构造时建立索引。
     * <p>
     * 构造期只重建索引、不广播 {@link AgentsLoadedEvent}：此刻配置尚未加载（索引必为空），
     * 事件总线也可能还没启动，发出去只会是一条被缓冲重放的假事件。
     *
     * @param runtimeConfig 运行时配置门面
     * @param registry      agent 定义与策略索引
     * @param events        通知发布入口
     */
    @Inject
    public AgentManager(RuntimeConfig runtimeConfig, AgentRegistry registry, EventPublisher events) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        rebuild(false);
    }

    /**
     * 重建 agent 索引，并在重建后广播 {@link AgentsLoadedEvent}。
     * <p>
     * 事件表示「索引已重建」而不是「配置发生了变更」：本轮不做新旧快照 diff，因此首次装载与热更新
     * 发出的是同一种事件，且内容可以为空（「一个 agent 都没配」同样必须可见）。
     * 发布属 best-effort，失败不影响索引重建结果。
     *
     * @param reloadConfig 是否先重新读取配置文件（配置热更新时传 {@code true}）
     */
    public void refresh(boolean reloadConfig) {
        rebuild(reloadConfig);
        publishLoaded();
    }

    /**
     * 按 agentId 查找定义。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 定义，未命中返回 {@code null}
     */
    public AgentDefinition find(String agentId) {
        return registry.find(agentId);
    }

    /**
     * 按 agentId 查找定义，未命中即失败。
     *
     * @param agentId agent 标识，不可为空白
     * @return 定义
     * @throws JellyfishException 标识为空白，或未被任何 agent 声明时抛出
     */
    public AgentDefinition require(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            throw new JellyfishException("agentId must not be blank");
        }
        AgentDefinition definition = registry.find(agentId);
        if (definition == null) {
            throw new JellyfishException("agent not found: " + agentId);
        }
        return definition;
    }

    /**
     * 解析「会话创建时应绑定的默认 agent」。
     * <p>
     * 三档：① 配置了 {@code defaultAgent} 且该 agent 存在 → 它；② 否则取第一个 agent（配置顺序）；
     * ③ 一个 agent 都没有 → 返回 {@code null}。
     * <p>
     * <b>与 {@code ModelManager.resolveDefault()} 的唯一差别</b>：没有 agent 时返回 {@code null}
     * 而不是抛异常。「一个 agent 都没配」是合法状态（全员 fail-open），不需要让会话创建失败。
     * <p>
     * TODO 会话级自动绑定未落地：{@code SessionManager} 尚未实现，本方法目前没有任何调用点。
     *      待它落地后在「创建会话」那一步调用本方法并把结果写入会话的当前 agentId；
     *      刻意<b>不</b>在 {@code SessionManager} 里写占位实现，避免出现一个永远不被执行的假接线。
     *
     * @return 默认 agent 定义，无任何 agent 时返回 {@code null}
     */
    public AgentDefinition resolveDefault() {
        String defaultAgentId = registry.getDefaultAgentId();
        if (StringUtils.isNotBlank(defaultAgentId)) {
            AgentDefinition configured = registry.find(defaultAgentId);
            if (configured != null) {
                return configured;
            }
        }
        for (AgentDefinition definition : registry.all()) {
            if (definition != null) {
                return definition;
            }
        }
        return null;
    }

    /**
     * 取系统提示词原文。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 提示词原文；未命中或该 agent 未配置提示词时返回 {@code null}
     */
    public String systemPromptOf(String agentId) {
        AgentDefinition definition = registry.find(agentId);
        return definition == null ? null : definition.getSystemPrompt();
    }

    /**
     * 获取配置声明的默认 agentId。
     *
     * @return 默认 agentId，未配置时为 {@code null}
     */
    public String getDefaultAgentId() {
        return registry.getDefaultAgentId();
    }

    /**
     * 获取全部 agent 定义。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Collection<AgentDefinition> all() {
        return registry.all();
    }

    /**
     * 取某个 agent 的权限策略。
     * <p>
     * 这是 permission 模块的窄接口实现：未命中一律返回 {@link PermissionPolicy#unrestricted()}，
     * 因此调用方不需要判空，「取不到策略就 fail-open」这条规则只有这一个落点。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 权限策略，保证非 {@code null}
     */
    @Override
    public PermissionPolicy policyOf(String agentId) {
        return registry.policyOf(agentId);
    }

    /**
     * 只重建索引、不广播事件。
     *
     * @param reloadConfig 是否先重新读取配置文件
     */
    private void rebuild(boolean reloadConfig) {
        if (reloadConfig) {
            runtimeConfig.refresh();
        }
        registry.refresh(runtimeConfig.getAgentSettings());
    }

    /**
     * 广播索引重建事件，发布失败只记日志。
     */
    private void publishLoaded() {
        try {
            Set<String> agentIds = new LinkedHashSet<>();
            for (AgentDefinition definition : registry.all()) {
                if (definition != null && StringUtils.isNotBlank(definition.getAgentId())) {
                    agentIds.add(definition.getAgentId());
                }
            }
            events.publish(new AgentsLoadedEvent(registry.getDefaultAgentId(), agentIds));
        } catch (RuntimeException e) {
            LOG.warn("agent 装载事件发布失败", e);
        }
    }
}
