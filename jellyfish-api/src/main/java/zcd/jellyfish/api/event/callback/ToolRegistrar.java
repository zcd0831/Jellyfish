package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;

/**
 * 工具注册入口（扩展点 {@code tool.provide}）。
 * <p>
 * 插件只看到本接口，不接触通道参数：唯一性 / 空表策略 / 结果数量 / 执行模式全部由扩展点定义决定，
 * 插件唯一可声明的通道参数是 {@code order}。
 *
 * @author zcd
 */
public interface ToolRegistrar {

    /**
     * 注册工具处理器。
     *
     * @param toolName 工具名，也是路由键，不可为空
     * @param handler  工具处理器
     * @param options  注册选项，覆盖语义与顺序在此声明
     * @return 注册句柄，插件卸载时可用于提前解除
     */
    Subscription register(String toolName, CallbackHandler<ToolCallRequest, ToolCallResult> handler,
                          RegisterOptions options);

    /**
     * 以默认选项注册工具处理器。
     *
     * @param toolName 工具名，不可为空
     * @param handler  工具处理器
     * @return 注册句柄
     */
    default Subscription register(String toolName, CallbackHandler<ToolCallRequest, ToolCallResult> handler) {
        return register(toolName, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 以指定顺序注册工具处理器。
     *
     * @param toolName 工具名，不可为空
     * @param handler  工具处理器
     * @param order    调用顺序
     * @return 注册句柄
     */
    default Subscription register(String toolName, CallbackHandler<ToolCallRequest, ToolCallResult> handler, int order) {
        return register(toolName, handler, RegisterOptions.order(order));
    }
}
