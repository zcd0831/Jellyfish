package zcd.jellyfish.api.event.command;

import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;

/**
 * 命令注册入口：对插件暴露的细粒度命令注册能力。
 * <p>
 * 命令是「恰好一个处理器」语义，因此注册时即校验键冲突与越权，冲突在插件加载期暴露而不是首次调用时。
 *
 * @author zcd
 */
public interface CommandRegistrar {

    /**
     * 注册具名命令（{@link PluginCommand} 载体）。
     *
     * @param name    命令名，不可为空
     * @param handler 命令处理器
     * @param options 注册选项，覆盖语义在此声明
     * @return 注册句柄，插件卸载时可用于提前解除
     */
    Subscription register(String name, PluginCommandHandler handler, RegisterOptions options);

    /**
     * 注册指定命令类型下某个路由键的处理器。仅对标记 {@link PluginExtensible} 的命令类型开放。
     *
     * @param commandType 命令类型
     * @param routeKey    路由键，与 {@link Command#getRouteKey()} 对应，{@code null} 表示类型唯一
     * @param handler     命令处理器
     * @param options     注册选项
     * @param <C>         命令类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    <C extends Command<R>, R> Subscription register(Class<C> commandType, String routeKey,
                                                    CommandHandler<C, R> handler, RegisterOptions options);

    /**
     * 以默认选项注册具名命令。
     *
     * @param name    命令名，不可为空
     * @param handler 命令处理器
     * @return 注册句柄
     */
    default Subscription register(String name, PluginCommandHandler handler) {
        return register(name, handler, RegisterOptions.DEFAULT);
    }
}
