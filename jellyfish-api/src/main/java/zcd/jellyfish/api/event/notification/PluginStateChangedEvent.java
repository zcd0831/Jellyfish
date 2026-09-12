package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 插件状态变更事件：插件启动 / 停止 / 卸载时广播，供诊断与 UI 展示。
 *
 * @author zcd
 */
public final class PluginStateChangedEvent extends AbstractJellyfishEvent {

    /** 插件标识。 */
    private final String pluginId;

    /** 状态名，取值由插件运行时定义（如 STARTED / STOPPED / DELETED）。 */
    private final String state;

    /**
     * 构造插件状态变更事件。
     *
     * @param pluginId 插件标识
     * @param state    状态名
     */
    public PluginStateChangedEvent(String pluginId, String state) {
        super(null);
        this.pluginId = pluginId;
        this.state = state;
    }

    /**
     * 获取插件标识。
     *
     * @return 插件标识
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * 获取状态名。
     *
     * @return 状态名
     */
    public String getState() {
        return state;
    }
}
