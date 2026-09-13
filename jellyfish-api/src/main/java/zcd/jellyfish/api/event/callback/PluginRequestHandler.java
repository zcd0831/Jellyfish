package zcd.jellyfish.api.event.callback;

/**
 * 具名命令处理器，等价于 {@code CallbackHandler<PluginRequest, Object>}，为插件作者提供最简签名。
 *
 * @author zcd
 */
@FunctionalInterface
public interface PluginRequestHandler {

    /**
     * 处理具名命令。
     *
     * @param callback 回调对象，只读
     * @return 回调结果，不允许为 {@code null}
     * @throws Exception 处理失败时抛出
     */
    Object handle(PluginRequest callback) throws Exception;
}
