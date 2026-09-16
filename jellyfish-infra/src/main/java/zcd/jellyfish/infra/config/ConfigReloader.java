package zcd.jellyfish.infra.config;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginReloadReport;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

/**
 * 配置热更新编排：把「重读文件」与「各索引 / 插件跟随」串成一条有顺序的路径。
 * <p>
 * <b>为什么需要单独的编排类</b>：热更新的顺序不是随意排列的，而每一处依赖关系都不该由调用点记住：
 * <ol>
 *     <li>{@link ModelManager#refresh(boolean)} 传 {@code true} 是唯一会重读配置文件的一步
 *     （它同时负责清理按旧 apiKey / baseUrl 建好的 LLM 客户端缓存）；</li>
 *     <li>{@link AgentManager#refresh(boolean)} 于是传 {@code false}，复用同一份新快照——
 *     若也传 {@code true}，配置文件会被读第二遍，并在两遍之间产生一个「模型用了新配置、agent 还在用旧配置」
 *     的窗口；</li>
 *     <li>{@link PluginRuntimeConfig#refresh} 必须在插件运行时重载之前：后者读的是它那份快照。</li>
 * </ol>
 * <b>配置段变化的判定在替换快照之前取旧值</b>：一旦插件运行时快照被刷新，旧值就没了。
 * <p>
 * <b>重载是单向的</b>：不做「失败回滚到上一份配置」。配置的真相在文件里，回滚只会制造
 * 「内存里的配置与文件不一致」这种更难排查的状态；失败原样上抛，由命令层告诉用户。
 * <p>
 * <b>单飞</b>：整个重载过程 {@code synchronized}。并发重载会让插件被停两次、启动两次，
 * 而配置重载本就是低频的人类动作，排队比并发更符合预期。
 *
 * @author zcd
 */
@Singleton
public final class ConfigReloader {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConfigReloader.class);

    /** 运行时配置门面。 */
    private final RuntimeConfig runtimeConfig;

    /** 模型门面：负责重读配置与重建模型索引。 */
    private final ModelManager modelManager;

    /** agent 门面。 */
    private final AgentManager agentManager;

    /** 插件运行时装配输入。 */
    private final PluginRuntimeConfig pluginRuntimeConfig;

    /** 插件运行时门面。 */
    private final PF4JPluginManager pluginManager;

    /** 通知发布入口，用于广播重载完成事件。 */
    private final EventPublisher events;

    /**
     * 构造重载器。
     *
     * @param runtimeConfig       运行时配置门面
     * @param modelManager        模型门面
     * @param agentManager        agent 门面
     * @param pluginRuntimeConfig 插件运行时装配输入
     * @param pluginManager       插件运行时门面
     * @param events              通知发布入口
     */
    @Inject
    public ConfigReloader(RuntimeConfig runtimeConfig, ModelManager modelManager, AgentManager agentManager,
                          PluginRuntimeConfig pluginRuntimeConfig, PF4JPluginManager pluginManager,
                          EventPublisher events) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.pluginRuntimeConfig = Objects.requireNonNull(pluginRuntimeConfig,
                "pluginRuntimeConfig must not be null");
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 重新读取并应用全部配置。
     * <p>
     * 顺序见类注释；插件侧只重启「配置段真的变了」的插件，名单变化由插件运行时统一收敛。
     *
     * @return 重载结果，保证非 {@code null}
     */
    public synchronized ReloadOutcome reload() {
        long startedAt = System.currentTimeMillis();
        Map<String, Map<String, Object>> before = pluginRuntimeConfig.getPluginConfigurations();

        modelManager.refresh(true);
        agentManager.refresh(false);
        pluginRuntimeConfig.refresh(runtimeConfig.getPluginRoots(), runtimeConfig.getPluginsSettings());

        Set<String> reconfigured = changedPluginIds(before,
                pluginRuntimeConfig.getPluginConfigurations());
        PluginReloadReport pluginReport = pluginManager.reload(reconfigured);

        long duration = System.currentTimeMillis() - startedAt;
        publishReloaded(pluginReport, duration);
        LOG.info("配置已重载: durationMillis={} reconfiguredPlugins={}", duration, reconfigured);
        return new ReloadOutcome(duration, pluginReport);
    }

    /**
     * 比较两份插件配置段，找出内容发生变化的插件。
     * <p>
     * 用整段相等对比而不是逐字段 diff：插件配置段是自由映射（键由插件自己定义），
     * 内核既不知道哪些键有语义，也无从判断「哪个字段更重要」。整段不等即重启，
     * 是唯一不需要内核理解插件配置的判据。
     *
     * @param before 刷新前的全部插件配置段
     * @param after  刷新后的全部插件配置段
     * @return 配置段内容变化的插件标识，保证非 {@code null}
     */
    private static Set<String> changedPluginIds(Map<String, Map<String, Object>> before,
                                                Map<String, Map<String, Object>> after) {
        Set<String> changed = new LinkedHashSet<String>();
        for (Map.Entry<String, Map<String, Object>> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String pluginId : before.keySet()) {
            if (!after.containsKey(pluginId)) {
                changed.add(pluginId);
            }
        }
        return changed;
    }

    /**
     * 广播重载完成事件，发布失败只记日志。
     * <p>
     * best-effort：重载本身已经成功，不可能因为一条通知发不出去而失败。
     *
     * @param report   插件侧变动报告
     * @param duration 重载耗时（毫秒）
     */
    private void publishReloaded(PluginReloadReport report, long duration) {
        try {
            events.publish(new ConfigReloadedEvent(report.touchedPluginIds(), duration));
        } catch (RuntimeException e) {
            LOG.warn("配置重载事件发布失败", e);
        }
    }
}
