package zcd.jellyfish.infra.event;

import zcd.jellyfish.infra.event.notification.EventRegistry;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

/**
 * 注册表诊断快照：回答「这个插件注册了什么」。
 * <p>
 * 供启动日志与 TUI 的 {@code /plugins} 视图使用。
 * 快照在创建时刻即固定文本内容，不影响后续注册行为。
 *
 * @author zcd
 */
public final class RegistrySnapshot {

    /** 扩展点注册视图。 */
    private final String handlers;

    /** 通知订阅视图。 */
    private final String notifications;

    /**
     * 构造快照。
     *
     * @param handlers      扩展点注册视图
     * @param notifications 通知订阅视图
     */
    private RegistrySnapshot(String handlers, String notifications) {
        this.handlers = handlers;
        this.notifications = notifications;
    }

    /**
     * 生成快照：扩展点视图来自唯一一份注册表，通知视图仍来自过渡期的通知注册表。
     *
     * @param extensions   同步扩展点策略
     * @param eventRegistry 通知注册表
     * @return 诊断快照
     */
    public static RegistrySnapshot of(ExtensionRegistry extensions, EventRegistry eventRegistry) {
        return new RegistrySnapshot(extensions.snapshot().render(), eventRegistry.render());
    }

    /**
     * 判断快照是否为空。
     *
     * @return 两张表都没有内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return handlers.isEmpty() && notifications.isEmpty();
    }

    /**
     * 渲染为多行文本。
     *
     * @return 可读快照
     */
    public String render() {
        if (isEmpty()) {
            return "(no extension handler or notification registered)";
        }
        return handlers + notifications;
    }
}
