package zcd.jellyfish.api.event.command;

/**
 * 具名命令处理器，等价于 {@code CommandHandler<PluginCommand, Object>}，为插件作者提供最简签名。
 *
 * @author zcd
 */
@FunctionalInterface
public interface PluginCommandHandler {

    /**
     * 处理具名命令。
     *
     * @param command 命令对象，只读
     * @return 命令结果，不允许为 {@code null}
     * @throws Exception 处理失败时抛出
     */
    Object handle(PluginCommand command) throws Exception;
}
