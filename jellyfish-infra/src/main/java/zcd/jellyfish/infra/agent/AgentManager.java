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
 * <b>默认 agent 是内置的，不来自用户配置</b>：一切会话创建都绑随构件发布的系统 agent，
 * 想用自定义 agent 只能显式 {@code /agent} 切换。因此 {@link #resolveDefault()} 在内置定义缺失时
 * 不再返回 {@code null}——那是启动期就该拖住的错误，已由 {@code RuntimeConfig} 直接抛错。
 * <p>
 * <b>fail-open 的边界</b>：引用到未被声明的 {@code agentId} 时，{@link #policyOf(String)} 返回
 * {@link PermissionPolicy#unrestricted()}（放行），与 permission 方案的 fail-open 口径一致；
 * 需要「硬失败」的调用点（{@code /agent} 切换命令）改用 {@link #require(String)}。
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
     * 恒为内置默认 agent：它随构件发布、不受用户 {@code agents.json} 影响，因此不存在
     * 「未配置就没 agent 可用」这种退化状态，也不需要「第一个 agent」这类候选顺序规则。
     * <p>
     * 调用点是 {@code SessionManager.create(...)}：会话创建时用它绑定 agentId。
     * 不缓存结果——快照每次 {@code refresh()} 整体替换，新建的会话按当次快照绑定。
     *
     * @return 内置默认 agent 定义；尚未加载配置快照时为 {@code null}
     */
    public AgentDefinition resolveDefault() {
        return runtimeConfig.getSystemAgent();
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
     * 获取内置默认 agent 的标识。
     *
     * @return 内置默认 agent 的 agentId；尚未加载配置快照时为 {@code null}
     */
    public String getDefaultAgentId() {
        AgentDefinition systemAgent = runtimeConfig.getSystemAgent();
        return systemAgent == null ? null : systemAgent.getAgentId();
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
        registry.refresh(runtimeConfig.getAgentSettings(), runtimeConfig.getSystemAgent());
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
            events.publish(new AgentsLoadedEvent(getDefaultAgentId(), agentIds));
        } catch (RuntimeException e) {
            LOG.warn("agent 装载事件发布失败", e);
        }
    }
}
