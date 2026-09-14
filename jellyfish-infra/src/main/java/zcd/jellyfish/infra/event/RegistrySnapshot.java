package zcd.jellyfish.infra.event;

import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

/**
 * 注册表诊断快照：回答「这个插件注册了什么」。
 * <p>
 * 供启动日志与 TUI 的 {@code /plugins} 视图使用，这是两层注册表结构相较裸用 Guava 最大的运维收益。
 * 快照在创建时刻即固定文本内容，不影响后续注册行为。
 *
 * @author zcd
 */
public final class RegistrySnapshot {

    /** 回调注册视图。 */
    private final String callbacks;

    /** 通知订阅视图。 */
    private final String notifications;

    /**
     * 构造快照。
     *
     * @param callbacks     回调注册视图
     * @param notifications 通知订阅视图
     */
    private RegistrySnapshot(String callbacks, String notifications) {
        this.callbacks = callbacks;
        this.notifications = notifications;
    }

    /**
     * 从两张表生成快照。
     *
     * @param callbackRegistry 回调注册表
     * @param eventRegistry    通知注册表
     * @return 诊断快照
     */
    public static RegistrySnapshot of(CallbackRegistry callbackRegistry, EventRegistry eventRegistry) {
        return new RegistrySnapshot(callbackRegistry.render(), eventRegistry.render());
    }

    /**
     * 判断快照是否为空。
     *
     * @return 两张表都没有内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return callbacks.isEmpty() && notifications.isEmpty();
    }

    /**
     * 渲染为多行文本。
     *
     * @return 可读快照
     */
    public String render() {
        if (isEmpty()) {
            return "(no callback or notification registered)";
        }
        return callbacks + notifications;
    }
}
