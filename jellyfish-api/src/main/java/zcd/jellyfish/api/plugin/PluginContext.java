package zcd.jellyfish.api.plugin;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.EventRegistrar;
import zcd.jellyfish.api.event.callback.CommandRegistrar;
import zcd.jellyfish.api.event.callback.ToolRegistrar;

/**
 * 插件能力上下文：插件与核心交互的唯一入口。
 * <p>
 * 按「扩展点」分组暴露注册入口、按「能力」分组暴露能力：插件只看到实例层，
 * 不感知事件总线实现、不依赖 Guava、也不需要自行反注册——卸载时由框架按 pluginId 批量回收。
 *
 * @author zcd
 */
public interface PluginContext {

    /**
     * 获取工具注册入口（扩展点 {@code tool.provide}）。
     *
     * @return 工具注册入口
     */
    ToolRegistrar tools();

    /**
     * 获取具名命令注册入口（扩展点 {@code command.provide}）。
     *
     * @return 具名命令注册入口
     */
    CommandRegistrar commands();

    /**
     * 获取事件订阅入口。
     *
     * @return 事件订阅入口
     */
    EventRegistrar events();

    /**
     * 获取事件发布入口。
     *
     * @return 事件发布入口
     */
    EventPublisher publisher();
}
