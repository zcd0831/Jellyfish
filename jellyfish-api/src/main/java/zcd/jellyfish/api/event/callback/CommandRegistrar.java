package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;

/**
 * 具名命令注册入口（扩展点 {@code command.provide}）。
 * <p>
 * 插件只看到本接口，不接触通道参数：唯一性 / 空表策略 / 结果数量 / 执行模式全部由扩展点定义决定，
 * 插件唯一可声明的通道参数是 {@code order}。
 *
 * @author zcd
 */
public interface CommandRegistrar {

    /**
     * 注册具名命令处理器。
     *
     * @param name    命令名，也是路由键，不可为空
     * @param handler 命令处理器
     * @param options 注册选项，覆盖语义与顺序在此声明
     * @return 注册句柄，插件卸载时可用于提前解除
     */
    Subscription register(String name, PluginRequestHandler handler, RegisterOptions options);

    /**
     * 以默认选项注册具名命令处理器。
     *
     * @param name    命令名，不可为空
     * @param handler 命令处理器
     * @return 注册句柄
     */
    default Subscription register(String name, PluginRequestHandler handler) {
        return register(name, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 以指定顺序注册具名命令处理器。
     *
     * @param name    命令名，不可为空
     * @param handler 命令处理器
     * @param order   调用顺序
     * @return 注册句柄
     */
    default Subscription register(String name, PluginRequestHandler handler, int order) {
        return register(name, handler, RegisterOptions.order(order));
    }
}
