package zcd.jellyfish.infra.event;

import com.google.common.eventbus.Subscribe;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.CommandHandler;
import zcd.jellyfish.api.event.command.PermissionCheckCommand;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.command.ToolCallCommand;
import zcd.jellyfish.infra.event.command.CommandRegistry;

/**
 * 命令分发器：Guava 层的唯一命令入口。
 * <p>
 * 每类核心命令一个 {@code @Subscribe} 方法，内部统一走 {@link #invoke(Command)}：
 * 查细粒度注册表 → 执行处理器 → 回填应答槽。这样把「Guava 的 {@code @Subscribe} 只能返回 void」
 * 这道边界关在框架代码里，插件代码永远是 {@code return / throw}。
 * <p>
 * 新增核心命令类型时，必须在这里显式加一个方法——这是有意保留的登记点，可 review、可加约定、可加指标。
 *
 * @author zcd
 */
final class CommandDispatcher {

    /** 细粒度命令注册表。 */
    private final CommandRegistry commandRegistry;

    /** 命令应答槽。 */
    private final CommandReplies commandReplies;

    /** 指标。 */
    private final EventBusStats stats;

    /**
     * 构造命令分发器。
     *
     * @param commandRegistry 细粒度命令注册表
     * @param commandReplies  命令应答槽
     * @param stats           指标
     */
    CommandDispatcher(CommandRegistry commandRegistry, CommandReplies commandReplies, EventBusStats stats) {
        this.commandRegistry = commandRegistry;
        this.commandReplies = commandReplies;
        this.stats = stats;
    }

    /**
     * 派发工具调用命令。
     *
     * @param command 工具调用命令
     */
    @Subscribe
    void onToolCall(ToolCallCommand command) {
        invoke(command);
    }

    /**
     * 派发权限检查命令。
     *
     * @param command 权限检查命令
     */
    @Subscribe
    void onPermissionCheck(PermissionCheckCommand command) {
        invoke(command);
    }

    /**
     * 派发插件自定义命令。
     *
     * @param command 插件命令
     */
    @Subscribe
    void onPluginCommand(PluginCommand command) {
        invoke(command);
    }

    /**
     * 查询注册表并执行处理器，捕获处理器异常回填为命令失败。
     *
     * @param command 命令对象
     * @param <R>     结果类型
     */
    private <R> void invoke(Command<R> command) {
        CommandHandler<Command<R>, R> handler = resolve(command);
        if (handler == null) {
            return;
        }
        try {
            Object result = handler.handle(command);
            commandReplies.complete(command, result);
            stats.dispatchedCommands.increment();
        } catch (Exception e) {
            commandReplies.fail(command, e);
            stats.failedCommands.increment();
        }
    }

    /**
     * 解析命令处理器，解析失败时把错误码回填到应答槽并记账。
     *
     * @param command 命令对象
     * @param <R>     结果类型
     * @return 命中的处理器；解析失败时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private <R> CommandHandler<Command<R>, R> resolve(Command<R> command) {
        try {
            return (CommandHandler<Command<R>, R>) commandRegistry.resolve(command);
        } catch (CommandException e) {
            commandReplies.fail(command, e);
            stats.failedCommands.increment();
            if (e.getCode() == CommandException.Code.NO_HANDLER) {
                stats.noHandlerCommands.increment();
            } else if (e.getCode() == CommandException.Code.AMBIGUOUS_HANDLER) {
                stats.ambiguousHandlerCommands.increment();
            }
            return null;
        }
    }
}
