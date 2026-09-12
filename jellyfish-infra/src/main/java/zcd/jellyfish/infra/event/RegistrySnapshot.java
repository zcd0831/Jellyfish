package zcd.jellyfish.infra.event;

import zcd.jellyfish.infra.event.command.CommandRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

/**
 * 注册表诊断快照：回答「这个命令谁提供 / 这个插件注册了什么」。
 * <p>
 * 供启动日志与 TUI 的 {@code /plugins} 视图使用，这是两层注册表结构相较裸用 Guava 最大的运维收益。
 * 快照在创建时刻即固定文本内容，不影响后续注册行为。
 *
 * @author zcd
 */
public final class RegistrySnapshot {

    /** 命令注册视图。 */
    private final String commands;

    /** 通知订阅视图。 */
    private final String notifications;

    /**
     * 构造快照。
     *
     * @param commands      命令注册视图
     * @param notifications 通知订阅视图
     */
    private RegistrySnapshot(String commands, String notifications) {
        this.commands = commands;
        this.notifications = notifications;
    }

    /**
     * 从两个注册表生成快照。
     *
     * @param commandRegistry 命令注册表
     * @param eventRegistry   通知注册表
     * @return 诊断快照
     */
    public static RegistrySnapshot of(CommandRegistry commandRegistry, EventRegistry eventRegistry) {
        return new RegistrySnapshot(commandRegistry.render(), eventRegistry.render());
    }

    /**
     * 判断快照是否为空。
     *
     * @return 两层注册表都没有内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return commands.isEmpty() && notifications.isEmpty();
    }

    /**
     * 渲染为多行文本。
     *
     * @return 可读快照
     */
    public String render() {
        if (isEmpty()) {
            return "(no command or notification registered)";
        }
        return commands + notifications;
    }
}
