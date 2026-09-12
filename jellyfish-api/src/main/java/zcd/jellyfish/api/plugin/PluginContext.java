package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.EventRegistrar;
import zcd.jellyfish.api.event.command.CommandRegistrar;

/**
 * 插件能力上下文：插件与核心交互的唯一入口。
 * <p>
 * 插件不感知事件总线实现、不依赖 Guava、也不需要自行反注册——卸载时由框架按 pluginId 批量回收。
 *
 * @author zcd
 */
public interface PluginContext {

    /**
     * 获取命令注册入口。
     *
     * @return 命令注册入口
     */
    CommandRegistrar commands();

    /**
     * 获取通知订阅入口。
     *
     * @return 通知订阅入口
     */
    EventRegistrar events();

    /**
     * 获取通知发布入口。
     *
     * @return 通知发布入口
     */
    EventPublisher publisher();
}
