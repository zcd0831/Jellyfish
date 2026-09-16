package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 配置重载完成事件：{@code ConfigReloader} 重新读取并应用全部配置段后广播。
 * <p>
 * <b>为什么需要它</b>：配置热更新会重建模型 / agent 索引、并按新配置重启部分插件，
 * 这是一次成功的变更而不是一次失败告警（失败走日志与命令返回的 {@code ERROR}），
 * 因此需要一条可观察的「刚刚发生了什么」。
 * <p>
 * <b>携带的是「结果」而不是「配置内容」</b>：订阅方（指标、界面提示）关心的是
 * 「重载了几次、重启了哪些插件、花了多久」，配置本身由各域服务持有。
 * <p>
 * 经 {@code EventChannel} 异步派发，属 best-effort，可丢弃。
 *
 * @author zcd
 */
public final class ConfigReloadedEvent extends AbstractJellyfishEvent {

    /** 本次被重启（停止后重新启动）的插件标识，按配置段变更者计。 */
    private final Set<String> restartedPluginIds;

    /** 本次重载耗时（毫秒）。 */
    private final long durationMillis;

    /**
     * 构造进程级事件。
     *
     * @param restartedPluginIds 本次被重启的插件标识，可为 {@code null}
     * @param durationMillis     重载耗时（毫秒）
     */
    public ConfigReloadedEvent(Set<String> restartedPluginIds, long durationMillis) {
        super(null);
        this.restartedPluginIds = restartedPluginIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(restartedPluginIds));
        this.durationMillis = durationMillis;
    }

    /**
     * 获取本次被重启的插件标识。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Set<String> getRestartedPluginIds() {
        return restartedPluginIds;
    }

    /**
     * 获取重载耗时。
     *
     * @return 耗时（毫秒）
     */
    public long getDurationMillis() {
        return durationMillis;
    }
}
