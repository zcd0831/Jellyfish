package zcd.jellyfish.infra.plugin;

import org.pf4j.PluginDependency;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件管理器门面：内核侧唯一入口，负责启动期编排序与失败隔离。
 * <p>
 * 启动期流程固定为四步：
 * <ol>
 *     <li><b>加载</b>：{@code safeLoadPlugins()}，描述符解析与依赖解析；</li>
 *     <li><b>描述符体检</b>：描述符是否完整合法（{@code plugin.class}、版本约束、插件包内容），
 *     不通过者不启动并标记 {@code FAILED}；</li>
 *     <li><b>启动</b>：逐插件 {@code safeStart(id)}，失败即降级为「该插件不可用」；</li>
 *     <li><b>汇总告警</b>：未解析与被禁用的插件逐条 WARN，否则「插件没生效」会静默无痕。</li>
 * </ol>
 * <b>刻意不用 PF4J 的批量 {@code startPlugins()}</b>：体检不通过的插件必须<b>不</b>启动，
 * 而批量方法会启动全部已解析插件，绕开校验。逐插件启动同样保留 PF4J 的依赖先行语义。
 *
 * @author zcd
 */
public final class PF4JPluginManager implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PF4JPluginManager.class);

    /** 插件上下文工厂，用于创建插件上下文与按 owner 回收注册。 */
    private final PluginContextFactory contexts;

    /** 插件运行时装配输入。 */
    private final PluginRuntimeConfig runtimeConfig;

    /** 因描述符存在问题或依赖不可用而不启动的插件标识。 */
    private final Set<String> blocked = new LinkedHashSet<>();

    /** 内部管理器，启动时创建。 */
    private JellyfishPluginManager manager;

    /**
     * 构造门面。
     *
     * @param contexts      插件上下文工厂，不可为 {@code null}
     * @param runtimeConfig 装配输入，不可为 {@code null}
     */
    @Inject
    public PF4JPluginManager(PluginContextFactory contexts, PluginRuntimeConfig runtimeConfig) {
        this.contexts = Objects.requireNonNull(contexts, "contexts must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
    }

    /**
     * 启动插件运行时：加载 → 描述符体检 → 逐插件启动 → 汇总告警。
     * <p>
     * 只允许调用一次：重复启动会创建第二套插件类加载器，属于使用错误。
     *
     * @throws JellyfishException 已经启动过时抛出
     */
    public void bootstrap() {
        if (manager != null) {
            throw new JellyfishException("plugin manager already bootstrapped");
        }
        JellyfishPluginManager created = new JellyfishPluginManager(contexts, runtimeConfig);
        manager = created;
        created.safeLoadPlugins();
        rejectBrokenDescriptors(created);
        startResolved(created);
        warnUnavailable(created);
        LOG.info("插件运行时已启动: total={} started={} blocked={}",
                created.getPlugins().size(), created.getStartedPlugins().size(), blocked.size());
    }

    /**
     * 获取已发现的插件。
     *
     * @return 插件包装器列表；尚未启动时为空列表
     */
    public List<PluginWrapper> plugins() {
        return manager == null ? Collections.<PluginWrapper>emptyList() : manager.getPlugins();
    }

    /**
     * 获取指定插件的状态。
     *
     * @param pluginId 插件标识
     * @return 状态；插件不存在或尚未启动时为 {@code null}
     */
    public PluginState stateOf(String pluginId) {
        if (manager == null) {
            return null;
        }
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        return wrapper == null ? null : wrapper.getPluginState();
    }

    /**
     * 关闭插件运行时：逐个停止并回收注册，然后卸载全部插件。
     * <p>
     * 逐插件走 {@code safeStop}（停止 + 注册回收）而<b>不用</b> PF4J 的批量 {@code stopPlugins()}：
     * 批量方法不回收注册，会留下「已停止但工具还能调」的幽灵注册。
     */
    @Override
    public void close() {
        if (manager == null) {
            return;
        }
        JellyfishPluginManager closing = manager;
        manager = null;
        for (PluginWrapper wrapper : new ArrayList<>(closing.getStartedPlugins())) {
            closing.safeStop(wrapper.getPluginId());
        }
        for (PluginWrapper wrapper : new ArrayList<>(closing.getPlugins())) {
            closing.safeUnload(wrapper.getPluginId());
        }
        blocked.clear();
        LOG.info("插件运行时已关闭");
    }

    /**
     * 描述符体检：存在描述符层面问题的插件不启动，并标记为 {@code FAILED}。
     * <p>
     * 校验内容只剩「描述符本身是否完整合法」（缺少 {@code plugin.class}、版本约束非法、
     * 插件包自带内核契约等）——注册边界已由回调类型承载，不再有需要事前声明的清单。
     *
     * @param manager 内部管理器
     */
    private void rejectBrokenDescriptors(JellyfishPluginManager manager) {
        for (PluginWrapper wrapper : manager.getResolvedPlugins()) {
            String pluginId = wrapper.getPluginId();
            try {
                JellyfishPluginDescriptor descriptor = JellyfishPluginDescriptor.of(wrapper);
                if (descriptor.hasLoadErrors()) {
                    throw new JellyfishException("描述符存在问题: " + descriptor.getLoadErrors());
                }
            } catch (JellyfishException e) {
                LOG.error("插件未通过描述符体检，将不启动: pluginId={} reason={}", pluginId, e.getMessage());
                blocked.add(pluginId);
                manager.markFailed(pluginId, e);
            }
        }
        blockDependents(manager);
    }

    /**
     * 把依赖了被阻断插件的插件一并阻断（传递闭包）。
     * <p>
     * 必须做这一步：PF4J 的 {@code startPlugin(id)} 会先启动依赖，若只跳过被阻断插件本身，
     * 一个合法插件仍然能把它依赖的「非法插件」拉起来，校验就白做了。
     *
     * @param manager 内部管理器
     */
    private void blockDependents(JellyfishPluginManager manager) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PluginWrapper wrapper : manager.getResolvedPlugins()) {
                String pluginId = wrapper.getPluginId();
                if (blocked.contains(pluginId)) {
                    continue;
                }
                for (PluginDependency dependency : wrapper.getDescriptor().getDependencies()) {
                    if (dependency.isOptional() || !blocked.contains(dependency.getPluginId())) {
                        continue;
                    }
                    LOG.error("插件依赖不可用，将不启动: pluginId={} dependency={}", pluginId, dependency.getPluginId());
                    blocked.add(pluginId);
                    manager.markFailed(pluginId, new JellyfishException(
                            "依赖插件不可用: " + dependency.getPluginId()));
                    changed = true;
                    break;
                }
            }
        }
    }

    /**
     * 逐个启动通过校验的已解析插件。
     *
     * @param manager 内部管理器
     */
    private void startResolved(JellyfishPluginManager manager) {
        for (PluginWrapper wrapper : manager.getResolvedPlugins()) {
            String pluginId = wrapper.getPluginId();
            PluginState state = wrapper.getPluginState();
            if (state.isDisabled() || state.isStarted() || blocked.contains(pluginId)) {
                continue;
            }
            manager.safeStart(pluginId);
        }
    }

    /**
     * 汇总「加载了但没生效」的插件，逐条 WARN。
     * <p>
     * 没有这一步，被禁用与依赖未满足的插件就是静默不启动，排查时无从下手。
     *
     * @param manager 内部管理器
     */
    private void warnUnavailable(JellyfishPluginManager manager) {
        for (PluginWrapper wrapper : manager.getUnresolvedPlugins()) {
            LOG.warn("插件依赖未满足，未启动: pluginId={} requires={} version={}", wrapper.getPluginId(),
                    wrapper.getDescriptor().getRequires(), wrapper.getDescriptor().getVersion());
        }
        for (PluginWrapper wrapper : manager.getPlugins(PluginState.DISABLED)) {
            LOG.warn("插件被禁用或版本不满足，未启动: pluginId={} requires={}", wrapper.getPluginId(),
                    wrapper.getDescriptor().getRequires());
        }
    }
}
