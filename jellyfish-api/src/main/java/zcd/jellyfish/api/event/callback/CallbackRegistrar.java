package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;

/**
 * 回调注册入口：对插件暴露的细粒度回调注册能力。
 * <p>
 * 唯一性由扩展点定义驱动：{@code unique=true}（B 提供）的回调在注册期校验键冲突与越权，
 * 冲突在插件加载期暴露而不是首次调用时；{@code unique=false}（A 贡献 / D 拦截 / E 策略）的回调天然允许 0..N 个处理器。
 * 插件只能声明 {@code order}，其余通道参数由扩展点定义决定。
 *
 * @author zcd
 */
public interface CallbackRegistrar {

    /**
     * 注册具名命令（{@link PluginRequest} 载体）。
     *
     * @param name    命令名，不可为空
     * @param handler 回调处理器
     * @param options 注册选项，覆盖语义在此声明
     * @return 注册句柄，插件卸载时可用于提前解除
     */
    Subscription register(String name, PluginRequestHandler handler, RegisterOptions options);

    /**
     * 注册指定回调类型下某个路由键的处理器。仅对标记 {@link PluginExtensible} 的回调类型开放。
     *
     * @param callbackType 回调类型
     * @param routeKey    路由键，与 {@link Callback#getRouteKey()} 对应，{@code null} 表示类型唯一
     * @param handler     回调处理器
     * @param options     注册选项
     * @param <C>         回调类型
     * @param <R>         结果类型
     * @return 注册句柄
     */
    <C extends Callback<R>, R> Subscription register(Class<C> callbackType, String routeKey,
                                                    CallbackHandler<C, R> handler, RegisterOptions options);

    /**
     * 以默认选项注册具名命令。
     *
     * @param name    命令名，不可为空
     * @param handler 回调处理器
     * @return 注册句柄
     */
    default Subscription register(String name, PluginRequestHandler handler) {
        return register(name, handler, RegisterOptions.DEFAULT);
    }
}
